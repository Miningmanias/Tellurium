// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualifiedHookEvidenceTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void onlyCompleteIndependentZeroMismatchCorpusIsAdmissible() {
        var evidence = new QualifiedHookEvidence("captured-world-key", "CPU_OWNED",
                QualifiedHookEvidence.MINIMUM_CAPTURE_CASES, 10_000, 0,
                true, true, HASH.toUpperCase());

        assertTrue(evidence.admissible());
        assertEquals(HASH, evidence.sourceArtifactSha256());
        assertEquals(QualifiedHookEvidence.RESULT_ABI, evidence.resultAbi());
        assertEquals(QualifiedHookEvidence.CPU_COMPILER_VERSION, evidence.compilerVersion());
        evidence.requireAdmissible();
    }

    @Test
    void partialOrNonIndependentEvidenceExplainsWhyRegistrationMustFail() {
        var evidence = new QualifiedHookEvidence("captured-world-key", "GPU_IEEE_BITS",
                10, 0, 0, false, false, HASH);

        assertFalse(evidence.admissible());
        assertTrue(evidence.rejectionReason().contains("compared cases"));
        assertTrue(evidence.rejectionReason().contains("no compared fields"));
        assertTrue(evidence.rejectionReason().contains("coverage"));
        assertTrue(evidence.rejectionReason().contains("independently"));
        assertThrows(IllegalArgumentException.class, evidence::requireAdmissible);
    }

    @Test
    void malformedEvidenceCannotBecomeAQualificationReceipt() {
        assertThrows(IllegalArgumentException.class, () -> new QualifiedHookEvidence(
                "context", "CPU_ORIGINAL_PLANNED", 1500, 1, 0, true, true, HASH));
        assertThrows(IllegalArgumentException.class, () -> new QualifiedHookEvidence(
                "context", "CPU_OWNED", 1500, 1, 2, true, true, HASH));
        assertThrows(IllegalArgumentException.class, () -> new QualifiedHookEvidence(
                "context", "CPU_OWNED", 1500, 1, 0, true, true, "not-a-sha256"));
        assertThrows(IllegalArgumentException.class, () -> new QualifiedHookEvidence(
                "context", "CPU_OWNED", 1500, 1, 0, true, true, HASH,
                        QualifiedHookEvidence.RESULT_ABI, "compiler\nwith-injection"));
        assertThrows(IllegalArgumentException.class, () -> new QualifiedHookEvidence(
                "context\nwith-injection", "CPU_OWNED", 1500, 1, 0, true, true, HASH));
    }
}
