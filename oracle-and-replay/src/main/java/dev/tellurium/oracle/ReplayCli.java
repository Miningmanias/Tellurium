// SPDX-License-Identifier: MIT
package dev.tellurium.oracle;

import java.io.PrintStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import dev.tellurium.material.chunk.ChunkResultCodec;
import dev.tellurium.oracle.minecraft.ChunkResultSnapshot;
import dev.tellurium.oracle.minecraft.ChunkSnapshotComparator;
import dev.tellurium.oracle.minecraft.FailureBundle;
import dev.tellurium.oracle.schema.SnapshotField;
import dev.tellurium.oracle.schema.SnapshotIo;

/** GPU mode is a dedicated process: native failures exit nonzero, without retry or CPU substitution. */
public final class ReplayCli {
    private ReplayCli() {}
    public static void main(String[] args) { System.exit(execute(args, System.out, System.err)); }
    static int execute(String[] args, PrintStream out, PrintStream err) {
        try {
            String mode = args.length == 0 ? "replay" : args[0];
            if (mode.equals("--help") || mode.equals("help")) {
                out.println("Tellurium tools: replay|gpu-smoke|gpu-smoke-normal [--output=path.json] | compare-captures expected.snap actual.snap [--failure-dir=path] | compare-corpus expected-dir actual-dir [--failure-dir=path] | compare-candidate-result expected.snap candidate.chunk [--failure-dir=path] | compare-candidate-corpus expected-dir candidate-dir [--failure-dir=path]. Normal-range mode is a limited diagnostic, not full FP64 qualification. GPU modes require a real GPU. Candidate comparisons are fail-closed: they compare every oracle field and report fields absent from the current result ABI.");
                return 0;
            }
            if (mode.equals("compare-captures")) {
                if (args.length < 3 || args.length > 4) throw new IllegalArgumentException("Expected compare-captures expected.snap actual.snap [--failure-dir=path]");
                var expected = SnapshotIo.read(Path.of(args[1]));
                var actual = SnapshotIo.read(Path.of(args[2]));
                var comparison = new ChunkSnapshotComparator().compare(expected, actual, EnumSet.allOf(SnapshotField.class));
                Path failureDirectory = null;
                if (args.length == 4) {
                    if (!args[3].startsWith("--failure-dir=") || args[3].length() == 14) {
                        throw new IllegalArgumentException("Expected --failure-dir=path");
                    }
                    failureDirectory = Path.of(args[3].substring(14));
                }
                if (!comparison.passed() && failureDirectory != null) {
                    new FailureBundle(expected.identity().caseKey(), comparison.reason(), comparison.differences()).write(failureDirectory);
                }
                out.println((comparison.passed() ? "PASS" : "FAIL") + " CAPTURE_COMPARISON fields=" + comparison.comparedFields()
                        + " differences=" + comparison.differences().size() + " reason=" + comparison.reason()
                        + (failureDirectory == null ? "" : " failureDir=" + failureDirectory.toAbsolutePath().normalize()));
                return comparison.passed() ? 0 : 1;
            }
            if (mode.equals("compare-candidate-result")) {
                if (args.length < 3 || args.length > 4) throw new IllegalArgumentException("Expected compare-candidate-result expected.snap candidate.chunk [--failure-dir=path]");
                var expected = SnapshotIo.read(Path.of(args[1]));
                if (!"NOISE".equals(expected.identity().endpoint())) {
                    throw new IllegalArgumentException("Candidate result ABI comparison currently supports NOISE only; "
                            + expected.identity().endpoint() + " requires a complete endpoint snapshot");
                }
                var bytes = Files.readAllBytes(Path.of(args[2]));
                var result = ChunkResultCodec.decode(bytes);
                var candidateIdentity = new dev.tellurium.oracle.schema.CaptureIdentity(
                        "CANDIDATE", "tellurium-cpu-candidate", expected.identity().seed(),
                        expected.identity().dimension(), expected.identity().chunkX(), expected.identity().chunkZ(),
                        expected.identity().endpoint(), expected.identity().contextFingerprint());
                var actual = ChunkResultSnapshot.candidate(result, candidateIdentity, Map.of(
                        "seed", expected.identity().seed(),
                        "dimension", expected.identity().dimension(),
                        "endpoint", expected.identity().endpoint()));
                var comparison = new ChunkSnapshotComparator().compare(expected, actual, EnumSet.allOf(SnapshotField.class));
                Path failureDirectory = null;
                if (args.length == 4) {
                    if (!args[3].startsWith("--failure-dir=") || args[3].length() == 14) {
                        throw new IllegalArgumentException("Expected --failure-dir=path");
                    }
                    failureDirectory = Path.of(args[3].substring(14));
                }
                if (!comparison.passed() && failureDirectory != null) {
                    new FailureBundle(expected.identity().caseKey(), comparison.reason(), comparison.differences()).write(failureDirectory);
                }
                var blockComparison = ChunkResultSnapshot.compareBlockStates(
                        expected.field(SnapshotField.BLOCK_STATES), actual.field(SnapshotField.BLOCK_STATES));
                var mismatchPairs = ChunkResultSnapshot.mismatchPairs(
                        expected.field(SnapshotField.BLOCK_STATES), actual.field(SnapshotField.BLOCK_STATES));
                out.println((comparison.passed() ? "PASS" : "FAIL") + " CANDIDATE_RESULT_COMPARISON fields="
                        + comparison.comparedFields() + " differences=" + comparison.differences().size()
                        + " reason=" + comparison.reason()
                        + " blockStateExpected=" + blockComparison.expectedBlocks()
                        + " blockStateActual=" + blockComparison.actualBlocks()
                        + " blockStateMismatches=" + blockComparison.mismatches()
                        + " firstBlockMismatch=" + blockComparison.firstMismatch()
                        + " mismatchPairs=" + mismatchPairs
                        + (failureDirectory == null ? "" : " failureDir=" + failureDirectory.toAbsolutePath().normalize()));
                return comparison.passed() ? 0 : 1;
            }
            if (mode.equals("compare-candidate-corpus")) {
                if (args.length < 3 || args.length > 4) throw new IllegalArgumentException(
                        "Expected compare-candidate-corpus expected-dir candidate-dir [--failure-dir=path]");
                Path expectedDirectory = Path.of(args[1]);
                Path candidateDirectory = Path.of(args[2]);
                if (!Files.isDirectory(expectedDirectory) || !Files.isDirectory(candidateDirectory)) {
                    throw new IllegalArgumentException("Expected an oracle directory and a candidate directory");
                }
                Path failureDirectory = null;
                if (args.length == 4) {
                    if (!args[3].startsWith("--failure-dir=") || args[3].length() == 14) {
                        throw new IllegalArgumentException("Expected --failure-dir=path");
                    }
                    failureDirectory = Path.of(args[3].substring(14));
                }
                var expectedByPath = byRelativePath(expectedDirectory, snapshotFiles(expectedDirectory));
                var candidateByPath = byRelativePath(candidateDirectory, candidateFiles(candidateDirectory));
                var failures = new ArrayList<String>();
                int comparedCases = 0;
                int comparedFields = 0;
                long expectedBlocks = 0;
                long actualBlocks = 0;
                long blockMismatches = 0;
                for (var entry : expectedByPath.entrySet()) {
                    Path candidatePath = candidateByPath.get(entry.getKey().replaceAll("\\.snap$", ".chunk"));
                    if (candidatePath == null) {
                        failures.add(entry.getKey() + ": missing candidate result");
                        continue;
                    }
                    var expected = SnapshotIo.read(entry.getValue());
                    if (!"NOISE".equals(expected.identity().endpoint())) {
                        throw new IllegalArgumentException("Candidate corpus comparison supports NOISE only; "
                                + expected.identity().endpoint() + " at " + entry.getKey());
                    }
                    var result = ChunkResultCodec.decode(Files.readAllBytes(candidatePath));
                    var candidateIdentity = new dev.tellurium.oracle.schema.CaptureIdentity(
                            "CANDIDATE", "tellurium-candidate", expected.identity().seed(),
                            expected.identity().dimension(), expected.identity().chunkX(), expected.identity().chunkZ(),
                            expected.identity().endpoint(), expected.identity().contextFingerprint());
                    var actual = ChunkResultSnapshot.candidate(result, candidateIdentity, Map.of(
                            "seed", expected.identity().seed(),
                            "dimension", expected.identity().dimension(),
                            "endpoint", expected.identity().endpoint()));
                    var comparison = new ChunkSnapshotComparator().compare(expected, actual,
                            EnumSet.allOf(SnapshotField.class));
                    comparedCases++;
                    comparedFields += comparison.comparedFields();
                    var blocks = ChunkResultSnapshot.compareBlockStates(
                            expected.field(SnapshotField.BLOCK_STATES), actual.field(SnapshotField.BLOCK_STATES));
                    expectedBlocks += blocks.expectedBlocks();
                    actualBlocks += blocks.actualBlocks();
                    blockMismatches += blocks.mismatches();
                    if (!comparison.passed()) {
                        failures.add(entry.getKey() + ": " + comparison.reason()
                                + " differences=" + comparison.differences().size());
                        if (failureDirectory != null) {
                            new FailureBundle(expected.identity().caseKey(), comparison.reason(), comparison.differences())
                                    .write(failureDirectory);
                        }
                    }
                }
                for (String name : expectedByPath.keySet()) {
                    String candidateName = name.replaceAll("\\.snap$", ".chunk");
                    if (!candidateByPath.containsKey(candidateName)) failures.add(name + ": missing candidate result");
                }
                candidateByPath.keySet().stream()
                        .filter(name -> !expectedByPath.containsKey(name.replaceAll("\\.chunk$", ".snap")))
                        .forEach(name -> failures.add(name + ": unexpected candidate result"));
                boolean pass = !expectedByPath.isEmpty() && failures.isEmpty()
                        && comparedCases == expectedByPath.size()
                        && candidateByPath.size() == expectedByPath.size();
                out.println((pass ? "PASS" : "FAIL") + " CANDIDATE_CORPUS_COMPARISON cases=" + comparedCases
                        + " expectedCases=" + expectedByPath.size() + " candidateCases=" + candidateByPath.size()
                        + " fields=" + comparedFields + " blockStateExpected=" + expectedBlocks
                        + " blockStateActual=" + actualBlocks + " blockStateMismatches=" + blockMismatches
                        + " failures=" + failures.size()
                        + " reason=" + (pass ? "equal" : "missing, extra, or logically different results"));
                failures.forEach(failure -> out.println("CASE " + failure));
                return pass ? 0 : 1;
            }
            if (mode.equals("compare-corpus")) {
                if (args.length < 3 || args.length > 4) throw new IllegalArgumentException("Expected compare-corpus expected-dir actual-dir [--failure-dir=path]");
                Path expectedDirectory = Path.of(args[1]);
                Path actualDirectory = Path.of(args[2]);
                if (!Files.isDirectory(expectedDirectory) || !Files.isDirectory(actualDirectory)) {
                    throw new IllegalArgumentException("Expected two capture directories");
                }
                Path failureDirectory = null;
                if (args.length == 4) {
                    if (!args[3].startsWith("--failure-dir=") || args[3].length() == 14) {
                        throw new IllegalArgumentException("Expected --failure-dir=path");
                    }
                    failureDirectory = Path.of(args[3].substring(14));
                }
                var expectedFiles = snapshotFiles(expectedDirectory);
                var actualFiles = snapshotFiles(actualDirectory);
                var expectedByPath = byRelativePath(expectedDirectory, expectedFiles);
                var actualByPath = byRelativePath(actualDirectory, actualFiles);
                var failures = new ArrayList<String>();
                int comparedCases = 0;
                int comparedFields = 0;
                for (var entry : expectedByPath.entrySet()) {
                    Path actualPath = actualByPath.get(entry.getKey());
                    if (actualPath == null) {
                        failures.add(entry.getKey() + ": missing actual capture");
                        continue;
                    }
                    var expected = SnapshotIo.read(entry.getValue());
                    var actual = SnapshotIo.read(actualPath);
                    var comparison = new ChunkSnapshotComparator().compare(expected, actual, EnumSet.allOf(SnapshotField.class));
                    comparedCases++;
                    comparedFields += comparison.comparedFields();
                    if (!comparison.passed()) {
                        failures.add(entry.getKey() + ": " + comparison.reason() + " differences=" + comparison.differences().size());
                        if (failureDirectory != null) {
                            new FailureBundle(expected.identity().caseKey(), comparison.reason(), comparison.differences()).write(failureDirectory);
                        }
                    }
                }
                var extra = new HashSet<>(actualByPath.keySet());
                extra.removeAll(expectedByPath.keySet());
                extra.forEach(name -> failures.add(name + ": unexpected actual capture"));
                boolean pass = !expectedByPath.isEmpty() && failures.isEmpty()
                        && comparedCases == expectedByPath.size() && actualByPath.size() == expectedByPath.size();
                out.println((pass ? "PASS" : "FAIL") + " CORPUS_COMPARISON cases=" + comparedCases
                        + " expectedCases=" + expectedByPath.size() + " actualCases=" + actualByPath.size()
                        + " fields=" + comparedFields + " failures=" + failures.size()
                        + " reason=" + (pass ? "equal" : "missing, extra, or logically different captures"));
                failures.forEach(failure -> out.println("CASE " + failure));
                return pass ? 0 : 1;
            }
            if (!mode.equals("replay") && !mode.equals("gpu-smoke") && !mode.equals("gpu-smoke-normal")) throw new IllegalArgumentException("Unknown mode: " + mode);
            Path output = Path.of("build", "replay", mode + ".json");
            if (args.length > 2) throw new IllegalArgumentException("Expected one optional --output=path.json argument");
            if (args.length == 2) {
                if (!args[1].startsWith("--output=") || args[1].length() == 9) throw new IllegalArgumentException("Expected --output=path.json");
                output = Path.of(args[1].substring(9));
            }
            var fixtures = SyntheticCorpus.fixtures();
            ReplayReport report = switch (mode) {
                case "replay" -> ReplayRunner.cpu(fixtures);
                case "gpu-smoke" -> ReplayRunner.gpu(fixtures);
                default -> ReplayRunner.gpuNormalRangeDiagnostic();
            };
            ReportJson.write(output, report);
            out.println(report.verdict() + " " + report.backend() + " SYNTHETIC_DENSITY_ONLY: " + report.comparedSamples() + "/" + report.expectedSamples()
                    + " compared, mismatches=" + report.mismatches() + ", reportedFixtures=" + report.fixtures().size() + "/" + report.expectedFixtures()
                    + "; report=" + output.toAbsolutePath().normalize());
            return report.passed() ? 0 : 1;
        } catch (Exception | LinkageError failure) {
            err.println("FAILED: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return 2;
        }
    }

    private static List<Path> snapshotFiles(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".snap"))
                    .sorted(java.util.Comparator.comparing(path -> relativePath(directory, path)))
                    .toList();
        }
    }

    private static List<Path> candidateFiles(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".chunk"))
                    .sorted(java.util.Comparator.comparing(path -> relativePath(directory, path)))
                    .toList();
        }
    }

    private static Map<String, Path> byRelativePath(Path root, List<Path> paths) {
        var result = new HashMap<String, Path>();
        for (Path path : paths) {
            String key = relativePath(root, path);
            Path previous = result.put(key, path);
            if (previous != null) throw new IllegalArgumentException("Duplicate capture path: " + key);
        }
        return result;
    }

    private static String relativePath(Path root, Path path) {
        String value = root.relativize(path).toString().replace('\\', '/');
        if (value.isBlank() || value.equals("..") || value.startsWith("../") || value.contains("/../")) {
            throw new IllegalArgumentException("Capture path escapes corpus root: " + path);
        }
        return value;
    }
}
