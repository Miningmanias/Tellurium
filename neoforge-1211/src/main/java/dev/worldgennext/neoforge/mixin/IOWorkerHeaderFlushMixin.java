// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import dev.worldgennext.neoforge.threading.DeferredRegionHeaders;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Brings deferred region headers (see {@link DeferredRegionHeaders}) up to date whenever the store loop runs dry. */
@Mixin(IOWorker.class)
public abstract class IOWorkerHeaderFlushMixin {
    @Shadow @Final private Map<ChunkPos, ?> pendingWrites;

    @Inject(method = "storePendingChunk", at = @At("HEAD"))
    private void worldgenNext$flushHeadersWhenIdle(CallbackInfo callback) {
        if (pendingWrites.isEmpty()) DeferredRegionHeaders.flushAll();
    }
}
