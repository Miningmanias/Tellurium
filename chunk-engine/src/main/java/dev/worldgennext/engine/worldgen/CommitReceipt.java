// SPDX-License-Identifier: MIT
package dev.worldgennext.engine.worldgen;

import dev.worldgennext.semantic.execution.ExecutionReceipt;

public record CommitReceipt(boolean committed, String detail, long revision, ExecutionReceipt execution) {
    public CommitReceipt { if (detail == null || detail.isBlank()) throw new IllegalArgumentException("Commit detail required"); if (revision < 0) throw new IllegalArgumentException("Negative revision"); }
    public static CommitReceipt rejected(String detail) { return new CommitReceipt(false, detail, 0, null); }
}
