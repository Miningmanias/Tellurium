// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

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
    @Unique private static final boolean worldgenNext$ENABLED =
            Boolean.parseBoolean(System.getProperty("worldgennext.fast.regionChunkCache", "true"));

    @Shadow @Final private ChunkAccess center;
    @Shadow @Final private ChunkStep generatingStep;

    @Unique private ChunkAccess[] worldgenNext$chunks;
    @Unique private int[] worldgenNext$allowedIndex;
    @Unique private int worldgenNext$radius = -1, worldgenNext$centerX, worldgenNext$centerZ;

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("HEAD"), cancellable = true)
    private void worldgenNext$cachedChunk(int x, int z, ChunkStatus status, boolean required, CallbackInfoReturnable<ChunkAccess> callback) {
        ChunkAccess[] chunks = worldgenNext$chunks;
        if (chunks == null) return;
        int radius = worldgenNext$radius;
        int dx = x - worldgenNext$centerX + radius, dz = z - worldgenNext$centerZ + radius;
        int side = 2 * radius + 1;
        if (dx < 0 || dz < 0 || dx >= side || dz >= side) return;
        int slot = dx * side + dz;
        ChunkAccess chunk = chunks[slot];
        if (chunk != null && status.getIndex() <= worldgenNext$allowedIndex[slot]) callback.setReturnValue(chunk);
    }

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("RETURN"))
    private void worldgenNext$rememberChunk(int x, int z, ChunkStatus status, boolean required, CallbackInfoReturnable<ChunkAccess> callback) {
        if (!worldgenNext$ENABLED) return;
        ChunkAccess chunk = callback.getReturnValue();
        if (chunk == null) return;
        if (worldgenNext$radius < 0) {
            worldgenNext$radius = generatingStep.directDependencies().size() - 1;
            if (worldgenNext$radius < 0 || worldgenNext$radius > 16) {
                worldgenNext$radius = 0;
                return;
            }
            worldgenNext$centerX = center.getPos().x;
            worldgenNext$centerZ = center.getPos().z;
            int side = 2 * worldgenNext$radius + 1;
            worldgenNext$allowedIndex = new int[side * side];
            worldgenNext$chunks = new ChunkAccess[side * side];
        }
        ChunkAccess[] chunks = worldgenNext$chunks;
        if (chunks == null) return;
        int radius = worldgenNext$radius;
        int dx = x - worldgenNext$centerX + radius, dz = z - worldgenNext$centerZ + radius;
        int side = 2 * radius + 1;
        if (dx < 0 || dz < 0 || dx >= side || dz >= side) return;
        int slot = dx * side + dz;
        if (chunks[slot] == chunk) return;
        // The original returned this chunk for a status no later than the one this distance allows.
        int distance = Math.max(Math.abs(dx - radius), Math.abs(dz - radius));
        ChunkStatus allowed = generatingStep.directDependencies().get(distance);
        if (allowed == null) return;
        worldgenNext$allowedIndex[slot] = allowed.getIndex();
        chunks[slot] = chunk;
    }
}
