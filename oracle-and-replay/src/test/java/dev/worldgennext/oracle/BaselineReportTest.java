// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle;

import dev.worldgennext.oracle.benchmark.BaselineReportBuilder;
import dev.worldgennext.oracle.benchmark.BaselineReportJson;
import dev.worldgennext.oracle.benchmark.RunManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineReportTest {
    @TempDir Path temporary;

    @Test
    void builderAggregatesMeasuredRowsAndPreservesWorkloadOrder() throws Exception {
        var first = new RunManifest("noise-cpu", "CPU_OWNED", "NOISE", List.of("a"), 1, 1);
        var second = new RunManifest("saved-gpu", "GPU_IEEE_BITS", "SAVED", List.of("b", "c"), 1, 1);
        var report = BaselineReportBuilder.build("source", List.of(
                new BaselineReportBuilder.Measurement(first, 11, 2, 0, 0),
                new BaselineReportBuilder.Measurement(second, 13, 4, 0, 4)));

        assertTrue(report.complete());
        assertEquals(6, report.compared());
        assertEquals(List.of(first, second), report.workloads());
        String encoded = BaselineReportJson.encode(report);
        assertEquals(encoded, BaselineReportJson.encode(report));
        Path output = temporary.resolve("baseline.json");
        BaselineReportJson.write(output, report);
        assertEquals(encoded, Files.readString(output));
    }

    @Test
    void builderRejectsDuplicateWorkloadsAndIncompleteMeasurements() {
        var workload = new RunManifest("same", "CPU", "NOISE", List.of("a"), 1, 1);
        assertThrows(IllegalArgumentException.class, () -> new BaselineReportBuilder.Measurement(
                workload, 0, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> BaselineReportBuilder.build("source", List.of(
                new BaselineReportBuilder.Measurement(workload, 1, 1, 0, 0),
                new BaselineReportBuilder.Measurement(workload, 1, 1, 0, 0))));
    }
}
