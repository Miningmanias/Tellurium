// SPDX-License-Identifier: MIT
package dev.tellurium.oracle.benchmark;

import dev.tellurium.oracle.minecraft.ChunkSnapshotComparator;
import dev.tellurium.oracle.schema.ChunkSnapshot;

public final class ReopenVerifier {
    public ChunkSnapshotComparator.Comparison compare(ChunkSnapshot expected, ChunkSnapshot reopened) { return new ChunkSnapshotComparator().compare(expected, reopened, java.util.EnumSet.allOf(dev.tellurium.oracle.schema.SnapshotField.class)); }
}
