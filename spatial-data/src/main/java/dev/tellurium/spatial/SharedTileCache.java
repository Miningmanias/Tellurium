// SPDX-License-Identifier: MIT
package dev.tellurium.spatial;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Bounded byte-payload cache with a single producer per resident key and leased consumers.
 * The budget covers reserved resident payload bytes, not Java object overhead or caller copies.
 * Active leases and in-flight producers cannot be evicted. Producers are supplied by the caller;
 * this class does not compute Minecraft samples or install a production region service.
 */
public final class SharedTileCache implements AutoCloseable {
    private final ByteBudget budget;
    private final int maximumEntries;
    private final LinkedHashMap<TileKey, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private boolean closed;
    public SharedTileCache(long maximumPayloadBytes, int maximumEntries) {
        if (maximumEntries <= 0) throw new IllegalArgumentException("Entry limit must be positive");
        this.budget = new ByteBudget(maximumPayloadBytes);
        this.maximumEntries = maximumEntries;
    }
    public long reservedBytes() { return budget.usedBytes(); }
    public synchronized int entryCount() { return entries.size(); }
    public Lease acquire(TileKey key, int payloadBytes, Supplier<byte[]> producer, Executor executor) {
        Objects.requireNonNull(key, "key"); Objects.requireNonNull(producer, "producer"); Objects.requireNonNull(executor, "executor");
        if (payloadBytes < 0) throw new IllegalArgumentException("Negative payload size");
        Entry entry;
        synchronized (this) {
            if (closed) throw new IllegalStateException("Cache closed");
            entry = entries.get(key);
            if (entry != null) {
                if (entry.payloadBytes != payloadBytes) throw new IllegalArgumentException("Different byte size for resident key");
                entry.leases++;
                return new Lease(entry);
            }
            if (payloadBytes > budget.capacity()) throw new RejectedExecutionException("Payload exceeds cache capacity");
            evictUntilFits(payloadBytes);
            if (entries.size() >= maximumEntries) throw new RejectedExecutionException("All cache entries pinned");
            ByteBudget.Reservation reservation = budget.tryReserve(payloadBytes)
                    .orElseThrow(() -> new RejectedExecutionException("All cache bytes pinned"));
            entry = new Entry(key, payloadBytes, reservation);
            entry.leases = 1;
            entries.put(key, entry);
        }
        Entry created = entry;
        try { executor.execute(() -> produce(created, producer)); }
        catch (RuntimeException failure) { finish(created, null, failure); }
        return new Lease(entry);
    }
    private void evictUntilFits(int bytes) {
        Iterator<Entry> iterator = entries.values().iterator();
        while ((entries.size() >= maximumEntries || budget.availableBytes() < bytes) && iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.leases == 0 && entry.producerFinished) {
                iterator.remove(); entry.reservation.close();
            }
        }
    }
    private void produce(Entry entry, Supplier<byte[]> producer) {
        try {
            byte[] value = Objects.requireNonNull(producer.get(), "Producer returned null");
            if (value.length != entry.payloadBytes) throw new IllegalArgumentException("Producer payload differs from reservation");
            finish(entry, value.clone(), null);
        } catch (Throwable failure) { finish(entry, null, failure); }
    }
    private void finish(Entry entry, byte[] value, Throwable failure) {
        synchronized (this) {
            // Defends against an executor that ran a task and then threw on return.
            if (entry.producerFinished) return;
            entry.producerFinished = true;
            entry.failed = failure != null;
            retireIfUnused(entry);
        }
        // Future callbacks must not execute under the cache lock.
        if (failure == null) entry.value.complete(value); else entry.value.completeExceptionally(failure);
    }
    private void retireIfUnused(Entry entry) {
        if (entry.leases == 0 && entry.producerFinished && (entry.failed || closed)) {
            entries.remove(entry.key, entry);
            entry.reservation.close();
        }
    }
    @Override public synchronized void close() {
        closed = true;
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.leases == 0 && entry.producerFinished) { iterator.remove(); entry.reservation.close(); }
        }
    }
    private static final class Entry {
        final TileKey key;
        final int payloadBytes;
        final ByteBudget.Reservation reservation;
        final CompletableFuture<byte[]> value = new CompletableFuture<>();
        int leases;
        boolean producerFinished, failed;
        Entry(TileKey key, int payloadBytes, ByteBudget.Reservation reservation) {
            this.key = key; this.payloadBytes = payloadBytes; this.reservation = reservation;
        }
    }
    public final class Lease implements AutoCloseable {
        private final Entry entry;
        private boolean released;
        private Lease(Entry entry) { this.entry = entry; }
        /** Each consumer receives its own copy; completing/cancelling that future cannot alter the producer. */
        public CompletionStage<byte[]> value() {
            synchronized (SharedTileCache.this) {
                if (released) throw new IllegalStateException("Lease released");
                return entry.value.thenApply(byte[]::clone).minimalCompletionStage();
            }
        }
        @Override public void close() {
            synchronized (SharedTileCache.this) {
                if (released) return;
                released = true;
                entry.leases--;
                retireIfUnused(entry);
            }
        }
    }
}
