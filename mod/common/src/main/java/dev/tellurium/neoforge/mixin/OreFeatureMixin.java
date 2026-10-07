// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import dev.tellurium.neoforge.fast.FastOrePlacement;
import dev.tellurium.neoforge.fast.OrePlacementVerifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Runs ore vein placement through {@link FastOrePlacement}, the same algorithm with a cheaper scan. */
@Mixin(OreFeature.class)
public abstract class OreFeatureMixin {
    /** Set while the verifier runs the original body on this thread. */
    @Unique private static final ThreadLocal<Boolean> tellurium$ORIGINAL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Shadow
    protected abstract boolean doPlace(WorldGenLevel level, RandomSource random, OreConfiguration config,
                                       double minXCentre, double maxXCentre, double minZCentre, double maxZCentre,
                                       double minYCentre, double maxYCentre, int x, int y, int z, int width, int height);

    @Inject(method = "doPlace", at = @At("HEAD"), cancellable = true)
    private void tellurium$cheaperScan(WorldGenLevel level, RandomSource random, OreConfiguration config,
                                          double minXCentre, double maxXCentre, double minZCentre, double maxZCentre,
                                          double minYCentre, double maxYCentre, int x, int y, int z, int width, int height,
                                          CallbackInfoReturnable<Boolean> callback) {
        // Only the exact vanilla class: a subclass may override pieces this copy does not call.
        if (!FastOrePlacement.ENABLED || ((Object) this).getClass() != OreFeature.class) return;
        if (OrePlacementVerifier.ENABLED) {
            if (tellurium$ORIGINAL.get()) return;
            callback.setReturnValue(OrePlacementVerifier.run(level, random, config, minXCentre, maxXCentre, minZCentre, maxZCentre,
                    minYCentre, maxYCentre, x, y, z, width, height, recorded -> {
                        tellurium$ORIGINAL.set(Boolean.TRUE);
                        try {
                            return doPlace(level, recorded, config, minXCentre, maxXCentre, minZCentre, maxZCentre,
                                    minYCentre, maxYCentre, x, y, z, width, height);
                        } finally {
                            tellurium$ORIGINAL.set(Boolean.FALSE);
                        }
                    }));
            return;
        }
        callback.setReturnValue(FastOrePlacement.doPlace(level, random, config, minXCentre, maxXCentre, minZCentre, maxZCentre,
                minYCentre, maxYCentre, x, y, z, width, height));
    }
}
