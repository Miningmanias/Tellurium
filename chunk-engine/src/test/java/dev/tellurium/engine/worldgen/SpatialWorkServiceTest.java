// SPDX-License-Identifier: MIT
package dev.tellurium.engine.worldgen;

import dev.tellurium.engine.ExecutionRoute;
import dev.tellurium.semantic.identity.ContextIdentity;
import dev.tellurium.semantic.identity.DynamicInputIdentity;
import dev.tellurium.semantic.program.NumericProfile;
import dev.tellurium.spatial.worldgen.ColumnSamples;
import dev.tellurium.spatial.worldgen.SampleDomain;
import dev.tellurium.spatial.worldgen.SampleExtent;
import dev.tellurium.spatial.worldgen.SampleKey;
import dev.tellurium.spatial.worldgen.SampleProducer;
import dev.tellurium.spatial.worldgen.SpatialSampleStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialWorkServiceTest {
    private static final ContextIdentity CONTEXT = new ContextIdentity(
            "snapshot", "registry", "program", NumericProfile.JAVA_REFERENCE,
            "abi", "compiler", 0, DynamicInputIdentity.empty(), 0);

    @Test
    void staticRouteSelectsOneTypedProducerAndConsumesAColumnWindow() {
        SampleKey key = new SampleKey("temperature", SampleDomain.COLUMN,
                new SampleExtent(3, -2, 7, 4, 4, 8), 0, CONTEXT);
        try (var service = new SpatialWorkService(
                SpatialSampleStore.bounded(128, 8, Runnable::run),
                new StaticRoutePolicy(StaticRoutePolicy.Mode.CPU_ONLY), 2,
                1, Runnable::run)) {
            var result = service.request(key,
                    SampleProducer.column(tile -> {
                        var extent = tile.requestedExtent();
                        var values = new double[extent.height()];
                        for (int y = extent.minY(); y < extent.maxYExclusive(); y++) {
                            values[y - extent.minY()] = 10 + y - key.requestedExtent().minY();
                        }
                        return new ColumnSamples(extent, values);
                    }),
                    null, true, false).toCompletableFuture().join();
            try (result) {
                assertEquals(ExecutionRoute.CPU_PLANNED, result.route());
                assertEquals(6, result.size());
                assertEquals(10, result.value(3, -2, 7));
                assertEquals(15, result.value(3, 3, 7));
            }
        }
    }

    @Test
    void unsupportedRouteAndMissingSelectedProducerFailClosed() {
        SampleKey key = new SampleKey("density", SampleDomain.LATTICE,
                new SampleExtent(0, 0, 0, 1, 1, 1), 0, CONTEXT);
        try (var unsupported = new SpatialWorkService(
                SpatialSampleStore.bounded(8, 1, Runnable::run),
                new StaticRoutePolicy(StaticRoutePolicy.Mode.GPU_REQUIRED), 1)) {
            assertThrows(java.util.concurrent.CompletionException.class, () -> unsupported
                    .request(key, ignored -> new double[]{1}, null, true, false)
                    .toCompletableFuture().join());
        }
        try (var missing = new SpatialWorkService(
                SpatialSampleStore.bounded(8, 1, Runnable::run),
                new StaticRoutePolicy(StaticRoutePolicy.Mode.GPU_REQUIRED), 1)) {
            assertThrows(java.util.concurrent.CompletionException.class, () -> missing
                    .request(key, ignored -> new double[]{1}, null, false, true)
                    .toCompletableFuture().join());
        }
    }

    @Test
    void gpuRequiredMayOmitUnusedCpuProducer() {
        SampleKey key = new SampleKey("density", SampleDomain.COLUMN,
                new SampleExtent(4, 64, 5, 5, 65, 6), 0, CONTEXT);
        try (var service = new SpatialWorkService(
                SpatialSampleStore.bounded(128, 8, Runnable::run),
                new StaticRoutePolicy(StaticRoutePolicy.Mode.GPU_REQUIRED), 1)) {
            var result = service.request(key, null,
                    SpatialWorkProducer.from((SampleProducer) ignored -> new double[]{42.0}),
                    false, true).toCompletableFuture().join();
            try (result) {
                assertEquals(ExecutionRoute.GPU, result.route());
                assertEquals(42.0, result.value(4, 64, 5));
            }
        }
    }

    @Test
    void serviceCloseCancelsPendingSpatialDemandBeforeClosingStore() {
        SampleKey key = new SampleKey("pending", SampleDomain.LATTICE,
                new SampleExtent(0, 0, 0, 1, 1, 1), 0, CONTEXT);
        var pending = new java.util.concurrent.CompletableFuture<double[]>();
        var service = new SpatialWorkService(
                SpatialSampleStore.bounded(8, 1, Runnable::run),
                new StaticRoutePolicy(StaticRoutePolicy.Mode.CPU_ONLY), 1);
        var request = service.requestAsync(key, ignored -> pending, null, true, false)
                .toCompletableFuture();
        assertEquals(1, service.activeRequests());
        service.close();
        assertTrue(request.isCancelled());
        assertTrue(service.isClosed());
        assertEquals(0, service.activeRequests());
    }

    @Test
    void aWindowAcquiredWhileTheServiceClosesIsNotLeftHeld() throws Exception {
        // A request whose tiles are there at once races the service being closed.  Whichever wins, once a
        // delivered result has been closed no lease may still be held.
        SampleKey key = new SampleKey("racing", SampleDomain.LATTICE,
                new SampleExtent(0, 0, 0, 1, 1, 1), 0, CONTEXT);
        for (int round = 0; round < 3_000; round++) {
            var held = new java.util.concurrent.atomic.AtomicInteger();
            SpatialSampleStore store = new SpatialSampleStore() {
                @Override public java.util.concurrent.CompletionStage<dev.tellurium.spatial.worldgen.SampleLease> acquire(
                        SampleKey tile, SampleProducer producer) {
                    held.incrementAndGet();
                    return java.util.concurrent.CompletableFuture.completedFuture(new dev.tellurium.spatial.worldgen.SampleLease(
                            tile, new double[tile.requestedExtent().volume()], held::decrementAndGet));
                }
                @Override public java.util.concurrent.CompletionStage<dev.tellurium.spatial.worldgen.SampleLease> acquireAsync(
                        SampleKey tile, dev.tellurium.spatial.worldgen.AsyncSampleProducer producer) {
                    return acquire(tile, null);
                }
            };
            var service = new SpatialWorkService(store, new StaticRoutePolicy(StaticRoutePolicy.Mode.CPU_ONLY), 1, 1, Runnable::run);
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            Thread closer = new Thread(() -> {
                try {
                    barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                service.close();
            });
            closer.start();
            barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
            var request = service.request(key, ignored -> new double[]{1}, null, true, false).toCompletableFuture();
            closer.join();
            if (!request.isCompletedExceptionally()) request.join().close();
            assertEquals(0, held.get(), "round " + round + ": a lease stayed held after the service closed");
        }
    }
}
