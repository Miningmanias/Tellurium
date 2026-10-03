// SPDX-License-Identifier: MIT
package dev.worldgennext.spatial;

import dev.worldgennext.semantic.identity.ContextIdentity;
import dev.worldgennext.semantic.identity.DynamicInputIdentity;
import dev.worldgennext.semantic.program.NumericProfile;
import dev.worldgennext.spatial.worldgen.AsyncSampleProducer;
import dev.worldgennext.spatial.worldgen.SampleDomain;
import dev.worldgennext.spatial.worldgen.SampleExtent;
import dev.worldgennext.spatial.worldgen.SampleKey;
import dev.worldgennext.spatial.worldgen.SampleWindow;
import dev.worldgennext.spatial.worldgen.SpatialSampleStore;
import dev.worldgennext.spatial.worldgen.SurfaceColumnAtlas;
import dev.worldgennext.spatial.worldgen.TiledSampleConsumer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class V02TiledConsumerTest {
    private static final ContextIdentity CONTEXT = new ContextIdentity(
            "snapshot", "registry", "program", NumericProfile.JAVA_REFERENCE,
            "abi", "compiler", 4, DynamicInputIdentity.empty(), 2);

    @Test
    void demandIsSplitIntoBoundedTilesAndProducesOneContiguousWindow() {
        SampleKey key = new SampleKey("density", SampleDomain.LATTICE,
                new SampleExtent(-1, -2, 3, 5, 3, 8), 1, CONTEXT);
        AtomicInteger calls = new AtomicInteger();
        try (var store = SpatialSampleStore.bounded(3_136, 64, Runnable::run)) {
            var consumer = new TiledSampleConsumer(store, 2, 2, Runnable::run);
            try (SampleWindow window = consumer.acquire(key, tile -> {
                calls.incrementAndGet();
                SampleExtent extent = tile.requestedExtent();
                double[] values = new double[extent.volume()];
                for (int y = extent.minY(); y < extent.maxYExclusive(); y++) {
                    for (int z = extent.minZ(); z < extent.maxZExclusive(); z++) {
                        for (int x = extent.minX(); x < extent.maxXExclusive(); x++) {
                            values[extent.index(x, y, z)] = x * 10_000.0 + y * 100.0 + z;
                        }
                    }
                }
                return values;
            }).toCompletableFuture().join()) {
                assertEquals(key.requestedExtent(), window.extent());
                assertEquals(key.requestedExtent().volume(), window.size());
                assertEquals(-20_298.0, window.value(-2, -3, 2));
                assertEquals(40_207.0, window.value(4, 2, 7));
                assertFalse(window.isClosed());
            }
            assertTrue(calls.get() > 1, "large demand must not become one giant producer ticket");
            assertEquals(3_136, store.reservedBytes(),
                    "successful tiles remain resident until eviction or store close");
            SampleKey freshKey = new SampleKey("fresh", SampleDomain.LATTICE,
                    key.extent(), key.halo(), CONTEXT);
            try (var fresh = consumer.acquire(freshKey, tile ->
                    new double[tile.requestedExtent().volume()]).toCompletableFuture().join()) {
                assertEquals(key.requestedExtent().volume(), fresh.size());
            }
            assertEquals(3_136, store.reservedBytes());
        }
    }

    @Test
    void cachedTilesAreSharedButEachWindowOwnsAnIndependentLease() {
        SampleKey key = new SampleKey("shared", SampleDomain.LATTICE,
                new SampleExtent(0, 0, 0, 4, 1, 4), 0, CONTEXT);
        AtomicInteger calls = new AtomicInteger();
        try (var store = SpatialSampleStore.bounded(512, 16, Runnable::run)) {
            var consumer = new TiledSampleConsumer(store, 2, 1, Runnable::run);
            SampleWindow first = consumer.acquire(key, tile -> {
                calls.incrementAndGet();
                return new double[tile.requestedExtent().volume()];
            }).toCompletableFuture().join();
            SampleWindow second = consumer.acquire(key, tile -> {
                calls.incrementAndGet();
                return new double[tile.requestedExtent().volume()];
            }).toCompletableFuture().join();
            assertEquals(4, calls.get());
            first.close();
            assertFalse(second.isClosed());
            assertEquals(0, second.value(3, 0, 3));
            second.close();
            assertEquals(128, store.reservedBytes());
        }
    }

    @Test
    void cancellationReleasesPendingTileInterestAndTypedSurfaceTilesHandlePartialEdges() {
        SampleKey key = new SampleKey("async", SampleDomain.LATTICE,
                new SampleExtent(0, 0, 0, 2, 1, 1), 0, CONTEXT);
        ManualExecutor producerExecutor = new ManualExecutor();
        CompletableFuture<double[]> delayed = new CompletableFuture<>();
        try (var store = SpatialSampleStore.bounded(16, 2, producerExecutor)) {
            var consumer = new TiledSampleConsumer(store, 1, 1, Runnable::run);
            var cancelled = consumer.acquireAsync(key, ignored -> delayed).toCompletableFuture();
            assertTrue(cancelled.cancel(false));
            producerExecutor.runNext();
            delayed.completeExceptionally(new IllegalStateException("producer failed after cancellation"));

            var retryStage = consumer.acquire(key, tile ->
                            new double[]{tile.requestedExtent().minX() == 0 ? 23 : 29})
                    .toCompletableFuture();
            producerExecutor.runNext();
            producerExecutor.runNext();
            var retry = retryStage.join();
            try (retry) {
                assertEquals(23, retry.value(0, 0, 0));
                assertEquals(29, retry.value(1, 0, 0));
            }

            SampleKey surfaceKey = new SampleKey("surface", SampleDomain.SURFACE_COLUMN,
                    new SampleExtent(-1, 0, -1, 2, 1, 2), 0, CONTEXT);
            var surfaceConsumer = new TiledSampleConsumer(new dev.worldgennext.spatial.worldgen.UncachedSampleStore(), 2, 2,
                    Runnable::run);
            try (var surface = surfaceConsumer.acquire(surfaceKey, tile -> {
                SampleExtent extent = tile.requestedExtent();
                var heights = new LinkedHashMap<Long, Integer>();
                for (int z = extent.minZ(); z < extent.maxZExclusive(); z++) {
                    for (int x = extent.minX(); x < extent.maxXExclusive(); x++) {
                        heights.put(SurfaceColumnAtlas.key(x, z), x * 100 + z);
                    }
                }
                return dev.worldgennext.spatial.worldgen.SampleProducer.surfaceHeights(
                        ignored -> new SurfaceColumnAtlas(heights)).produce(tile);
            }).toCompletableFuture().join()) {
                assertEquals(-101, surface.value(-1, 0, -1));
                assertEquals(101, surface.value(1, 0, 1));
            }
        }
    }

    @Test
    void closingConsumerCancelsPendingWindowsAndRejectsNewDemand() {
        SampleKey key = new SampleKey("close", SampleDomain.LATTICE,
                new SampleExtent(0, 0, 0, 2, 1, 1), 0, CONTEXT);
        ManualExecutor producerExecutor = new ManualExecutor();
        CompletableFuture<double[]> delayed = new CompletableFuture<>();
        try (var store = SpatialSampleStore.bounded(16, 2, producerExecutor)) {
            var consumer = new TiledSampleConsumer(store, 1, 1, Runnable::run);
            var pending = consumer.acquireAsync(key, ignored -> delayed).toCompletableFuture();
            assertEquals(1, consumer.activeRequests());
            consumer.close();
            assertTrue(pending.isCancelled());
            assertEquals(0, consumer.activeRequests());
            assertTrue(consumer.isClosed());
            assertThrows(java.util.concurrent.CompletionException.class, () -> consumer.acquire(
                    key, ignored -> new double[]{1}).toCompletableFuture().join());
        }
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayBlockingQueue<Runnable> pending = new ArrayBlockingQueue<>(4);

        @Override
        public void execute(Runnable task) {
            if (!pending.offer(task)) throw new IllegalStateException("manual queue full");
        }

        void runNext() {
            Runnable task = pending.poll();
            if (task == null) throw new IllegalStateException("no pending task");
            task.run();
        }
    }
}
