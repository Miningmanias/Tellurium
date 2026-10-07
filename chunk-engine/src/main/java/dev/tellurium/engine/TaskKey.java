// SPDX-License-Identifier: MIT
package dev.tellurium.engine;

import dev.tellurium.semantic.WorldgenIdentity;
import java.util.Objects;

public record TaskKey(WorldgenIdentity world, int chunkX, int chunkZ, GenerationStage stage) {
    public TaskKey { Objects.requireNonNull(world, "world"); Objects.requireNonNull(stage, "stage"); }
}
