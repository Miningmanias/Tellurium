// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import dev.tellurium.neoforge.version.Version;

import dev.tellurium.neoforge.fast.LazyMappedDensity;
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
    @Unique private List<LazyMappedDensity> tellurium$deferred;

    @Unique
    private DensityFunction tellurium$defer(DensityFunction source, DensityFunction.Visitor visitor) {
        if (tellurium$deferred == null) tellurium$deferred = new ArrayList<>(16);
        LazyMappedDensity lazy = new LazyMappedDensity(source, visitor);
        tellurium$deferred.add(lazy);
        return lazy;
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;"))
    private NoiseRouter tellurium$deferRouter(NoiseRouter router, DensityFunction.Visitor visitor) {
        if (!LazyMappedDensity.ENABLED) return router.mapAll(visitor);
        return new NoiseRouter(
                tellurium$defer(router.barrierNoise(), visitor),
                tellurium$defer(router.fluidLevelFloodednessNoise(), visitor),
                tellurium$defer(router.fluidLevelSpreadNoise(), visitor),
                tellurium$defer(router.lavaNoise(), visitor),
                tellurium$defer(router.temperature(), visitor),
                tellurium$defer(router.vegetation(), visitor),
                tellurium$defer(router.continents(), visitor),
                tellurium$defer(router.erosion(), visitor),
                tellurium$defer(router.depth(), visitor),
                tellurium$defer(router.ridges(), visitor),
                tellurium$defer(Version.preliminarySurface(router), visitor),
                tellurium$defer(router.finalDensity(), visitor),
                tellurium$defer(router.veinToggle(), visitor),
                tellurium$defer(router.veinRidged(), visitor),
                tellurium$defer(router.veinGap(), visitor));
    }

    /** The cell-cached final density: its source holds the deferred router function, resolved first when this one is. */
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/DensityFunction;"))
    private DensityFunction tellurium$deferFinalDensity(DensityFunction function, DensityFunction.Visitor visitor) {
        if (!LazyMappedDensity.ENABLED) return function.mapAll(visitor);
        return tellurium$defer(function, visitor);
    }

    @Inject(method = "initializeForFirstCellX", at = @At("HEAD"))
    private void tellurium$resolveBeforeInterpolation(CallbackInfo callback) {
        List<LazyMappedDensity> deferred = tellurium$deferred;
        if (deferred == null) return;
        tellurium$deferred = null;
        for (LazyMappedDensity lazy : deferred) lazy.resolve();
    }
}
