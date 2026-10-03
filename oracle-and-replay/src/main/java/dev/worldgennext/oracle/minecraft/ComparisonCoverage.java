// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle.minecraft;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Exact expected/actual case accounting, including duplicates and unknowns. */
public final class ComparisonCoverage {
    private final Set<String> expected = new HashSet<>(), actual = new HashSet<>(), duplicate = new HashSet<>();
    private final Set<String> rawActual = new HashSet<>();
    public void expect(String key) { if (key == null || key.isBlank()) throw new IllegalArgumentException("Expected key required"); expected.add(key); }
    public void observe(String key) { if (key == null || key.isBlank()) throw new IllegalArgumentException("Actual key required"); if (!rawActual.add(key)) duplicate.add(key); actual.add(key); }
    public Set<String> expected() { return Collections.unmodifiableSet(expected); }
    public Set<String> actual() { return Collections.unmodifiableSet(actual); }
    public Set<String> duplicate() { return Collections.unmodifiableSet(duplicate); }
    public Set<String> missing() { var result = new HashSet<>(expected); result.removeAll(actual); return Collections.unmodifiableSet(result); }
    public Set<String> unknown() { var result = new HashSet<>(actual); result.removeAll(expected); return Collections.unmodifiableSet(result); }
    public boolean complete() { return !expected.isEmpty() && missing().isEmpty() && unknown().isEmpty() && duplicate.isEmpty(); }
}
