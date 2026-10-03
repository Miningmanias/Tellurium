// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle.benchmark;

import dev.worldgennext.oracle.minecraft.ChunkSnapshotComparator;
import dev.worldgennext.oracle.schema.ChunkSnapshot;

import java.util.Objects;

/**
 * Completes a saved receipt only after the explicit close barrier and a
 * field-by-field comparison of a fresh-process snapshot.
 */
public final class SavedWorldVerifier {
    public SavedChunkReceipt verify(SavedChunkReceipt barrier, ChunkSnapshot expected, ChunkSnapshot reopened) {
        Objects.requireNonNull(barrier, "barrier");
        if (!barrier.saveRequested() || !barrier.saveCompleted() || !barrier.processClosed()) {
            return barrier.withComparison(false, 0, 0,
                    "save request/completion/close barrier is incomplete");
        }
        if (expected == null || reopened == null) {
            return barrier.withComparison(false, 0, 0, "fresh-process snapshot is missing");
        }
        if (!"SAVED".equals(expected.identity().endpoint()) || !"SAVED".equals(reopened.identity().endpoint())) {
            return barrier.withComparison(false, 0, 0,
                    "saved verification requires SAVED endpoint identities");
        }
        if (!expected.identity().key().equals(reopened.identity().key())) {
            return barrier.withComparison(false, 0, 0,
                    "fresh-process snapshot identity does not match the requested SAVED identity");
        }
        ChunkSnapshotComparator.Comparison comparison = new ReopenVerifier().compare(expected, reopened);
        if (!comparison.passed()) {
            return barrier.withComparison(true, comparison.comparedFields(), comparison.differences().size(),
                    "fresh-process logical comparison failed: " + comparison.reason());
        }
        return barrier.withComparison(true, comparison.comparedFields(), comparison.differences().size(),
                "fresh-process logical state verified");
    }
}
