// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import dev.tellurium.material.chunk.ChunkNoiseResult;
import dev.tellurium.material.chunk.ChunkResultValidator;

public final class MinecraftResultValidator {
    public ChunkResultValidator.Validation validate(ChunkNoiseResult result, String context, String registry) { return ChunkResultValidator.validate(result, context, registry); }
}
