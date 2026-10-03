// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle;

import java.util.Map;

/** Actual comparisons, with evidence kept per fixture so partial work stays visible. */
public record FixtureResult(String name, int expectedSamples, int comparedSamples, int mismatches,
                            String error, Map<String, String> evidence) {
    public FixtureResult {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Fixture name required");
        if (expectedSamples < 1 || comparedSamples < 0 || comparedSamples > expectedSamples || mismatches < 0 || mismatches > comparedSamples)
            throw new IllegalArgumentException("Invalid fixture comparison counts");
        error = error == null ? "" : error;
        evidence = Map.copyOf(evidence);
    }
    public boolean passed() { return error.isEmpty() && comparedSamples == expectedSamples && mismatches == 0; }
}
