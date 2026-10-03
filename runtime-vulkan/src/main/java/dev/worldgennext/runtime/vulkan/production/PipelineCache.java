// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan.production;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PipelineCache<T> {
    private final int maximumEntries; private final LinkedHashMap<String, T> entries = new LinkedHashMap<>(16, .75f, true);
    public PipelineCache(int maximumEntries) { if (maximumEntries <= 0) throw new IllegalArgumentException("Pipeline cache bound required"); this.maximumEntries = maximumEntries; }
    public synchronized T get(String key) { return entries.get(key); }
    public synchronized void put(String key, T value) {
        putAndCollectEvicted(key, value);
    }

    /**
     * Inserts one value and returns values evicted by the bounded LRU policy.
     * The returned list is detached from the cache, so callers may dispose
     * native values after releasing this monitor.  Replacing an existing key
     * reports the replaced value unless it is the same object.
     */
    public synchronized List<T> putAndCollectEvicted(String key, T value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        var evicted = new java.util.ArrayList<T>(1);
        T previous = entries.put(key, value);
        if (previous != null && previous != value) evicted.add(previous);
        while (entries.size() > maximumEntries) {
            String eldest = entries.keySet().iterator().next();
            T removed = entries.remove(eldest);
            if (removed != null && removed != value) evicted.add(removed);
        }
        return List.copyOf(evicted);
    }

    public synchronized T remove(String key) { return entries.remove(key); }

    /** Removes all entries and returns them for caller-owned disposal. */
    public synchronized List<T> clear() {
        if (entries.isEmpty()) return List.of();
        List<T> values = List.copyOf(entries.values());
        entries.clear();
        return values;
    }

    public synchronized int size() { return entries.size(); }
    public int maximumEntries() { return maximumEntries; }
    public synchronized Map<String, T> snapshot() { return Map.copyOf(entries); }
}
