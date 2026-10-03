// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import dev.worldgennext.neoforge.fast.LazyMappedDensity;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Defers the constructor's router mapping until a function is first used; see
 * {@link LazyMappedDensity}.  Everything is resolved, in the constructor's
 * order, before the first slice is filled, so the CPU noise fill sees the same
 * interpolators and cell caches as the original.
 */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkLazyWrapMixin {
    @Unique private List<LazyMappedDensity> worldgenNext$deferred;

    @Unique
    private DensityFunction worldgenNext$defer(DensityFunction source, DensityFunction.Visitor visitor) {
        if (worldgenNext$deferred == null) worldgenNext$deferred = new ArrayList<>(16);
        LazyMappedDensity lazy = new LazyMappedDensity(source, visitor);
        worldgenNext$deferred.add(lazy);
        return lazy;
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;"))
    private NoiseRouter worldgenNext$deferRouter(NoiseRouter router, DensityFunction.Visitor visitor) {
        if (!LazyMappedDensity.ENABLED) return router.mapAll(visitor);
        return new NoiseRouter(
                worldgenNext$defer(router.barrierNoise(), visitor),
                worldgenNext$defer(router.fluidLevelFloodednessNoise(), visitor),
                worldgenNext$defer(router.fluidLevelSpreadNoise(), visitor),
                worldgenNext$defer(router.lavaNoise(), visitor),
                worldgenNext$defer(router.temperature(), visitor),
                worldgenNext$defer(router.vegetation(), visitor),
                worldgenNext$defer(router.continents(), visitor),
                worldgenNext$defer(router.erosion(), visitor),
                worldgenNext$defer(router.depth(), visitor),
                worldgenNext$defer(router.ridges(), visitor),
                worldgenNext$defer(router.initialDensityWithoutJaggedness(), visitor),
                worldgenNext$defer(router.finalDensity(), visitor),
                worldgenNext$defer(router.veinToggle(), visitor),
                worldgenNext$defer(router.veinRidged(), visitor),
                worldgenNext$defer(router.veinGap(), visitor));
    }

    /** The cell-cached final density: its source holds the deferred router function, resolved first when this one is. */
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;"))
    private DensityFunction worldgenNext$deferFinalDensity(DensityFunction function, DensityFunction.Visitor visitor) {
        if (!LazyMappedDensity.ENABLED) return function.mapAll(visitor);
        return worldgenNext$defer(function, visitor);
    }

    @Inject(method = "initializeForFirstCellX", at = @At("HEAD"))
    private void worldgenNext$resolveBeforeInterpolation(CallbackInfo callback) {
        List<LazyMappedDensity> deferred = worldgenNext$deferred;
        if (deferred == null) return;
        worldgenNext$deferred = null;
        for (LazyMappedDensity lazy : deferred) lazy.resolve();
    }
}
