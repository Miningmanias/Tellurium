// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.jvm.worldgen;

import java.util.HashMap;
import java.util.Map;

/** Reusable request-owned scratch values; clear it between requests/epochs. */
public final class EvaluationScratch {
    private final Map<String, Object> values = new HashMap<>();
    public Object get(String key) { return values.get(key); }
    public void put(String key, Object value) { if (key == null || key.isBlank() || value == null) throw new IllegalArgumentException("Invalid scratch value"); values.put(key, value); }
    public boolean contains(String key) { return values.containsKey(key); }
    public int size() { return values.size(); }
    public void clear() { values.clear(); }
}
