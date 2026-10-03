// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import dev.worldgennext.semantic.material.AquiferProgram;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.Objects;

/**
 * Version-pinned creation boundary for the cached Minecraft {@link NoiseChunk}.
 *
 * <p>In 1.21.1 the BIOMES task creates this object and the later NOISE,
 * SURFACE, and CARVERS tasks reuse it through {@code ChunkAccess}. A candidate
 * NOISE route must therefore preserve the object even when it replaces the
 * generator's block-filling loop. The factory below mirrors
 * {@code NoiseBasedChunkGenerator.createNoiseChunk}; it only creates the
 * object when the target does not already have one.</p>
 */
public final class MinecraftNoiseChunkLifecycle {
    private MinecraftNoiseChunkLifecycle() { }

    /**
     * Returns the target's existing NoiseChunk or creates the exact
     * version-pinned equivalent without filling or mutating block storage.
     */
    public static NoiseChunk ensure(ServerLevel level, ChunkAccess target,
                                    NoiseGeneratorSettings settings,
                                    StructureManager structures, Blender blender) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(structures, "structures");
        Objects.requireNonNull(blender, "blender");
        return target.getOrCreateNoiseChunk(chunk -> NoiseChunk.forChunk(
                chunk,
                level.getChunkSource().randomState(),
                Beardifier.forStructuresInChunk(structures, chunk.getPos()),
                settings,
                globalFluidPicker(settings),
                blender));
    }

    /** Same global fluid picker used by the pinned NoiseBasedChunkGenerator. */
    private static Aquifer.FluidPicker globalFluidPicker(NoiseGeneratorSettings settings) {
        int globalLavaLevel = AquiferProgram.VANILLA_GLOBAL_LAVA_LEVEL;
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(globalLavaLevel, Blocks.LAVA.defaultBlockState());
        Aquifer.FluidStatus water = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        return (x, y, z) -> y < Math.min(globalLavaLevel, settings.seaLevel()) ? lava : water;
    }
}
