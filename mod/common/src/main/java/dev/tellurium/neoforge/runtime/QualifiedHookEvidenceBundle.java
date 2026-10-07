// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Aggregate qualification evidence for all exact context identities supported
 * by one provider. Contexts remain exact; the bundle only aggregates the
 * required corpus bar and never turns a missing context into a wildcard.
 */
public final class QualifiedHookEvidenceBundle {
    public static final int SCHEMA_VERSION = 2;
    public static final long MINIMUM_CAPTURE_CASES = QualifiedHookEvidence.MINIMUM_CAPTURE_CASES;

    private final List<QualifiedHookEvidence> entries;
    private final Map<String, QualifiedHookEvidence> byContext;
    private final String route;
    private final String resultAbi;
    private final String compilerVersion;
    private final long comparedCases;
    private final long comparedFields;
    private final long mismatches;

    public QualifiedHookEvidenceBundle(List<QualifiedHookEvidence> entries) {
        List<QualifiedHookEvidence> supplied = List.copyOf(entries == null ? List.of() : entries);
        if (supplied.isEmpty()) throw new IllegalArgumentException("Qualification evidence bundle is empty");
        supplied = supplied.stream()
                .peek(entry -> Objects.requireNonNull(entry, "entry"))
                .sorted(Comparator.comparing(QualifiedHookEvidence::contextKey))
                .toList();
        Map<String, QualifiedHookEvidence> contexts = new LinkedHashMap<>();
        String selectedRoute = null;
        String selectedAbi = null;
        String selectedCompiler = null;
        long cases = 0;
        long fields = 0;
        long differences = 0;
        try {
            for (QualifiedHookEvidence entry : supplied) {
                if (contexts.put(entry.contextKey(), entry) != null) {
                    throw new IllegalArgumentException("Duplicate qualification context: " + entry.contextKey());
                }
                if (selectedRoute == null) {
                    selectedRoute = entry.route();
                    selectedAbi = entry.resultAbi();
                    selectedCompiler = entry.compilerVersion();
                } else if (!selectedRoute.equals(entry.route())
                        || !selectedAbi.equals(entry.resultAbi())
                        || !selectedCompiler.equals(entry.compilerVersion())) {
                    throw new IllegalArgumentException("Qualification bundle entries must share route, ABI and compiler identity");
                }
                cases = Math.addExact(cases, entry.comparedCases());
                fields = Math.addExact(fields, entry.comparedFields());
                differences = Math.addExact(differences, entry.mismatches());
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Qualification bundle counters overflow", overflow);
        }
        this.entries = List.copyOf(supplied);
        this.byContext = Map.copyOf(contexts);
        this.route = selectedRoute;
        this.resultAbi = selectedAbi;
        this.compilerVersion = selectedCompiler;
        this.comparedCases = cases;
        this.comparedFields = fields;
        this.mismatches = differences;
    }

    public static QualifiedHookEvidenceBundle single(QualifiedHookEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence").requireAdmissible();
        return new QualifiedHookEvidenceBundle(List.of(evidence));
    }

    public List<QualifiedHookEvidence> entries() { return entries; }
    public Set<String> contextKeys() { return byContext.keySet(); }
    public QualifiedHookEvidence evidenceFor(String contextKey) { return byContext.get(contextKey); }
    public String route() { return route; }
    public String resultAbi() { return resultAbi; }
    public String compilerVersion() { return compilerVersion; }
    public long comparedCases() { return comparedCases; }
    public long comparedFields() { return comparedFields; }
    public long mismatches() { return mismatches; }

    public boolean admissible() {
        return comparedCases >= MINIMUM_CAPTURE_CASES
                && comparedFields > 0
                && mismatches == 0
                && entries.stream().allMatch(entry -> entry.comparedCases() > 0
                        && entry.comparedFields() > 0
                        && entry.completeCoverage() && entry.independentOracle());
    }

    public String rejectionReason() {
        if (admissible()) return "qualified evidence bundle accepted";
        List<String> reasons = new ArrayList<>();
        if (comparedCases < MINIMUM_CAPTURE_CASES) {
            reasons.add("aggregate compared cases " + comparedCases + " < " + MINIMUM_CAPTURE_CASES);
        }
        if (comparedFields <= 0) reasons.add("no compared fields");
        if (mismatches != 0) reasons.add("mismatches=" + mismatches);
        if (entries.stream().anyMatch(entry -> entry.comparedCases() <= 0)) {
            reasons.add("one or more contexts have no compared cases");
        }
        if (entries.stream().anyMatch(entry -> entry.comparedFields() <= 0)) {
            reasons.add("one or more contexts have no compared fields");
        }
        if (entries.stream().anyMatch(entry -> !entry.completeCoverage())) reasons.add("comparison coverage is incomplete");
        if (entries.stream().anyMatch(entry -> !entry.independentOracle())) reasons.add("oracle is not independently identified");
        return String.join("; ", reasons);
    }

    public void requireAdmissible() {
        if (!admissible()) throw new IllegalArgumentException("Qualified hook evidence bundle rejected: " + rejectionReason());
    }
}
