// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.bench;

import dev.worldgennext.neoforge.loader.Names;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wall-clock chunk throughput driver.  A separate warmup square is generated
 * first; the measured square starts only after every warmup chunk completed.
 * Each request holds its own region ticket at exactly the requested status
 * level until its future completes, then releases it.  The numerator is the
 * number of measured chunks whose future completed successfully; failed or
 * unloaded results are counted separately and make the run FAIL.
 */
public final class ChunkThroughputBenchmark {
    private static final Logger LOG = LoggerFactory.getLogger("worldgennext-bench");
    private static final TicketType<ChunkPos> BENCH_TICKET =
            TicketType.create("worldgennext_bench", Comparator.comparingLong(ChunkPos::toLong));

    public record Settings(ChunkStatus status, int radiusChunks, int warmupRadiusChunks,
                           int centerX, int centerZ, int inFlight, Path output, boolean stopAfter,
                           boolean releaseAtEnd, boolean digest) {
        public static Settings fromSystemProperties(Path gameDirectory) {
            String statusName = System.getProperty("worldgennext.bench.status", "NOISE").trim().toUpperCase(Locale.ROOT);
            ChunkStatus status = switch (statusName) {
                case "BIOMES" -> ChunkStatus.BIOMES;
                case "NOISE" -> ChunkStatus.NOISE;
                case "SURFACE" -> ChunkStatus.SURFACE;
                case "CARVERS" -> ChunkStatus.CARVERS;
                case "FEATURES" -> ChunkStatus.FEATURES;
                case "FULL" -> ChunkStatus.FULL;
                default -> throw new IllegalArgumentException("Unknown worldgennext.bench.status: " + statusName);
            };
            int radius = Integer.getInteger("worldgennext.bench.radiusChunks", 45);
            int warmup = Integer.getInteger("worldgennext.bench.warmupRadiusChunks", 5);
            int centerX = Integer.getInteger("worldgennext.bench.centerX", 4000);
            int centerZ = Integer.getInteger("worldgennext.bench.centerZ", 4000);
            int inFlight = Integer.getInteger("worldgennext.bench.inFlight", 1024);
            if (radius < 0 || radius > 400 || warmup < 0 || warmup > 100 || inFlight < 1 || inFlight > 65536) {
                throw new IllegalArgumentException("Benchmark radius/warmup/inFlight out of range");
            }
            String out = System.getProperty("worldgennext.bench.output", "").trim();
            Path output = out.isEmpty() ? gameDirectory.resolve("worldgennext-bench.json") : Path.of(out);
            boolean stop = Boolean.parseBoolean(System.getProperty("worldgennext.bench.stopAfter", "true"));
            String release = System.getProperty("worldgennext.bench.release", "immediate").trim();
            if (!release.equals("immediate") && !release.equals("end")) {
                throw new IllegalArgumentException("worldgennext.bench.release must be immediate or end");
            }
            boolean digest = Boolean.parseBoolean(System.getProperty("worldgennext.bench.digest", "false"));
            if (digest && !release.equals("end")) {
                throw new IllegalArgumentException("worldgennext.bench.digest requires worldgennext.bench.release=end");
            }
            return new Settings(status, radius, warmup, centerX, centerZ, inFlight, output, stop,
                    release.equals("end"), digest);
        }
    }

    public record Phase(int requested, int completed, int failed, long elapsedNanos) {
        double chunksPerSecond() {
            return elapsedNanos <= 0 ? 0.0 : completed * 1.0e9 / elapsedNanos;
        }
    }

    private final MinecraftServer server;
    private final ServerLevel level;
    private final Settings settings;

    public ChunkThroughputBenchmark(MinecraftServer server, ServerLevel level, Settings settings) {
        this.server = server;
        this.level = level;
        this.settings = settings;
    }

    public static boolean requested() {
        return Boolean.parseBoolean(System.getProperty("worldgennext.bench.autorun", "false"));
    }

