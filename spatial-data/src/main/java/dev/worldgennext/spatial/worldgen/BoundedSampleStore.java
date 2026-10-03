// SPDX-License-Identifier: MIT
package dev.worldgennext.spatial.worldgen;

import dev.worldgennext.spatial.ByteBudget;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded typed sample store with one producer and multiple independent consumer leases. */
final class BoundedSampleStore implements SpatialSampleStore {
    private final ByteBudget budget; private final int maximumEntries; private final Executor executor;
    private final LinkedHashMap<SampleKey, Entry> entries = new LinkedHashMap<>(16, .75f, true); private boolean closed;
    BoundedSampleStore(long maximumBytes, int maximumEntries, Executor executor) { if (maximumEntries <= 0) throw new IllegalArgumentException("Entry bound required"); budget = new ByteBudget(maximumBytes); this.maximumEntries = maximumEntries; this.executor = Objects.requireNonNull(executor); }
    @Override public synchronized long reservedBytes() { return budget.usedBytes(); }
    @Override public synchronized int residentEntries() { return entries.size(); }
    @Override public CompletionStage<SampleLease> acquire(SampleKey key, SampleProducer producer) {
        Objects.requireNonNull(key); Objects.requireNonNull(producer);
        return acquireInternal(key, producer, (entry, value) -> executor.execute(() -> produce(entry, value)));
    }

    @Override public CompletionStage<SampleLease> acquireAsync(SampleKey key, AsyncSampleProducer producer) {
        Objects.requireNonNull(key); Objects.requireNonNull(producer);
        // Invoke the async producer on the supplied executor, but never wait
        // for its returned stage.  This keeps the caller's worker free to run
        // the completion that the producer may need.
        return acquireInternal(key, producer, (entry, value) -> executor.execute(() -> produceAsync(entry, value)));
    }

    private <P> CompletionStage<SampleLease> acquireInternal(SampleKey key, P producer, Starter<P> starter) {
        Entry entry; boolean start = false;
        synchronized (this) {
            if (closed) throw new IllegalStateException("Sample store closed"); entry = entries.get(key);
            if (entry == null) {
                long bytes;
                try { bytes = Math.multiplyExact((long) key.requestedExtent().volume(), Double.BYTES); }
                catch (ArithmeticException overflow) { throw new RejectedExecutionException("Sample byte count overflow", overflow); }
                if (bytes > budget.capacity()) throw new RejectedExecutionException("Sample exceeds byte budget");
                evict(bytes);
                if (entries.size() >= maximumEntries) throw new RejectedExecutionException("Sample entry bound exhausted");
                var reservation = budget.tryReserve(bytes).orElseThrow(() -> new RejectedExecutionException("Sample byte budget exhausted"));
                entry = new Entry(key, reservation); entries.put(key, entry); start = true;
            }
            entry.leases++;
        }
        Entry leased = entry;
        var released = new AtomicBoolean();
        Runnable releaseInterest = () -> {
            if (released.compareAndSet(false, true)) release(leased);
        };
        var result = new CompletableFuture<SampleLease>();
        // The returned future is the consumer's pending interest.  A caller
        // may cancel it before the producer completes; cancellation must not
        // pin a failed/in-flight entry or strand the reservation.
        result.whenComplete((value, failure) -> {
            if (failure != null) releaseInterest.run();
        });
        leased.value.whenComplete((values, failure) -> {
            if (failure != null) {
                releaseInterest.run();
                result.completeExceptionally(failure);
                return;
            }
            try {
                SampleLease lease = new SampleLease(key, values, releaseInterest);
                // Cancellation can race with producer completion.  If the
                // future lost that race, close the newly-created lease so its
                // reserved consumer interest is released as well.
                if (!result.complete(lease)) lease.close();
            } catch (Throwable invalidResult) {
                releaseInterest.run();
                result.completeExceptionally(invalidResult);
            }
        });
        if (start) {
            Entry created = entry;
            try { starter.start(created, producer); }
            // Executor implementations normally reject with RuntimeException,
            // but an Error from a custom queue is still a failed start and
            // must not strand the entry reservation.
            catch (Throwable failure) { finish(created, null, failure); }
        }
        return result;
    }
    private void produce(Entry entry, SampleProducer producer) { try { double[] values = Objects.requireNonNull(producer.produce(entry.key)); if (values.length != entry.key.requestedExtent().volume()) throw new IllegalArgumentException("Produced sample count differs from extent"); finish(entry, values, null); } catch (Throwable failure) { finish(entry, null, failure); } }
    private void produceAsync(Entry entry, AsyncSampleProducer producer) {
        CompletionStage<double[]> produced;
        try {
            produced = Objects.requireNonNull(producer.produce(entry.key), "async producer returned null stage");
        } catch (Throwable failure) {
            finish(entry, null, failure);
            return;
        }
        try {
            produced.whenComplete((values, failure) -> {
                if (failure != null) {
                    finish(entry, null, failure);
                } else if (values == null) {
                    finish(entry, null, new NullPointerException("async producer returned null values"));
                } else if (values.length != entry.key.requestedExtent().volume()) {
                    finish(entry, null, new IllegalArgumentException("Produced sample count differs from extent"));
                } else {
                    finish(entry, values, null);
                }
            });
        } catch (Throwable registrationFailure) {
            finish(entry, null, registrationFailure);
        }
    }
    private void finish(Entry entry, double[] values, Throwable failure) { synchronized (this) { if (entry.finished) return; entry.finished = true; entry.failed = failure != null; retire(entry); } if (failure == null) entry.value.complete(values.clone()); else entry.value.completeExceptionally(failure); }
    private synchronized void release(Entry entry) { if (entry.leases > 0) entry.leases--; retire(entry); }
    private void retire(Entry entry) { if (entry.leases == 0 && entry.finished && (entry.failed || closed)) { entries.remove(entry.key, entry); entry.reservation.close(); } }
    private void evict(long requiredBytes) {
        Iterator<Entry> iterator = entries.values().iterator();
        while ((entries.size() >= maximumEntries || budget.availableBytes() < requiredBytes) && iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.leases == 0 && entry.finished) { iterator.remove(); entry.reservation.close(); }
        }
    }
    @Override public synchronized void close() { closed = true; entries.values().removeIf(entry -> { if (entry.leases == 0 && entry.finished) { entry.reservation.close(); return true; } return false; }); }
    @FunctionalInterface
    private interface Starter<P> { void start(Entry entry, P producer); }
    private static final class Entry { final SampleKey key; final ByteBudget.Reservation reservation; final CompletableFuture<double[]> value = new CompletableFuture<>(); int leases; boolean finished, failed; Entry(SampleKey key, ByteBudget.Reservation reservation) { this.key = key; this.reservation = reservation; } }
}
