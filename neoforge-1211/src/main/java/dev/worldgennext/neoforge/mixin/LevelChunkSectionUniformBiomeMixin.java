// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import dev.worldgennext.neoforge.fast.UniformBiomeLookup;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Remembers whether every cell of the section holds the same biome.
 * The section only ever replaces its biome container (it holds the read-only
 * view), so the answer is cached against the container's identity.
 */
@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionUniformBiomeMixin implements UniformBiomeLookup.Section {
    @Shadow private PalettedContainerRO<Holder<Biome>> biomes;

    /** Immutable; replaced as a whole, so concurrent readers see a consistent pair. */
    @Unique private volatile UniformBiomeLookup.Entry worldgenNext$uniformBiome;

    @Override
    public Holder<Biome> worldgenNext$uniformBiome() {
        PalettedContainerRO<Holder<Biome>> container = biomes;
        UniformBiomeLookup.Entry entry = worldgenNext$uniformBiome;
        if (entry == null || entry.container != container) {
            entry = new UniformBiomeLookup.Entry(container);
            worldgenNext$uniformBiome = entry;
        }
        return entry.biome;
    }
}
