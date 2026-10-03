// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.jvm.worldgen;

/** Bounded deterministic End-island approximation; integer coordinate behavior is explicit. */
public final class EndIslandEvaluator {
    public double sample(long seed, int x, int z) {
        if (Math.abs((long) x) > 1_000_000_000L || Math.abs((long) z) > 1_000_000_000L) return -1.0;
        double radius = Math.hypot((double) x, (double) z) / 1024.0;
        double island = Math.max(0.0, 1.0 - radius);
        double noise = new NoiseEvaluator().sample(seed ^ 0xC0FFEE, x / 64.0, 0, z / 64.0);
        return island * 2.0 + noise * 0.25 - 0.5;
    }
}
