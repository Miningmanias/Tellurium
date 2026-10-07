// SPDX-License-Identifier: MIT
package dev.tellurium.semantic.snapshot;

import java.util.List;

/** Seed-expanded 2D simplex state used by Minecraft's End island density function. */
public record EndIslandParameters(double xOffset, double yOffset, List<Integer> permutation) {
    public EndIslandParameters {
        if (!Double.isFinite(xOffset) || !Double.isFinite(yOffset)) throw new IllegalArgumentException("End island offsets must be finite");
        if (permutation == null || permutation.size() != 256) throw new IllegalArgumentException("End island permutation must contain 256 entries");
        permutation = List.copyOf(permutation);
        if (permutation.stream().anyMatch(value -> value == null || value < 0 || value > 255)) throw new IllegalArgumentException("Invalid End island permutation entry");
    }
}
