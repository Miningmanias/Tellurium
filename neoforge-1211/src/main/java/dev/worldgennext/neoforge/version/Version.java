// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.version;

import net.minecraft.Util;
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
import net.minecraft.world.level.chunk.storage.ChunkSerializer;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * What differs between Minecraft versions in code that is otherwise shared, Minecraft 1.21.1 version.  A
 * module for another Minecraft version supplies its own class of this name with the same methods; here every
 * method is the plain 1.21.1 call.
 */
public final class Version {
    private Version() {}

    public static String minecraft() {
        return "1.21.1";
    }

    /** The rule this Minecraft version uses to mark aquifer blocks for a fluid update. */
    public static dev.worldgennext.compiler.vulkan.fused.FusedNoiseCompiler.FluidUpdates fluidUpdates() {
        return dev.worldgennext.compiler.vulkan.fused.FusedNoiseCompiler.FluidUpdates.BETWEEN_AQUIFERS;
    }

    /** The chunk as the game would write it to disk. */
    public static CompoundTag chunkNbt(ServerLevel level, ChunkAccess chunk) {
        return ChunkSerializer.write(level, chunk);
    }

    /** The lowest block y. */
    public static int minY(LevelHeightAccessor heights) {
        return heights.getMinBuildHeight();
    }

    /** One above the highest block y. */
    public static int maxYExclusive(LevelHeightAccessor heights) {
        return heights.getMaxBuildHeight();
    }

    public static <T> Registry<T> registry(RegistryAccess access, ResourceKey<? extends Registry<T>> key) {
        return access.registryOrThrow(key);
    }

    public static <T> Optional<Holder.Reference<T>> holder(Registry<T> registry, ResourceKey<T> key) {
        return registry.getHolder(key);
    }

    /** The registered value, or null. */
    public static <T> T value(Registry<T> registry, ResourceLocation id) {
        return registry.get(id);
    }

    public static void setUnsaved(ChunkAccess chunk, boolean unsaved) {
        chunk.setUnsaved(unsaved);
    }

    public static void addPostProcess(ChunkAccess chunk, short packedPosition, int sectionIndex) {
        chunk.addPackedPostProcess(packedPosition, sectionIndex);
    }

    /** The supplier, run with the worker thread named after the task where the game does that. */
    public static <T> Supplier<T> named(String task, Supplier<T> body) {
        return Util.wrapThreadWithTaskName(task, body);
    }

    public static int generationRefCount(GenerationChunkHolder holder) {
        return holder.getGenerationRefCount();
    }

    public static void teleport(ServerPlayer player, ServerLevel level, double x, double y, double z, float yRot, float xRot) {
        player.teleportTo(level, x, y, z, yRot, xRot);
    }
}
