// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

/**
 * GLSL for the fused SURFACE stage (Minecraft 1.21.1 SurfaceSystem.buildSurface
 * and SurfaceRules).  The kernel source is the same for every level: the rule
 * tree, palettes, noises and biome tables live in the uint/double tables and
 * are reached through the per-chunk surface header offset (ChunkInfo.pad0;
 * zero disables the stage for that chunk).
 *
 * <p>K_SURF_A: column top biome and the eroded-badlands extension, one
 * invocation per four X-adjacent columns (they share block words).
 * K_SURF_B: the descending column walk, rule interpreter and frozen-ocean
 * extension.  Vanilla walks columns sequentially and the only cross-column
 * input is the "steep" condition's read of neighbour heights; B uses the
 * post-extension height for already-walked neighbours and raises
 * SURF_HEIGHT_CHANGED when a walk changes its own height afterwards, so the
 * host can discard a chunk in which that assumption could have mattered.</p>
 */
final class FusedSurfaceKernels {
    private FusedSurfaceKernels() {}

    static final int SURF_HEIGHT_CHANGED = 0x100, SURF_STEEP_USED = 0x200;

    static String constants(FusedNoiseCompiler.Geometry g) {
        int quartHeight = g.storageHeight() / 4;
        return "const int BIOME_QH = " + quartHeight + ";\n"
                + "const int BIOME_WORDS = " + biomeWordsPerChunk(g) + ";\n"
                + "const double LEGACY_DOUBLE_UNIT = " + FusedNoiseCompiler.dlit((double) 1.110223E-16F) + ";\n"
                + "#define F_0_05 " + FusedNoiseCompiler.flit(0.05F) + "\n"
                + "#define F_0_1 " + FusedNoiseCompiler.flit(0.1F) + "\n"
                + "#define F_0_15 " + FusedNoiseCompiler.flit(0.15F) + "\n"
                + "#define F_0_2 " + FusedNoiseCompiler.flit(0.2F) + "\n";
    }

    /** Biome ids are 16 bits each over a 6x6 quart-column window, two per uint. */
    static int biomeWordsPerChunk(FusedNoiseCompiler.Geometry g) {
        return (36 * (g.storageHeight() / 4) + 1) / 2;
    }

