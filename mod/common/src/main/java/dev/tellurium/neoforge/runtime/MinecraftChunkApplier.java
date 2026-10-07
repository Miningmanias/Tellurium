// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import dev.tellurium.neoforge.version.Version;

import dev.tellurium.neoforge.loader.Names;

import dev.tellurium.material.chunk.BlockStateTable;
import dev.tellurium.material.chunk.ChunkNoiseResult;
import dev.tellurium.semantic.snapshot.BlockStateDescriptor;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtUtils;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.lang.reflect.Field;

/**
 * Version-pinned NOISE application boundary for a real ProtoChunk.
 *
 * <p>The result is fully validated and resolved before the first mutation.
 * Only a BIOMES ProtoChunk is accepted: the surrounding ChunkStep remains the
 * authority that advances the status to NOISE.  This keeps the adapter from
 * publishing a result into an already-populated or LevelChunk target and
 * avoids invoking post-light LevelChunk side effects from a worldgen worker.</p>
 */
public final class MinecraftChunkApplier {
    private static final int HEIGHTMAP_COLUMNS = 256;
    public enum MutationPhase {
        BEFORE_BLOCKS,
        AFTER_BLOCKS,
        AFTER_HEIGHTMAPS,
        AFTER_FLUID_MARKS,
        AFTER_DIRTY_STATE
    }

    @FunctionalInterface
    public interface MutationHook {
        void after(MutationPhase phase);
    }

    @FunctionalInterface
    public interface BlockStateResolver {
        BlockState resolve(BlockStateDescriptor descriptor);
    }

    public record ApplicationReceipt(int blocksChanged, int heightmapsApplied, int fluidMarksAdded) {
        public ApplicationReceipt {
            if (blocksChanged < 0 || heightmapsApplied < 0 || fluidMarksAdded < 0) {
                throw new IllegalArgumentException("Negative application count");
            }
        }
    }

    private record OriginalBlock(int x, int y, int z, BlockState state) {}

    private final BlockStateResolver states;
    private final MutationHook mutationHook;

    public MinecraftChunkApplier(BlockStateResolver states) {
        this(states, phase -> { });
    }

    /**
     * The hook is an injectable failure seam for loader qualification and
     * fault tests. Production callers use the no-op constructor; it never
     * changes the mutation order or owns chunk scheduling.
     */
    public MinecraftChunkApplier(BlockStateResolver states, MutationHook mutationHook) {
        this.states = Objects.requireNonNull(states, "states");
        this.mutationHook = Objects.requireNonNull(mutationHook, "mutationHook");
    }

    /**
     * Resolves canonical ABI names through the actual 1.21.1 block holder
     * registry and rejects a lossy resolution that would silently become air
     * or lose a state property.
     */
    public static BlockStateResolver fromBlocks(HolderGetter<Block> blocks) {
        Objects.requireNonNull(blocks, "blocks");
        return descriptor -> {
            CompoundTag encoded = new CompoundTag();
            encoded.putString("Name", descriptor.name());
            if (!descriptor.properties().isEmpty()) {
                CompoundTag properties = new CompoundTag();
                descriptor.properties().forEach(properties::putString);
                encoded.put("Properties", properties);
            }
            BlockState resolved = NbtUtils.readBlockState(blocks, encoded);
            if (!descriptorMatches(descriptor, resolved)) {
                throw new IllegalArgumentException("Registry did not resolve canonical block state: "
                        + descriptor.canonical());
            }
            return resolved;
        };
    }

    /**
     * Applies a complete dense result.  The supplied journal remains OPEN on
     * success so the exclusive committer can publish it atomically.  On a
     * mutation failure this method rolls back immediately; a rollback failure
     * is retained by the journal as UNSAFE.
     */
    public ApplicationReceipt apply(ChunkAccess target, ChunkNoiseResult result, ChunkMutationJournal journal) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(journal, "journal");
        validateTarget(target, result);

