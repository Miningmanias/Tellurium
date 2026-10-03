// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.snapshot;

import dev.worldgennext.semantic.snapshot.RegistrySnapshot;
import dev.worldgennext.semantic.snapshot.BlockStateDescriptor;
import dev.worldgennext.semantic.snapshot.BlockStateTraits;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;

public final class RegistrySnapshotReader {
    public RegistrySnapshot requireCaptured(Object value) { if (value instanceof RegistrySnapshot snapshot) return snapshot; throw new IllegalArgumentException("Registry input must be captured before pure execution"); }

    /** Capture every process-stable Minecraft block-state identity; numeric IDs never cross the boundary. */
    public RegistrySnapshot capture(BlockState defaultBlock, BlockState defaultFluid) {
        if (defaultBlock == null || defaultFluid == null) throw new IllegalArgumentException("Generator defaults are missing");
        var states = new ArrayList<BlockStateDescriptor>(Block.BLOCK_STATE_REGISTRY.size());
        var traits = new LinkedHashMap<String, BlockStateTraits>();
        for (int id = 0; id < Block.BLOCK_STATE_REGISTRY.size(); id++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            if (state == null) throw new IllegalArgumentException("Missing block state registry entry " + id);
            var blockName = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (blockName == null) throw new IllegalArgumentException("Unregistered block state at id " + id);
            var properties = new LinkedHashMap<String, String>();
            for (var entry : state.getValues().entrySet()) {
                Property<?> property = entry.getKey();
                properties.put(property.getName(), propertyName(property, entry.getValue()));
            }
            var descriptor = new BlockStateDescriptor(blockName.toString(), properties);
            states.add(descriptor);
            traits.put(descriptor.canonical(), new BlockStateTraits(
                    state.isAir(), state.blocksMotion(), !state.getFluidState().isEmpty(),
                    state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock));
        }
        return new RegistrySnapshot(states, describe(defaultBlock), describe(defaultFluid), traits);
    }

    private static BlockStateDescriptor describe(BlockState state) {
        var blockName = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (blockName == null) throw new IllegalArgumentException("Unregistered generator default block");
        var properties = new LinkedHashMap<String, String>();
        for (var entry : state.getValues().entrySet()) properties.put(entry.getKey().getName(), propertyName(entry.getKey(), entry.getValue()));
        return new BlockStateDescriptor(blockName.toString(), properties);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyName(Property property, Comparable value) { return property.getName(value); }
}
