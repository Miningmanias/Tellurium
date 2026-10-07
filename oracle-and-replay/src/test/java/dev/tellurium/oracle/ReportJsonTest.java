// SPDX-License-Identifier: MIT
package dev.tellurium.oracle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ReportJsonTest {
    @TempDir Path temporary;

    @Test void repeatedCpuRunsProduceIdenticalArtifactsWithExplicitScope() {
        String first = ReportJson.encode(ReplayRunner.cpu(SyntheticCorpus.fixtures()));
        assertEquals(first, ReportJson.encode(ReplayRunner.cpu(SyntheticCorpus.fixtures())));
        assertTrue(first.contains("\"minecraftParity\": \"NOT_IMPLEMENTED\""));
        assertTrue(first.contains("\"gpuComparedSamples\": 0"));
        assertTrue(first.contains("\"scope\": \"SYNTHETIC_DENSITY_ONLY\""));
    }
    @Test void failureAndIncompleteCoverageRemainVisible() {
        var result = new FixtureResult("first", 2, 1, 0, "stop\n\"now\"", Map.of("key", "a\\b\t"));
        String json = ReportJson.encode(new ReplayReport(ReplayReport.Backend.CPU, 2, 4, List.of(result)));
        assertTrue(json.contains("\"verdict\": \"FAILED\""));
        assertTrue(json.contains("\"comparedSamples\": 1"));
        assertTrue(json.contains("stop\\n\\\"now\\\""));
        assertEquals("\"\\u0000\\b\\f\\r\"", ReportJson.quote("\0\b\f\r"));
    }
    @Test void writesAndReplacesCompleteReportWithoutTemporaryFiles() throws Exception {
        Path target = temporary.resolve("nested/report.json");
        var report = ReplayRunner.cpu(SyntheticCorpus.fixtures());
        ReportJson.write(target, report);
        assertEquals(ReportJson.encode(report), Files.readString(target));
        ReportJson.write(target, report);
        try (var files = Files.list(target.getParent())) { assertEquals(1, files.count()); }
    }
    @Test void cliRunsCpuWritesReportAndRejectsInvalidArgsWithoutNativeUse() throws Exception {
        Path target = temporary.resolve("cli.json");
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        assertEquals(0, ReplayCli.execute(new String[]{"replay", "--output=" + target}, new PrintStream(output), new PrintStream(error)));
        assertTrue(Files.readString(target).contains("\"comparedSamples\": 1048"));
        assertTrue(output.toString().contains("PASS CPU"));
        assertEquals(2, ReplayCli.execute(new String[]{"cuda"}, new PrintStream(output), new PrintStream(error)));
        assertEquals(2, ReplayCli.execute(new String[]{"replay", "--output="}, new PrintStream(output), new PrintStream(error)));
        assertEquals(2, ReplayCli.execute(new String[]{"replay", "--bogus=x"}, new PrintStream(output), new PrintStream(error)));
    }
    @Test void unwritableOutputCannotReportSuccessfulExit() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("directory"));
        assertEquals(2, ReplayCli.execute(new String[]{"replay", "--output=" + directory}, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));
    }
}
