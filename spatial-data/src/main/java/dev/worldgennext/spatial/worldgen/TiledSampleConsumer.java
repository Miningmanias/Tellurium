// SPDX-License-Identifier: MIT
package dev.worldgennext.spatial.worldgen;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demand-driven consumer for bounded spatial windows.
 *
 * <p>A consumer request is split into small exact sub-extents.  It never
 * turns a large regional demand into one producer ticket, and it limits the
 * number of producer requests that can be in flight at once.  Each tile is
 * still acquired through the supplied {@link SpatialSampleStore}, so two
 * consumers share one producer per tile while keeping independent leases.</p>
 */
public final class TiledSampleConsumer implements AutoCloseable {
    private static final int DEFAULT_MAXIMUM_TILES = 1_000_000;
    private final SpatialSampleStore store;
    private final int tileSize;
    private final int maximumInFlight;
    private final int maximumTiles;
    private final Executor continuationExecutor;
    private final Object lifecycleLock = new Object();
    private final Set<CancelableState> activeStates = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Uses one bounded tile request at a time and a common completion pool. */
    public TiledSampleConsumer(SpatialSampleStore store, int tileSize) {
        this(store, tileSize, 1, DEFAULT_MAXIMUM_TILES, ForkJoinPool.commonPool());
    }

    public TiledSampleConsumer(SpatialSampleStore store, int tileSize,
                               int maximumInFlight, Executor continuationExecutor) {
        this(store, tileSize, maximumInFlight, DEFAULT_MAXIMUM_TILES, continuationExecutor);
    }

    public TiledSampleConsumer(SpatialSampleStore store, int tileSize,
                               int maximumInFlight, int maximumTiles,
                               Executor continuationExecutor) {
        this.store = Objects.requireNonNull(store, "store");
        if (tileSize <= 0) throw new IllegalArgumentException("Positive spatial tile size required");
        if (maximumInFlight <= 0) throw new IllegalArgumentException("Positive in-flight tile bound required");
        if (maximumTiles <= 0) throw new IllegalArgumentException("Positive tile count bound required");
        this.tileSize = tileSize;
        this.maximumInFlight = maximumInFlight;
        this.maximumTiles = maximumTiles;
        this.continuationExecutor = new TrampolineExecutor(
                Objects.requireNonNull(continuationExecutor, "continuationExecutor"));
    }

    public int tileSize() { return tileSize; }
    public int maximumInFlight() { return maximumInFlight; }
    public int maximumTiles() { return maximumTiles; }
    public int activeRequests() { return activeStates.size(); }
    public boolean isClosed() { return closed.get(); }

    public CompletionStage<SampleWindow> acquire(SampleKey key, SampleProducer producer) {
        Objects.requireNonNull(producer, "producer");
        return acquireInternal(key, producer, (tile, value) -> store.acquire(tile, value));
    }

    public CompletionStage<SampleWindow> acquireAsync(SampleKey key, AsyncSampleProducer producer) {
        Objects.requireNonNull(producer, "producer");
        return acquireInternal(key, producer, (tile, value) -> store.acquireAsync(tile, value));
    }

    private <P> CompletionStage<SampleWindow> acquireInternal(
            SampleKey key, P producer, TileAcquire<P> acquire) {
        Objects.requireNonNull(key, "key");
        if (closed.get()) return CompletableFuture.failedFuture(
                new IllegalStateException("Spatial consumer is closed"));
        final List<SampleExtent> tiles;
        try {
            tiles = split(key);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }

        State<P> state = new State<>(key, producer, tiles, acquire);
        synchronized (lifecycleLock) {
            if (closed.get()) return CompletableFuture.failedFuture(
                    new IllegalStateException("Spatial consumer is closed"));
            activeStates.add(state);
            state.result.whenComplete((ignored, failure) -> activeStates.remove(state));
        }
        state.start();
        return state.result;
    }

