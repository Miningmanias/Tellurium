// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan.production;

import java.util.LinkedHashMap;
import java.util.Objects;

/** Owned Java-only modules, bounded by both entry count and binary bytes. No native handles. */
public final class SpirvModuleCache {
    public record Telemetry(int retainedEntries, long retainedBytes, int maximumEntries,
                            long maximumBytes, long evictions, long oversizedRejections) {
        public Telemetry {
            if (maximumEntries < 1 || maximumBytes < 1 || retainedEntries < 0
                    || retainedEntries > maximumEntries || retainedBytes < 0 || retainedBytes > maximumBytes
                    || evictions < 0 || oversizedRejections < 0)
                throw new IllegalArgumentException("Invalid SPIR-V cache telemetry");
        }
    }
    private final int maximumEntries;
    private final long maximumBytes;
    private final LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>(16, .75f, true);
    private long retainedBytes;
    private long evictions;
    private long oversizedRejections;

    public SpirvModuleCache(int maximumEntries, long maximumBytes) {
        if (maximumEntries < 1 || maximumBytes < 1) throw new IllegalArgumentException("Positive SPIR-V cache bounds required");
        this.maximumEntries = maximumEntries;
        this.maximumBytes = maximumBytes;
    }

    /** Callers cannot mutate retained cache storage through a hit. */
    public synchronized byte[] get(String key) {
        byte[] value = entries.get(Objects.requireNonNull(key, "key"));
        return value == null ? null : value.clone();
    }

    /** Oversized modules may execute, but are never retained and invalidate an older same-key value. */
    public synchronized boolean put(String key, byte[] module) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Module cache identity required");
        if (module == null || module.length == 0 || (module.length & 3) != 0)
            throw new IllegalArgumentException("Word-aligned nonempty module required");
        byte[] previous = entries.remove(key);
        if (previous != null) retainedBytes -= previous.length;
        if (module.length > maximumBytes) {
            oversizedRejections++;
            return false;
        }
        while (entries.size() >= maximumEntries || retainedBytes > maximumBytes - module.length) {
            var eldest = entries.entrySet().iterator();
            var removed = eldest.next();
            retainedBytes -= removed.getValue().length;
            eldest.remove();
            evictions++;
        }
        entries.put(key, module.clone());
        retainedBytes += module.length;
        return true;
    }

    /** Drop storage, preserving executor-lifetime counters. */
    public synchronized void clear() { entries.clear(); retainedBytes = 0; }
    public synchronized Telemetry telemetry() {
        return new Telemetry(entries.size(), retainedBytes, maximumEntries, maximumBytes, evictions, oversizedRejections);
    }
}
