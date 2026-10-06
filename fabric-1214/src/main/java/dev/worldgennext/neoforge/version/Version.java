// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.version;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
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

    /**
     * A kind of chunk ticket of the mod's own.  Each ticket has a position, a radius (0 keeps one chunk at FULL;
     * each step up adds a ring, each step down lowers the status asked for) and a key: tickets of one position
     * and radius under different keys are held separately.
     */
    public static final class Ticket {
        private final TicketType<ChunkPos> type;

        private Ticket(String name) {
            type = TicketType.create(name, Comparator.comparingLong(ChunkPos::toLong));
        }

        public void add(ServerChunkCache cache, ChunkPos position, int radius, ChunkPos key) {
            cache.addRegionTicket(type, position, radius, key);
        }

        public void remove(ServerChunkCache cache, ChunkPos position, int radius, ChunkPos key) {
            cache.removeRegionTicket(type, position, radius, key);
        }
    }

    public static Ticket ticket(String name) {
        return new Ticket(name);
    }

    /** The name of the kind of a ticket taken from the game's own ticket lists (a diagnostic). */
    public static String ticketTypeName(Object ticket) {
        return ((net.minecraft.server.level.Ticket<?>) ticket).getType().toString();
    }

    public static ServerLevel level(ServerPlayer player) {
        return player.serverLevel();
    }

    /** The chunks kept loaded by /forceload, as packed positions. */
    public static LongSet forcedChunks(ServerLevel level) {
        return level.getForcedChunks();
    }

    public static Set<String> keys(CompoundTag tag) {
        return tag.getAllKeys();
    }

    /** The list of compounds under the name; empty when there is none. */
    public static ListTag compounds(CompoundTag tag, String name) {
        return tag.getList(name, Tag.TAG_COMPOUND);
    }

    public static CompoundTag compoundAt(ListTag list, int index) {
        return list.getCompound(index);
    }

    public static short shortAt(ListTag list, int index) {
        return list.getShort(index);
    }

    /** The flag under the name; false when there is none. */
    public static boolean flag(CompoundTag tag, String name) {
        return tag.getBoolean(name);
    }

    /** Sets a block in a chunk the way world generation does: not as a piston move. */
    public static void setBlock(ChunkAccess chunk, BlockPos position, BlockState state) {
        chunk.setBlockState(position, state, false);
    }
}
