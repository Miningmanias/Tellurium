// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.jvm.worldgen;

import dev.worldgennext.semantic.snapshot.NoiseParameters;
import dev.worldgennext.semantic.snapshot.BlendedNoiseParameters;
import dev.worldgennext.semantic.snapshot.EndIslandParameters;
import java.util.Objects;

/** Deterministic scalar octave/value-noise evaluator used by the CPU baseline. */
public final class NoiseEvaluator {
    public double sample(NoiseParameters parameters, long worldSeed, double x, double y, double z) {
        Objects.requireNonNull(parameters, "parameters");
        if (parameters.captured() != null) {
            if (parameters.captured().worldSeed() != worldSeed) {
                throw new IllegalArgumentException("Captured noise seed does not match world seed");
            }
            return capturedNormal(parameters.captured(), x, y, z);
        }
        double result = 0, amplitude = 0, frequency = Math.scalb(1.0, parameters.firstOctave());
        for (double octaveAmplitude : parameters.amplitudes()) {
            result += valueNoise(worldSeed ^ parameters.salt(), x * frequency, y * frequency, z * frequency) * octaveAmplitude;
            amplitude += Math.abs(octaveAmplitude);
            frequency *= 2.0;
        }
        return amplitude == 0 ? 0 : result / amplitude;
    }
    public double sample(long seed, double x, double y, double z) {
        return valueNoise(seed, x, y, z);
    }

    /** Replays Minecraft 1.21.1's captured EndIslandDensityFunction. */
    public double sampleEndIsland(EndIslandParameters parameters, int x, int z) {
        Objects.requireNonNull(parameters, "parameters");
        int gridX = x / 2;
        int gridZ = z / 2;
        int remainderX = x % 2;
        int remainderZ = z % 2;
        float height = 100.0f - (float) Math.sqrt((float) (x * x + z * z)) * 8.0f;
        height = Math.max(-100.0f, Math.min(80.0f, height));
        for (int i = -12; i <= 12; i++) {
            for (int j = -12; j <= 12; j++) {
                long islandX = (long) gridX + i;
                long islandZ = (long) gridZ + j;
                if (islandX * islandX + islandZ * islandZ > 4096L
                        && simplex2D(parameters, islandX, islandZ) < -0.8999999761581421D) {
                    float islandScale = (float) Math.abs(islandX) * 3439.0f
                            + (float) Math.abs(islandZ) * 147.0f;
                    islandScale %= 13.0f;
                    islandScale += 9.0f;
                    float localX = (float) (remainderX - i * 2);
                    float localZ = (float) (remainderZ - j * 2);
                    float islandHeight = 100.0f - (float) Math.sqrt(localX * localX + localZ * localZ) * islandScale;
                    islandHeight = Math.max(-100.0f, Math.min(80.0f, islandHeight));
                    height = Math.max(height, islandHeight);
                }
            }
        }
        return (height - 8.0) / 128.0;
    }

    private static double simplex2D(EndIslandParameters parameters, double x, double y) {
        double skew = (x + y) * SIMPLEX_F2;
        int latticeX = (int) Math.floor(x + skew);
        int latticeY = (int) Math.floor(y + skew);
        double unskew = (latticeX + latticeY) * SIMPLEX_G2;
        double x0 = x - (latticeX - unskew);
        double y0 = y - (latticeY - unskew);
        int offsetX = x0 > y0 ? 1 : 0;
        int offsetY = x0 > y0 ? 0 : 1;
        double x1 = x0 - offsetX + SIMPLEX_G2;
        double y1 = y0 - offsetY + SIMPLEX_G2;
        double x2 = x0 - 1.0 + 2.0 * SIMPLEX_G2;
        double y2 = y0 - 1.0 + 2.0 * SIMPLEX_G2;
        int hash0 = simplexPermutation(parameters, latticeX + simplexPermutation(parameters, latticeY)) % 12;
        int hash1 = simplexPermutation(parameters, latticeX + offsetX
                + simplexPermutation(parameters, latticeY + offsetY)) % 12;
        int hash2 = simplexPermutation(parameters, latticeX + 1
                + simplexPermutation(parameters, latticeY + 1)) % 12;
        double n0 = simplexCorner(hash0, x0, y0);
        double n1 = simplexCorner(hash1, x1, y1);
        double n2 = simplexCorner(hash2, x2, y2);
        return 70.0 * (n0 + n1 + n2);
    }

    private static int simplexPermutation(EndIslandParameters parameters, int index) {
        return parameters.permutation().get(index & 255);
    }

