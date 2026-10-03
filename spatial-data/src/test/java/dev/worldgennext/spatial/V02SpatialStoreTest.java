// SPDX-License-Identifier: MIT
package dev.worldgennext.spatial;

import dev.worldgennext.semantic.identity.DynamicInputIdentity;
import dev.worldgennext.semantic.identity.ContextIdentity;
import dev.worldgennext.semantic.program.NumericProfile;
import dev.worldgennext.spatial.worldgen.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.Executor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class V02SpatialStoreTest {
    private static final class ManualExecutor implements Executor {
        private final ArrayBlockingQueue<Runnable> pending = new ArrayBlockingQueue<>(8);
        @Override public void execute(Runnable task) { if (!pending.offer(task)) throw new IllegalStateException("manual queue full"); }
        void runNext() { Runnable task = pending.poll(); if (task == null) throw new IllegalStateException("no pending task"); task.run(); }
    }

    @Test void cachedAndUncachedStoresReturnTheSameLiteralSamples() {
        var context = new ContextIdentity("snapshot", "registry", "program", NumericProfile.JAVA_REFERENCE, "1", "compiler", 0, DynamicInputIdentity.empty(), 0);
        var key = new SampleKey("density", SampleDomain.LATTICE, new SampleExtent(-1, 0, -1, 1, 2, 1), 0, context);
        Executor direct = Runnable::run;
        try (var store = SpatialSampleStore.bounded(1024, 2, direct)) {
            var first = store.acquire(key, ignored -> new double[]{1, 2, 3, 4, 5, 6, 7, 8}).toCompletableFuture().join();
            assertEquals(8, first.size());
            assertEquals(1, first.value(-1, 0, -1));
            first.close();
        }
    }

    @Test void sampleExtentUsesWidenedCoordinateArithmeticAndRejectsOverflowingHalos() {
        var extent = new SampleExtent(Integer.MIN_VALUE, 0, 0, -1, 1, 1);
        assertEquals(Integer.MAX_VALUE, extent.width());
        assertEquals(Integer.MAX_VALUE - 1, extent.index(-2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> extent.expand(1));
    }

    @Test void boundedStoreEvictsFinishedEntriesWhenTheNewPayloadNeedsMoreThanRemainingBytes() {
        var context = new ContextIdentity("snapshot", "registry", "program", NumericProfile.JAVA_REFERENCE,
                "1", "compiler", 0, DynamicInputIdentity.empty(), 0);
        var one = new SampleKey("one", SampleDomain.LATTICE, new SampleExtent(0, 0, 0, 2, 1, 1), 0, context);
        var two = new SampleKey("two", SampleDomain.LATTICE, new SampleExtent(0, 0, 0, 1, 1, 1), 0, context);
        var three = new SampleKey("three", SampleDomain.LATTICE, new SampleExtent(0, 0, 0, 2, 1, 1), 0, context);
        AtomicInteger retried = new AtomicInteger();
        try (var store = SpatialSampleStore.bounded(32, 3, Runnable::run)) {
            try (var lease = store.acquire(one, ignored -> new double[]{1, 2}).toCompletableFuture().join()) { }
            try (var lease = store.acquire(two, ignored -> new double[]{3}).toCompletableFuture().join()) { }
            try (var lease = store.acquire(two, ignored -> new double[]{3}).toCompletableFuture().join()) { }
            try (var lease = store.acquire(three, ignored -> new double[]{4, 5}).toCompletableFuture().join()) { }
            try (var lease = store.acquire(one, ignored -> { retried.incrementAndGet(); return new double[]{6, 7}; }).toCompletableFuture().join()) { }
            assertEquals(1, retried.get());
        }
    }

    @Test void cancellingPendingAcquireReleasesInterestAfterProducerFailureSoTheKeyCanBeRetried() {
        ManualExecutor executor = new ManualExecutor();
        SampleKey failed = key("failed", 0);
        SampleKey replacement = key("replacement", 1);
        try (var store = SpatialSampleStore.bounded(Double.BYTES, 1, executor)) {
            var pending = store.acquire(failed, ignored -> { throw new IllegalStateException("producer failed"); })
                    .toCompletableFuture();
            assertTrue(pending.cancel(false));

            // The producer still runs to preserve shared-producer ownership,
            // but the cancelled consumer no longer pins its failed entry.
            executor.runNext();

            var replacementPending = store.acquire(replacement, ignored -> new double[]{9})
                    .toCompletableFuture();
            executor.runNext();
            try (var lease = replacementPending.join()) {
                assertEquals(9, lease.value(1, 0, 0));
            }
        }
    }

    @Test void cancellingOnePendingConsumerDoesNotCancelTheSharedProducerOrOtherConsumer() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger produced = new AtomicInteger();
        SampleKey sample = key("shared", 0);
        try (var store = SpatialSampleStore.bounded(Double.BYTES, 1, executor)) {
            var cancelled = store.acquire(sample, ignored -> {
                produced.incrementAndGet();
                return new double[]{17};
            }).toCompletableFuture();
            var retained = store.acquire(sample, ignored -> { fail("duplicate producer"); return null; })
                    .toCompletableFuture();
            assertTrue(cancelled.cancel(false));

            executor.runNext();

            try (var lease = retained.join()) {
                assertEquals(17, lease.value(0, 0, 0));
            }
            assertEquals(1, produced.get());
        }
    }

    @Test void asyncProducerDoesNotBlockTheCallingWorkerOrJoinItsCompletion() {
        ManualExecutor executor = new ManualExecutor();
        SampleKey sample = key("async", 0);
        CompletableFuture<double[]> produced = new CompletableFuture<>();
        AtomicInteger producerCalls = new AtomicInteger();
        try (var store = SpatialSampleStore.bounded(Double.BYTES, 1, executor)) {
            var pending = store.acquireAsync(sample, ignored -> {
                producerCalls.incrementAndGet();
                return produced;
            }).toCompletableFuture();

            assertFalse(pending.isDone(), "acquire must not join an incomplete producer stage");
            executor.runNext();
            assertEquals(1, producerCalls.get());
            assertFalse(pending.isDone());

            produced.complete(new double[]{23});
            try (var lease = pending.join()) {
                assertEquals(23, lease.value(0, 0, 0));
            }
        }
    }

    @Test void asyncConsumersShareOneProducerAndCancellationReleasesOnlyTheirInterest() {
        ManualExecutor executor = new ManualExecutor();
        SampleKey sample = key("async-shared", 0);
        CompletableFuture<double[]> produced = new CompletableFuture<>();
        AtomicInteger producerCalls = new AtomicInteger();
        try (var store = SpatialSampleStore.bounded(Double.BYTES, 1, executor)) {
            var cancelled = store.acquireAsync(sample, ignored -> {
                producerCalls.incrementAndGet();
                return produced;
            }).toCompletableFuture();
            var retained = store.acquireAsync(sample, ignored -> {
                fail("duplicate async producer");
                return null;
            }).toCompletableFuture();

            assertTrue(cancelled.cancel(false));
            executor.runNext();
            produced.complete(new double[]{29});

            try (var lease = retained.join()) {
                assertEquals(29, lease.value(0, 0, 0));
            }
            assertEquals(1, producerCalls.get());
        }
    }

    @Test void asyncStoreRejectsProducerFailureAndMalformedValuesInBothRoutes() {
        SampleKey sample = key("async-invalid", 0);
        CompletableFuture<double[]> failed = new CompletableFuture<>();
        var uncached = new UncachedSampleStore();
        var failedAcquire = uncached.acquireAsync(sample, ignored -> failed).toCompletableFuture();
        failed.completeExceptionally(new IllegalStateException("sample failed"));
        assertThrows(java.util.concurrent.CompletionException.class, failedAcquire::join);

        CompletableFuture<double[]> malformed = CompletableFuture.completedFuture(new double[0]);
        try (var store = SpatialSampleStore.bounded(Double.BYTES, 1, Runnable::run)) {
            var malformedAcquire = store.acquireAsync(sample, ignored -> malformed).toCompletableFuture();
            assertThrows(java.util.concurrent.CompletionException.class, malformedAcquire::join);
        }
    }

    @Test void executorErrorsRetireTheBoundedAsyncEntryInsteadOfLeakingItsReservation() {
        SampleKey failed = key("executor-error", 0);
        SampleKey replacement = key("executor-replacement", 1);
        AtomicInteger executions = new AtomicInteger();
        try (var store = SpatialSampleStore.bounded(Double.BYTES, 1,
                task -> {
                    if (executions.getAndIncrement() == 0) throw new AssertionError("executor failed");
                    task.run();
                })) {
            var pending = store.acquireAsync(failed,
                    ignored -> CompletableFuture.completedFuture(new double[]{1})).toCompletableFuture();
            assertThrows(java.util.concurrent.CompletionException.class, pending::join);

            try (var lease = store.acquire(replacement, ignored -> new double[]{31})
                    .toCompletableFuture().join()) {
                assertEquals(31, lease.value(1, 0, 0));
            }
        }
    }

    @Test void residencyLedgerRequiresIdentitiesAndReleasesRetainedCapacityExactlyOnce() {
        SampleKey sample = key("resident", 0);
        var ledger = new ResidencyLedger(8);
        var lease = ledger.tryReserve(sample, 8).orElseThrow();
        assertEquals(8, ledger.usedBytes());
        assertEquals(1, ledger.residentEntries());
        assertTrue(ledger.tryReserve(sample, 1).isEmpty());
        assertThrows(NullPointerException.class, () -> ledger.tryReserve(null, 1));
        assertThrows(IllegalArgumentException.class, () -> ledger.tryReserve(key("negative", 1), -1));

        lease.close();
        lease.close();
        assertEquals(0, ledger.usedBytes());
        assertEquals(0, ledger.residentEntries());
        ledger.close();
        assertTrue(ledger.tryReserve(key("closed", 2), 0).isEmpty());
    }

    private static SampleKey key(String node, int x) {
        var context = new ContextIdentity("snapshot", "registry", "program", NumericProfile.JAVA_REFERENCE,
                "1", "compiler", 0, DynamicInputIdentity.empty(), 0);
        return new SampleKey(node, SampleDomain.LATTICE, new SampleExtent(x, 0, 0, x + 1, 1, 1), 0, context);
    }
}
