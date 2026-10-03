// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical logical chunk snapshot. The comparator sees fields directly, never only a checksum. */
public final class ChunkSnapshot {
    private final CaptureIdentity identity;
    private final Map<SnapshotField, String> fields;
    private final Map<String, String> values;
    public ChunkSnapshot(CaptureIdentity identity, Map<SnapshotField, String> fields, Map<String, String> values) {
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        var fieldCopy = new java.util.EnumMap<SnapshotField, String>(SnapshotField.class); if (fields != null) fields.forEach((key, value) -> { if (key == null || value == null) throw new IllegalArgumentException("Invalid snapshot field"); fieldCopy.put(key, value); });
        this.fields = Collections.unmodifiableMap(fieldCopy); var valueCopy = new LinkedHashMap<String, String>(); if (values != null) values.forEach((key, value) -> { if (key == null || key.isBlank() || value == null) throw new IllegalArgumentException("Invalid snapshot value"); valueCopy.put(key, value); }); this.values = Collections.unmodifiableMap(valueCopy);
    }
    public CaptureIdentity identity() { return identity; }
    public Map<SnapshotField, String> fields() { return fields; }
    public Map<String, String> values() { return values; }
    public String field(SnapshotField field) { return fields.get(field); }
    public String value(String key) { return values.get(key); }
    public ChunkSnapshot withField(SnapshotField field, String value) { var copy = new java.util.EnumMap<SnapshotField, String>(fields); copy.put(field, value); return new ChunkSnapshot(identity, copy, values); }
    public ChunkSnapshot withValue(String key, String value) { var copy = new LinkedHashMap<>(values); copy.put(key, value); return new ChunkSnapshot(identity, fields, copy); }
}