    static final String BODY = """
        #if defined(K_SURF_A) || defined(K_SURF_B)
        // ------------------------------------------------------------ surface
        const int MINI = -2147483647 - 1;
        int gSurf;
        int gLx, gLz, gX, gZ;
        uint gColBase;
        uint gShift;
        int sh(int i) { return int(perm[gSurf + i]); }
        int stateFlags(int state) { return int(perm[sh(1) + state]); }

        void setColumn(int lx, int lz) {
            gLx = lx; gLz = lz;
            gX = gBaseX + lx; gZ = gBaseZ + lz;
            gColBase = gChunk * uint(64 * STORAGE_H) + uint(lz * 4 + lx / 4);
            gShift = uint(8 * (lx % 4));
        }
        // Raw block byte, or -1 outside the build height (VOID_AIR).
        int rawAt(int y) {
            int ly = y - MIN_Y;
            if (ly < 0 || ly >= STORAGE_H) return -1;
            return int((blocks[gColBase + uint(ly * 64)] >> gShift) & 0xFFu);
        }
        int flagsOfRaw(int raw) { return raw < 0 ? 0 : stateFlags(raw & 0x7F); }
        int flagsAt(int y) { return flagsOfRaw(rawAt(y)); }
        // BlockColumn.setBlock: ignored outside the build height; a fluid result is marked for post-processing.
        void putBlock(int y, int state) {
            int ly = y - MIN_Y;
            if (ly < 0 || ly >= STORAGE_H) return;
            uint i = gColBase + uint(ly * 64);
            uint w = blocks[i];
            uint old = (w >> gShift) & 0xFFu;
            uint mark = (old & 0x80u) | (((stateFlags(state) & 1) != 0) ? 0x80u : 0u);
            blocks[i] = (w & ~(0xFFu << gShift)) | ((uint(state) | mark) << gShift);
        }

        int64_t u64at(int i) { return int64_t(uint64_t(perm[i]) | (uint64_t(perm[i + 1]) << 32)); }
        Rng tableRng(int R, int x, int y, int z) {
            return rngAt(perm[R] != 0u, u64at(R + 1), u64at(R + 3), u64at(R + 5), x, y, z);
        }
        double rngNextDouble(inout Rng r) {
            if (r.isLegacy) {
                int i = legacyBits(r, 26);
                int j = legacyBits(r, 27);
                int64_t k = (int64_t(i) << 27) + int64_t(j);
                return double(k) * LEGACY_DOUBLE_UNIT;
            }
            return double(int64_t(uint64_t(xoroNext(r)) >> 11)) * 1.1102230246251565E-16lf;
        }

        // ---- BiomeManager.getBiome over the uploaded quart window [-1, 4] x [-1, 4]
        int biomeIdAt(int qx, int qy, int qz) {
            int ix = qx - (gFirstQX - 1);
            int iz = qz - (gFirstQZ - 1);
            if (ix < 0 || iz < 0 || ix > 5 || iz > 5) { bail(BAIL_RANGE); return 0; }
            int minQ = MIN_Y >> 2;
            int iy = clamp(qy, minQ, minQ + BIOME_QH - 1) - minQ;
            int idx = (ix * 6 + iz) * BIOME_QH + iy;
            uint w = biomes[gChunk * uint(BIOME_WORDS) + uint(idx >> 1)];
            return int((w >> uint((idx & 1) * 16)) & 0xFFFFu);
        }
        int64_t lcgNext(int64_t s, int64_t a) {
            s *= s * 6364136223846793005l + 1442695040888963407l;
            return s + a;
        }
        precise double fiddle(int64_t v) {
            precise double d = double(int((v >> 24) & 1023l)) * 0.0009765625lf;
            return (d - 0.5lf) * 0.9lf;
        }
        precise double fiddledDistance(int64_t seed, int qx, int qy, int qz, double fx, double fy, double fz) {
            int64_t v = lcgNext(seed, int64_t(qx));
            v = lcgNext(v, int64_t(qy));
            v = lcgNext(v, int64_t(qz));
            v = lcgNext(v, int64_t(qx));
            v = lcgNext(v, int64_t(qy));
            v = lcgNext(v, int64_t(qz));
            precise double d0 = fiddle(v);
            v = lcgNext(v, seed);
            precise double d1 = fiddle(v);
            v = lcgNext(v, seed);
            precise double d2 = fiddle(v);
            precise double a = fz + d2;
            precise double b = fy + d1;
            precise double c = fx + d0;
            precise double aa = a * a;
            precise double bb = b * b;
            precise double cc = c * c;
            return aa + bb + cc;
        }
        int biomeAt(int x, int y, int z) {
            int64_t seed = u64at(gSurf + 20);
            int i = x - 2, j = y - 2, k = z - 2;
            int l = i >> 2, i1 = j >> 2, j1 = k >> 2;
            precise double d0 = double(i & 3) * 0.25lf;
            precise double d1 = double(j & 3) * 0.25lf;
            precise double d2 = double(k & 3) * 0.25lf;
            int best = 0;
            precise double d3 = bitsd(int64_t(0x7ff0000000000000ul));
            for (int n = 0; n < 8; n++) {
                bool f0 = (n & 4) == 0, f1 = (n & 2) == 0, f2 = (n & 1) == 0;
                precise double d7 = fiddledDistance(seed, f0 ? l : l + 1, f1 ? i1 : i1 + 1, f2 ? j1 : j1 + 1,
                        f0 ? d0 : d0 - 1.0lf, f1 ? d1 : d1 - 1.0lf, f2 ? d2 : d2 - 1.0lf);
                if (d3 > d7) { best = n; d3 = d7; }
            }
            return biomeIdAt((best & 4) == 0 ? l : l + 1, (best & 2) == 0 ? i1 : i1 + 1, (best & 1) == 0 ? j1 : j1 + 1);
        }
        int biomeFlags(int biome) { return int(perm[sh(6) + biome * 2 + 1]); }
        #endif

        #ifdef K_SURF_A
        // SurfaceSystem.erodedBadlandsExtension; returns the column's first free Y afterwards.
        int erodedBadlands(int k1) {
            precise double d1 = jmin(abs(normalNoise(sh(14), double(gX), 0.0lf, double(gZ)) * 8.25lf),
                    normalNoise(sh(12), double(gX) * 0.2lf, 0.0lf, double(gZ) * 0.2lf) * 15.0lf);
            int top = k1;
            if (!(d1 <= 0.0lf)) {
                precise double d4 = abs(normalNoise(sh(13), double(gX) * 0.75lf, 0.0lf, double(gZ) * 0.75lf) * 1.5lf);
                precise double d5 = 64.0lf + jmin(d1 * d1 * 2.5lf, ceil(d4 * 50.0lf) + 24.0lf);
                int i = mfloor(d5);
                if (i > MIN_Y + STORAGE_H + 4096) { bail(BAIL_RANGE); return top; }
                if (k1 <= i) {
                    for (int j = i; j >= MIN_Y; j--) {
                        int f = flagsAt(j);
                        if ((f & 16) != 0) break;
                        if ((f & 8) != 0) return top;
                    }
                    for (int k = i; k >= MIN_Y && (flagsAt(k) & 4) == 0; k--) {
                        putBlock(k, sh(24));
                        if (k < MIN_Y + STORAGE_H && top < k + 1) top = k + 1;
                    }
                }
            }
            return top;
        }
        void main() {
            uint id = gl_GlobalInvocationID.x;
            uint slot = id / 64u;
            if (slot >= pc.chunkCount) return;
            setupChunk(slot);
            gSurf = chunks[slot].pad0;
            if (gSurf == 0) return;
            int r = int(id % 64u);
            for (int n = 0; n < 4; n++) {
                setColumn((r % 4) * 4 + n, r / 4);
                uint col = uint(gLz * 16 + gLx);
                int k1 = heights[(slot * 2u + 1u) * 256u + col];
                int top = k1;
                int biome = biomeAt(gX, sh(19) != 0 ? 0 : k1, gZ);
                if ((biomeFlags(biome) & 2) != 0) top = erodedBadlands(k1);
                surfTmp[slot * 256u + col] = top;
            }
        }
        #endif

        #ifdef K_SURF_B
        int cY, cDepth, cStoneAbove, cStoneBelow, cWater, cOwnH;
        bool cHaveSecondary; double cSecondary;
        bool cHaveMin; int cMin;
        bool cHaveSteep, cSteep;
        bool cHaveBand; int cBand;
        bool cHaveBiome; int cBiome;
        uint cNoiseKnown[4];
        double cNoise[128];

        precise double simplexOctaves(int S, double x, double y) {
            int n = int(dtab[S]);
            precise double result = 0.0lf;
            precise double d1 = dtab[S + 1];
            precise double d2 = dtab[S + 2];
            for (int i = 0; i < n; i++) {
                int permBase = int(dtab[S + 3 + i]);
                if (permBase >= 0) result += simplex2D(permBase, x * d1 + 0.0lf, y * d1 + 0.0lf) * d2;
                d1 = d1 * 0.5lf;
                d2 = d2 * 2.0lf;
            }
            return result;
        }
        // Biome.getHeightAdjustedTemperature (the per-thread cache in getTemperature is a pure memo).
        precise float biomeTemperature(int biome, int x, int y, int z) {
            int T = sh(6) + biome * 2;
            precise float f = uintBitsToFloat(perm[T]);
            if ((int(perm[T + 1]) & 1) != 0) {
                precise double d0 = simplexOctaves(sh(26), double(x) * 0.05lf, double(z) * 0.05lf) * 7.0lf;
                precise double d1 = simplexOctaves(sh(27), double(x) * 0.2lf, double(z) * 0.2lf);
                precise double d2 = d0 + d1;
                if (d2 < 0.3lf) {
                    precise double d3 = simplexOctaves(sh(27), double(x) * 0.09lf, double(z) * 0.09lf);
                    if (d3 < 0.8lf) f = F_0_2;
                }
            }
            if (y > 80) {
                precise float f1 = float(simplexOctaves(sh(25), double(float(x) * 0.125), double(float(z) * 0.125)) * 8.0lf);
                precise float t = (f1 + float(y) - 80.0) * F_0_05;
                return f - jdivf(t, 40.0);
            }
            return f;
        }

        int surfaceDepthAt() {
            precise double d0 = normalNoise(sh(9), double(gX), 0.0lf, double(gZ));
            Rng r = tableRng(sh(18), gX, 0, gZ);
            return jd2i(d0 * 2.75lf + 3.0lf + rngNextDouble(r) * 0.25lf);
        }
        double surfaceSecondary() {
            if (!cHaveSecondary) {
                cHaveSecondary = true;
                cSecondary = normalNoise(sh(10), double(gX), 0.0lf, double(gZ));
            }
            return cSecondary;
        }
        int prelimCorner(int cx, int cz) {
            int value = prelimOut[prelimIndex[int(gChunk) * 961 + (16 + 4 * cx) * 31 + (16 + 4 * cz)]];
            if (value == MINI) { bail(BAIL_RANGE); return 0; }
            return value;
        }
        int minSurfaceLevel() {
            if (!cHaveMin) {
                cHaveMin = true;
                precise double fx = double(float(gX & 15) * 0.0625);
                precise double fz = double(float(gZ & 15) * 0.0625);
                precise double a = lerpd(fx, double(prelimCorner(0, 0)), double(prelimCorner(1, 0)));
                precise double b = lerpd(fx, double(prelimCorner(0, 1)), double(prelimCorner(1, 1)));
                cMin = mfloor(lerpd(fz, a, b)) + cDepth - 8;
            }
            return cMin;
        }
        int neighbourHeight(int lx, int lz) {
            if (lx == gLx && lz == gLz) return cOwnH;
            bool walked = lx < gLx || (lx == gLx && lz < gLz);
            uint col = uint(lz * 16 + lx);
            return walked ? surfTmp[gChunk * 256u + col] : heights[(gChunk * 2u + 1u) * 256u + col];
        }
        bool steepCondition() {
            if (!cHaveSteep) {
                cHaveSteep = true;
                atomicOr(flags[gChunk], 0x200u);
                int k = max(gLz - 1, 0), l = min(gLz + 1, 15);
                if (neighbourHeight(gLx, l) >= neighbourHeight(gLx, k) + 4) {
                    cSteep = true;
                } else {
                    int k1 = max(gLx - 1, 0), l1 = min(gLx + 1, 15);
                    cSteep = neighbourHeight(k1, gLz) >= neighbourHeight(l1, gLz) + 4;
                }
            }
            return cSteep;
        }
        bool noiseCondition(int slot, int bounds) {
            int w = slot >> 5;
            uint bit = 1u << uint(slot & 31);
            if ((cNoiseKnown[w] & bit) == 0u) {
                cNoiseKnown[w] |= bit;
                cNoise[slot] = normalNoise(int(perm[sh(3) + slot]), double(gX), 0.0lf, double(gZ));
            }
            double d = cNoise[slot];
            int B = sh(29) + bounds * 2;
            return d >= dtab[B] && d <= dtab[B + 1];
        }
        bool verticalGradient(int random, int trueAtAndBelow, int falseAtAndAbove) {
            if (cY <= trueAtAndBelow) return true;
            if (cY >= falseAtAndAbove) return false;
            precise double d0 = mapd(double(cY), double(trueAtAndBelow), double(falseAtAndAbove), 1.0lf, 0.0lf);
            Rng r = tableRng(sh(4) + random * 7, gX, cY, gZ);
            return double(rngNextFloat(r)) < d0;
        }
        int blockBiome() {
            if (!cHaveBiome) {
                cHaveBiome = true;
                cBiome = biomeAt(gX, cY, gZ);
            }
            return cBiome;
        }
        // SurfaceSystem.getBand; Math.round is floor(v + 1/2) evaluated exactly.
        int bandState() {
            if (!cHaveBand) {
                cHaveBand = true;
                precise double v = normalNoise(sh(11), double(gX), 0.0lf, double(gZ)) * 4.0lf;
                precise double fl = floor(v);
                cBand = jd2i((v - fl) >= 0.5lf ? fl + 1.0lf : fl);
            }
            int index = jrem(cY + cBand + 192, 192);
            if (index < 0) { bail(BAIL_RANGE); return -1; }
            return int(perm[sh(5) + index]);
        }

        // Returns the rule's block state index, or -1 for "no rule applies".
        int applyRule() {
            int base = sh(0);
            int pc = 0;
            for (int guard = 0; guard < 65536; guard++) {
                int I = base + pc * 6;
                int op = int(perm[I]);
                int a = int(perm[I + 1]), b = int(perm[I + 2]), c = int(perm[I + 3]), d = int(perm[I + 4]);
                bool r = false;
                switch (op & 255) {
                    case 0: return -1;
                    case 1: return a;
                    case 2: return bandState();
                    case 3: {
                        int biome = blockBiome();
                        r = (perm[sh(7) + a + (biome >> 5)] & (1u << uint(biome & 31))) != 0u;
                        break;
                    }
                    case 4: r = noiseCondition(a, b); break;
                    case 5: r = verticalGradient(a, b, c); break;
                    case 6: r = cY + (c != 0 ? cStoneAbove : 0) >= a + cDepth * b; break;
                    case 7: r = cWater == MINI || cY + (c != 0 ? cStoneAbove : 0) >= cWater + a + cDepth * b; break;
                    case 8: r = !(biomeTemperature(blockBiome(), gX, cY, gZ) >= F_0_15); break;
                    case 9: r = steepCondition(); break;
                    case 10: r = cDepth <= 0; break;
                    case 11: r = cY >= minSurfaceLevel(); break;
                    case 12: {
                        int i = d != 0 ? cStoneBelow : cStoneAbove;
                        int j = b != 0 ? cDepth : 0;
                        int k = c == 0 ? 0 : jd2i(mapd(surfaceSecondary(), -1.0lf, 1.0lf, 0.0lf, double(c)));
                        r = i <= 1 + a + j + k;
                        break;
                    }
                    default: bail(BAIL_RANGE); return -1;
                }
                bool negate = (op & 256) != 0;
                pc = (r != negate) ? pc + 1 : int(perm[I + 5]);
            }
            bail(BAIL_RANGE);
            return -1;
        }

        // setBlock plus Heightmap.update for WORLD_SURFACE_WG of this column.
        void setTracked(int y, int state) {
            int ly = y - MIN_Y;
            if (ly < 0 || ly >= STORAGE_H) return;
            putBlock(y, state);
            int i = cOwnH;
            if (y <= i - 2) return;
            if ((stateFlags(state) & 4) != 0) {
                if (y >= i) cOwnH = y + 1;
            } else if (i - 1 == y) {
                int next = MIN_Y;
                for (int k = y - 1; k >= MIN_Y; k--) {
                    if ((flagsAt(k) & 4) != 0) { next = k + 1; break; }
                }
                cOwnH = next;
            }
        }

        void frozenOcean(int minSurface, int biome, int height) {
            precise double d1 = jmin(abs(normalNoise(sh(17), double(gX), 0.0lf, double(gZ)) * 8.25lf),
                    normalNoise(sh(15), double(gX) * 1.28lf, 0.0lf, double(gZ) * 1.28lf) * 15.0lf);
            if (!(d1 <= 1.8lf)) {
                precise double d5 = abs(normalNoise(sh(16), double(gX) * 1.17lf, 0.0lf, double(gZ) * 1.17lf) * 1.5lf);
                precise double d6 = jmin(d1 * d1 * 1.2lf, ceil(d5 * 40.0lf) + 14.0lf);
                if (biomeTemperature(biome, gX, 63, gZ) > F_0_1) d6 -= 2.0lf;
                precise double d2;
                if (d6 > 2.0lf) {
                    d2 = double(SEA_LEVEL) - d6 - 7.0lf;
                    d6 += double(SEA_LEVEL);
                } else {
                    d6 = 0.0lf;
                    d2 = 0.0lf;
                }
                Rng r = tableRng(sh(18), gX, 0, gZ);
                int i = 2 + rngNextInt(r, 4);
                int j = SEA_LEVEL + 18 + rngNextInt(r, 10);
                int k = 0;
                int top = jd2i(d6);
                int bottom = jd2i(d2);
                int start = max(height, top + 1);
                if (start - minSurface > 8192) { bail(BAIL_RANGE); return; }
                for (int l = start; l >= minSurface; l--) {
                    int f = flagsAt(l);
                    bool hit = false;
                    if ((f & 4) == 0 && l < top && rngNextDouble(r) > 0.01lf) hit = true;
                    else if ((f & 8) != 0 && l > bottom && l < SEA_LEVEL && d2 != 0.0lf && rngNextDouble(r) > 0.15lf) hit = true;
                    if (hit) {
                        if (k <= i && l > j) {
                            setTracked(l, sh(22));
                            k++;
                        } else {
                            setTracked(l, sh(23));
                        }
                    }
                }
            }
        }

        void main() {
            uint id = gl_GlobalInvocationID.x;
            uint slot = id / 64u;
            if (slot >= pc.chunkCount) return;
            setupChunk(slot);
            gSurf = chunks[slot].pad0;
            if (gSurf == 0) return;
            int r = int(id % 64u);
            for (int n = 0; n < 4; n++) {
                setColumn((r % 4) * 4 + n, r / 4);
                uint col = uint(gLz * 16 + gLx);
                int k1 = heights[(slot * 2u + 1u) * 256u + col];
                int l1 = surfTmp[slot * 256u + col];
                int topBiome = biomeAt(gX, sh(19) != 0 ? 0 : k1, gZ);
                cOwnH = l1;
                cDepth = surfaceDepthAt();
                cHaveSecondary = false; cHaveMin = false; cHaveSteep = false; cHaveBand = false;
                for (int w = 0; w < 4; w++) cNoiseKnown[w] = 0u;
                int stoneAbove = 0;
                int water = MINI;
                int nextCeiling = 2147483647;
                for (int y = l1; y >= MIN_Y; y--) {
                    int f = flagsAt(y);
                    if ((f & 4) == 0) {
                        stoneAbove = 0;
                        water = MINI;
                    } else if ((f & 1) != 0) {
                        if (water == MINI) water = y + 1;
                    } else {
                        if (nextCeiling >= y) {
                            nextCeiling = -32512;
                            for (int j3 = y - 1; j3 >= MIN_Y - 1; j3--) {
                                int f2 = flagsAt(j3);
                                if ((f2 & 4) == 0 || (f2 & 1) != 0) { nextCeiling = j3 + 1; break; }
                            }
                        }
                        stoneAbove++;
                        if ((f & 32) != 0) {
                            cY = y;
                            cStoneAbove = stoneAbove;
                            cStoneBelow = y - nextCeiling + 1;
                            cWater = water;
                            cHaveBiome = false;
                            int state = applyRule();
                            if (state >= 0) setTracked(y, state);
                        }
                    }
                }
                if ((biomeFlags(topBiome) & 4) != 0) frozenOcean(minSurfaceLevel(), topBiome, k1);
                if (cOwnH != l1) atomicOr(flags[gChunk], 0x100u);
            }
        }
        #endif
        """;
}
