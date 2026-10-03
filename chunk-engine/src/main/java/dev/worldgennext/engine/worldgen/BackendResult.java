// SPDX-License-Identifier: MIT
package dev.worldgennext.engine.worldgen;

import dev.worldgennext.engine.ExecutionRoute;
import dev.worldgennext.material.chunk.ChunkNoiseResult;
import dev.worldgennext.semantic.execution.ExecutionReceipt;
import java.util.Objects;

public record BackendResult(ExecutionRoute route, ExecutionReceipt receipt, ChunkNoiseResult result) {
    public BackendResult { Objects.requireNonNull(route, "route"); Objects.requireNonNull(receipt, "receipt"); Objects.requireNonNull(result, "result"); }
}
