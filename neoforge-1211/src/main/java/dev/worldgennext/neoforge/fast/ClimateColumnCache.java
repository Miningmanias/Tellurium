// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.fast;

import dev.worldgennext.compiler.vulkan.fused.FusedNoiseCompiler;
import dev.worldgennext.semantic.program.ProgramNode;
import dev.worldgennext.semantic.snapshot.WorldgenSnapshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BIOMES samples six climate functions at every quart position of a chunk
 * (4 x 4 columns, one sample per quart Y).  In the vanilla router five of the
 * six ignore Y, yet each is re-evaluated for every Y.  For a function whose
 * captured graph provably ignores Y, this wrapper evaluates the original
 * function once per column and reuses the value: the function is pure, so the
 * biome resolver receives exactly the values it would have computed.
 *
 * <p>The Y-independence proof is the same conservative analysis the fused
 * kernels use; a router that cannot be captured, a non-empty blender or a
 * function that may depend on Y keeps the original evaluation.</p>
 */
public final class ClimateColumnCache {
    private static final Logger LOG = LoggerFactory.getLogger("worldgennext-fast");
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("worldgennext.biomeColumnCache", "true"));
    private static final String[] ROOTS = {"temperature", "vegetation", "continents", "erosion", "depth", "ridges"};
    /** Per level: which of the six climate functions provably ignore Y. */
    private static final Map<RandomState, boolean[]> COLUMN_ONLY = new ConcurrentHashMap<>();
    /**
     * Levels whose six climate functions give the same single-point values without NoiseChunk's cache
     * wrappers; BIOMES can then sample RandomState.sampler() and need not build a NoiseChunk.
     */
    private static final Map<RandomState, Boolean> UNWRAPPED = new ConcurrentHashMap<>();
    public static final boolean UNWRAPPED_ENABLED = Boolean.parseBoolean(System.getProperty("worldgennext.biomeUnwrappedSampler", "true"));

    private ClimateColumnCache() {}

    public static void start(MinecraftServer server) {
        COLUMN_ONLY.clear();
        UNWRAPPED.clear();
        if (!ENABLED) return;
        for (ServerLevel level : server.getAllLevels()) {
            if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator)) continue;
            String dimension = level.dimension().location().toString();
            try {
                WorldgenSnapshot snapshot = FastRouterCapture.capture(level);
                boolean[] columnOnly = new boolean[ROOTS.length];
                StringBuilder names = new StringBuilder();
                for (int i = 0; i < ROOTS.length; i++) {
                    ProgramNode root = snapshot.router().roots().get(ROOTS[i]);
                    columnOnly[i] = root != null && !FusedNoiseCompiler.dependsOnY(root);
                    if (columnOnly[i]) names.append(names.isEmpty() ? "" : ", ").append(ROOTS[i]);
                }
                COLUMN_ONLY.put(level.getChunkSource().randomState(), columnOnly);
                // Without the NoiseChunk there is no FlatCache: only worthwhile when every function is
                // evaluated once per column anyway.
                boolean unwrapped = UNWRAPPED_ENABLED;
                for (boolean column : columnOnly) unwrapped &= column;
                for (String name : ROOTS) {
                    ProgramNode root = snapshot.router().roots().get(name);
                    unwrapped &= root != null && FusedNoiseCompiler.pointValueIgnoresCaches(root);
                }
                if (unwrapped) UNWRAPPED.put(level.getChunkSource().randomState(), Boolean.TRUE);
                LOG.debug("Biome column cache {}: {}{}", dimension, names.isEmpty() ? "no Y-independent climate function" : names,
                        unwrapped ? "; BIOMES samples the router without a NoiseChunk" : "");
            } catch (Throwable failure) {
                LOG.debug("Biome column cache not used for {}: {}", dimension, failure.toString());
            }
        }
    }

    public static void stop() {
        LOG.debug("Biome index: lookups={} fast={} verifyMismatches={}", ColumnBiomeIndex.lookups.get(),
                ColumnBiomeIndex.fastLookups.get(), ColumnBiomeIndex.mismatches.get());
        COLUMN_ONLY.clear();
        UNWRAPPED.clear();
    }

    /**
     * BIOMES without a NoiseChunk: null when this level or chunk must take the original path.
     * The NoiseChunk is then created by the first later stage that asks for it, as usual.
     */
    public static Climate.Sampler unwrappedSampler(Blender blender, RandomState randomState, ChunkAccess chunk) {
        if (blender != Blender.empty() || !UNWRAPPED.containsKey(randomState)) return null;
        return wrap(randomState.sampler(), blender, randomState, chunk);
    }

    /** Hook from NoiseBasedChunkGenerator.doCreateBiomes. */
    public static Climate.Sampler wrap(Climate.Sampler sampler, Blender blender, RandomState randomState, ChunkAccess chunk) {
        boolean[] columnOnly = COLUMN_ONLY.get(randomState);
        if (columnOnly == null || blender != Blender.empty()) return sampler;
        return new Climate.Sampler(
                columnOnly[0] ? new PerColumn(sampler.temperature()) : sampler.temperature(),
                columnOnly[1] ? new PerColumn(sampler.humidity()) : sampler.humidity(),
                columnOnly[2] ? new PerColumn(sampler.continentalness()) : sampler.continentalness(),
                columnOnly[3] ? new PerColumn(sampler.erosion()) : sampler.erosion(),
                columnOnly[4] ? new PerColumn(sampler.depth()) : sampler.depth(),
                columnOnly[5] ? new PerColumn(sampler.weirdness()) : sampler.weirdness(),
                sampler.spawnTarget());
    }

    /** One value per quart column of the chunk being filled; used by a single thread. */
    private static final class PerColumn implements DensityFunction {
        private final DensityFunction original;
        private final int[] xs = new int[16];
        private final int[] zs = new int[16];
        private final double[] values = new double[16];
        private int valid;

        PerColumn(DensityFunction original) { this.original = original; }

        @Override
        public double compute(FunctionContext context) {
            int x = context.blockX(), z = context.blockZ();
            int slot = (((x >> 2) & 3) << 2) | ((z >> 2) & 3);
            int bit = 1 << slot;
            if ((valid & bit) != 0 && xs[slot] == x && zs[slot] == z) return values[slot];
            double value = original.compute(context);
            xs[slot] = x;
            zs[slot] = z;
            values[slot] = value;
            valid |= bit;
            return value;
        }

        @Override
        public void fillArray(double[] array, ContextProvider provider) { original.fillArray(array, provider); }

        @Override
        public DensityFunction mapAll(Visitor visitor) { return original.mapAll(visitor); }

        @Override
        public double minValue() { return original.minValue(); }

        @Override
        public double maxValue() { return original.maxValue(); }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() { return original.codec(); }
    }
}
