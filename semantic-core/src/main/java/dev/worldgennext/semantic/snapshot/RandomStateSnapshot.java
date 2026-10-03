// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic.snapshot;

import java.util.ArrayList;
import java.util.List;

/** Immutable seed/RNG tables. Tables are captured data, not a live random object. */
public record RandomStateSnapshot(long worldSeed, long salt, List<Long> permutation,
                                  List<NoiseParameters> noiseParameters,
                                  PositionalRandomFactorySnapshot aquiferRandom,
                                  PositionalRandomFactorySnapshot oreRandom) {
    public RandomStateSnapshot(long worldSeed, long salt, List<Long> permutation,
                                List<NoiseParameters> noiseParameters) {
        this(worldSeed, salt, permutation, noiseParameters, null, null);
    }
    public RandomStateSnapshot {
        permutation = List.copyOf(permutation == null ? List.of() : permutation);
        noiseParameters = List.copyOf(noiseParameters == null ? List.of() : noiseParameters);
        if (permutation.size() > 1_048_576) throw new IllegalArgumentException("Permutation table too large");
        if (permutation.stream().anyMatch(value -> value == null)) throw new IllegalArgumentException("Null permutation entry");
    }
    public static RandomStateSnapshot forSeed(long seed) {
        var values = new ArrayList<Long>(256);
        long state = seed ^ 0x9E3779B97F4A7C15L;
        for (int i = 0; i < 256; i++) { state = mix(state + i); values.add(state); }
        return new RandomStateSnapshot(seed, 0, values, List.of(), null, null);
    }
    public long positional(int x, int y, int z) {
        long value = worldSeed ^ salt;
        value ^= (long) x * 0x9E3779B97F4A7C15L;
        value ^= (long) y * 0xC2B2AE3D27D4EB4FL;
        value ^= (long) z * 0x165667B19E3779F9L;
        return mix(value);
    }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
