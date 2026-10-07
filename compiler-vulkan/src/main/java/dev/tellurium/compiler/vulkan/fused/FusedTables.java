// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.fused;

import dev.tellurium.semantic.snapshot.BlendedNoiseParameters;
import dev.tellurium.semantic.snapshot.EndIslandParameters;
import dev.tellurium.semantic.snapshot.NoiseParameters;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable lookup tables for the fused kernels: a double table holding
 * Perlin/Normal/Blended/End records with exactly precomputed per-octave
 * factors, and a uint table holding 256-entry permutations.
 */
final class FusedTables {
    private final List<Double> doubles = new ArrayList<>();
    private final List<Integer> perm = new ArrayList<>();
    private final Map<Object, Integer> perlinRecords = new IdentityHashMap<>();
    private final Map<Object, Integer> normalRecords = new IdentityHashMap<>();
    private final Map<Object, Integer> blendedRecords = new IdentityHashMap<>();
    private final Map<Object, Integer> endRecords = new IdentityHashMap<>();
    private final Map<Object, Integer> permRecords = new IdentityHashMap<>();

    int normal(NoiseParameters.CapturedNoise noise) {
        Integer existing = normalRecords.get(noise);
        if (existing != null) return existing;
        int first = perlin(noise.first());
        int second = perlin(noise.second());
        int index = doubles.size();
        doubles.add(noise.valueFactor());
        doubles.add((double) first);
        doubles.add((double) second);
        normalRecords.put(noise, index);
        return index;
    }

    /** Perlin record: [n, per level: amplitude, inputFactor, valueFactor, xo, yo, zo, permBase]. */
    int perlin(NoiseParameters.PerlinNoiseSnapshot noise) {
        Integer existing = perlinRecords.get(noise);
        if (existing != null) return existing;
        List<Integer> levelPerms = new ArrayList<>();
        for (var level : noise.levels()) levelPerms.add(level == null ? -1 : permutation(level.permutation()));
        int index = doubles.size();
        int n = noise.levels().size();
        doubles.add((double) n);
        // Same derivation and operation order as PerlinNoise / the CPU reference.
        double inputFactor = Math.scalb(1.0, noise.firstOctave());
        double valueFactor = Math.scalb(1.0, n - 1) / (Math.scalb(1.0, n) - 1.0);
        for (int i = 0; i < n; i++) {
            var level = noise.levels().get(i);
            doubles.add(noise.amplitudes().get(i));
            doubles.add(inputFactor);
            doubles.add(valueFactor);
            doubles.add(level == null ? 0.0 : level.xOffset());
            doubles.add(level == null ? 0.0 : level.yOffset());
            doubles.add(level == null ? 0.0 : level.zOffset());
            doubles.add((double) levelPerms.get(i));
            inputFactor *= 2.0;
            valueFactor /= 2.0;
        }
        perlinRecords.put(noise, index);
        return index;
    }

    /** Blended record: [xzScale, yScale, xzFactor, yFactor, smear, minP, maxP, mainP]. */
    int blended(BlendedNoiseParameters parameters) {
        Integer existing = blendedRecords.get(parameters);
        if (existing != null) return existing;
        int min = perlin(parameters.minLimitNoise());
        int max = perlin(parameters.maxLimitNoise());
        int main = perlin(parameters.mainNoise());
        int index = doubles.size();
        doubles.add(parameters.xzScale());
        doubles.add(parameters.yScale());
        doubles.add(parameters.xzFactor());
        doubles.add(parameters.yFactor());
        doubles.add(parameters.smearScaleMultiplier());
        doubles.add((double) min);
        doubles.add((double) max);
        doubles.add((double) main);
        blendedRecords.put(parameters, index);
        return index;
    }

    int endIsland(EndIslandParameters parameters) {
        Integer existing = endRecords.get(parameters);
        if (existing != null) return existing;
        int base = permutation(parameters.permutation());
        int index = doubles.size();
        doubles.add((double) base);
        endRecords.put(parameters, index);
        return index;
    }

    private final Map<Object, Map<List<Object>, Integer>> splineRecords = new IdentityHashMap<>();

    /**
     * Encodes a spline multipoint tree into the uint table:
     * [n, coordinateIndex, then per point: locationBits, derivativeBits, kind (0 constant / 1 child), payload].
     * Children are encoded first; returns the record offset.
     */
    int spline(dev.tellurium.semantic.program.ProgramNode.SplineMultipoint node,
               List<dev.tellurium.semantic.program.ProgramNode> coordinates,
               Map<dev.tellurium.semantic.program.ProgramNode, Integer> indexOf) {
        var byCoordinates = splineRecords.computeIfAbsent(node, k -> new java.util.HashMap<>());
        List<Object> key = new ArrayList<>(coordinates);
        Integer existing = byCoordinates.get(key);
        if (existing != null) return existing;
        int n = node.locations().size();
        int[] payloads = new int[n];
        int[] kinds = new int[n];
        for (int i = 0; i < n; i++) {
            var value = node.values().get(i);
            if (value instanceof dev.tellurium.semantic.program.ProgramNode.SplineConstant constant) {
                kinds[i] = 0;
                payloads[i] = Float.floatToRawIntBits(constant.value());
            } else {
                kinds[i] = 1;
                payloads[i] = spline((dev.tellurium.semantic.program.ProgramNode.SplineMultipoint) value, coordinates, indexOf);
            }
        }
        Integer coordinateIndex = indexOf.get(node.coordinate());
        if (coordinateIndex == null) throw new IllegalStateException("Spline coordinate missing from argument list");
        int offset = perm.size();
        perm.add(n);
        perm.add(coordinateIndex);
        for (int i = 0; i < n; i++) {
            perm.add(Float.floatToRawIntBits(node.locations().get(i)));
            perm.add(Float.floatToRawIntBits(node.derivatives().get(i)));
            perm.add(kinds[i]);
            perm.add(payloads[i]);
        }
        byCoordinates.put(key, offset);
        return offset;
    }

