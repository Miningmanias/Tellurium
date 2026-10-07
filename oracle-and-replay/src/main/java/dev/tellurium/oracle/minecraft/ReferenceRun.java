// SPDX-License-Identifier: MIT
package dev.tellurium.oracle.minecraft;

import dev.tellurium.oracle.schema.ChunkSnapshot;
import java.util.List;

public record ReferenceRun(String worldDirectory, RunArtifactManifest artifact, List<ChunkSnapshot> snapshots) {
    public ReferenceRun { if (worldDirectory == null || worldDirectory.isBlank() || artifact == null) throw new IllegalArgumentException("Reference run identity required"); snapshots = List.copyOf(snapshots == null ? List.of() : snapshots); }
}
