// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.fast;

import dev.worldgennext.compiler.vulkan.fused.FusedNoiseCompiler;
import dev.worldgennext.compiler.vulkan.fused.FusedGpuBackend;
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
    /** Fuse the SURFACE stage into the NOISE batch where the level's surface rules could be lowered. */
    public static final boolean SURFACE = Boolean.parseBoolean(System.getProperty("worldgennext.fast.surface", "true"));
    private static final int BATCH = Integer.getInteger("worldgennext.fast.batch", 64);
    private static final int SLOTS = Integer.getInteger("worldgennext.fast.slots", 4);
    private static final long MAX_DELAY_NANOS = TimeUnit.MICROSECONDS.toNanos(Long.getLong("worldgennext.fast.maxDelayMicros", 1500));
    private static final ThreadLocal<Boolean> ORIGINAL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static volatile FastNoiseEngine INSTANCE;

    private final FusedGpuBackend device;
    private final Map<RandomState, LevelProgram> programs = new ConcurrentHashMap<>();
    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private final Thread worker;
    private volatile boolean running = true;
    final AtomicLong gpuChunks = new AtomicLong(), fallbackChunks = new AtomicLong(), bailChunks = new AtomicLong(),
            verifiedChunks = new AtomicLong(), verifyMismatches = new AtomicLong(), batches = new AtomicLong(),
            surfaceChunks = new AtomicLong(), surfaceBails = new AtomicLong(), aquiferPrefills = new AtomicLong();

    /** @param surfaceHeader non-zero when the SURFACE stage is fused for this level */
    record LevelProgram(String dimension, FusedGpuBackend.Program program, BlockState[] palette,
                        FusedNoiseCompiler.Geometry geometry, WorldgenSnapshot snapshot, boolean aquifers,
                        int surfaceHeader, int basePaletteSize, FastSurfaceCapture.Captured surface,
                        FastChunkApplier.PaletteInfo paletteInfo) {}

    /** @param biomes 16-bit biome ids of the 6x6 quart-column window, or null when this chunk's surface stays vanilla */
    record Request(LevelProgram program, ChunkAccess chunk, NoiseBasedChunkGenerator generator, Blender blender,
                   RandomState randomState, StructureManager structures, int[] beard, int pieces, int junctions,
                   short[] biomes, CompletableFuture<ChunkAccess> result, long enqueuedNanos) {}

    private FastNoiseEngine(FusedGpuBackend device) {
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
        FusedGpuBackend device;
        try {
            device = GpuRuntimeLoader.open(server.getServerDirectory());
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
        LOG.info("Fast GPU NOISE stopped: gpu={} fallback={} bail={} batches={} verified={} verifyMismatches={} surface={} surfaceBail={} aquiferPrefill={}",
                engine.gpuChunks.get(), engine.fallbackChunks.get(), engine.bailChunks.get(), engine.batches.get(),
                engine.verifiedChunks.get(), engine.verifyMismatches.get(), engine.surfaceChunks.get(), engine.surfaceBails.get(),
                engine.aquiferPrefills.get());
        if (FastSurfaceState.VERIFY) LOG.info("Fast GPU SURFACE verify: chunks={} mismatched={}",
                FastSurfaceState.verified.get(), FastSurfaceState.mismatched.get());
        if (engine.device.profiling()) LOG.info("Fast GPU NOISE kernel profile: {}", engine.device.profileSummary());
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
        String dimension = level.dimension().location().toString();
        FastSurfaceCapture.Captured captured = null;
        if (SURFACE && !VERIFY) {
            try {
                captured = FastSurfaceCapture.capture(level, generator, palette);
            } catch (Throwable failure) {
                LOG.warn("Fast GPU SURFACE not available for {} (the SURFACE step stays vanilla): {}", dimension, failure.toString());
            }
        }
        FastSurfaceCapture.Captured surface = captured;
        var request = new FusedNoiseCompiler.Request(snapshot.router().roots(), snapshot.generatorSettings(),
                snapshot.randomState().aquiferRandom(), snapshot.randomState().oreRandom(), materialPalette, beardKernel(),
                surface == null ? null : surface.program());
        FusedNoiseCompiler.Compiled compiled = new FusedNoiseCompiler().compile(request);
        RandomState randomState = level.getChunkSource().randomState();
        String qualifiedAs = QUALIFIED.get(compiled.structureFingerprint());
        LOG.info("Fast GPU NOISE {}: kernel structure {} ({})", dimension, compiled.structureFingerprint(),
                qualifiedAs == null ? "not in the qualified list" : "qualified as " + qualifiedAs);
        if (qualifiedAs == null && MODE == GpuMode.AUTO) {
            LOG.warn("Fast GPU NOISE not enabled for {}: this generator's kernel structure has not passed qualification; "
                    + "using vanilla generation (set -Dworldgennext.fast.gpu=force to override)", dimension);
            return null;
        }
        boolean surfaceEnabled = false;
        if (surface != null) {
            String surfaceKey = "surface." + compiled.structureFingerprint() + "." + compiled.surfaceFingerprint();
            String surfaceQualifiedAs = QUALIFIED.get(surfaceKey);
            surfaceEnabled = surfaceQualifiedAs != null || MODE == GpuMode.FORCE;
            LOG.info("Fast GPU SURFACE {}: {} ({}), {} instructions, {} block states, {} noises", dimension, surfaceKey,
                    surfaceQualifiedAs == null ? (surfaceEnabled ? "not in the qualified list, forced" : "not in the qualified list, SURFACE stays vanilla")
                            : "qualified as " + surfaceQualifiedAs,
                    surface.program().code().length / dev.worldgennext.compiler.vulkan.fused.SurfaceProgram.INSTRUCTION_INTS,
                    surface.palette().length, surface.program().conditionNoises().size());
        }
        boolean fuseSurface = surfaceEnabled;
        return () -> {
            try {
                install(level, dimension, randomState, compiled, fuseSurface ? surface.palette() : palette, snapshot, start,
                        fuseSurface ? surface : null, palette.length);
            } catch (Throwable failure) {
                LOG.warn("Fast GPU NOISE disabled for {}: {}", dimension, failure.toString());
            }
        };
    }

    private void install(ServerLevel level, String dimension, RandomState randomState, FusedNoiseCompiler.Compiled compiled,
                         BlockState[] palette, WorldgenSnapshot snapshot, long start,
                         FastSurfaceCapture.Captured surface, int basePaletteSize) {
        dumpSource(level, compiled);
        if (Boolean.getBoolean("worldgennext.fast.dumpSourceOnly")) {
            throw new IllegalStateException("source dumped; pipeline build skipped by worldgennext.fast.dumpSourceOnly");
        }
        FusedGpuBackend.Program program = device.load(compiled, BATCH, SLOTS);
        programs.put(randomState, new LevelProgram(dimension, program, palette, compiled.geometry(), snapshot,
                snapshot.generatorSettings().aquifersEnabled(), surface == null ? 0 : compiled.surfaceHeader(), basePaletteSize, surface,
                new FastChunkApplier.PaletteInfo(palette, basePaletteSize)));
        LOG.info("Fast GPU NOISE ready for {}: flat={} interp={} source={} chars, compile {} ms (pipelines {} ms)",
                level.dimension().location(), compiled.flatChannels(), compiled.interpolatedChannels(), compiled.source().length(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), TimeUnit.NANOSECONDS.toMillis(program.compileNanos()));
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
        // fillFromNoise is called on the single worldgen mailbox thread; the structure and biome
        // gathering below runs on a worker (the original does the same work inside its own async task).
        CompletableFuture<ChunkAccess> result = new CompletableFuture<>();
        Util.backgroundExecutor().execute(() -> {
            try {
                if (!prepareAndQueue(program, generator, blender, randomState, structures, chunk, result)) {
                    fallbackChunks.incrementAndGet();
                    ORIGINAL.set(Boolean.TRUE);
                    CompletableFuture<ChunkAccess> vanilla;
                    try {
                        vanilla = generator.fillFromNoise(blender, randomState, structures, chunk);
                    } finally {
                        ORIGINAL.set(Boolean.FALSE);
                    }
                    vanilla.whenComplete((done, error) -> {
                        if (error != null) result.completeExceptionally(error);
                        else result.complete(done);
                    });
                }
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /** False when this chunk has to be generated by the original code. */
    private boolean prepareAndQueue(LevelProgram program, NoiseBasedChunkGenerator generator, Blender blender, RandomState randomState,
                                    StructureManager structures, ChunkAccess chunk, CompletableFuture<ChunkAccess> result) {
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
            return false;
        }
        if (beard.length > 2048) return false;
        short[] biomes = program.surfaceHeader() != 0 ? gatherBiomes(program, structures, chunk) : null;
        synchronized (queue) {
            queue.add(new Request(program, chunk, generator, blender, randomState, structures, beard, pieces, junctions,
                    biomes, result, System.nanoTime()));
            queue.notifyAll();
        }
        return true;
    }

    private static final Field STRUCTURE_LEVEL = structureLevelField();
    private static Field structureLevelField() {
        try {
            Field field = StructureManager.class.getDeclaredField("level");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }

    /**
     * Biome ids of the quart columns [-1, 4] x [-1, 4] around the chunk at every quart Y: everything
     * BiomeManager.getBiome can read for a block of this chunk.  Null keeps the chunk's SURFACE step vanilla.
     */
    private static short[] gatherBiomes(LevelProgram program, StructureManager structures, ChunkAccess chunk) {
        try {
            if (STRUCTURE_LEVEL == null || !(STRUCTURE_LEVEL.get(structures) instanceof net.minecraft.server.level.WorldGenRegion region)) return null;
            var surface = program.surface();
            int quartHeight = program.geometry().storageHeight() >> 2;
            int minQuartY = program.geometry().minY() >> 2;
            int firstQuartX = chunk.getPos().x * 4 - 1, firstQuartZ = chunk.getPos().z * 4 - 1;
            short[] out = new short[36 * quartHeight];
            for (int ix = 0; ix < 6; ix++) {
                for (int iz = 0; iz < 6; iz++) {
                    int qx = firstQuartX + ix, qz = firstQuartZ + iz;
                    ChunkAccess source = region.getChunk(qx >> 2, qz >> 2, net.minecraft.world.level.chunk.status.ChunkStatus.BIOMES, false);
                    if (source == null) return null;
                    int base = (ix * 6 + iz) * quartHeight;
                    Object previous = null;
                    int id = -1;
                    for (int iy = 0; iy < quartHeight; iy++) {
                        var holder = source.getNoiseBiome(qx, minQuartY + iy, qz);
                        if (holder != previous) {
                            previous = holder;
                            id = surface.biomeIds().getInt(holder);
                            if (id < 0) {
                                id = surface.biomes().getId(holder.value());
                                if (id < 0) return null;
                            }
                        }
                        out[base + iy] = (short) id;
                    }
                }
            }
            return out;
        } catch (Throwable failure) {
            return null;
        }
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
    private record InFlight(FusedGpuBackend.Slot slot, List<Request> requests) {}

    private void loop() {
        List<InFlight> inFlight = new ArrayList<>();
        Map<FusedGpuBackend.Program, Integer> nextSlot = new IdentityHashMap<>();
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
                FusedGpuBackend.Slot slot = freeSlot(program.program(), inFlight, nextSlot);
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

    private FusedGpuBackend.Slot freeSlot(FusedGpuBackend.Program program, List<InFlight> inFlight,
                                                   Map<FusedGpuBackend.Program, Integer> nextSlot) {
        for (int attempt = 0; attempt < program.slotCount(); attempt++) {
            int index = nextSlot.merge(program, 1, (a, b) -> (a + b) % program.slotCount());
            FusedGpuBackend.Slot slot = program.slot(index);
            boolean busy = false;
            for (InFlight f : inFlight) if (f.slot() == slot) { busy = true; break; }
            if (!busy && !slot.pending()) return slot;
        }
        return null;
    }

    private void submit(FusedGpuBackend.Slot slot, List<Request> requests) {
        ByteBuffer info = slot.chunkInfo();
        ByteBuffer beard = slot.beardData();
        int beardOffset = 0;
        boolean anySurface = false;
        LevelProgram level = requests.get(0).program();
        int biomeShorts = level.program().compiled().biomeWordsPerChunk() * 2;
        java.nio.ShortBuffer biomeIds = slot.biomes().asShortBuffer();
        for (int i = 0; i < requests.size(); i++) {
            Request r = requests.get(i);
            ChunkPos pos = r.chunk().getPos();
            int base = i * FusedGpuBackend.CHUNK_INFO_INTS * 4;
            info.putInt(base, pos.x);
            info.putInt(base + 4, pos.z);
            info.putInt(base + 8, beardOffset);
            info.putInt(base + 12, r.pieces());
            info.putInt(base + 16, r.junctions());
            info.putInt(base + 20, r.biomes() != null ? level.surfaceHeader() : 0);
            if (r.biomes() != null) {
                anySurface = true;
                biomeIds.put(i * biomeShorts, r.biomes());
            }
            for (int v : r.beard()) { beard.putInt(beardOffset * 4, v); beardOffset++; }
        }
        int prelimCount = 0;
        if (!level.aquifers() && anySurface) {
            // Only the surface rules read preliminary surfaces here: the four chunk-corner columns.
            var ids = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(requests.size() * 4);
            ids.defaultReturnValue(-1);
            java.nio.IntBuffer index = slot.prelimIndex().asIntBuffer();
            java.nio.IntBuffer columns = slot.prelimColumns().asIntBuffer();
            int window = dev.worldgennext.compiler.vulkan.fused.FusedNoiseCompiler.PRELIM_WINDOW;
            int origin = dev.worldgennext.compiler.vulkan.fused.FusedNoiseCompiler.PRELIM_ORIGIN;
            for (int i = 0; i < requests.size(); i++) {
                ChunkPos pos = requests.get(i).chunk().getPos();
                for (int corner = 0; corner < 4; corner++) {
                    int a = -origin + 4 * (corner & 1), b = -origin + 4 * (corner >> 1);
                    int qx = pos.x * 4 + origin + a, qz = pos.z * 4 + origin + b;
                    long key = ChunkPos.asLong(qx, qz);
                    int id = ids.get(key);
                    if (id < 0) {
                        id = prelimCount++;
                        ids.put(key, id);
                        columns.put(2 * id, qx << 2);
                        columns.put(2 * id + 1, qz << 2);
                    }
                    index.put((i * window + a) * window + b, id);
                }
            }
        }
        if (requests.get(0).program().aquifers()) {
            // Unique preliminary-surface columns of the whole batch, and each chunk's 31x31 index window.
            var ids = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(requests.size() * 256);
            ids.defaultReturnValue(-1);
            java.nio.IntBuffer index = slot.prelimIndex().asIntBuffer();
            java.nio.IntBuffer columns = slot.prelimColumns().asIntBuffer();
            int window = dev.worldgennext.compiler.vulkan.fused.FusedNoiseCompiler.PRELIM_WINDOW;
            int origin = dev.worldgennext.compiler.vulkan.fused.FusedNoiseCompiler.PRELIM_ORIGIN;
            for (int i = 0; i < requests.size(); i++) {
                ChunkPos pos = requests.get(i).chunk().getPos();
                int q0x = pos.x * 4 + origin, q0z = pos.z * 4 + origin;
                for (int a = 0; a < window; a++) {
                    for (int b = 0; b < window; b++) {
                        long key = ChunkPos.asLong(q0x + a, q0z + b);
                        int id = ids.get(key);
                        if (id < 0) {
                            id = prelimCount++;
                            ids.put(key, id);
                            columns.put(2 * id, (q0x + a) << 2);
                            columns.put(2 * id + 1, (q0z + b) << 2);
                        }
                        index.put((i * window + a) * window + b, id);
                    }
                }
            }
        }
        slot.submit(requests.size(), prelimCount, anySurface);
    }

    private void complete(InFlight batch) {
        FusedGpuBackend.Slot slot = batch.slot();
        LevelProgram program = batch.requests().get(0).program();
        var g = program.geometry();
        int blockBytes = g.blockCount();
        ByteBuffer blocks = slot.blocks();
        ByteBuffer heights = slot.heights().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < batch.requests().size(); i++) {
            Request r = batch.requests().get(i);
            int chunkFlags = slot.flags(i);
            int surfaceSoft = FusedNoiseCompiler.SURF_HEIGHT_CHANGED | FusedNoiseCompiler.SURF_STEEP_USED;
            if ((chunkFlags & FusedNoiseCompiler.BAIL_MASK) != 0 || (chunkFlags & surfaceSoft) == surfaceSoft) {
                // The surface kernels rewrite the block output in place, so a chunk whose surface result
                // cannot be trusted is regenerated as a whole by the original code.
                if ((chunkFlags & FusedNoiseCompiler.BAIL_MASK) == 0) surfaceBails.incrementAndGet();
                bailChunks.incrementAndGet();
                runOriginal(r);
                continue;
            }
            byte[] data = new byte[blockBytes];
            blocks.get(i * blockBytes, data);
            int[] h = new int[512];
            for (int k = 0; k < 512; k++) h[k] = heights.getInt((i * 512 + k) * 4);
            int[] aquiferCells = null;
            if (program.aquifers() && AquiferPrefill.ENABLED) {
                aquiferCells = new int[g.aquiferCellCount() * AquiferPrefill.STRIDE];
                slot.aquifers().duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(i * aquiferCells.length, aquiferCells);
            }
            int[] cells = aquiferCells;
            double[][] debug = null;
            if (VERIFY && device.debugBuffers()) {
                var compiled = program.program().compiled();
                int perColumns = Math.max(1, compiled.flatChannels()) * g.columnCount();
                int perCorners = Math.max(1, compiled.interpolatedChannels()) * g.cornerCount();
                double[] columns = new double[perColumns];
                double[] corners = new double[perCorners];
                slot.debugColumns().asDoubleBuffer().get(i * perColumns, columns);
                slot.debugCorners().asDoubleBuffer().get(i * perCorners, corners);
                debug = new double[][]{columns, corners};
            }
            double[][] debugFinal = debug;
            CompletableFuture.runAsync(() -> applyOrVerify(r, data, h, debugFinal, cells), Util.backgroundExecutor());
        }
    }

    private void applyOrVerify(Request r, byte[] data, int[] heights, double[][] debug, int[] aquiferCells) {
        try {
            var g = r.program().geometry();
            if (VERIFY) {
                ChunkAccess vanilla = original(r).join();
                verify(r, vanilla, data, heights, debug);
                r.result().complete(vanilla);
                return;
            }
            if (FastSurfaceState.VERIFY && r.biomes() != null) {
                ChunkAccess vanilla = original(r).join();
                FastSurfaceState.expect(vanilla, data, heights, r.program().palette());
                r.result().complete(vanilla);
                return;
            }
            LevelChunkSection[] sections = r.chunk().getSections();
            for (LevelChunkSection section : sections) section.acquire();
            try {
                FastChunkApplier.apply(r.chunk(), data, heights, r.program().paletteInfo(),
                        g.minY(), g.genHeight(), g.cellWidth(), g.cellHeight());
            } finally {
                for (LevelChunkSection section : sections) section.release();
            }
            if (r.biomes() != null) {
                FastSurfaceState.mark(r.chunk());
                surfaceChunks.incrementAndGet();
            }
            if (aquiferCells != null && AquiferPrefill.fill(r.chunk(), aquiferCells, g.aquiferCellsY(), r.program().palette()[2])) {
                aquiferPrefills.incrementAndGet();
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
        var compiled = r.program().program().compiled();
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
