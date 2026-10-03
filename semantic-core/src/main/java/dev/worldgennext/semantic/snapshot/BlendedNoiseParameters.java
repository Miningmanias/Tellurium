// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic.snapshot;

import java.util.Objects;

/** Seed-expanded legacy BlendedNoise state captured from the pinned 1.21.1 stack. */
public record BlendedNoiseParameters(long worldSeed,
                                     NoiseParameters.PerlinNoiseSnapshot minLimitNoise,
                                     NoiseParameters.PerlinNoiseSnapshot maxLimitNoise,
                                     NoiseParameters.PerlinNoiseSnapshot mainNoise,
                                     double xzScale, double yScale, double xzFactor,
                                     double yFactor, double smearScaleMultiplier) {
    public BlendedNoiseParameters {
        Objects.requireNonNull(minLimitNoise, "minLimitNoise");
        Objects.requireNonNull(maxLimitNoise, "maxLimitNoise");
        Objects.requireNonNull(mainNoise, "mainNoise");
        if (!Double.isFinite(xzScale) || !Double.isFinite(yScale) || !Double.isFinite(xzFactor)
                || !Double.isFinite(yFactor) || !Double.isFinite(smearScaleMultiplier)
                || xzScale == 0.0 || yScale == 0.0 || xzFactor == 0.0 || yFactor == 0.0) {
            throw new IllegalArgumentException("BlendedNoise scales must be finite and nonzero");
        }
    }
}
