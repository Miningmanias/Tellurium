// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import dev.tellurium.neoforge.version.Version;

import dev.tellurium.neoforge.fast.BaseHeightCache;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Answers repeated terrain-height questions from {@link BaseHeightCache}. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorHeightCacheMixin {
    @Inject(method = "getBaseHeight", at = @At("HEAD"), cancellable = true)
    private void tellurium$rememberedHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState,
                                               CallbackInfoReturnable<Integer> callback) {
        if (!BaseHeightCache.ENABLED) return;
        int height = BaseHeightCache.get(this, randomState, x, z, type.ordinal(), Version.minY(level), level.getHeight());
        if (height != Integer.MIN_VALUE) callback.setReturnValue(height);
    }

    @Inject(method = "getBaseHeight", at = @At("RETURN"))
    private void tellurium$rememberHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState,
                                             CallbackInfoReturnable<Integer> callback) {
        if (!BaseHeightCache.ENABLED) return;
        BaseHeightCache.put(this, randomState, x, z, type.ordinal(), Version.minY(level), level.getHeight(), callback.getReturnValueI());
    }
}
