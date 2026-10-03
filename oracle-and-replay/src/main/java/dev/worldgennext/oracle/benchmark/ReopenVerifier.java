// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle.benchmark;

import dev.worldgennext.oracle.minecraft.ChunkSnapshotComparator;
import dev.worldgennext.oracle.schema.ChunkSnapshot;

public final class ReopenVerifier {
    public ChunkSnapshotComparator.Comparison compare(ChunkSnapshot expected, ChunkSnapshot reopened) { return new ChunkSnapshotComparator().compare(expected, reopened, java.util.EnumSet.allOf(dev.worldgennext.oracle.schema.SnapshotField.class)); }
}
