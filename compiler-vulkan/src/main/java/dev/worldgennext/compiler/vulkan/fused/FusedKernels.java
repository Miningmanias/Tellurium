// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan.fused;

import dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot;

/**
 * GLSL kernel templates for the fused NOISE route.  Each block mirrors a
 * vanilla 1.21.1 method line by line: NoiseBasedAquifer.computeSubstance /
 * computeFluid / computeSurfaceLevel / calculatePressure, OreVeinifier,
 * Beardifier.compute and NoiseBasedChunkGenerator.doFill.
 */
final class FusedKernels {
    private FusedKernels() {}

    /** Output: one byte per block (palette index | 0x80 post-processing mark), four blocks per uint along X. */
    static final int MARK_BIT = 0x80;

    /** Constants, setup and helpers that generated root functions may call. */
    static String prelude(FusedNoiseCompiler.Geometry g, FusedNoiseCompiler.Request request, int beardKernelIndex) {
        var p = request.palette();
        var settings = request.settings();
        StringBuilder s = new StringBuilder();
        s.append("const int MIN_Y = ").append(g.minY()).append(";\n");
        s.append("const int GEN_HEIGHT = ").append(g.genHeight()).append(";\n");
        s.append("const int STORAGE_H = ").append(g.storageHeight()).append(";\n");
        s.append("const int SEA_LEVEL = ").append(settings.seaLevel()).append(";\n");
        s.append("const int AQ_CELLS_Y = ").append(g.aquiferCellsY()).append(";\n");
        s.append("const int AQ_MIN_GRID_Y = ").append(g.aquiferMinGridYOffset()).append(";\n");
        s.append("const int AQ_STRIDE = 8;\n");
        s.append("const bool AQUIFERS = ").append(settings.aquifersEnabled()).append(";\n");
        s.append("const bool ORES = ").append(settings.oresEnabled()).append(";\n");
        s.append("const int P_AIR = ").append(p.air()).append(", P_DEFAULT_BLOCK = ").append(p.defaultBlock())
                .append(", P_DEFAULT_FLUID = ").append(p.defaultFluid()).append(", P_LAVA = ").append(p.lava()).append(";\n");
        s.append("const int P_COPPER_ORE = ").append(p.copperOre()).append(", P_RAW_COPPER = ").append(p.rawCopperBlock())
                .append(", P_GRANITE = ").append(p.granite()).append(";\n");
        s.append("const int P_IRON_ORE = ").append(p.ironOre()).append(", P_RAW_IRON = ").append(p.rawIronBlock())
                .append(", P_TUFF = ").append(p.tuff()).append(";\n");
        s.append("const bool DF_IS_WATER = ").append(p.defaultFluidIsWater()).append(", DF_IS_LAVA = ")
                .append(p.defaultFluidIsLava()).append(", DF_IS_LAVA_DEFAULT = ").append(p.defaultFluidIsLavaDefaultState())
                .append(", DF_IS_AIR = ").append(p.defaultFluidIsAir()).append(";\n");
        s.append("const int PALETTE_SIZE = ").append(p.size()).append(";\n");
        s.append("const int PALETTE_FLAGS[").append(p.size()).append("] = int[](");
        for (int i = 0; i < p.size(); i++) s.append(i == 0 ? "" : ", ").append(p.paletteFlags()[i]);
        s.append(");\n");
        s.append("const int BEARD_KERNEL = ").append(beardKernelIndex).append(";\n");
        appendRandom(s, "AQ", request.aquiferRandom());
        appendRandom(s, "ORE", request.oreRandom());
        s.append("const double FLOWING_UPDATE_SIMILARITY = ")
                .append(FusedNoiseCompiler.dlit(1.0 - (double) Math.abs(144 - 100) / 25.0)).append(";\n");
        s.append(PRELUDE_BODY);
        return s.toString();
    }

    /** Aquifer, ore, material and the five kernel entry points; placed after generated functions. */
    static String kernels(int flatCount, int interpCount, int xzCount, FusedNoiseCompiler.FluidUpdates fluidUpdates) {
        return kernels(flatCount, interpCount, xzCount, fluidUpdates, FusedNoiseCompiler.PreliminarySurface.DENSITY_SEARCH);
    }

