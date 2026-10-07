// SPDX-License-Identifier: MIT
package dev.tellurium.oracle.minecraft;

import dev.tellurium.oracle.schema.ChunkSnapshot;

public final class DeterminismPreflight {
    public record Result(boolean deterministic, String reason) {}
    public Result compare(ChunkSnapshot first, ChunkSnapshot second) { if (first == null || second == null) return new Result(false, "missing original capture"); var comparison = new ChunkSnapshotComparator().compare(first, second, java.util.EnumSet.allOf(dev.tellurium.oracle.schema.SnapshotField.class)); return new Result(comparison.passed(), comparison.reason()); }
}
