// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.loader.mixin;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A structure template's per-block cache is a plain map that vanilla fills from several world generation
 * threads (MC-271899).  NeoForge makes it a concurrent map; on Fabric this does, because this mod runs the
 * structure step of different chunks at the same time and the unsafe map then fails quickly.  What the cache
 * holds for a block is computed from the template alone, so which thread fills it makes no difference.
 */
@Mixin(StructureTemplate.Palette.class)
public abstract class StructurePaletteCacheMixin {
    @Shadow @Final @Mutable
    private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void worldgenNext$concurrentCache(List<StructureTemplate.StructureBlockInfo> blocks, CallbackInfo callback) {
        this.cache = new ConcurrentHashMap<>();
    }
}
