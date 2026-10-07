// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.BelowZeroRetrogen;

import java.util.Objects;

/**
 * Version-pinned tail of {@code ChunkStatusTasks.generateNoise}.
 *
 * <p>The qualified hook replaces the generator's {@code fillFromNoise}
 * future, so it must retain the small piece of vanilla work that follows
 * that future before {@code ChunkStep} advances the status.  Keeping this
 * operation separate makes the ordering visible and prevents a provider from
 * accidentally claiming that result publication also performed retrogen.</p>
 */
public final class MinecraftNoisePostProcessor {
    private MinecraftNoisePostProcessor() { }

    /** Applies the vanilla 1.21.1 post-noise retrogen operations in order. */
    public static void apply(ChunkAccess target) {
        Objects.requireNonNull(target, "target");
        if (!(target instanceof ProtoChunk proto)) return;
        BelowZeroRetrogen retrogen = proto.getBelowZeroRetrogen();
        if (retrogen == null) return;
        BelowZeroRetrogen.replaceOldBedrock(proto);
        if (retrogen.hasBedrockHoles()) retrogen.applyBedrockMask(proto);
    }
}