    /** Starts the benchmark on its own driver thread; never blocks the server thread. */
    public static void startAsync(MinecraftServer server, String routeDescription) {
        Settings settings;
        try {
            settings = Settings.fromSystemProperties(server.getServerDirectory());
        } catch (RuntimeException invalid) {
            LOG.error("Invalid WorldgenNext benchmark settings", invalid);
            server.execute(() -> server.halt(false));
            return;
        }
        String dimension = System.getProperty("worldgennext.bench.dimension", "minecraft:overworld").trim();
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.ResourceLocation.parse(dimension)));
        if (level == null) {
            LOG.error("Unknown benchmark dimension {}", dimension);
            server.execute(() -> server.halt(false));
            return;
        }
        Settings located = settings;
        String centerBiome = System.getProperty("worldgennext.bench.centerBiome", "").trim();
        if (!centerBiome.isEmpty()) {
            // Centre the measured square on the nearest occurrence of a biome (test coverage of biome-specific code).
            var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,
                    net.minecraft.resources.ResourceLocation.parse(centerBiome));
            var origin = new net.minecraft.core.BlockPos(settings.centerX() * 16, 64, settings.centerZ() * 16);
            var found = level.findClosestBiome3d(holder -> holder.is(key), origin, 12800, 64, 64);
            if (found == null) {
                LOG.error("Benchmark centre biome {} not found near {}", centerBiome, origin);
                server.execute(() -> server.halt(false));
                return;
            }
            var at = found.getFirst();
            LOG.info("WorldgenNext benchmark centre moved to {} at block {} {}", centerBiome, at.getX(), at.getZ());
            located = new Settings(settings.status(), settings.radiusChunks(), settings.warmupRadiusChunks(), at.getX() >> 4, at.getZ() >> 4,
                    settings.inFlight(), settings.output(), settings.stopAfter(), settings.releaseAtEnd(), settings.digest());
        }
        Settings resolved = located;
        Thread driver = new Thread(() -> {
            try {
                new ChunkThroughputBenchmark(server, level, resolved).run(routeDescription);
            } catch (Throwable failure) {
                LOG.error("WorldgenNext benchmark failed", failure);
                writeQuietly(settings.output(), "{\"status\":\"ERROR\",\"error\":" + json(failure.toString()) + "}\n");
            } finally {
                if (settings.stopAfter()) server.execute(() -> server.halt(false));
            }
        }, "worldgennext-bench-driver");
        driver.setDaemon(true);
        driver.start();
    }

    void run(String routeDescription) throws Exception {
        if (Boolean.parseBoolean(System.getProperty("worldgennext.bench.productionThreadNames", "true"))
                && net.minecraft.SharedConstants.IS_RUNNING_IN_IDE) {
            // A development launch renames the thread around every scheduled task (two syscalls per task);
            // an installed server does not.  Measure what an installed server does.
            net.minecraft.SharedConstants.IS_RUNNING_IN_IDE = false;
            LOG.info("WorldgenNext benchmark: development-only per-task thread renaming disabled");
        }
        var engine = dev.worldgennext.neoforge.fast.FastNoiseEngine.instance();
        if (engine != null) engine.awaitCompiled();
        int warmOffset = settings.radiusChunks() + settings.warmupRadiusChunks() + 64;
        LOG.info("WorldgenNext benchmark: status={} radius={} warmupRadius={} center=({},{}) inFlight={} route={}",
                settings.status(), settings.radiusChunks(), settings.warmupRadiusChunks(),
                settings.centerX(), settings.centerZ(), settings.inFlight(), routeDescription);
        Phase warmup = settings.warmupRadiusChunks() == 0 ? new Phase(0, 0, 0, 0)
                : generateSquare(settings.centerX() - warmOffset, settings.centerZ() - warmOffset,
                settings.warmupRadiusChunks(), "warmup", false);
        Phase measured = generateSquare(settings.centerX(), settings.centerZ(), settings.radiusChunks(), "measured",
                settings.digest());
        boolean pass = measured.failed() == 0 && measured.completed() == measured.requested() && measured.requested() > 0;
        Runtime rt = Runtime.getRuntime();
        String report = "{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"kind\": \"worldgennext_chunk_throughput\",\n"
                + "  \"status\": " + json(pass ? "PASS" : "FAIL") + ",\n"
                + "  \"endpoint\": " + json(settings.status().toString().toUpperCase(Locale.ROOT)) + ",\n"
                + "  \"route\": " + json(routeDescription) + ",\n"
                + "  \"radiusChunks\": " + settings.radiusChunks() + ",\n"
                + "  \"center\": [" + settings.centerX() + ", " + settings.centerZ() + "],\n"
                + "  \"inFlight\": " + settings.inFlight() + ",\n"
                + "  \"seed\": " + level.getSeed() + ",\n"
                + "  \"availableProcessors\": " + rt.availableProcessors() + ",\n"
                + "  \"maxHeapBytes\": " + rt.maxMemory() + ",\n"
                + "  \"javaVersion\": " + json(System.getProperty("java.version")) + ",\n"
                + "  \"warmup\": " + phaseJson(warmup) + ",\n"
                + "  \"measured\": " + phaseJson(measured) + ",\n"
                + "  \"chunksPerSecond\": " + String.format(Locale.ROOT, "%.2f", measured.chunksPerSecond()) + ",\n"
                + "  \"note\": \"Wall-clock interval from first measured request to last completion; compilation is not subtracted.\"\n"
                + "}\n";
        writeQuietly(settings.output(), report);
        LOG.info("WorldgenNext benchmark {}: {} {} chunks in {} ms = {} cps (failed={})",
                pass ? "PASS" : "FAIL", measured.completed(), settings.status(),
                TimeUnit.NANOSECONDS.toMillis(measured.elapsedNanos()),
                String.format(Locale.ROOT, "%.2f", measured.chunksPerSecond()), measured.failed());
    }

    private Phase generateSquare(int centerX, int centerZ, int radius, String label, boolean digest)
            throws InterruptedException {
        // Request order: "spiral" (default) walks one-chunk-wide rings outward; "tiles" walks square tiles in
        // a spiral and fills each tile row by row (measured slower here: 1,500-1,650 against 1,800-2,000).
        String orderName = System.getProperty("worldgennext.bench.order", "spiral").trim();
        List<ChunkPos> order = orderName.equals("spiral") ? spiral(centerX, centerZ, radius)
                : tiles(centerX, centerZ, radius, Integer.getInteger("worldgennext.bench.tileSize", 32));
        ServerChunkCache cache = level.getChunkSource();
        int ticketDistance = ChunkLevel.byStatus(net.minecraft.server.level.FullChunkStatus.FULL)
                - ChunkLevel.byStatus(settings.status());
        Semaphore permits = new Semaphore(settings.inFlight());
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicLong lastCompletion = new AtomicLong();
        CompletableFuture<?>[] futures = new CompletableFuture<?>[order.size()];
        java.util.concurrent.atomic.AtomicLongArray completionNanos = new java.util.concurrent.atomic.AtomicLongArray(order.size());
        AtomicInteger finished = new AtomicInteger();
        long start = System.nanoTime();
        Thread backlog = Boolean.getBoolean("worldgennext.bench.backlog") && label.equals("measured") ? startBacklogLog(finished) : null;
        Thread sampler = Boolean.getBoolean("worldgennext.bench.sampleServerThread") && label.equals("measured")
                ? startServerThreadSampler(finished, order.size(), order, futures) : null;
        // Requests and ticket releases are handed to the server thread in batches: one server task adds the
        // tickets of every request that queued up meanwhile, then asks for the chunks, then drops the tickets
        // of finished chunks.  Each separate ticket change otherwise costs a distance-manager pass and a copy
        // of the whole chunk-holder map on the server thread (worldgennext.bench.batchTickets=false restores
        // one task per ticket).
        boolean batch = Boolean.parseBoolean(System.getProperty("worldgennext.bench.batchTickets", "false"));
        java.util.concurrent.ConcurrentLinkedQueue<Integer> requests = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.ConcurrentLinkedQueue<ChunkPos> releases = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.atomic.AtomicBoolean drainQueued = new java.util.concurrent.atomic.AtomicBoolean();
        // worldgennext.bench.completionDigest=true: digest every measured chunk the moment it completes, while
        // tickets are released as usual.  The chunks then unload and are saved during the run, so a reopened
        // world's digest compared with this one checks the unload save path.
        String[] completionDigests = Boolean.getBoolean("worldgennext.bench.completionDigest") && label.equals("measured")
                && !settings.releaseAtEnd() ? new String[order.size()] : null;
        java.util.function.IntConsumer request = index -> {
            ChunkPos pos = order.get(index);
            CompletableFuture<?> done = futures[index];
            cache.getChunkFuture(pos.x, pos.z, settings.status(), true).handle((result, error) -> {
                if (error == null && result != null && result.isSuccess()) {
                    completed.incrementAndGet();
                    if (completionDigests != null) completionDigests[index] = ChunkDigest.line(level, result.orElse(null));
                } else failed.incrementAndGet();
                long now = System.nanoTime();
                lastCompletion.set(now);
                completionNanos.set(finished.getAndIncrement(), now - start);
                if (!settings.releaseAtEnd()) {
                    if (batch) releases.add(pos);
                    else server.execute(() -> cache.removeRegionTicket(BENCH_TICKET, pos, ticketDistance, pos));
                }
                permits.release();
                done.complete(null);
                return null;
            });
        };
        int batchLimit = Integer.getInteger("worldgennext.bench.batchLimit", 64);
        // The chunk source's own main-thread executor: its tasks run whenever the server thread polls chunk
        // work, unlike MinecraftServer tasks, which wait for tick boundaries while the server is behind.
        java.util.concurrent.Executor chunkExecutor = server;
        try {
            java.lang.reflect.Field field = Names.declaredField(ServerChunkCache.class, "mainThreadProcessor");
            field.setAccessible(true);
            chunkExecutor = (java.util.concurrent.Executor) field.get(cache);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            LOG.warn("Chunk executor not reachable; ticket batches use server tasks: {}", unavailable.toString());
        }
        java.util.concurrent.Executor mainThread = chunkExecutor;
        Runnable[] self = new Runnable[1];
        Runnable drain = () -> {
            drainQueued.set(false);
            List<Integer> batchRequests = new ArrayList<>();
            for (Integer index; batchRequests.size() < batchLimit && (index = requests.poll()) != null; ) batchRequests.add(index);
            // A bounded batch keeps requests flowing instead of moving the whole in-flight window as one wave.
            if (!requests.isEmpty() && drainQueued.compareAndSet(false, true)) mainThread.execute(self[0]);
            for (int index : batchRequests) {
                ChunkPos pos = order.get(index);
                cache.addRegionTicket(BENCH_TICKET, pos, ticketDistance, pos);
            }
            for (int index : batchRequests) request.accept(index);
            for (ChunkPos pos; (pos = releases.poll()) != null; ) cache.removeRegionTicket(BENCH_TICKET, pos, ticketDistance, pos);
        };
        self[0] = drain;
        for (int i = 0; i < order.size(); i++) {
            permits.acquire();
            futures[i] = new CompletableFuture<>();
            if (batch) {
                requests.add(i);
                if (drainQueued.compareAndSet(false, true)) mainThread.execute(drain);
            } else {
                ChunkPos pos = order.get(i);
                int index = i;
                server.execute(() -> cache.addRegionTicket(BENCH_TICKET, pos, ticketDistance, pos));
                request.accept(index);
            }
        }
        if (batch) {
            // Releases queued after the last request still need a drain.
            while (finished.get() < order.size()) {
                if (!releases.isEmpty() && drainQueued.compareAndSet(false, true)) mainThread.execute(drain);
                Thread.sleep(1);
            }
            mainThread.execute(drain);
        }
        CompletableFuture.allOf(futures).join();
        if (backlog != null) backlog.interrupt();
        if (sampler != null) {
            sampler.interrupt();
            sampler.join();
        }
        long elapsed = lastCompletion.get() - start;
        if (digest) writeDigest(order);
        if (completionDigests != null) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < completionDigests.length; i++) {
                ChunkPos pos = order.get(i);
                text.append(completionDigests[i] == null ? pos.x + " " + pos.z + " MISSING" : completionDigests[i]).append(System.lineSeparator());
            }
            Path digestPath = settings.output().resolveSibling(settings.output().getFileName().toString()
                    .replaceFirst("[.]json$", "") + ".digest.txt");
            writeQuietly(digestPath, text.toString());
            LOG.info("WorldgenNext benchmark completion digest written: {}", digestPath);
        }
        if (settings.releaseAtEnd()) {
            server.execute(() -> {
                for (ChunkPos pos : order) cache.removeRegionTicket(BENCH_TICKET, pos, ticketDistance, pos);
            });
        }
        // Completions per half second: shows whether throughput is steady or ends in a serial tail.
        int buckets = (int) (elapsed / 500_000_000L) + 1;
        int[] timeline = new int[buckets];
        for (int i = 0; i < finished.get(); i++) timeline[(int) Math.min(buckets - 1, completionNanos.get(i) / 500_000_000L)]++;
        LOG.info("WorldgenNext benchmark {} completions per 500 ms: {}", label, java.util.Arrays.toString(timeline));
        // Rate between the 20% and 85% completion marks: excludes pipeline ramp-up and the final drain.
        int done = finished.get();
        if (done >= 100) {
            long[] sorted = new long[done];
            for (int i = 0; i < done; i++) sorted[i] = completionNanos.get(i);
            java.util.Arrays.sort(sorted);
            int from = done / 5, to = done * 85 / 100;
            double steady = (to - from) * 1.0e9 / Math.max(1L, sorted[to] - sorted[from]);
            LOG.info("WorldgenNext benchmark {} steady-state rate (20%..85% of completions): {} cps", label,
                    String.format(Locale.ROOT, "%.2f", steady));
        }
        Phase phase = new Phase(order.size(), completed.get(), failed.get(), elapsed);
        LOG.info("WorldgenNext benchmark {} phase: {}/{} chunks, failed={}, {} ms, {} cps", label,
                phase.completed(), phase.requested(), phase.failed(), TimeUnit.NANOSECONDS.toMillis(elapsed),
                String.format(Locale.ROOT, "%.2f", phase.chunksPerSecond()));
        return phase;
    }

    /** Server thread only: why loaded chunks are still loaded (tickets by type, drop queue, generation references). */
    private String holderDiagnostics() {
        try {
            var chunkMap = level.getChunkSource().chunkMap;
            java.lang.reflect.Field dropField = Names.declaredField(net.minecraft.server.level.ChunkMap.class, "toDrop");
            dropField.setAccessible(true);
            var toDrop = (it.unimi.dsi.fastutil.longs.LongSet) dropField.get(chunkMap);
            java.lang.reflect.Field updatingField = Names.declaredField(net.minecraft.server.level.ChunkMap.class, "updatingChunkMap");
            updatingField.setAccessible(true);
            var updating = (it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<?>) updatingField.get(chunkMap);
            int referenced = 0;
            for (long pos : toDrop) {
                Object holder = updating.get(pos);
                if (holder instanceof net.minecraft.server.level.GenerationChunkHolder generation && generation.getGenerationRefCount() != 0) referenced++;
            }
            int loadedLevels = 0, unloadedLevels = 0;
            for (Object holder : updating.values()) {
                if (holder instanceof net.minecraft.server.level.ChunkHolder chunkHolder) {
                    if (ChunkLevel.isLoaded(chunkHolder.getTicketLevel())) loadedLevels++;
                    else unloadedLevels++;
                }
            }
            java.lang.reflect.Field ticketsField = Names.declaredField(net.minecraft.server.level.DistanceManager.class, "tickets");
            ticketsField.setAccessible(true);
            var tickets = (it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<?>) ticketsField.get(chunkMap.getDistanceManager());
            java.util.Map<String, Integer> byType = new java.util.TreeMap<>();
            for (Object set : tickets.values()) {
                for (Object ticket : (Iterable<?>) set) {
                    byType.merge(((net.minecraft.server.level.Ticket<?>) ticket).getType().toString(), 1, Integer::sum);
                }
            }
            return "holders=" + updating.size() + " ticketLevelLoaded=" + loadedLevels + " ticketLevelUnloaded=" + unloadedLevels
                    + " toDrop=" + toDrop.size() + " toDropStillReferenced=" + referenced + " tickets=" + byType;
        } catch (Throwable failure) {
            return "holder diagnostics unavailable: " + failure;
        }
    }

    /** Diagnostic: once a second, how much work is queued behind generation (unload, encode, write) and heap in use. */
    private Thread startBacklogLog(AtomicInteger finished) {
        Thread thread = new Thread(() -> {
            java.util.Map<?, ?> pendingWrites = null;
            try {
                java.lang.reflect.Field workerField = Names.declaredField(net.minecraft.world.level.chunk.storage.ChunkStorage.class, "worker");
                workerField.setAccessible(true);
                Object worker = workerField.get(level.getChunkSource().chunkMap);
                java.lang.reflect.Field pending = Names.declaredField(worker.getClass(), "pendingWrites");
                pending.setAccessible(true);
                pendingWrites = (java.util.Map<?, ?>) pending.get(worker);
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                // reported as -1
            }
            StringBuilder out = new StringBuilder();
            while (!Thread.currentThread().isInterrupted()) {
                Runtime rt = Runtime.getRuntime();
                String holders;
                try {
                    holders = server.submit(this::holderDiagnostics).get(5, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    holders = failure.toString();
                }
                out.append(String.format(Locale.ROOT, "%n  done=%d loaded=%d ioPending=%d %s heapUsedMB=%d %s",
                        finished.get(), level.getChunkSource().getLoadedChunksCount(), pendingWrites == null ? -1 : pendingWrites.size(),
                        dev.worldgennext.neoforge.threading.AsyncSectionEncoding.status(),
                        (rt.totalMemory() - rt.freeMemory()) >> 20, holders));
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException stop) {
                    break;
                }
            }
            LOG.info("Backlog per second:{}", out);
        }, "worldgennext-bench-backlog");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Diagnostic: samples the server thread's stack every 2 ms (JFR samples too few threads per tick to
     * profile one thread) and logs where it spends its time, overall and after 90% of chunks completed.
     */
    private Thread startServerThreadSampler(AtomicInteger finished, int total, List<ChunkPos> order, CompletableFuture<?>[] futures) {
        Thread target = server.getRunningThread();
        Thread sampler = new Thread(() -> {
            java.util.Map<String, int[]> counts = new java.util.HashMap<>();
            int samples = 0, tailSamples = 0;
            int lastFinished = -1, dumps = 0;
            boolean slowWindow = false;
            java.util.Map<String, Integer> mailbox = new java.util.HashMap<>();
            int mailboxTicks = 0, mailboxBusy = 0, tick = 0;
            long lastChange = System.nanoTime();
            while (!Thread.currentThread().isInterrupted()) {
                int now = finished.get();
                boolean slow = false;
                if (System.nanoTime() - lastChange > 300_000_000L) {
                    slowWindow = now - lastFinished < 120 && now > total / 4 && now < total - 500;
                    // Fewer than 60 completions in 300 ms (200/s) well into the run: record what every thread is doing.
                    slow = now - lastFinished < 120 && now > total / 4 && now < total - 500 && dumps < 4;
                    lastFinished = now;
                    lastChange = System.nanoTime();
                }
                if (slow) {
                    dumps++;
                    StringBuilder dump = new StringBuilder();
                    java.util.Map<String, Integer> grouped = new java.util.TreeMap<>();
                    for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                        StackTraceElement[] frames = entry.getValue();
                        if (frames.length == 0) continue;
                        StringBuilder line = new StringBuilder(entry.getKey().getName().replaceAll("[0-9]+", "N")).append(": ");
                        for (int i = 0; i < Math.min(frames.length, frames.length > 6 && frames[5].getMethodName().equals("waitingGet") ? 22 : 7); i++) {
                            String owner = frames[i].getClassName();
                            line.append(owner.substring(owner.lastIndexOf('.') + 1)).append('.').append(frames[i].getMethodName()).append(" < ");
                        }
                        grouped.merge(line.toString(), 1, Integer::sum);
                    }
                    grouped.forEach((line, count) -> dump.append(String.format(Locale.ROOT, "%n  %3d  %s", count, line)));
                    // Where the requested-but-unfinished chunks are: ticket level and latest status.
                    java.util.Map<String, Integer> stuck = new java.util.TreeMap<>();
                    for (int i = 0; i < futures.length; i++) {
                        CompletableFuture<?> future = futures[i];
                        if (future == null || future.isDone()) continue;
                        String data = level.getChunkSource().chunkMap.getChunkDebugData(order.get(i))
                                .replaceAll("\u00a7.", "").replace('\n', ' ');
                        stuck.merge(data, 1, Integer::sum);
                    }
                    dump.append(String.format(Locale.ROOT, "%n  unfinished requests by state: %s", stuck));
                    var chunkSource = level.getChunkSource();
                    LOG.info("Stall at {}/{} completed; {} loadedChunks={} pendingTasks={} gpu={}; threads:{}", now, total,
                            dev.worldgennext.neoforge.threading.AsyncSectionEncoding.status(), chunkSource.getLoadedChunksCount(),
                            chunkSource.getPendingTasksCount(),
                            dev.worldgennext.neoforge.fast.FastNoiseEngine.instance() == null ? "-"
                                    : dev.worldgennext.neoforge.fast.FastNoiseEngine.instance().status(), dump);
                }
                StackTraceElement[] stack = target.getStackTrace();
                // Second column: samples taken while the completion rate over the last 300 ms was below 200/s.
                boolean tail = slowWindow;
                StringBuilder key = new StringBuilder();
                int kept = 0;
                for (StackTraceElement frame : stack) {
                    String owner = frame.getClassName();
                    if (kept == 0 || owner.startsWith("net.minecraft") || owner.startsWith("dev.worldgennext") || owner.startsWith("ca.spottedleaf")) {
                        key.append(owner.substring(owner.lastIndexOf('.') + 1)).append('.').append(frame.getMethodName()).append(" < ");
                        if (++kept >= 5) break;
                    }
                }
                if (++tick % 5 == 0) {
                    // Every 10 ms: what the serial worldgen mailbox (a ProcessorMailbox on the worker pool) is running.
                    mailboxTicks++;
                    boolean busy = false;
                    for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                        StackTraceElement[] frames = entry.getValue();
                        boolean inMailbox = false;
                        for (StackTraceElement frame : frames) {
                            if (frame.getClassName().endsWith("ProcessorMailbox")) { inMailbox = true; break; }
                        }
                        if (!inMailbox) continue;
                        busy = true;
                        StringBuilder line = new StringBuilder();
                        int keptFrames = 0;
                        for (StackTraceElement frame : frames) {
                            String owner = frame.getClassName();
                            if (keptFrames == 0 || owner.startsWith("net.minecraft") || owner.startsWith("dev.worldgennext") || owner.startsWith("ca.spottedleaf")) {
                                line.append(owner.substring(owner.lastIndexOf('.') + 1)).append('.').append(frame.getMethodName()).append(" < ");
                                if (++keptFrames >= 6) break;
                            }
                        }
                        mailbox.merge(line.toString(), 1, Integer::sum);
                    }
                    if (busy) mailboxBusy++;
                }
                int[] count = counts.computeIfAbsent(key.toString(), ignored -> new int[2]);
                count[0]++;
                samples++;
                if (tail) { count[1]++; tailSamples++; }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException stop) {
                    break;
                }
            }
            StringBuilder mail = new StringBuilder();
            mailbox.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(25)
                    .forEach(e -> mail.append(String.format(Locale.ROOT, "%n  %5d  %s", e.getValue(), e.getKey())));
            LOG.info("Mailbox samples: {} ticks, {} with a mailbox task running:{}", mailboxTicks, mailboxBusy, mail);
            for (int column = 0; column < 2; column++) {
                int which = column;
                StringBuilder out = new StringBuilder();
                counts.entrySet().stream().sorted((a, b) -> b.getValue()[which] - a.getValue()[which]).limit(18)
                        .forEach(e -> out.append(String.format(Locale.ROOT, "%n  %5d  %s", e.getValue()[which], e.getKey())));
                LOG.info("Server thread samples {} ({}):{}", column == 0 ? "overall" : "while completions were below 200/s",
                        column == 0 ? samples : tailSamples, out);
            }
        }, "worldgennext-bench-sampler");
        sampler.setDaemon(true);
        sampler.start();
        return sampler;
    }

    private void writeDigest(List<ChunkPos> order) {
        ServerChunkCache cache = level.getChunkSource();
        CompletableFuture<String> lines = CompletableFuture.supplyAsync(() -> {
            StringBuilder text = new StringBuilder();
            java.util.Map<String, Integer> coverage = new java.util.TreeMap<>();
            for (ChunkPos pos : order) {
                var chunk = cache.getChunk(pos.x, pos.z, settings.status(), false);
                text.append(chunk == null ? pos.x + " " + pos.z + " MISSING" : ChunkDigest.line(level, chunk)).append(System.lineSeparator());
                if (chunk != null) for (String biome : ChunkDigest.biomeNames(chunk)) coverage.merge(biome, 1, Integer::sum);
            }
            LOG.info("WorldgenNext benchmark biome coverage (chunks containing each biome): {}", coverage);
            return text.toString();
        }, server);
        Path digestPath = settings.output().resolveSibling(settings.output().getFileName().toString()
                .replaceFirst("[.]json$", "") + ".digest.txt");
        writeQuietly(digestPath, lines.join());
        LOG.info("WorldgenNext benchmark digest written: {}", digestPath);
    }

    /** Square of side 2r+1 in outward spiral order so neighbors share prerequisites. */
    static List<ChunkPos> spiral(int cx, int cz, int radius) {
        List<ChunkPos> out = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        out.add(new ChunkPos(cx, cz));
        for (int ring = 1; ring <= radius; ring++) {
            for (int x = -ring; x <= ring; x++) out.add(new ChunkPos(cx + x, cz - ring));
            for (int z = -ring + 1; z <= ring; z++) out.add(new ChunkPos(cx + ring, cz + z));
            for (int x = ring - 1; x >= -ring; x--) out.add(new ChunkPos(cx + x, cz + ring));
            for (int z = ring - 1; z >= -ring + 1; z--) out.add(new ChunkPos(cx - ring, cz + z));
        }
        return out;
    }

    /** The same square as {@link #spiral}, visited tile by tile: tiles in an outward spiral, rows inside a tile. */
    static List<ChunkPos> tiles(int cx, int cz, int radius, int tileSize) {
        int side = 2 * radius + 1;
        int tilesPerAxis = (side + tileSize - 1) / tileSize;
        int minX = cx - radius, minZ = cz - radius;
        List<ChunkPos> out = new ArrayList<>(side * side);
        int centreTile = (tilesPerAxis - 1) / 2;
        for (ChunkPos tile : spiral(centreTile, centreTile, tilesPerAxis)) {
            if (tile.x < 0 || tile.z < 0 || tile.x >= tilesPerAxis || tile.z >= tilesPerAxis) continue;
            for (int dz = 0; dz < tileSize; dz++) {
                for (int dx = 0; dx < tileSize; dx++) {
                    int x = tile.x * tileSize + dx, z = tile.z * tileSize + dz;
                    if (x < side && z < side) out.add(new ChunkPos(minX + x, minZ + z));
                }
            }
        }
        if (out.size() != side * side) throw new IllegalStateException("tile order lost chunks: " + out.size());
        return out;
    }

    private static String phaseJson(Phase p) {
        return "{\"requested\": " + p.requested() + ", \"completed\": " + p.completed()
                + ", \"failed\": " + p.failed() + ", \"elapsedMillis\": "
                + TimeUnit.NANOSECONDS.toMillis(p.elapsedNanos()) + ", \"chunksPerSecond\": "
                + String.format(Locale.ROOT, "%.2f", p.chunksPerSecond()) + "}";
    }

    private static String json(String value) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    private static void writeQuietly(Path output, String content) {
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(output, content, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            LOG.error("Could not write benchmark report {}", output, failure);
        }
    }
}
