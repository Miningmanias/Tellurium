// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.fast;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.function.Function;

/**
 * OreFeature.doPlace (Minecraft 1.21.1): the same blocks are tested in the
 * same order with the same random draws, but the scan is cheaper.
 *
 * <p>The original walks the bounding box of every sphere of the vein and, for
 * each candidate block, evaluates the sphere test and looks the block up in a
 * BitSet of visited positions.  Spheres of one vein overlap heavily, so most
 * candidates are rejected by that lookup.</p>
 *
 * <ul>
 * <li>The per-axis terms {@code ((y + 0.5 - cy) / r)^2} and
 *     {@code ((z + 0.5 - cz) / r)^2} depend on one loop variable only and are
 *     computed once per sphere, by the same double operations, and added in
 *     the same order.</li>
 * <li>Visited positions are kept as one 64-bit word per (x, y) row with one
 *     bit per z.  A row's sphere test produces a mask; the blocks to process
 *     are {@code mask & ~visited}, taken in ascending z, which is the order of
 *     the original inner loop.  This layout is used only when every sphere's
 *     box lies inside the vein's width/height box and the width fits a word;
 *     otherwise the original index arithmetic (including its aliasing) is
 *     kept.</li>
 * <li>A rule test of one of the three vanilla classes that ignore the random
 *     source is not repeated for the block state it was last given.</li>
 * </ul>
 */
public final class FastOrePlacement {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellurium.fast.orePlacement", "true"));
    private static final boolean ROWS = Boolean.parseBoolean(System.getProperty("tellurium.fast.oreRows", "true"));
    private static final int MAX_ROWS = 1 << 16;

    private static final class Scratch {
        double[] ySquares = new double[32];
        double[] zSquares = new double[32];
        long[] visited = new long[512];
        BlockState[] memoState = new BlockState[4];
        boolean[] memoPass = new boolean[4];
        boolean[] pure = new boolean[4];
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private FastOrePlacement() {}

    public static boolean doPlace(WorldGenLevel level, RandomSource random, OreConfiguration config,
                                  double minXCentre, double maxXCentre, double minZCentre, double maxZCentre,
                                  double minYCentre, double maxYCentre, int x, int y, int z, int width, int height) {
        int size = config.size;
        double[] spheres = new double[size * 4];

        for (int k = 0; k < size; k++) {
            float f = (float) k / (float) size;
            double d0 = Mth.lerp((double) f, minXCentre, maxXCentre);
            double d1 = Mth.lerp((double) f, minYCentre, maxYCentre);
            double d2 = Mth.lerp((double) f, minZCentre, maxZCentre);
            double d3 = random.nextDouble() * (double) size / 16.0;
            double d4 = ((double) (Mth.sin((float) Math.PI * f) + 1.0F) * d3 + 1.0) / 2.0;
            spheres[k * 4] = d0;
            spheres[k * 4 + 1] = d1;
            spheres[k * 4 + 2] = d2;
            spheres[k * 4 + 3] = d4;
        }

        for (int a = 0; a < size - 1; a++) {
            if (!(spheres[a * 4 + 3] <= 0.0)) {
                for (int b = a + 1; b < size; b++) {
                    if (!(spheres[b * 4 + 3] <= 0.0)) {
                        double d8 = spheres[a * 4] - spheres[b * 4];
                        double d10 = spheres[a * 4 + 1] - spheres[b * 4 + 1];
                        double d12 = spheres[a * 4 + 2] - spheres[b * 4 + 2];
                        double d14 = spheres[a * 4 + 3] - spheres[b * 4 + 3];
                        if (d14 * d14 > d8 * d8 + d10 * d10 + d12 * d12) {
                            if (d14 > 0.0) {
                                spheres[b * 4 + 3] = -1.0;
                            } else {
                                spheres[a * 4 + 3] = -1.0;
                            }
                        }
                    }
                }
            }
        }

        Scratch scratch = SCRATCH.get();
        return rowLayoutFits(spheres, size, x, y, z, width, height)
                ? placeRows(level, random, config, spheres, x, y, z, width, height, scratch)
                : placeIndexed(level, random, config, spheres, x, y, z, width, height, scratch);
    }

