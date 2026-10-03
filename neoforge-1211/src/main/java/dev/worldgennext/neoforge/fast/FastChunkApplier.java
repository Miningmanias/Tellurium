// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.fast;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.BitStorage;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes one fused-kernel chunk result into a fresh NOISE ProtoChunk with the
 * same observable state as NoiseBasedChunkGenerator.doFill: section block
 * states and counts, the two worldgen heightmaps, and post-processing marks
 * in doFill's iteration order.
 */
public final class FastChunkApplier {
    private FastChunkApplier() {}

    /**
     * @param data one byte per storage block (palette index | 0x80 mark), section-major,
     *             index = (localY * 16 + z) * 16 + x relative to the storage bottom
     */
    public static void apply(ChunkAccess chunk, byte[] data, int[] heights, BlockState[] palette,
                             int minY, int genHeight, int cellWidth, int cellHeight) {
        LevelChunkSection[] sections = chunk.getSections();
        int airIndex = -1;
        for (int i = 0; i < palette.length; i++) if (palette[i].isAir()) { airIndex = i; break; }
        for (int s = 0; s < sections.length; s++) {
            int offset = s * 4096;
            if (offset + 4096 > data.length) break;
            if (allAir(data, offset, palette)) continue; // doFill never touches an all-air section
            LevelChunkSection old = sections[s];
            sections[s] = buildSection(data, offset, palette, old.getBiomes());
        }
        // Worldgen heightmaps: doFill creates both and updates them for every placed block.
        int bits = Mth.ceillog2(chunk.getHeight() + 1);
        setHeightmap(chunk, Heightmap.Types.OCEAN_FLOOR_WG, heights, 0, bits, minY);
        setHeightmap(chunk, Heightmap.Types.WORLD_SURFACE_WG, heights, 256, bits, minY);
        // Post-processing marks in doFill order: cellX, cellZ, y descending, x in cell, z in cell.
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        int cells = 16 / cellWidth;
        for (int cx = 0; cx < cells; cx++) {
            for (int cz = 0; cz < cells; cz++) {
                for (int y = minY + genHeight - 1; y >= minY; y--) {
                    int rowBase = (y - chunk.getMinBuildHeight()) * 256;
                    for (int ix = 0; ix < cellWidth; ix++) {
                        int x = cx * cellWidth + ix;
                        for (int iz = 0; iz < cellWidth; iz++) {
                            int z = cz * cellWidth + iz;
                            if ((data[rowBase + z * 16 + x] & 0x80) != 0) {
                                pos.set(baseX + x, y, baseZ + z);
                                chunk.markPosForPostprocessing(pos);
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean allAir(byte[] data, int offset, BlockState[] palette) {
        for (int i = 0; i < 4096; i++) {
            if (!palette[data[offset + i] & 0x7F].isAir()) return false;
        }
        return true;
    }

    private static LevelChunkSection buildSection(byte[] data, int offset, BlockState[] palette,
                                                  PalettedContainerRO<Holder<Biome>> biomes) {
        int[] local = new int[palette.length];
        java.util.Arrays.fill(local, -1);
        List<BlockState> values = new ArrayList<>(4);
        Map<BlockState, Integer> byState = new IdentityHashMap<>();
        for (int i = 0; i < 4096; i++) {
            int index = data[offset + i] & 0x7F;
            if (local[index] < 0) {
                BlockState state = palette[index];
                Integer existing = byState.get(state);
                if (existing == null) {
                    existing = values.size();
                    values.add(state);
                    byState.put(state, existing);
                }
                local[index] = existing;
            }
        }
        int requested = Mth.ceillog2(values.size());
        int storageBits = requested == 0 ? 0 : requested <= 4 ? 4 : requested;
        BitStorage storage;
        if (storageBits == 0) {
            storage = new ZeroBitStorage(4096);
        } else {
            int perLong = 64 / storageBits;
            long[] raw = new long[(4096 + perLong - 1) / perLong];
            for (int i = 0; i < 4096; i++) {
                long value = local[data[offset + i] & 0x7F];
                raw[i / perLong] |= value << ((i % perLong) * storageBits);
            }
            storage = new SimpleBitStorage(storageBits, 4096, raw);
        }
        PalettedContainer<BlockState> states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                PalettedContainer.Strategy.SECTION_STATES,
                PalettedContainer.Strategy.SECTION_STATES.getConfiguration(Block.BLOCK_STATE_REGISTRY, requested),
                storage, values);
        return new LevelChunkSection(states, biomes);
    }

    private static void setHeightmap(ChunkAccess chunk, Heightmap.Types type, int[] heights, int offset, int bits, int minY) {
        SimpleBitStorage storage = new SimpleBitStorage(bits, 256);
        for (int i = 0; i < 256; i++) storage.set(i, heights[offset + i] - minY);
        chunk.setHeightmap(type, storage.getRaw());
    }
}
