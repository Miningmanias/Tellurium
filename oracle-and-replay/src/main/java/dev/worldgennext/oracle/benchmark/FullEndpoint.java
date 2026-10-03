// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle.benchmark;

public record FullEndpoint(long compared, long fullCompleted, long wallNanos) {
    public FullEndpoint {
        if (compared < 0 || fullCompleted < 0 || wallNanos < 0) {
            throw new IllegalArgumentException("Negative endpoint metric");
        }
    }

    public boolean complete() { return compared > 0 && fullCompleted > 0 && wallNanos > 0; }
}
