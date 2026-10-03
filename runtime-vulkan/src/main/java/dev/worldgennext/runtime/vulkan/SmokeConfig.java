// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan;

public record SmokeConfig(int sampleCount, long fenceTimeoutNanos) {
    public SmokeConfig {
        if (sampleCount < 1 || sampleCount > 4096) throw new IllegalArgumentException("sampleCount must be 1..4096");
        if (fenceTimeoutNanos < 1 || fenceTimeoutNanos > 30_000_000_000L) throw new IllegalArgumentException("Fence timeout must be positive and at most 30 seconds");
    }
    public static SmokeConfig of(int samples) { return new SmokeConfig(samples, 10_000_000_000L); }
    public int workgroups() { return (sampleCount + 63) / 64; }
    public long pointBytes() { return Math.multiplyExact(sampleCount, 16L); }
    public long valueBytes() { return Math.multiplyExact(sampleCount, 8L); }
}
