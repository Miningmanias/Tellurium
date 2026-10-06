// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.version;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What differs between Minecraft versions in code that is otherwise shared, Minecraft 1.21.8 version (the
 * shared source is written for 1.21.1; see the class of this name there).
 */
public final class Version {
    private Version() {}

    public static String minecraft() {
        return "1.21.8";
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
        // Since 1.21.5 a kind of ticket is a plain value, told apart by identity, and a chunk holds at most one
        // ticket of a kind and level; the game has no key.  This kind is not registered: it is never saved
        // (only kinds marked to persist are), and a registered one would have to exist on clients as well.
        // Like the region tickets of earlier versions it both loads and counts towards simulation.
        private final TicketType type = new TicketType(TicketType.NO_TIMEOUT, false, TicketType.TicketUse.LOADING_AND_SIMULATION);
        private final String name;
        /** How many keys hold a ticket of one cache, position and radius; the game's ticket is there while any does. */
        private final Map<Held, Integer> held = new HashMap<>();

        private record Held(ServerChunkCache cache, long position, int radius) {}

        private Ticket(String name) {
            this.name = name;
        }

        public synchronized void add(ServerChunkCache cache, ChunkPos position, int radius, ChunkPos key) {
            if (held.merge(new Held(cache, position.toLong(), radius), 1, Integer::sum) == 1) cache.addTicketWithRadius(type, position, radius);
        }

        public synchronized void remove(ServerChunkCache cache, ChunkPos position, int radius, ChunkPos key) {
            Held entry = new Held(cache, position.toLong(), radius);
            Integer count = held.get(entry);
            if (count == null) return;
            if (count > 1) {
                held.put(entry, count - 1);
            } else {
                held.remove(entry);
                cache.removeTicketWithRadius(type, position, radius);
            }
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static Ticket ticket(String name) {
        return new Ticket(name);
    }

    /** The name of the kind of a ticket taken from the game's own ticket lists (a diagnostic). */
    public static String ticketTypeName(Object ticket) {
        return String.valueOf(net.minecraft.core.registries.BuiltInRegistries.TICKET_TYPE.getKey(((net.minecraft.server.level.Ticket) ticket).getType()));
    }

    public static ServerLevel level(ServerPlayer player) {
        return player.level();
    }

    /** The chunks kept loaded by /forceload, as packed positions. */
    public static LongSet forcedChunks(ServerLevel level) {
        return level.getForceLoadedChunks();
    }

    public static Set<String> keys(CompoundTag tag) {
        return tag.keySet();
    }

    /** The list of compounds under the name; empty when there is none. */
    public static ListTag compounds(CompoundTag tag, String name) {
        return tag.getListOrEmpty(name);
    }

    public static CompoundTag compoundAt(ListTag list, int index) {
        return list.getCompoundOrEmpty(index);
    }

    public static short shortAt(ListTag list, int index) {
        return list.getShortOr(index, (short) 0);
    }

    /** The flag under the name; false when there is none. */
    public static boolean flag(CompoundTag tag, String name) {
        return tag.getBooleanOr(name, false);
    }

    /** Sets a block in a chunk the way world generation does: not as a piston move. */
    public static void setBlock(ChunkAccess chunk, BlockPos position, BlockState state) {
        chunk.setBlockState(position, state);
    }
}
