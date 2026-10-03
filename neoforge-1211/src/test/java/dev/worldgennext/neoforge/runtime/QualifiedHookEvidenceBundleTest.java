// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualifiedHookEvidenceBundleTest {
    @Test
    void aggregatesExactContextsWithoutLoweringTheGlobalCaseBar() {
        var bundle = new QualifiedHookEvidenceBundle(List.of(
                evidence("seed-a", 750), evidence("seed-b", 750)));

        assertTrue(bundle.admissible());
        assertEquals(2, bundle.contextKeys().size());
        assertEquals(1_500, bundle.comparedCases());
        assertEquals("seed-a", bundle.evidenceFor("seed-a").contextKey());
        bundle.requireAdmissible();
    }

    @Test
    void rejectsDuplicateOrIncompatibleContexts() {
        assertThrows(IllegalArgumentException.class, () -> new QualifiedHookEvidenceBundle(List.of(
                evidence("same", 750), evidence("same", 750))));
        assertThrows(IllegalArgumentException.class, () -> new QualifiedHookEvidenceBundle(List.of(
                evidence("cpu", 750), new QualifiedHookEvidence("gpu", "GPU_IEEE_BITS", 750, 1,
                        0, true, true, hash("gpu"), QualifiedHookEvidence.RESULT_ABI,
                        QualifiedHookEvidence.GPU_COMPILER_VERSION))));
        var partial = new QualifiedHookEvidenceBundle(List.of(
                new QualifiedHookEvidence("partial", "CPU_OWNED", 1_500, 1,
                        1, true, true, hash("partial"), QualifiedHookEvidence.RESULT_ABI,
                        QualifiedHookEvidence.CPU_COMPILER_VERSION)));
        assertTrue(!partial.admissible());
        assertTrue(partial.rejectionReason().contains("mismatches"));
    }

    @Test
    void bundleFileRoundTripsAndChecksEachArtifact(@TempDir Path directory) throws Exception {
        Path first = directory.resolve("first.bin");
        Path second = directory.resolve("second.bin");
        Files.writeString(first, "first\n", StandardCharsets.UTF_8);
        Files.writeString(second, "second\n", StandardCharsets.UTF_8);
        var firstEvidence = evidence("context-a", 900, first);
        var secondEvidence = evidence("context-b", 600, second);
        var bundle = new QualifiedHookEvidenceBundle(List.of(firstEvidence, secondEvidence));
        Path receipt = directory.resolve("qualification-bundle.properties");

        var written = QualificationEvidenceBundleFile.write(receipt, bundle, Map.of(
                firstEvidence.contextKey(), first, secondEvidence.contextKey(), second));
        var loaded = QualificationEvidenceBundleFile.load(receipt);

        assertEquals(bundle.entries(), written.bundle().entries());
        assertEquals(bundle.entries(), loaded.bundle().entries());
        assertEquals(first.toAbsolutePath().normalize(), loaded.sourceArtifacts().get("context-a"));
        assertTrue(Files.readString(receipt).contains("schemaVersion=2\n"));
        assertThrows(IllegalArgumentException.class, () -> QualificationEvidenceBundleFile.write(
                receipt, bundle, Map.of(firstEvidence.contextKey(), first, secondEvidence.contextKey(), second)));

        Files.writeString(second, "edited\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> QualificationEvidenceBundleFile.load(receipt));
    }

    private static QualifiedHookEvidence evidence(String context, long cases) {
        return new QualifiedHookEvidence(context, "CPU_OWNED", cases, cases * 10,
                0, true, true, hash(context), QualifiedHookEvidence.RESULT_ABI,
                QualifiedHookEvidence.CPU_COMPILER_VERSION);
    }

    private static QualifiedHookEvidence evidence(String context, long cases, Path artifact) throws Exception {
        return new QualifiedHookEvidence(context, "CPU_OWNED", cases, cases * 10,
                0, true, true, sha256(artifact), QualifiedHookEvidence.RESULT_ABI,
                QualifiedHookEvidence.CPU_COMPILER_VERSION);
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String sha256(Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file)));
    }
}
