// SPDX-License-Identifier: MIT
package dev.tellurium.oracle.minecraft;

import dev.tellurium.oracle.schema.ChunkSnapshot;
import java.util.List;

public record CandidateRun(String worldDirectory, RunArtifactManifest artifact, List<ChunkSnapshot> snapshots) {
    public CandidateRun { if (worldDirectory == null || worldDirectory.isBlank() || artifact == null) throw new IllegalArgumentException("Candidate run identity required"); snapshots = List.copyOf(snapshots == null ? List.of() : snapshots); }
}
