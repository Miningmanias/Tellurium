// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.version;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.Util;
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
import net.minecraft.world.level.chunk.storage.ChunkSerializer;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    /** Where this Minecraft version takes a column's preliminary surface from, and its aquifer rule with it. */
    public static dev.worldgennext.compiler.vulkan.fused.FusedNoiseCompiler.PreliminarySurface preliminarySurfaceKind() {
        return dev.worldgennext.compiler.vulkan.fused.FusedNoiseCompiler.PreliminarySurface.DENSITY_SEARCH;
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
