// SPDX-License-Identifier: MIT
package dev.tellurium.engine.worldgen;

import dev.tellurium.material.chunk.ChunkNoiseResult;
import dev.tellurium.semantic.execution.ExecutionReceipt;

/** Loader-injected mutation boundary. */
public interface ChunkCommitter {
    CommitReceipt commit(CommitToken token, ChunkNoiseResult result, ExecutionReceipt execution) throws Exception;
    default void rollback(CommitToken token) throws Exception {}
}
