// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Blender.of asks, for every generation step, whether a pre-1.18 chunk lies
 * near; the answer comes from a per-region scan of all 1,024 chunk positions
 * through the IO mailbox, and the worldgen mailbox thread blocks on it (one
 * to two seconds per region while the IO thread is busy writing).
 *
 * <p>A chunk saved by an older game can only be in a region file that existed
 * before this session.  The first query lists the region files present at
 * that moment; a region outside that list holds only chunks written in this
 * session by this version, so its scan result is the empty set and is
 * returned without scanning.  Listed regions keep the original scan.</p>
 */
@Mixin(IOWorker.class)
public abstract class IOWorkerOldChunkMixin {
    @Unique private static final boolean worldgenNext$ENABLED =
            Boolean.parseBoolean(System.getProperty("worldgennext.fast.freshRegionShortcut", "true"));

    @Shadow @Final private RegionFileStorage storage;

    /** Region files present at the first query, or null before it; guarded by itself once created. */
    @Unique private LongOpenHashSet worldgenNext$existingRegions;
    @Unique private boolean worldgenNext$listingFailed;

    @Unique
    private synchronized boolean worldgenNext$existedBefore(int regionX, int regionZ) {
        if (worldgenNext$listingFailed) return true;
        if (worldgenNext$existingRegions == null) {
            LongOpenHashSet existing = new LongOpenHashSet();
            Path folder = ((RegionFileStorageAccessor) (Object) storage).worldgenNext$folder();
            if (Files.isDirectory(folder)) {
                try (Stream<Path> files = Files.list(folder)) {
                    files.forEach(file -> {
                        String[] parts = file.getFileName().toString().split("\\.");
                        if (parts.length == 4 && parts[0].equals("r")) {
                            try {
                                existing.add(ChunkPos.asLong(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                            } catch (NumberFormatException ignored) {
                                // not a region file
                            }
                        }
                    });
                } catch (Exception failure) {
                    worldgenNext$listingFailed = true;
                    return true;
                }
            }
            worldgenNext$existingRegions = existing;
        }
        return worldgenNext$existingRegions.contains(ChunkPos.asLong(regionX, regionZ));
    }

    @Shadow @Final private java.util.Map<ChunkPos, ?> pendingWrites;

    /** The store loop found nothing left to write: bring deferred region headers up to date. */
    @Inject(method = "storePendingChunk", at = @At("HEAD"))
    private void worldgenNext$flushHeadersWhenIdle(org.spongepowered.asm.mixin.injection.callback.CallbackInfo callback) {
        if (pendingWrites.isEmpty()) dev.worldgennext.neoforge.threading.DeferredRegionHeaders.flushAll();
    }

    @Inject(method = "createOldDataForRegion", at = @At("HEAD"), cancellable = true)
    private void worldgenNext$newRegionHasNoOldChunks(int regionX, int regionZ, CallbackInfoReturnable<CompletableFuture<BitSet>> callback) {
        if (!worldgenNext$ENABLED || worldgenNext$existedBefore(regionX, regionZ)) return;
        callback.setReturnValue(CompletableFuture.completedFuture(new BitSet()));
    }
}
