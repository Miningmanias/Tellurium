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
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("worldgennext.fast.gpu", "false"));
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
                        FusedNoiseCompiler.Geometry geometry) {}

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

    /** Opens the device and compiles every NoiseBasedChunkGenerator level; failures leave that level on vanilla. */
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
        LOG.info("Fast GPU NOISE device: {}", device.deviceName());
        for (ServerLevel level : server.getAllLevels()) {
            try {
                engine.compile(level);
            } catch (Throwable failure) {
                LOG.warn("Fast GPU NOISE disabled for {}: {}", level.dimension().location(), failure.toString());
            }
        }
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

    private void compile(ServerLevel level) throws Exception {
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) return;
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
        dumpSource(level, compiled);
        FusedNoiseDevice.Program program = device.load(compiled, BATCH, SLOTS);
        programs.put(level.getChunkSource().randomState(),
                new LevelProgram(level.dimension().location().toString(), program, palette, compiled.geometry()));
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
            CompletableFuture.runAsync(() -> applyOrVerify(r, data, h), Util.backgroundExecutor());
        }
    }

    private void applyOrVerify(Request r, byte[] data, int[] heights) {
        try {
            var g = r.program().geometry();
            if (VERIFY) {
                ChunkAccess vanilla = original(r).join();
                verify(r, vanilla, data, heights);
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

    private void verify(Request r, ChunkAccess vanilla, byte[] data, int[] heights) {
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
        if (mismatches > 0) {
            verifyMismatches.incrementAndGet();
            LOG.warn("Fast GPU NOISE verify mismatch {} at chunk {}: {} blocks, first {}", r.program().dimension(),
                    vanilla.getPos(), mismatches, first);
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
