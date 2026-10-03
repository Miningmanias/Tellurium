// SPDX-License-Identifier: MIT
package dev.worldgennext.material.chunk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Canonical endpoint metadata carried alongside the dense result.
 *
 * <p>The material codec intentionally does not depend on the oracle module or
 * on Minecraft NBT classes.  Metadata is therefore transported as named,
 * canonical logical fields.  The loader owns the conversion from game data;
 * the codec only enforces immutability, deterministic ordering and bounded
 * field identity.  Unknown field names remain transportable so an ABI reader
 * does not silently discard a newer producer's data.</p>
 */
public final class ChunkMetadataPayload {
    private final Map<String, String> fields;

    public ChunkMetadataPayload(Map<String, String> fields) {
        if (fields == null) throw new IllegalArgumentException("Metadata fields are required");
        var sorted = new TreeMap<String, String>();
        fields.forEach((name, value) -> {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Metadata field name is blank");
            if (value == null) throw new IllegalArgumentException("Metadata field is null: " + name);
            sorted.put(name, value);
        });
        this.fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    public static ChunkMetadataPayload empty() { return new ChunkMetadataPayload(Map.of()); }

    public Map<String, String> fields() { return fields; }

    public String field(String name) {
        Objects.requireNonNull(name, "name");
        return fields.get(name);
    }

    @Override public boolean equals(Object other) {
        return other instanceof ChunkMetadataPayload that && fields.equals(that.fields);
    }

    @Override public int hashCode() { return fields.hashCode(); }

    @Override public String toString() { return fields.toString(); }
}
