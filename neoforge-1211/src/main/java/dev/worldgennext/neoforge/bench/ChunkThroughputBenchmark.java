// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.bench;

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
        Thread driver = new Thread(() -> {
            try {
                new ChunkThroughputBenchmark(server, level, settings).run(routeDescription);
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
        List<ChunkPos> order = spiral(centerX, centerZ, radius);
        ServerChunkCache cache = level.getChunkSource();
        int ticketDistance = ChunkLevel.byStatus(net.minecraft.server.level.FullChunkStatus.FULL)
                - ChunkLevel.byStatus(settings.status());
        Semaphore permits = new Semaphore(settings.inFlight());
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicLong lastCompletion = new AtomicLong();
        CompletableFuture<?>[] futures = new CompletableFuture<?>[order.size()];
        long start = System.nanoTime();
        for (int i = 0; i < order.size(); i++) {
            permits.acquire();
            ChunkPos pos = order.get(i);
            server.execute(() -> cache.addRegionTicket(BENCH_TICKET, pos, ticketDistance, pos));
            futures[i] = cache.getChunkFuture(pos.x, pos.z, settings.status(), true)
                    .handle((result, error) -> {
                        if (error == null && result != null && result.isSuccess()) completed.incrementAndGet();
                        else failed.incrementAndGet();
                        lastCompletion.set(System.nanoTime());
                        if (!settings.releaseAtEnd()) {
                            server.execute(() -> cache.removeRegionTicket(BENCH_TICKET, pos, ticketDistance, pos));
                        }
                        permits.release();
                        return null;
                    });
        }
        CompletableFuture.allOf(futures).join();
        long elapsed = lastCompletion.get() - start;
        if (digest) writeDigest(order);
        if (settings.releaseAtEnd()) {
            server.execute(() -> {
                for (ChunkPos pos : order) cache.removeRegionTicket(BENCH_TICKET, pos, ticketDistance, pos);
            });
        }
        Phase phase = new Phase(order.size(), completed.get(), failed.get(), elapsed);
        LOG.info("WorldgenNext benchmark {} phase: {}/{} chunks, failed={}, {} ms, {} cps", label,
                phase.completed(), phase.requested(), phase.failed(), TimeUnit.NANOSECONDS.toMillis(elapsed),
                String.format(Locale.ROOT, "%.2f", phase.chunksPerSecond()));
        return phase;
    }

    private void writeDigest(List<ChunkPos> order) {
        ServerChunkCache cache = level.getChunkSource();
        CompletableFuture<String> lines = CompletableFuture.supplyAsync(() -> {
            StringBuilder text = new StringBuilder();
            for (ChunkPos pos : order) {
                var chunk = cache.getChunk(pos.x, pos.z, settings.status(), false);
                text.append(chunk == null ? pos.x + " " + pos.z + " MISSING" : ChunkDigest.line(level, chunk)).append(System.lineSeparator());
            }
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
