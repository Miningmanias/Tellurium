// SPDX-License-Identifier: MIT
package dev.tellurium.engine.worldgen;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** One consumer interest in shared work; cancellation does not cancel other subscribers. */
public final class RequestSubscription implements AutoCloseable {
    private final WorkRecord record;
    private final java.util.concurrent.CompletableFuture<WorkRecord.Terminal> completion = new java.util.concurrent.CompletableFuture<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    RequestSubscription(WorkRecord record) { this.record = record; record.attach(this); }
    public CompletionStage<WorkRecord.Terminal> completion() { return completion.minimalCompletionStage(); }
    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) return false;
        if (!record.detach(this)) {
            // The shared work became terminal before this consumer won the
            // detach race.  Preserve the authoritative terminal outcome;
            // cancellation is only successful while interest was attached.
            cancelled.set(false);
            return false;
        }
        completion.complete(new WorkRecord.Terminal(WorkRecord.State.CANCELLED, "subscriber cancelled", null));
        return true;
    }
    boolean isCancelled() { return cancelled.get(); }
    void complete(WorkRecord.Terminal terminal) { if (!cancelled.get()) completion.complete(terminal); }
    @Override public void close() { cancel(); }
}
