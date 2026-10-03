// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.fast;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;

/**
 * BiomeManager.getBiome picks one of the eight biome cells around a block by a
 * seeded distance and returns that cell's biome.  When all eight cells hold
 * the same biome the choice cannot matter, and the distance computation is
 * skipped.  The check is per chunk section: whether all of its 64 cells hold
 * one biome, determined once per biome container.
 *
 * <p>Applies to lookups through a WorldGenRegion (feature placement), which
 * reads cells exactly as done here: LevelReader.getNoiseBiome, then
 * ChunkAccess.getNoiseBiome with its clamp of the cell's y.</p>
 */
public final class UniformBiomeLookup {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("worldgennext.fast.uniformBiome", "true"));
    public static final boolean VERIFY = Boolean.getBoolean("worldgennext.fast.uniformBiomeVerify");

    /** Implemented by LevelChunkSection: its only biome, or null when it has several. */
    public interface Section {
        Holder<Biome> worldgenNext$uniformBiome();
    }

    /** The only biome stored in a container (or null), cached with the container it was read from. */
    public static final class Entry {
        public final PalettedContainerRO<Holder<Biome>> container;
        public final Holder<Biome> biome;

        public Entry(PalettedContainerRO<Holder<Biome>> container) {
            this.container = container;
            // getAll reports each value that is stored in a cell once.  The palette itself is no guide:
            // a refilled container keeps its previous default as an unused palette entry.
            Probe probe = new Probe();
            container.getAll(probe);
            this.biome = probe.count == 1 ? probe.first : null;
        }
    }

    private static final class Probe implements Consumer<Holder<Biome>> {
        int count;
        Holder<Biome> first;

        @Override
        public void accept(Holder<Biome> biome) {
            if (count++ == 0) first = biome;
        }
    }

    /** Verify mode only: why the shortcut did not apply (no chunk, other height, no section, several biomes, sections differ). */
    private static final AtomicLongArray MISSES = new AtomicLongArray(5);

    /** Verify mode only: shortcut answers that were compared with the original. */
    public static final AtomicLong VERIFIED = new AtomicLong();

    static {
        if (VERIFY) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("[worldgennext] Uniform biome lookup verification total: "
                    + VERIFIED.get() + " shortcut answers identical; misses " + MISSES), "worldgennext-biome-verify-summary"));
        }
    }

    private static Holder<Biome> miss(int reason) {
        if (VERIFY) MISSES.incrementAndGet(reason);
        return null;
    }

    private UniformBiomeLookup() {}

    /** The biome of all eight candidate cells of BiomeManager.getBiome(x, y, z), or null when they differ or are unavailable. */
    public static Holder<Biome> find(WorldGenRegion region, int blockX, int blockY, int blockZ) {
        int quartX = (blockX - 2) >> 2;
        int quartY = (blockY - 2) >> 2;
        int quartZ = (blockZ - 2) >> 2;
        // ChunkAccess.getNoiseBiome clamps the cell's y into the chunk before choosing the section.
        int minQuartY = QuartPos.fromBlock(region.getMinBuildHeight());
        int maxQuartY = minQuartY + QuartPos.fromBlock(region.getHeight()) - 1;
        int sectionY0 = Mth.clamp(quartY, minQuartY, maxQuartY) >> 2;
        int sectionY1 = Mth.clamp(quartY + 1, minQuartY, maxQuartY) >> 2;
        int chunkX0 = quartX >> 2, chunkX1 = (quartX + 1) >> 2;
        int chunkZ0 = quartZ >> 2, chunkZ1 = (quartZ + 1) >> 2;

        Holder<Biome> biome = null;
        for (int chunkX = chunkX0; chunkX <= chunkX1; chunkX++) {
            for (int chunkZ = chunkZ0; chunkZ <= chunkZ1; chunkZ++) {
                ChunkAccess chunk = region.getChunk(chunkX, chunkZ, ChunkStatus.BIOMES, false);
                if (chunk == null) return miss(0);
                if (chunk.getMinBuildHeight() != region.getMinBuildHeight() || chunk.getHeight() != region.getHeight()) return miss(1);
                LevelChunkSection[] sections = chunk.getSections();
                for (int sectionY = sectionY0; sectionY <= sectionY1; sectionY++) {
                    int index = chunk.getSectionIndexFromSectionY(sectionY);
                    if (index < 0 || index >= sections.length) return miss(2);
                    Holder<Biome> only = ((Section) sections[index]).worldgenNext$uniformBiome();
                    if (only == null) return miss(3);
                    if (biome == null) {
                        biome = only;
                    } else if (biome != only) {
                        return miss(4);
                    }
                }
            }
        }
        return biome;
    }
}
