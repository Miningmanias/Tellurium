// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle;

import dev.worldgennext.oracle.benchmark.SavedChunkReceipt;
import dev.worldgennext.oracle.benchmark.SavedWorldVerifier;
import dev.worldgennext.oracle.schema.CaptureIdentity;
import dev.worldgennext.oracle.schema.ChunkSnapshot;
import dev.worldgennext.oracle.schema.SnapshotField;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SavedWorldVerifierTest {
    @Test
    void receiptRequiresSaveCloseReopenAndCompleteLogicalComparison() {
        var expected = snapshot("stone");
        var reopened = snapshot("stone");
        var barrier = new SavedChunkReceipt("case-0", "flush-save-close", true,
                true, true, false, 0, 0, "close complete");
        var verified = new SavedWorldVerifier().verify(barrier, expected, reopened);
        assertTrue(verified.verified(), verified.detail());
        assertTrue(verified.comparedFields() == SnapshotField.values().length);
    }

    @Test
    void saveFailureAndReopenMismatchNeverBecomeVerified() {
        var expected = snapshot("stone");
        var mismatch = snapshot("dirt");
        var failedSave = new SavedChunkReceipt("case-1", "flush-save-close", true,
                false, true, false, 0, 0, "flush failed");
        assertFalse(new SavedWorldVerifier().verify(failedSave, expected, expected).verified());

        var barrier = new SavedChunkReceipt("case-2", "flush-save-close", true,
                true, true, false, 0, 0, "close complete");
        var result = new SavedWorldVerifier().verify(barrier, expected, mismatch);
        assertFalse(result.verified());
        assertTrue(result.mismatches() > 0);
    }

    @Test
    void invalidReceiptStateIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SavedChunkReceipt(
                "case", "policy", false, true, false, false, 0, 0, "invalid"));
        assertThrows(IllegalArgumentException.class, () -> new SavedChunkReceipt(
                "case", "policy", true, true, false, true, 1, 0, "invalid"));
    }

    private static ChunkSnapshot snapshot(String block) {
        CaptureIdentity identity = new CaptureIdentity("original", "stack", "0",
                "minecraft:overworld", 0, 0, "SAVED", "context");
        var fields = new EnumMap<SnapshotField, String>(SnapshotField.class);
        for (SnapshotField field : SnapshotField.values()) fields.put(field,
                field == SnapshotField.BLOCK_STATES ? block : field.name());
        return new ChunkSnapshot(identity, fields, Map.of("block:0", block));
    }
}
