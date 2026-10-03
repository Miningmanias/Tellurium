// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic.snapshot;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Canonical, loader-free block-state identity. */
public record BlockStateDescriptor(String name, Map<String, String> properties) {
    public BlockStateDescriptor {
        if (name == null || name.isBlank() || !name.contains(":")) throw new IllegalArgumentException("Canonical state name required");
        var sorted = new TreeMap<String, String>();
        if (properties != null) properties.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) throw new IllegalArgumentException("Invalid state property");
            sorted.put(key, value);
        });
        properties = Collections.unmodifiableMap(sorted);
    }
    public String canonical() {
        StringBuilder result = new StringBuilder(name);
        if (!properties.isEmpty()) {
            result.append('[');
            boolean first = true;
            for (var entry : properties.entrySet()) {
                if (!first) result.append(',');
                result.append(entry.getKey()).append('=').append(entry.getValue()); first = false;
            }
            result.append(']');
        }
        return result.toString();
    }
    public static BlockStateDescriptor of(String name) { return new BlockStateDescriptor(Objects.requireNonNull(name), Map.of()); }
}