        String[] denseStates = result.denseStates();
        Map<String, BlockState> resolvedStates = resolveStates(result.stateTable(), denseStates);
        EnumMap<Heightmap.Types, long[]> encodedHeightmaps = prepareHeightmaps(target, result);
        Map<Heightmap.Types, long[]> originalHeightmaps = captureHeightmaps(target, encodedHeightmaps.keySet());
        ShortList[] postProcessing = target.getPostProcessing();
        List<short[]> originalPostProcessing = capturePostProcessing(postProcessing);
        boolean originalUnsaved = target.isUnsaved();
        List<OriginalBlock> originalBlocks = new ArrayList<>();

        // Register restoration in reverse dependency order.  Sections restore
        // first, then explicit heightmaps and postprocessing, and finally the
        // dirty bit/lifecycle marker.
        journal.record("dirty/lifecycle", () -> Version.setUnsaved(target, originalUnsaved));
        journal.record("fluid/postprocessing metadata", () -> restorePostProcessing(postProcessing, originalPostProcessing));
        journal.record("heightmaps", () -> restoreHeightmaps(target, encodedHeightmaps.keySet(), originalHeightmaps));
        journal.record("sections/storage/counts", () -> restoreBlocks(target, originalBlocks));

        try {
            mutationHook.after(MutationPhase.BEFORE_BLOCKS);
            int blocksChanged = applyBlocks(target, result.header().chunkX(), result.header().chunkZ(),
                    result.header().minY(), result.header().height(), denseStates, resolvedStates, originalBlocks);
            mutationHook.after(MutationPhase.AFTER_BLOCKS);
            encodedHeightmaps.forEach(target::setHeightmap);
            mutationHook.after(MutationPhase.AFTER_HEIGHTMAPS);
            int fluidMarksAdded = applyFluidMarks(target, result);
            mutationHook.after(MutationPhase.AFTER_FLUID_MARKS);
            Version.setUnsaved(target, true);
            mutationHook.after(MutationPhase.AFTER_DIRTY_STATE);
            return new ApplicationReceipt(blocksChanged, encodedHeightmaps.size(), fluidMarksAdded);
        } catch (RuntimeException | Error failure) {
            rollbackAfterFailure(journal, failure);
            throw failure;
        }
    }

    private static void validateTarget(ChunkAccess target, ChunkNoiseResult result) {
        if (!(target instanceof ProtoChunk) || target instanceof ImposterProtoChunk) {
            throw new IllegalArgumentException("NOISE commit requires a writable ProtoChunk, not "
                    + target.getClass().getName());
        }
        if (target.getPersistedStatus() != ChunkStatus.BIOMES) {
            throw new IllegalStateException("NOISE commit requires BIOMES input status; got "
                    + target.getPersistedStatus().getName());
        }
        ChunkPos position = target.getPos();
        if (position.x != result.header().chunkX() || position.z != result.header().chunkZ()) {
            throw new IllegalArgumentException("Chunk coordinate mismatch at application boundary");
        }
        if (Version.minY(target) != result.header().minY()
                || target.getHeight() != result.header().height()) {
            throw new IllegalArgumentException("Chunk geometry mismatch at application boundary");
        }
    }

    private Map<String, BlockState> resolveStates(BlockStateTable table, String[] denseStates) {
        Map<String, BlockState> resolved = new HashMap<>();
        for (String canonical : denseStates) {
            if (resolved.containsKey(canonical)) continue;
            int id = table.id(canonical);
            if (id < 0) throw new IllegalArgumentException("Result state is absent from its state table: " + canonical);
            BlockState state = Objects.requireNonNull(states.resolve(table.state(id)),
                    "Resolver returned no state for " + canonical);
            resolved.put(canonical, state);
        }
        return resolved;
    }

    private static EnumMap<Heightmap.Types, long[]> prepareHeightmaps(ChunkAccess target, ChunkNoiseResult result) {
        int bits = heightmapBits(target.getHeight());
        long maximum = (1L << bits) - 1L;
        EnumMap<Heightmap.Types, long[]> encoded = new EnumMap<>(Heightmap.Types.class);
        result.heightmaps().maps().forEach((name, values) -> {
            Heightmap.Types type;
            try {
                type = Heightmap.Types.valueOf(name);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Unknown Minecraft heightmap type: " + name, failure);
            }
            int[] relative = new int[HEIGHTMAP_COLUMNS];
            for (int column = 0; column < relative.length; column++) {
                long value = values[column];
                long offset = value - result.header().minY();
                if (offset < 0 || offset > result.header().height() || offset > maximum) {
                    throw new IllegalArgumentException("Heightmap value cannot be represented: " + name
                            + "[" + column + "]=" + value);
                }
                relative[column] = Math.toIntExact(offset);
            }
            encoded.put(type, new SimpleBitStorage(bits, HEIGHTMAP_COLUMNS, relative).getRaw().clone());
        });
        return encoded;
    }

    /**
     * Returns the packed heightmap width without allowing the inclusive
     * height calculation to wrap before Minecraft's bit-width helper sees it.
     * A zero-height target cannot carry a valid section result and is rejected
     * here rather than creating a zero-bit storage with ambiguous metadata.
     */
    static int heightmapBits(int height) {
        return HeightmapPacking.bitsForHeight(height);
    }

    private static Map<Heightmap.Types, long[]> captureHeightmaps(ChunkAccess target, java.util.Set<Heightmap.Types> types) {
        EnumMap<Heightmap.Types, long[]> result = new EnumMap<>(Heightmap.Types.class);
        for (var entry : target.getHeightmaps()) {
            if (types.contains(entry.getKey())) result.put(entry.getKey(), entry.getValue().getRawData().clone());
        }
        return result;
    }

    /**
     * Restores both pre-existing heightmap contents and the map's presence.
     * A BIOMES ProtoChunk is allowed to have no NOISE heightmap objects yet;
     * {@link ChunkAccess#setHeightmap} creates those objects during a
     * successful application.  The public ChunkAccess API has no remove
     * operation, so this version-pinned loader boundary removes only the
     * exact maps that were absent before the transaction.
     */
    private static void restoreHeightmaps(ChunkAccess target, java.util.Set<Heightmap.Types> types,
                                          Map<Heightmap.Types, long[]> original) {
        for (Heightmap.Types type : types) {
            long[] raw = original.get(type);
            if (raw != null) target.setHeightmap(type, raw);
            else removeHeightmap(target, type);
        }
    }

    @SuppressWarnings("unchecked")
    private static void removeHeightmap(ChunkAccess target, Heightmap.Types type) {
        try {
            Field field = null;
            Class<?> current = target.getClass();
            while (current != null && field == null) {
                try {
                    field = Names.declaredField(current, "heightmaps");
                } catch (NoSuchFieldException ignored) {
                    current = current.getSuperclass();
                }
            }
            if (field == null) throw new NoSuchFieldException("heightmaps");
            field.setAccessible(true);
            Object value = field.get(target);
            if (!(value instanceof Map<?, ?> maps)) {
                throw new IllegalStateException("ChunkAccess heightmaps field is not a map");
            }
            ((Map<Heightmap.Types, Heightmap>) maps).remove(type);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot remove a newly-created Minecraft heightmap", failure);
        }
    }

    private static List<short[]> capturePostProcessing(ShortList[] postProcessing) {
        List<short[]> result = new ArrayList<>(postProcessing.length);
        for (ShortList list : postProcessing) {
            if (list == null) {
                result.add(null);
            } else {
                short[] values = new short[list.size()];
                for (int index = 0; index < values.length; index++) values[index] = list.getShort(index);
                result.add(values);
            }
        }
        return result;
    }

    private static void restorePostProcessing(ShortList[] target, List<short[]> original) {
        for (int section = 0; section < target.length; section++) {
            short[] values = original.get(section);
            if (values == null) {
                target[section] = null;
                continue;
            }
            ShortList list = target[section];
            if (list == null) {
                list = new ShortArrayList(values.length);
                target[section] = list;
            } else {
                list.clear();
            }
            for (short value : values) list.add(value);
        }
    }

    private static int applyBlocks(ChunkAccess target, int chunkX, int chunkZ, int minY, int height,
                                   String[] denseStates, Map<String, BlockState> resolvedStates,
                                   List<OriginalBlock> originalBlocks) {
        int changed = 0;
        int baseX = Math.multiplyExact(chunkX, 16);
        int baseZ = Math.multiplyExact(chunkZ, 16);
        for (int localY = 0; localY < height; localY++) {
            int worldY = Math.addExact(minY, localY);
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int index = (localY * 16 + z) * 16 + x;
                BlockState next = resolvedStates.get(denseStates[index]);
                BlockPos position = new BlockPos(Math.addExact(baseX, x), worldY, Math.addExact(baseZ, z));
                BlockState previous = target.getBlockState(position);
                if (previous.equals(next)) continue;
                originalBlocks.add(new OriginalBlock(x, worldY, z, previous));
                Version.setBlock(target, position, next);
                changed++;
            }
        }
        return changed;
    }

    private static int applyFluidMarks(ChunkAccess target, ChunkNoiseResult result) {
        boolean[] marks = result.postProcessing().fluidMarks();
        int added = 0;
        int baseX = Math.multiplyExact(result.header().chunkX(), 16);
        int baseZ = Math.multiplyExact(result.header().chunkZ(), 16);
        // This is the same cell-X/cell-Z/Y-descending order used by the
        // version-pinned fillFromNoise traversal and oracle canonicalizer.
        for (int section = 0; section < result.header().sectionCount(); section++) {
            int sectionBase = section * 16 * 256;
            for (int cellX = 0; cellX < 16; cellX += 4) {
                for (int cellZ = 0; cellZ < 16; cellZ += 4) {
                    for (int localY = 15; localY >= 0; localY--) {
                        int yBase = sectionBase + localY * 256;
                        int worldY = Math.addExact(Math.addExact(result.header().minY(),
                                Math.multiplyExact(section, 16)), localY);
                        for (int x = cellX; x < cellX + 4; x++) for (int z = cellZ; z < cellZ + 4; z++) {
                            int index = yBase + z * 16 + x;
                            if (!marks[index]) continue;
                            BlockPos position = new BlockPos(Math.addExact(baseX, x), worldY,
                                    Math.addExact(baseZ, z));
                            int sectionIndex = target.getSectionIndex(worldY);
                            short packed = ProtoChunk.packOffsetCoordinates(position);
                            // ProtoChunk.addPackedPostProcess intentionally
                            // appends without deduplicating.  The pinned
                            // fillFromNoise loop can append an offset that was
                            // already present from an earlier stage, and the
                            // serialized endpoint preserves that multiplicity.
                            Version.addPostProcess(target, packed, sectionIndex);
                            added++;
                        }
                    }
                }
            }
        }
        return added;
    }

    private static void restoreBlocks(ChunkAccess target, List<OriginalBlock> originalBlocks) {
        for (int index = originalBlocks.size() - 1; index >= 0; index--) {
            OriginalBlock original = originalBlocks.get(index);
            int baseX = target.getPos().getMinBlockX();
            int baseZ = target.getPos().getMinBlockZ();
            Version.setBlock(target, new BlockPos(Math.addExact(baseX, original.x()), original.y(),
                            Math.addExact(baseZ, original.z())), original.state());
        }
    }

    private static void rollbackAfterFailure(ChunkMutationJournal journal, Throwable failure) {
        try {
            journal.rollback();
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static boolean descriptorMatches(BlockStateDescriptor descriptor, BlockState state) {
        var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (key == null || !descriptor.name().equals(key.toString())) return false;
        Map<String, String> actual = new HashMap<>();
        for (var entry : state.getValues().entrySet()) {
            actual.put(entry.getKey().getName(), propertyName(entry.getKey(), entry.getValue()));
        }
        return descriptor.properties().equals(actual);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyName(Property property, Comparable value) {
        return property.getName(value);
    }
}
