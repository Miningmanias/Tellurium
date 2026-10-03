// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import dev.worldgennext.neoforge.fast.FastOrePlacement;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Runs ore vein placement through {@link FastOrePlacement}, the same algorithm with its sphere terms hoisted. */
@Mixin(OreFeature.class)
public abstract class OreFeatureMixin {
    @Inject(method = "doPlace", at = @At("HEAD"), cancellable = true)
    private void worldgenNext$hoistedSphereTerms(WorldGenLevel level, RandomSource random, OreConfiguration config,
                                                 double minXCentre, double maxXCentre, double minZCentre, double maxZCentre,
                                                 double minYCentre, double maxYCentre, int x, int y, int z, int width, int height,
                                                 CallbackInfoReturnable<Boolean> callback) {
        // Only the exact vanilla class: a subclass may override pieces this copy does not call.
        if (FastOrePlacement.ENABLED && ((Object) this).getClass() == OreFeature.class) {
            callback.setReturnValue(FastOrePlacement.doPlace(level, random, config, minXCentre, maxXCentre, minZCentre, maxZCentre,
                    minYCentre, maxYCentre, x, y, z, width, height));
        }
    }
}
