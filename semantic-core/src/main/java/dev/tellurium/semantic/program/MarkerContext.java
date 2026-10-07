// SPDX-License-Identifier: MIT
package dev.tellurium.semantic.program;

import dev.tellurium.semantic.snapshot.StructureBlendSnapshot;
import java.util.Collections;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Request-owned mutable marker values. It is never shared between worldgen requests. */
public final class MarkerContext {
    /**
     * The finite horizontal domain populated by a Minecraft NoiseChunk
     * FlatCache.  Points outside this domain must evaluate the wrapped
     * function at their original coordinates instead of being silently
     * quantized into the cache.
     */
    public record FlatCacheBounds(int firstNoiseX, int firstNoiseZ, int noiseSizeXZ) {
        public FlatCacheBounds {
            if (noiseSizeXZ < 0) throw new IllegalArgumentException("FlatCache size cannot be negative");
        }

        public boolean contains(int x, int z) {
            long quartX = Math.floorDiv(x, 4);
            long quartZ = Math.floorDiv(z, 4);
            long offsetX = quartX - firstNoiseX;
            long offsetZ = quartZ - firstNoiseZ;
            return offsetX >= 0 && offsetX <= noiseSizeXZ
                    && offsetZ >= 0 && offsetZ <= noiseSizeXZ;
        }
    }

    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Map<ScopedKey, Object> scopedValues = new LinkedHashMap<>();
    /** Last-value domains such as Minecraft's Cache2D are deliberately not unbounded maps. */
    private final Map<String, LastScopedValue> lastScopedValues = new LinkedHashMap<>();
    private final Deque<Long> interpolationScopes = new ArrayDeque<>();
    private long interpolationSequence;
    private final long requestEpoch;
    private final StructureBlendSnapshot structureBlend;
    private final FlatCacheBounds flatCacheBounds;

    public MarkerContext(long requestEpoch) {
        this(requestEpoch, StructureBlendSnapshot.empty());
    }

    /**
     * Creates a request-owned marker context with immutable structure/blend
     * inputs.  The default constructor remains the empty Blender identity
     * route used by synthetic programs.
     */
    public MarkerContext(long requestEpoch, StructureBlendSnapshot structureBlend) {
        this(requestEpoch, structureBlend, null);
    }

    /**
     * Creates a request context with the optional finite FlatCache domain of
     * the owning NoiseChunk.  A null domain retains the compatibility
     * behavior used by pure callers that do not have a loader-owned range.
     */
    public MarkerContext(long requestEpoch, StructureBlendSnapshot structureBlend,
                         FlatCacheBounds flatCacheBounds) {
        if (requestEpoch < 0) throw new IllegalArgumentException("Negative request epoch");
        this.requestEpoch = requestEpoch;
        this.structureBlend = Objects.requireNonNull(structureBlend, "structureBlend");
        this.flatCacheBounds = flatCacheBounds;
    }

    public long requestEpoch() { return requestEpoch; }
    public StructureBlendSnapshot structureBlend() { return structureBlend; }
    public FlatCacheBounds flatCacheBounds() { return flatCacheBounds; }

    public synchronized boolean contains(String marker) {
        return values.containsKey(requireMarker(marker));
    }

    public synchronized Object get(String marker) {
        return values.get(requireMarker(marker));
    }

    public synchronized <T> T get(String marker, Class<T> type) {
        Objects.requireNonNull(type, "type");
        Object value = get(marker);
        return value == null ? null : type.cast(value);
    }

    public synchronized void put(String marker, Object value) {
        values.put(requireMarker(marker), Objects.requireNonNull(value, "value"));
    }

    public synchronized Object computeIfAbsent(String marker, java.util.function.Supplier<?> supplier) {
        String key = requireMarker(marker);
        Object existing = values.get(key);
        if (existing != null) return existing;
        // Do not use Map.computeIfAbsent here: a marker producer may evaluate
        // another marker and therefore legitimately mutate this request-owned
        // map before the outer producer returns.
        Object produced = Objects.requireNonNull(supplier.get(), "supplier returned null");
        values.put(key, produced);
        return produced;
    }

    /**
     * Gets a value in a request-local cache scope.  Scoped values are kept
     * separate from named marker values so a cache implementation cannot
     * accidentally collide with a caller-owned lifecycle value.
     */
    public synchronized Object getScoped(String marker, String scope) {
        return scopedValues.get(new ScopedKey(requireMarker(marker), requireScope(scope)));
    }

    public synchronized boolean containsScoped(String marker, String scope) {
        return scopedValues.containsKey(new ScopedKey(requireMarker(marker), requireScope(scope)));
    }

    public synchronized Object computeScopedIfAbsent(String marker, String scope,
                                                      java.util.function.Supplier<?> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        ScopedKey key = new ScopedKey(requireMarker(marker), requireScope(scope));
        Object existing = scopedValues.get(key);
        if (existing != null) return existing;
        // Nested Minecraft marker wrappers are common in a captured router;
        // HashMap.computeIfAbsent rejects the nested put as a structural
        // modification, even though both evaluations are request-local.
        Object produced = Objects.requireNonNull(supplier.get(), "supplier returned null");
        scopedValues.put(key, produced);
        return produced;
    }

    /**
     * Computes a value for a last-value cache domain.  A value is reusable only
     * while the marker's immediately preceding scope is the same; moving to a
     * different scope replaces it.  This is the lifecycle used by Minecraft's
     * Cache2D wrapper and is intentionally different from a request-wide map.
     */
    public synchronized Object computeScopedIfSame(String marker, String scope,
                                                    java.util.function.Supplier<?> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        ScopedKey key = new ScopedKey(requireMarker(marker), requireScope(scope));
        LastScopedValue previous = lastScopedValues.get(key.marker());
        if (previous != null && previous.key().equals(key)) return previous.value();
        Object produced = Objects.requireNonNull(supplier.get(), "supplier returned null");
        lastScopedValues.put(key.marker(), new LastScopedValue(key, produced));
        return produced;
    }

    public synchronized Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /**
     * Marks the request as evaluating a NoiseChunk-style interpolation cell.
     * Every invocation gets a distinct token, matching NoiseChunk's
     * interpolation counter rather than treating all cells in a request as one
     * cache domain.
     */
    public synchronized void beginInterpolation() {
        interpolationScopes.push(Math.incrementExact(interpolationSequence));
        interpolationSequence++;
    }

    public synchronized void endInterpolation() {
        if (interpolationScopes.isEmpty()) throw new IllegalStateException("Interpolation is not active");
        interpolationScopes.pop();
    }

    public synchronized boolean interpolating() { return !interpolationScopes.isEmpty(); }

    /** Returns the active NoiseChunk-style cell token. */
    public synchronized long interpolationToken() {
        Long token = interpolationScopes.peek();
        if (token == null) throw new IllegalStateException("Interpolation is not active");
        return token;
    }

    public synchronized void clear() { values.clear(); scopedValues.clear(); lastScopedValues.clear(); }

    private static String requireMarker(String marker) {
        if (marker == null || marker.isBlank()) throw new IllegalArgumentException("Marker name is required");
        return marker;
    }

    private static String requireScope(String scope) {
        if (scope == null || scope.isBlank()) throw new IllegalArgumentException("Marker scope is required");
        return scope;
    }

    private record ScopedKey(String marker, String scope) { }
    private record LastScopedValue(ScopedKey key, Object value) { }
}
