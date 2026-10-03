// SPDX-License-Identifier: MIT
package dev.worldgennext.engine.worldgen;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface StageExecutor {
    CompletionStage<BackendResult> execute(GenerationRequest request, ResourceReservation reservation);
}
