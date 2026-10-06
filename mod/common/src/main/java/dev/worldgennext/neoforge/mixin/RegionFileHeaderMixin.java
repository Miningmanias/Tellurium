// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import dev.worldgennext.neoforge.threading.DeferredRegionHeaders;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;

/** Batches the region header rewrite that follows every chunk write; see {@link DeferredRegionHeaders}. */
@Mixin(RegionFile.class)
public abstract class RegionFileHeaderMixin implements DeferredRegionHeaders.Holder {
    @Shadow
    private void writeHeader() throws IOException {
        throw new AssertionError();
    }

    @Unique private int worldgenNext$deferredWrites;
    @Unique private long worldgenNext$firstDeferredNanos;

    @Redirect(method = "write(Lnet/minecraft/world/level/ChunkPos;Ljava/nio/ByteBuffer;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/RegionFile;writeHeader()V"))
    private void worldgenNext$deferHeader(RegionFile self) throws IOException {
        // Called inside the synchronized write.
        if (!DeferredRegionHeaders.ENABLED) {
            writeHeader();
            return;
        }
        long now = System.nanoTime();
        if (worldgenNext$deferredWrites == 0) worldgenNext$firstDeferredNanos = now;
        if (++worldgenNext$deferredWrites >= DeferredRegionHeaders.MAX_WRITES
                || now - worldgenNext$firstDeferredNanos >= DeferredRegionHeaders.MAX_NANOS) {
            worldgenNext$deferredWrites = 0;
            DeferredRegionHeaders.clean(this);
            writeHeader();
        } else if (worldgenNext$deferredWrites == 1) {
            DeferredRegionHeaders.markDirty(this);
        }
    }

    @Override
    public void worldgenNext$flushHeader() throws IOException {
        synchronized (this) {
            if (worldgenNext$deferredWrites == 0) return;
            worldgenNext$deferredWrites = 0;
            DeferredRegionHeaders.clean(this);
            writeHeader();
        }
    }

    @Inject(method = "flush", at = @At("HEAD"))
    private void worldgenNext$headerBeforeFlush(CallbackInfo callback) throws IOException {
        worldgenNext$flushHeader();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void worldgenNext$headerBeforeClose(CallbackInfo callback) throws IOException {
        worldgenNext$flushHeader();
    }
}
