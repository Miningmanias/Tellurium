// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the fused-result applier install a prebuilt block container together with its block counts. */
@Mixin(LevelChunkSection.class)
public interface LevelChunkSectionAccessor {
    @Mutable
    @Accessor("states")
    void worldgenNext$setStates(PalettedContainer<BlockState> states);

    @Accessor("nonEmptyBlockCount")
    void worldgenNext$setNonEmptyBlockCount(short count);

    @Accessor("tickingBlockCount")
    void worldgenNext$setTickingBlockCount(short count);

    @Accessor("tickingFluidCount")
    void worldgenNext$setTickingFluidCount(short count);
}
