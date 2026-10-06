// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.fast;

import dev.worldgennext.neoforge.loader.Names;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseChunk;

import java.lang.reflect.Field;

/**
 * Hands the aquifer grid the fused kernels computed for a chunk to that
 * chunk's own NoiseBasedAquifer.
 *
 * <p>The aquifer keeps two lazily filled per-chunk caches: the randomized
 * centre of every grid cell and that cell's fluid status.  The original NOISE
 * stage fills them as a side effect, and CARVERS, which asks the aquifer what
 * a carved block becomes, then finds them filled.  With NOISE on the GPU the
 * caches stay empty and CARVERS recomputes every status it touches on the CPU
 * (a preliminary-surface scan and several noise samples each).  The kernels
 * already computed exactly these values for the same grid; storing them is
 * what the original stage would have left behind for the cells it visited,
 * and the cached value of a cell is a pure function of the cell.</p>
 */
public final class AquiferPrefill {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("worldgennext.fast.aquiferPrefill", "true"));
    /** ints per aquifer cell in the kernel output: x, y, z, fluid level, fluid type code, three unused. */
    public static final int STRIDE = 8;

    private static final Field CACHE = field("aquiferCache"), LOCATIONS = field("aquiferLocationCache"),
            SIZE_X = field("gridSizeX"), SIZE_Z = field("gridSizeZ");

    private AquiferPrefill() {}

    private static Field field(String name) {
        try {
            Field field = Names.declaredField(Aquifer.NoiseBasedAquifer.class, name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /**
     * @param cells kernel output for this chunk, cell order (gridY * 9 + gridX * 3 + gridZ)
     * @param defaultFluid the generator's default fluid (type code 1); code 2 is lava
     * @return false when the chunk's aquifer does not have the expected shape and was left untouched
     */
    public static boolean fill(ChunkAccess chunk, int[] cells, int cellsY, BlockState defaultFluid) {
        if (!ENABLED || CACHE == null || LOCATIONS == null || SIZE_X == null || SIZE_Z == null) return false;
        try {
            NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(ignored -> null);
            if (noiseChunk == null || !(noiseChunk.aquifer() instanceof Aquifer.NoiseBasedAquifer aquifer)) return false;
            Aquifer.FluidStatus[] cache = (Aquifer.FluidStatus[]) CACHE.get(aquifer);
            long[] locations = (long[]) LOCATIONS.get(aquifer);
            if (SIZE_X.getInt(aquifer) != 3 || SIZE_Z.getInt(aquifer) != 3 || cache.length != 9 * cellsY
                    || locations.length != cache.length || cells.length != cache.length * STRIDE) {
                return false;
            }
            BlockState lava = Blocks.LAVA.defaultBlockState();
            for (int cy = 0; cy < cellsY; cy++) {
                for (int cx = 0; cx < 3; cx++) {
                    for (int cz = 0; cz < 3; cz++) {
                        int o = (cy * 9 + cx * 3 + cz) * STRIDE;
                        int type = cells[o + 4];
                        if (type != 1 && type != 2) return false; // the kernels only produce these two; keep the original path otherwise
                        int index = (cy * 3 + cz) * 3 + cx; // NoiseBasedAquifer.getIndex
                        if (locations[index] == Long.MAX_VALUE) locations[index] = BlockPos.asLong(cells[o], cells[o + 1], cells[o + 2]);
                        if (cache[index] == null) cache[index] = new Aquifer.FluidStatus(cells[o + 3], type == 1 ? defaultFluid : lava);
                    }
                }
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }
}