    static String kernels(int flatCount, int interpCount, int xzCount, FusedNoiseCompiler.FluidUpdates fluidUpdates,
                          FusedNoiseCompiler.PreliminarySurface preliminarySurface) {
        StringBuilder s = new StringBuilder();
        StringBuilder columnStores = new StringBuilder();
        for (int f = 0; f < flatCount; f++) {
            columnStores.append("columns[((int(slot) * FLATS + ").append(f).append(") * COLS + qi) * COLS + qj] = flat_")
                    .append(f).append("_column(p);").append(System.lineSeparator()).append("            ");
        }
        StringBuilder cornerStores = new StringBuilder();
        for (int k = 0; k < interpCount; k++) {
            cornerStores.append("corners[(((int(slot) * INTERPS + ").append(k).append(") * CNY + iy) * CNX + ix) * CNX + iz] = interp_")
                    .append(k).append("_corner(p);").append(System.lineSeparator()).append("            ");
        }
        StringBuilder xzStores = new StringBuilder();
        for (int k = 0; k < xzCount; k++) {
            xzStores.append("xzcache[(int(slot) * XZS + ").append(k).append(") * 256 + col] = xz_").append(k)
                    .append("_col(p);").append(System.lineSeparator()).append("            ");
        }
        String body = fluidUpdates == FusedNoiseCompiler.FluidUpdates.WHERE_NEIGHBOURS_DIFFER ? comparingFluidUpdates(KERNEL_BODY) : KERNEL_BODY;
        if (preliminarySurface == FusedNoiseCompiler.PreliminarySurface.LEVEL_FUNCTION) body = skippingAboveSurface(body);
        s.append(body.replace("COLUMN_STORES", columnStores.toString()).replace("CORNER_STORES", cornerStores.toString())
                .replace("XZ_STORES", xzStores.toString()));
        return s.toString();
    }

    /**
     * The kernel text with Minecraft 1.21.2's fluid-update rule in computeSubstance.  The rule is put in by
     * rewriting the text, so the kernels of the earlier rule stay character for character what they were:
     * their text is what the list of tested generators is keyed on.
     */
    private static String comparingFluidUpdates(String body) {
        body = swap(body, """
            int k1 = 2147483647, l1 = 2147483647, i2 = 2147483647;
            int j2 = -1, k2 = -1, l2 = -1;
        """, """
            int k1 = 2147483647, l1 = 2147483647, i2 = 2147483647, i4 = 2147483647;
            int j2 = -1, k2 = -1, l2 = -1, m2 = -1;
        """);
        body = swap(body, """
                        if (k1 >= dist) {
                            l2 = k2; k2 = j2; j2 = slot;
                            i2 = l1; l1 = k1; k1 = dist;
                        } else if (l1 >= dist) {
                            l2 = k2; k2 = slot;
                            i2 = l1; l1 = dist;
                        } else if (i2 >= dist) {
                            l2 = slot; i2 = dist;
                        }
        """, """
                        if (k1 >= dist) {
                            m2 = l2; l2 = k2; k2 = j2; j2 = slot;
                            i4 = i2; i2 = l1; l1 = k1; k1 = dist;
                        } else if (l1 >= dist) {
                            m2 = l2; l2 = k2; k2 = slot;
                            i4 = i2; i2 = l1; l1 = dist;
                        } else if (i2 >= dist) {
                            m2 = l2; l2 = slot;
                            i4 = i2; i2 = dist;
                        } else if (i4 >= dist) {
                            m2 = slot; i4 = dist;
                        }
        """);
        body = swap(body, """
                schedule = d1 >= FLOWING_UPDATE_SIMILARITY;
                return fluidPalette(blockstate);
        """, """
                schedule = d1 >= FLOWING_UPDATE_SIMILARITY && !sameStatus(s1, ivec2(aquifer[k2 + 3], aquifer[k2 + 4]));
                return fluidPalette(blockstate);
        """);
        body = swap(body, """
                if (density + d5 > 0.0lf) return -1;
            }
            schedule = true;
            return fluidPalette(blockstate);
        """, """
                if (density + d5 > 0.0lf) return -1;
            }
            schedule = true;
            if (sameStatus(s1, s2)
                    && !(d4 >= FLOWING_UPDATE_SIMILARITY && !sameStatus(s2, s3))
                    && !(d0 >= FLOWING_UPDATE_SIMILARITY && !sameStatus(s1, s3))) {
                schedule = d0 >= FLOWING_UPDATE_SIMILARITY && similarity(k1, i4) >= FLOWING_UPDATE_SIMILARITY
                        && !sameStatus(s1, ivec2(aquifer[m2 + 3], aquifer[m2 + 4]));
            }
            return fluidPalette(blockstate);
        """);
        return swap(body, """
        // Returns palette index, or -1 for the null (solid) result; writes the schedule flag.
        """, """
        // Equal fluid level and equal fluid block state; codes 1 and 2 are the same state when the default fluid is lava.
        bool sameStatus(ivec2 a, ivec2 b) {
            return a.x == b.x && (a.y == b.y || (DF_IS_LAVA_DEFAULT && a.y != 0 && b.y != 0));
        }

        // Returns palette index, or -1 for the null (solid) result; writes the schedule flag.
        """);
    }

