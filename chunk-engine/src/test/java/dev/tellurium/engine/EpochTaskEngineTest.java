// SPDX-License-Identifier: MIT
package dev.tellurium.engine;

import dev.tellurium.material.*;
import dev.tellurium.semantic.WorldgenIdentity;
import dev.tellurium.spatial.ByteBudget;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class EpochTaskEngineTest {
    private static final long BYTES = EpochTaskEngine.MINIMUM_TASK_BYTES;
    private static TaskKey key(int x, long epoch) { return new TaskKey(new WorldgenIdentity(1, "overworld", "graph", epoch), x, 0, GenerationStage.MATERIAL_FIXTURE); }
    private static SectionData badSection() {
        return new SectionData() {
            public int blockStateId(int index) { return 0; }
            public int nonAirCount() { return 1; }
            public long logicalChecksum() { return 0; }
        };
    }
    private static SectionData blockingSection(CountDownLatch entered, CountDownLatch release) {
        SectionData actual = new UniformSection(1);
        return new SectionData() {
            public int blockStateId(int index) {
                if (index == 0) {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                }
                return actual.blockStateId(index);
            }
            public int nonAirCount() { return actual.nonAirCount(); }
            public long logicalChecksum() { return actual.logicalChecksum(); }
        };
    }
    @Test void commitOwnsValidatedSnapshotAndReleasesReservation() {
        ByteBudget budget = new ByteBudget(BYTES);
        try (var engine = new EpochTaskEngine(budget, 0)) {
            var task = engine.submit(key(0, 0), ExecutionRoute.CPU_PLANNED, BYTES);
            int[] mutable = new int[4096]; Arrays.fill(mutable, 42);
            SectionData external = new SectionData() {
                public int blockStateId(int index) { return mutable[index]; }
                public int nonAirCount() { return 4096; }
                public long logicalChecksum() { return LogicalChecksum.of(mutable); }
            };
            assertTrue(task.complete(external)); Arrays.fill(mutable, 0);
            assertEquals(42, task.committedSection().orElseThrow().blockStateId(9));
            assertEquals(EpochTaskEngine.Status.COMMITTED, task.completion().toCompletableFuture().join().status());
            assertEquals(0, budget.usedBytes()); assertEquals(1, engine.counters().cpuCommitted());
            assertEquals(0, engine.counters().gpuCommitted()); assertEquals(0, engine.counters().gpuResultsReceived());
        }
    }
    @Test void malformedOrNullResultFailsBeforeCommit() {
        ByteBudget budget = new ByteBudget(BYTES);
        try (var engine = new EpochTaskEngine(budget, 0)) {
            for (int i = 0; i < 2; i++) {
                var task = engine.submit(key(i, 0), ExecutionRoute.GPU, BYTES);
                assertFalse(task.complete(i == 0 ? badSection() : null));
                assertEquals(EpochTaskEngine.Status.FAILED, task.status()); assertTrue(task.committedSection().isEmpty());
                assertEquals(0, budget.usedBytes());
            }
            assertEquals(2, engine.counters().gpuResultsReceived()); assertEquals(0, engine.counters().gpuCommitted());
            assertEquals(2, engine.counters().failed());
        }
    }
    @Test void duplicateAndLateResultsCannotChangeCommitOrCounters() {
        try (var engine = new EpochTaskEngine(new ByteBudget(BYTES), 0)) {
            var task = engine.submit(key(0, 0), ExecutionRoute.GPU, BYTES);
            assertTrue(task.complete(new UniformSection(3))); assertFalse(task.complete(new UniformSection(7)));
            assertFalse(task.cancel()); assertFalse(task.fail(new IllegalStateException()));
            assertEquals(3, task.committedSection().orElseThrow().blockStateId(0));
            assertEquals(1, engine.counters().gpuResultsReceived()); assertEquals(1, engine.counters().gpuCommitted());
            assertEquals(1, engine.counters().duplicateOrLateCompletions());
            assertEquals(0, engine.counters().cancelled()); assertEquals(0, engine.counters().failed());
        }
    }
    @Test void concurrentCompletionHasExactlyOneWinner() throws Exception {
        try (var engine = new EpochTaskEngine(new ByteBudget(BYTES), 0); ExecutorService pool = Executors.newFixedThreadPool(8)) {
            var task = engine.submit(key(0, 0), ExecutionRoute.GPU, BYTES);
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 32; i++) { int state = i; results.add(pool.submit(() -> task.complete(new UniformSection(state)))); }
            int wins = 0; for (var result : results) if (result.get()) wins++;
            assertEquals(1, wins); assertEquals(1, engine.counters().gpuCommitted());
            assertEquals(1, engine.counters().gpuResultsReceived()); assertEquals(31, engine.counters().duplicateOrLateCompletions());
        }
    }
    @Test void cancellationIsTerminalAndDoesNotCountCpuRecoveryAsGpu() {
        try (var engine = new EpochTaskEngine(new ByteBudget(BYTES), 0)) {
            var cancelled = engine.submit(key(0, 0), ExecutionRoute.GPU, BYTES);
            assertTrue(cancelled.cancel()); assertFalse(cancelled.complete(new UniformSection(0)));
            var recovery = engine.submit(key(1, 0), ExecutionRoute.CPU_RECOVERY, BYTES);
            assertTrue(recovery.complete(new UniformSection(0)));
            assertEquals(1, engine.counters().cancelled()); assertEquals(1, engine.counters().cpuRecoveryPlanned());
            assertEquals(1, engine.counters().cpuRecoveryCommitted()); assertEquals(0, engine.counters().gpuCommitted());
            assertEquals(0, engine.counters().gpuResultsReceived()); assertEquals(1, engine.counters().totalCommitted());
        }
    }
    @Test void pendingEpochAdvanceRejectsStaleTasksAndPermitsNewIdentity() {
        ByteBudget budget = new ByteBudget(BYTES);
        try (var engine = new EpochTaskEngine(budget, 0)) {
            var old = engine.submit(key(0, 0), ExecutionRoute.CPU_PLANNED, BYTES);
            engine.advanceEpoch(1); assertEquals(0, budget.usedBytes());
            assertEquals(EpochTaskEngine.Status.STALE, old.completion().toCompletableFuture().join().status());
            assertFalse(old.complete(new UniformSection(1))); assertEquals(0, engine.retainedTaskRecords());
            assertThrows(RejectedExecutionException.class, () -> engine.submit(key(0, 0), ExecutionRoute.CPU_PLANNED, BYTES));
            assertTrue(engine.submit(key(0, 1), ExecutionRoute.CPU_PLANNED, BYTES).complete(new UniformSection(2)));
            assertThrows(IllegalArgumentException.class, () -> engine.advanceEpoch(1));
            assertEquals(1, engine.counters().stale());
        }
    }
    @Test void cancellationDuringValidationRetainsBudgetUntilValidatorReturns() throws Exception {
        ByteBudget budget = new ByteBudget(BYTES); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var engine = new EpochTaskEngine(budget, 0); ExecutorService pool = Executors.newSingleThreadExecutor()) {
            var task = engine.submit(key(0, 0), ExecutionRoute.GPU, BYTES);
            Future<Boolean> completion = pool.submit(() -> task.complete(blockingSection(entered, release)));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(task.cancel());
                assertEquals(BYTES, budget.usedBytes()); assertEquals(EpochTaskEngine.Status.CANCELLED, task.status());
                assertThrows(RejectedExecutionException.class, () -> engine.submit(key(1, 0), ExecutionRoute.CPU_PLANNED, BYTES));
            } finally { release.countDown(); }
            assertFalse(completion.get()); assertEquals(0, budget.usedBytes()); assertTrue(task.committedSection().isEmpty());
        }
    }
    @Test void epochAdvanceDuringValidationCannotCommitOldResult() throws Exception {
        ByteBudget budget = new ByteBudget(BYTES); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var engine = new EpochTaskEngine(budget, 0); ExecutorService pool = Executors.newSingleThreadExecutor()) {
            var task = engine.submit(key(0, 0), ExecutionRoute.GPU, BYTES);
            Future<Boolean> completion = pool.submit(() -> task.complete(blockingSection(entered, release)));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS)); engine.advanceEpoch(1);
                assertEquals(EpochTaskEngine.Status.STALE, task.status()); assertEquals(BYTES, budget.usedBytes());
                assertThrows(RejectedExecutionException.class, () -> engine.submit(key(0, 1), ExecutionRoute.GPU, BYTES));
            } finally { release.countDown(); }
            assertFalse(completion.get()); assertEquals(0, budget.usedBytes()); assertEquals(0, engine.counters().gpuCommitted());
            assertTrue(engine.submit(key(0, 1), ExecutionRoute.CPU_PLANNED, BYTES).complete(new UniformSection(0)));
        }
    }
    @Test void closedEngineCancelsPendingWorkAndRejectsAdmission() {
        ByteBudget budget = new ByteBudget(BYTES); var engine = new EpochTaskEngine(budget, 0);
        var task = engine.submit(key(0, 0), ExecutionRoute.CPU_PLANNED, BYTES);
        engine.close(); engine.close(); assertEquals(0, budget.usedBytes());
        assertEquals(EpochTaskEngine.Status.CANCELLED, task.status()); assertEquals(1, engine.counters().cancelled());
        assertThrows(RejectedExecutionException.class, () -> engine.submit(key(1, 0), ExecutionRoute.CPU_PLANNED, BYTES));
        assertThrows(IllegalArgumentException.class, () -> engine.advanceEpoch(1));
    }
    @Test void taskRegistryIsBoundedAndRetainsCommitTombstoneUntilEpochChanges() {
        try (var engine = new EpochTaskEngine(new ByteBudget(BYTES), 0, 1)) {
            var task = engine.submit(key(0, 0), ExecutionRoute.CPU_PLANNED, BYTES);
            assertThrows(RejectedExecutionException.class, () -> engine.submit(key(0, 0), ExecutionRoute.CPU_PLANNED, BYTES));
            assertTrue(task.complete(new UniformSection(0)));
            assertThrows(RejectedExecutionException.class, () -> engine.submit(key(0, 0), ExecutionRoute.CPU_PLANNED, BYTES));
            assertThrows(RejectedExecutionException.class, () -> engine.submit(key(1, 0), ExecutionRoute.CPU_PLANNED, BYTES));
            assertEquals(1, engine.retainedTaskRecords()); engine.advanceEpoch(1);
            assertTrue(engine.submit(key(1, 1), ExecutionRoute.CPU_PLANNED, BYTES).cancel());
        }
    }
    @Test void unsupportedMinecraftStagesAndUnderReservationsFailClosed() {
        try (var engine = new EpochTaskEngine(new ByteBudget(BYTES), 0)) {
            assertThrows(IllegalArgumentException.class, () -> engine.submit(key(0, 0), ExecutionRoute.CPU_PLANNED, BYTES - 1));
            for (GenerationStage stage : GenerationStage.values()) if (stage != GenerationStage.MATERIAL_FIXTURE) {
                var key = new TaskKey(key(0, 0).world(), 0, 0, stage);
                assertThrows(UnsupportedOperationException.class, () -> engine.submit(key, ExecutionRoute.GPU, BYTES));
            }
            assertEquals(0, engine.counters().admitted());
        }
    }
    @Test void consumerFutureCannotForgeEngineCompletion() {
        try (var engine = new EpochTaskEngine(new ByteBudget(BYTES), 0)) {
            var task = engine.submit(key(0, 0), ExecutionRoute.CPU_PLANNED, BYTES);
            var external = task.completion().toCompletableFuture(); external.cancel(false);
            assertEquals(EpochTaskEngine.Status.PENDING, task.status());
            assertTrue(task.complete(new UniformSection(4)));
            assertEquals(EpochTaskEngine.Status.COMMITTED, task.completion().toCompletableFuture().join().status());
        }
    }
    @Test void failedProducerAndFatalValidationReleaseResourcesWithoutCommit() {
        ByteBudget budget = new ByteBudget(BYTES);
        try (var engine = new EpochTaskEngine(budget, 0)) {
            var failed = engine.submit(key(0, 0), ExecutionRoute.CPU_PLANNED, BYTES);
            assertTrue(failed.fail(new IllegalStateException("producer"))); assertFalse(failed.fail(new IllegalStateException()));
            var fatal = engine.submit(key(1, 0), ExecutionRoute.GPU, BYTES);
            SectionData error = new SectionData() {
                public int blockStateId(int index) { throw new AssertionError("fatal"); }
                public int nonAirCount() { return 0; }
                public long logicalChecksum() { return 0; }
            };
            assertThrows(AssertionError.class, () -> fatal.complete(error));
            assertEquals(0, budget.usedBytes()); assertEquals(2, engine.counters().failed());
            assertEquals(0, engine.counters().totalCommitted()); assertEquals(EpochTaskEngine.Status.FAILED, fatal.status());
        }
    }
}
