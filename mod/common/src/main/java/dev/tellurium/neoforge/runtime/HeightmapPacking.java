// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

/** Dependency-free checked arithmetic for Minecraft's packed heightmap width. */
final class HeightmapPacking {
    private HeightmapPacking() {}

    static int bitsForHeight(int height) {
        if (height <= 0) throw new IllegalArgumentException("Chunk height must be positive");
        final int inclusiveHeight;
        try {
            inclusiveHeight = Math.addExact(height, 1);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Chunk height overflows heightmap bounds", overflow);
        }
        // This is Mth.ceillog2(value) for a positive int, written without a
        // Minecraft class dependency so the arithmetic contract can be tested
        // on the pure unit-test classpath as well.
        int bits = Integer.SIZE - Integer.numberOfLeadingZeros(inclusiveHeight - 1);
        if (bits <= 0 || bits >= Long.SIZE) {
            throw new IllegalArgumentException("Unsupported heightmap bit width: " + bits);
        }
        return bits;
    }
}
