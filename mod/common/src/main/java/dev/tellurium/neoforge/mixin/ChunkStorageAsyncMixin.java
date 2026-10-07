// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import dev.tellurium.neoforge.threading.AsyncSectionEncoding;
import dev.tellurium.neoforge.threading.PendingChunkSaves;
import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.levelgen.structure.LegacyStructureDataHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks chunk saves whose section encoding is still running on a worker.
 * A read of such a chunk waits for the save, and flush/close wait for all of
 * them, so storage never observes a state older than the last unload.
 */
@Mixin(ChunkStorage.class)
public abstract class ChunkStorageAsyncMixin implements PendingChunkSaves {
    @Shadow
    private volatile LegacyStructureDataHandler legacyStructureHandler;

    @Override
    public boolean tellurium$legacyIndexActive() { return legacyStructureHandler != null; }

    @Unique
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> tellurium$pendingSaves = new ConcurrentHashMap<>();

    @Override
    public void tellurium$track(ChunkPos pos, CompletableFuture<Void> save,
                                   AsyncSectionEncoding.Task encoding) {
        long key = tellurium$key(pos);
        tellurium$pendingSaves.put(key, save);
        tellurium$pendingEncodings.put(key, encoding);
        save.whenComplete((ignored, error) -> {
            tellurium$pendingSaves.remove(key, save);
            tellurium$pendingEncodings.remove(key, encoding);
        });
    }

    @Unique
    private final ConcurrentHashMap<Long, AsyncSectionEncoding.Task> tellurium$pendingEncodings =
            new ConcurrentHashMap<>();

    /** Long.hashCode of a packed chunk position is x ^ z, which piles neighbouring chunks into few buckets. */
    @Unique
    private static long tellurium$key(ChunkPos pos) {
        return HashCommon.mix(pos.toLong());
    }

    @Override
    public void tellurium$awaitAll() {
        for (CompletableFuture<Void> save : tellurium$pendingSaves.values().toArray(CompletableFuture[]::new)) {
            try {
                save.join();
            } catch (RuntimeException ignored) {
                // the save path already reported its failure
            }
        }
    }

    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private void tellurium$readAfterPendingSave(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> callback) {
        CompletableFuture<Void> pending = tellurium$pendingSaves.get(tellurium$key(pos));
        if (pending != null && !pending.isDone()) {
            var encoding = tellurium$pendingEncodings.get(tellurium$key(pos));
            CompletableFuture<Void> ready = pending;
            if (encoding != null) {
                encoding.expedite();
                ready = encoding.handedOff;
            }
            if (ready.isDone()) return; // already with the IO worker: the ordinary read sees the pending write
            callback.setReturnValue(ready.handle((ignored, error) -> null)
                    .thenCompose(ignored -> ((ChunkStorage) (Object) this).read(pos)));
        }
    }

    @Inject(method = "flushWorker", at = @At("HEAD"))
    private void tellurium$flushPendingSaves(CallbackInfo callback) {
        tellurium$awaitAll();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void tellurium$closePendingSaves(CallbackInfo callback) {
        tellurium$awaitAll();
    }
}
