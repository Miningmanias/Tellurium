// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.jvm.worldgen;

/** Small deterministic xoroshiro-style source with explicit state ownership. */
public final class XoroshiroRandom {
    private static final long DEFAULT_LO = -7046029254386353131L;
    private static final long DEFAULT_HI = 7640891576956012809L;
    private long lo;
    private long hi;
    public XoroshiroRandom(long seed) { lo = mix(seed); hi = mix(lo); if ((lo | hi) == 0) hi = 1; }
    public XoroshiroRandom(long lo, long hi) {
        if ((lo | hi) == 0) { lo = DEFAULT_LO; hi = DEFAULT_HI; }
        this.lo = lo; this.hi = hi;
    }
    public long nextLong() {
        long result = Long.rotateLeft(lo + hi, 17) + lo;
        long nextHi = hi ^ lo;
        lo = Long.rotateLeft(lo, 49) ^ nextHi ^ (nextHi << 21);
        hi = Long.rotateLeft(nextHi, 28);
        return result;
    }
    public double nextDouble() { return (nextLong() >>> 11) * 0x1.0p-53; }
    public int nextInt(int bound) {
        if (bound <= 0) throw new IllegalArgumentException("Bound must be positive");
        // Minecraft's XoroshiroRandomSource.nextInt() takes the low 32 bits of
        // nextLong(), then treats them as unsigned before multiply-and-reject.
        // Using the high half here silently changes every positional ore and
        // aquifer draw while still looking statistically plausible.
        long bits = nextLong() & 0xffffffffL;
        long product = bits * (long) bound;
        long low = product & 0xffffffffL;
        if (low < bound) {
            long threshold = Integer.remainderUnsigned(-bound, bound);
            while (low < threshold) {
                bits = nextLong() & 0xffffffffL;
                product = bits * (long) bound;
                low = product & 0xffffffffL;
            }
        }
        return (int) (product >>> 32);
    }
    public float nextFloat() { return (float) (nextLong() >>> 40) * 5.9604645E-8F; }
    public long stateLo() { return lo; }
    public long stateHi() { return hi; }
    public XoroshiroRandom fork(long salt) { return new XoroshiroRandom(nextLong() ^ salt); }
    private static long mix(long value) { value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L; value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL; return value ^ (value >>> 31); }
}
