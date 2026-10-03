// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import dev.worldgennext.neoforge.threading.ShapeFullBlockCache;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes Block.isShapeFullBlock to a per-thread memo; see {@link ShapeFullBlockCache}. */
@Mixin(Block.class)
public abstract class BlockShapeCacheMixin {
    @Inject(method = "isShapeFullBlock", at = @At("HEAD"), cancellable = true)
    private static void worldgenNext$perThreadShapeCache(VoxelShape shape, CallbackInfoReturnable<Boolean> callback) {
        if (ShapeFullBlockCache.ENABLED) callback.setReturnValue(ShapeFullBlockCache.isFullBlock(shape));
    }
}
