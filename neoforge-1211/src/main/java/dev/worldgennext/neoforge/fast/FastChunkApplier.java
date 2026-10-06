// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.fast;

import dev.worldgennext.neoforge.version.Version;

import dev.worldgennext.neoforge.loader.Loader;

import dev.worldgennext.neoforge.mixin.LevelChunkSectionAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.BitStorage;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes one fused-kernel chunk result into a fresh NOISE ProtoChunk with the
 * same observable state as NoiseBasedChunkGenerator.doFill (and, when the
 * surface stage ran, SurfaceSystem.buildSurface): section block states and
 * counts, the two worldgen heightmaps, and post-processing marks in the
 * original iteration order.
 */
public final class FastChunkApplier {
    private FastChunkApplier() {}

    /** Per-level palette facts, computed once. */
    public static final class PaletteInfo {
        final BlockState[] palette;
        /** Palette index -> index of the first palette entry holding the same state. */
        final int[] canonical;
        final boolean[] air;
        final boolean[] surfaceFluid;
        // LevelChunkSection.recalcBlockCounts contributions of one block of each state.
        final int[] nonEmpty, tickingBlock, tickingFluid;

        public PaletteInfo(BlockState[] palette, int basePaletteSize) {
            this.palette = palette;
            int n = palette.length;
            canonical = new int[n];
            air = new boolean[128];
            surfaceFluid = new boolean[128];
            nonEmpty = new int[n];
            tickingBlock = new int[n];
            tickingFluid = new int[n];
            for (int i = 0; i < n; i++) {
                BlockState state = palette[i];
                canonical[i] = i;
                for (int k = 0; k < i; k++) if (palette[k] == state) { canonical[i] = k; break; }
                air[i] = state.isAir();
                FluidState fluid = state.getFluidState();
                surfaceFluid[i] = i >= basePaletteSize && !fluid.isEmpty();
                if (!Loader.countsAsEmpty(state)) {
                    nonEmpty[i]++;
                    if (state.isRandomlyTicking()) tickingBlock[i]++;
                }
                if (!fluid.isEmpty()) {
                    nonEmpty[i]++;
                    if (fluid.isRandomlyTicking()) tickingFluid[i]++;
                }
            }
        }
    }

