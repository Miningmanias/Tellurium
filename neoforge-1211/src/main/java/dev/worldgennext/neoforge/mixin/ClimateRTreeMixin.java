// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Climate.RTree.search stores its result in a ThreadLocal after every lookup,
 * even when the result is the leaf it just read from that ThreadLocal.  BIOMES
 * performs 1,536 lookups per chunk and most return the previous leaf; skipping
 * the redundant store leaves the ThreadLocal's value, and so every later
 * lookup, unchanged.
 */
@Mixin(targets = "net.minecraft.world.level.biome.Climate$RTree")
public abstract class ClimateRTreeMixin {
    @org.spongepowered.asm.mixin.Unique
    private static final boolean worldgenNext$ENABLED = Boolean.parseBoolean(System.getProperty("worldgennext.fast.rtreeStoreSkip", "true"));

    @Redirect(method = "search(Lnet/minecraft/world/level/biome/Climate$TargetPoint;Lnet/minecraft/world/level/biome/Climate$DistanceMetric;)Ljava/lang/Object;",
            at = @At(value = "INVOKE", target = "Ljava/lang/ThreadLocal;set(Ljava/lang/Object;)V"))
    private void worldgenNext$storeOnlyWhenChanged(ThreadLocal<Object> lastResult, Object leaf) {
        if (!worldgenNext$ENABLED || lastResult.get() != leaf) lastResult.set(leaf);
    }
}
