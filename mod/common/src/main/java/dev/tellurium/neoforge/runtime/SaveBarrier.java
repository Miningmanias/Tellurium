// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class SaveBarrier {
    public enum State { IDLE, REQUESTED, COMPLETED, FAILED }
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final CompletableFuture<Void> completed = new CompletableFuture<>();

    public CompletionStage<Void> request() {
        if (!state.compareAndSet(State.IDLE, State.REQUESTED)) {
            throw new IllegalStateException("Save barrier is already " + state.get());
        }
        return completed.minimalCompletionStage();
    }

    /**
     * Returns a bounded observer of this barrier without mutating the shared
     * completion future when the caller's deadline expires.
     */
    public CompletionStage<Void> await(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Positive save-barrier timeout required");
        }
        if (!requested()) {
            throw new IllegalStateException("Save barrier must be requested before await");
        }
        final long timeoutMillis;
        try {
            // Duration#toMillis truncates. Preserve a positive observer
            // deadline for sub-millisecond callers instead of turning it into
            // an already-expired zero-millisecond timeout.
            timeoutMillis = Math.max(1L, timeout.toMillis());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Save-barrier timeout is too large", overflow);
        }
        CompletableFuture<Void> bounded = new CompletableFuture<>();
        completed.whenComplete((ignored, error) -> {
            if (error == null) bounded.complete(null);
            else bounded.completeExceptionally(error);
        });
        return bounded.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).minimalCompletionStage();
    }

    public boolean requested() { return state.get() != State.IDLE; }
    public boolean terminal() { return switch (state.get()) { case COMPLETED, FAILED -> true; default -> false; }; }
    public State state() { return state.get(); }
    public boolean completed() { return state.get() == State.COMPLETED; }
    public Throwable failure() { return failure.get(); }

    public void complete() {
        if (!state.compareAndSet(State.REQUESTED, State.COMPLETED)) {
            throw new IllegalStateException("Save barrier is " + state.get());
        }
        completed.complete(null);
    }

    public void fail(Throwable failure) {
        java.util.Objects.requireNonNull(failure, "failure");
        if (!state.compareAndSet(State.REQUESTED, State.FAILED)) {
            throw new IllegalStateException("Save barrier is " + state.get());
        }
        this.failure.set(failure);
        completed.completeExceptionally(failure);
    }
}
