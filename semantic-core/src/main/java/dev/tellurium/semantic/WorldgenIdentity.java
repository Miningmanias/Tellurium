// SPDX-License-Identifier: MIT
package dev.tellurium.semantic;

public record WorldgenIdentity(long seed, String dimension, String graphHash, long epoch) {
    public WorldgenIdentity { java.util.Objects.requireNonNull(dimension); java.util.Objects.requireNonNull(graphHash); if (epoch < 0) throw new IllegalArgumentException("Negative epoch"); }
}
