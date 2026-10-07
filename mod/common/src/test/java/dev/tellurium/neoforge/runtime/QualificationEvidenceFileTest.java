// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationEvidenceFileTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void loadsAnExplicitEvidenceReceipt(@TempDir Path directory) throws Exception {
        Path artifact = directory.resolve("comparison-bundle.bin");
        Files.writeString(artifact, "independent comparison bundle\n", StandardCharsets.UTF_8);
        String artifactHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(artifact)));
        Path file = directory.resolve("qualification.properties");
        Files.writeString(file, "schemaVersion=1\n"
                + "contextKey=world-key\n"
                + "route=cpu_owned\n"
                + "resultAbi=" + QualifiedHookEvidence.RESULT_ABI + "\n"
                + "compilerVersion=" + QualifiedHookEvidence.CPU_COMPILER_VERSION + "\n"
                + "comparedCases=1500\n"
                + "comparedFields=15000\n"
                + "mismatches=0\n"
                + "completeCoverage=true\n"
                + "independentOracle=true\n"
                + "sourceArtifactSha256=" + artifactHash + "\n"
                + "sourceArtifactFile=" + artifact.getFileName() + "\n", StandardCharsets.UTF_8);

        var loaded = QualificationEvidenceFile.load(file);
        assertEquals(file.toAbsolutePath().normalize(), loaded.file());
        assertEquals(artifact.toAbsolutePath().normalize(), loaded.sourceArtifact());
        assertEquals("CPU_OWNED", loaded.evidence().route());
        assertEquals(1500, loaded.evidence().comparedCases());
        assertTrue(loaded.evidence().admissible());
    }

    @Test
    void rejectsMissingFieldsMalformedValuesAndSymlinks(@TempDir Path directory) throws Exception {
        Path missing = directory.resolve("missing.properties");
        Files.writeString(missing, "route=CPU_OWNED\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> QualificationEvidenceFile.load(missing));

        Path malformed = directory.resolve("malformed.properties");
        Files.writeString(malformed, "schemaVersion=1\ncontextKey=world-key\nroute=CPU_OWNED\n"
                + "resultAbi=" + QualifiedHookEvidence.RESULT_ABI + "\n"
                + "compilerVersion=" + QualifiedHookEvidence.CPU_COMPILER_VERSION + "\n"
                + "comparedCases=lots\ncomparedFields=1\nmismatches=0\n"
                + "completeCoverage=true\nindependentOracle=true\nsourceArtifactSha256=" + HASH + "\n"
                + "sourceArtifactFile=missing.bin\n",
                StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> QualificationEvidenceFile.load(malformed));

        Path missingArtifact = directory.resolve("missing-artifact.properties");
        Files.writeString(missingArtifact, "schemaVersion=1\ncontextKey=world-key\nroute=CPU_OWNED\n"
                + "resultAbi=" + QualifiedHookEvidence.RESULT_ABI + "\n"
                + "compilerVersion=" + QualifiedHookEvidence.CPU_COMPILER_VERSION + "\n"
                + "comparedCases=1500\ncomparedFields=1\nmismatches=0\n"
                + "completeCoverage=true\nindependentOracle=true\nsourceArtifactSha256=" + HASH + "\n"
                + "sourceArtifactFile=missing.bin\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> QualificationEvidenceFile.load(missingArtifact));

        Path directoryTarget = directory.resolve("directory");
        Files.createDirectory(directoryTarget);
        assertThrows(IllegalArgumentException.class, () -> QualificationEvidenceFile.load(directoryTarget));
    }

    @Test
    void rejectsAReceiptWhoseArtifactHashWasEdited(@TempDir Path directory) throws Exception {
        Path artifact = directory.resolve("comparison-bundle.bin");
        Files.writeString(artifact, "actual\n", StandardCharsets.UTF_8);
        Path file = directory.resolve("qualification.properties");
        Files.writeString(file, "schemaVersion=1\ncontextKey=world-key\nroute=CPU_OWNED\n"
                + "resultAbi=" + QualifiedHookEvidence.RESULT_ABI + "\n"
                + "compilerVersion=" + QualifiedHookEvidence.CPU_COMPILER_VERSION + "\n"
                + "comparedCases=1500\ncomparedFields=1\nmismatches=0\n"
                + "completeCoverage=true\nindependentOracle=true\nsourceArtifactSha256=" + HASH + "\n"
                + "sourceArtifactFile=" + artifact.getFileName() + "\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> QualificationEvidenceFile.load(file));
    }

    @Test
    void writesAStableReceiptAndReloadsThroughTheAdmissionShape(@TempDir Path directory) throws Exception {
        Path artifact = directory.resolve("comparison-bundle.bin");
        Files.writeString(artifact, "generated comparison bundle\n", StandardCharsets.UTF_8);
        String artifactHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(artifact)));
        var evidence = new QualifiedHookEvidence("world-key", "CPU_OWNED", 1500, 15000,
                0, true, true, artifactHash, QualifiedHookEvidence.RESULT_ABI,
                QualifiedHookEvidence.CPU_COMPILER_VERSION);
        Path first = directory.resolve("qualification.properties");
        Path second = directory.resolve("qualification-copy.properties");

        var loaded = QualificationEvidenceFile.write(first, evidence, artifact);
        QualificationEvidenceFile.write(second, evidence, artifact);

        assertEquals(evidence, loaded.evidence());
        assertEquals(artifact.toAbsolutePath().normalize(), loaded.sourceArtifact());
        assertEquals(Files.readString(first), Files.readString(second));
        assertTrue(Files.readString(first).contains("schemaVersion=1\n"));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationEvidenceFile.write(first, evidence, artifact));
    }

    @Test
    void writerRejectsEditedSource(@TempDir Path directory) throws Exception {
        Path artifact = directory.resolve("comparison-bundle.bin");
        Files.writeString(artifact, "actual\n", StandardCharsets.UTF_8);
        String artifactHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(artifact)));
        var evidence = new QualifiedHookEvidence("world-key", "CPU_OWNED", 1500, 1,
                0, true, true, artifactHash, QualifiedHookEvidence.RESULT_ABI,
                QualifiedHookEvidence.CPU_COMPILER_VERSION);
        Files.writeString(artifact, "edited\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> QualificationEvidenceFile.write(
                directory.resolve("qualification.properties"), evidence, artifact));
    }

    @Test
    void loaderRejectsAnArtifactOutsideTheReceiptDirectory(@TempDir Path directory) throws Exception {
        Path sourceDirectory = directory.resolve("source");
        Files.createDirectory(sourceDirectory);
        Path artifact = sourceDirectory.resolve("comparison-bundle.bin");
        Files.writeString(artifact, "actual\n", StandardCharsets.UTF_8);
        String artifactHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(artifact)));
        Path receiptDirectory = directory.resolve("receipt");
        Files.createDirectory(receiptDirectory);
        Path file = receiptDirectory.resolve("qualification.properties");
        Files.writeString(file, "schemaVersion=1\ncontextKey=world-key\nroute=CPU_OWNED\n"
                + "resultAbi=" + QualifiedHookEvidence.RESULT_ABI + "\n"
                + "compilerVersion=" + QualifiedHookEvidence.CPU_COMPILER_VERSION + "\n"
                + "comparedCases=1500\ncomparedFields=1\nmismatches=0\n"
                + "completeCoverage=true\nindependentOracle=true\nsourceArtifactSha256=" + artifactHash + "\n"
                + "sourceArtifactFile=" + artifact.toAbsolutePath() + "\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> QualificationEvidenceFile.load(file));
    }
}
