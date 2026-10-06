// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import dev.worldgennext.neoforge.fast.FastSurfaceState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Persists the "surface already applied" mark of a chunk saved at NOISE
 * status, so a reload skips the SURFACE step instead of running the surface
 * rules over an already surfaced chunk.
 */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerSurfaceMixin {
    @Inject(method = "write", at = @At("RETURN"))
    private static void worldgenNext$writeSurfaceMark(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<CompoundTag> callback) {
        if (chunk.getPersistedStatus() == ChunkStatus.NOISE && FastSurfaceState.applied(chunk)) {
            callback.getReturnValue().putBoolean(FastSurfaceState.TAG, true);
        }
    }

    @Inject(method = "read", at = @At("RETURN"))
    private static void worldgenNext$readSurfaceMark(ServerLevel level, PoiManager poi, RegionStorageInfo info, ChunkPos pos,
                                                     CompoundTag tag, CallbackInfoReturnable<ProtoChunk> callback) {
        ProtoChunk chunk = callback.getReturnValue();
        if (chunk != null && tag.getBoolean(FastSurfaceState.TAG) && chunk.getPersistedStatus() == ChunkStatus.NOISE) {
            FastSurfaceState.mark(chunk);
        }
    }
}
