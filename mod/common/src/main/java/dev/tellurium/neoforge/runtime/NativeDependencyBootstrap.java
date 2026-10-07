// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Mode-bound lazy native boundary: CPU_ONLY can never invoke the initializer. */
public final class NativeDependencyBootstrap {
    public enum State { DISABLED, NOT_INITIALIZED, READY, FAILED }

    private final boolean allowed;
    private final AtomicInteger calls = new AtomicInteger();
    private State state;
    private String failure = "";

    public NativeDependencyBootstrap(boolean allowed) {
        this.allowed = allowed;
        if (allowed) {
            state = State.NOT_INITIALIZED;
        } else {
            state = State.DISABLED;
            failure = "native initialization disabled by CPU_ONLY mode";
        }
    }

    /** Returns false without touching the supplier when this instance is CPU_ONLY-bound. */
    public synchronized boolean initialize(BooleanSupplier initializer) {
        Objects.requireNonNull(initializer, "initializer");
        if (!allowed || state == State.DISABLED) return false;
        if (state == State.READY) return true;
        if (state == State.FAILED) return false;
        calls.incrementAndGet();
        try {
            if (initializer.getAsBoolean()) {
                state = State.READY;
                return true;
            }
            failure = "initializer returned false";
        } catch (RuntimeException | LinkageError error) {
            failure = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        state = State.FAILED;
        return false;
    }

    public boolean allowed() { return allowed; }
    public synchronized State state() { return state; }
    public int initializationCalls() { return calls.get(); }
    public synchronized String failure() { return failure; }
}
