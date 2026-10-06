// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import dev.worldgennext.neoforge.fast.FastSurfaceState;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the surface from being built twice.  The chunk system's own surface step already skips chunks whose
 * surface came from the fused kernels; a mod that drives the generator itself (Distant Horizons' built-in
 * generator calls {@code fillFromNoise} and then {@code buildSurface} directly) would otherwise run the surface
 * rules again over terrain that already has its surface.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorSurfaceOnceMixin {
    @Inject(method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;"
            + "Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
            at = @At("HEAD"), cancellable = true)
    private void worldgenNext$surfaceAlreadyBuilt(WorldGenRegion region, StructureManager structures, RandomState randomState,
                                                  ChunkAccess chunk, CallbackInfo callback) {
        if (FastSurfaceState.applied(chunk)) callback.cancel();
    }
}
