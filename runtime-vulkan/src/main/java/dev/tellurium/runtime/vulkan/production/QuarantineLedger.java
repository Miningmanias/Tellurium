// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

import java.util.LinkedHashMap;
import java.util.Map;

public final class QuarantineLedger {
    private final Map<String, Long> entries = new LinkedHashMap<>();
    public synchronized void quarantine(String id, long bytes, String reason) { if (id == null || id.isBlank() || bytes < 0 || reason == null || reason.isBlank()) throw new IllegalArgumentException("Invalid quarantine entry"); entries.put(id, bytes); }
    public synchronized long bytes() { return entries.values().stream().mapToLong(Long::longValue).sum(); }
    public synchronized int size() { return entries.size(); }
    public synchronized Map<String, Long> snapshot() { return Map.copyOf(entries); }
    public synchronized void clearForProcessExit() { entries.clear(); }
}
