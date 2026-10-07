// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.jvm.worldgen;

import dev.tellurium.semantic.program.MarkerContext;
import java.util.Objects;
import java.util.function.Supplier;

/** Explicit cache semantics for mutable marker nodes. */
public final class MarkerEvaluator {
    public enum Mode { NONE, ONCE, ALL_IN_CELL, CACHE_2D, FLAT_CACHE }
    public <T> T evaluate(String marker, Mode mode, MarkerContext context, Supplier<T> producer) {
        Objects.requireNonNull(context, "context"); Objects.requireNonNull(producer, "producer");
        if (marker == null || marker.isBlank()) throw new IllegalArgumentException("Marker required");
        if (mode == Mode.NONE) return Objects.requireNonNull(producer.get(), "producer returned null");
        return evaluate(marker, mode, context, "request", producer);
    }

    /**
     * Evaluates a marker in an explicit request-local domain.  The domain is
     * supplied by the caller because cache identity is part of the
     * version-pinned game lifecycle, not a property of the wrapped scalar.
     */
    public <T> T evaluate(String marker, Mode mode, MarkerContext context, String scope, Supplier<T> producer) {
        Objects.requireNonNull(context, "context"); Objects.requireNonNull(producer, "producer");
        if (marker == null || marker.isBlank()) throw new IllegalArgumentException("Marker required");
        if (mode == Mode.NONE) return Objects.requireNonNull(producer.get(), "producer returned null");
        @SuppressWarnings("unchecked") T value = (T) context.computeScopedIfAbsent(marker, scope, producer);
        return value;
    }

    /** Evaluate a last-value cache domain such as Minecraft's Cache2D. */
    public <T> T evaluateLast(String marker, MarkerContext context, String scope, Supplier<T> producer) {
        Objects.requireNonNull(context, "context"); Objects.requireNonNull(producer, "producer");
        if (marker == null || marker.isBlank()) throw new IllegalArgumentException("Marker required");
        @SuppressWarnings("unchecked") T value = (T) context.computeScopedIfSame(marker, scope, producer);
        return value;
    }
}
