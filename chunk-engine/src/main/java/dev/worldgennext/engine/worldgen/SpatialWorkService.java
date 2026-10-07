// SPDX-License-Identifier: MIT
package dev.worldgennext.engine.worldgen;

import dev.worldgennext.engine.ExecutionRoute;
import dev.worldgennext.spatial.worldgen.AsyncSampleProducer;
import dev.worldgennext.spatial.worldgen.SampleKey;
import dev.worldgennext.spatial.worldgen.SampleProducer;
import dev.worldgennext.spatial.worldgen.SampleWindow;
import dev.worldgennext.spatial.worldgen.SpatialSampleStore;
import dev.worldgennext.spatial.worldgen.TiledSampleConsumer;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Engine-side composition of static routing and demand-driven sample
 * consumption.  It is a spatial service, not a second Minecraft holder
 * scheduler: the chunk {@link WorldgenCoordinator} still owns chunk work,
 * while this service turns a selected CPU/GPU producer into bounded sample
 * windows.
 */
public final class SpatialWorkService implements AutoCloseable {
    public record Result(ExecutionRoute route, SampleWindow window) implements AutoCloseable {
        public Result {
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(window, "window");
        }

        public SampleKey key() { return window.key(); }
        public int size() { return window.size(); }
        public double value(int x, int y, int z) { return window.value(x, y, z); }
        @Override public void close() { window.close(); }
    }

    private final SpatialSampleStore store;
    private final TiledSampleConsumer consumer;
    private final StaticRoutePolicy routes;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private final Set<CompletableFuture<Result>> activeResults = ConcurrentHashMap.newKeySet();

    /** The service owns the supplied store and closes it with the service. */
    public SpatialWorkService(SpatialSampleStore store, StaticRoutePolicy routes,
                              int tileSize, int maximumInFlight, Executor continuationExecutor) {
        this.store = Objects.requireNonNull(store, "store");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.consumer = new TiledSampleConsumer(store, tileSize, maximumInFlight,
                continuationExecutor);
    }

    /** Uses the common completion pool for bounded tile orchestration. */
    public SpatialWorkService(SpatialSampleStore store, StaticRoutePolicy routes, int tileSize) {
        this(store, routes, tileSize, 1, java.util.concurrent.ForkJoinPool.commonPool());
    }

    public StaticRoutePolicy routes() { return routes; }
    public TiledSampleConsumer consumer() { return consumer; }
    public int activeRequests() { return activeResults.size(); }
    public boolean isClosed() { return closed.get(); }

    /**
     * Selects one fixed route before any producer is called.  Route selection
     * never changes after a producer failure, and a missing selected producer
     * is an explicit failed request rather than a zero-filled sample window.
     */
    public CompletionStage<Result> request(SampleKey key, SampleProducer cpuProducer,
                                           SpatialWorkProducer gpuProducer,
                                           boolean cpuSupported, boolean gpuSupported) {
        return requestSelected(key, cpuProducer, gpuProducer, cpuSupported, gpuSupported);
    }

    public CompletionStage<Result> requestAsync(SampleKey key, AsyncSampleProducer cpuProducer,
                                                SpatialWorkProducer gpuProducer,
                                                boolean cpuSupported, boolean gpuSupported) {
        return requestSelected(key, cpuProducer, gpuProducer, cpuSupported, gpuSupported);
    }

    private <P> CompletionStage<Result> requestSelected(SampleKey key, P cpuProducer,
                                                        SpatialWorkProducer gpuProducer,
                                                        boolean cpuSupported, boolean gpuSupported) {
        Objects.requireNonNull(key, "key");
        synchronized (lifecycleLock) {
            if (closed.get()) return CompletableFuture.failedFuture(
                    new IllegalStateException("Spatial work service is closed"));
        }
        final ExecutionRoute route;
        try {
            route = routes.choose(cpuSupported, gpuSupported);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (route == ExecutionRoute.GPU && gpuProducer == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "GPU route was selected without a GPU spatial producer"));
        }
        if (route != ExecutionRoute.GPU && cpuProducer == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "CPU route was selected without a CPU spatial producer"));
        }

        CompletionStage<SampleWindow> windowStage;
        try {
            windowStage = route == ExecutionRoute.GPU
                    ? consumer.acquireAsync(key, gpuProducer)
                    : cpuProducer instanceof SampleProducer producer
                    ? consumer.acquire(key, producer)
                    : consumer.acquireAsync(key, (AsyncSampleProducer) cpuProducer);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
        CompletableFuture<SampleWindow> source = windowStage.toCompletableFuture();
        CompletableFuture<Result> result = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (cancelled) source.cancel(mayInterruptIfRunning);
                return cancelled;
            }
        };
        synchronized (lifecycleLock) {
            if (closed.get()) {
                result.cancel(false);
                // Cancelling does nothing to a source that has already produced its window; that window,
                // or one still to come, has nobody to receive it.
                source.whenComplete((window, failure) -> {
                    if (window != null) window.close();
                });
                return result;
            }
            activeResults.add(result);
            result.whenComplete((ignored, failure) -> activeResults.remove(result));
        }
        try {
            source.whenComplete((window, failure) -> {
                if (failure != null) result.completeExceptionally(unwrap(failure));
                else if (window == null) result.completeExceptionally(
                        new IllegalStateException("Spatial consumer completed without a window"));
                else if (!result.complete(new Result(route, window))) window.close();
            });
        } catch (Throwable registrationFailure) {
            source.cancel(false);
            result.completeExceptionally(registrationFailure);
        }
        return result;
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof java.util.concurrent.CompletionException completion
                && completion.getCause() != null) return completion.getCause();
        if (failure instanceof java.util.concurrent.ExecutionException execution
                && execution.getCause() != null) return execution.getCause();
        return failure;
    }

    @Override public void close() {
        Set<CompletableFuture<Result>> cancel;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return;
            cancel = Set.copyOf(activeResults);
        }
        cancel.forEach(request -> request.cancel(false));
        consumer.close();
        store.close();
    }
}
