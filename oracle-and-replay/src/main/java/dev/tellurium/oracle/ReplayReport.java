// SPDX-License-Identifier: MIT
package dev.tellurium.oracle;

import java.util.HashSet;
import java.util.List;

/** A pass requires every expected fixture and sample, never an absent mismatch counter. */
public record ReplayReport(Backend backend, int expectedFixtures, int expectedSamples, List<FixtureResult> fixtures) {
    public enum Backend { CPU, VULKAN, VULKAN_NORMAL_RANGE_DIAGNOSTIC }
    public ReplayReport {
        if (backend == null || expectedFixtures < 1 || expectedSamples < 1) throw new IllegalArgumentException("Nonzero expected coverage required");
        fixtures = List.copyOf(fixtures);
        var names = new HashSet<String>();
        for (var fixture : fixtures) if (!names.add(fixture.name())) throw new IllegalArgumentException("Duplicate fixture result: " + fixture.name());
        if (fixtures.size() > expectedFixtures || fixtures.stream().mapToLong(FixtureResult::expectedSamples).sum() > expectedSamples)
            throw new IllegalArgumentException("Results exceed expected coverage");
    }
    public int comparedSamples() { return fixtures.stream().mapToInt(FixtureResult::comparedSamples).sum(); }
    public int mismatches() { return fixtures.stream().mapToInt(FixtureResult::mismatches).sum(); }
    public int failedFixtures() { return (int) fixtures.stream().filter(f -> !f.passed()).count(); }
    public boolean passed() {
        return fixtures.size() == expectedFixtures && comparedSamples() == expectedSamples && fixtures.stream().allMatch(FixtureResult::passed);
    }
    public String verdict() { return passed() ? "PASS" : "FAILED"; }
}
