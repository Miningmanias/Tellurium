// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import dev.tellurium.material.chunk.ChunkNoiseResult;

public interface MinecraftStageAdapter {
    Object captureInputs(Object chunk, MinecraftOwnershipToken ownership);
    void continueOriginalStages(Object chunk, ChunkNoiseResult noiseResult);
}
