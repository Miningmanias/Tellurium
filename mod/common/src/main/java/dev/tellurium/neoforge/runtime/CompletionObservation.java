// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/** Evidence observation must not cut the cancellation link to the real coordinated request. */
final class CompletionObservation {
    private CompletionObservation() { }

    static <T> CompletableFuture<T> observe(CompletableFuture<T> source, BiConsumer<T, Throwable> observer) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(observer, "observer");
        CompletableFuture<T> observed = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (cancelled) source.cancel(mayInterruptIfRunning);
                return cancelled;
            }
        };
        source.whenComplete((value, failure) -> {
            Throwable terminal = failure;
            try { observer.accept(value, failure); }
            catch (Throwable observationFailure) {
                if (terminal == null) terminal = observationFailure;
                else if (terminal != observationFailure) terminal.addSuppressed(observationFailure);
            }
            if (terminal == null) observed.complete(value);
            else observed.completeExceptionally(terminal);
        });
        return observed;
    }
}
