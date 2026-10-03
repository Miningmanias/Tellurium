// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle.benchmark;

public record NoiseEndpoint(long compared, long committed, long wallNanos) {
    public NoiseEndpoint {
        if (compared < 0 || committed < 0 || wallNanos < 0) {
            throw new IllegalArgumentException("Negative endpoint metric");
        }
    }

    public boolean complete() { return compared > 0 && committed > 0 && wallNanos > 0; }
}
