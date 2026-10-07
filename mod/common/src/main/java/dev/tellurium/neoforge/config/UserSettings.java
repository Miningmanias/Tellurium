// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The options a server owner is expected to touch, read from
 * {@code config/tellurium.toml}.
 *
 * <p>Every switch in the mod is a {@code -Dtellurium.*} system property
 * read where it is used.  This class maps the few user-facing options onto
 * those properties before any of them is read (it is loaded from the mixin
 * plugin, ahead of every mixin).  A property given on the command line always
 * wins over the file, so developer overrides keep working.</p>
 *
 * <p>No Minecraft classes are referenced here: the class runs before the game
 * is loaded.</p>
 */
public final class UserSettings {
    public static final String FILE_NAME = "tellurium.toml";
    /** The file's name while the mod was called WorldgenNext (up to 0.2.0); read once, when the new file does not exist yet. */
    static final String FORMER_FILE_NAME = "worldgennext.toml";

    /** Switches that restore the original code path for everything the mod changes. */
    private static final String[][] ALL_OFF = {
            {"tellurium.fast.gpu", "off"}, {"tellurium.parallelStructureSteps", "false"},
            {"tellurium.parallelSurfaceCarvers", "false"}, {"tellurium.parallelFeatures", "false"},
            {"tellurium.asyncChunkSave", "false"}, {"tellurium.asyncChunkCompress", "false"},
            {"tellurium.biomeColumnCache", "false"}, {"tellurium.unloadTypeCache", "false"},
            {"tellurium.fast.rtreeStoreSkip", "false"}, {"tellurium.fast.biomeIndex", "false"},
            {"tellurium.fast.aquiferPrefill", "false"}, {"tellurium.fast.orePlacement", "false"},
            {"tellurium.fast.lazyNoiseWrap", "false"}, {"tellurium.fast.uniformBiome", "false"}, {"tellurium.fast.cavePlans", "false"}, {"tellurium.fast.heightCache", "false"},
            {"tellurium.asyncIoMailboxBatch", "1"}, {"tellurium.asyncGroupCommit", "false"}, {"tellurium.asyncChunkLoad", "false"}, {"tellurium.parallelMailboxThreads", "false"},
            {"tellurium.fast.regionChunkCache", "false"}, {"tellurium.regionHeaderBatch", "false"},
            {"tellurium.fast.freshRegionShortcut", "false"}, {"tellurium.fast.shapeCache", "false"},
            {"tellurium.unloadPacing", "false"}, {"tellurium.promptTaskRelease", "false"},
            {"tellurium.dh.mode", "off"}, {"tellurium.voxy.generate", "false"}};

    private static final String DEFAULT_FILE = """
            # Tellurium configuration.  Changes take effect on the next server start.
            # Delete this file to get a fresh copy with the defaults.

            # Master switch.  false turns off everything the mod changes; the server then
            # generates and saves chunks exactly as it would without the mod installed.
            enabled = true

            [gpu]
            # "auto"  - use the GPU for world generators on the tested list (vanilla, Terralith,
            #           Tectonic and both together, Incendium, Amplified Nether, Nullscape);
            #           anything else generates with vanilla code.
            # "check" - for a world generator that is not on the tested list (datapacks, other
            #           terrain mods): generate with vanilla code, also run the GPU, and compare the
            #           two block by block.  Slower than vanilla.  /tellurium status shows how
            #           many chunks were compared and how many differed.
            # "force" - use the GPU for any world generator the kernels can be built for.
            #           Untested generators are not guaranteed to produce identical terrain;
            #           run "check" first.
            # "off"   - never use the GPU.  The CPU-side optimizations stay on.
            mode = "auto"

            [generation]
            # Run structure, surface, carver and feature steps of different chunks at the same
            # time instead of one after another.
            parallel_steps = true

            [saving]
            # Encode and compress chunks on background threads when they unload.
            async = true
            # Deflate level for chunks saved that way: 1 (fastest, files about 12% larger)
            # to 9 (smallest).  6 matches the file size of an unmodded server.
            compression_level = 1

            [pregen]
            # How many chunks /tellurium pregen works on at once.  Higher is faster and
            # uses more memory; lower it if the server runs out of heap.
            in_flight = 1024
            # Seconds between progress messages.
            progress_seconds = 10
            # If the Chunky pregenerator is installed: let it work on as many chunks at once as
            # in_flight above (reduced on small heaps) instead of its own default of 50, which
            # holds this mod to about 1,000 chunks per second.  false leaves Chunky alone.
            tune_chunky = true

            [voxy]
            # Only matters if Voxy is installed and this is singleplayer or the host of a LAN world.
            # Generate the terrain around each player that Voxy shows in the distance, and hand it to
            # Voxy.  Without this (or the separate Voxy WorldGen mod) Voxy only shows where you have been.
            generate = true
            # How far around each player, in chunks (16-1024).  The chunks are real and are saved.
            radius = 128

            [distant_horizons]
            # Only matters if Distant Horizons is installed.
            # "hybrid" - Distant Horizons keeps its own generator and plan (rough surface for far
            #            terrain first); the chunks it generates to refine that come from the
            #            server's own, accelerated chunk generation instead.
            # "direct" - all of its distant terrain is built from finished chunks.  Fastest way to
            #            full detail, but nothing is shown for an area until its chunks are done.
            # "off"    - Distant Horizons is left alone.
            # With "hybrid" and "direct" those chunks are real and are saved in the world.
            generator = "hybrid"
            # false - ("hybrid" only) chunks made for Distant Horizons stop before lighting and are
            #         saved unfinished; the game finishes them when a player gets there.  Faster.
            # true  - they are generated completely, so the area is fully pregenerated as well.
            full_chunks = false
            """;

