// SPDX-License-Identifier: MIT
package dev.tellurium.oracle.benchmark;

public record SavedEndpoint(long compared, long saveBarrierCompleted, long reopenedVerified, long wallNanos) {
    public SavedEndpoint {
        if (compared < 0 || saveBarrierCompleted < 0 || reopenedVerified < 0 || wallNanos < 0) {
            throw new IllegalArgumentException("Negative endpoint metric");
        }
    }

    public boolean complete() {
        return compared > 0 && saveBarrierCompleted > 0 && reopenedVerified > 0 && wallNanos > 0;
    }
}
