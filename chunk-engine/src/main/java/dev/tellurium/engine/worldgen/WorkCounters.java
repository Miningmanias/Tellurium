// SPDX-License-Identifier: MIT
package dev.tellurium.engine.worldgen;

import java.util.concurrent.atomic.LongAdder;

/** Distinct subscriber/work/backend/commit counters; fields are never inferred from one another. */
public final class WorkCounters {
    private final LongAdder requested = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder cancelled = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder stale = new LongAdder();
    private final LongAdder uniqueWork = new LongAdder();
    private final LongAdder backendAttempts = new LongAdder();
    private final LongAdder gpuSubmitted = new LongAdder();
    private final LongAdder gpuCompleted = new LongAdder();
    private final LongAdder validated = new LongAdder();
    private final LongAdder committed = new LongAdder();
    private final LongAdder cpuOwned = new LongAdder();
    private final LongAdder originalStages = new LongAdder();
    private final LongAdder recovery = new LongAdder();
    private final LongAdder comparedFields = new LongAdder();
    private final LongAdder mismatchedFields = new LongAdder();
    private final LongAdder fullCompleted = new LongAdder();
    private final LongAdder saveBarrierCompleted = new LongAdder();
    private final LongAdder reopenedVerified = new LongAdder();

    public void requested() { requested.increment(); }
    public void rejected() { rejected.increment(); }
    public void cancelled() { cancelled.increment(); }
    public void failed() { failed.increment(); }
    public void stale() { stale.increment(); }
    public void uniqueWork() { uniqueWork.increment(); }
    public void backendAttempt() { backendAttempts.increment(); }
    public void gpuSubmitted() { gpuSubmitted(1); }
    public void gpuSubmitted(long count) { addNonNegative(gpuSubmitted, count, "GPU submitted count"); }
    public void gpuCompleted() { gpuCompleted(1); }
    public void gpuCompleted(long count) { addNonNegative(gpuCompleted, count, "GPU completed count"); }
    public void validated() { validated.increment(); }
    public void committed() { committed.increment(); }

    /** Records a Tellurium-owned CPU execution, independent of its commit result. */
    public void cpuOwned() { cpuOwned.increment(); }
    /** Records a deliberate original-game downstream execution. */
    public void originalStage() { originalStages.increment(); }
    /** Records a post-failure CPU recovery attempt. */
    public void recovery() { recovery.increment(); }

    public void compared(long fields, long mismatches) {
        if (fields < 0 || mismatches < 0 || mismatches > fields) {
            throw new IllegalArgumentException("Invalid comparison counters");
        }
        comparedFields.add(fields);
        mismatchedFields.add(mismatches);
    }

    public void fullCompleted() { fullCompleted.increment(); }
    public void saveBarrierCompleted() { saveBarrierCompleted.increment(); }
    public void reopenedVerified() { reopenedVerified.increment(); }

    private static void addNonNegative(LongAdder counter, long count, String name) {
        if (count < 0) throw new IllegalArgumentException(name + " cannot be negative");
        counter.add(count);
    }

    public record Snapshot(long requested, long rejected, long cancelled, long failed, long stale,
                           long uniqueWork, long backendAttempts, long gpuSubmitted, long gpuCompleted,
                           long validated, long committed, long cpuOwned, long originalStages,
                           long recovery, long comparedFields, long mismatchedFields,
                           long fullCompleted, long saveBarrierCompleted, long reopenedVerified) {}

    public Snapshot snapshot() {
        return new Snapshot(requested.sum(), rejected.sum(), cancelled.sum(), failed.sum(), stale.sum(),
                uniqueWork.sum(), backendAttempts.sum(), gpuSubmitted.sum(), gpuCompleted.sum(),
                validated.sum(), committed.sum(), cpuOwned.sum(), originalStages.sum(), recovery.sum(),
                comparedFields.sum(), mismatchedFields.sum(), fullCompleted.sum(),
                saveBarrierCompleted.sum(), reopenedVerified.sum());
    }
}