    private static volatile UserSettings LOADED;

    private final Path file;
    private final boolean created;
    private final Map<String, String> values;
    private final List<String> problems = new ArrayList<>();

    private UserSettings(Path file, boolean created, Map<String, String> values) {
        this.file = file;
        this.created = created;
        this.values = values;
    }

    /** The settings applied at startup, or defaults if {@link #loadAndApply} never ran. */
    public static UserSettings get() {
        UserSettings loaded = LOADED;
        return loaded != null ? loaded : new UserSettings(Path.of("config", FILE_NAME), false, Map.of());
    }

    /** Reads (or creates) the file and publishes its options as system properties.  Idempotent. */
    public static synchronized UserSettings loadAndApply(Path configDirectory) {
        if (LOADED != null) return LOADED;
        UserSettings settings = read(configDirectory);
        // The command line wins: a property that is already set is left alone.
        settings.systemProperties().forEach((property, value) -> {
            if (System.getProperty(property) == null) System.setProperty(property, value);
        });
        LOADED = settings;
        return settings;
    }

    /** Reads the file, creating it with the defaults when missing; changes nothing else. */
    static UserSettings read(Path configDirectory) {
        Path file = configDirectory.resolve(FILE_NAME).toAbsolutePath().normalize();
        boolean created = false;
        Map<String, String> values = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Path former = file.resolveSibling(FORMER_FILE_NAME);
                if (Files.isRegularFile(former)) {
                    // Settings made under the mod's earlier name are kept: the file is copied, not moved.
                    Files.copy(former, file);
                } else {
                    Files.writeString(file, DEFAULT_FILE, StandardCharsets.UTF_8);
                    created = true;
                }
            }
            parse(Files.readAllLines(file, StandardCharsets.UTF_8), values, problems);
        } catch (IOException | RuntimeException failure) {
            problems.add("could not read " + file + " (" + failure + "); using defaults");
            values.clear();
        }
        UserSettings settings = new UserSettings(file, created, values);
        settings.problems.addAll(problems);
        settings.systemProperties();
        return settings;
    }

    /** Flat TOML subset: comments, [tables], and key = "string" | integer | true | false. */
    static void parse(List<String> lines, Map<String, String> values, List<String> problems) {
        String table = "";
        int number = 0;
        for (String raw : lines) {
            number++;
            String line = stripComment(raw).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                table = line.substring(1, line.length() - 1).trim() + ".";
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                problems.add("line " + number + " is not 'key = value': " + raw.trim());
                continue;
            }
            String key = table + line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
    }

    private static String stripComment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == '#' && !quoted) return line.substring(0, i);
        }
        return line;
    }

    /** The developer switches these options stand for.  Also records every problem with the file. */
    Map<String, String> systemProperties() {
        for (String key : values.keySet()) {
            if (!List.of("enabled", "gpu.mode", "generation.parallel_steps", "saving.async", "saving.compression_level",
                    "pregen.in_flight", "pregen.progress_seconds", "pregen.tune_chunky", "distant_horizons.generator", "distant_horizons.full_chunks",
                    "voxy.generate", "voxy.radius")
                    .contains(key)) {
                String text = "unknown option '" + key + "' ignored";
                if (!problems.contains(text)) problems.add(text);
            }
        }
        pregenInFlight();
        pregenProgressSeconds();
        Map<String, String> published = new LinkedHashMap<>();
        if (!enabled()) {
            for (String[] off : ALL_OFF) published.put(off[0], off[1]);
            return published;
        }
        // "check" runs the kernels for any generator, like "force", but keeps the original result and compares.
        published.put("tellurium.fast.gpu", gpuMode().equals("check") ? "force" : gpuMode());
        if (gpuMode().equals("check")) published.put("tellurium.fast.check", "true");
        String parallel = Boolean.toString(parallelSteps());
        published.put("tellurium.parallelStructureSteps", parallel);
        published.put("tellurium.parallelSurfaceCarvers", parallel);
        published.put("tellurium.parallelFeatures", parallel);
        String async = Boolean.toString(asyncSaving());
        published.put("tellurium.asyncChunkSave", async);
        published.put("tellurium.asyncChunkCompress", async);
        published.put("tellurium.asyncChunkCompressLevel", Integer.toString(compressionLevel()));
        // Chunky reads this property once, when its generation task class loads.
        if (tuneChunky()) published.put("chunky.maxWorkingCount", Integer.toString(chunkyInFlight(Runtime.getRuntime().maxMemory())));
        published.put("tellurium.dh.mode", distantHorizonsGenerator());
        published.put("tellurium.voxy.generate", Boolean.toString(bool("voxy.generate", true)));
        published.put("tellurium.voxy.radius", Integer.toString(integer("voxy.radius", 128, 16, 1024)));
        published.put("tellurium.dh.fullChunks", Boolean.toString(bool("distant_horizons.full_chunks", false)));
        return published;
    }

    public Path file() { return file; }

    /** True when this start wrote the default file. */
    public boolean created() { return created; }

    /** Things in the file that were ignored or replaced by a default, for the log and the status command. */
    public List<String> problems() { return List.copyOf(problems); }

    public boolean enabled() { return bool("enabled", true); }

    public String gpuMode() {
        String mode = values.getOrDefault("gpu.mode", "auto").trim().toLowerCase(Locale.ROOT);
        if (mode.equals("auto") || mode.equals("force") || mode.equals("off") || mode.equals("check")) return mode;
        problem("gpu.mode", mode, "\"auto\", \"check\", \"force\" or \"off\"", "auto");
        return "auto";
    }

    public boolean parallelSteps() { return bool("generation.parallel_steps", true); }

    public boolean asyncSaving() { return bool("saving.async", true); }

    public int compressionLevel() { return integer("saving.compression_level", 1, 1, 9); }

    public int pregenInFlight() { return integer("pregen.in_flight", 1024, 16, 16384); }

    public int pregenProgressSeconds() { return integer("pregen.progress_seconds", 10, 1, 3600); }

    public boolean tuneChunky() { return bool("pregen.tune_chunky", true); }
    public String distantHorizonsGenerator() {
        String mode = values.getOrDefault("distant_horizons.generator", "hybrid").trim().toLowerCase(Locale.ROOT);
        if (mode.equals("hybrid") || mode.equals("direct") || mode.equals("off")) return mode;
        problem("distant_horizons.generator", mode, "\"hybrid\", \"direct\" or \"off\"", "hybrid");
        return "hybrid";
    }

    /** pregen.in_flight, held to one chunk per 12 MB of heap (not below 32): the built-in pregenerator's rule. */
    public int chunkyInFlight(long maxHeapBytes) {
        return (int) Math.min(pregenInFlight(), Math.max(32, (maxHeapBytes >> 20) / 12));
    }

    private boolean bool(String key, boolean fallback) {
        String value = values.get(key);
        if (value == null) return fallback;
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        problem(key, value, "true or false", Boolean.toString(fallback));
        return fallback;
    }

    private int integer(String key, int fallback, int min, int max) {
        String value = values.get(key);
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value.replace("_", ""));
            if (parsed >= min && parsed <= max) return parsed;
        } catch (NumberFormatException notANumber) {
            // reported below
        }
        problem(key, value, "a whole number from " + min + " to " + max, Integer.toString(fallback));
        return fallback;
    }

    private void problem(String key, String value, String expected, String used) {
        String text = key + " = " + value + " is not " + expected + "; using " + used;
        if (!problems.contains(text)) problems.add(text);
    }
}
