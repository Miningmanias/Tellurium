// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle.schema;

import java.util.HashSet;
import java.util.List;

public record CorpusManifest(int schemaVersion, String corpusId, List<CaptureIdentity> captures,
                             java.util.Set<SnapshotField> requiredFields) {
    public CorpusManifest {
        if (schemaVersion <= 0 || corpusId == null || corpusId.isBlank()) throw new IllegalArgumentException("Invalid corpus manifest");
        captures = List.copyOf(captures == null ? List.of() : captures); requiredFields = java.util.Set.copyOf(requiredFields == null ? java.util.Set.of() : requiredFields);
        var keys = new HashSet<String>(); for (var capture : captures) if (!keys.add(capture.key())) throw new IllegalArgumentException("Duplicate capture identity");
        if (requiredFields.isEmpty()) throw new IllegalArgumentException("Required snapshot fields cannot be empty");
    }
}