    /**
     * The kernel text with Minecraft 1.21.9's aquifer shortcut: above a level taken from the highest
     * preliminary surface over the chunk's aquifer grid, a block is the global fluid and no aquifer is looked
     * at.  The aquifer kernel works that level out once per chunk and leaves it in a spare int of the chunk's
     * first aquifer cell.  Put in by rewriting the text, as above and for the same reason.
     */
    private static String skippingAboveSurface(String body) {
        body = swap(body, """
            aquifer[o + 4] = status.y;
        }
        #endif
        """, """
            aquifer[o + 4] = status.y;
            if (r == 0) {
                // The game's range: x and z from the chunk's first aquifer cell to nine blocks into its last, every fourth block.
                int highest = -2147483647 - 1;
                for (int qz = 0; qz <= 10; qz++) {
                    for (int qx = 0; qx <= 10; qx++) {
                        highest = max(highest, preliminarySurface(gBaseX - 16 + 4 * qx, gBaseZ - 16 + 4 * qz));
                    }
                }
                aquifer[o + 5] = (jfloorDiv(highest + 8 + 12, 12) + 1) * 12 + 10;
            }
        }
        #endif
        """);
        return swap(body, """
            if (!AQUIFERS) return fluidPalette(statusAt(global, p.y));
        """, """
            if (!AQUIFERS) return fluidPalette(statusAt(global, p.y));
            if (p.y > aquifer[int(gChunk) * (9 * AQ_CELLS_Y) * AQ_STRIDE + 5]) return fluidPalette(statusAt(global, p.y));
        """);
    }

    private static String swap(String text, String from, String to) {
        int at = text.indexOf(from);
        if (at < 0 || text.indexOf(from, at + 1) >= 0) throw new IllegalStateException("Kernel text to replace is not there exactly once: " + from);
        return text.substring(0, at) + to + text.substring(at + from.length());
    }

    private static void appendRandom(StringBuilder s, String prefix, PositionalRandomFactorySnapshot random) {
        boolean legacy = random == null || random.algorithm() == PositionalRandomFactorySnapshot.Algorithm.LEGACY;
        long seed = random == null ? 0L : random.seed();
        long lo = random == null ? 0L : random.seedLo();
        long hi = random == null ? 0L : random.seedHi();
        s.append("const bool ").append(prefix).append("_LEGACY = ").append(legacy).append(";\n");
        s.append("const int64_t ").append(prefix).append("_SEED = int64_t(").append(Long.toUnsignedString(seed)).append("ul);\n");
        s.append("const int64_t ").append(prefix).append("_LO = int64_t(").append(Long.toUnsignedString(lo)).append("ul);\n");
        s.append("const int64_t ").append(prefix).append("_HI = int64_t(").append(Long.toUnsignedString(hi)).append("ul);\n");
    }

