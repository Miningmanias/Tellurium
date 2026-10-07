// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.version;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Iterator;
import java.util.List;
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
    public static dev.tellurium.compiler.vulkan.fused.FusedNoiseCompiler.FluidUpdates fluidUpdates() {
        return dev.tellurium.compiler.vulkan.fused.FusedNoiseCompiler.FluidUpdates.WHERE_NEIGHBOURS_DIFFER;
    }

    /** Where this Minecraft version takes a column's preliminary surface from, and its aquifer rule with it. */
    public static dev.tellurium.compiler.vulkan.fused.FusedNoiseCompiler.PreliminarySurface preliminarySurfaceKind() {
        return dev.tellurium.compiler.vulkan.fused.FusedNoiseCompiler.PreliminarySurface.DENSITY_SEARCH;
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
        /**
         * The keys that hold a ticket of one cache, position and radius; the game's ticket is there while any
         * does.  A set, as in earlier versions: adding a ticket twice under one key is one ticket.
         */
        private final Map<Held, Set<Long>> held = new HashMap<>();

        private record Held(ServerChunkCache cache, long position, int radius) {}

        private Ticket(String name) {
            this.name = name;
        }

        public synchronized void add(ServerChunkCache cache, ChunkPos position, int radius, ChunkPos key) {
            Set<Long> keys = held.computeIfAbsent(new Held(cache, position.toLong(), radius), entry -> new HashSet<>(2));
            if (keys.add(key.toLong()) && keys.size() == 1) cache.addTicketWithRadius(type, position, radius);
        }

        public synchronized void remove(ServerChunkCache cache, ChunkPos position, int radius, ChunkPos key) {
            Held entry = new Held(cache, position.toLong(), radius);
            Set<Long> keys = held.get(entry);
            if (keys == null || !keys.remove(key.toLong()) || !keys.isEmpty()) return;
            held.remove(entry);
            cache.removeTicketWithRadius(type, position, radius);
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

    /**
     * The router function the preliminary surface comes from: until 1.21.8 a density, searched from the top
     * for the first value above 0.390625; since 1.21.9 a function that is the level itself.
     */
    public static DensityFunction preliminarySurface(NoiseRouter router) {
        return router.initialDensityWithoutJaggedness();
    }

    /** Where a pregeneration without a centre starts. */
    public static BlockPos spawn(ServerLevel level) {
        return level.getSharedSpawnPos();
    }

    /** Operators, the console, and the owner of a singleplayer world (who has no operator level without cheats). */
    public static boolean mayUseCommands(CommandSourceStack source) {
        if (source.hasPermission(2)) return true;
        return source.getEntity() instanceof ServerPlayer player && source.getServer().isSingleplayerOwner(player.getGameProfile());
    }

    /**
     * A chunk section's block states from a palette and the indices into it, packed as the game packs them
     * at the given width (null at width 0: one state).
     */
    public static PalettedContainer<BlockState> blockStates(List<BlockState> palette, int requestedBits, int storageBits, long[] packedIndices) {
        BitStorage storage = storageBits == 0 ? new ZeroBitStorage(4096) : new SimpleBitStorage(storageBits, 4096, packedIndices);
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, PalettedContainer.Strategy.SECTION_STATES,
                PalettedContainer.Strategy.SECTION_STATES.getConfiguration(Block.BLOCK_STATE_REGISTRY, requestedBits), storage, palette);
    }

    /** A chunk section's block states, all air. */
    public static PalettedContainer<BlockState> airBlockStates() {
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
    }

    /** Beardifier's fields with the structure pieces and the jigsaw junctions, and all the fields that are its own. */
    public static final String BEARD_PIECES = "pieceIterator", BEARD_JUNCTIONS = "junctionIterator";
    public static final List<String> BEARD_OWN_FIELDS = List.of(BEARD_PIECES, BEARD_JUNCTIONS);

    /** The entries of one of those two fields, from the first. */
    public static Iterator<?> beardEntries(Object field) {
        return (Iterator<?>) field;
    }
}
