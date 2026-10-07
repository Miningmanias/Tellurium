// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import dev.tellurium.neoforge.threading.AsyncChunkLoad;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** The ChunkMap half of {@link AsyncChunkLoad}: a worker reads the chunk between the IO thread and the server thread. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapAsyncLoadMixin {
    @Shadow @Final ServerLevel level;
    @Shadow @Final private PoiManager poiManager;

    @Shadow
    private static boolean isChunkDataValid(CompoundTag tag) {
        throw new AssertionError();
    }

    @Shadow
    private CompletableFuture<Optional<CompoundTag>> readChunk(ChunkPos pos) {
        throw new AssertionError();
    }

    @Redirect(method = "scheduleChunkLoad", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;readChunk(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Optional<CompoundTag>> tellurium$readAhead(ChunkMap self, ChunkPos pos) {
        CompletableFuture<Optional<CompoundTag>> bytes = readChunk(pos);
        if (!AsyncChunkLoad.ENABLED) return bytes;
        return bytes.thenApplyAsync(tag -> {
            // Invalid data is left for the original code, which logs it and makes an empty chunk.
            if (tag.isPresent() && isChunkDataValid(tag.get())) {
                AsyncChunkLoad.prepare(level, poiManager, ((ChunkStorageInfoAccessor) this).tellurium$storageInfo(), pos, tag.get());
            }
            return tag;
        }, Util.backgroundExecutor());
    }
}
