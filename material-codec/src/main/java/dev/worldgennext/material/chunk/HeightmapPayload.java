// SPDX-License-Identifier: MIT
package dev.worldgennext.material.chunk;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class HeightmapPayload {
    public static final int COLUMN_COUNT = 256;
    public static final String DEFAULT_KIND = "WORLD_SURFACE";
    private final Map<String, int[]> values;

    public HeightmapPayload(int[] values) {
        this(Map.of(DEFAULT_KIND, values));
    }

    /**
     * Carries every heightmap produced by the endpoint.  The legacy single-array
     * constructor remains as the explicit WORLD_SURFACE shorthand; named maps
     * are sorted for deterministic ABI/checksum output and every array is
     * copied on both ingress and egress.
     */
    public HeightmapPayload(Map<String, int[]> values) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("At least one heightmap is required");
        var copy = new TreeMap<String, int[]>();
        values.forEach((kind, columns) -> {
            if (kind == null || kind.isBlank()) throw new IllegalArgumentException("Heightmap kind is required");
            if (columns == null || columns.length != COLUMN_COUNT) throw new IllegalArgumentException("Expected 256 heightmap columns for " + kind);
            copy.put(kind, columns.clone());
        });
        this.values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(copy));
    }

    public int value(int x, int z) {
        if (x < 0 || x >= 16 || z < 0 || z >= 16) throw new IndexOutOfBoundsException();
        return values()[z * 16 + x];
    }

    /** Backwards-compatible WORLD_SURFACE view (or the deterministic first map). */
    public int[] values() { return values(DEFAULT_KIND); }

    public int[] values(String kind) {
        if (xOutOfBounds(kind)) throw new IllegalArgumentException("Unknown heightmap kind: " + kind);
        int[] result = values.get(kind);
        if (result == null) {
            if (!DEFAULT_KIND.equals(kind)) throw new IllegalArgumentException("Unknown heightmap kind: " + kind);
            result = values.values().iterator().next();
        }
        return result.clone();
    }

    /** Returns a defensive deep copy suitable for inspection and serialization. */
    public Map<String, int[]> maps() {
        var copy = new LinkedHashMap<String, int[]>();
        values.forEach((kind, columns) -> copy.put(kind, columns.clone()));
        return java.util.Collections.unmodifiableMap(copy);
    }

    private boolean xOutOfBounds(String kind) { return kind == null || kind.isBlank(); }

    @Override public boolean equals(Object other) {
        if (!(other instanceof HeightmapPayload that) || !values.keySet().equals(that.values.keySet())) return false;
        for (String kind : values.keySet()) if (!Arrays.equals(values.get(kind), that.values.get(kind))) return false;
        return true;
    }

    @Override public int hashCode() {
        int result = 1;
        for (var entry : values.entrySet()) result = 31 * result + Objects.hash(entry.getKey(), Arrays.hashCode(entry.getValue()));
        return result;
    }
}
