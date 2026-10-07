// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.jvm.worldgen;

/** Java-compatible 48-bit linear congruential random source used by legacy worldgen paths. */
public final class LegacyRandom {
    private static final long MULTIPLIER = 0x5DEECE66DL;
    private static final long ADDEND = 0xBL;
    private static final long MASK = (1L << 48) - 1;
    private long seed;
    public LegacyRandom(long seed) { this.seed = (seed ^ MULTIPLIER) & MASK; }
    public long state() { return seed; }
    public int nextInt() { return nextBits(32); }
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("Bound must be positive");
        if ((bound & -bound) == bound) return (int) ((bound * (long) nextBits(31)) >> 31);
        int bits, value;
        do { bits = nextBits(31); value = bits % bound; } while (bits - value + (bound - 1) < 0);
        return value;
    }
    public long nextLong() { return ((long) nextInt() << 32) + (nextInt() & 0xffffffffL); }
    public float nextFloat() { return nextBits(24) * 5.9604645E-8F; }
    public double nextDouble() { return ((long) nextBits(26) << 27 | nextBits(27)) * 0x1.0p-53; }
    public LegacyRandom fork(long salt) { return new LegacyRandom(nextLong() ^ salt); }
    private int nextBits(int bits) { seed = (seed * MULTIPLIER + ADDEND) & MASK; return (int) (seed >>> (48 - bits)); }
}