    private static double simplexCorner(int hash, double x, double y) {
        double attenuation = 0.5 - x * x - y * y;
        if (attenuation < 0.0) return 0.0;
        attenuation *= attenuation;
        int[] gradient = GRADIENTS[hash % 12];
        return attenuation * attenuation * (gradient[0] * x + gradient[1] * y);
    }

    /** Replays the seed-expanded legacy BlendedNoise used by the 1.21.1 overworld router. */
    public double sampleBlended(BlendedNoiseParameters parameters, double x, double y, double z) {
        Objects.requireNonNull(parameters, "parameters");
        double xzMultiplier = 684.412 * parameters.xzScale();
        double yMultiplier = 684.412 * parameters.yScale();
        double xValue = x * xzMultiplier;
        double yValue = y * yMultiplier;
        double zValue = z * xzMultiplier;
        double mainX = xValue / parameters.xzFactor();
        double mainY = yValue / parameters.yFactor();
        double mainZ = zValue / parameters.xzFactor();
        double smearScale = yMultiplier * parameters.smearScaleMultiplier() / parameters.yFactor();

        double main = 0.0;
        double mainScale = 1.0;
        for (int i = 0; i < 8; i++) {
            main += legacyLevel(parameters.mainNoise(), i,
                    wrap(mainX * mainScale), wrap(mainY * mainScale), wrap(mainZ * mainScale),
                    smearScale * mainScale, mainY * mainScale) / mainScale;
            mainScale /= 2.0;
        }
        double blend = (main / 10.0 + 1.0) / 2.0;
        boolean skipMin = blend >= 1.0;
        boolean skipMax = blend <= 0.0;
        double min = 0.0;
        double max = 0.0;
        double limitScale = 1.0;
        double limitSmear = yMultiplier * parameters.smearScaleMultiplier();
        for (int i = 0; i < 16; i++) {
            if (!skipMin) {
                min += legacyLevel(parameters.minLimitNoise(), i,
                        wrap(xValue * limitScale), wrap(yValue * limitScale), wrap(zValue * limitScale),
                        limitSmear * limitScale, yValue * limitScale) / limitScale;
            }
            if (!skipMax) {
                max += legacyLevel(parameters.maxLimitNoise(), i,
                        wrap(xValue * limitScale), wrap(yValue * limitScale), wrap(zValue * limitScale),
                        limitSmear * limitScale, yValue * limitScale) / limitScale;
            }
            limitScale /= 2.0;
        }
        double lower = min / 512.0;
        double upper = max / 512.0;
        double clampedBlend = Math.max(0.0, Math.min(1.0, blend));
        return (lower + clampedBlend * (upper - lower)) / 128.0;
    }

