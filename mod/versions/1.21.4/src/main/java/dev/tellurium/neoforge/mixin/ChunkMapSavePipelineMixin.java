// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import dev.tellurium.neoforge.threading.SavePipeline;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Sends a chunk save through {@link SavePipeline} (Minecraft 1.21.2 and later; 1.21.1: ChunkMapAsyncSaveMixin). */
@Mixin(ChunkMap.class)
public abstract class ChunkMapSavePipelineMixin {
    @Shadow @Final private AtomicInteger activeChunkWrites;

    @Redirect(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<CompoundTag> tellurium$ownSaveThreads(Supplier<CompoundTag> write, Executor background) {
        if (!SavePipeline.ENABLED) return CompletableFuture.supplyAsync(write, background);
        // This save is already counted; the wait is for the ones before it.
        SavePipeline.awaitRoom(activeChunkWrites);
        return SavePipeline.encode(write);
    }
}
