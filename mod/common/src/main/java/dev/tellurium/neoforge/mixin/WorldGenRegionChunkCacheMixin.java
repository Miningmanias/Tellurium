// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A WorldGenRegion resolves every block, biome and height read to a chunk
 * through the generation task's holder cache: a distance computation, a list
 * lookup, a holder lookup, an atomic array read and a future read.  Feature
 * placement does this hundreds of thousands of times per chunk for the same
 * few neighbours.  A region lives for one generation step on one thread, so
 * the chunk it resolved for a position is remembered together with the
 * status that position is allowed to be read at, and returned directly while
 * the requested status is still covered.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionChunkCacheMixin {
    @Unique private static final boolean tellurium$ENABLED =
            Boolean.parseBoolean(System.getProperty("tellurium.fast.regionChunkCache", "true"));

    @Shadow @Final private ChunkAccess center;
    @Shadow @Final private ChunkStep generatingStep;

    @Unique private ChunkAccess[] tellurium$chunks;
    @Unique private int[] tellurium$allowedIndex;
    @Unique private int tellurium$radius = -1, tellurium$centerX, tellurium$centerZ;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"), cancellable = true)
    private void tellurium$cachedChunk(int x, int z, ChunkStatus status, boolean required, CallbackInfoReturnable<ChunkAccess> callback) {
        ChunkAccess[] chunks = tellurium$chunks;
        if (chunks == null) return;
        int radius = tellurium$radius;
        int dx = x - tellurium$centerX + radius, dz = z - tellurium$centerZ + radius;
        int side = 2 * radius + 1;
        if (dx < 0 || dz < 0 || dx >= side || dz >= side) return;
        int slot = dx * side + dz;
        ChunkAccess chunk = chunks[slot];
        if (chunk != null && status.getIndex() <= tellurium$allowedIndex[slot]) callback.setReturnValue(chunk);
    }

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("RETURN"))
    private void tellurium$rememberChunk(int x, int z, ChunkStatus status, boolean required, CallbackInfoReturnable<ChunkAccess> callback) {
        if (!tellurium$ENABLED) return;
        ChunkAccess chunk = callback.getReturnValue();
        if (chunk == null) return;
        if (tellurium$radius < 0) {
            tellurium$radius = generatingStep.directDependencies().size() - 1;
            if (tellurium$radius < 0 || tellurium$radius > 16) {
                tellurium$radius = 0;
                return;
            }
            tellurium$centerX = center.getPos().x;
            tellurium$centerZ = center.getPos().z;
            int side = 2 * tellurium$radius + 1;
            tellurium$allowedIndex = new int[side * side];
            tellurium$chunks = new ChunkAccess[side * side];
        }
        ChunkAccess[] chunks = tellurium$chunks;
        if (chunks == null) return;
        int radius = tellurium$radius;
        int dx = x - tellurium$centerX + radius, dz = z - tellurium$centerZ + radius;
        int side = 2 * radius + 1;
        if (dx < 0 || dz < 0 || dx >= side || dz >= side) return;
        int slot = dx * side + dz;
        if (chunks[slot] == chunk) return;
        // The original returned this chunk for a status no later than the one this distance allows.
        int distance = Math.max(Math.abs(dx - radius), Math.abs(dz - radius));
        ChunkStatus allowed = generatingStep.directDependencies().get(distance);
        if (allowed == null) return;
        tellurium$allowedIndex[slot] = allowed.getIndex();
        chunks[slot] = chunk;
    }
}
