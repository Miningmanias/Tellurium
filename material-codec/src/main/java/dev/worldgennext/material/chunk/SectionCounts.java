// SPDX-License-Identifier: MIT
package dev.worldgennext.material.chunk;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public final class SectionCounts {
    private final Map<String, Integer> counts;
    public SectionCounts(Map<String, Integer> counts) {
        var sorted = new TreeMap<String, Integer>();
        if (counts != null) counts.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || value < 0) throw new IllegalArgumentException("Invalid state count");
            sorted.put(key, value);
        });
        this.counts = Collections.unmodifiableMap(sorted);
    }
    public Map<String, Integer> values() { return counts; }
    public int count(String canonical) { return counts.getOrDefault(canonical, 0); }
    public int total() { return counts.values().stream().mapToInt(Integer::intValue).sum(); }
}
