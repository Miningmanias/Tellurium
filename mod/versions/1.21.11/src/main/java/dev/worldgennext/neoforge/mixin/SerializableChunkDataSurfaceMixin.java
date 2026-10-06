// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import dev.worldgennext.neoforge.version.Version;

import com.google.common.collect.MapMaker;
import dev.worldgennext.neoforge.fast.FastSurfaceState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Persists the "surface already applied" mark of a chunk saved at NOISE status, so a reload skips the SURFACE
 * step instead of running the surface rules over an already surfaced chunk.  The 1.21.4 form of 1.21.1's
 * ChunkSerializerSurfaceMixin: since 1.21.2 a chunk passes through a SerializableChunkData on its way to and
 * from disk, written and parsed apart from the chunk, so the mark rides on that object in between.
 */
@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataSurfaceMixin {
    /** The data objects that carry the mark, by identity (the record's own equality would compare whole chunks). */
    @Unique private static final Map<Object, Boolean> worldgenNext$MARKED = new MapMaker().weakKeys().makeMap();

    @Inject(method = "copyOf", at = @At("RETURN"))
    private static void worldgenNext$noteSurfaceMark(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<SerializableChunkData> callback) {
        if (chunk.getPersistedStatus() == ChunkStatus.NOISE && FastSurfaceState.applied(chunk)) {
            worldgenNext$MARKED.put(callback.getReturnValue(), Boolean.TRUE);
        }
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void worldgenNext$writeSurfaceMark(CallbackInfoReturnable<CompoundTag> callback) {
        if (worldgenNext$MARKED.containsKey(this)) callback.getReturnValue().putBoolean(FastSurfaceState.TAG, true);
    }

    @Inject(method = "parse", at = @At("RETURN"))
    private static void worldgenNext$parseSurfaceMark(LevelHeightAccessor heights, PalettedContainerFactory containers, CompoundTag tag,
                                                      CallbackInfoReturnable<SerializableChunkData> callback) {
        if (callback.getReturnValue() != null && Version.flag(tag, FastSurfaceState.TAG)) {
            worldgenNext$MARKED.put(callback.getReturnValue(), Boolean.TRUE);
        }
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void worldgenNext$readSurfaceMark(ServerLevel level, PoiManager poi, RegionStorageInfo info, ChunkPos pos,
                                              CallbackInfoReturnable<ProtoChunk> callback) {
        ProtoChunk chunk = callback.getReturnValue();
        if (chunk != null && worldgenNext$MARKED.containsKey(this) && chunk.getPersistedStatus() == ChunkStatus.NOISE) {
            FastSurfaceState.mark(chunk);
        }
    }
}
