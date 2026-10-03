// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.fast;

import dev.worldgennext.compiler.vulkan.fused.FusedNoiseCompiler;
import dev.worldgennext.runtime.vulkan.fused.FusedNoiseDevice;
import dev.worldgennext.semantic.snapshot.WorldgenSnapshot;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * GPU-batched NOISE stage.  Admitted requests (empty blender, fresh
 * ProtoChunk, a compiled program for the level) are batched to the fused
 * Vulkan kernels; every other request, and every chunk whose kernels raised a
 * bail flag, runs the original NoiseBasedChunkGenerator path unchanged.
 */
public final class FastNoiseEngine {
    private static final Logger LOG = LoggerFactory.getLogger("worldgennext-fast");
    /** OFF: never; AUTO: only routers whose kernel structure passed qualification; FORCE: any router that compiles. */
    public enum GpuMode { OFF, AUTO, FORCE }
    public static final GpuMode MODE = parseMode(System.getProperty("worldgennext.fast.gpu", "auto"));
    public static final boolean ENABLED = MODE != GpuMode.OFF;

    private static GpuMode parseMode(String value) {
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "off", "false", "none" -> GpuMode.OFF;
            case "force", "true" -> GpuMode.FORCE;
            default -> GpuMode.AUTO;
        };
    }

    /** Kernel-structure fingerprints that passed the digest matrix against the original generator. */
    private static final Map<String, String> QUALIFIED = loadQualified();

    private static Map<String, String> loadQualified() {
        Map<String, String> out = new java.util.HashMap<>();
        try (var in = FastNoiseEngine.class.getResourceAsStream("/worldgennext/fused-qualified.properties")) {
            if (in != null) {
                var properties = new java.util.Properties();
                properties.load(in);
                for (String key : properties.stringPropertyNames()) out.put(key, properties.getProperty(key));
            }
        } catch (java.io.IOException ignored) {
            // no allowlist: AUTO admits nothing
        }
        return Map.copyOf(out);
    }
    public static final boolean VERIFY = Boolean.getBoolean("worldgennext.fast.verify");
    private static final int BATCH = Integer.getInteger("worldgennext.fast.batch", 64);
    private static final int SLOTS = Integer.getInteger("worldgennext.fast.slots", 4);
    private static final long MAX_DELAY_NANOS = TimeUnit.MICROSECONDS.toNanos(Long.getLong("worldgennext.fast.maxDelayMicros", 1500));
    private static final ThreadLocal<Boolean> ORIGINAL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static volatile FastNoiseEngine INSTANCE;

    private final FusedNoiseDevice device;
    private final Map<RandomState, LevelProgram> programs = new ConcurrentHashMap<>();
    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private final Thread worker;
    private volatile boolean running = true;
    final AtomicLong gpuChunks = new AtomicLong(), fallbackChunks = new AtomicLong(), bailChunks = new AtomicLong(),
            verifiedChunks = new AtomicLong(), verifyMismatches = new AtomicLong(), batches = new AtomicLong();

    record LevelProgram(String dimension, FusedNoiseDevice.Program program, BlockState[] palette,
                        FusedNoiseCompiler.Geometry geometry, WorldgenSnapshot snapshot) {}

    record Request(LevelProgram program, ChunkAccess chunk, NoiseBasedChunkGenerator generator, Blender blender,
                   RandomState randomState, StructureManager structures, int[] beard, int pieces, int junctions,
                   CompletableFuture<ChunkAccess> result, long enqueuedNanos) {}

    private FastNoiseEngine(FusedNoiseDevice device) {
        this.device = device;
        this.worker = new Thread(this::loop, "worldgennext-fast-gpu");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public static FastNoiseEngine instance() { return INSTANCE; }

    private final java.util.concurrent.CountDownLatch compiled = new java.util.concurrent.CountDownLatch(1);

    /**
     * Opens the device and compiles every NoiseBasedChunkGenerator level on a
     * background thread.  A level generates with the original code until its
     * program is ready; failures and unqualified routers leave it there.
     */
    public static void start(MinecraftServer server) {
        if (!ENABLED || INSTANCE != null) return;
        FusedNoiseDevice device;
        try {
            device = FusedNoiseDevice.open();
        } catch (Throwable failure) {
            LOG.warn("Fast GPU NOISE unavailable, using vanilla generation: {}", failure.toString());
            return;
        }
        FastNoiseEngine engine = new FastNoiseEngine(device);
        INSTANCE = engine;
        LOG.info("Fast GPU NOISE device: {} (mode {})", device.deviceName(), MODE);
        // Captures read live registries and the seeded router; take them on the server thread.
        List<Runnable> jobs = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            try {
                Runnable job = engine.prepare(level);
                if (job != null) jobs.add(job);
            } catch (Throwable failure) {
                LOG.warn("Fast GPU NOISE disabled for {}: {}", level.dimension().location(), failure.toString());
            }
        }
        Thread compiler = new Thread(() -> {
            try {
                for (Runnable job : jobs) job.run();
            } finally {
                engine.compiled.countDown();
            }
        }, "worldgennext-fast-compile");
        compiler.setDaemon(true);
        compiler.start();
    }

    /** Blocks until every level's compile attempt finished (benchmarks and tests only). */
    public void awaitCompiled() throws InterruptedException {
        compiled.await();
    }

    public static void stop() {
        FastNoiseEngine engine = INSTANCE;
        INSTANCE = null;
        if (engine == null) return;
        engine.running = false;
        engine.worker.interrupt();
        try {
            engine.worker.join(10_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        LOG.info("Fast GPU NOISE stopped: gpu={} fallback={} bail={} batches={} verified={} verifyMismatches={}",
                engine.gpuChunks.get(), engine.fallbackChunks.get(), engine.bailChunks.get(), engine.batches.get(),
                engine.verifiedChunks.get(), engine.verifyMismatches.get());
        if (FusedNoiseDevice.PROFILE) LOG.info("Fast GPU NOISE kernel profile: {}", FusedNoiseDevice.profileSummary());
        engine.device.close();
    }

    /** Captures on the calling (server) thread; the returned job compiles and installs off-thread. */
    private Runnable prepare(ServerLevel level) throws Exception {
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) return null;
        long start = System.nanoTime();
        WorldgenSnapshot snapshot = FastRouterCapture.capture(level);
        NoiseGeneratorSettings settings = generator.generatorSettings().value();
        BlockState[] palette = {
                Blocks.AIR.defaultBlockState(), settings.defaultBlock(), settings.defaultFluid(), Blocks.LAVA.defaultBlockState(),
                Blocks.COPPER_ORE.defaultBlockState(), Blocks.RAW_COPPER_BLOCK.defaultBlockState(), Blocks.GRANITE.defaultBlockState(),
                Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), Blocks.RAW_IRON_BLOCK.defaultBlockState(), Blocks.TUFF.defaultBlockState()};
        int[] flags = new int[palette.length];
        for (int i = 0; i < palette.length; i++) {
            BlockState state = palette[i];
            flags[i] = (state.getFluidState().isEmpty() ? 0 : FusedNoiseCompiler.MaterialPalette.FLAG_HAS_FLUID)
                    | (Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(state) ? FusedNoiseCompiler.MaterialPalette.FLAG_BLOCKS_MOTION : 0)
                    | (Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(state) ? FusedNoiseCompiler.MaterialPalette.FLAG_NOT_AIR : 0);
        }
        BlockState fluid = settings.defaultFluid();
        var materialPalette = new FusedNoiseCompiler.MaterialPalette(0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
                fluid.is(Blocks.WATER), fluid.is(Blocks.LAVA), fluid == Blocks.LAVA.defaultBlockState(), fluid.isAir(),
                palette.length, flags);
        var request = new FusedNoiseCompiler.Request(snapshot.router().roots(), snapshot.generatorSettings(),
                snapshot.randomState().aquiferRandom(), snapshot.randomState().oreRandom(), materialPalette, beardKernel());
        FusedNoiseCompiler.Compiled compiled = new FusedNoiseCompiler().compile(request);
        String dimension = level.dimension().location().toString();
        RandomState randomState = level.getChunkSource().randomState();
        String qualifiedAs = QUALIFIED.get(compiled.structureFingerprint());
        LOG.info("Fast GPU NOISE {}: kernel structure {} ({})", dimension, compiled.structureFingerprint(),
                qualifiedAs == null ? "not in the qualified list" : "qualified as " + qualifiedAs);
        if (qualifiedAs == null && MODE == GpuMode.AUTO) {
            LOG.warn("Fast GPU NOISE not enabled for {}: this generator's kernel structure has not passed qualification; "
                    + "using vanilla generation (set -Dworldgennext.fast.gpu=force to override)", dimension);
            return null;
        }
        return () -> {
            try {
                install(level, dimension, randomState, compiled, palette, snapshot, start);
            } catch (Throwable failure) {
                LOG.warn("Fast GPU NOISE disabled for {}: {}", dimension, failure.toString());
            }
        };
    }

    private void install(ServerLevel level, String dimension, RandomState randomState, FusedNoiseCompiler.Compiled compiled,
                         BlockState[] palette, WorldgenSnapshot snapshot, long start) {
        dumpSource(level, compiled);
        if (Boolean.getBoolean("worldgennext.fast.dumpSourceOnly")) {
            throw new IllegalStateException("source dumped; pipeline build skipped by worldgennext.fast.dumpSourceOnly");
        }
        FusedNoiseDevice.Program program = device.load(compiled, BATCH, SLOTS);
        programs.put(randomState, new LevelProgram(dimension, program, palette, compiled.geometry(), snapshot));
        LOG.info("Fast GPU NOISE ready for {}: flat={} interp={} source={} chars, compile {} ms (pipelines {} ms)",
                level.dimension().location(), compiled.flatChannels(), compiled.interpolatedChannels(), compiled.source().length(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), TimeUnit.NANOSECONDS.toMillis(program.compileNanos));
    }

    private static void dumpSource(ServerLevel level, FusedNoiseCompiler.Compiled compiled) {
        String dir = System.getProperty("worldgennext.fast.dumpSourceDir", "").trim();
        if (dir.isEmpty()) return;
        try {
            java.nio.file.Path path = java.nio.file.Path.of(dir, level.dimension().location().getPath() + ".comp");
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, compiled.source());
        } catch (java.io.IOException failure) {
            LOG.warn("Could not dump fused source: {}", failure.toString());
        }
    }

    private static float[] beardKernel() throws ReflectiveOperationException {
        Field field = Beardifier.class.getDeclaredField("BEARD_KERNEL");
        field.setAccessible(true);
        return ((float[]) field.get(null)).clone();
    }

    /** Hook from NoiseBasedChunkGenerator.fillFromNoise; null means "run vanilla". */
    public CompletableFuture<ChunkAccess> tryGenerate(NoiseBasedChunkGenerator generator, Blender blender, RandomState randomState,
                                                      StructureManager structures, ChunkAccess chunk) {
        if (ORIGINAL.get() || !running || device.lost()) return null;
        LevelProgram program = programs.get(randomState);
        if (program == null) return null;
        if (blender != Blender.empty() || !(chunk instanceof ProtoChunk proto) || proto.getBelowZeroRetrogen() != null) {
            fallbackChunks.incrementAndGet();
            return null;
        }
        if (chunk.getMinBuildHeight() != program.geometry().minY() || chunk.getHeight() != program.geometry().storageHeight()) return null;
        int[] beard;
        int pieces, junctions;
        try {
            Beardifier beardifier = Beardifier.forStructuresInChunk(structures, chunk.getPos());
            List<int[]> rigid = new ArrayList<>();
            List<int[]> joints = new ArrayList<>();
            var pieceIterator = (it.unimi.dsi.fastutil.objects.ObjectListIterator<?>) PIECES.get(beardifier);
            while (pieceIterator.hasNext()) {
                var piece = (Beardifier.Rigid) pieceIterator.next();
                BoundingBox box = piece.box();
                rigid.add(new int[]{box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(),
                        piece.terrainAdjustment().ordinal(), piece.groundLevelDelta()});
            }
            var junctionIterator = (it.unimi.dsi.fastutil.objects.ObjectListIterator<?>) JUNCTIONS.get(beardifier);
            while (junctionIterator.hasNext()) {
                var junction = (JigsawJunction) junctionIterator.next();
                joints.add(new int[]{junction.getSourceX(), junction.getSourceGroundY(), junction.getSourceZ()});
            }
            pieces = rigid.size();
            junctions = joints.size();
            beard = new int[pieces * 8 + junctions * 3];
            int o = 0;
            for (int[] r : rigid) { System.arraycopy(r, 0, beard, o, 8); o += 8; }
            for (int[] j : joints) { System.arraycopy(j, 0, beard, o, 3); o += 3; }
        } catch (ReflectiveOperationException failure) {
            return null;
        }
        if (beard.length > 2048) {
            fallbackChunks.incrementAndGet();
            return null;
        }
        CompletableFuture<ChunkAccess> result = new CompletableFuture<>();
        synchronized (queue) {
            queue.add(new Request(program, chunk, generator, blender, randomState, structures, beard, pieces, junctions,
                    result, System.nanoTime()));
            queue.notifyAll();
        }
        return result;
    }

    private static final Field PIECES = beardField("pieceIterator");
    private static final Field JUNCTIONS = beardField("junctionIterator");
    private static Field beardField(String name) {
        try {
            Field field = Beardifier.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException failure) {
            throw new IllegalStateException(failure);
        }
    }

    // ------------------------------------------------------------- GPU loop
    private record InFlight(FusedNoiseDevice.Program.Slot slot, List<Request> requests) {}

    private void loop() {
        List<InFlight> inFlight = new ArrayList<>();
        Map<FusedNoiseDevice.Program, Integer> nextSlot = new IdentityHashMap<>();
        while (running || !inFlight.isEmpty()) {
            boolean progress = false;
            // Complete finished batches.
            for (int i = 0; i < inFlight.size(); i++) {
                InFlight batch = inFlight.get(i);
                if (batch.slot().poll()) {
                    inFlight.remove(i--);
                    complete(batch);
                    progress = true;
                } else if (device.lost()) {
                    inFlight.remove(i--);
                    for (Request r : batch.requests()) runOriginal(r);
                    progress = true;
                }
            }
            // Submit new batches while slots are free.
            List<Request> taken = takeBatch(inFlight.size());
            if (!taken.isEmpty()) {
                LevelProgram program = taken.get(0).program();
                FusedNoiseDevice.Program.Slot slot = freeSlot(program.program(), inFlight, nextSlot);
                if (slot == null) {
                    synchronized (queue) { for (int i = taken.size() - 1; i >= 0; i--) queue.addFirst(taken.get(i)); }
                } else {
                    try {
                        submit(slot, taken);
                        inFlight.add(new InFlight(slot, taken));
                        batches.incrementAndGet();
                    } catch (Throwable failure) {
                        LOG.error("Fast GPU NOISE submission failed; using vanilla", failure);
                        for (Request r : taken) runOriginal(r);
                    }
                    progress = true;
                }
            }
            if (!running && inFlight.isEmpty()) {
                synchronized (queue) { while (!queue.isEmpty()) runOriginal(queue.poll()); }
                break;
            }
            if (!progress) LockSupport.parkNanos(inFlight.isEmpty() ? 200_000L : 20_000L);
        }
    }

    /** Takes one same-program batch when full, aged, or when the GPU is otherwise idle. */
    private List<Request> takeBatch(int inFlight) {
        synchronized (queue) {
            if (queue.isEmpty()) return List.of();
            Request head = queue.peek();
            boolean aged = System.nanoTime() - head.enqueuedNanos() >= MAX_DELAY_NANOS;
            if (queue.size() < BATCH && !aged && inFlight > 0) return List.of();
            List<Request> out = new ArrayList<>(BATCH);
            var it = queue.iterator();
            while (it.hasNext() && out.size() < BATCH) {
                Request r = it.next();
                if (r.program() == head.program()) { out.add(r); it.remove(); }
            }
            return out;
        }
    }

    private FusedNoiseDevice.Program.Slot freeSlot(FusedNoiseDevice.Program program, List<InFlight> inFlight,
                                                   Map<FusedNoiseDevice.Program, Integer> nextSlot) {
        for (int attempt = 0; attempt < program.slotCount(); attempt++) {
            int index = nextSlot.merge(program, 1, (a, b) -> (a + b) % program.slotCount());
            FusedNoiseDevice.Program.Slot slot = program.slot(index);
            boolean busy = false;
            for (InFlight f : inFlight) if (f.slot() == slot) { busy = true; break; }
            if (!busy && !slot.pending()) return slot;
        }
        return null;
    }

    private void submit(FusedNoiseDevice.Program.Slot slot, List<Request> requests) {
        ByteBuffer info = slot.chunkInfo();
        ByteBuffer beard = slot.beardData();
        int beardOffset = 0;
        for (int i = 0; i < requests.size(); i++) {
            Request r = requests.get(i);
            ChunkPos pos = r.chunk().getPos();
            int base = i * FusedNoiseDevice.Program.Slot.CHUNK_INFO_INTS * 4;
            info.putInt(base, pos.x);
            info.putInt(base + 4, pos.z);
            info.putInt(base + 8, beardOffset);
            info.putInt(base + 12, r.pieces());
            info.putInt(base + 16, r.junctions());
            for (int v : r.beard()) { beard.putInt(beardOffset * 4, v); beardOffset++; }
        }
        slot.submit(requests.size());
    }

    private void complete(InFlight batch) {
        FusedNoiseDevice.Program.Slot slot = batch.slot();
        LevelProgram program = batch.requests().get(0).program();
        var g = program.geometry();
        int blockBytes = g.blockCount();
        ByteBuffer blocks = slot.blocks();
        ByteBuffer heights = slot.heights().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < batch.requests().size(); i++) {
            Request r = batch.requests().get(i);
            if (slot.flags(i) != 0) {
                bailChunks.incrementAndGet();
                runOriginal(r);
                continue;
            }
            byte[] data = new byte[blockBytes];
            blocks.get(i * blockBytes, data);
            int[] h = new int[512];
            for (int k = 0; k < 512; k++) h[k] = heights.getInt((i * 512 + k) * 4);
            double[][] debug = null;
            if (VERIFY && FusedNoiseDevice.DEBUG_BUFFERS) {
                var compiled = program.program().compiled;
                int perColumns = Math.max(1, compiled.flatChannels()) * g.columnCount();
                int perCorners = Math.max(1, compiled.interpolatedChannels()) * g.cornerCount();
                double[] columns = new double[perColumns];
                double[] corners = new double[perCorners];
                slot.debugColumns().asDoubleBuffer().get(i * perColumns, columns);
                slot.debugCorners().asDoubleBuffer().get(i * perCorners, corners);
                debug = new double[][]{columns, corners};
            }
            double[][] debugFinal = debug;
            CompletableFuture.runAsync(() -> applyOrVerify(r, data, h, debugFinal), Util.backgroundExecutor());
        }
    }

    private void applyOrVerify(Request r, byte[] data, int[] heights, double[][] debug) {
        try {
            var g = r.program().geometry();
            if (VERIFY) {
                ChunkAccess vanilla = original(r).join();
                verify(r, vanilla, data, heights, debug);
                r.result().complete(vanilla);
                return;
            }
            LevelChunkSection[] sections = r.chunk().getSections();
            for (LevelChunkSection section : sections) section.acquire();
            try {
                FastChunkApplier.apply(r.chunk(), data, heights, r.program().palette(), g.minY(), g.genHeight(),
                        g.cellWidth(), g.cellHeight());
            } finally {
                for (LevelChunkSection section : sections) section.release();
            }
            gpuChunks.incrementAndGet();
            r.result().complete(r.chunk());
        } catch (Throwable failure) {
            r.result().completeExceptionally(failure);
        }
    }

    private final java.util.concurrent.atomic.AtomicInteger debugReports = new java.util.concurrent.atomic.AtomicInteger();

    /** Compares GPU flat columns and interpolation corners with the CPU reference interpreter for one chunk. */
    private void debugCompare(Request r, double[][] debug, int mismatchY) {
        var compiled = r.program().program().compiled;
        var g = r.program().geometry();
        var snapshot = r.program().snapshot();
        var reference = new dev.worldgennext.compiler.jvm.worldgen.DenseNoiseGenerator();
        int cx = r.chunk().getPos().x, cz = r.chunk().getPos().z;
        int baseX = cx * 16, baseZ = cz * 16, cols = g.columnsPerAxis();
        StringBuilder out = new StringBuilder("Fast GPU NOISE debug chunk " + r.chunk().getPos() + ":");
        for (int f = 0; f < compiled.flatChannels(); f++) {
            int bad = 0; String firstBad = "";
            for (int qi = 0; qi < cols; qi++) for (int qj = 0; qj < cols; qj++) {
                int x = ((baseX >> 2) + qi) << 2, z = ((baseZ >> 2) + qj) << 2;
                double cpu = reference.capturedNodeValueAt(snapshot, cx, cz, compiled.flatChildren().get(f), x, 0, z);
                double gpu = debug[0][(f * cols + qi) * cols + qj];
                if (Double.doubleToLongBits(cpu) != Double.doubleToLongBits(gpu) && !(cpu == 0.0 && gpu == 0.0)) {
                    if (bad++ == 0) firstBad = " first(" + x + "," + z + ") cpu=" + cpu + " gpu=" + gpu;
                }
            }
            if (bad > 0) out.append("\n  flat ").append(f).append(": ").append(bad).append(" columns differ").append(firstBad)
                    .append(" node=").append(compiled.flatChildren().get(f).operation());
        }
        int nx = g.cornersPerAxisXZ(), ny = g.cornersY();
        int centerIy = Math.floorDiv(mismatchY, g.cellHeight()) - g.minCellY();
        for (int k = 0; k < compiled.interpolatedChannels(); k++) {
            int bad = 0; String firstBad = "";
            for (int iy = Math.max(0, centerIy - 3); iy <= Math.min(ny - 1, centerIy + 3); iy++)
                for (int ix = 0; ix < nx; ix++) for (int iz = 0; iz < nx; iz++) {
                    int x = baseX + ix * g.cellWidth(), y = (g.minCellY() + iy) * g.cellHeight(), z = baseZ + iz * g.cellWidth();
                    double cpu = reference.capturedNodeValueAt(snapshot, cx, cz, compiled.interpolatedChildren().get(k), x, y, z);
                    double gpu = debug[1][((k * ny + iy) * nx + ix) * nx + iz];
                    if (Double.doubleToLongBits(cpu) != Double.doubleToLongBits(gpu) && !(cpu == 0.0 && gpu == 0.0)) {
                        if (bad++ == 0) firstBad = " first(" + x + "," + y + "," + z + ") cpu=" + cpu + " gpu=" + gpu;
                    }
                }
            if (bad > 0) out.append("\n  interp ").append(k).append(": ").append(bad).append(" corners differ").append(firstBad);
        }
        LOG.warn(out.toString());
    }

    private void verify(Request r, ChunkAccess vanilla, byte[] data, int[] heights, double[][] debug) {
        verifiedChunks.incrementAndGet();
        var palette = r.program().palette();
        int minY = vanilla.getMinBuildHeight();
        int mismatches = 0;
        String first = null;
        for (int ly = 0; ly < vanilla.getHeight(); ly++) {
            LevelChunkSection section = vanilla.getSection(ly >> 4);
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState expected = section.getBlockState(x, ly & 15, z);
                BlockState actual = palette[data[(ly * 16 + z) * 16 + x] & 0x7F];
                if (expected != actual) {
                    mismatches++;
                    if (first == null) first = "(" + (vanilla.getPos().getMinBlockX() + x) + "," + (minY + ly) + ","
                            + (vanilla.getPos().getMinBlockZ() + z) + ") expected " + expected + " got " + actual;
                }
            }
        }
        if (mismatches > 0 && debug != null && debugReports.getAndIncrement() < 3) {
            try {
                int y = Integer.parseInt(first.substring(1, first.indexOf(41)).split(",")[1]);
                debugCompare(r, debug, y);
            } catch (Throwable failure) {
                LOG.warn("Fast GPU NOISE debug comparison failed", failure);
            }
        }
        if (mismatches > 0) {
            verifyMismatches.incrementAndGet();
            LOG.warn("Fast GPU NOISE verify mismatch {} at chunk {}: {} blocks, first {} pieces={} junctions={} beard={}",
                    r.program().dimension(), vanilla.getPos(), mismatches, first, r.pieces(), r.junctions(),
                    java.util.Arrays.toString(java.util.Arrays.copyOf(r.beard(), Math.min(r.beard().length, 24))));
        }
    }

    private void runOriginal(Request r) {
        fallbackChunks.incrementAndGet();
        original(r).whenComplete((chunk, error) -> {
            if (error != null) r.result().completeExceptionally(error);
            else r.result().complete(chunk);
        });
    }

    private static CompletableFuture<ChunkAccess> original(Request r) {
        ORIGINAL.set(Boolean.TRUE);
        try {
            return r.generator().fillFromNoise(r.blender(), r.randomState(), r.structures(), r.chunk());
        } finally {
            ORIGINAL.set(Boolean.FALSE);
        }
    }

    public String status() {
        return "fastGpu{gpu=" + gpuChunks.get() + ", fallback=" + fallbackChunks.get() + ", bail=" + bailChunks.get()
                + ", batches=" + batches.get() + ", verified=" + verifiedChunks.get() + ", mismatches=" + verifyMismatches.get() + "}";
    }
}
