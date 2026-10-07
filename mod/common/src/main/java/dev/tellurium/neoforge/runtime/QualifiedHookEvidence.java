// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import java.util.Locale;
import java.util.Objects;

/**
 * Evidence required before a provider may replace Minecraft's NOISE task.
 *
 * <p>The callback itself cannot prove that it was compared with an
 * independent same-stack oracle. Keeping that proof as an explicit value
 * makes the admission boundary visible and prevents a configuration toggle
 * from becoming an accidental production enable switch.</p>
 */
public record QualifiedHookEvidence(String contextKey, String route,
                                    long comparedCases, long comparedFields,
                                    long mismatches, boolean completeCoverage,
                                    boolean independentOracle,
                                    String sourceArtifactSha256,
                                    String resultAbi, String compilerVersion) {
    public static final long MINIMUM_CAPTURE_CASES = 1_500L;
    public static final String RESULT_ABI = "chunk-result-v4";
    public static final String CPU_COMPILER_VERSION = "tellurium-cpu-live-v0.2";
    public static final String GPU_COMPILER_VERSION = "tellurium-gpu-live-v0.2";

    /**
     * Compatibility constructor for the pre-identity receipt shape. New
     * receipts should always carry the explicit result ABI and compiler
     * identity so a live provider cannot be admitted against a different
     * output contract merely because its route name matches.
     */
    public QualifiedHookEvidence(String contextKey, String route,
                                 long comparedCases, long comparedFields,
                                 long mismatches, boolean completeCoverage,
                                 boolean independentOracle,
                                 String sourceArtifactSha256) {
        this(contextKey, route, comparedCases, comparedFields, mismatches,
                completeCoverage, independentOracle, sourceArtifactSha256,
                RESULT_ABI, expectedCompilerVersion(route));
    }

    public QualifiedHookEvidence {
        requireSingleLine(contextKey, "contextKey");
        requireText(route, "route");
        if (!route.equals("CPU_OWNED") && !route.equals("GPU_IEEE_BITS")) {
            throw new IllegalArgumentException("Unsupported qualified hook route: " + route);
        }
        if (comparedCases < 0 || comparedFields < 0 || mismatches < 0 || mismatches > comparedFields) {
            throw new IllegalArgumentException("Invalid qualification comparison counts");
        }
        requireText(sourceArtifactSha256, "sourceArtifactSha256");
        String normalizedHash = sourceArtifactSha256.toLowerCase(Locale.ROOT);
        if (!normalizedHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Qualification artifact must be a SHA-256 hex digest");
        }
        sourceArtifactSha256 = normalizedHash;
        requireSingleLine(resultAbi, "resultAbi");
        requireSingleLine(compilerVersion, "compilerVersion");
    }

    public static String expectedCompilerVersion(String route) {
        return switch (Objects.requireNonNull(route, "route")) {
            case "CPU_OWNED" -> CPU_COMPILER_VERSION;
            case "GPU_IEEE_BITS" -> GPU_COMPILER_VERSION;
            default -> "UNDECLARED";
        };
    }

    /** Whether this record meets the minimum product admission bar. */
    public boolean admissible() {
        return admissible(MINIMUM_CAPTURE_CASES);
    }

    /** Whether this record is valid for a bundle with the supplied aggregate bar. */
    public boolean admissible(long minimumCases) {
        requireMinimumCases(minimumCases);
        return comparedCases >= minimumCases
                && comparedFields > 0
                && mismatches == 0
                && completeCoverage
                && independentOracle;
    }

    public String rejectionReason() {
        return rejectionReason(MINIMUM_CAPTURE_CASES);
    }

    public String rejectionReason(long minimumCases) {
        requireMinimumCases(minimumCases);
        if (admissible(minimumCases)) return "qualified evidence accepted";
        var reasons = new java.util.ArrayList<String>();
        if (comparedCases < minimumCases) {
            reasons.add("compared cases " + comparedCases + " < " + minimumCases);
        }
        if (comparedFields <= 0) reasons.add("no compared fields");
        if (mismatches != 0) reasons.add("mismatches=" + mismatches);
        if (!completeCoverage) reasons.add("comparison coverage is incomplete");
        if (!independentOracle) reasons.add("oracle is not independently identified");
        return String.join("; ", reasons);
    }

    public void requireAdmissible() {
        requireAdmissible(MINIMUM_CAPTURE_CASES);
    }

    public void requireAdmissible(long minimumCases) {
        if (!admissible(minimumCases)) {
            throw new IllegalArgumentException("Qualified hook evidence rejected: " + rejectionReason(minimumCases));
        }
    }

    private static void requireMinimumCases(long minimumCases) {
        if (minimumCases <= 0) throw new IllegalArgumentException("Minimum qualification cases must be positive");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) throw new IllegalArgumentException(name + " is blank");
    }

    private static void requireSingleLine(String value, String name) {
        requireText(value, name);
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " must be single-line");
        }
    }
}
