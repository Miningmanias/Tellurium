// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import dev.worldgennext.neoforge.threading.AsyncSectionEncoding;
import dev.worldgennext.neoforge.threading.PendingChunkSaves;
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
    public boolean worldgenNext$legacyIndexActive() { return legacyStructureHandler != null; }

    @Unique
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> worldgenNext$pendingSaves = new ConcurrentHashMap<>();

    @Override
    public void worldgenNext$track(ChunkPos pos, CompletableFuture<Void> save,
                                   AsyncSectionEncoding.Task encoding) {
        long key = worldgenNext$key(pos);
        worldgenNext$pendingSaves.put(key, save);
        worldgenNext$pendingEncodings.put(key, encoding);
        save.whenComplete((ignored, error) -> {
            worldgenNext$pendingSaves.remove(key, save);
            worldgenNext$pendingEncodings.remove(key, encoding);
        });
    }

    @Unique
    private final ConcurrentHashMap<Long, AsyncSectionEncoding.Task> worldgenNext$pendingEncodings =
            new ConcurrentHashMap<>();

    /** Long.hashCode of a packed chunk position is x ^ z, which piles neighbouring chunks into few buckets. */
    @Unique
    private static long worldgenNext$key(ChunkPos pos) {
        return HashCommon.mix(pos.toLong());
    }

    @Override
    public void worldgenNext$awaitAll() {
        for (CompletableFuture<Void> save : worldgenNext$pendingSaves.values().toArray(CompletableFuture[]::new)) {
            try {
                save.join();
            } catch (RuntimeException ignored) {
                // the save path already reported its failure
            }
        }
    }

    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private void worldgenNext$readAfterPendingSave(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> callback) {
        CompletableFuture<Void> pending = worldgenNext$pendingSaves.get(worldgenNext$key(pos));
        if (pending != null && !pending.isDone()) {
            var encoding = worldgenNext$pendingEncodings.get(worldgenNext$key(pos));
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
    private void worldgenNext$flushPendingSaves(CallbackInfo callback) {
        worldgenNext$awaitAll();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void worldgenNext$closePendingSaves(CallbackInfo callback) {
        worldgenNext$awaitAll();
    }
}
