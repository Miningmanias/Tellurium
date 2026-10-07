// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import dev.tellurium.neoforge.threading.DeferredRegionHeaders;
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

    @Unique private int tellurium$deferredWrites;
    @Unique private long tellurium$firstDeferredNanos;

    @Redirect(method = "write(Lnet/minecraft/world/level/ChunkPos;Ljava/nio/ByteBuffer;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/RegionFile;writeHeader()V"))
    private void tellurium$deferHeader(RegionFile self) throws IOException {
        // Called inside the synchronized write.
        if (!DeferredRegionHeaders.ENABLED) {
            writeHeader();
            return;
        }
        long now = System.nanoTime();
        if (tellurium$deferredWrites == 0) tellurium$firstDeferredNanos = now;
        if (++tellurium$deferredWrites >= DeferredRegionHeaders.MAX_WRITES
                || now - tellurium$firstDeferredNanos >= DeferredRegionHeaders.MAX_NANOS) {
            tellurium$deferredWrites = 0;
            DeferredRegionHeaders.clean(this);
            writeHeader();
        } else if (tellurium$deferredWrites == 1) {
            DeferredRegionHeaders.markDirty(this);
        }
    }

    @Override
    public void tellurium$flushHeader() throws IOException {
        synchronized (this) {
            if (tellurium$deferredWrites == 0) return;
            tellurium$deferredWrites = 0;
            DeferredRegionHeaders.clean(this);
            writeHeader();
        }
    }

    @Inject(method = "flush", at = @At("HEAD"))
    private void tellurium$headerBeforeFlush(CallbackInfo callback) throws IOException {
        tellurium$flushHeader();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void tellurium$headerBeforeClose(CallbackInfo callback) throws IOException {
        tellurium$flushHeader();
    }
}
