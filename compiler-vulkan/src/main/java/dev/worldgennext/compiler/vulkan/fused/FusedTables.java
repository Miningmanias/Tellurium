// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan.fused;

import dev.worldgennext.semantic.snapshot.BlendedNoiseParameters;
import dev.worldgennext.semantic.snapshot.EndIslandParameters;
import dev.worldgennext.semantic.snapshot.NoiseParameters;

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
    int spline(dev.worldgennext.semantic.program.ProgramNode.SplineMultipoint node,
               List<dev.worldgennext.semantic.program.ProgramNode> coordinates,
               Map<dev.worldgennext.semantic.program.ProgramNode, Integer> indexOf) {
        var byCoordinates = splineRecords.computeIfAbsent(node, k -> new java.util.HashMap<>());
        List<Object> key = new ArrayList<>(coordinates);
        Integer existing = byCoordinates.get(key);
        if (existing != null) return existing;
        int n = node.locations().size();
        int[] payloads = new int[n];
        int[] kinds = new int[n];
        for (int i = 0; i < n; i++) {
            var value = node.values().get(i);
            if (value instanceof dev.worldgennext.semantic.program.ProgramNode.SplineConstant constant) {
                kinds[i] = 0;
                payloads[i] = Float.floatToRawIntBits(constant.value());
            } else {
                kinds[i] = 1;
                payloads[i] = spline((dev.worldgennext.semantic.program.ProgramNode.SplineMultipoint) value, coordinates, indexOf);
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