    /** True when every sphere's box maps to distinct in-range (x, y, z) offsets, so one word per row is an exact visited set. */
    private static boolean rowLayoutFits(double[] spheres, int size, int x, int y, int z, int width, int height) {
        if (!ROWS || width <= 0 || width > 64 || height <= 0 || (long) width * height > MAX_ROWS) return false;
        for (int s = 0; s < size; s++) {
            double radius = spheres[s * 4 + 3];
            if (!(radius < 0.0)) {
                double cx = spheres[s * 4];
                double cy = spheres[s * 4 + 1];
                double cz = spheres[s * 4 + 2];
                int x0 = Math.max(Mth.floor(cx - radius), x);
                int y0 = Math.max(Mth.floor(cy - radius), y);
                int z0 = Math.max(Mth.floor(cz - radius), z);
                int x1 = Math.max(Mth.floor(cx + radius), x0);
                int y1 = Math.max(Mth.floor(cy + radius), y0);
                int z1 = Math.max(Mth.floor(cz + radius), z0);
                // Long arithmetic: a NaN or huge centre must not wrap into range.
                if ((long) x1 - x >= width || (long) y1 - y >= height || (long) z1 - z >= width) return false;
            }
        }
        return true;
    }

    private static boolean placeRows(WorldGenLevel level, RandomSource random, OreConfiguration config, double[] spheres,
                                     int x, int y, int z, int width, int height, Scratch scratch) {
        int placed = 0;
        int size = config.size;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int rows = width * height;
        if (scratch.visited.length < rows) scratch.visited = new long[rows];
        long[] visited = scratch.visited;
        Arrays.fill(visited, 0, rows, 0L);

        List<OreConfiguration.TargetBlockState> targets = config.targetStates;
        int targetCount = targets.size();
        if (scratch.pure.length < targetCount) {
            scratch.pure = new boolean[targetCount];
            scratch.memoPass = new boolean[targetCount];
            scratch.memoState = new BlockState[targetCount];
        }
        boolean[] pure = scratch.pure;
        boolean[] memoPass = scratch.memoPass;
        BlockState[] memoState = scratch.memoState;
        for (int t = 0; t < targetCount; t++) {
            Class<?> test = targets.get(t).target.getClass();
            pure[t] = test == TagMatchTest.class || test == BlockMatchTest.class || test == BlockStateMatchTest.class;
            memoState[t] = null;
        }
        float discardChance = config.discardChanceOnAirExposure;

        try (BulkSectionAccess sections = new BulkSectionAccess(level)) {
            Function<BlockPos, BlockState> adjacent = sections::getBlockState;
            for (int s = 0; s < size; s++) {
                double radius = spheres[s * 4 + 3];
                if (!(radius < 0.0)) {
                    double cx = spheres[s * 4];
                    double cy = spheres[s * 4 + 1];
                    double cz = spheres[s * 4 + 2];
                    int x0 = Math.max(Mth.floor(cx - radius), x);
                    int y0 = Math.max(Mth.floor(cy - radius), y);
                    int z0 = Math.max(Mth.floor(cz - radius), z);
                    int x1 = Math.max(Mth.floor(cx + radius), x0);
                    int y1 = Math.max(Mth.floor(cy + radius), y0);
                    int z1 = Math.max(Mth.floor(cz + radius), z0);

                    int ySpan = y1 - y0 + 1, zSpan = z1 - z0 + 1;
                    if (scratch.ySquares.length < ySpan) scratch.ySquares = new double[ySpan];
                    if (scratch.zSquares.length < zSpan) scratch.zSquares = new double[zSpan];
                    double[] ySquares = scratch.ySquares, zSquares = scratch.zSquares;
                    for (int by = y0; by <= y1; by++) {
                        double d6 = ((double) by + 0.5 - cy) / radius;
                        ySquares[by - y0] = d6 * d6;
                    }
                    for (int bz = z0; bz <= z1; bz++) {
                        double d7 = ((double) bz + 0.5 - cz) / radius;
                        zSquares[bz - z0] = d7 * d7;
                    }
                    int zShift = z0 - z;

                    for (int bx = x0; bx <= x1; bx++) {
                        double d5 = ((double) bx + 0.5 - cx) / radius;
                        double xSquare = d5 * d5;
                        if (xSquare < 1.0) {
                            int rowBase = (bx - x) * height - y;
                            for (int by = y0; by <= y1; by++) {
                                double xy = xSquare + ySquares[by - y0];
                                if (xy < 1.0 && !level.isOutsideBuildHeight(by)) {
                                    long mask = 0L;
                                    for (int k = 0; k < zSpan; k++) {
                                        if (xy + zSquares[k] < 1.0) mask |= 1L << k;
                                    }
                                    mask <<= zShift;
                                    int row = rowBase + by;
                                    long fresh = mask & ~visited[row];
                                    visited[row] |= mask;

                                    while (fresh != 0L) {
                                        int bz = z + Long.numberOfTrailingZeros(fresh);
                                        fresh &= fresh - 1;
                                        pos.set(bx, by, bz);
                                        if (level.ensureCanWrite(pos)) {
                                            LevelChunkSection section = sections.getSection(pos);
                                            if (section != null) {
                                                int sx = SectionPos.sectionRelative(bx);
                                                int sy = SectionPos.sectionRelative(by);
                                                int sz = SectionPos.sectionRelative(bz);
                                                BlockState state = section.getBlockState(sx, sy, sz);

                                                for (int t = 0; t < targetCount; t++) {
                                                    OreConfiguration.TargetBlockState target = targets.get(t);
                                                    boolean place;
                                                    if (pure[t]) {
                                                        boolean pass;
                                                        if (memoState[t] == state) {
                                                            pass = memoPass[t];
                                                        } else {
                                                            pass = target.target.test(state, random);
                                                            memoState[t] = state;
                                                            memoPass[t] = pass;
                                                        }
                                                        // The rest of OreFeature.canPlaceOre, in its order.
                                                        place = pass && (skipAirCheck(random, discardChance) || !Feature.isAdjacentToAir(adjacent, pos));
                                                    } else {
                                                        place = OreFeature.canPlaceOre(state, adjacent, random, config, target, pos);
                                                    }
                                                    if (place) {
                                                        section.setBlockState(sx, sy, sz, target.state, false);
                                                        placed++;
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            Arrays.fill(memoState, 0, targetCount, null);
        }

        return placed > 0;
    }

    /** OreFeature.shouldSkipAirCheck. */
    private static boolean skipAirCheck(RandomSource random, float chance) {
        if (chance <= 0.0F) {
            return true;
        } else {
            return chance >= 1.0F ? false : random.nextFloat() >= chance;
        }
    }

    /** The original visited index, for veins whose spheres can leave the width/height box. */
    private static boolean placeIndexed(WorldGenLevel level, RandomSource random, OreConfiguration config, double[] spheres,
                                        int x, int y, int z, int width, int height, Scratch scratch) {
        int placed = 0;
        int size = config.size;
        BitSet visited = new BitSet(width * height * width);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        try (BulkSectionAccess sections = new BulkSectionAccess(level)) {
            Function<BlockPos, BlockState> adjacent = sections::getBlockState;
            for (int s = 0; s < size; s++) {
                double radius = spheres[s * 4 + 3];
                if (!(radius < 0.0)) {
                    double cx = spheres[s * 4];
                    double cy = spheres[s * 4 + 1];
                    double cz = spheres[s * 4 + 2];
                    int x0 = Math.max(Mth.floor(cx - radius), x);
                    int y0 = Math.max(Mth.floor(cy - radius), y);
                    int z0 = Math.max(Mth.floor(cz - radius), z);
                    int x1 = Math.max(Mth.floor(cx + radius), x0);
                    int y1 = Math.max(Mth.floor(cy + radius), y0);
                    int z1 = Math.max(Mth.floor(cz + radius), z0);

                    int ySpan = y1 - y0 + 1, zSpan = z1 - z0 + 1;
                    if (scratch.ySquares.length < ySpan) scratch.ySquares = new double[ySpan];
                    if (scratch.zSquares.length < zSpan) scratch.zSquares = new double[zSpan];
                    double[] ySquares = scratch.ySquares, zSquares = scratch.zSquares;
                    for (int by = y0; by <= y1; by++) {
                        double d6 = ((double) by + 0.5 - cy) / radius;
                        ySquares[by - y0] = d6 * d6;
                    }
                    for (int bz = z0; bz <= z1; bz++) {
                        double d7 = ((double) bz + 0.5 - cz) / radius;
                        zSquares[bz - z0] = d7 * d7;
                    }

                    for (int bx = x0; bx <= x1; bx++) {
                        double d5 = ((double) bx + 0.5 - cx) / radius;
                        double xSquare = d5 * d5;
                        if (xSquare < 1.0) {
                            for (int by = y0; by <= y1; by++) {
                                double xy = xSquare + ySquares[by - y0];
                                if (xy < 1.0) {
                                    for (int bz = z0; bz <= z1; bz++) {
                                        if (xy + zSquares[bz - z0] < 1.0 && !level.isOutsideBuildHeight(by)) {
                                            int index = bx - x + (by - y) * width + (bz - z) * width * height;
                                            if (!visited.get(index)) {
                                                visited.set(index);
                                                pos.set(bx, by, bz);
                                                if (level.ensureCanWrite(pos)) {
                                                    LevelChunkSection section = sections.getSection(pos);
                                                    if (section != null) {
                                                        int sx = SectionPos.sectionRelative(bx);
                                                        int sy = SectionPos.sectionRelative(by);
                                                        int sz = SectionPos.sectionRelative(bz);
                                                        BlockState state = section.getBlockState(sx, sy, sz);

                                                        for (OreConfiguration.TargetBlockState target : config.targetStates) {
                                                            if (OreFeature.canPlaceOre(state, adjacent, random, config, target, pos)) {
                                                                section.setBlockState(sx, sy, sz, target.state, false);
                                                                placed++;
                                                                break;
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return placed > 0;
    }
}
