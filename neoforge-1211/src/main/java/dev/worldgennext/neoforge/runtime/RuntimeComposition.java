// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import dev.worldgennext.engine.worldgen.WorldgenCoordinator;
import dev.worldgennext.engine.worldgen.CommitCoordinator;
import dev.worldgennext.engine.worldgen.ResourceAdmission;
import dev.worldgennext.neoforge.compat.CompatibilityRegistry;
import dev.worldgennext.neoforge.config.WorldgenNextConfig;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class RuntimeComposition {
    private final WorldgenNextConfig config;
    private final CompatibilityRegistry compatibility;
    private final NativeDependencyBootstrap nativeBootstrap;
    private final ServerLifecycle serverLifecycle = new ServerLifecycle();
    private final ReloadCoordinator reloadCoordinator = new ReloadCoordinator();
    private final AtomicReference<SaveBarrier> saveBarrier = new AtomicReference<>();
    private final Executor coordinatorExecutor;
    /**
     * One runtime-owned subordinate coordinator. Provider adapters bind their
     * captured backend and commit handlers per request; the queue, budget and
     * invalidation lifecycle stay shared for the whole server runtime.
     */
    private final WorldgenCoordinator coordinator;

    public RuntimeComposition(WorldgenNextConfig config, CompatibilityRegistry compatibility) {
        this(config, compatibility, false);
    }

    /**
     * Reference composition for the isolated, blocking logical verifier only.
     * Dispatch, synchronous backend work and publication stay in the caller's
     * Minecraft NOISE task. Moving even a synchronous backend to a second
     * worker changes when neighboring feature tasks become runnable. This
     * deliberately conservative route is not a production throughput policy.
     */
    public static RuntimeComposition forInlineVerifier(WorldgenNextConfig config,
                                                       CompatibilityRegistry compatibility) {
        return new RuntimeComposition(config, compatibility, true);
    }

    private RuntimeComposition(WorldgenNextConfig config, CompatibilityRegistry compatibility,
                               boolean inlineVerifier) {
        this.config = Objects.requireNonNull(config);
        this.compatibility = Objects.requireNonNull(compatibility);
        // CPU_ONLY may still run an independently qualified CPU-owned
        // generation route.  Admission of a GPU route is checked at the
        // evidence-bearing provider boundary, where the route identity is
        // available; rejecting the hook here would also reject the required
        // no-native CPU product mode.
        this.nativeBootstrap = new NativeDependencyBootstrap(config.mode() != WorldgenNextConfig.Mode.CPU_ONLY);
        this.coordinatorExecutor = inlineVerifier ? Runnable::run : Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "worldgennext-coordinator");
            // Server shutdown explicitly closes this executor.  A daemon
            // fallback prevents a test or abnormal loader teardown from
            // keeping the JVM alive after the authoritative runtime is gone.
            thread.setDaemon(true);
            return thread;
        });
        this.coordinator = new WorldgenCoordinator(
                coordinatorExecutor,
                new ResourceAdmission(config.hostBudgetBytes()),
                (request, reservation) -> CompletableFuture.failedFuture(
                        new IllegalStateException("No backend handler was bound to the request")),
                new CommitCoordinator((token, result, execution) -> {
                    throw new IllegalStateException("No commit handler was bound to the request");
                }),
                config.queueCapacity());
        reloadCoordinator.onInvalidation(invalidation -> coordinator.invalidateBefore(
                invalidation.current().worldEpoch(), invalidation.current().deviceGeneration()));
    }

    public WorldgenNextConfig config() { return config; }
    public CompatibilityRegistry compatibility() { return compatibility; }
    public NativeDependencyBootstrap nativeBootstrap() { return nativeBootstrap; }
    public ServerLifecycle serverLifecycle() { return serverLifecycle; }
    public ReloadCoordinator reloadCoordinator() { return reloadCoordinator; }
    public WorldgenCoordinator coordinator() { return coordinator; }

    /**
     * Routes backend completion, validation and Minecraft publication through
     * the authoritative loader mailbox. The coordinator's own executor still
     * owns admission and backend dispatch.
     */
    public synchronized void bindAuthoritativeExecutor(Executor executor) {
        coordinator.bindCompletionExecutor(executor);
    }
    public SaveBarrier currentSaveBarrier() { return saveBarrier.get(); }
    public boolean initializeNativeIfAllowed(BooleanSupplier initializer) { return nativeBootstrap.initialize(initializer); }
    public boolean canInitializeNative() { return config.mode() != WorldgenNextConfig.Mode.CPU_ONLY; }

    /**
     * Connects a subordinate coordinator to the same invalidation source as
     * the loader runtime.  The returned registration must be closed when the
     * coordinator is dismantled; the coordinator itself remains the owner of
     * its queues and reservations.
     */
    public synchronized AutoCloseable attachCoordinator(WorldgenCoordinator coordinator) {
        Objects.requireNonNull(coordinator, "coordinator");
        return reloadCoordinator.onInvalidation(invalidation -> coordinator.invalidateBefore(
                invalidation.current().worldEpoch(), invalidation.current().deviceGeneration()));
    }

    /** Called by the loader's about-to-start event. */
    public synchronized void serverStarting() { serverLifecycle.start(); }

    /** Called only once the loader reports that the server is ready. */
    public synchronized void serverReady() { serverLifecycle.ready(); }

    /**
     * Marks all captured work stale before allowing the server to stop.  A
     * pending save is failed explicitly; it is never upgraded to SAVED just
     * because the process happened to shut down.
     */
    public synchronized void serverStopping() {
        if (serverLifecycle.state() == ServerLifecycle.State.STOPPED
                || serverLifecycle.state() == ServerLifecycle.State.STOPPING) return;
        invalidateSave("server stopping before save completion");
        RuntimeException invalidationFailure = null;
        try {
            reloadCoordinator.reloadWorld();
        } catch (RuntimeException failure) {
            // The world identity must still be retired when a listener has a
            // bug.  Leaving the lifecycle RUNNING would allow new qualified
            // work to enter after the loader has begun shutting down.
            invalidationFailure = failure;
        }
        serverLifecycle.stop();
        if (invalidationFailure != null) throw invalidationFailure;
    }

    /** Defensive idempotent endpoint for the loader's stopped event. */
    public synchronized void serverStopped() {
        if (serverLifecycle.state() == ServerLifecycle.State.STOPPED) return;
        invalidateSave("server stopped before save completion");
        if (serverLifecycle.state() != ServerLifecycle.State.STOPPING) serverLifecycle.stop();
        serverLifecycle.stopped();
    }

    /**
     * Closes the runtime-owned queue after loader/provider owners have stopped
     * their backend workers. This is separate from {@link #serverStopped()} so
     * the mod can first let a provider release native or compute ownership and
     * then wait for the coordinator's final reservation callbacks.
     */
    public synchronized void closeCoordinator() {
        coordinator.close(config.requestTimeout());
    }

    /** Resource/data reload invalidates the world identity and all old work. */
    public synchronized long worldReloaded() {
        serverLifecycle.requireRunning();
        invalidateSave("world reload invalidated the pending save");
        return reloadCoordinator.reloadWorld();
    }

    /** Device recreation is separate from the world epoch but equally stale for GPU work. */
    public synchronized long deviceLostOrRecreated() {
        if (serverLifecycle.state() == ServerLifecycle.State.STOPPED
                || serverLifecycle.state() == ServerLifecycle.State.STOPPING) {
            throw new IllegalStateException("Cannot recreate a device while server lifecycle is " + serverLifecycle.state());
        }
        return reloadCoordinator.deviceLostOrRecreated();
    }

    public synchronized ReloadCoordinator.Generation requireGeneration() {
        serverLifecycle.requireRunning();
        return reloadCoordinator.generation();
    }

    /**
     * A captured token may be used while the server is starting its initial
     * generation work or is running, and while both world/device generations
     * still match. NeoForge can invoke the NOISE task before ServerStarted.
     */
    public synchronized boolean isCurrent(MinecraftOwnershipToken ownership) {
        if (ownership == null || !serverLifecycle.acceptingGeneration()) return false;
        ReloadCoordinator.Generation current = reloadCoordinator.generation();
        return ownership.worldEpoch() == current.worldEpoch()
                && ownership.context().worldEpoch() == current.worldEpoch()
                && ownership.context().deviceGeneration() == current.deviceGeneration();
    }

    public synchronized boolean canRunQualifiedGeneration(MinecraftOwnershipToken ownership) {
        return config.enableQualifiedHook() && isCurrent(ownership);
    }

    /** Starts one logical save barrier, reusing only an in-flight request. */
    public synchronized SaveBarrier requestSave() {
        serverLifecycle.requireRunning();
        SaveBarrier existing = saveBarrier.get();
        if (existing != null && !existing.terminal()) return existing;
        SaveBarrier next = new SaveBarrier();
        next.request();
        saveBarrier.set(next);
        return next;
    }

    public synchronized void completeSave() {
        SaveBarrier barrier = requirePendingSave();
        barrier.complete();
    }

    public synchronized void failSave(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        SaveBarrier barrier = requirePendingSave();
        barrier.fail(failure);
    }

    private SaveBarrier requirePendingSave() {
        SaveBarrier barrier = saveBarrier.get();
        if (barrier == null || barrier.state() != SaveBarrier.State.REQUESTED) {
            throw new IllegalStateException("No save barrier is pending");
        }
        return barrier;
    }

    private void invalidateSave(String reason) {
        SaveBarrier barrier = saveBarrier.get();
        if (barrier != null && barrier.state() == SaveBarrier.State.REQUESTED) {
            barrier.fail(new IllegalStateException(reason));
        }
    }
}
