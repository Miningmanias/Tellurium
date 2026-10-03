// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import dev.worldgennext.neoforge.fast.FastNoiseEngine;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** Routes admitted NOISE fills to the batched GPU engine; everything else stays vanilla. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorFastMixin {
    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void worldgenNext$fastFill(Blender blender, RandomState randomState, StructureManager structures,
                                       ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
        FastNoiseEngine engine = FastNoiseEngine.instance();
        if (engine == null) return;
        CompletableFuture<ChunkAccess> future = engine.tryGenerate(
                (NoiseBasedChunkGenerator) (Object) this, blender, randomState, structures, chunk);
        if (future != null) callback.setReturnValue(future);
    }
}
