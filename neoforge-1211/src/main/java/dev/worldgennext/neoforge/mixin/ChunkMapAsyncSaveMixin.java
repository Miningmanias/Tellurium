// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import dev.worldgennext.neoforge.threading.AsyncSectionEncoding;
import dev.worldgennext.neoforge.threading.PendingChunkSaves;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * For a chunk that is being unloaded (its holder has already left the
 * updating map and the chunk can no longer change), section palette encoding
 * runs on the worker pool and the tag is handed to the IO worker afterwards.
 * Saves of live chunks keep the vanilla synchronous path.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapAsyncSaveMixin {
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;

    /** Set between the two redirects of one save() call on the server thread. */
    @Unique private Map<Tag, Supplier<Tag>> worldgenNext$deferred;

    @Redirect(method = "save", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/ChunkSerializer;write(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/nbt/CompoundTag;"))
    private CompoundTag worldgenNext$serialize(ServerLevel level, ChunkAccess chunk) {
        worldgenNext$deferred = null;
        if (!AsyncSectionEncoding.ENABLED || updatingChunkMap.containsKey(chunk.getPos().toLong())
                || ((PendingChunkSaves) this).worldgenNext$legacyIndexActive()) {
            return ChunkSerializer.write(level, chunk);
        }
        Map<Tag, Supplier<Tag>> collector = AsyncSectionEncoding.begin();
        try {
            CompoundTag tag = ChunkSerializer.write(level, chunk);
            if (!collector.isEmpty()) worldgenNext$deferred = collector;
            return tag;
        } finally {
            AsyncSectionEncoding.end();
        }
    }

    @Redirect(method = "save", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> worldgenNext$store(ChunkMap self, ChunkPos pos, CompoundTag tag) {
        Map<Tag, Supplier<Tag>> collector = worldgenNext$deferred;
        worldgenNext$deferred = null;
        if (collector == null) return self.write(pos, tag);
        CompletableFuture<Void> save = CompletableFuture
                .runAsync(() -> AsyncSectionEncoding.resolve(tag, collector), Util.backgroundExecutor())
                .thenCompose(ignored -> self.write(pos, tag));
        ((PendingChunkSaves) self).worldgenNext$track(pos, save);
        return save;
    }
}