    private static final String PRELUDE_BODY = """
        void setupChunk(uint slot) {
            gChunk = slot;
            gBaseX = chunks[slot].chunkX * 16;
            gBaseZ = chunks[slot].chunkZ * 16;
            gFirstQX = gBaseX >> 2;
            gFirstQZ = gBaseZ >> 2;
        }

        precise double rarity3D(double v) {
            if (v < -0.5lf) return 0.75lf;
            if (v < 0.0lf) return 1.0lf;
            return v < 0.5lf ? 1.5lf : 2.0lf;
        }
        precise double rarity2D(double v) {
            if (v < -0.75lf) return 0.5lf;
            if (v < -0.5lf) return 0.75lf;
            if (v < 0.5lf) return 1.0lf;
            return v < 0.75lf ? 2.0lf : 3.0lf;
        }

        // ------------------------------------------------------- Beardifier
        precise double buryContribution(double x, double y, double z) {
            precise double d = jsqrt(x * x + y * y + z * z);
            return clampedMap(d, 0.0lf, 6.0lf, 1.0lf, 0.0lf);
        }
        precise double fastInvSqrt(double x) {
            precise double halfX = 0.5lf * x;
            int64_t i = dbits(x);
            i = 6910469410427058090l - (i >> 1);
            precise double e = bitsd(i);
            return e * (1.5lf - halfX * e * e);
        }
        precise double beardContribution(int x, int y, int z, int groundDelta) {
            int i = x + 12, j = y + 12, k = z + 12;
            if (i < 0 || i >= 24 || j < 0 || j >= 24 || k < 0 || k >= 24) return 0.0lf;
            precise double d0 = double(groundDelta) + 0.5lf;
            precise double d1 = double(x) * double(x) + d0 * d0 + double(z) * double(z);
            precise double d2 = -d0 * fastInvSqrt(d1 * 0.5lf) * 0.5lf;
            return d2 * dtab[BEARD_KERNEL + k * 576 + i * 24 + j];
        }
        precise double beardifier(ivec3 p) {
            int base = chunks[gChunk].beardOffset;
            int pieces = chunks[gChunk].pieceCount;
            int junctions = chunks[gChunk].junctionCount;
            precise double result = 0.0lf;
            for (int n = 0; n < pieces; n++) {
                int o = base + n * 8;
                int minX = beard[o], minY = beard[o + 1], minZ = beard[o + 2];
                int maxX = beard[o + 3], maxY = beard[o + 4], maxZ = beard[o + 5];
                int adjustment = beard[o + 6], groundDelta = beard[o + 7];
                int i1 = max(0, max(minX - p.x, p.x - maxX));
                int j1 = max(0, max(minZ - p.z, p.z - maxZ));
                int k1 = minY + groundDelta;
                int l1 = p.y - k1;
                // adjustment: 0 NONE, 1 BURY, 2 BEARD_THIN, 3 BEARD_BOX, 4 ENCAPSULATE
                int i2 = adjustment == 0 ? 0
                        : (adjustment == 1 || adjustment == 2) ? l1
                        : adjustment == 3 ? max(0, max(k1 - p.y, p.y - maxY))
                        : max(0, max(minY - p.y, p.y - maxY));
                if (adjustment == 1) result += buryContribution(double(i1), double(i2) * 0.5lf, double(j1));
                else if (adjustment == 2 || adjustment == 3) result += beardContribution(i1, i2, j1, l1) * 0.8lf;
                else if (adjustment == 4) result += buryContribution(double(i1) * 0.5lf, double(i2) * 0.5lf, double(j1) * 0.5lf) * 0.8lf;
                else result += 0.0lf;
            }
            int jb = base + pieces * 8;
            for (int n = 0; n < junctions; n++) {
                int o = jb + n * 3;
                int j2 = p.x - beard[o];
                int k2 = p.y - beard[o + 1];
                int l2 = p.z - beard[o + 2];
                result += beardContribution(j2, k2, l2, k2) * 0.4lf;
            }
            return result;
        }

        """;

