// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.version;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * What differs between Minecraft versions in code that is otherwise shared, Minecraft 1.21.4 version (the
 * shared source is written for 1.21.1; see the class of this name there).
 */
public final class Version {
    private Version() {}

    public static String minecraft() {
        return "1.21.4";
    }

    /** The rule this Minecraft version uses to mark aquifer blocks for a fluid update. */
    public static dev.worldgennext.compiler.vulkan.fused.FusedNoiseCompiler.FluidUpdates fluidUpdates() {
        return dev.worldgennext.compiler.vulkan.fused.FusedNoiseCompiler.FluidUpdates.WHERE_NEIGHBOURS_DIFFER;
    }

    /** The chunk as the game would write it to disk. */
    public static CompoundTag chunkNbt(ServerLevel level, ChunkAccess chunk) {
        return SerializableChunkData.copyOf(level, chunk).write();
    }

    /** The lowest block y. */
    public static int minY(LevelHeightAccessor heights) {
        return heights.getMinY();
    }

    /** One above the highest block y. */
    public static int maxYExclusive(LevelHeightAccessor heights) {
        return heights.getMinY() + heights.getHeight();
    }

    public static <T> Registry<T> registry(RegistryAccess access, ResourceKey<? extends Registry<T>> key) {
        return access.lookupOrThrow(key);
    }

    public static <T> Optional<Holder.Reference<T>> holder(Registry<T> registry, ResourceKey<T> key) {
        return registry.get(key);
    }

    /** The registered value, or null. */
    public static <T> T value(Registry<T> registry, ResourceLocation id) {
        return registry.getValue(id);
    }

    public static void setUnsaved(ChunkAccess chunk, boolean unsaved) {
        if (unsaved) chunk.markUnsaved();
        else chunk.tryMarkSaved();
    }

    public static void addPostProcess(ChunkAccess chunk, short packedPosition, int sectionIndex) {
        chunk.addPackedPostProcess(it.unimi.dsi.fastutil.shorts.ShortList.of(packedPosition), sectionIndex);
    }

    /** The supplier itself: 1.21.4 no longer names worker threads after their task. */
    public static <T> Supplier<T> named(String task, Supplier<T> body) {
        return body;
    }

    public static int generationRefCount(GenerationChunkHolder holder) {
        return 0;  // not exposed in 1.21.4; only a benchmark diagnostic reads it
    }

    public static void teleport(ServerPlayer player, ServerLevel level, double x, double y, double z, float yRot, float xRot) {
        player.teleportTo(level, x, y, z, java.util.Set.of(), yRot, xRot, false);
    }
}
