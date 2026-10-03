// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic.snapshot;

import java.util.Objects;

/** Seed-expanded state for a Minecraft positional random factory. */
public record PositionalRandomFactorySnapshot(Algorithm algorithm, long seed, long seedLo, long seedHi) {
    public enum Algorithm { LEGACY, XOROSHIRO }
    private static final long XOROSHIRO_DEFAULT_LO = -7046029254386353131L;
    private static final long XOROSHIRO_DEFAULT_HI = 7640891576956012809L;

    public PositionalRandomFactorySnapshot {
        Objects.requireNonNull(algorithm, "algorithm");
        if (algorithm == Algorithm.XOROSHIRO && (seedLo | seedHi) == 0L) {
            // Xoroshiro128PlusPlus replaces the forbidden zero state during
            // construction; rejecting it here would make a captured factory
            // differ from Minecraft before its first draw.
            seedLo = XOROSHIRO_DEFAULT_LO;
            seedHi = XOROSHIRO_DEFAULT_HI;
        }
    }

    public static PositionalRandomFactorySnapshot legacy(long seed) {
        return new PositionalRandomFactorySnapshot(Algorithm.LEGACY, seed, 0L, 0L);
    }

    public static PositionalRandomFactorySnapshot xoroshiro(long seedLo, long seedHi) {
        return new PositionalRandomFactorySnapshot(Algorithm.XOROSHIRO, 0L, seedLo, seedHi);
    }
}
