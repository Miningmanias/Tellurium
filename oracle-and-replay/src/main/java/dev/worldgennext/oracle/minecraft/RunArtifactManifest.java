// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle.minecraft;

import java.util.List;

public record RunArtifactManifest(String sourceHash, String jarHash, String dependencyHash, String command,
                                  List<String> outputs) {
    public RunArtifactManifest { if (sourceHash == null || sourceHash.isBlank() || jarHash == null || jarHash.isBlank() || dependencyHash == null || dependencyHash.isBlank() || command == null || command.isBlank()) throw new IllegalArgumentException("Artifact identity required"); outputs = List.copyOf(outputs == null ? List.of() : outputs); }
}
