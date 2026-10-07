// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CarvingContext.class)
public interface CarvingContextAccessor {
    @Accessor("randomState")
    RandomState tellurium$randomState();
}