    /**
     * Cancels every still-pending demand and prevents new tile windows from
     * being admitted.  Completed windows remain caller-owned and are not
     * closed here; their explicit {@link SampleWindow#close()} still controls
     * the retained child leases.
     */
    @Override
    public void close() {
        List<CancelableState> cancel;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return;
            cancel = List.copyOf(activeStates);
        }
        cancel.forEach(CancelableState::cancel);
    }

    private List<SampleExtent> split(SampleKey key) {
        SampleExtent requested = key.requestedExtent();
        validateDomainExtent(key.domain(), requested);
        long xTiles = tilesAlong(requested.width());
        long yTiles = tilesAlong(requested.height());
        long zTiles = tilesAlong(requested.depth());
        long count;
        try {
            count = Math.multiplyExact(Math.multiplyExact(xTiles, yTiles), zTiles);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Spatial tile count overflows", overflow);
        }
        if (count > maximumTiles) {
            throw new IllegalArgumentException("Spatial demand exceeds bounded tile count: " + count);
        }

        var result = new ArrayList<SampleExtent>(Math.toIntExact(count));
        for (int minY = requested.minY(); minY < requested.maxYExclusive();) {
            int maxY = end(minY, requested.maxYExclusive());
            for (int minZ = requested.minZ(); minZ < requested.maxZExclusive();) {
                int maxZ = end(minZ, requested.maxZExclusive());
                for (int minX = requested.minX(); minX < requested.maxXExclusive();) {
                    int maxX = end(minX, requested.maxXExclusive());
                    result.add(new SampleExtent(minX, minY, minZ, maxX, maxY, maxZ));
                    if (maxX == requested.maxXExclusive()) break;
                    minX = maxX;
                }
                if (maxZ == requested.maxZExclusive()) break;
                minZ = maxZ;
            }
            if (maxY == requested.maxYExclusive()) break;
            minY = maxY;
        }
        return List.copyOf(result);
    }

    private long tilesAlong(int length) {
        return ((long) length + tileSize - 1L) / tileSize;
    }

    private int end(int start, int exclusiveLimit) {
        return (int) Math.min((long) exclusiveLimit, (long) start + tileSize);
    }

    private static void validateDomainExtent(SampleDomain domain, SampleExtent extent) {
        if (domain == SampleDomain.COLUMN && (extent.width() != 1 || extent.depth() != 1)) {
            throw new IllegalArgumentException("Column samples require one X/Z column");
        }
        if (domain == SampleDomain.SURFACE_COLUMN && extent.height() != 1) {
            throw new IllegalArgumentException("Surface columns require a one-level extent");
        }
    }

    @FunctionalInterface
    private interface TileAcquire<P> {
        CompletionStage<SampleLease> acquire(SampleKey key, P producer);
    }

    @FunctionalInterface
    private interface CancelableState {
        void cancel();
    }

    /** Prevents an inline executor from recursively walking an entire window. */
    private static final class TrampolineExecutor implements Executor {
        private final Executor delegate;
        private final ThreadLocal<ArrayDeque<Runnable>> pending =
                ThreadLocal.withInitial(ArrayDeque::new);
        private final ThreadLocal<Boolean> draining =
                ThreadLocal.withInitial(() -> false);

        private TrampolineExecutor(Executor delegate) { this.delegate = delegate; }

        @Override
        public void execute(Runnable task) {
            Objects.requireNonNull(task, "task");
            ArrayDeque<Runnable> queue = pending.get();
            queue.addLast(task);
            if (draining.get()) return;
            draining.set(true);
            try {
                Runnable next;
                while ((next = queue.pollFirst()) != null) delegate.execute(next);
            } finally {
                queue.clear();
                draining.set(false);
            }
        }
    }

    private final class State<P> implements CancelableState {
        private final SampleKey key;
        private final P producer;
        private final List<SampleExtent> tiles;
        private final TileAcquire<P> acquire;
        private final CompletableFuture<SampleWindow> result;
        private final Object lock = new Object();
        private final List<SampleLease> leases = new ArrayList<>();
        private final List<CompletableFuture<SampleLease>> pending = new ArrayList<>();
        private final double[] values;
        private int nextTile;
        private int active;
        private int completed;
        private boolean stopped;

        private State(SampleKey key, P producer, List<SampleExtent> tiles,
                      TileAcquire<P> acquire) {
            this.key = key;
            this.producer = producer;
            this.tiles = tiles;
            this.acquire = acquire;
            this.values = new double[key.requestedExtent().volume()];
            this.result = new CompletableFuture<>() {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    boolean cancelled = super.cancel(mayInterruptIfRunning);
                    if (cancelled) stop(null);
                    return cancelled;
                }
            };
        }

        private void start() {
            synchronized (lock) {
                fillLocked();
            }
        }

        @Override
        public void cancel() {
            result.cancel(false);
        }

        private void fillLocked() {
            while (!stopped && active < maximumInFlight && nextTile < tiles.size()) {
                int index = nextTile++;
                active++;
                startTile(index, tileKey(tiles.get(index)));
            }
            if (!stopped && completed == tiles.size() && active == 0) {
                stopped = true;
                result.complete(new SampleWindow(key, key.requestedExtent(), values, leases));
            }
        }

        private void startTile(int index, SampleKey tileKey) {
            CompletionStage<SampleLease> stage;
            try {
                stage = Objects.requireNonNull(acquire.acquire(tileKey, producer), "sample store returned no stage");
            } catch (Throwable failure) {
                finishTile(index, null, failure);
                return;
            }
            CompletableFuture<SampleLease> future;
            try {
                future = stage.toCompletableFuture();
            } catch (Throwable failure) {
                finishTile(index, null, failure);
                return;
            }
            synchronized (lock) {
                if (stopped) {
                    future.cancel(false);
                    return;
                }
                pending.add(future);
            }
            try {
                future.whenCompleteAsync((lease, failure) -> finishTile(index, lease, failure),
                        continuationExecutor);
            } catch (Throwable registrationFailure) {
                finishTile(index, null, registrationFailure);
            }
        }

        private void finishTile(int index, SampleLease lease, Throwable failure) {
            List<CompletableFuture<SampleLease>> cancel = List.of();
            Throwable terminalFailure = failure;
            synchronized (lock) {
                if (stopped) {
                    if (lease != null) lease.close();
                    return;
                }
                active--;
                if (terminalFailure == null && lease == null) {
                    terminalFailure = new NullPointerException("sample store completed without a lease");
                }
                if (terminalFailure == null) {
                    try {
                        SampleExtent tile = tiles.get(index);
                        if (!lease.key().requestedExtent().equals(tile)
                                || lease.size() != tile.volume()) {
                            throw new IllegalArgumentException("Sample store returned a lease for the wrong tile");
                        }
                        copy(lease, tile);
                        leases.add(lease);
                        completed++;
                    } catch (Throwable invalidResult) {
                        terminalFailure = invalidResult;
                        if (lease != null) lease.close();
                    }
                } else if (lease != null) {
                    lease.close();
                }
                if (terminalFailure != null) {
                    stopped = true;
                    cancel = List.copyOf(pending);
                    pending.clear();
                } else {
                    fillLocked();
                }
            }
            if (terminalFailure != null) {
                cancel.forEach(future -> future.cancel(false));
                closeLeases();
                result.completeExceptionally(unwrap(terminalFailure));
            }
        }

        private void copy(SampleLease lease, SampleExtent tile) {
            for (int y = tile.minY(); y < tile.maxYExclusive(); y++) {
                for (int z = tile.minZ(); z < tile.maxZExclusive(); z++) {
                    for (int x = tile.minX(); x < tile.maxXExclusive(); x++) {
                        values[key.requestedExtent().index(x, y, z)] = lease.value(x, y, z);
                    }
                }
            }
        }

        private SampleKey tileKey(SampleExtent extent) {
            return new SampleKey(key.nodeIdentity(), key.domain(), extent, 0,
                    key.contextIdentity(), key.worldEpoch());
        }

        private void stop(Throwable ignored) {
            List<CompletableFuture<SampleLease>> cancel;
            synchronized (lock) {
                if (stopped) return;
                stopped = true;
                cancel = List.copyOf(pending);
                pending.clear();
            }
            cancel.forEach(future -> future.cancel(false));
            closeLeases();
        }

        private void closeLeases() {
            List<SampleLease> close;
            synchronized (lock) {
                close = List.copyOf(leases);
                leases.clear();
            }
            close.forEach(SampleLease::close);
        }

        private Throwable unwrap(Throwable failure) {
            if (failure instanceof java.util.concurrent.CompletionException completion
                    && completion.getCause() != null) return completion.getCause();
            if (failure instanceof java.util.concurrent.ExecutionException execution
                    && execution.getCause() != null) return execution.getCause();
            return failure;
        }
    }
}
