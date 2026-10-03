// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Deterministic, replace-atomically serialized endpoint baseline report. */
public final class BaselineReportJson {
    private BaselineReportJson() {}

    public static String encode(BaselineReport report) {
        Objects.requireNonNull(report, "report");
        StringBuilder json = new StringBuilder("{\n")
                .append("  \"schemaVersion\":1,\n")
                .append("  \"kind\":\"worldgennext-endpoint-baseline\",\n")
                .append("  \"status\":").append(quote(report.status())).append(",\n")
                .append("  \"sourceHash\":").append(quote(report.sourceHash())).append(",\n")
                .append("  \"compared\":").append(report.compared()).append(",\n")
                .append("  \"mismatches\":").append(report.mismatches()).append(",\n")
                .append("  \"gpuReceipts\":").append(report.gpuReceipts()).append(",\n")
                .append("  \"wallNanos\":[");
        for (int index = 0; index < report.wallNanos().size(); index++) {
            if (index > 0) json.append(',');
            json.append(report.wallNanos().get(index));
        }
        json.append("],\n  \"workloads\":[");
        for (int index = 0; index < report.workloads().size(); index++) {
            if (index > 0) json.append(',');
            RunManifest workload = report.workloads().get(index);
            json.append("\n    {\"workloadId\":").append(quote(workload.workloadId()))
                    .append(",\"backend\":").append(quote(workload.backend()))
                    .append(",\"endpoint\":").append(quote(workload.endpoint()))
                    .append(",\"coldLaunches\":").append(workload.coldLaunches())
                    .append(",\"warmRuns\":").append(workload.warmRuns())
                    .append(",\"chunks\":[");
            for (int chunkIndex = 0; chunkIndex < workload.chunks().size(); chunkIndex++) {
                if (chunkIndex > 0) json.append(',');
                json.append(quote(workload.chunks().get(chunkIndex)));
            }
            json.append("]}");
        }
        return json.append("\n  ]\n}\n").toString();
    }

    public static void write(Path path, BaselineReport report) throws IOException {
        Objects.requireNonNull(path, "path");
        Path target = path.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IllegalArgumentException("Baseline report has no parent directory");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".worldgennext-baseline-", ".tmp");
        try {
            Files.writeString(temporary, encode(report), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 0x20) result.append(String.format("\\u%04x", (int) character));
                    else result.append(character);
                }
            }
        }
        return result.append('"').toString();
    }
}
