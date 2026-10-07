// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import dev.tellurium.neoforge.threading.IdleUnloads;
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
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Queue;
import java.util.function.BooleanSupplier;

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
public abstract class ChunkMapUnloadTypeMixin implements IdleUnloads {
    /** Runs one queued unload (the save of one chunk that already left the holder map); false when none is queued. */
    @Override
    public boolean tellurium$runIdleUnload() {
        if (!tellurium$STEADY_UNLOADS) return false;
        Runnable unload = unloadQueue.poll();
        if (unload == null) return false;
        unload.run();
        return true;
    }

    @Unique private static final boolean tellurium$ENABLED =
            Boolean.parseBoolean(System.getProperty("tellurium.unloadTypeCache", "true"));
    @Unique private static final int tellurium$LIMIT = 1 << 21;

    @Shadow @Final private Long2ByteMap chunkTypeCache;

    /** Server thread only, like chunkTypeCache. */
    @Unique private final Long2ByteOpenHashMap tellurium$knownTypes = new Long2ByteOpenHashMap();

    @Unique
    private void tellurium$remember(long pos, byte type) {
        if (!tellurium$ENABLED) return;
        if (tellurium$knownTypes.size() >= tellurium$LIMIT) tellurium$knownTypes.clear(); // unknown positions read storage
        tellurium$knownTypes.put(pos, type);
    }

    // Unload pacing.  Vanilla saves the whole unload queue in one tick whenever the tick has time left or
    // more than 2,000 chunks are waiting.  During generation this feeds on itself: while the server thread
    // saves a burst, finished chunks cannot be promoted, so every generation task in flight piles up one
    // step short of FULL; when the burst ends they are promoted together, release their neighbourhoods
    // together, and thousands of chunks become unloadable in the same tick: the next burst.  Each cycle
    // empties the pipeline.  During the regular tick this saves a quarter of the queue (at least 128
    // chunks) instead; which chunks unload and what is saved is unchanged, and the shutdown and save-all
    // paths, which call processUnloads outside the tick, still drain everything.
    @Unique private static final boolean tellurium$STEADY_UNLOADS =
            Boolean.parseBoolean(System.getProperty("tellurium.unloadPacing", "true"));
    @Unique private boolean tellurium$inTick;
    @Unique private int tellurium$unloadBudget;

    @Shadow @Final private Queue<Runnable> unloadQueue;

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void tellurium$enterTick(BooleanSupplier hasTime, CallbackInfo callback) {
        tellurium$inTick = tellurium$STEADY_UNLOADS;
        tellurium$unloadBudget = -1;
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"))
    private void tellurium$leaveTick(BooleanSupplier hasTime, CallbackInfo callback) {
        tellurium$inTick = false;
    }

    @Redirect(method = "processUnloads", at = @At(value = "INVOKE",
            target = "Ljava/util/function/BooleanSupplier;getAsBoolean()Z", ordinal = 1))
    private boolean tellurium$pacedUnloads(BooleanSupplier hasTime) {
        if (!tellurium$inTick) return hasTime.getAsBoolean();
        if (tellurium$unloadBudget < 0) tellurium$unloadBudget = Math.max(128, unloadQueue.size() / 4);
        return tellurium$unloadBudget-- > 0;
    }

    @ModifyConstant(method = "processUnloads",
            constant = @Constant(intValue = 2000, ordinal = 1))
    private int tellurium$unloadQueueAllowance(int vanilla) {
        return tellurium$inTick ? Integer.MAX_VALUE : vanilla;
    }

    @Inject(method = "markPositionReplaceable", at = @At("HEAD"))
    private void tellurium$rememberReplaceable(ChunkPos pos, CallbackInfo callback) {
        tellurium$remember(pos.toLong(), (byte) -1);
    }

    @Inject(method = "markPosition", at = @At("HEAD"))
    private void tellurium$rememberType(ChunkPos pos, ChunkType type, CallbackInfoReturnable<Byte> callback) {
        tellurium$remember(pos.toLong(), (byte) (type == ChunkType.PROTOCHUNK ? -1 : 1));
    }

    @Inject(method = "isExistingChunkFull", at = @At("HEAD"), cancellable = true)
    private void tellurium$answerFromKnownType(ChunkPos pos, CallbackInfoReturnable<Boolean> callback) {
        if (!tellurium$ENABLED) return;
        long key = pos.toLong();
        if (chunkTypeCache.get(key) != 0) return;
        byte known = tellurium$knownTypes.remove(key);
        if (known != 0) {
            chunkTypeCache.put(key, known);
            callback.setReturnValue(known == 1);
        }
    }
}
