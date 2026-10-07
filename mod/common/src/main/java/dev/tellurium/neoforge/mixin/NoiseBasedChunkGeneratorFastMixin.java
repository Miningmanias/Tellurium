// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import dev.tellurium.neoforge.fast.ClimateColumnCache;
import dev.tellurium.neoforge.fast.FastNoiseEngine;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.BelowZeroRetrogen;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** Routes admitted NOISE fills to the batched GPU engine; everything else stays vanilla. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorFastMixin {
    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void tellurium$fastFill(Blender blender, RandomState randomState, StructureManager structures,
                                       ChunkAccess chunk, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
        FastNoiseEngine engine = FastNoiseEngine.instance();
        if (engine == null) return;
        CompletableFuture<ChunkAccess> future = engine.tryGenerate(
                (NoiseBasedChunkGenerator) (Object) this, blender, randomState, structures, chunk);
        if (future != null) callback.setReturnValue(future);
    }

    /** BIOMES: where the cache wrappers provably do not change sampled values, skip building the NoiseChunk. */
    @Inject(method = "doCreateBiomes", at = @At("HEAD"), cancellable = true)
    private void tellurium$biomesWithoutNoiseChunk(Blender blender, RandomState randomState, StructureManager structures,
                                                      ChunkAccess chunk, CallbackInfo callback) {
        Climate.Sampler sampler =
                ClimateColumnCache.unwrappedSampler(blender, randomState, chunk);
        if (sampler == null) return;
        NoiseBasedChunkGenerator self = (NoiseBasedChunkGenerator) (Object) this;
        BiomeResolver resolver = BelowZeroRetrogen.getBiomeResolver(
                blender.getBiomeResolver(self.getBiomeSource()), chunk);
        chunk.fillBiomesFromNoise(resolver, sampler);
        callback.cancel();
    }

    /** BIOMES: evaluate provably Y-independent climate functions once per quart column. */
    @Redirect(method = "doCreateBiomes", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkAccess;fillBiomesFromNoise(Lnet/minecraft/world/level/biome/BiomeResolver;Lnet/minecraft/world/level/biome/Climate$Sampler;)V"))
    private void tellurium$columnCachedClimate(ChunkAccess target, BiomeResolver resolver,
                                                  Climate.Sampler sampler,
                                                  Blender blender, RandomState randomState, StructureManager structures, ChunkAccess chunk) {
        target.fillBiomesFromNoise(resolver, ClimateColumnCache.wrap(sampler, blender, randomState, chunk));
    }
}
