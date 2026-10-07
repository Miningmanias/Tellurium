// SPDX-License-Identifier: MIT
package dev.tellurium.oracle.benchmark;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

public final class StageTimers {
    private final Map<String, Long> nanos = new LinkedHashMap<>();
    public synchronized void add(String stage, long durationNanos) { if (stage == null || stage.isBlank() || durationNanos < 0) throw new IllegalArgumentException("Invalid stage timing"); nanos.merge(stage, durationNanos, Math::addExact); }
    public synchronized Map<String, Long> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(nanos));
    }
}
