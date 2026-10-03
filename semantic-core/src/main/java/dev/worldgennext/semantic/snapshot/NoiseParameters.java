// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic.snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Seed-expanded octave parameters captured from the pinned game stack. */
public record NoiseParameters(String key, int firstOctave, List<Double> amplitudes, long salt,
                              CapturedNoise captured) {
    /** Backwards-compatible parameter-only form used by pure fixtures. */
    public NoiseParameters(String key, int firstOctave, List<Double> amplitudes, long salt) {
        this(key, firstOctave, amplitudes, salt, null);
    }

    public NoiseParameters {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Noise key required");
        if (amplitudes == null || amplitudes.isEmpty() || amplitudes.size() > 64) throw new IllegalArgumentException("Invalid octave count");
        amplitudes = List.copyOf(amplitudes);
        if (amplitudes.stream().anyMatch(value -> value == null || !Double.isFinite(value))) throw new IllegalArgumentException("Non-finite amplitude");
    }
    public static NoiseParameters single(String key, double amplitude) { return new NoiseParameters(Objects.requireNonNull(key), 0, List.of(amplitude), 0); }

    /** Seed-expanded state of one pinned NormalNoise instance. */
    public record CapturedNoise(long worldSeed, double valueFactor, PerlinNoiseSnapshot first,
                                PerlinNoiseSnapshot second) {
        public CapturedNoise {
            if (!Double.isFinite(valueFactor)) throw new IllegalArgumentException("Non-finite noise value factor");
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(second, "second");
        }
    }

    /** Captured Perlin octave array, including null slots for zero amplitudes. */
    public record PerlinNoiseSnapshot(int firstOctave, List<Double> amplitudes,
                                      List<ImprovedNoiseSnapshot> levels) {
        public PerlinNoiseSnapshot {
            if (amplitudes == null || amplitudes.isEmpty() || amplitudes.size() > 64) throw new IllegalArgumentException("Invalid captured amplitudes");
            amplitudes = List.copyOf(amplitudes);
            if (amplitudes.stream().anyMatch(value -> value == null || !Double.isFinite(value))) throw new IllegalArgumentException("Non-finite captured amplitude");
            if (levels == null || levels.size() != amplitudes.size()) throw new IllegalArgumentException("Captured octave levels must match amplitudes");
            levels = Collections.unmodifiableList(new ArrayList<>(levels));
        }
    }

    /** Immutable ImprovedNoise permutation and coordinate offsets. */
    public record ImprovedNoiseSnapshot(double xOffset, double yOffset, double zOffset,
                                        List<Integer> permutation) {
        public ImprovedNoiseSnapshot {
            if (!Double.isFinite(xOffset) || !Double.isFinite(yOffset) || !Double.isFinite(zOffset)) throw new IllegalArgumentException("Non-finite noise offset");
            if (permutation == null || permutation.size() != 256) throw new IllegalArgumentException("ImprovedNoise permutation must contain 256 entries");
            permutation = List.copyOf(permutation);
            if (permutation.stream().anyMatch(value -> value == null || value < 0 || value > 255)) throw new IllegalArgumentException("Invalid permutation entry");
        }
    }
}