    static final int SURFACE_HEADER_INTS = 30;

    private int ints(int[] values) {
        int index = perm.size();
        for (int value : values) perm.add(value);
        return index;
    }

    /** Random record: [legacy, seed lo/hi, seedLo lo/hi, seedHi lo/hi]. */
    private int random(dev.tellurium.semantic.snapshot.PositionalRandomFactorySnapshot random) {
        boolean legacy = random.algorithm() == dev.tellurium.semantic.snapshot.PositionalRandomFactorySnapshot.Algorithm.LEGACY;
        return ints(new int[]{legacy ? 1 : 0, (int) random.seed(), (int) (random.seed() >>> 32),
                (int) random.seedLo(), (int) (random.seedLo() >>> 32), (int) random.seedHi(), (int) (random.seedHi() >>> 32)});
    }

    /** Simplex octave record in the double table: [n, inputFactor, valueFactor, permBase per level (-1 = null)]. */
    private int simplex(SurfaceProgram.SimplexOctaves noise) {
        int[] bases = new int[noise.permutations().size()];
        for (int i = 0; i < bases.length; i++) {
            List<Integer> p = noise.permutations().get(i);
            bases[i] = p == null || p.isEmpty() ? -1 : permutation(p);
        }
        int index = doubles.size();
        doubles.add((double) bases.length);
        doubles.add(noise.highestFreqInputFactor());
        doubles.add(noise.highestFreqValueFactor());
        for (int base : bases) doubles.add((double) base);
        return index;
    }

    /** Encodes the surface program; returns the (non-zero) header offset in the uint table. */
    int surface(SurfaceProgram s) {
        int code = ints(s.code());
        int flags = ints(s.paletteFlags());
        int[] slots = new int[s.conditionNoises().size()];
        for (int i = 0; i < slots.length; i++) slots[i] = normal(s.conditionNoises().get(i));
        int noiseSlots = ints(slots);
        int noiseBounds = raw(s.conditionNoiseBounds());
        int gradients = perm.size();
        for (var random : s.gradientRandoms()) random(random);
        int bands = ints(s.clayBands());
        int[] biomes = new int[s.biomeCount() * 2];
        for (int i = 0; i < s.biomeCount(); i++) {
            biomes[2 * i] = Float.floatToRawIntBits(s.biomeBaseTemperature()[i]);
            biomes[2 * i + 1] = s.biomeFlags()[i];
        }
        int biomeTable = ints(biomes);
        int masks = ints(s.biomeMasks());
        int noiseRandom = random(s.noiseRandom());
        int[] header = new int[SURFACE_HEADER_INTS];
        header[0] = code;
        header[1] = flags;
        header[2] = s.paletteFlags().length;
        header[3] = noiseSlots;
        header[4] = gradients;
        header[5] = bands;
        header[6] = biomeTable;
        header[7] = masks;
        header[8] = s.maskWords();
        header[9] = normal(s.surfaceNoise());
        header[10] = normal(s.surfaceSecondaryNoise());
        header[11] = normal(s.clayBandsOffsetNoise());
        header[12] = normal(s.badlandsPillarNoise());
        header[13] = normal(s.badlandsPillarRoofNoise());
        header[14] = normal(s.badlandsSurfaceNoise());
        header[15] = normal(s.icebergPillarNoise());
        header[16] = normal(s.icebergPillarRoofNoise());
        header[17] = normal(s.icebergSurfaceNoise());
        header[18] = noiseRandom;
        header[19] = s.biomeLookupAtY0() ? 1 : 0;
        header[20] = (int) s.biomeZoomSeed();
        header[21] = (int) (s.biomeZoomSeed() >>> 32);
        header[22] = s.snowBlock();
        header[23] = s.packedIce();
        header[24] = s.defaultBlock();
        header[25] = simplex(s.temperatureNoise());
        header[26] = simplex(s.frozenTemperatureNoise());
        header[27] = simplex(s.biomeInfoNoise());
        header[28] = s.biomeCount();
        header[29] = noiseBounds;
        if (perm.isEmpty()) perm.add(0); // offset 0 means "no surface program"
        return ints(header);
    }

    /** Appends raw doubles (e.g. the Beardifier kernel); returns the first index. */
    int raw(double[] values) {
        int index = doubles.size();
        for (double value : values) doubles.add(value);
        return index;
    }

    private int permutation(List<Integer> values) {
        Integer existing = permRecords.get(values);
        if (existing != null) return existing;
        if (values.size() != 256) throw new IllegalArgumentException("Permutation must have 256 entries");
        int base = perm.size();
        for (int value : values) {
            if (value < 0 || value > 255) throw new IllegalArgumentException("Permutation entry out of range");
            perm.add(value);
        }
        permRecords.put(values, base);
        return base;
    }

    double[] doubleTable() {
        double[] out = new double[Math.max(1, doubles.size())];
        for (int i = 0; i < doubles.size(); i++) out[i] = doubles.get(i);
        return out;
    }

    int[] permTable() {
        int[] out = new int[Math.max(1, perm.size())];
        for (int i = 0; i < perm.size(); i++) out[i] = perm.get(i);
        return out;
    }
}
