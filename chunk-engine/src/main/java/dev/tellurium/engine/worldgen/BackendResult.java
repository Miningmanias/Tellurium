// SPDX-License-Identifier: MIT
package dev.tellurium.engine.worldgen;

import dev.tellurium.engine.ExecutionRoute;
import dev.tellurium.material.chunk.ChunkNoiseResult;
import dev.tellurium.semantic.execution.ExecutionReceipt;
import java.util.Objects;

public record BackendResult(ExecutionRoute route, ExecutionReceipt receipt, ChunkNoiseResult result) {
    public BackendResult { Objects.requireNonNull(route, "route"); Objects.requireNonNull(receipt, "receipt"); Objects.requireNonNull(result, "result"); }
}
