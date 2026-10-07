// SPDX-License-Identifier: MIT
package dev.tellurium.oracle.minecraft;

import dev.tellurium.oracle.schema.ChunkSnapshot;
import dev.tellurium.oracle.schema.CorpusManifest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CorpusValidator {
    public record Validation(boolean valid, List<String> errors) {
        public Validation {
            errors = List.copyOf(errors == null ? List.of() : errors);
        }
    }

    public Validation validate(CorpusManifest manifest, List<ChunkSnapshot> snapshots) {
        var errors = new ArrayList<String>();
        if (manifest == null) {
            errors.add("missing manifest");
            return new Validation(false, errors);
        }

        Set<String> expected = new LinkedHashSet<>();
        for (var capture : manifest.captures()) {
            if (capture == null) {
                errors.add("manifest contains a null capture identity");
            } else if (!expected.add(capture.key())) {
                // CorpusManifest normally rejects this at construction time;
                // retaining the check here keeps validation fail-closed if a
                // future deserializer bypasses the record constructor.
                errors.add("duplicate manifest capture: " + capture.key());
            }
        }
        if (expected.isEmpty()) errors.add("manifest contains no captures");

        Set<String> actual = new LinkedHashSet<>();
        if (snapshots == null) {
            errors.add("missing snapshots");
        } else {
            if (snapshots.isEmpty()) errors.add("empty snapshots");
            for (int index = 0; index < snapshots.size(); index++) {
                ChunkSnapshot snapshot = snapshots.get(index);
                if (snapshot == null) {
                    errors.add("missing snapshot at index " + index);
                    continue;
                }
                String key = snapshot.identity().key();
                if (!actual.add(key)) errors.add("duplicate snapshot: " + key);
                if (!expected.contains(key)) errors.add("unknown snapshot: " + key);
                for (var field : manifest.requiredFields()) {
                    if (snapshot.field(field) == null) errors.add("missing field " + field + " in " + key);
                }
            }
        }
        expected.stream().filter(key -> !actual.contains(key))
                .forEach(key -> errors.add("missing snapshot: " + key));
        return new Validation(errors.isEmpty(), errors);
    }
}
