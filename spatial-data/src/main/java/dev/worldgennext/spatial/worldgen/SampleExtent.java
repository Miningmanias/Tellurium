// SPDX-License-Identifier: MIT
package dev.worldgennext.spatial.worldgen;

/** Half-open XYZ sample extent with checked volume arithmetic. */
public record SampleExtent(int minX, int minY, int minZ, int maxXExclusive, int maxYExclusive, int maxZExclusive) {
    public SampleExtent {
        if (maxXExclusive <= minX || maxYExclusive <= minY || maxZExclusive <= minZ) throw new IllegalArgumentException("Empty sample extent");
        long width = (long) maxXExclusive - minX;
        long height = (long) maxYExclusive - minY;
        long depth = (long) maxZExclusive - minZ;
        try {
            long volume = Math.multiplyExact(Math.multiplyExact(width, height), depth);
            if (volume > Integer.MAX_VALUE) throw new IllegalArgumentException("Sample extent too large");
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Sample extent volume overflow", overflow);
        }
    }
    public int width() { return Math.toIntExact((long) maxXExclusive - minX); }
    public int height() { return Math.toIntExact((long) maxYExclusive - minY); }
    public int depth() { return Math.toIntExact((long) maxZExclusive - minZ); }
    public int volume() { return Math.multiplyExact(Math.multiplyExact(width(), height()), depth()); }
    public boolean contains(int x, int y, int z) { return x >= minX && x < maxXExclusive && y >= minY && y < maxYExclusive && z >= minZ && z < maxZExclusive; }
    public int index(int x, int y, int z) {
        if (!contains(x, y, z)) throw new IndexOutOfBoundsException();
        long index = (((long) y - minY) * depth() + ((long) z - minZ)) * width() + ((long) x - minX);
        return Math.toIntExact(index);
    }
    public SampleExtent expand(int halo) {
        if (halo < 0) throw new IllegalArgumentException("Negative halo");
        try {
            return new SampleExtent(Math.subtractExact(minX, halo), Math.subtractExact(minY, halo), Math.subtractExact(minZ, halo),
                    Math.addExact(maxXExclusive, halo), Math.addExact(maxYExclusive, halo), Math.addExact(maxZExclusive, halo));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Expanded sample extent exceeds coordinate bounds", overflow);
        }
    }
}
