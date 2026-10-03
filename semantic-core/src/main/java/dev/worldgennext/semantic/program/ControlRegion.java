// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic.program;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;
import java.util.TreeMap;

/** A bounded ordered region of the typed program. */
public record ControlRegion(String id, EvaluationDomain domain, List<ProgramNode> nodes,
                            boolean lazy, Map<String, String> effects) {
    public ControlRegion {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Region id is required");
        Objects.requireNonNull(domain, "domain");
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
        if (effects == null || effects.isEmpty()) {
            effects = Map.of();
        } else {
            var sorted = new TreeMap<String, String>();
            effects.forEach((key, value) -> {
                if (key == null || key.isBlank()) throw new IllegalArgumentException("Effect key is required");
                sorted.put(key, Objects.requireNonNull(value, "effect value"));
            });
            effects = Collections.unmodifiableMap(sorted);
        }
    }

    public boolean hasSideEffects() { return !effects.isEmpty(); }
}
