// SPDX-License-Identifier: MIT
package dev.tellurium.oracle.benchmark;

import java.util.List;

public record BaselineReport(String sourceHash, List<RunManifest> workloads, List<Long> wallNanos,
                             long compared, long mismatches, long gpuReceipts, String status) {
    public BaselineReport {
        if (sourceHash == null || sourceHash.isBlank() || compared < 0 || mismatches < 0
                || mismatches > compared || gpuReceipts < 0 || status == null || status.isBlank()) {
            throw new IllegalArgumentException("Invalid baseline report");
        }
        workloads = List.copyOf(workloads == null ? List.of() : workloads);
        wallNanos = List.copyOf(wallNanos == null ? List.of() : wallNanos);
        if (wallNanos.stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalArgumentException("Baseline timings must be nonnegative");
        }
        if (workloads.size() != wallNanos.size()) {
            throw new IllegalArgumentException("Every baseline workload requires one wall-time value");
        }
    }

    public boolean complete() {
        return status.equals("PASS") && !workloads.isEmpty() && compared > 0 && mismatches == 0
                && wallNanos.stream().allMatch(value -> value > 0)
                && compared >= requestedRunCount()
                && gpuReceipts >= requiredGpuReceipts();
    }

    /** Number of fresh chunk comparisons requested across cold and warm runs. */
    public long requestedRunCount() {
        return sumRequestedRuns(false);
    }

    /** GPU-backed workloads require a device receipt for every requested run/case. */
    public long requiredGpuReceipts() {
        return sumRequestedRuns(true);
    }

    private long sumRequestedRuns(boolean gpuOnly) {
        long total = 0;
        try {
            for (RunManifest workload : workloads) {
                if (!gpuOnly || workload.backend().toUpperCase(java.util.Locale.ROOT).contains("GPU")) {
                    total = Math.addExact(total, requestedRuns(workload));
                }
            }
            return total;
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Baseline coverage exceeds the report counter", overflow);
        }
    }

    private static long requestedRuns(RunManifest workload) {
        try {
            return Math.multiplyExact((long) workload.chunks().size(),
                    Math.addExact((long) workload.coldLaunches(), (long) workload.warmRuns()));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Baseline workload coverage exceeds the report counter", overflow);
        }
    }
}
