// SPDX-License-Identifier: MIT
package dev.tellurium.engine.worldgen;

/**
 * Checked per-request estimates for every resource class held by the
 * coordinator.  These are reserved capacities, not measurements of JVM
 * object overhead.
 */
public record ResourceEstimate(long snapshotBytes, long stagingBytes, long outputBytes,
                               long readbackBytes, long applicationBytes) {
    public ResourceEstimate {
        if (snapshotBytes < 0 || stagingBytes < 0 || outputBytes < 0
                || readbackBytes < 0 || applicationBytes < 0) {
            throw new IllegalArgumentException("Resource estimates cannot be negative");
        }
        totalBytes(snapshotBytes, stagingBytes, outputBytes, readbackBytes, applicationBytes);
    }

    /**
     * Compatibility mapping for the original single-estimate request
     * constructor.  It preserves its conservative per-resource reservation
     * while making the five leases explicit.
     */
    public static ResourceEstimate uniform(long bytes) {
        if (bytes <= 0) throw new IllegalArgumentException("Positive resource estimate required");
        return new ResourceEstimate(bytes, bytes, bytes, bytes, bytes);
    }

    public long totalBytes() {
        return totalBytes(snapshotBytes, stagingBytes, outputBytes, readbackBytes, applicationBytes);
    }

    private static long totalBytes(long snapshot, long staging, long output,
                                   long readback, long application) {
        try {
            return Math.addExact(
                    Math.addExact(Math.addExact(Math.addExact(snapshot, staging), output), readback),
                    application);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Resource estimate total overflows", overflow);
        }
    }
}
