// SPDX-License-Identifier: MIT
package dev.tellurium.engine.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkCountersTest {
    @Test
    void endpointAndComparisonCountersAreIndependent() {
        WorkCounters counters = new WorkCounters();
        counters.originalStage();
        counters.recovery();
        counters.compared(10, 2);
        counters.fullCompleted();
        counters.saveBarrierCompleted();
        counters.reopenedVerified();

        WorkCounters.Snapshot snapshot = counters.snapshot();
        assertEquals(1, snapshot.originalStages());
        assertEquals(1, snapshot.recovery());
        assertEquals(10, snapshot.comparedFields());
        assertEquals(2, snapshot.mismatchedFields());
        assertEquals(1, snapshot.fullCompleted());
        assertEquals(1, snapshot.saveBarrierCompleted());
        assertEquals(1, snapshot.reopenedVerified());
        assertEquals(0, snapshot.committed());
    }

    @Test
    void invalidComparisonTotalsCannotPolluteCounters() {
        WorkCounters counters = new WorkCounters();
        assertThrows(IllegalArgumentException.class, () -> counters.compared(1, 2));
        assertThrows(IllegalArgumentException.class, () -> counters.compared(-1, 0));
        WorkCounters.Snapshot snapshot = counters.snapshot();
        assertEquals(0, snapshot.comparedFields());
        assertEquals(0, snapshot.mismatchedFields());
    }

    @Test
    void gpuCountsUseRuntimeReceiptCardinalityAndOriginalRouteStaysSeparate() {
        WorkCounters counters = new WorkCounters();
        counters.gpuSubmitted(4);
        counters.gpuCompleted(3);
        counters.originalStage();
        var snapshot = counters.snapshot();
        assertEquals(4, snapshot.gpuSubmitted());
        assertEquals(3, snapshot.gpuCompleted());
        assertEquals(1, snapshot.originalStages());
        assertEquals(0, snapshot.cpuOwned());
        assertThrows(IllegalArgumentException.class, () -> counters.gpuSubmitted(-1));
    }

    @Test
    void endpointEvidenceCannotReportMoreMismatchesThanComparisons() {
        assertThrows(IllegalArgumentException.class, () -> new EndpointCompletion(
                dev.tellurium.engine.GenerationStage.NOISE, false, "comparison failed", 1, 2));
        var failedComparison = new EndpointCompletion(
                dev.tellurium.engine.GenerationStage.NOISE, false, "comparison failed", 2, 2);
        assertFalse(failedComparison.completed());
        assertEquals(2, failedComparison.compared());
        assertEquals(2, failedComparison.mismatches());
    }
}
