// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.fast;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;

import java.util.BitSet;

/**
 * OreFeature.doPlace (Minecraft 1.21.1) with the same statements in the same
 * order, except that the per-axis terms of the sphere test are computed once
 * per sphere instead of once per candidate block.
 *
 * <p>The original evaluates {@code ((y + 0.5 - cy) / r)^2} and
 * {@code ((z + 0.5 - cz) / r)^2} inside the innermost loops, although each
 * depends on one loop variable only.  The values here are produced by the
 * same double operations and added in the same order, so every comparison
 * sees the same number; visited blocks, their order, and every random draw
 * are unchanged.</p>
 */
public final class FastOrePlacement {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("worldgennext.fast.orePlacement", "true"));

    private static final ThreadLocal<double[][]> SCRATCH = ThreadLocal.withInitial(() -> new double[][]{new double[32], new double[32]});

    private FastOrePlacement() {}

    public static boolean doPlace(WorldGenLevel level, RandomSource random, OreConfiguration config,
                                  double minXCentre, double maxXCentre, double minZCentre, double maxZCentre,
                                  double minYCentre, double maxYCentre, int x, int y, int z, int width, int height) {
        int placed = 0;
        BitSet visited = new BitSet(width * height * width);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
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

        double[][] scratch = SCRATCH.get();
        try (BulkSectionAccess sections = new BulkSectionAccess(level)) {
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
                    if (scratch[0].length < ySpan) scratch[0] = new double[ySpan];
                    if (scratch[1].length < zSpan) scratch[1] = new double[zSpan];
                    double[] ySquares = scratch[0], zSquares = scratch[1];
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
                                                            if (OreFeature.canPlaceOre(state, sections::getBlockState, random, config, target, pos)) {
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
