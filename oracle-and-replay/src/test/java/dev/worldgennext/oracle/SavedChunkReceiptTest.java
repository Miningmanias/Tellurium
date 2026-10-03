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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SavedChunkReceiptTest {
    @Test
    void pendingReceiptHasNoVerificationEvidence() {
        var pending = SavedChunkReceipt.pending("case-0", "flush-save-close");

        assertAll(
                () -> assertEquals("case-0", pending.requestKey()),
                () -> assertEquals("flush-save-close", pending.savePolicy()),
                () -> assertFalse(pending.saveRequested()),
                () -> assertFalse(pending.saveCompleted()),
                () -> assertFalse(pending.processClosed()),
                () -> assertFalse(pending.reopened()),
                () -> assertEquals(0, pending.comparedFields()),
                () -> assertEquals(0, pending.mismatches()),
                () -> assertFalse(pending.verified())
        );
    }

    @Test
    void verificationRequiresCompleteLifecyclePositiveComparisonAndZeroMismatches() {
        assertFalse(new SavedChunkReceipt("case-requested", "policy", true,
                false, false, false, 0, 0, "save pending").verified());
        assertFalse(new SavedChunkReceipt("case-complete", "policy", true,
                true, true, false, 0, 0, "no reopen comparison").verified());
        assertFalse(new SavedChunkReceipt("case-mismatch", "policy", true,
                true, true, true, 4, 1, "logical mismatch").verified());

        var verified = new SavedChunkReceipt("case-verified", "policy", true,
                true, true, true, 4, 0, "logical state verified");
        assertTrue(verified.verified());
    }

    @Test
    void invalidLifecycleAndComparisonCombinationsAreRejected() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new SavedChunkReceipt(
                        "case", "policy", false, true, false, false, 0, 0, "invalid")),
                () -> assertThrows(IllegalArgumentException.class, () -> new SavedChunkReceipt(
                        "case", "policy", true, true, false, true, 0, 0, "invalid")),
                () -> assertThrows(IllegalArgumentException.class, () -> new SavedChunkReceipt(
                        "case", "policy", true, true, true, false, 1, 0, "invalid")),
                () -> assertThrows(IllegalArgumentException.class, () -> new SavedChunkReceipt(
                        "case", "policy", true, true, true, true, 1, 2, "invalid"))
        );
    }

    @Test
    void withComparisonCannotVerifyZeroComparedFieldsButCanCompleteReceipt() {
        var barrier = new SavedChunkReceipt("case-comparison", "policy", true,
                true, true, false, 0, 0, "close complete");

        var noFields = barrier.withComparison(true, 0, 0, "fresh snapshot missing");
        assertFalse(noFields.verified());
        assertEquals(0, noFields.comparedFields());
        assertEquals(0, noFields.mismatches());

        var complete = noFields.withComparison(true, 4, 0, "fresh-process logical state verified");
        assertTrue(complete.verified());
        assertFalse(barrier.verified(), "withComparison must preserve receipt immutability");
    }

    @Test
    void verifierDoesNotTreatMissingComparisonAsZeroMismatchVerification() {
        var barrier = completeBarrier("case-missing");
        var result = new SavedWorldVerifier().verify(barrier, null, snapshot("stone"));

        assertFalse(result.verified());
        assertEquals(0, result.comparedFields());
        assertEquals(0, result.mismatches());
        assertFalse(result.reopened());
        assertTrue(result.detail().contains("snapshot is missing"));
    }

    @Test
    void verifierRequiresPositiveComparisonForEqualFreshSnapshot() {
        var result = new SavedWorldVerifier().verify(
                completeBarrier("case-equal"), snapshot("stone"), snapshot("stone"));

        assertTrue(result.verified(), result.detail());
        assertTrue(result.comparedFields() > 0);
        assertEquals(0, result.mismatches());
    }

    @Test
    void verifierRejectsEqualFieldsFromASecondEndpointOrIdentity() {
        var barrier = completeBarrier("case-identity");
        var expected = snapshot("stone");
        var differentSource = snapshot("stone", "other-process", "SAVED");
        assertFalse(new SavedWorldVerifier().verify(barrier, expected, differentSource).verified());
        assertTrue(new SavedWorldVerifier().verify(barrier, expected,
                snapshot("stone", "other-process", "NOISE")).detail().contains("SAVED endpoint"));
    }

    private static SavedChunkReceipt completeBarrier(String requestKey) {
        return new SavedChunkReceipt(requestKey, "flush-save-close", true,
                true, true, false, 0, 0, "close complete");
    }

    private static ChunkSnapshot snapshot(String block) {
        return snapshot(block, "original", "SAVED");
    }

    private static ChunkSnapshot snapshot(String block, String source, String endpoint) {
        CaptureIdentity identity = new CaptureIdentity(source, "stack", "0",
                "minecraft:overworld", 0, 0, endpoint, "context");
        var fields = new EnumMap<SnapshotField, String>(SnapshotField.class);
        for (SnapshotField field : SnapshotField.values()) {
            fields.put(field, field == SnapshotField.BLOCK_STATES ? block : field.name());
        }
        return new ChunkSnapshot(identity, fields, Map.of("block:0", block));
    }
}
