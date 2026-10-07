// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.loader;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * What the mod needs from the mod loader, Fabric version.  Everything outside this package is the same on
 * every loader; the NeoForge module has its own class of this name with the same methods.
 */
public final class Loader {
    private Loader() {}

    public static String name() {
        return "Fabric";
    }

    public static Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    public static boolean isModLoaded(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }

    // ---- names (see Names) ----------------------------------------------------------------------------------
    // A development environment runs with the names the source uses; a released game with Fabric's
    // "intermediary" names.  tellurium/fabric-names.tsv, written by the build from the mappings the build
    // itself uses, holds the pairs for the names that appear in this mod's source and nothing else.

    private static final boolean TRANSLATES =
            !"named".equals(FabricLoader.getInstance().getMappingResolver().getCurrentRuntimeNamespace());
    /** Runtime class name to source class name. */
    private static final Map<String, String> CLASSES = new HashMap<>();
    /** "runtime class name/source field name" to runtime field name. */
    private static final Map<String, String> FIELDS = new HashMap<>();
    /** "runtime class name/source method name" to the runtime names of the methods of that name. */
    private static final Map<String, List<String>> METHODS = new HashMap<>();

    static {
        if (TRANSLATES) {
            try (var in = Loader.class.getResourceAsStream("/tellurium/fabric-names.tsv")) {
                if (in == null) throw new java.io.FileNotFoundException("tellurium/fabric-names.tsv");
                for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                    String[] part = line.strip().split("\t");
                    if (part.length == 3 && part[0].equals("c")) {
                        CLASSES.put(part[1].replace('/', '.'), part[2].replace('/', '.'));
                    } else if (part.length == 4 && part[0].equals("f")) {
                        FIELDS.put(part[1].replace('/', '.') + "/" + part[2], part[3]);
                    } else if (part.length == 4 && part[0].equals("m")) {
                        METHODS.computeIfAbsent(part[1].replace('/', '.') + "/" + part[2], key -> new ArrayList<>(1)).add(part[3]);
                    }
                }
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("The table of Minecraft names this mod needs on Fabric is missing from the jar", failure);
            }
        }
    }

    /** Whether the game runs with other names than the source uses (see {@link Names}). */
    public static boolean translatesNames() {
        return TRANSLATES;
    }

    public static String simpleName(Class<?> type) {
        String source = TRANSLATES ? CLASSES.get(type.getName()) : null;
        if (source == null) return type.getSimpleName();
        return source.substring(Math.max(source.lastIndexOf('.'), source.lastIndexOf('$')) + 1);
    }

    public static String fieldName(Class<?> owner, String name) {
        return TRANSLATES ? FIELDS.getOrDefault(owner.getName() + "/" + name, name) : name;
    }

    public static List<String> methodNames(Class<?> owner, String name) {
        return TRANSLATES ? METHODS.getOrDefault(owner.getName() + "/" + name, List.of()) : List.of();
    }

    /** The files of all loaded mods, in a stable order. */
    public static List<Path> modFiles() {
        return FabricLoader.getInstance().getAllMods().stream()
                .flatMap(mod -> mod.getOrigin().getKind() == ModOrigin.Kind.PATH ? mod.getOrigin().getPaths().stream() : Stream.<Path>empty())
                .filter(path -> java.nio.file.Files.isRegularFile(path))
                .distinct()
                .sorted()
                .toList();
    }

    /** Whether a chunk section leaves this block state out of its count of non-empty blocks: in vanilla, air. */
    public static boolean countsAsEmpty(BlockState state) {
        return state.isAir();
    }

    /** The temperature modifier the game uses for this biome. */
    public static Biome.TemperatureModifier temperatureModifier(Biome biome) {
        return biome.climateSettings.temperatureModifier();  // made accessible by tellurium.accesswidener
    }

    /**
     * Gives a climate sampler built from another whatever the loader attached to the original.  Fabric API's
     * biome module keeps the world seed on the sampler (its End biome source needs it and fails without) and
     * copies it itself wherever vanilla builds one sampler from another; this does the same for this mod's.
     * The interface is Fabric API's own, not a public one, so it is looked up by name: without it, or without a
     * seed on the original, the replacement is returned as it is.
     */
    public static Climate.Sampler carryOver(Climate.Sampler original, Climate.Sampler replacement) {
        try {
            Class<?> hooks = SAMPLER_HOOKS;
            if (hooks == null) SAMPLER_HOOKS = hooks = Class.forName("net.fabricmc.fabric.impl.biome.MultiNoiseSamplerHooks");
            if (hooks.isInstance(original) && hooks.isInstance(replacement)) {
                Object seed = hooks.getMethod("fabric_getSeed").invoke(original);
                hooks.getMethod("fabric_setSeed", long.class).invoke(replacement, seed);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absentOrUnset) {
            // No biome module, another version of it, or no seed on the original: nothing to carry over.
        }
        return replacement;
    }

    private static volatile Class<?> SAMPLER_HOOKS;

    /** Called on the server thread at the end of every server tick. */
    public static void onServerTickEnd(Consumer<MinecraftServer> listener) {
        ServerTickEvents.END_SERVER_TICK.register(listener::accept);
    }

    /**
     * Called on the server thread when a full chunk has been written out, after other mods have seen the save.
     * Fabric API has no such notice, so this registers nothing and returns false; callers then rely on
     * {@link #onChunkUnloaded}.
     */
    public static boolean onFullChunkSaved(BiConsumer<ServerLevel, ChunkPos> listener) {
        return false;
    }

    /** Called on the server thread when a full chunk is unloaded. */
    public static void onChunkUnloaded(BiConsumer<ServerLevel, ChunkPos> listener) {
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> listener.accept(level, chunk.getPos()));
    }
}
