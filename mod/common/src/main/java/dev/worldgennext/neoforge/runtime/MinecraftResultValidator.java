// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import dev.worldgennext.material.chunk.ChunkNoiseResult;
import dev.worldgennext.material.chunk.ChunkResultValidator;

public final class MinecraftResultValidator {
    public ChunkResultValidator.Validation validate(ChunkNoiseResult result, String context, String registry) { return ChunkResultValidator.validate(result, context, registry); }
}
