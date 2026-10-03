// SPDX-License-Identifier: MIT
package dev.worldgennext.engine.worldgen;

import dev.worldgennext.engine.GenerationStage;

public record EndpointCompletion(GenerationStage endpoint, boolean completed, String detail, long compared, long mismatches) {
    public EndpointCompletion {
        if (endpoint == null || detail == null || detail.isBlank() || compared < 0 || mismatches < 0
                || mismatches > compared) {
            throw new IllegalArgumentException("Invalid endpoint completion");
        }
    }
}
