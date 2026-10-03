// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.TreeMap;

/** Dependency-free deterministic JSON; artifacts contain counts, identity and explicit scope. */
public final class ReportJson {
    private ReportJson() {}
    public static String encode(ReplayReport report) {
        StringBuilder json = new StringBuilder("{\n");
        field(json, "schema", "worldgennext-replay-v1", true);
        boolean normalDiagnostic = report.backend() == ReplayReport.Backend.VULKAN_NORMAL_RANGE_DIAGNOSTIC;
        field(json, "corpus", normalDiagnostic ? SyntheticCorpus.NORMAL_RANGE_ID : SyntheticCorpus.ID, true);
        field(json, "scope", "SYNTHETIC_DENSITY_ONLY", true);
        field(json, "backend", report.backend().name(), true);
        field(json, "verdict", report.verdict(), true);
        field(json, "minecraftParity", "NOT_IMPLEMENTED", true);
        field(json, "chunkThroughput", "NOT_MEASURED", true);
        field(json, "fullFp64Qualification", "NOT_PASSED", true);
        field(json, "floatControlQualification", "Finite fixture raw-bit comparisons only; explicit SPIR-V float-control modes and cross-vendor qualification pending", true);
        number(json, "expectedFixtures", report.expectedFixtures());
        number(json, "reportedFixtures", report.fixtures().size());
        number(json, "expectedSamples", report.expectedSamples());
        number(json, "comparedSamples", report.comparedSamples());
        number(json, "gpuComparedSamples", report.backend() == ReplayReport.Backend.CPU ? 0 : report.comparedSamples());
        number(json, "mismatches", report.mismatches());
        number(json, "failedFixtures", report.failedFixtures());
        json.append("  \"fixtures\": [");
        for (int i = 0; i < report.fixtures().size(); i++) {
            var result = report.fixtures().get(i);
            if (i != 0) json.append(',');
            json.append("\n    {\"name\": ").append(quote(result.name()))
                    .append(", \"expectedSamples\": ").append(result.expectedSamples())
                    .append(", \"comparedSamples\": ").append(result.comparedSamples())
                    .append(", \"mismatches\": ").append(result.mismatches())
                    .append(", \"passed\": ").append(result.passed())
                    .append(", \"error\": ").append(quote(result.error())).append(", \"evidence\": {");
            boolean comma = false;
            for (var item : new TreeMap<>(result.evidence()).entrySet()) {
                if (comma) json.append(", ");
                json.append(quote(item.getKey())).append(": ").append(quote(item.getValue()));
                comma = true;
            }
            json.append("}}");
        }
        return json.append("\n  ]\n}\n").toString();
    }
    public static void write(Path path, ReplayReport report) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".worldgennext-report-", ".tmp");
        try {
            Files.writeString(temporary, encode(report), StandardCharsets.UTF_8);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> { if (c < 0x20) result.append(String.format("\\u%04x", (int) c)); else result.append(c); }
            }
        }
        return result.append('"').toString();
    }
    private static void field(StringBuilder json, String name, String value, boolean comma) {
        json.append("  ").append(quote(name)).append(": ").append(quote(value)).append(comma ? ",\n" : "\n");
    }
    private static void number(StringBuilder json, String name, int value) { json.append("  ").append(quote(name)).append(": ").append(value).append(",\n"); }
}
