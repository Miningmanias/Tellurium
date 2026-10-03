// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.jvm.worldgen;

import dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot;

/** Position-derived random values without mutable shared state. */
public final class PositionalRandom {
    private final long seed;
    public PositionalRandom(long seed) { this.seed = seed; }
    public long seed() { return seed; }
    public long at(int x, int y, int z, long salt) {
        long value = seed ^ salt;
        value += (long) x * 0x9E3779B97F4A7C15L;
        value += (long) y * 0xC2B2AE3D27D4EB4FL;
        value += (long) z * 0x165667B19E3779F9L;
        value ^= Long.rotateLeft(value, 23);
        value *= 0x9E3779B97F4A7C15L;
        return mix(value);
    }
    public double unit(int x, int y, int z, long salt) { return (at(x, y, z, salt) >>> 11) * 0x1.0p-53; }
    public static FactoryRandom at(PositionalRandomFactorySnapshot factory, int x, int y, int z) {
        if (factory == null) throw new IllegalArgumentException("Positional random factory is missing");
        long positionSeed = minecraftPositionSeed(x, y, z);
        return factory.algorithm() == PositionalRandomFactorySnapshot.Algorithm.LEGACY
                ? new FactoryRandom(new LegacyRandom(positionSeed ^ factory.seed()), null)
                : new FactoryRandom(null, new XoroshiroRandom(positionSeed ^ factory.seedLo(), factory.seedHi()));
    }
    private static long minecraftPositionSeed(int x, int y, int z) {
        long value = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        return value * value * 42317861L + value * 11L >> 16;
    }
    public static final class FactoryRandom {
        private final LegacyRandom legacy;
        private final XoroshiroRandom xoroshiro;
        private FactoryRandom(LegacyRandom legacy, XoroshiroRandom xoroshiro) { this.legacy = legacy; this.xoroshiro = xoroshiro; }
        public int nextInt(int bound) { return legacy != null ? legacy.nextInt(bound) : xoroshiro.nextInt(bound); }
        public float nextFloat() { return legacy != null ? legacy.nextFloat() : xoroshiro.nextFloat(); }
    }
    private static long mix(long value) { value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L; value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL; return value ^ (value >>> 31); }
}
