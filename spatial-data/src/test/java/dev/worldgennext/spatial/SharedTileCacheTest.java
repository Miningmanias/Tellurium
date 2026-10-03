// SPDX-License-Identifier: MIT
package dev.worldgennext.spatial;

import dev.worldgennext.semantic.WorldgenIdentity;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class SharedTileCacheTest {
    private static TileKey key(int x) { return TileKey.atChunk(new WorldgenIdentity(1, "overworld", "g", 0), "node", "d", "h", x, 0, 1); }
    private static final class ManualExecutor implements Executor {
        final Queue<Runnable> pending = new ArrayDeque<>();
        public synchronized void execute(Runnable task) { pending.add(task); }
        void runNext() { Runnable task; synchronized (this) { task = pending.remove(); } task.run(); }
    }
    @Test void consumersShareProducerAndReceiveIndependentOwnedCopies() {
        ManualExecutor executor = new ManualExecutor(); AtomicInteger produced = new AtomicInteger(); byte[] original = {1, 2};
        try (SharedTileCache cache = new SharedTileCache(2, 1);
             var a = cache.acquire(key(0), 2, () -> { produced.incrementAndGet(); return original; }, executor);
             var b = cache.acquire(key(0), 2, () -> { fail("Duplicate producer"); return null; }, executor)) {
            assertEquals(1, executor.pending.size()); executor.runNext(); assertEquals(1, produced.get());
            original[0] = 99; byte[] first = a.value().toCompletableFuture().join(); first[1] = 77;
            assertArrayEquals(new byte[]{1, 2}, b.value().toCompletableFuture().join());
            assertArrayEquals(new byte[]{1, 2}, a.value().toCompletableFuture().join());
        }
    }
    @Test void activeAndProducingEntriesCannotBeEvicted() {
        ManualExecutor executor = new ManualExecutor(); SharedTileCache cache = new SharedTileCache(4, 1);
        var active = cache.acquire(key(0), 4, () -> new byte[4], executor);
        assertThrows(RejectedExecutionException.class, () -> cache.acquire(key(1), 4, () -> new byte[4], executor));
        active.close(); assertThrows(RejectedExecutionException.class, () -> cache.acquire(key(1), 4, () -> new byte[4], executor));
        executor.runNext();
        try (var replacement = cache.acquire(key(1), 4, () -> new byte[4], Runnable::run)) {
            assertEquals(4, cache.reservedBytes()); assertEquals(1, cache.entryCount());
        }
        cache.close(); assertEquals(0, cache.reservedBytes());
    }
    @Test void closePreservesLeaseAndInFlightReservationsUntilSafeRelease() {
        ManualExecutor executor = new ManualExecutor(); SharedTileCache cache = new SharedTileCache(4, 2);
        var lease = cache.acquire(key(0), 4, () -> new byte[4], executor);
        var value = lease.value().toCompletableFuture(); cache.close(); cache.close(); lease.close(); lease.close();
        assertEquals(4, cache.reservedBytes());
        assertThrows(IllegalStateException.class, lease::value);
        assertThrows(IllegalStateException.class, () -> cache.acquire(key(1), 0, () -> new byte[0], executor));
        executor.runNext(); assertEquals(4, value.join().length); assertEquals(0, cache.reservedBytes());
        assertEquals(0, cache.entryCount());
    }
    @Test void cancellationOfConsumerFutureDoesNotCancelOtherLeases() {
        ManualExecutor executor = new ManualExecutor();
        try (var cache = new SharedTileCache(1, 1); var lease = cache.acquire(key(0), 1, () -> new byte[]{7}, executor)) {
            var cancelled = lease.value().toCompletableFuture(); var unaffected = lease.value().toCompletableFuture();
            assertTrue(cancelled.cancel(false)); executor.runNext(); assertArrayEquals(new byte[]{7}, unaffected.join());
            assertTrue(cancelled.isCancelled());
        }
    }
    @Test void failedProducerRetiresAfterLastLeaseAndCanBeRetried() {
        try (var cache = new SharedTileCache(1, 1)) {
            var lease = cache.acquire(key(0), 1, () -> { throw new IllegalStateException("boom"); }, Runnable::run);
            assertThrows(CompletionException.class, () -> lease.value().toCompletableFuture().join());
            assertEquals(1, cache.reservedBytes()); lease.close(); assertEquals(0, cache.reservedBytes());
            try (var retry = cache.acquire(key(0), 1, () -> new byte[]{5}, Runnable::run)) {
                assertEquals(5, retry.value().toCompletableFuture().join()[0]);
            }
        }
    }
    @Test void rejectedExecutorAndWrongSizeCannotLeakReservations() {
        try (var cache = new SharedTileCache(1, 1)) {
            for (Executor executor : new Executor[]{task -> { throw new RejectedExecutionException(); }, Runnable::run}) {
                try (var lease = cache.acquire(key(0), 1, () -> new byte[0], executor)) {
                    assertThrows(CompletionException.class, () -> lease.value().toCompletableFuture().join());
                }
                assertEquals(0, cache.reservedBytes()); assertEquals(0, cache.entryCount());
            }
        }
    }
    @Test void executorThrowAfterRunningCannotOverwriteSuccessfulProduction() {
        try (var cache = new SharedTileCache(1, 1);
             var lease = cache.acquire(key(0), 1, () -> new byte[]{9}, task -> { task.run(); throw new RejectedExecutionException(); })) {
            assertArrayEquals(new byte[]{9}, lease.value().toCompletableFuture().join());
        }
    }
    @Test void zeroByteEntriesRemainEntryBoundedAndPayloadIdentityMustMatch() {
        try (var cache = new SharedTileCache(0, 1); var lease = cache.acquire(key(0), 0, () -> new byte[0], Runnable::run)) {
            assertThrows(RejectedExecutionException.class, () -> cache.acquire(key(1), 0, () -> new byte[0], Runnable::run));
            assertThrows(RejectedExecutionException.class, () -> cache.acquire(key(1), 1, () -> new byte[1], Runnable::run));
            assertThrows(IllegalArgumentException.class, () -> cache.acquire(key(0), 1, () -> new byte[1], Runnable::run));
            assertEquals(0, cache.reservedBytes());
        }
    }
    @Test void leastRecentlyUsedUnpinnedEntryIsEvicted() {
        AtomicInteger produced = new AtomicInteger();
        try (var cache = new SharedTileCache(2, 2)) {
            cache.acquire(key(0), 1, () -> new byte[1], Runnable::run).close();
            cache.acquire(key(1), 1, () -> new byte[1], Runnable::run).close();
            cache.acquire(key(0), 1, () -> { fail("Key 0 should remain"); return null; }, Runnable::run).close();
            cache.acquire(key(2), 1, () -> new byte[1], Runnable::run).close();
            cache.acquire(key(1), 1, () -> { produced.incrementAndGet(); return new byte[1]; }, Runnable::run).close();
            assertEquals(1, produced.get());
        }
    }
    @Test void concurrentAcquisitionUsesOneProducerAndEveryLeaseReleasesOnce() throws Exception {
        ManualExecutor executor = new ManualExecutor(); AtomicInteger produced = new AtomicInteger();
        try (var cache = new SharedTileCache(8, 1); ExecutorService pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<SharedTileCache.Lease>>();
            for (int i = 0; i < 64; i++) futures.add(pool.submit(() -> cache.acquire(key(0), 8, () -> { produced.incrementAndGet(); return new byte[8]; }, executor)));
            var leases = new ArrayList<SharedTileCache.Lease>(); for (var future : futures) leases.add(future.get());
            assertEquals(1, executor.pending.size()); executor.runNext(); assertEquals(1, produced.get());
            for (var lease : leases) { assertEquals(8, lease.value().toCompletableFuture().join().length); lease.close(); lease.close(); }
            cache.close(); assertEquals(0, cache.reservedBytes()); assertEquals(0, cache.entryCount());
        }
    }
}
