// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import dev.tellurium.neoforge.version.Version;

import com.google.common.collect.MapMaker;
import dev.tellurium.neoforge.fast.FastSurfaceState;
import net.minecraft.core.RegistryAccess;
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
    @Unique private static final Map<Object, Boolean> tellurium$MARKED = new MapMaker().weakKeys().makeMap();

    @Inject(method = "copyOf", at = @At("RETURN"))
    private static void tellurium$noteSurfaceMark(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<SerializableChunkData> callback) {
        if (chunk.getPersistedStatus() == ChunkStatus.NOISE && FastSurfaceState.applied(chunk)) {
            tellurium$MARKED.put(callback.getReturnValue(), Boolean.TRUE);
        }
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void tellurium$writeSurfaceMark(CallbackInfoReturnable<CompoundTag> callback) {
        if (tellurium$MARKED.containsKey(this)) callback.getReturnValue().putBoolean(FastSurfaceState.TAG, true);
    }

    @Inject(method = "parse", at = @At("RETURN"))
    private static void tellurium$parseSurfaceMark(LevelHeightAccessor heights, RegistryAccess registries, CompoundTag tag,
                                                      CallbackInfoReturnable<SerializableChunkData> callback) {
        if (callback.getReturnValue() != null && Version.flag(tag, FastSurfaceState.TAG)) {
            tellurium$MARKED.put(callback.getReturnValue(), Boolean.TRUE);
        }
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void tellurium$readSurfaceMark(ServerLevel level, PoiManager poi, RegionStorageInfo info, ChunkPos pos,
                                              CallbackInfoReturnable<ProtoChunk> callback) {
        ProtoChunk chunk = callback.getReturnValue();
        if (chunk != null && tellurium$MARKED.containsKey(this) && chunk.getPersistedStatus() == ChunkStatus.NOISE) {
            FastSurfaceState.mark(chunk);
        }
    }
}
