// SPDX-License-Identifier: MIT
package dev.tellurium.engine;

import dev.tellurium.engine.worldgen.*;
import dev.tellurium.material.chunk.BlockStateTable;
import dev.tellurium.material.chunk.ChunkNoiseResult;
import dev.tellurium.material.chunk.ChunkResultHeader;
import dev.tellurium.semantic.execution.ExecutionReceipt;
import dev.tellurium.semantic.identity.ContextIdentity;
import dev.tellurium.semantic.program.NumericProfile;
import dev.tellurium.semantic.program.EvaluationDomain;
import dev.tellurium.semantic.program.WorldgenProgram;
import dev.tellurium.semantic.snapshot.RegistrySnapshot;
import dev.tellurium.semantic.snapshot.WorldgenSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorldgenCoordinatorTest {
    @Test
    void workKeyIncludesSpatialExtentAndEvaluationDomain() {
        ContextIdentity context = context();
        WorkKey block = new WorkKey(context, 0, 0, GenerationStage.NOISE, "noise", -64, 384,
                EvaluationDomain.BLOCK);
        WorkKey column = new WorkKey(context, 0, 0, GenerationStage.NOISE, "noise", -64, 384,
                EvaluationDomain.COLUMN);
        WorkKey shorter = new WorkKey(context, 0, 0, GenerationStage.NOISE, "noise", -64, 16,
                EvaluationDomain.BLOCK);

        assertNotEquals(block, column);
        assertNotEquals(block, shorter);
        assertEquals(-64, block.minY());
        assertEquals(320, block.maxYExclusive());
        assertEquals(context.programHash(), block.graphIdentity());
        assertEquals(context.dynamicInputs(), block.dynamicInputs());
        assertEquals(context.worldEpoch(), block.worldEpoch());
        assertThrows(IllegalArgumentException.class,
                () -> new WorkKey(context, 0, 0, GenerationStage.NOISE, "noise", Integer.MAX_VALUE, 2,
                        EvaluationDomain.BLOCK));
    }

    @Test
    void fairQueueUsesPriorityAndSharedWorkReleasesResources() {
        ContextIdentity context = context();
        GenerationRequest low = request(context, 0);
        GenerationRequest high = request(context, 3);
        FairWorkQueue queue = new FairWorkQueue(2);
        assertTrue(queue.offer(low));
        assertTrue(queue.offer(high));
        assertEquals(high, queue.poll());
        assertEquals(low, queue.poll());
        assertTrue(queue.isEmpty());

        ChunkNoiseResult result = result(context);
        ExecutionReceipt receipt = receipt(context);
        ResourceAdmission admission = new ResourceAdmission(1_000);
        CommitCoordinator commits = new CommitCoordinator((token, value, execution) ->
                new CommitReceipt(true, "committed", token.expectedRevision(), execution.committed(1)));
        StageExecutor stage = (request, reservation) -> CompletableFuture.completedFuture(
                new BackendResult(ExecutionRoute.CPU_PLANNED, receipt, result));
        WorldgenCoordinator coordinator = new WorldgenCoordinator(Runnable::run, admission, stage, commits, 2);

        try {
            WorkRecord.Terminal terminal = coordinator.submit(request(context, 0)).completion().toCompletableFuture().join();
            assertEquals(WorkRecord.State.COMMITTED, terminal.state(), terminal.detail());
            assertEquals(0, admission.usedBytes());
            assertEquals(0, coordinator.retainedRecords());
            assertEquals(1, coordinator.counters().committed());
            assertEquals(1, coordinator.counters().cpuOwned());
            assertEquals(0, coordinator.counters().recovery());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void requestSpecificHandlersShareTheCoordinatorWithoutCrossPublishing() {
        ContextIdentity context = context();
        AtomicInteger stageCalls = new AtomicInteger();
        AtomicInteger commitCalls = new AtomicInteger();
        StageExecutor stage = (request, reservation) -> {
            stageCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new BackendResult(
                    ExecutionRoute.CPU_PLANNED, receipt(context), result(context)));
        };
        CommitCoordinator commits = new CommitCoordinator((token, value, execution) -> {
            commitCalls.incrementAndGet();
            return new CommitReceipt(true, "request-bound commit", token.expectedRevision(), execution.committed(execution.validated()));
        });
        var coordinator = new WorldgenCoordinator(Runnable::run, new ResourceAdmission(1_000),
                (request, reservation) -> CompletableFuture.failedFuture(
                        new AssertionError("default handler must not run")),
                new CommitCoordinator((token, value, execution) -> {
                    throw new AssertionError("default commit handler must not run");
                }), 2);
        try {
            WorkRecord.Terminal terminal = coordinator.submit(request(context, 0), stage, commits)
                    .completion().toCompletableFuture().join();
            assertEquals(WorkRecord.State.COMMITTED, terminal.state(), terminal.detail());
            assertEquals(1, stageCalls.get());
            assertEquals(1, commitCalls.get());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void backendCompletionCanBeBoundToAnAuthoritativeMailbox() {
        ContextIdentity context = context();
        AtomicReference<Runnable> mailbox = new AtomicReference<>();
        AtomicInteger commitCalls = new AtomicInteger();
        CommitCoordinator commits = new CommitCoordinator((token, value, execution) -> {
            commitCalls.incrementAndGet();
            return new CommitReceipt(true, "mailbox commit", token.expectedRevision(), execution.committed(execution.validated()));
        });
        StageExecutor stage = (request, reservation) -> CompletableFuture.completedFuture(
                new BackendResult(ExecutionRoute.CPU_PLANNED, receipt(context), result(context)));
        var coordinator = new WorldgenCoordinator(Runnable::run, new ResourceAdmission(1_000), stage, commits, 2);
        coordinator.bindCompletionExecutor(command -> {
            if (!mailbox.compareAndSet(null, command)) throw new AssertionError("completion mailbox reused before drain");
        });
        try {
            var completion = coordinator.submit(request(context, 0)).completion().toCompletableFuture();
            assertFalse(completion.isDone(), "publication must wait for the authoritative mailbox");
            assertEquals(0, commitCalls.get());
            Runnable publication = mailbox.getAndSet(null);
            assertNotNull(publication);
            publication.run();
            assertEquals(WorkRecord.State.COMMITTED, completion.join().state());
            assertEquals(1, commitCalls.get());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void gpuRequiredAndReceiptRouteCannotBeRelabelledByTheBackend() {
        ContextIdentity context = context();
        ExecutionReceipt cpuReceipt = receipt(context);
        StageExecutor stage = (request, reservation) -> CompletableFuture.completedFuture(
                new BackendResult(ExecutionRoute.CPU_PLANNED, cpuReceipt, result(context)));
        var coordinator = new WorldgenCoordinator(Runnable::run, new ResourceAdmission(1_000), stage,
                new CommitCoordinator((token, value, execution) -> new CommitReceipt(true, "committed", token.expectedRevision(), execution.committed(execution.validated()))), 2);
        try {
            var request = new GenerationRequest(new WorkKey(context, 0, 0, GenerationStage.NOISE, "noise"), "owner", 0, 8, true);
            var terminal = coordinator.submit(request).completion().toCompletableFuture().join();
            assertEquals(WorkRecord.State.FAILED, terminal.state());
            assertTrue(terminal.detail().contains("GPU-required"));
        } finally {
            coordinator.close();
        }
    }

    @Test
    void gpuRouteWithoutCompiledArtifactsFailsBeforeCommit() {
        ContextIdentity context = ContextIdentity.of(
                WorldgenSnapshot.builder(43L, "minecraft:overworld").build(),
                WorldgenProgram.builder().build(), NumericProfile.GPU_IEEE_BITS,
                "abi-v2", "compiler-v2", 0, 0);
        ExecutionReceipt receipt = ExecutionReceipt.issued("gpu-1", "GPU_IEEE_BITS", context,
                NumericProfile.GPU_IEEE_BITS, context.programHash(), context.abiVersion(), 0, 1)
                .completed(1).validated(1);
        AtomicInteger commits = new AtomicInteger();
        StageExecutor stage = (request, reservation) -> CompletableFuture.completedFuture(
                new BackendResult(ExecutionRoute.GPU, receipt, result(context)));
        var coordinator = new WorldgenCoordinator(Runnable::run, new ResourceAdmission(1_000), stage,
                new CommitCoordinator((token, value, execution) -> {
                    commits.incrementAndGet();
                    return new CommitReceipt(true, "committed", token.expectedRevision(), execution.committed(execution.validated()));
                }), 2);
        try {
            var request = new GenerationRequest(new WorkKey(context, 0, 0, GenerationStage.NOISE, "noise"),
                    "owner", 0, 8, false);
            var terminal = coordinator.submit(request).completion().toCompletableFuture().join();
            assertEquals(WorkRecord.State.FAILED, terminal.state());
            assertTrue(terminal.detail().contains("compiled shader/SPIR-V provenance"));
            assertEquals(0, commits.get());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void cancellingEverySubscriberBeforeDispatchDoesNotConsumeBackendResources() {
        ContextIdentity context = context();
        AtomicReference<Runnable> pending = new AtomicReference<>();
        AtomicInteger executions = new AtomicInteger();
        StageExecutor stage = (request, reservation) -> {
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(new BackendResult(ExecutionRoute.CPU_PLANNED, receipt(context), result(context)));
        };
        var coordinator = new WorldgenCoordinator(pending::set, new ResourceAdmission(1_000), stage,
                new CommitCoordinator((token, value, execution) -> new CommitReceipt(true, "committed", token.expectedRevision(), execution.committed(execution.validated()))), 2);
        try {
            var subscription = coordinator.submit(request(context, 0));
            assertTrue(subscription.cancel());
            pending.get().run();
            assertEquals(0, executions.get());
            assertEquals(0, coordinator.retainedRecords());
            assertEquals(1, coordinator.counters().cancelled());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void conflictingSharedRequestsAreRejectedBeforeASecondSubscriberAttaches() {
        ContextIdentity context = context();
        AtomicReference<Runnable> pending = new AtomicReference<>();
        var coordinator = new WorldgenCoordinator(pending::set, new ResourceAdmission(1_000),
                (request, reservation) -> CompletableFuture.completedFuture(
                        new BackendResult(ExecutionRoute.CPU_PLANNED, receipt(context), result(context))),
                new CommitCoordinator((token, value, execution) ->
                        new CommitReceipt(true, "committed", token.expectedRevision(), execution.committed(execution.validated()))), 2);
        GenerationRequest first = request(context, 0);
        GenerationRequest conflictingOwner = new GenerationRequest(first.key(), "different-owner", 0,
                first.resources(), false);
        try {
            coordinator.submit(first);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> coordinator.submit(conflictingOwner));
            assertTrue(failure.getMessage().contains("ownership token differs"));
            assertEquals(1, coordinator.counters().uniqueWork());
            assertEquals(1, coordinator.counters().rejected());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void authoritativeRevisionIsCarriedIntoTheCommitTokenAndStaleCommitIsDistinct() {
        ContextIdentity context = context();
        AtomicLong observedRevision = new AtomicLong(-1);
        var coordinator = new WorldgenCoordinator(Runnable::run, new ResourceAdmission(1_000),
                (request, reservation) -> CompletableFuture.completedFuture(
                        new BackendResult(ExecutionRoute.CPU_PLANNED, receipt(context), result(context))),
                new CommitCoordinator((token, value, execution) -> {
                    observedRevision.set(token.expectedRevision());
                    return CommitReceipt.rejected("stale holder revision");
                }), 2);
        var request = new GenerationRequest(new WorkKey(context, 0, 0, GenerationStage.NOISE, "noise"),
                "owner", 0, new ResourceEstimate(1, 1, 1, 1, 1), false, 37);
        try {
            var terminal = coordinator.submit(request).completion().toCompletableFuture().join();
            assertEquals(WorkRecord.State.STALE, terminal.state());
            assertEquals(37, observedRevision.get());
            assertEquals(1, coordinator.counters().stale());
            assertEquals(0, coordinator.counters().failed());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void staleCommitExceptionIsClassifiedAsStaleInsteadOfAHealthyFailure() {
        ContextIdentity context = context();
        var coordinator = new WorldgenCoordinator(Runnable::run, new ResourceAdmission(1_000),
                (request, reservation) -> CompletableFuture.completedFuture(
                        new BackendResult(ExecutionRoute.CPU_PLANNED, receipt(context), result(context))),
                new CommitCoordinator((token, value, execution) -> {
                    throw new IllegalStateException("holder ownership changed before commit");
                }), 2);
        try {
            var terminal = coordinator.submit(request(context, 0)).completion().toCompletableFuture().join();
            assertEquals(WorkRecord.State.STALE, terminal.state());
            assertEquals(1, coordinator.counters().stale());
            assertEquals(0, coordinator.counters().failed());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void invalidatingExecutingWorkRetainsItsReservationUntilTheBackendReturns() {
        ContextIdentity context = context();
        var backend = new CompletableFuture<BackendResult>();
        var admission = new ResourceAdmission(100);
        var coordinator = new WorldgenCoordinator(Runnable::run, admission,
                (request, reservation) -> backend,
                new CommitCoordinator((token, value, execution) ->
                        new CommitReceipt(true, "must not publish stale work", token.expectedRevision(), execution.committed(execution.validated()))), 2);
        try {
            var subscription = coordinator.submit(request(context, 0));
            assertEquals(40, admission.usedBytes());
            assertEquals(1, coordinator.invalidateBefore(1, 0));
            assertEquals(40, admission.usedBytes());
            assertFalse(subscription.completion().toCompletableFuture().isDone());

            backend.complete(new BackendResult(ExecutionRoute.CPU_PLANNED, receipt(context), result(context)));
            var terminal = subscription.completion().toCompletableFuture().join();
            assertEquals(WorkRecord.State.STALE, terminal.state());
            assertEquals(0, admission.usedBytes());
            assertEquals(0, coordinator.retainedRecords());
            assertEquals(1, coordinator.counters().stale());
            assertEquals(0, coordinator.counters().committed());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void invalidationPublishesSubscriberCallbacksOutsideTheLifecycleMonitor() throws Exception {
        AtomicReference<Runnable> pending = new AtomicReference<>();
        ContextIdentity context = context();
        var coordinator = new WorldgenCoordinator(pending::set, new ResourceAdmission(1_000),
                (request, reservation) -> CompletableFuture.completedFuture(
                        new BackendResult(ExecutionRoute.CPU_PLANNED, receipt(context), result(context))),
                new CommitCoordinator((token, value, execution) ->
                        new CommitReceipt(true, "committed", token.expectedRevision(), execution.committed(execution.validated()))), 2);
        try {
            var subscription = coordinator.submit(request(context, 0));
            CountDownLatch callbackFinished = new CountDownLatch(1);
            CountDownLatch probeFinished = new CountDownLatch(1);
            AtomicReference<Thread> probeThread = new AtomicReference<>();
            subscription.completion().whenComplete((terminal, failure) -> {
                Thread probe = new Thread(() -> {
                    coordinator.retainedRecords();
                    probeFinished.countDown();
                });
                probeThread.set(probe);
                probe.start();
                try {
                    assertTrue(probeFinished.await(1, TimeUnit.SECONDS),
                            "subscriber callback ran while lifecycle monitor was held");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                } finally {
                    callbackFinished.countDown();
                }
            });

            assertEquals(1, coordinator.invalidateBefore(1, 0));
            assertTrue(callbackFinished.await(1, TimeUnit.SECONDS));
            assertEquals(WorkRecord.State.STALE,
                    subscription.completion().toCompletableFuture().join().state());
            Thread probe = probeThread.get();
            assertNotNull(probe);
            probe.join(1_000);
            assertFalse(probe.isAlive());
        } finally {
            coordinator.close();
        }
    }

    @Test void asynchronousBackendCanCompleteOnTheSameSingleWorkerWithoutDeadlock() throws Exception {
        ContextIdentity context = context();
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        StageExecutor stage = (request, reservation) -> {
            var backend = new CompletableFuture<BackendResult>();
            worker.execute(() -> backend.complete(new BackendResult(ExecutionRoute.CPU_PLANNED,
                    receipt(context), result(context))));
            return backend;
        };
        var coordinator = new WorldgenCoordinator(worker, new ResourceAdmission(1_000), stage,
                new CommitCoordinator((token, value, execution) -> new CommitReceipt(true, "committed", token.expectedRevision(), execution.committed(execution.validated()))), 2);
        try {
            var terminal = coordinator.submit(request(context, 0)).completion()
                    .toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(WorkRecord.State.COMMITTED, terminal.state(), terminal.detail());
            assertEquals(0, coordinator.retainedRecords());
        } finally {
            coordinator.close(java.time.Duration.ofSeconds(2));
        }
    }

    @Test void drainTerminatesQueuedWorkAfterAdmissionCloses() {
        ContextIdentity context = context();
        AtomicReference<Runnable> pending = new AtomicReference<>();
        AtomicInteger executions = new AtomicInteger();
        StageExecutor stage = (request, reservation) -> {
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(new BackendResult(ExecutionRoute.CPU_PLANNED,
                    receipt(context), result(context)));
        };
        var coordinator = new WorldgenCoordinator(pending::set, new ResourceAdmission(1_000), stage,
                new CommitCoordinator((token, value, execution) -> new CommitReceipt(true, "committed", token.expectedRevision(), execution.committed(execution.validated()))), 2);
        var subscription = coordinator.submit(request(context, 0));
        try {
            coordinator.drain(Duration.ofSeconds(1)).toCompletableFuture().join();
            assertEquals(WorkRecord.State.CANCELLED,
                    subscription.completion().toCompletableFuture().join().state());
            assertEquals(0, executions.get());
            assertEquals(0, coordinator.retainedRecords());
            assertEquals(1, coordinator.counters().cancelled());
        } finally {
            coordinator.close();
        }
    }

    @Test void oversizedCloseTimeoutFailsBeforeEnteringDrain() {
        ContextIdentity context = context();
        var coordinator = new WorldgenCoordinator(Runnable::run, new ResourceAdmission(1_000),
                (request, reservation) -> CompletableFuture.completedFuture(
                        new BackendResult(ExecutionRoute.CPU_PLANNED, receipt(context), result(context))),
                new CommitCoordinator((token, value, execution) ->
                        new CommitReceipt(true, "committed", token.expectedRevision(), execution.committed(execution.validated()))), 2);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> coordinator.close(Duration.ofSeconds(Long.MAX_VALUE)));

            // An overflow rejection must not silently transition the
            // coordinator into DRAINING and strand later work.
            var terminal = coordinator.submit(request(context, 0)).completion()
                    .toCompletableFuture().join();
            assertEquals(WorkRecord.State.COMMITTED, terminal.state(), terminal.detail());
        } finally {
            coordinator.close();
        }
    }

    @Test void completedWorkIsReusedWithinTheGenerationAndRetiredOnInvalidation() {
        ContextIdentity context = context();
        AtomicInteger executions = new AtomicInteger();
        var coordinator = new WorldgenCoordinator(Runnable::run, new ResourceAdmission(1_000),
                (request, reservation) -> {
                    executions.incrementAndGet();
                    return CompletableFuture.completedFuture(new BackendResult(
                            ExecutionRoute.CPU_PLANNED, receipt(context), result(context)));
                },
                new CommitCoordinator((token, value, execution) ->
                        new CommitReceipt(true, "committed", token.expectedRevision(), execution.committed(execution.validated()))), 2);
        try {
            GenerationRequest request = request(context, 0);
            assertEquals(WorkRecord.State.COMMITTED,
                    coordinator.submit(request).completion().toCompletableFuture().join().state());

            // The first terminal outcome is still authoritative for this
            // identity; a second subscriber must not launch or commit again.
            assertEquals(WorkRecord.State.COMMITTED,
                    coordinator.submit(request).completion().toCompletableFuture().join().state());
            assertEquals(1, executions.get());
            assertEquals(0, coordinator.retainedRecords());
            assertEquals(1, coordinator.retainedTerminalRecords());

            GenerationRequest conflicting = new GenerationRequest(request.key(), "other-owner", 0,
                    request.resources(), false, request.expectedRevision());
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> coordinator.submit(conflicting));
            assertTrue(failure.getMessage().contains("ownership token differs"));

            // A world-generation advance retires the terminal deduplication
            // record without inventing a stale backend outcome.
            assertEquals(0, coordinator.invalidateBefore(1, 0));
            assertEquals(0, coordinator.retainedTerminalRecords());
        } finally {
            coordinator.close();
        }
    }

    private static GenerationRequest request(ContextIdentity context, int priority) {
        return new GenerationRequest(new WorkKey(context, 0, 0, GenerationStage.NOISE, "noise"), "owner", priority, 8, false);
    }

    private static ContextIdentity context() {
        var snapshot = WorldgenSnapshot.builder(42L, "minecraft:overworld").build();
        return ContextIdentity.of(snapshot, WorldgenProgram.builder().build(), NumericProfile.JAVA_REFERENCE,
                "abi-v2", "compiler-v2", 0, 0);
    }

    private static ChunkNoiseResult result(ContextIdentity context) {
        var table = BlockStateTable.fromRegistry(RegistrySnapshot.minimal());
        String[] states = new String[4096];
        Arrays.fill(states, "minecraft:air");
        return ChunkNoiseResult.ofDense(
                new ChunkResultHeader(0, 0, 0, 16, context.worldKey(), context.registryHash(), "abi-v2"),
                table, states, new int[256], new boolean[4096]);
    }

    private static ExecutionReceipt receipt(ContextIdentity context) {
        return ExecutionReceipt.issued("execution-1", "cpu", context, NumericProfile.JAVA_REFERENCE,
                context.programHash(), "abi-v2", 0, 1).completed(1).validated(1);
    }
}
