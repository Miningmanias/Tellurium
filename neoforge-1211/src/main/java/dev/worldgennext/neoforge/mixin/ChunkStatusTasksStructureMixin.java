// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import dev.worldgennext.neoforge.threading.ParallelWorldgenSteps;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Moves the synchronous 1.21.1 STRUCTURE_STARTS and STRUCTURE_REFERENCES
 * step bodies off the single worldgen mailbox onto the worldgen worker pool.
 * The unmodified vanilla body runs on the worker; only where it runs changes.
 * Each body writes only its own chunk and reads neighbours whose required
 * statuses already completed, so ordering-dependent output is unchanged.
 */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksStructureMixin {
    @Shadow
    static CompletableFuture<ChunkAccess> generateStructureStarts(
            WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk) {
        throw new AssertionError();
    }

    @Shadow
    static CompletableFuture<ChunkAccess> generateStructureReferences(
            WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk) {
        throw new AssertionError();
    }

    @Inject(method = "generateStructureStarts", at = @At("HEAD"), cancellable = true)
    private static void worldgenNext$parallelStructureStarts(
            WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
        if (ParallelWorldgenSteps.shouldOffload()) {
            callback.setReturnValue(ParallelWorldgenSteps.offload("structure_starts",
                    () -> generateStructureStarts(context, step, cache, chunk)));
        }
    }

    @Inject(method = "generateStructureReferences", at = @At("HEAD"), cancellable = true)
    private static void worldgenNext$parallelStructureReferences(
            WorldGenContext context, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> callback) {
        if (ParallelWorldgenSteps.shouldOffload()) {
            callback.setReturnValue(ParallelWorldgenSteps.offload("structure_references",
                    () -> generateStructureReferences(context, step, cache, chunk)));
        }
    }
}
