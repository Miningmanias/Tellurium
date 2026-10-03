// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import dev.worldgennext.engine.worldgen.WorldEpoch;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class ReloadCoordinator {
    public enum Cause { WORLD_RELOAD, DEVICE_LOST_OR_RECREATED }

    /** The two identities that must be captured together for an async attempt. */
    public record Generation(long worldEpoch, long deviceGeneration) {
        public Generation {
            if (worldEpoch < 0 || deviceGeneration < 0) {
                throw new IllegalArgumentException("Negative runtime generation");
            }
        }
    }

    public record Invalidation(Generation previous, Generation current, Cause cause) {
        public Invalidation {
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(cause, "cause");
        }
    }

    private final WorldEpoch epoch = new WorldEpoch(0);
    private final List<Consumer<Invalidation>> listeners = new ArrayList<>();
    private long deviceGeneration;

    public synchronized long worldEpoch() { return epoch.current(); }
    public synchronized long deviceGeneration() { return deviceGeneration; }
    public synchronized Generation generation() { return new Generation(epoch.current(), deviceGeneration); }

    public long reloadWorld() {
        return advance(Cause.WORLD_RELOAD).current().worldEpoch();
    }

    public long deviceLostOrRecreated() {
        return advance(Cause.DEVICE_LOST_OR_RECREATED).current().deviceGeneration();
    }

    public synchronized boolean isCurrent(Generation candidate) {
        return candidate != null && candidate.equals(generation());
    }

    /**
     * Registers a listener that invalidates loader-owned caches or in-flight
     * work.  The returned handle only removes this registration; a callback
     * never owns the coordinator itself.
     */
    public synchronized AutoCloseable onInvalidation(Consumer<Invalidation> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> removeListener(listener);
    }

    private Invalidation advance(Cause cause) {
        Invalidation invalidation;
        List<Consumer<Invalidation>> callbacks;
        synchronized (this) {
            Generation previous = new Generation(epoch.current(), deviceGeneration);
            if (cause == Cause.WORLD_RELOAD) epoch.advance();
            else deviceGeneration = Math.addExact(deviceGeneration, 1);
            invalidation = new Invalidation(previous, new Generation(epoch.current(), deviceGeneration), cause);
            callbacks = List.copyOf(listeners);
        }
        RuntimeException firstFailure = null;
        for (Consumer<Invalidation> callback : callbacks) {
            try {
                callback.accept(invalidation);
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
            }
        }
        if (firstFailure != null) throw firstFailure;
        return invalidation;
    }

    private synchronized void removeListener(Consumer<Invalidation> listener) {
        listeners.remove(listener);
    }
}
