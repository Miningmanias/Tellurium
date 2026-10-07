// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import dev.tellurium.neoforge.threading.DeferredRegionHeaders;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Final;
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

    @Shadow @Final private java.nio.IntBuffer offsets;

    @Unique private int tellurium$deferredWrites;
    @Unique private long tellurium$firstDeferredNanos;
    /** Set at the start of a write: the chunk already has sectors (or an external file) that the header points to. */
    @Unique private boolean tellurium$replacing;

    @Inject(method = "write(Lnet/minecraft/world/level/ChunkPos;Ljava/nio/ByteBuffer;)V", at = @At("HEAD"))
    private void tellurium$noteReplacement(net.minecraft.world.level.ChunkPos pos, java.nio.ByteBuffer data, CallbackInfo callback) {
        // Inside the synchronized write.  The index is RegionFile.getOffsetIndex: region-local x + z * 32.
        tellurium$replacing = offsets.get(pos.getRegionLocalX() + pos.getRegionLocalZ() * 32) != 0;
    }

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
        // A chunk that is being replaced has its old sectors (or its old external file) given up right after
        // this call, and another chunk may be written into them: the header on disk must stop pointing there
        // first, as in the original.  Only the header of chunks that had no place yet is put off.
        if (tellurium$replacing || ++tellurium$deferredWrites >= DeferredRegionHeaders.MAX_WRITES
                || now - tellurium$firstDeferredNanos >= DeferredRegionHeaders.MAX_NANOS) {
            // Forgotten only once written: a failed write leaves the header owed.
            if (tellurium$deferredWrites == 0) tellurium$deferredWrites = 1;
            DeferredRegionHeaders.markDirty(this);
            writeHeader();
            tellurium$deferredWrites = 0;
            DeferredRegionHeaders.clean(this);
        } else if (tellurium$deferredWrites == 1) {
            DeferredRegionHeaders.markDirty(this);
        }
    }

    @Override
    public void tellurium$flushHeader() throws IOException {
        synchronized (this) {
            if (tellurium$deferredWrites == 0) return;
            writeHeader();
            // Only now: if the write failed the header is still owed, and whoever asked for the flush is told.
            tellurium$deferredWrites = 0;
            DeferredRegionHeaders.clean(this);
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
