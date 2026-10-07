// SPDX-License-Identifier: MIT
package dev.tellurium.spatial.worldgen;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Reference route: every acquire executes independently and never crosses identity boundaries. */
public final class UncachedSampleStore implements SpatialSampleStore {
    @Override public java.util.concurrent.CompletionStage<SampleLease> acquire(SampleKey key, SampleProducer producer) {
        Objects.requireNonNull(key, "key"); Objects.requireNonNull(producer, "producer");
        try { return CompletableFuture.completedFuture(new SampleLease(key, producer.produce(key), () -> {})); }
        catch (Throwable failure) { return CompletableFuture.failedFuture(failure); }
    }

    @Override public java.util.concurrent.CompletionStage<SampleLease> acquireAsync(SampleKey key, AsyncSampleProducer producer) {
        Objects.requireNonNull(key, "key"); Objects.requireNonNull(producer, "producer");
        CompletionStage<double[]> produced;
        try {
            produced = Objects.requireNonNull(producer.produce(key), "async producer returned null stage");
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
        var result = new CompletableFuture<SampleLease>();
        try {
            produced.whenComplete((values, failure) -> {
                if (failure != null) {
                    result.completeExceptionally(failure);
                    return;
                }
                try {
                    SampleLease lease = new SampleLease(key, values, () -> {});
                    if (!result.complete(lease)) lease.close();
                } catch (Throwable invalidResult) {
                    result.completeExceptionally(invalidResult);
                }
            });
        } catch (Throwable registrationFailure) {
            result.completeExceptionally(registrationFailure);
        }
        return result;
    }
}
