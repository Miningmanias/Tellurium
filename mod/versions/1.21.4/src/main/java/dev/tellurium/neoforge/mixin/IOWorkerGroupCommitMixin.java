// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import dev.tellurium.neoforge.threading.GroupCommit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The IO-worker half of {@link GroupCommit}: a save's future is held back until the batch it belongs to has
 * been forced to the disk.
 */
@Mixin(IOWorker.class)
public abstract class IOWorkerGroupCommitMixin {
    @Unique private static final Logger tellurium$LOG = LoggerFactory.getLogger("tellurium");

    @Shadow @Final private RegionFileStorage storage;
    @Shadow @Final private java.util.SequencedMap<ChunkPos, ?> pendingWrites;  // a plain Map in 1.21.1

    /** Saves written but not yet forced.  The worker's messages run one at a time; close() comes from another thread. */
    @Unique private final List<CompletableFuture<Object>> tellurium$uncommitted = new ArrayList<>();
    @Unique private long tellurium$firstUncommittedNanos;

    @Unique
    private boolean tellurium$active() {
        return GroupCommit.ENABLED && ((RegionFileStorageAccessor) (Object) storage).tellurium$sync();
    }

    @Redirect(method = "runStore", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;complete(Ljava/lang/Object;)Z"))
    private boolean tellurium$completeAfterCommit(CompletableFuture<Object> result, Object value) {
        if (!tellurium$active()) return result.complete(value);
        long now = System.nanoTime();
        boolean full;
        synchronized (tellurium$uncommitted) {
            if (tellurium$uncommitted.isEmpty()) tellurium$firstUncommittedNanos = now;
            tellurium$uncommitted.add(result);
            full = tellurium$uncommitted.size() >= GroupCommit.MAX_WRITES
                    || now - tellurium$firstUncommittedNanos >= GroupCommit.MAX_NANOS;
        }
        if (full) tellurium$commit();
        return true;
    }

    /** The store loop found nothing left to write. */
    @Inject(method = "storePendingChunk", at = @At("HEAD"))
    private void tellurium$commitWhenIdle(CallbackInfo callback) {
        if (pendingWrites.isEmpty()) tellurium$commit();
    }

    /** Before the region files are closed: nothing may be left waiting for a commit that would never come. */
    @Inject(method = "close", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/RegionFileStorage;close()V"))
    private void tellurium$commitBeforeClose(CallbackInfo callback) {
        tellurium$commit();
    }

    @Unique
    private void tellurium$commit() {
        List<CompletableFuture<Object>> batch;
        synchronized (tellurium$uncommitted) {
            if (tellurium$uncommitted.isEmpty()) return;
            batch = new ArrayList<>(tellurium$uncommitted);
            tellurium$uncommitted.clear();
        }
        try {
            for (RegionFile file : ((RegionFileStorageAccessor) (Object) storage).tellurium$regionCache().values()) {
                ((GroupCommit.File) file).tellurium$commit();
            }
        } catch (IOException | RuntimeException failure) {
            tellurium$LOG.error("Failed to force {} saved chunks to disk", batch.size(), failure);
            for (CompletableFuture<Object> result : batch) result.completeExceptionally(failure);
            return;
        }
        for (CompletableFuture<Object> result : batch) result.complete(null);
    }
}