    /**
     * Replays one captured legacy octave from {@link #sampleBlended}.
     *
     * <p>This deliberately exposes the same intermediate boundary used by
     * the isolated GPU fan-out diagnostic.  It is not a second worldgen
     * implementation: the coordinate setup and octave dispatch are kept
     * identical to {@code sampleBlended}, while the caller selects one
     * statically emitted sample.</p>
     */
    public double sampleBlendedSample(BlendedNoiseParameters parameters, String group,
                                      int octave, double x, double y, double z) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(group, "group");
        if (octave < 0 || (group.equals("main") && octave >= 8)
                || ((group.equals("min") || group.equals("max")) && octave >= 16)
                || !(group.equals("main") || group.equals("min") || group.equals("max"))) {
            throw new IllegalArgumentException("Invalid blended-noise sample: " + group + ":" + octave);
        }
        double xzMultiplier = 684.412 * parameters.xzScale();
        double yMultiplier = 684.412 * parameters.yScale();
        double xValue = x * xzMultiplier;
        double yValue = y * yMultiplier;
        double zValue = z * xzMultiplier;
        double scale = Math.scalb(1.0, -octave);
        if (group.equals("main")) {
            double mainX = xValue / parameters.xzFactor();
            double mainY = yValue / parameters.yFactor();
            double mainZ = zValue / parameters.xzFactor();
            double smearScale = yMultiplier * parameters.smearScaleMultiplier() / parameters.yFactor();
            return legacyLevel(parameters.mainNoise(), octave,
                    wrap(mainX * scale), wrap(mainY * scale), wrap(mainZ * scale),
                    smearScale * scale, mainY * scale);
        }
        double limitSmear = yMultiplier * parameters.smearScaleMultiplier();
        NoiseParameters.PerlinNoiseSnapshot noise = group.equals("min")
                ? parameters.minLimitNoise() : parameters.maxLimitNoise();
        return legacyLevel(noise, octave,
                wrap(xValue * scale), wrap(yValue * scale), wrap(zValue * scale),
                limitSmear * scale, yValue * scale);
    }

    private static double legacyLevel(NoiseParameters.PerlinNoiseSnapshot noise, int octave,
                                      double x, double y, double z, double yScale, double yMax) {
        int levelIndex = noise.levels().size() - 1 - octave;
        if (levelIndex < 0 || levelIndex >= noise.levels().size()) return 0.0;
        var level = noise.levels().get(levelIndex);
        if (level == null) return 0.0;
        return noise.amplitudes().get(levelIndex)
                * improvedWithYScale(level, x, y, z, yScale, yMax);
    }

    /**
     * Replays the seed-expanded 1.21.1 NormalNoise state captured by the loader.  The
     * parameter-only path above remains a deterministic fixture fallback; it is never
     * mixed with a captured table for the wrong world seed.
     */
    private static double capturedNormal(NoiseParameters.CapturedNoise noise, double x, double y, double z) {
        double factor = 1.0181268882175227;
        double first = capturedPerlin(noise.first(), x, y, z);
        double second = capturedPerlin(noise.second(), x * factor, y * factor, z * factor);
        return (first + second) * noise.valueFactor();
    }

    private static double capturedPerlin(NoiseParameters.PerlinNoiseSnapshot noise, double x, double y, double z) {
        // The captured field is Minecraft's internal PerlinNoise.firstOctave.
        // It is the signed lowest octave itself (normally negative), and
        // PerlinNoise derives its initial input factor as 2^firstOctave.
        double inputFactor = Math.scalb(1.0, noise.firstOctave());
        // PerlinNoise derives this factor from the number of captured levels,
        // not from the index of the lowest octave.  Using the latter happens
        // to look plausible for a few vanilla noises but scales every octave
        // stack incorrectly as soon as the two values differ.
        int levelCount = noise.levels().size();
        double valueFactor = Math.scalb(1.0, levelCount - 1) / (Math.scalb(1.0, levelCount) - 1.0);
        double result = 0.0;
        for (int i = 0; i < noise.levels().size(); i++) {
            var level = noise.levels().get(i);
            if (level != null) {
                result += noise.amplitudes().get(i)
                        * improved(level, wrap(x * inputFactor), wrap(y * inputFactor), wrap(z * inputFactor))
                        * valueFactor;
            }
            inputFactor *= 2.0;
            valueFactor /= 2.0;
        }
        return result;
    }

    private static double improved(NoiseParameters.ImprovedNoiseSnapshot noise, double x, double y, double z) {
        double shiftedX = x + noise.xOffset();
        double shiftedY = y + noise.yOffset();
        double shiftedZ = z + noise.zOffset();
        int x0 = floor(shiftedX), y0 = floor(shiftedY), z0 = floor(shiftedZ);
        double fx = shiftedX - x0, fy = shiftedY - y0, fz = shiftedZ - z0;
        var permutation = noise.permutation();
        int xHash = permutation.get(x0 & 255), xHashNext = permutation.get((x0 + 1) & 255);
        int xy00 = permutation.get((xHash + y0) & 255), xy01 = permutation.get((xHash + y0 + 1) & 255);
        int xy10 = permutation.get((xHashNext + y0) & 255), xy11 = permutation.get((xHashNext + y0 + 1) & 255);
        double v000 = gradient(permutation.get((xy00 + z0) & 255), fx, fy, fz);
        double v100 = gradient(permutation.get((xy10 + z0) & 255), fx - 1.0, fy, fz);
        double v010 = gradient(permutation.get((xy01 + z0) & 255), fx, fy - 1.0, fz);
        double v110 = gradient(permutation.get((xy11 + z0) & 255), fx - 1.0, fy - 1.0, fz);
        double v001 = gradient(permutation.get((xy00 + z0 + 1) & 255), fx, fy, fz - 1.0);
        double v101 = gradient(permutation.get((xy10 + z0 + 1) & 255), fx - 1.0, fy, fz - 1.0);
        double v011 = gradient(permutation.get((xy01 + z0 + 1) & 255), fx, fy - 1.0, fz - 1.0);
        double v111 = gradient(permutation.get((xy11 + z0 + 1) & 255), fx - 1.0, fy - 1.0, fz - 1.0);
        double sx = smoothstep(fx), sy = smoothstep(fy), sz = smoothstep(fz);
        double x00 = lerp(sx, v000, v100), x10 = lerp(sx, v010, v110);
        double x01 = lerp(sx, v001, v101), x11 = lerp(sx, v011, v111);
        return lerp(sz, lerp(sy, x00, x10), lerp(sy, x01, x11));
    }

    private static double improvedWithYScale(NoiseParameters.ImprovedNoiseSnapshot noise,
                                             double x, double y, double z,
                                             double yScale, double yMax) {
        double shiftedX = x + noise.xOffset();
        double shiftedY = y + noise.yOffset();
        double shiftedZ = z + noise.zOffset();
        int x0 = floor(shiftedX), y0 = floor(shiftedY), z0 = floor(shiftedZ);
        double fx = shiftedX - x0, fy = shiftedY - y0, fz = shiftedZ - z0;
        double ySmear = 0.0;
        if (yScale != 0.0) {
            double clamped = yMax >= 0.0 && yMax < fy ? yMax : fy;
            ySmear = Math.floor(clamped / yScale + 1.0000000116860974E-7) * yScale;
        }
        double adjustedFy = fy - ySmear;
        var permutation = noise.permutation();
        int xHash = permutation.get(x0 & 255), xHashNext = permutation.get((x0 + 1) & 255);
        int xy00 = permutation.get((xHash + y0) & 255), xy01 = permutation.get((xHash + y0 + 1) & 255);
        int xy10 = permutation.get((xHashNext + y0) & 255), xy11 = permutation.get((xHashNext + y0 + 1) & 255);
        double v000 = gradient(permutation.get((xy00 + z0) & 255), fx, adjustedFy, fz);
        double v100 = gradient(permutation.get((xy10 + z0) & 255), fx - 1.0, adjustedFy, fz);
        double v010 = gradient(permutation.get((xy01 + z0) & 255), fx, adjustedFy - 1.0, fz);
        double v110 = gradient(permutation.get((xy11 + z0) & 255), fx - 1.0, adjustedFy - 1.0, fz);
        double v001 = gradient(permutation.get((xy00 + z0 + 1) & 255), fx, adjustedFy, fz - 1.0);
        double v101 = gradient(permutation.get((xy10 + z0 + 1) & 255), fx - 1.0, adjustedFy, fz - 1.0);
        double v011 = gradient(permutation.get((xy01 + z0 + 1) & 255), fx, adjustedFy - 1.0, fz - 1.0);
        double v111 = gradient(permutation.get((xy11 + z0 + 1) & 255), fx - 1.0, adjustedFy - 1.0, fz - 1.0);
        double sx = smoothstep(fx), sy = smoothstep(fy), sz = smoothstep(fz);
        double x00 = lerp(sx, v000, v100), x10 = lerp(sx, v010, v110);
        double x01 = lerp(sx, v001, v101), x11 = lerp(sx, v011, v111);
        return lerp(sz, lerp(sy, x00, x10), lerp(sy, x01, x11));
    }

    private static final int[][] GRADIENTS = {
            {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
            {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
            {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1},
            {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}
    };

    private static final double SIMPLEX_F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
    private static final double SIMPLEX_G2 = (3.0 - Math.sqrt(3.0)) / 6.0;

    private static double gradient(int hash, double x, double y, double z) {
        int[] gradient = GRADIENTS[hash & 15];
        return gradient[0] * x + gradient[1] * y + gradient[2] * z;
    }

    private static double smoothstep(double value) { return value * value * value * (value * (value * 6.0 - 15.0) + 10.0); }
    private static double wrap(double value) { return value - Math.floor(value / 33554432.0 + 0.5) * 33554432.0; }
    private static double valueNoise(long seed, double x, double y, double z) {
        int x0 = floor(x), y0 = floor(y), z0 = floor(z);
        double tx = x - x0, ty = y - y0, tz = z - z0;
        double[][] yz = new double[2][2];
        for (int iy = 0; iy < 2; iy++) for (int iz = 0; iz < 2; iz++) {
            double low = lattice(seed, x0, y0 + iy, z0 + iz);
            double high = lattice(seed, x0 + 1, y0 + iy, z0 + iz);
            yz[iy][iz] = lerp(tx, low, high);
        }
        return lerp(tz, lerp(ty, yz[0][0], yz[1][0]), lerp(ty, yz[0][1], yz[1][1]));
    }
    // Mth.floor(double) first performs Java's narrowing conversion and only
    // then corrects values below the truncated integer.  Keeping that order
    // matters for the boundary behavior of the captured value-noise helper.
    private static int floor(double value) {
        int integer = (int) value;
        return value < (double) integer ? integer - 1 : integer;
    }
    private static double lattice(long seed, int x, int y, int z) {
        long value = seed + x * 0x9E3779B97F4A7C15L + y * 0xC2B2AE3D27D4EB4FL + z * 0x165667B19E3779F9L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return ((value ^ (value >>> 31)) >>> 11) * 0x1.0p-53 * 2.0 - 1.0;
    }
    private static double lerp(double t, double a, double b) { return a + t * (b - a); }
}
