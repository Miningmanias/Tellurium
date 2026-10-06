// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.loader;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * What the mod needs from the mod loader, NeoForge version.  Everything outside this package is the same on
 * every loader; each loader's build supplies its own class of this name with the same methods.
 */
public final class Loader {
    private Loader() {}

    public static String name() {
        return "NeoForge";
    }

    public static Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static boolean isModLoaded(String id) {
        return ModList.get().isLoaded(id);
    }

    /** Whether the game runs with other names than the source uses (see {@link Names}).  Not on NeoForge. */
    public static boolean translatesNames() {
        return false;
    }

    public static String simpleName(Class<?> type) {
        return type.getSimpleName();
    }

    public static String fieldName(Class<?> owner, String name) {
        return name;
    }

    public static List<String> methodNames(Class<?> owner, String name) {
        return List.of();
    }

    /** The files of all loaded mods, in a stable order. */
    public static List<Path> modFiles() {
        return ModList.get().applyForEachModFileAlphabetical(file -> file.getFilePath()).toList();
    }

    /**
     * Whether a chunk section leaves this block state out of its count of non-empty blocks.  NeoForge asks the
     * block; vanilla asks whether it is air.
     */
    public static boolean countsAsEmpty(BlockState state) {
        return state.isEmpty();
    }

    /** The temperature modifier the game uses for this biome: on NeoForge the one after biome modifiers. */
    public static Biome.TemperatureModifier temperatureModifier(Biome biome) {
        return biome.getModifiedClimateSettings().temperatureModifier();
    }

    /**
     * Gives a climate sampler built from another whatever the loader attached to the original.  NeoForge
     * attaches nothing.
     */
    public static Climate.Sampler carryOver(Climate.Sampler original, Climate.Sampler replacement) {
        return replacement;
    }

    /** Called on the server thread at the end of every server tick. */
    public static void onServerTickEnd(Consumer<MinecraftServer> listener) {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> listener.accept(event.getServer()));
    }

    /**
     * Called on the server thread when a full chunk has been written out, after other mods have seen the save.
     * False when this loader has no such notice; callers then rely on {@link #onChunkUnloaded}.
     */
    public static boolean onFullChunkSaved(BiConsumer<ServerLevel, ChunkPos> listener) {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ChunkDataEvent.Save event) -> {
            if (event.getChunk() instanceof LevelChunk && event.getLevel() instanceof ServerLevel level) {
                listener.accept(level, event.getChunk().getPos());
            }
        });
        return true;
    }

    /** Called on the server thread when a full chunk is unloaded, after other mods have seen it. */
    public static void onChunkUnloaded(BiConsumer<ServerLevel, ChunkPos> listener) {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ChunkEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel level) listener.accept(level, event.getChunk().getPos());
        });
    }
}
