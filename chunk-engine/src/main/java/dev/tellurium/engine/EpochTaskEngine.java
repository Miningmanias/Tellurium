// SPDX-License-Identifier: MIT
package dev.tellurium.engine;

import dev.tellurium.material.SectionCodec;
import dev.tellurium.material.SectionData;
import dev.tellurium.spatial.ByteBudget;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

/**
 * MC-free fixture commit model. It owns an immutable committed value, not a Minecraft chunk.
 * Reservation accounts for active validation/task bytes, not consumer-owned committed results.
 * Every supplied result is snapshotted and verified before the atomic commit decision.
 * No external mutation callback is allowed, so a failed validation cannot partly modify a target.
 */
public final class EpochTaskEngine implements AutoCloseable {
    public enum Status { PENDING, VALIDATING, COMMITTED, CANCELLED, STALE, FAILED }
    public record Outcome(Status status, Optional<SectionData> section, String detail) {
        public Outcome {
            Objects.requireNonNull(status); Objects.requireNonNull(section); Objects.requireNonNull(detail);
            if ((status == Status.COMMITTED) != section.isPresent()) throw new IllegalArgumentException("Only commits contain data");
            if (status == Status.PENDING || status == Status.VALIDATING) throw new IllegalArgumentException("Outcome must be terminal");
        }
    }
    public record Counters(long admitted, long rejected, long gpuResultsReceived, long gpuCommitted,
                           long cpuPlanned, long cpuCommitted, long cpuRecoveryPlanned, long cpuRecoveryCommitted,
                           long cancelled, long stale, long failed, long duplicateOrLateCompletions) {
        public long totalCommitted() { return gpuCommitted + cpuCommitted + cpuRecoveryCommitted; }
    }
    /** Minimum for the mandatory owned logical-state snapshot; caller may reserve additional task bytes. */
    public static final long MINIMUM_TASK_BYTES = (long) SectionData.BLOCK_COUNT * Integer.BYTES;
    private final ByteBudget budget;
    private final int maximumTaskRecords;
    private final Map<TaskKey, TaskHandle> tasks = new HashMap<>();
    private long epoch;
    private boolean closed;
    private long admitted, rejected, gpuResultsReceived, gpuCommitted, cpuPlanned, cpuCommitted;
    private long cpuRecoveryPlanned, cpuRecoveryCommitted, cancelled, stale, failed, duplicateOrLateCompletions;
    public EpochTaskEngine(ByteBudget budget, long initialEpoch) { this(budget, initialEpoch, 4096); }
    public EpochTaskEngine(ByteBudget budget, long initialEpoch, int maximumTaskRecords) {
        this.budget = Objects.requireNonNull(budget, "budget");
        if (initialEpoch < 0 || maximumTaskRecords <= 0) throw new IllegalArgumentException("Invalid epoch or task bound");
        epoch = initialEpoch; this.maximumTaskRecords = maximumTaskRecords;
    }
    public synchronized long epoch() { return epoch; }
    public synchronized int retainedTaskRecords() { return tasks.size(); }
    public synchronized Counters counters() {
        return new Counters(admitted, rejected, gpuResultsReceived, gpuCommitted, cpuPlanned, cpuCommitted,
                cpuRecoveryPlanned, cpuRecoveryCommitted, cancelled, stale, failed, duplicateOrLateCompletions);
    }
    public synchronized TaskHandle submit(TaskKey key, ExecutionRoute route, long reservedBytes) {
        Objects.requireNonNull(key, "key"); Objects.requireNonNull(route, "route");
        if (reservedBytes < MINIMUM_TASK_BYTES) throw new IllegalArgumentException("Reservation must cover logical snapshot");
        if (key.stage() != GenerationStage.MATERIAL_FIXTURE) throw new UnsupportedOperationException("Minecraft stage is not implemented: " + key.stage());
        if (closed || key.world().epoch() != epoch || tasks.containsKey(key) || tasks.size() >= maximumTaskRecords) {
            rejected++; throw new RejectedExecutionException("Closed/stale/duplicate task or bounded registry full");
        }
        Optional<ByteBudget.Reservation> reservation = budget.tryReserve(reservedBytes);
        if (reservation.isEmpty()) { rejected++; throw new RejectedExecutionException("Task byte budget exhausted"); }
        TaskHandle task = new TaskHandle(key, route, reservation.orElseThrow());
        tasks.put(key, task); admitted++;
        if (route == ExecutionRoute.CPU_PLANNED) cpuPlanned++;
        if (route == ExecutionRoute.CPU_RECOVERY) cpuRecoveryPlanned++;
        return task;
    }
    /** Old handles become terminal before the epoch changes; an in-flight validator retains its lease until return. */
    public void advanceEpoch(long nextEpoch) {
        ArrayList<TaskHandle> retired = new ArrayList<>();
        synchronized (this) {
            if (closed || nextEpoch <= epoch) throw new IllegalArgumentException("Epoch must advance on an open engine");
            for (TaskHandle task : tasks.values()) if (task.active()) { task.terminate(Status.STALE, "World epoch advanced"); retired.add(task); }
            tasks.clear(); epoch = nextEpoch;
        }
        retired.forEach(TaskHandle::publish);
    }
    @Override public void close() {
        ArrayList<TaskHandle> retired = new ArrayList<>();
        synchronized (this) {
            if (closed) return;
            closed = true;
            for (TaskHandle task : tasks.values()) if (task.active()) { task.terminate(Status.CANCELLED, "Engine closed"); retired.add(task); }
            tasks.clear();
        }
        retired.forEach(TaskHandle::publish);
    }
    public final class TaskHandle {
        private final TaskKey key;
        private final ExecutionRoute route;
        private final ByteBudget.Reservation reservation;
        private final CompletableFuture<Outcome> completion = new CompletableFuture<>();
        private Status status = Status.PENDING;
        private boolean validatorActive;
        private Outcome outcome;
        private TaskHandle(TaskKey key, ExecutionRoute route, ByteBudget.Reservation reservation) { this.key = key; this.route = route; this.reservation = reservation; }
        public TaskKey key() { return key; }
        public ExecutionRoute route() { return route; }
        public Status status() { synchronized (EpochTaskEngine.this) { return status; } }
        public CompletionStage<Outcome> completion() { return completion.minimalCompletionStage(); }
        public Optional<SectionData> committedSection() {
            synchronized (EpochTaskEngine.this) { return outcome == null ? Optional.empty() : outcome.section(); }
        }
        private boolean active() { return status == Status.PENDING || status == Status.VALIDATING; }
        public boolean complete(SectionData result) {
            synchronized (EpochTaskEngine.this) {
                if (status != Status.PENDING) { duplicateOrLateCompletions++; return false; }
                status = Status.VALIDATING; validatorActive = true;
                if (route == ExecutionRoute.GPU) gpuResultsReceived++;
            }
            SectionData owned = null;
            Throwable validationFailure = null;
            try { owned = SectionCodec.encode(SectionCodec.decode(result)); }
            catch (Throwable failure) { validationFailure = failure; }
            boolean committed = false;
            synchronized (EpochTaskEngine.this) {
                validatorActive = false;
                if (status == Status.VALIDATING) {
                    if (validationFailure != null) terminate(Status.FAILED, "Result validation failed: " + validationFailure.getClass().getSimpleName());
                    else if (closed || key.world().epoch() != epoch) terminate(Status.STALE, "Result belongs to stale epoch");
                    else {
                        status = Status.COMMITTED;
                        outcome = new Outcome(status, Optional.of(owned), "Fixture material committed; no Minecraft stage executed");
                        if (route == ExecutionRoute.GPU) gpuCommitted++;
                        else if (route == ExecutionRoute.CPU_PLANNED) cpuCommitted++;
                        else cpuRecoveryCommitted++;
                        committed = true;
                    }
                }
                reservation.close();
            }
            publish();
            if (validationFailure instanceof Error error) throw error;
            return committed;
        }
        public boolean cancel() { return finishFailure(Status.CANCELLED, "Cancelled by caller"); }
        public boolean fail(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            return finishFailure(Status.FAILED, "Producer failed: " + failure.getClass().getSimpleName());
        }
        private boolean finishFailure(Status terminal, String detail) {
            synchronized (EpochTaskEngine.this) {
                if (!active()) return false;
                terminate(terminal, detail);
            }
            publish(); return true;
        }
        private void terminate(Status terminal, String detail) {
            status = terminal;
            outcome = new Outcome(status, Optional.empty(), detail);
            switch (terminal) {
                case CANCELLED -> cancelled++;
                case STALE -> stale++;
                case FAILED -> failed++;
                default -> throw new IllegalArgumentException("Not a failure terminal status");
            }
            if (!validatorActive) reservation.close();
        }
        private void publish() {
            Outcome result;
            synchronized (EpochTaskEngine.this) { result = outcome; }
            if (result != null) completion.complete(result);
        }
    }
}