    /**
     * @param data one byte per storage block (palette index | 0x80 mark), section-major,
     *             index = (localY * 16 + z) * 16 + x relative to the storage bottom
     */
    public static void apply(ChunkAccess chunk, byte[] data, int[] heights, PaletteInfo info,
                             int minY, int genHeight, int cellWidth, int cellHeight) {
        LevelChunkSection[] sections = chunk.getSections();
        boolean[] marked = new boolean[sections.length];
        boolean anyMark = false;
        int[] local = new int[info.palette.length];
        int[] counts = new int[info.palette.length];
        byte[] indices = new byte[4096];
        for (int s = 0; s < sections.length; s++) {
            int offset = s * 4096;
            if (offset + 4096 > data.length) break;
            Arrays.fill(local, -1);
            int distinct = 0, marks = 0;
            boolean onlyAir = true;
            for (int i = 0; i < 4096; i++) {
                int raw = data[offset + i];
                marks |= raw;
                int index = info.canonical[raw & 0x7F];
                int slot = local[index];
                if (slot < 0) {
                    slot = distinct++;
                    local[index] = slot;
                    counts[slot] = 0;
                    onlyAir &= info.air[index];
                }
                counts[slot]++;
                indices[i] = (byte) slot;
            }
            if ((marks & 0x80) != 0) {
                marked[s] = true;
                anyMark = true;
            }
            if (onlyAir) continue; // doFill never touches an all-air section
            sections[s] = buildSection(indices, local, counts, distinct, info, sections[s].getBiomes());
        }
        // Worldgen heightmaps: doFill creates both and updates them for every placed block.
        int bits = Mth.ceillog2(chunk.getHeight() + 1);
        setHeightmap(chunk, Heightmap.Types.OCEAN_FLOOR_WG, heights, 0, bits, minY);
        setHeightmap(chunk, Heightmap.Types.WORLD_SURFACE_WG, heights, 256, bits, minY);
        if (!anyMark) return;
        // Post-processing marks in doFill order: cellX, cellZ, y descending, x in cell, z in cell.
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        int bottom = Version.minY(chunk);
        boolean[] surfaceFluid = info.surfaceFluid;
        boolean surfaceMarks = false;
        int cells = 16 / cellWidth;
        for (int cx = 0; cx < cells; cx++) {
            for (int cz = 0; cz < cells; cz++) {
                for (int y = minY + genHeight - 1; y >= minY; y--) {
                    int ly = y - bottom;
                    if (!marked[ly >> 4]) {
                        y -= (ly & 15); // skip the rest of this unmarked section
                        continue;
                    }
                    int rowBase = ly * 256;
                    for (int ix = 0; ix < cellWidth; ix++) {
                        int x = cx * cellWidth + ix;
                        for (int iz = 0; iz < cellWidth; iz++) {
                            int z = cz * cellWidth + iz;
                            int raw = data[rowBase + z * 16 + x];
                            if ((raw & 0x80) != 0) {
                                if (surfaceFluid[raw & 0x7F]) {
                                    surfaceMarks = true;
                                } else {
                                    pos.set(baseX + x, y, baseZ + z);
                                    chunk.markPosForPostprocessing(pos);
                                }
                            }
                        }
                    }
                }
            }
        }
        // Surface marks follow in buildSurface order: x, z, y descending.  A marked fluid with a
        // surface-palette index was placed by a surface rule; a marked non-fluid surface state (ice
        // over aquifer water) inherited the mark doFill gave the fluid it replaced.
        if (surfaceMarks) {
            int storageHeight = data.length / 256;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int ly = storageHeight - 1; ly >= 0; ly--) {
                        if (!marked[ly >> 4]) {
                            ly -= (ly & 15);
                            continue;
                        }
                        int raw = data[ly * 256 + z * 16 + x];
                        if ((raw & 0x80) != 0 && surfaceFluid[raw & 0x7F]) {
                            pos.set(baseX + x, bottom + ly, baseZ + z);
                            chunk.markPosForPostprocessing(pos);
                        }
                    }
                }
            }
        }
    }

    private static LevelChunkSection buildSection(byte[] indices, int[] local, int[] counts, int distinct, PaletteInfo info,
                                                  PalettedContainerRO<Holder<Biome>> biomes) {
        BlockState[] ordered = new BlockState[distinct];
        int nonEmpty = 0, tickingBlock = 0, tickingFluid = 0;
        for (int index = 0; index < local.length; index++) {
            int slot = local[index];
            if (slot < 0) continue;
            ordered[slot] = info.palette[index];
            nonEmpty += info.nonEmpty[index] * counts[slot];
            tickingBlock += info.tickingBlock[index] * counts[slot];
            tickingFluid += info.tickingFluid[index] * counts[slot];
        }
        List<BlockState> values = new ArrayList<>(distinct);
        for (BlockState state : ordered) values.add(state);
        int requested = Mth.ceillog2(distinct);
        int storageBits = requested == 0 ? 0 : requested <= 4 ? 4 : requested;
        long[] packedIndices = null;
        if (storageBits != 0) {
            int perLong = 64 / storageBits;
            long[] raw = new long[(4096 + perLong - 1) / perLong];
            int at = 0;
            for (int word = 0; word < raw.length; word++) {
                long packed = 0;
                int end = Math.min(4096, at + perLong);
                for (int shift = 0; at < end; at++, shift += storageBits) packed |= (long) indices[at] << shift;
                raw[word] = packed;
            }
            packedIndices = raw;
        }
        PalettedContainer<BlockState> states = Version.blockStates(values, requested, storageBits, packedIndices);
        // The section constructor recounts all 4096 entries; build it around a one-state container
        // (counted in constant time), then install the real container with the counts taken above.
        LevelChunkSection section = new LevelChunkSection(Version.airBlockStates(), biomes);
        LevelChunkSectionAccessor access = (LevelChunkSectionAccessor) section;
        access.worldgenNext$setStates(states);
        access.worldgenNext$setNonEmptyBlockCount((short) nonEmpty);
        access.worldgenNext$setTickingBlockCount((short) tickingBlock);
        access.worldgenNext$setTickingFluidCount((short) tickingFluid);
        return section;
    }

    private static void setHeightmap(ChunkAccess chunk, Heightmap.Types type, int[] heights, int offset, int bits, int minY) {
        SimpleBitStorage storage = new SimpleBitStorage(bits, 256);
        for (int i = 0; i < 256; i++) storage.set(i, heights[offset + i] - minY);
        chunk.setHeightmap(type, storage.getRaw());
    }
}
