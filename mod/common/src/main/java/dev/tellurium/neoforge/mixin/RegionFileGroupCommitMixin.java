// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import dev.tellurium.neoforge.threading.DeferredRegionHeaders;
import dev.tellurium.neoforge.threading.GroupCommit;
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
import java.nio.channels.FileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** The region-file half of {@link GroupCommit}: no DSYNC on the channel, an explicit force per batch instead. */
@Mixin(RegionFile.class)
public abstract class RegionFileGroupCommitMixin implements GroupCommit.File {
    @Shadow @Final private FileChannel file;

    /** True when the file was asked to be synchronous and is committed in batches instead. */
    @Unique private boolean tellurium$groupCommit;
    /** Something was written since the last force.  Guarded by this. */
    @Unique private boolean tellurium$unsynced;

    /** The first FileChannel.open of the constructor is the synchronous one. */
    @Redirect(method = "<init>(Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Ljava/nio/file/Path;Ljava/nio/file/Path;Lnet/minecraft/world/level/chunk/storage/RegionFileVersion;Z)V",
            at = @At(value = "INVOKE", ordinal = 0,
                    target = "Ljava/nio/channels/FileChannel;open(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/nio/channels/FileChannel;"))
    private FileChannel tellurium$openForGroupCommit(Path path, OpenOption[] synchronousOptions) throws IOException {
        if (!GroupCommit.ENABLED) return FileChannel.open(path, synchronousOptions);
        tellurium$groupCommit = true;
        return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    @Inject(method = "write(Lnet/minecraft/world/level/ChunkPos;Ljava/nio/ByteBuffer;)V", at = @At("HEAD"))
    private void tellurium$chunkWritten(CallbackInfo callback) {
        if (tellurium$groupCommit) {
            synchronized (this) {
                tellurium$unsynced = true;
            }
        }
    }

    @Inject(method = "writeHeader", at = @At("HEAD"))
    private void tellurium$headerWritten(CallbackInfo callback) {
        if (tellurium$groupCommit) {
            synchronized (this) {
                tellurium$unsynced = true;
            }
        }
    }

    @Override
    public void tellurium$commit() throws IOException {
        if (!tellurium$groupCommit) return;
        synchronized (this) {
            ((DeferredRegionHeaders.Holder) this).tellurium$flushHeader();
            if (!tellurium$unsynced) return;
            // Cleared only after the force succeeded: a failed batch must not make the next commit skip this file.
            file.force(false);
            tellurium$unsynced = false;
        }
    }
}
