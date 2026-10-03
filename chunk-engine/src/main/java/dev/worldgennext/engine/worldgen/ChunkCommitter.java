// SPDX-License-Identifier: MIT
package dev.worldgennext.engine.worldgen;

import dev.worldgennext.material.chunk.ChunkNoiseResult;
import dev.worldgennext.semantic.execution.ExecutionReceipt;

/** Loader-injected mutation boundary. */
public interface ChunkCommitter {
    CommitReceipt commit(CommitToken token, ChunkNoiseResult result, ExecutionReceipt execution) throws Exception;
    default void rollback(CommitToken token) throws Exception {}
}
