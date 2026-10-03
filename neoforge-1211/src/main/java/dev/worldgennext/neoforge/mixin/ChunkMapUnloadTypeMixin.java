// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge drops a chunk's entry from ChunkMap.chunkTypeCache right before the
 * unload save.  Saving a proto chunk then asks "is the chunk on disk already
 * full?", misses the cache and answers with a blocking region read on the
 * server thread, once per unloaded proto chunk.
 *
 * <p>The dropped entry was the answer: it is only ever written from what was
 * just read from or written to storage.  This mixin remembers the last known
 * type per position and restores it on such a miss, exactly as the read would
 * have re-populated the cache.  Positions it does not know still take the
 * original read.</p>
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapUnloadTypeMixin {
    @Unique private static final boolean worldgenNext$ENABLED =
            Boolean.parseBoolean(System.getProperty("worldgennext.unloadTypeCache", "true"));
    @Unique private static final int worldgenNext$LIMIT = 1 << 21;

    @Shadow @Final private Long2ByteMap chunkTypeCache;

    /** Server thread only, like chunkTypeCache. */
    @Unique private final Long2ByteOpenHashMap worldgenNext$knownTypes = new Long2ByteOpenHashMap();

    @Unique
    private void worldgenNext$remember(long pos, byte type) {
        if (!worldgenNext$ENABLED) return;
        if (worldgenNext$knownTypes.size() >= worldgenNext$LIMIT) worldgenNext$knownTypes.clear(); // unknown positions read storage
        worldgenNext$knownTypes.put(pos, type);
    }

    @Inject(method = "markPositionReplaceable", at = @At("HEAD"))
    private void worldgenNext$rememberReplaceable(ChunkPos pos, CallbackInfo callback) {
        worldgenNext$remember(pos.toLong(), (byte) -1);
    }

    @Inject(method = "markPosition", at = @At("HEAD"))
    private void worldgenNext$rememberType(ChunkPos pos, ChunkType type, CallbackInfoReturnable<Byte> callback) {
        worldgenNext$remember(pos.toLong(), (byte) (type == ChunkType.PROTOCHUNK ? -1 : 1));
    }

    @Inject(method = "isExistingChunkFull", at = @At("HEAD"), cancellable = true)
    private void worldgenNext$answerFromKnownType(ChunkPos pos, CallbackInfoReturnable<Boolean> callback) {
        if (!worldgenNext$ENABLED) return;
        long key = pos.toLong();
        if (chunkTypeCache.get(key) != 0) return;
        byte known = worldgenNext$knownTypes.remove(key);
        if (known != 0) {
            chunkTypeCache.put(key, known);
            callback.setReturnValue(known == 1);
        }
    }
}