    private static final String KERNEL_BODY = """
        // ---------------------------------------------------------- fluids
        // Fluid type codes: 0 air, 1 configured default fluid, 2 default lava.
        bool isLavaBlock(int code) { return code == 2 || (code == 1 && DF_IS_LAVA); }
        bool isWaterBlock(int code) { return code == 1 && DF_IS_WATER; }
        bool isAirState(int code) { return code == 0 || (code == 1 && DF_IS_AIR); }
        bool isLavaDefaultState(int code) { return code == 2 || (code == 1 && DF_IS_LAVA_DEFAULT); }
        int statusAt(ivec2 status, int y) { return y < status.x ? status.y : 0; }
        ivec2 globalFluid(int y) { return y < min(-54, SEA_LEVEL) ? ivec2(-54, 2) : ivec2(SEA_LEVEL, 1); }
        int fluidPalette(int code) { return code == 0 ? P_AIR : (code == 1 ? P_DEFAULT_FLUID : P_LAVA); }

        #ifdef K_AQUIFER
        int preliminarySurface(int x, int z) {
            int qx = (x >> 2) - (gFirstQX - 16);
            int qz = (z >> 2) - (gFirstQZ - 16);
            if (qx < 0 || qz < 0 || qx >= 31 || qz >= 31) { bail(BAIL_RANGE); return 0; }
            int value = prelimOut[prelimIndex[int(gChunk) * 961 + qx * 31 + qz]];
            if (value == -2147483647 - 1) { bail(BAIL_RANGE); return 0; }
            return value;
        }

        const int OFFS_X[13] = int[](0, -2, -1, 0, 1, -3, -2, -1, 1, -2, -1, 0, 1);
        const int OFFS_Z[13] = int[](0, -1, -1, -1, -1, 0, 0, 0, 0, 1, 1, 1, 1);

        int randomizedSurfaceLevel(int x, int y, int z, int prelim) {
            int k = jfloorDiv(x, 16);
            int l = jfloorDiv(y, 40);
            int i1 = jfloorDiv(z, 16);
            int j1 = l * 40 + 20;
            precise double d0 = root_spread_point(ivec3(k, l, i1)) * 10.0lf;
            int l1 = mfloor(jdiv(d0, 3.0lf)) * 3;
            int i2 = j1 + l1;
            return min(prelim, i2);
        }

        int computeSurfaceLevel(int x, int y, int z, ivec2 global, int prelimMin, bool neighborSurface) {
            ivec3 q = ivec3(x, y, z);
            precise double d0;
            precise double d1;
            if (root_erosion_point(q) < -0.22499999403953552lf && root_depth_point(q) > 0.8999999761581421lf) {
                d0 = -1.0lf;
                d1 = -1.0lf;
            } else {
                int i = prelimMin + 8 - y;
                precise double d2 = neighborSurface ? clampedMap(double(i), 0.0lf, 64.0lf, 1.0lf, 0.0lf) : 0.0lf;
                precise double d3 = mclamp(root_flood_point(q), -1.0lf, 1.0lf);
                precise double d4 = mapd(d2, 1.0lf, 0.0lf, -0.3lf, 0.8lf);
                precise double d5 = mapd(d2, 1.0lf, 0.0lf, -0.8lf, 0.4lf);
                d0 = d3 - d5;
                d1 = d3 - d4;
            }
            if (d1 > 0.0lf) return global.x;
            if (d0 > 0.0lf) return randomizedSurfaceLevel(x, y, z, prelimMin);
            return -32512;
        }

        int computeFluidType(int x, int y, int z, ivec2 global, int level) {
            int type = global.y;
            if (level <= -10 && level != -32512 && !isLavaDefaultState(global.y)) {
                precise double d0 = root_lava_point(ivec3(jfloorDiv(x, 64), jfloorDiv(y, 40), jfloorDiv(z, 64)));
                if (abs(d0) > 0.3lf) type = 2;
            }
            return type;
        }

        ivec2 computeFluid(int x, int y, int z) {
            ivec2 global = globalFluid(y);
            int surfaceMin = 2147483647;
            int upper = y + 12;
            int lower = y - 12;
            bool neighborSurface = false;
            for (int o = 0; o < 13; o++) {
                int l = x + OFFS_X[o] * 16;
                int i1 = z + OFFS_Z[o] * 16;
                int j1 = preliminarySurface(l, i1);
                int k1 = j1 + 8;
                bool center = o == 0;
                if (center && lower > k1) return global;
                bool above = upper > k1;
                if (above || center) {
                    ivec2 g1 = globalFluid(k1);
                    if (!isAirState(statusAt(g1, k1))) {
                        if (center) neighborSurface = true;
                        if (above) return g1;
                    }
                }
                surfaceMin = min(surfaceMin, j1);
            }
            int level = computeSurfaceLevel(x, y, z, global, surfaceMin, neighborSurface);
            return ivec2(level, computeFluidType(x, y, z, global, level));
        }

        #endif

        int aquiferSlot(int gx, int gy, int gz) {
            int cx = gx - (gBaseX >> 4) + 1;
            int cy = gy - AQ_MIN_GRID_Y;
            int cz = gz - (gBaseZ >> 4) + 1;
            return (int(gChunk) * (9 * AQ_CELLS_Y) + cy * 9 + cx * 3 + cz) * AQ_STRIDE;
        }

        precise double similarity(int a, int b) { return 1.0lf - jdiv(double(abs(b - a)), 25.0lf); }

        #ifdef K_BLOCK
        precise double calculatePressure(ivec3 p, inout double barrier, ivec2 s1, ivec2 s2) {
            int i = p.y;
            int bs = statusAt(s1, i);
            int bs1 = statusAt(s2, i);
            if ((!isLavaBlock(bs) || !isWaterBlock(bs1)) && (!isWaterBlock(bs) || !isLavaBlock(bs1))) {
                int j = abs(s1.x - s2.x);
                if (j == 0) return 0.0lf;
                precise double d0 = 0.5lf * double(s1.x + s2.x);
                precise double d1 = double(i) + 0.5lf - d0;
                precise double d2 = double(j) * 0.5lf;
                precise double d9 = d2 - abs(d1);
                precise double d10;
                if (d1 > 0.0lf) {
                    precise double d11 = 0.0lf + d9;
                    d10 = d11 > 0.0lf ? jdiv(d11, 1.5lf) : jdiv(d11, 2.5lf);
                } else {
                    precise double d15 = 3.0lf + d9;
                    d10 = d15 > 0.0lf ? jdiv(d15, 3.0lf) : jdiv(d15, 10.0lf);
                }
                precise double d12;
                if (!(d10 < -2.0lf) && !(d10 > 2.0lf)) {
                    if (isnan(barrier)) barrier = root_barrier_value(p);
                    d12 = barrier;
                } else {
                    d12 = 0.0lf;
                }
                return 2.0lf * (d12 + d10);
            }
            return 2.0lf;
        }

        // Returns palette index, or -1 for the null (solid) result; writes the schedule flag.
        int computeSubstance(ivec3 p, double density, out bool schedule) {
            schedule = false;
            if (density > 0.0lf) return -1;
            ivec2 global = globalFluid(p.y);
            if (!AQUIFERS) return fluidPalette(statusAt(global, p.y));
            if (isLavaBlock(statusAt(global, p.y))) return P_LAVA;
            int l = jfloorDiv(p.x - 5, 16);
            int i1 = jfloorDiv(p.y + 1, 12);
            int j1 = jfloorDiv(p.z - 5, 16);
            int k1 = 2147483647, l1 = 2147483647, i2 = 2147483647;
            int j2 = -1, k2 = -1, l2 = -1;
            for (int i3 = 0; i3 <= 1; i3++) {
                for (int j3 = -1; j3 <= 1; j3++) {
                    for (int k3 = 0; k3 <= 1; k3++) {
                        int slot = aquiferSlot(l + i3, i1 + j3, j1 + k3);
                        int dx = aquifer[slot] - p.x;
                        int dy = aquifer[slot + 1] - p.y;
                        int dz = aquifer[slot + 2] - p.z;
                        int dist = dx * dx + dy * dy + dz * dz;
                        if (k1 >= dist) {
                            l2 = k2; k2 = j2; j2 = slot;
                            i2 = l1; l1 = k1; k1 = dist;
                        } else if (l1 >= dist) {
                            l2 = k2; k2 = slot;
                            i2 = l1; l1 = dist;
                        } else if (i2 >= dist) {
                            l2 = slot; i2 = dist;
                        }
                    }
                }
            }
            ivec2 s1 = ivec2(aquifer[j2 + 3], aquifer[j2 + 4]);
            precise double d1 = similarity(k1, l1);
            int blockstate = statusAt(s1, p.y);
            if (d1 <= 0.0lf) {
                schedule = d1 >= FLOWING_UPDATE_SIMILARITY;
                return fluidPalette(blockstate);
            }
            if (isWaterBlock(blockstate) && isLavaBlock(statusAt(globalFluid(p.y - 1), p.y - 1))) {
                schedule = true;
                return fluidPalette(blockstate);
            }
            double barrier = bitsd(int64_t(0x7ff8000000000000ul));
            ivec2 s2 = ivec2(aquifer[k2 + 3], aquifer[k2 + 4]);
            precise double d2 = d1 * calculatePressure(p, barrier, s1, s2);
            if (density + d2 > 0.0lf) return -1;
            ivec2 s3 = ivec2(aquifer[l2 + 3], aquifer[l2 + 4]);
            precise double d0 = similarity(k1, i2);
            if (d0 > 0.0lf) {
                precise double d3 = d1 * d0 * calculatePressure(p, barrier, s1, s3);
                if (density + d3 > 0.0lf) return -1;
            }
            precise double d4 = similarity(l1, i2);
            if (d4 > 0.0lf) {
                precise double d5 = d1 * d4 * calculatePressure(p, barrier, s2, s3);
                if (density + d5 > 0.0lf) return -1;
            }
            schedule = true;
            return fluidPalette(blockstate);
        }

        // -------------------------------------------------------- ore veins
        int oreVein(ivec3 p) {
            precise double d0 = root_toggle_value(p);
            int i = p.y;
            bool copper = d0 > 0.0lf;
            precise double d1 = abs(d0);
            int maxY = copper ? 50 : -8;
            int minY = copper ? 0 : -60;
            int j = maxY - i;
            int k = i - minY;
            if (k < 0 || j < 0) return -1;
            int l = min(j, k);
            precise double d2 = clampedMap(double(l), 0.0lf, 20.0lf, -0.2lf, 0.0lf);
            if (d1 + d2 < 0.4000000059604645lf) return -1;
            Rng r = rngAt(ORE_LEGACY, ORE_SEED, ORE_LO, ORE_HI, p.x, i, p.z);
            if (rngNextFloat(r) > 0.7) return -1;
            if (root_ridged_value(p) >= 0.0lf) return -1;
            precise double d3 = clampedMap(d1, 0.4000000059604645lf, 0.6000000238418579lf, 0.10000000149011612lf, 0.30000001192092896lf);
            if (double(rngNextFloat(r)) < d3 && root_gap_value(p) > -0.30000001192092896lf) {
                bool raw = rngNextFloat(r) < 0.02;
                return copper ? (raw ? P_RAW_COPPER : P_COPPER_ORE) : (raw ? P_RAW_IRON : P_IRON_ORE);
            }
            return copper ? P_GRANITE : P_TUFF;
        }

        #endif

        // ------------------------------------------------------------ kernels
        #ifdef K_COLUMN
        void main() {
            uint id = gl_GlobalInvocationID.x;
            uint per = uint(COLS * COLS);
            uint slot = id / per;
            if (slot >= pc.chunkCount) return;
            setupChunk(slot);
            int r = int(id % per);
            int qi = r / COLS, qj = r % COLS;
            ivec3 p = ivec3((gFirstQX + qi) << 2, 0, (gFirstQZ + qj) << 2);
            COLUMN_STORES
        }
        #endif

        #ifdef K_XZ
        void main() {
            uint id = gl_GlobalInvocationID.x;
            uint slot = id / 256u;
            if (slot >= pc.chunkCount) return;
            setupChunk(slot);
            int col = int(id % 256u);
            ivec3 p = ivec3(gBaseX + (col & 15), 0, gBaseZ + (col >> 4));
            XZ_STORES
        }
        #endif

        #ifdef K_CORNER
        void main() {
            uint id = gl_GlobalInvocationID.x;
            uint per = uint(CNX * CNX * CNY);
            uint slot = id / per;
            if (slot >= pc.chunkCount) return;
            setupChunk(slot);
            int r = int(id % per);
            int iy = r / (CNX * CNX);
            int rem = r % (CNX * CNX);
            int ix = rem / CNX, iz = rem % CNX;
            ivec3 p = ivec3(gBaseX + ix * CELL_W, (MIN_CELL_Y + iy) * CELL_H, gBaseZ + iz * CELL_W);
            CORNER_STORES
        }
        #endif

        #ifdef K_PRELIM
        void main() {
            uint id = gl_GlobalInvocationID.x;
            if (id >= pc.prelimCount) return;
            gChunk = 0u;
            gShared = true;
            int value = prelim_scan(prelimCols[2u * id], prelimCols[2u * id + 1u]);
            // A bail here cannot be charged to one chunk: poison the column so every reader bails.
            prelimOut[id] = gBailed ? (-2147483647 - 1) : value;
        }
        #endif

        #ifdef K_AQUIFER
        void main() {
            uint id = gl_GlobalInvocationID.x;
            uint per = uint(9 * AQ_CELLS_Y);
            uint slot = id / per;
            if (slot >= pc.chunkCount) return;
            setupChunk(slot);
            int r = int(id % per);
            int cy = r / 9;
            int rem = r % 9;
            int cx = rem / 3, cz = rem % 3;
            int gx = (gBaseX >> 4) - 1 + cx;
            int gy = AQ_MIN_GRID_Y + cy;
            int gz = (gBaseZ >> 4) - 1 + cz;
            Rng rng = rngAt(AQ_LEGACY, AQ_SEED, AQ_LO, AQ_HI, gx, gy, gz);
            int px = gx * 16 + rngNextInt(rng, 10);
            int py = gy * 12 + rngNextInt(rng, 9);
            int pz = gz * 16 + rngNextInt(rng, 10);
            ivec2 status = computeFluid(px, py, pz);
            int o = (int(slot) * int(per) + r) * AQ_STRIDE;
            aquifer[o] = px;
            aquifer[o + 1] = py;
            aquifer[o + 2] = pz;
            aquifer[o + 3] = status.x;
            aquifer[o + 4] = status.y;
        }
        #endif

        #ifdef K_BLOCK
        uint blockByte(ivec3 p) {
            precise double density = root_final_cell(p) + beardifier(p);
            bool schedule;
            int state = computeSubstance(p, density, schedule);
            if (state < 0) {
                int ore = ORES ? oreVein(p) : -1;
                state = ore >= 0 ? ore : P_DEFAULT_BLOCK;
            }
            uint mark = (schedule && (PALETTE_FLAGS[state] & 1) != 0) ? 0x80u : 0u;
            return uint(state) | mark;
        }
        void main() {
            uint id = gl_GlobalInvocationID.x;
            uint per = uint(64 * STORAGE_H);
            uint slot = id / per;
            if (slot >= pc.chunkCount) return;
            setupChunk(slot);
            int r = int(id % per);
            int ly = r / 64;
            int rem = r % 64;
            int lz = rem / 4;
            int lx0 = (rem % 4) * 4;
            int y = MIN_Y + ly;
            uint word = 0u;
            if (y < MIN_Y + GEN_HEIGHT) {
                for (int k = 0; k < 4; k++) {
                    word |= blockByte(ivec3(gBaseX + lx0 + k, y, gBaseZ + lz)) << (8 * k);
                }
            } else {
                word = uint(P_AIR) * 0x01010101u;
            }
            blocks[slot * per + uint(r)] = word;
        }
        #endif

        #ifdef K_HEIGHT
        void main() {
            uint id = gl_GlobalInvocationID.x;
            uint slot = id / 256u;
            if (slot >= pc.chunkCount) return;
            int col = int(id % 256u);
            int lz = col / 16, lx = col % 16;
            uint per = uint(64 * STORAGE_H);
            int ocean = MIN_Y, surface = MIN_Y;
            bool haveOcean = false, haveSurface = false;
            int surf = chunks[slot].pad0;
            for (int ly = STORAGE_H - 1; ly >= 0 && !(haveOcean && haveSurface); ly--) {
                uint word = blocks[slot * per + uint(ly * 64 + lz * 4 + lx / 4)];
                int state = int((word >> (8 * (lx % 4))) & 0x7Fu);
                int f = surf != 0 ? int(perm[int(perm[surf + 1]) + state]) : PALETTE_FLAGS[state];
                if (!haveOcean && (f & 2) != 0) { ocean = MIN_Y + ly + 1; haveOcean = true; }
                if (!haveSurface && (f & 4) != 0) { surface = MIN_Y + ly + 1; haveSurface = true; }
            }
            heights[(slot * 2u) * 256u + uint(col)] = ocean;
            heights[(slot * 2u + 1u) * 256u + uint(col)] = surface;
        }
        #endif
        """;
}
