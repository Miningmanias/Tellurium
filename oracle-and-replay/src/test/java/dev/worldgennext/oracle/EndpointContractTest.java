// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle;

import dev.worldgennext.oracle.benchmark.BaselineReport;
import dev.worldgennext.oracle.benchmark.FullEndpoint;
import dev.worldgennext.oracle.benchmark.NoiseEndpoint;
import dev.worldgennext.oracle.benchmark.RunManifest;
import dev.worldgennext.oracle.benchmark.SavedEndpoint;
import dev.worldgennext.oracle.benchmark.StageTimers;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointContractTest {
    @Test
    void endpointCompletionRequiresPositiveComparisonEvidenceAndElapsedTime() {
        assertFalse(new NoiseEndpoint(0, 1, 1).complete());
        assertFalse(new NoiseEndpoint(1, 0, 1).complete());
        assertTrue(new NoiseEndpoint(1, 1, 1).complete());
        assertFalse(new FullEndpoint(1, 1, 0).complete());
        assertTrue(new FullEndpoint(1, 1, 1).complete());
        assertFalse(new SavedEndpoint(1, 1, 0, 1).complete());
        assertTrue(new SavedEndpoint(1, 1, 1, 1).complete());
    }

    @Test
    void manifestsRejectBlankOrDuplicateCoverageKeys() {
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(
                "workload", "CPU", "NOISE", List.of("case", "case"), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new RunManifest(
                "workload", "CPU", "NOISE", List.of(""), 1, 1));
        assertEquals(List.of("case-a", "case-b"), new RunManifest(
                "workload", "CPU", "NOISE", List.of("case-a", "case-b"), 1, 3).chunks());
    }

    @Test
    void baselineCompletionRequiresMatchedTimingRowsAndPassStatus() {
        RunManifest workload = new RunManifest("workload", "GPU", "SAVED", List.of("case"), 3, 3);
        assertFalse(new BaselineReport("source", List.of(workload), List.of(1L), 1, 0, 1,
                "NOT_RUN").complete());
        assertFalse(new BaselineReport("source", List.of(workload), List.of(1L), 1, 0, 1,
                "PASS").complete(), "one comparison cannot stand in for six requested runs");
        assertTrue(new BaselineReport("source", List.of(workload), List.of(1L), 6, 0, 6,
                "PASS").complete());
        RunManifest twoCases = new RunManifest("two-cases", "GPU", "NOISE", List.of("a", "b"), 1, 1);
        assertFalse(new BaselineReport("source", List.of(twoCases), List.of(1L), 3, 0, 4,
                "PASS").complete(), "one missing requested run cannot complete a two-case workload");
        assertFalse(new BaselineReport("source", List.of(twoCases), List.of(1L), 4, 0, 0,
                "PASS").complete(), "GPU cases require device receipts");
        assertTrue(new BaselineReport("source", List.of(twoCases), List.of(1L), 4, 0, 4,
                "PASS").complete());
        assertThrows(IllegalArgumentException.class, () -> new BaselineReport(
                "source", List.of(workload), List.of(), 1, 0, 1, "PASS"));
        assertThrows(IllegalArgumentException.class, () -> new BaselineReport(
                "source", List.of(workload), List.of(-1L), 1, 0, 1, "PASS"));
        assertThrows(IllegalArgumentException.class, () -> new BaselineReport(
                "source", List.of(workload), List.of(1L), 1, 2, 1, "PASS"));
    }

    @Test
    void stageTimerSnapshotIsStableAndCannotMutateTheAccumulator() {
        StageTimers timers = new StageTimers();
        timers.add("noise", 2);
        timers.add("full", 3);
        timers.add("noise", 4);
        var snapshot = timers.snapshot();
        assertEquals(List.of("noise", "full"), List.copyOf(snapshot.keySet()));
        assertEquals(6L, snapshot.get("noise"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("saved", 1L));
        assertEquals(2, timers.snapshot().size());
    }
}
