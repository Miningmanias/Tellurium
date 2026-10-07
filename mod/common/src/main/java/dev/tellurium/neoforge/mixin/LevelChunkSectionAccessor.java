// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

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
    void tellurium$setStates(PalettedContainer<BlockState> states);

    @Accessor("nonEmptyBlockCount")
    void tellurium$setNonEmptyBlockCount(short count);

    @Accessor("tickingBlockCount")
    void tellurium$setTickingBlockCount(short count);

    @Accessor("tickingFluidCount")
    void tellurium$setTickingFluidCount(short count);
}
