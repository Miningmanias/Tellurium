// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import dev.worldgennext.material.chunk.ChunkNoiseResult;

public interface MinecraftStageAdapter {
    Object captureInputs(Object chunk, MinecraftOwnershipToken ownership);
    void continueOriginalStages(Object chunk, ChunkNoiseResult noiseResult);
}
