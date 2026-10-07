// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockableEventLoop.class)
public interface BlockableEventLoopAccessor {
    @Accessor("blockingCount")
    int tellurium$blockingCount();
}
