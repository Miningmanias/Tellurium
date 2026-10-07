// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import dev.tellurium.neoforge.fast.ColumnBiomeIndex;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Answers biome parameter lookups through {@link ColumnBiomeIndex} when the list's search tree could be flattened. */
@Mixin(Climate.ParameterList.class)
public abstract class ClimateParameterListMixin {
    /** A ColumnBiomeIndex, or Boolean.FALSE when this list keeps the original search. */
    @Unique private volatile Object tellurium$index;

    @Inject(method = "findValueIndex(Lnet/minecraft/world/level/biome/Climate$TargetPoint;)Ljava/lang/Object;",
            at = @At("HEAD"), cancellable = true)
    private void tellurium$columnIndexed(Climate.TargetPoint target, CallbackInfoReturnable<Object> callback) {
        if (!ColumnBiomeIndex.ENABLED) return;
        Object index = tellurium$index;
        if (index == null) {
            ColumnBiomeIndex built = ColumnBiomeIndex.build((Climate.ParameterList<?>) (Object) this);
            index = built == null ? Boolean.FALSE : built;
            tellurium$index = index;
        }
        if (index instanceof ColumnBiomeIndex fast) callback.setReturnValue(fast.find(target));
    }
}
