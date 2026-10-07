// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import dev.tellurium.neoforge.config.TelluriumConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualifiedHookAdmissionTest {
    @Test
    void generatedReceiptLoadsAndMatchesTheLiveProviderIdentity(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("candidate-corpus.bin");
        Files.writeString(source, "generated candidate corpus\n", StandardCharsets.UTF_8);
        String sourceHash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(source)));
        var evidence = new QualifiedHookEvidence("world-key", "CPU_OWNED", 1500, 15000,
                0, true, true, sourceHash, QualifiedHookEvidence.RESULT_ABI,
                QualifiedHookEvidence.CPU_COMPILER_VERSION);
        var loaded = QualificationEvidenceFile.write(
                directory.resolve("qualification.properties"), evidence, source);
        var provider = new QualifiedHookAdmission.ProviderIdentity("CPU_OWNED",
                QualifiedHookEvidence.RESULT_ABI, QualifiedHookEvidence.CPU_COMPILER_VERSION);

        assertDoesNotThrow(() -> QualifiedHookAdmission.validate(
                loaded.evidence(), provider, enabledConfig()));

        var staleEvidence = new QualifiedHookEvidence("world-key", "CPU_OWNED", 1500, 15000,
                0, true, true, sourceHash, QualifiedHookEvidence.RESULT_ABI,
                "tellurium-cpu-live-v0.1");
        var failure = assertThrows(IllegalArgumentException.class, () ->
                QualifiedHookAdmission.validate(staleEvidence, provider, enabledConfig()));
        assertTrue(failure.getMessage().contains("compiler version"));
    }

    private static TelluriumConfig enabledConfig() {
        return new TelluriumConfig(TelluriumConfig.Mode.AUTO_SUPPORTED,
                1, 1, 1, Duration.ofSeconds(1), true);
    }
}
