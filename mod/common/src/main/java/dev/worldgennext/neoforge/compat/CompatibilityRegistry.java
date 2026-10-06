// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.compat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Explicit compatibility decisions for competing hooks and unsupported generators. */
public final class CompatibilityRegistry {
    public record Decision(boolean eligible, String route, String reason) { public Decision { if (route == null || route.isBlank() || reason == null || reason.isBlank()) throw new IllegalArgumentException("Compatibility decision requires reason"); } }
    private final Map<String, Decision> decisions = new LinkedHashMap<>();
    public synchronized void register(String context, Decision decision) {
        if (context == null || context.isBlank()) throw new IllegalArgumentException("Context required");
        Objects.requireNonNull(decision, "decision");
        Decision existing = decisions.get(context);
        if (existing != null && !existing.equals(decision)) {
            throw new IllegalStateException("Conflicting compatibility decision for context " + context
                    + ": existing=" + existing.route() + ", replacement=" + decision.route());
        }
        decisions.putIfAbsent(context, decision);
    }
    public synchronized Decision decision(String context) {
        if (context == null || context.isBlank()) {
            return new Decision(false, "CPU_ORIGINAL_PLANNED", "No qualified WorldgenNext context is registered");
        }
        return decisions.getOrDefault(context, new Decision(false, "CPU_ORIGINAL_PLANNED", "No qualified WorldgenNext context is registered"));
    }
    public synchronized Map<String, Decision> snapshot() { return Map.copyOf(decisions); }
}
