// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import dev.worldgennext.neoforge.threading.AsyncChunkLoad;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The ChunkSerializer half of {@link AsyncChunkLoad}. */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerAsyncLoadMixin {
    /** Server thread: hand out the chunk a worker already read from this tag. */
    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private static void worldgenNext$usePreparedChunk(ServerLevel level, PoiManager poiManager, RegionStorageInfo storageInfo, ChunkPos pos,
                                                      CompoundTag tag, CallbackInfoReturnable<ProtoChunk> callback) {
        if (!AsyncChunkLoad.ENABLED) return;
        AsyncChunkLoad.Prepared prepared = AsyncChunkLoad.take(tag);
        if (prepared == null) return;
        for (int i = 0; i < prepared.positions().size(); i++) {
            poiManager.checkConsistencyWithBlocks(prepared.positions().get(i), prepared.sections().get(i));
        }
        callback.setReturnValue(prepared.chunk());
    }

    /** Worker thread: the point-of-interest storage belongs to the server thread; record the check for later. */
    @Redirect(method = "read", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;checkConsistencyWithBlocks(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/chunk/LevelChunkSection;)V"))
    private static void worldgenNext$deferConsistencyCheck(PoiManager poiManager, SectionPos position, LevelChunkSection section) {
        if (!AsyncChunkLoad.defer(position, section)) poiManager.checkConsistencyWithBlocks(position, section);
    }
}
