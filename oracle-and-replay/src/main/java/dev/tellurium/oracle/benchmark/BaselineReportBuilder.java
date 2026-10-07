// SPDX-License-Identifier: MIT
package dev.tellurium.oracle.benchmark;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Builds an endpoint baseline from measured rows without inventing missing runs. */
public final class BaselineReportBuilder {
    private BaselineReportBuilder() {}

    public record Measurement(RunManifest workload, long wallNanos, long compared,
                              long mismatches, long gpuReceipts) {
        public Measurement {
            Objects.requireNonNull(workload, "workload");
            if (wallNanos <= 0 || compared <= 0 || mismatches < 0
                    || mismatches > compared || gpuReceipts < 0) {
                throw new IllegalArgumentException("Baseline measurement must contain positive timing/coverage and valid counts");
            }
        }
    }

    public static BaselineReport build(String sourceHash, List<Measurement> measurements) {
        Objects.requireNonNull(sourceHash, "sourceHash");
        List<Measurement> rows = List.copyOf(measurements == null ? List.of() : measurements);
        if (rows.isEmpty()) throw new IllegalArgumentException("At least one measured baseline row is required");
        var workloadIds = new HashSet<String>();
        var workloads = new java.util.ArrayList<RunManifest>(rows.size());
        var wallNanos = new java.util.ArrayList<Long>(rows.size());
        long compared = 0;
        long mismatches = 0;
        long gpuReceipts = 0;
        try {
            for (Measurement row : rows) {
                if (!workloadIds.add(row.workload().workloadId())) {
                    throw new IllegalArgumentException("Duplicate baseline workload: " + row.workload().workloadId());
                }
                workloads.add(row.workload());
                wallNanos.add(row.wallNanos());
                compared = Math.addExact(compared, row.compared());
                mismatches = Math.addExact(mismatches, row.mismatches());
                gpuReceipts = Math.addExact(gpuReceipts, row.gpuReceipts());
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Baseline counters overflow", overflow);
        }
        return new BaselineReport(sourceHash, workloads, wallNanos, compared, mismatches,
                gpuReceipts, mismatches == 0 ? "PASS" : "FAILED");
    }
}
