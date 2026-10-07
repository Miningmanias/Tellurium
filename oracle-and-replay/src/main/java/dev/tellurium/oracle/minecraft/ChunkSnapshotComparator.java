// SPDX-License-Identifier: MIT
package dev.tellurium.oracle.minecraft;

import dev.tellurium.oracle.schema.ChunkSnapshot;
import dev.tellurium.oracle.schema.SnapshotField;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Field-by-field logical comparator. Checksums are reported only as evidence, never as equality. */
public final class ChunkSnapshotComparator {
    public record Difference(String key, String expected, String actual) {}
    public record Comparison(boolean passed, int comparedFields, List<Difference> differences,
                             ComparisonCoverage coverage, String reason) {
        public Comparison { differences = List.copyOf(differences); }
    }
    public Comparison compare(ChunkSnapshot expected, ChunkSnapshot actual, java.util.Set<SnapshotField> requiredFields) {
        if (expected == null || actual == null) return new Comparison(false, 0, List.of(), new ComparisonCoverage(), "missing snapshot");
        if (!expected.identity().seed().equals(actual.identity().seed()) || !expected.identity().dimension().equals(actual.identity().dimension()) || expected.identity().chunkX() != actual.identity().chunkX() || expected.identity().chunkZ() != actual.identity().chunkZ()) return new Comparison(false, 0, List.of(), new ComparisonCoverage(), "capture identity mismatch");
        var required = EnumSet.copyOf(requiredFields == null || requiredFields.isEmpty() ? EnumSet.allOf(SnapshotField.class) : requiredFields);
        var coverage = new ComparisonCoverage(); coverage.expect(expected.identity().caseKey()); coverage.observe(actual.identity().caseKey());
        var differences = new ArrayList<Difference>(); int compared = 0;
        for (SnapshotField field : required) { String left = expected.field(field), right = actual.field(field);
            // Two snapshots that both carry no block data are equal and have compared nothing: block coverage
            // has to be there (an all-air chunk still lists its positions) before a case counts as covered.
            if (field == SnapshotField.BLOCK_STATES && left != null && right != null && (left.isBlank() || right.isBlank())) { differences.add(new Difference(field.name(), "<empty block payload>", "<empty block payload>")); continue; }
            if (left == null || right == null) differences.add(new Difference(field.name(), String.valueOf(left), String.valueOf(right))); else { compared++; if (!left.equals(right)) differences.add(new Difference(field.name(), left, right)); } }
        expected.values().keySet().forEach(key -> { String left = expected.value(key), right = actual.value(key); if (right == null || !left.equals(right)) differences.add(new Difference(key, left, right)); else { } });
        actual.values().keySet().stream().filter(key -> expected.value(key) == null).forEach(key -> differences.add(new Difference(key, null, actual.value(key))));
        boolean pass = coverage.complete() && differences.isEmpty() && compared == required.size(); return new Comparison(pass, compared, differences, coverage, pass ? "equal" : "logical fields differ or coverage incomplete");
    }
}
