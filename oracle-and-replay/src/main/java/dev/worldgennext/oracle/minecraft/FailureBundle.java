// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle.minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public record FailureBundle(String caseKey, String reason, java.util.List<ChunkSnapshotComparator.Difference> differences) {
    public FailureBundle { if (caseKey == null || caseKey.isBlank() || reason == null || reason.isBlank()) throw new IllegalArgumentException("Failure bundle identity required"); differences = java.util.List.copyOf(differences == null ? java.util.List.of() : differences); }
    public void write(Path directory) throws java.io.IOException { Files.createDirectories(directory); Path file = directory.resolve(caseKey.replaceAll("[^A-Za-z0-9._-]", "_") + ".failure.txt"); var out = new StringBuilder(reason).append('\n'); differences.forEach(diff -> out.append(diff.key()).append(" expected=").append(diff.expected()).append(" actual=").append(diff.actual()).append('\n')); Files.writeString(file, out.toString(), StandardCharsets.UTF_8); }
}
