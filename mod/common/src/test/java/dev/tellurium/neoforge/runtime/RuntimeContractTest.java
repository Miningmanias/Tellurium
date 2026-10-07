// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import dev.tellurium.neoforge.compat.CompatibilityRegistry;
import dev.tellurium.neoforge.config.TelluriumConfig;
import dev.tellurium.semantic.identity.ContextIdentity;
import dev.tellurium.semantic.identity.DynamicInputIdentity;
import dev.tellurium.semantic.program.NumericProfile;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeContractTest {
    @Test
    void inlineVerifierDispatchDoesNotHandTheNoiseTaskToAnotherThread() {
        var runtime = RuntimeComposition.forInlineVerifier(new TelluriumConfig(
                TelluriumConfig.Mode.CPU_ONLY, 1000, 1000, 2,
                Duration.ofSeconds(1), false), new CompatibilityRegistry());
        var caller = Thread.currentThread();
        var calls = new AtomicInteger();
        var context = ownership().context();
        var key = new dev.tellurium.engine.worldgen.WorkKey(context, 0, 0,
                dev.tellurium.engine.GenerationStage.NOISE, "noise");
        var request = new dev.tellurium.engine.worldgen.GenerationRequest(key, "holder-0", 0, 1, false);
        try {
            var subscription = runtime.coordinator().submit(request, (work, reservation) -> {
                assertSame(caller, Thread.currentThread());
                calls.incrementAndGet();
                return java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("injected inline failure"));
            }, new dev.tellurium.engine.worldgen.CommitCoordinator((token, value, execution) -> {
                throw new AssertionError("failed backend must not commit");
            }));
            assertTrue(subscription.completion().toCompletableFuture().isDone());
            var terminal = subscription.completion().toCompletableFuture().join();
            assertEquals(dev.tellurium.engine.worldgen.WorkRecord.State.FAILED, terminal.state());
            assertTrue(terminal.detail().contains("injected inline failure"));
            assertEquals(1, calls.get());
            assertEquals(0, runtime.coordinator().snapshot().reservedBytes());
            assertEquals(0, runtime.coordinator().retainedRecords());
            assertFalse(runtime.canInitializeNative());
        } finally {
            runtime.closeCoordinator();
        }
    }

    @Test
    void compatibilityRegistrationIsIdempotentButCannotHideAConflictingHook() {
        var registry = new CompatibilityRegistry();
        var qualified = new CompatibilityRegistry.Decision(true, "GPU_IEEE_BITS", "qualified");
        registry.register("vanilla", qualified);
        registry.register("vanilla", qualified);
        assertEquals(qualified, registry.decision("vanilla"));
        assertThrows(IllegalStateException.class, () -> registry.register("vanilla",
                new CompatibilityRegistry.Decision(false, "CPU_ORIGINAL_PLANNED", "competing hook")));
        assertTrue(registry.decision("").reason().contains("No qualified"));
        assertThrows(NullPointerException.class, () -> registry.register("missing", null));
    }

    @Test
    void configRoundTripIsStrictAndCpuOnlyKeepsTheCpuRouteAvailable() {
        var properties = new Properties();
        properties.setProperty("mode", "auto_supported");
        properties.setProperty("enableQualifiedHook", "true");
        var parsed = TelluriumConfig.from(properties);
        assertEquals(TelluriumConfig.Mode.AUTO_SUPPORTED, parsed.mode());
        assertTrue(parsed.enableQualifiedHook());
        assertEquals("", parsed.qualifiedEvidenceFile());
        assertEquals(parsed, TelluriumConfig.from(parsed.toProperties()));

        properties.setProperty("enableQualifiedHook", "yes");
        assertThrows(IllegalArgumentException.class, () -> TelluriumConfig.from(properties));
        assertThrows(NullPointerException.class, () -> new TelluriumConfig(
                TelluriumConfig.Mode.CPU_ONLY, 1, 1, 1, null, false));
        var cpuOnly = new RuntimeComposition(
                new TelluriumConfig(TelluriumConfig.Mode.CPU_ONLY, 1, 1, 1,
                        Duration.ofSeconds(1), true), new CompatibilityRegistry());
        assertTrue(cpuOnly.config().enableQualifiedHook());
        assertFalse(cpuOnly.canInitializeNative());
        assertEquals(NativeDependencyBootstrap.State.DISABLED, cpuOnly.nativeBootstrap().state());

        properties.setProperty("enableQualifiedHook", "true");
        properties.setProperty("mode", "auto");
        assertEquals(TelluriumConfig.Mode.AUTO_SUPPORTED, TelluriumConfig.from(properties).mode());
        properties.setProperty("mode", "auto-supported");
        assertEquals(TelluriumConfig.Mode.AUTO_SUPPORTED, TelluriumConfig.from(properties).mode());
    }

    @Test
    void configCanonicalizesSubMillisecondDeadlinesAndRejectsOverflow() {
        var config = new TelluriumConfig(TelluriumConfig.Mode.CPU_ONLY,
                1, 1, 1, Duration.ofNanos(1), false);
        assertEquals(Duration.ofMillis(1), config.requestTimeout());
        assertEquals(config, TelluriumConfig.from(config.toProperties()));
        assertThrows(IllegalArgumentException.class, () -> new TelluriumConfig(
                TelluriumConfig.Mode.CPU_ONLY, 1, 1, 1,
                Duration.ofSeconds(Long.MAX_VALUE), false));
    }

    @Test
    void heightmapPackingRejectsInvalidOrOverflowingDimensions() {
        assertEquals(1, HeightmapPacking.bitsForHeight(1));
        assertEquals(9, HeightmapPacking.bitsForHeight(256));
        assertThrows(IllegalArgumentException.class, () -> HeightmapPacking.bitsForHeight(0));
        assertThrows(IllegalArgumentException.class, () -> HeightmapPacking.bitsForHeight(Integer.MAX_VALUE));
    }

    @Test
    void cpuOnlyNeverInvokesTheNativeInitializer() {
        var runtime = new RuntimeComposition(new TelluriumConfig(
                TelluriumConfig.Mode.CPU_ONLY, 1, 1, 1, Duration.ofSeconds(1), false),
                new CompatibilityRegistry());
        var initializerCalls = new AtomicInteger();

        assertFalse(runtime.canInitializeNative());
        assertFalse(runtime.initializeNativeIfAllowed(() -> {
            initializerCalls.incrementAndGet();
            return true;
        }));
        assertEquals(0, initializerCalls.get());
        assertEquals(0, runtime.nativeBootstrap().initializationCalls());
        assertEquals(NativeDependencyBootstrap.State.DISABLED, runtime.nativeBootstrap().state());
    }

    @Test
    void gpuRequiredInterceptionNeverReportsCpuRecovery() {
        var config = new TelluriumConfig(TelluriumConfig.Mode.GPU_REQUIRED,
                1, 1, 1, Duration.ofSeconds(1), true);
        var registry = new CompatibilityRegistry();
        registry.register("known", new CompatibilityRegistry.Decision(true, "GPU_IEEE_BITS", "qualified"));
        var interceptor = new GenerationInterceptor(running(new RuntimeComposition(config, registry)));
        var failed = interceptor.intercept("known", new Object(), ownership(),
                (chunk, ownership) -> { throw new IllegalStateException("injected"); });
        assertFalse(failed.intercepted());
        assertEquals("GPU_REQUIRED_FAILED", failed.route());
        assertTrue(failed.reason().contains("recovery is forbidden"));

        var unknown = interceptor.intercept("unknown", new Object(), null, (chunk, ownership) -> null);
        assertEquals("GPU_REQUIRED_FAILED", unknown.route());
        assertTrue(unknown.reason().contains("no CPU fallback"));
    }

    @Test
    void autoOnlyReportsRecoveryAfterAnExplicitRestorationProof() {
        var config = new TelluriumConfig(TelluriumConfig.Mode.AUTO_SUPPORTED,
                1, 1, 1, Duration.ofSeconds(1), true);
        var registry = new CompatibilityRegistry();
        registry.register("known", new CompatibilityRegistry.Decision(true, "GPU_IEEE_BITS", "qualified"));
        var failed = new GenerationInterceptor(running(new RuntimeComposition(config, registry)));
        var original = failed.intercept("known", new Object(), ownership(),
                (chunk, ownership) -> { throw new IllegalStateException("injected"); });
        assertEquals("QUALIFIED_ROUTE_FAILED_UNSAFE", original.route());
        assertTrue(original.reason().contains("refusing original recovery"));

        var recovered = new GenerationInterceptor(running(new RuntimeComposition(config, registry)), (chunk, ownership) -> true);
        var recovery = recovered.intercept("known", new Object(), ownership(),
                (chunk, ownership) -> { throw new IllegalStateException("injected"); });
        assertEquals("CPU_RECOVERY", recovery.route());
        assertTrue(recovery.reason().contains("verified target restoration"));
    }

    @Test
    void qualifiedInterceptionRejectsMissingOwnershipBeforeCallingTheGenerator() {
        var config = new TelluriumConfig(TelluriumConfig.Mode.AUTO_SUPPORTED,
                1, 1, 1, Duration.ofSeconds(1), true);
        var registry = new CompatibilityRegistry();
        registry.register("known", new CompatibilityRegistry.Decision(true, "GPU_IEEE_BITS", "qualified"));
        var calls = new AtomicInteger();
        var decision = new GenerationInterceptor(new RuntimeComposition(config, registry))
                .intercept("known", new Object(), null, (chunk, ownership) -> {
                    calls.incrementAndGet();
                    throw new AssertionError("generator must not run without ownership");
                });
        assertEquals("CPU_ORIGINAL_PLANNED", decision.route());
        assertTrue(decision.reason().contains("ownership token is missing"));
        assertEquals(0, calls.get());
    }

    @Test
    void qualifiedInterceptionRejectsMissingChunkBeforeCallingTheGenerator() {
        var config = new TelluriumConfig(TelluriumConfig.Mode.AUTO_SUPPORTED,
                1, 1, 1, Duration.ofSeconds(1), true);
        var registry = new CompatibilityRegistry();
        registry.register("known", new CompatibilityRegistry.Decision(true, "GPU_IEEE_BITS", "qualified"));
        var calls = new AtomicInteger();
        var decision = new GenerationInterceptor(running(new RuntimeComposition(config, registry)))
                .intercept("known", null, ownership(), (chunk, ownership) -> {
                    calls.incrementAndGet();
                    throw new AssertionError("generator must not run without a chunk");
                });
        assertEquals("CPU_ORIGINAL_PLANNED", decision.route());
        assertTrue(decision.reason().contains("chunk is missing"));
        assertEquals(0, calls.get());
    }

    @Test
    void generationThatCrossesAnEpochBoundaryCannotBeReportedAsIntercepted() {
        var config = new TelluriumConfig(TelluriumConfig.Mode.AUTO_SUPPORTED,
                1, 1, 1, Duration.ofSeconds(1), true);
        var registry = new CompatibilityRegistry();
        registry.register("known", new CompatibilityRegistry.Decision(true, "GPU_IEEE_BITS", "qualified"));
        var runtime = running(new RuntimeComposition(config, registry));
        var decision = new GenerationInterceptor(runtime).intercept("known", new Object(), ownership(),
                (chunk, token) -> {
                    runtime.worldReloaded();
                    return emptyResult(token.context());
                });
        assertFalse(decision.intercepted());
        assertEquals("CPU_ORIGINAL_PLANNED", decision.route());
        assertTrue(decision.reason().contains("became stale"));
    }

    @Test
    void restorationProofFailureIsClassifiedAsUnsafe() {
        var config = new TelluriumConfig(TelluriumConfig.Mode.AUTO_SUPPORTED,
                1, 1, 1, Duration.ofSeconds(1), true);
        var registry = new CompatibilityRegistry();
        registry.register("known", new CompatibilityRegistry.Decision(true, "GPU_IEEE_BITS", "qualified"));
        var interceptor = new GenerationInterceptor(running(new RuntimeComposition(config, registry)),
                (chunk, token) -> { throw new IllegalStateException("proof failed"); });
        var decision = interceptor.intercept("known", new Object(), ownership(),
                (chunk, token) -> { throw new IllegalStateException("backend failed"); });
        assertFalse(decision.intercepted());
        assertEquals("QUALIFIED_ROUTE_FAILED_UNSAFE", decision.route());
        assertTrue(decision.reason().contains("restoration proof"));
        assertTrue(decision.reason().contains("proof failed"));
    }

    @Test
    void runtimeLifecycleRejectsStaleQualifiedWorkAndFailsPendingSave() {
        var config = new TelluriumConfig(TelluriumConfig.Mode.AUTO_SUPPORTED,
                1, 1, 1, Duration.ofSeconds(1), true);
        var registry = new CompatibilityRegistry();
        registry.register("known", new CompatibilityRegistry.Decision(true, "GPU_IEEE_BITS", "qualified"));
        var runtime = new RuntimeComposition(config, registry);
        var interceptor = new GenerationInterceptor(runtime);

        var beforeStart = interceptor.intercept("known", new Object(), ownership(0, 0),
                (chunk, token) -> { throw new AssertionError("qualified work must not run before start"); });
        assertEquals("CPU_ORIGINAL_PLANNED", beforeStart.route());
        assertTrue(beforeStart.reason().contains("not running"));

        runtime.serverStarting();
        assertTrue(runtime.isCurrent(ownership(0, 0)),
                "NOISE generation is allowed during ServerAboutToStart");
        runtime.serverReady();
        var current = ownership(0, 0);
        assertTrue(runtime.isCurrent(current));
        var pendingSave = runtime.requestSave();
        assertEquals(SaveBarrier.State.REQUESTED, pendingSave.state());
        assertSame(pendingSave, runtime.requestSave());

        runtime.worldReloaded();
        assertFalse(runtime.isCurrent(current));
        assertEquals(SaveBarrier.State.FAILED, pendingSave.state());
        assertTrue(pendingSave.failure().getMessage().contains("world reload"));

        var stale = interceptor.intercept("known", new Object(), current,
                (chunk, token) -> { throw new AssertionError("stale qualified work must not run"); });
        assertEquals("CPU_ORIGINAL_PLANNED", stale.route());
        assertTrue(stale.reason().contains("stale"));

        var afterDevice = ownership(1, 1);
        runtime.deviceLostOrRecreated();
        assertTrue(runtime.isCurrent(afterDevice));
        runtime.serverStopping();
        assertEquals(ServerLifecycle.State.STOPPING, runtime.serverLifecycle().state());
        runtime.serverStopped();
        assertEquals(ServerLifecycle.State.STOPPED, runtime.serverLifecycle().state());
        assertFalse(runtime.isCurrent(afterDevice));
    }

    @Test
    void reloadListenersObserveBothWorldAndDeviceInvalidationsAndCanBeRemoved() throws Exception {
        var reloads = new ReloadCoordinator();
        var invalidations = new ArrayList<ReloadCoordinator.Invalidation>();
        AutoCloseable registration = reloads.onInvalidation(invalidations::add);
        reloads.reloadWorld();
        reloads.deviceLostOrRecreated();
        assertEquals(2, invalidations.size());
        assertEquals(ReloadCoordinator.Cause.WORLD_RELOAD, invalidations.get(0).cause());
        assertEquals(ReloadCoordinator.Cause.DEVICE_LOST_OR_RECREATED, invalidations.get(1).cause());
        assertFalse(reloads.isCurrent(invalidations.get(0).current()));
        registration.close();
        reloads.reloadWorld();
        assertEquals(2, invalidations.size());
    }

    @Test
    void serverStoppingRetiresTheLifecycleEvenWhenAnInvalidationListenerFails() {
        var runtime = new RuntimeComposition(new TelluriumConfig(
                TelluriumConfig.Mode.AUTO_SUPPORTED, 1, 1, 1,
                Duration.ofSeconds(1), true), new CompatibilityRegistry());
        runtime.serverStarting();
        runtime.serverReady();
        runtime.reloadCoordinator().onInvalidation(invalidation -> {
            throw new IllegalStateException("injected listener failure");
        });

        var failure = assertThrows(IllegalStateException.class, runtime::serverStopping);
        assertTrue(failure.getMessage().contains("injected listener failure"));
        assertEquals(ServerLifecycle.State.STOPPING, runtime.serverLifecycle().state());
        runtime.serverStopped();
        assertEquals(ServerLifecycle.State.STOPPED, runtime.serverLifecycle().state());
    }

    @Test
    void journalRetainsNamedSeamsAndMarksAnyRollbackFailureUnsafe() {
        var order = new ArrayList<String>();
        var journal = new ChunkMutationJournal();
        journal.record("sections", () -> order.add("sections"));
        journal.record("heightmaps", () -> order.add("heightmaps"));
        assertEquals(List.of("sections", "heightmaps"), journal.seams());
        journal.rollback();
        assertEquals(List.of("heightmaps", "sections"), order);
        assertEquals(ChunkMutationJournal.State.ROLLED_BACK, journal.state());
        assertEquals(0, journal.mutationCount());

        var unsafe = new ChunkMutationJournal();
        unsafe.record("counts", () -> { throw new AssertionError("broken undo"); });
        var failure = assertThrows(IllegalStateException.class, unsafe::rollback);
        assertTrue(failure.getMessage().contains("unsafe"));
        assertEquals(ChunkMutationJournal.State.UNSAFE, unsafe.state());
        assertEquals(List.of("counts"), unsafe.seams());
    }

    @Test
    void saveBarrierRequiresARequestedAndSuccessfulCompletion() {
        var barrier = new SaveBarrier();
        assertThrows(IllegalStateException.class, () -> barrier.complete());
        assertThrows(IllegalStateException.class, () -> barrier.fail(new IllegalStateException("early")));
        var future = barrier.request().toCompletableFuture();
        assertFalse(barrier.completed());
        barrier.complete();
        assertTrue(future.isDone());
        assertTrue(barrier.completed());
        assertEquals(SaveBarrier.State.COMPLETED, barrier.state());
        assertThrows(IllegalStateException.class, barrier::request);

        var failed = new SaveBarrier();
        var failedFuture = failed.request().toCompletableFuture();
        failed.fail(new IllegalStateException("flush"));
        assertThrows(CompletionException.class, failedFuture::join);
        assertFalse(failed.completed());
        assertEquals(SaveBarrier.State.FAILED, failed.state());
        assertThrows(IllegalStateException.class, () -> failed.fail(new IllegalStateException("again")));
    }

    @Test
    void saveBarrierTimeoutIsOnlyAnObserverFailure() {
        var barrier = new SaveBarrier();
        assertThrows(IllegalStateException.class, () -> barrier.await(Duration.ofSeconds(1)));
        barrier.request();
        var timedOut = barrier.await(Duration.ofMillis(1)).toCompletableFuture();
        assertThrows(CompletionException.class, timedOut::join);
        assertEquals(SaveBarrier.State.REQUESTED, barrier.state());
        assertFalse(barrier.terminal());

        barrier.complete();
        assertTrue(barrier.await(Duration.ofSeconds(1)).toCompletableFuture().join() == null);
        assertEquals(SaveBarrier.State.COMPLETED, barrier.state());
    }

    @Test
    void saveBarrierKeepsASubMillisecondDeadlinePositive() {
        var barrier = new SaveBarrier();
        barrier.request();
        barrier.complete();
        assertTrue(barrier.await(Duration.ofNanos(1)).toCompletableFuture().join() == null);
    }

    @Test
    void noiseChunkLifecycleRejectsInvalidOrderingAndPreservesPublishedState() {
        var lifecycle = new NoiseChunkLifecycleAdapter();
        assertThrows(IllegalStateException.class, lifecycle::resultReady);
        lifecycle.releaseForCompute();
        lifecycle.resultReady();
        lifecycle.published();
        lifecycle.invalidate();
        assertEquals(NoiseChunkLifecycleAdapter.State.PUBLISHED, lifecycle.state());

        var stale = new NoiseChunkLifecycleAdapter();
        stale.invalidate();
        assertEquals(NoiseChunkLifecycleAdapter.State.INVALIDATED, stale.state());
        assertThrows(IllegalStateException.class, stale::releaseForCompute);
    }

    @Test
    void ownershipDoesNotCrossARecreatedDeviceGeneration() {
        var captured = ownership(0, 0);
        var recreated = ownership(0, 1);

        assertFalse(captured.matches(recreated.context(), recreated.worldEpoch(), recreated.revision(),
                recreated.status(), recreated.ownershipToken()));
        assertTrue(captured.matches(captured.context(), captured.worldEpoch(), captured.revision(),
                captured.status(), captured.ownershipToken()));
    }

    private static MinecraftOwnershipToken ownership() {
        return ownership(0, 0);
    }

    private static MinecraftOwnershipToken ownership(long worldEpoch, long deviceGeneration) {
        var context = new ContextIdentity("snapshot", "registry", "program", NumericProfile.JAVA_REFERENCE,
                "abi", "compiler", worldEpoch, DynamicInputIdentity.empty(), deviceGeneration);
        return new MinecraftOwnershipToken(context, worldEpoch, 0, "BIOMES", "holder-0");
    }

    private static RuntimeComposition running(RuntimeComposition runtime) {
        runtime.serverStarting();
        runtime.serverReady();
        return runtime;
    }

    private static dev.tellurium.material.chunk.ChunkNoiseResult emptyResult(ContextIdentity context) {
        var table = dev.tellurium.material.chunk.BlockStateTable.fromRegistry(
                dev.tellurium.semantic.snapshot.RegistrySnapshot.minimal());
        var states = new String[16 * 256];
        java.util.Arrays.fill(states, "minecraft:air");
        return dev.tellurium.material.chunk.ChunkNoiseResult.ofDense(
                new dev.tellurium.material.chunk.ChunkResultHeader(0, 0, 0, 16,
                        context.worldKey(), table.fingerprint(), "abi"),
                table, states, new int[256], new boolean[states.length]);
    }
}
