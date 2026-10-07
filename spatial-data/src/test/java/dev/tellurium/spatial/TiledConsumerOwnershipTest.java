// SPDX-License-Identifier: MIT
package dev.tellurium.spatial;

import dev.tellurium.semantic.identity.ContextIdentity;
import dev.tellurium.semantic.identity.DynamicInputIdentity;
import dev.tellurium.semantic.program.NumericProfile;
import dev.tellurium.spatial.worldgen.AsyncSampleProducer;
import dev.tellurium.spatial.worldgen.SampleDomain;
import dev.tellurium.spatial.worldgen.SampleExtent;
import dev.tellurium.spatial.worldgen.SampleKey;
import dev.tellurium.spatial.worldgen.SampleLease;
import dev.tellurium.spatial.worldgen.SampleProducer;
import dev.tellurium.spatial.worldgen.SampleWindow;
import dev.tellurium.spatial.worldgen.SpatialSampleStore;
import dev.tellurium.spatial.worldgen.TiledSampleConsumer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who holds a tile's lease when a window does not reach its caller. */
class TiledConsumerOwnershipTest {
    private static final ContextIdentity CONTEXT = new ContextIdentity(
            "snapshot", "registry", "program", NumericProfile.JAVA_REFERENCE,
            "abi", "compiler", 4, DynamicInputIdentity.empty(), 2);

    /** A store whose tiles are completed by the test, and which counts the leases still held. */
    private static final class HandStore implements SpatialSampleStore {
        final AtomicInteger held = new AtomicInteger();
        final List<CompletableFuture<SampleLease>> stages = new ArrayList<>();
        final List<SampleKey> keys = new ArrayList<>();
        boolean completeAtOnce;

        @Override public synchronized CompletionStage<SampleLease> acquire(SampleKey key, SampleProducer producer) {
            var stage = new CompletableFuture<SampleLease>();
            stages.add(stage);
            keys.add(key);
            if (completeAtOnce) stage.complete(lease(key));
            return stage;
        }

        @Override public CompletionStage<SampleLease> acquireAsync(SampleKey key, AsyncSampleProducer producer) {
            return acquire(key, null);
        }

        SampleLease lease(SampleKey key) {
            held.incrementAndGet();
            return new SampleLease(key, new double[key.requestedExtent().volume()], held::decrementAndGet);
        }

        synchronized void complete(int index) {
            SampleLease lease = lease(keys.get(index));
            // A stage the consumer has cancelled takes nothing: the store keeps, and here releases, its lease.
            if (!stages.get(index).complete(lease)) lease.close();
        }
    }

    private static SampleKey key(int width) {
        return new SampleKey("density", SampleDomain.LATTICE, new SampleExtent(0, 0, 0, width, 1, 1), 0, CONTEXT);
    }

    @Test
    void anExecutorThatRefusesTheContinuationFailsTheWindowAndReleasesTheLease() {
        Executor refusing = task -> { throw new RejectedExecutionException("closed"); };
        // The tile is already there when the continuation is registered.
        var ready = new HandStore();
        ready.completeAtOnce = true;
        var early = new TiledSampleConsumer(ready, 1, 1, refusing).acquire(key(1), tile -> new double[1]).toCompletableFuture();
        assertTrue(early.isCompletedExceptionally(), "the window must not stay pending");
        var failure = assertThrows(ExecutionException.class, () -> early.get(1, TimeUnit.SECONDS));
        assertInstanceOf(RejectedExecutionException.class, failure.getCause());
        assertEquals(0, ready.held.get(), "the lease the store handed over is released");

        // The tile arrives afterwards.
        var later = new HandStore();
        var late = new TiledSampleConsumer(later, 1, 1, refusing).acquire(key(1), tile -> new double[1]).toCompletableFuture();
        assertTrue(!late.isDone());
        later.complete(0);
        assertTrue(late.isCompletedExceptionally(), "the window must not stay pending");
        assertEquals(0, later.held.get(), "the lease the store handed over is released");
    }

    @Test
    void aSecondTileRefusedBehindTheFirstStillFinishesTheWindow() {
        // Tiles complete inline, so the second tile's continuation is queued while the first is being handed
        // to the executor; the executor takes the first and refuses everything after it.
        var store = new HandStore();
        store.completeAtOnce = true;
        AtomicInteger calls = new AtomicInteger();
        Executor onceOnly = task -> {
            if (calls.getAndIncrement() > 0) throw new RejectedExecutionException("closed");
            task.run();
        };
        var window = new TiledSampleConsumer(store, 1, 1, onceOnly).acquire(key(3), tile -> new double[1]).toCompletableFuture();
        assertTrue(window.isDone(), "no tile may be left waiting for a continuation that was dropped");
        if (!window.isCompletedExceptionally()) window.join().close();
        assertEquals(0, store.held.get());
    }

    @Test
    void aWindowThatLosesToCancellationIsClosed() throws Exception {
        // The last tile completes on one thread while the request is cancelled on another.  Whichever wins, no
        // lease may stay held once a delivered window has been closed.
        for (int round = 0; round < 3_000; round++) {
            var store = new HandStore();
            var consumer = new TiledSampleConsumer(store, 1, 2, Runnable::run);
            CompletableFuture<SampleWindow> window = consumer.acquire(key(2), tile -> new double[1]).toCompletableFuture();
            store.complete(0);
            var barrier = new CyclicBarrier(2);
            Thread finisher = new Thread(() -> {
                await(barrier);
                store.complete(1);
            });
            finisher.start();
            await(barrier);
            window.cancel(false);
            finisher.join();
            if (!window.isCompletedExceptionally()) window.join().close();
            assertEquals(0, store.held.get(), "round " + round + ": leases held after the window was cancelled or closed");
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
