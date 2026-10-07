// SPDX-License-Identifier: MIT
package dev.tellurium.engine.worldgen;

import java.util.Objects;

/**
 * Immutable operator view of one coordinator boundary.
 *
 * <p>The counters deliberately remain nested rather than being collapsed into
 * a single "completed" number.  A request can be shared by subscribers, can
 * be rejected before backend execution, and can retain a terminal record for
 * same-generation deduplication.  Exposing those dimensions together makes a
 * status report useful without turning it into qualification evidence.</p>
 */
public record CoordinatorSnapshot(
        DrainController.State lifecycle,
        int queueDepth,
        int queueCapacity,
        int activeRecords,
        int retainedTerminalRecords,
        long reservedBytes,
        long resourceBudgetBytes,
        WorkCounters.Snapshot counters) {

    public CoordinatorSnapshot {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(counters, "counters");
        if (queueDepth < 0 || queueCapacity <= 0 || queueDepth > queueCapacity) {
            throw new IllegalArgumentException("Invalid coordinator queue snapshot");
        }
        if (activeRecords < 0 || retainedTerminalRecords < 0
                || reservedBytes < 0 || resourceBudgetBytes < 0
                || reservedBytes > resourceBudgetBytes) {
            throw new IllegalArgumentException("Invalid coordinator resource snapshot");
        }
    }

    public boolean admitting() {
        return lifecycle == DrainController.State.RUNNING;
    }
}
