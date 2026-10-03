// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import dev.worldgennext.neoforge.threading.PrecompressedChunks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;

/** Writes a payload that a worker already serialized and compressed instead of compressing on the IO thread. */
@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageCompressMixin {
    @Shadow
    private RegionFile getRegionFile(ChunkPos pos) throws IOException {
        throw new AssertionError();
    }

    @Inject(method = "write", at = @At("HEAD"), cancellable = true)
    private void worldgenNext$writePrecompressed(ChunkPos pos, CompoundTag tag, CallbackInfo callback) throws IOException {
        if (tag == null) return;
        PrecompressedChunks.Payload payload = PrecompressedChunks.take(tag);
        if (payload == null) return;
        RegionFile region = getRegionFile(pos);
        RegionFileAccessor access = (RegionFileAccessor) region;
        if (access.worldgenNext$version().getId() != payload.versionId()) return;
        access.worldgenNext$write(pos, payload.buffer());
        callback.cancel();
    }
}
