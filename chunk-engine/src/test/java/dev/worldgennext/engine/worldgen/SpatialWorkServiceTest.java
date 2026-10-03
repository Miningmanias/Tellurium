// SPDX-License-Identifier: MIT
package dev.worldgennext.engine.worldgen;

import dev.worldgennext.engine.ExecutionRoute;
import dev.worldgennext.semantic.identity.ContextIdentity;
import dev.worldgennext.semantic.identity.DynamicInputIdentity;
import dev.worldgennext.semantic.program.NumericProfile;
import dev.worldgennext.spatial.worldgen.ColumnSamples;
import dev.worldgennext.spatial.worldgen.SampleDomain;
import dev.worldgennext.spatial.worldgen.SampleExtent;
import dev.worldgennext.spatial.worldgen.SampleKey;
import dev.worldgennext.spatial.worldgen.SampleProducer;
import dev.worldgennext.spatial.worldgen.SpatialSampleStore;
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
}
