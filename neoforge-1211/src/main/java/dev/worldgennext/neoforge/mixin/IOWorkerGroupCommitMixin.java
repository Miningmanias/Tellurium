// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import dev.worldgennext.neoforge.threading.GroupCommit;
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
    @Unique private static final Logger worldgenNext$LOG = LoggerFactory.getLogger("worldgennext");

    @Shadow @Final private RegionFileStorage storage;
    @Shadow @Final private Map<ChunkPos, ?> pendingWrites;

    /** Saves written but not yet forced.  The worker's messages run one at a time; close() comes from another thread. */
    @Unique private final List<CompletableFuture<Object>> worldgenNext$uncommitted = new ArrayList<>();
    @Unique private long worldgenNext$firstUncommittedNanos;

    @Unique
    private boolean worldgenNext$active() {
        return GroupCommit.ENABLED && ((RegionFileStorageAccessor) (Object) storage).worldgenNext$sync();
    }

    @Redirect(method = "runStore", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;complete(Ljava/lang/Object;)Z"))
    private boolean worldgenNext$completeAfterCommit(CompletableFuture<Object> result, Object value) {
        if (!worldgenNext$active()) return result.complete(value);
        long now = System.nanoTime();
        boolean full;
        synchronized (worldgenNext$uncommitted) {
            if (worldgenNext$uncommitted.isEmpty()) worldgenNext$firstUncommittedNanos = now;
            worldgenNext$uncommitted.add(result);
            full = worldgenNext$uncommitted.size() >= GroupCommit.MAX_WRITES
                    || now - worldgenNext$firstUncommittedNanos >= GroupCommit.MAX_NANOS;
        }
        if (full) worldgenNext$commit();
        return true;
    }

    /** The store loop found nothing left to write. */
    @Inject(method = "storePendingChunk", at = @At("HEAD"))
    private void worldgenNext$commitWhenIdle(CallbackInfo callback) {
        if (pendingWrites.isEmpty()) worldgenNext$commit();
    }

    /** Before the region files are closed: nothing may be left waiting for a commit that would never come. */
    @Inject(method = "close", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/RegionFileStorage;close()V"))
    private void worldgenNext$commitBeforeClose(CallbackInfo callback) {
        worldgenNext$commit();
    }

    @Unique
    private void worldgenNext$commit() {
        List<CompletableFuture<Object>> batch;
        synchronized (worldgenNext$uncommitted) {
            if (worldgenNext$uncommitted.isEmpty()) return;
            batch = new ArrayList<>(worldgenNext$uncommitted);
            worldgenNext$uncommitted.clear();
        }
        try {
            for (RegionFile file : ((RegionFileStorageAccessor) (Object) storage).worldgenNext$regionCache().values()) {
                ((GroupCommit.File) file).worldgenNext$commit();
            }
        } catch (IOException | RuntimeException failure) {
            worldgenNext$LOG.error("Failed to force {} saved chunks to disk", batch.size(), failure);
            for (CompletableFuture<Object> result : batch) result.completeExceptionally(failure);
            return;
        }
        for (CompletableFuture<Object> result : batch) result.complete(null);
    }
}
