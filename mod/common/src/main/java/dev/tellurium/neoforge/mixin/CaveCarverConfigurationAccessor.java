// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import net.minecraft.util.valueproviders.FloatProvider;
import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CaveCarverConfiguration.class)
public interface CaveCarverConfigurationAccessor {
    @Accessor("floorLevel")
    FloatProvider tellurium$floorLevel();
}
