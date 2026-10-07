// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.LongAdder;

/**
 * Low-cost operator telemetry for the version-pinned Minecraft hook.
 *
 * These counters describe what the hook did during this process.  They are
 * deliberately separate from coordinator qualification counters and must not
 * be used as parity or release evidence.
 */
public final class HookTelemetry {
    private final LongAdder calls = new LongAdder();
    private final LongAdder bypasses = new LongAdder();
    private final LongAdder replacements = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder futureFailures = new LongAdder();
    private final LongAdder cancellations = new LongAdder();

    public void recordBypass() {
        calls.increment();
        bypasses.increment();
    }

    public void recordFailure() {
        calls.increment();
        failures.increment();
    }

    /** Records a replacement and observes its eventual target future. */
    public void recordReplacement(CompletionStage<?> future) {
        calls.increment();
        replacements.increment();
        Objects.requireNonNull(future, "future").whenComplete((value, failure) -> {
            if (failure == null) {
                completed.increment();
            } else if (failure instanceof CancellationException
                    || future.toCompletableFuture().isCancelled()) {
                cancellations.increment();
            } else {
                futureFailures.increment();
            }
        });
    }

    public Snapshot snapshot() {
        return new Snapshot(calls.sum(), bypasses.sum(), replacements.sum(), failures.sum(),
                completed.sum(), futureFailures.sum(), cancellations.sum());
    }

    public void reset() {
        calls.reset();
        bypasses.reset();
        replacements.reset();
        failures.reset();
        completed.reset();
        futureFailures.reset();
        cancellations.reset();
    }

    public record Snapshot(long calls, long bypasses, long replacements, long failures,
                           long completed, long futureFailures, long cancellations) {
        public Snapshot {
            if (calls < 0 || bypasses < 0 || replacements < 0 || failures < 0
                    || completed < 0 || futureFailures < 0 || cancellations < 0) {
                throw new IllegalArgumentException("Hook telemetry counters cannot be negative");
            }
        }

        public long terminalFutureOutcomes() {
            return completed + futureFailures + cancellations;
        }
    }
}
