// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.config;

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
 * {@code config/worldgennext.toml}.
 *
 * <p>Every switch in the mod is a {@code -Dworldgennext.*} system property
 * read where it is used.  This class maps the few user-facing options onto
 * those properties before any of them is read (it is loaded from the mixin
 * plugin, ahead of every mixin).  A property given on the command line always
 * wins over the file, so developer overrides keep working.</p>
 *
 * <p>No Minecraft classes are referenced here: the class runs before the game
 * is loaded.</p>
 */
public final class UserSettings {
    public static final String FILE_NAME = "worldgennext.toml";

    /** Switches that restore the original code path for everything the mod changes. */
    private static final String[][] ALL_OFF = {
            {"worldgennext.fast.gpu", "off"}, {"worldgennext.parallelStructureSteps", "false"},
            {"worldgennext.parallelSurfaceCarvers", "false"}, {"worldgennext.parallelFeatures", "false"},
            {"worldgennext.asyncChunkSave", "false"}, {"worldgennext.asyncChunkCompress", "false"},
            {"worldgennext.biomeColumnCache", "false"}, {"worldgennext.unloadTypeCache", "false"},
            {"worldgennext.fast.rtreeStoreSkip", "false"}, {"worldgennext.fast.biomeIndex", "false"},
            {"worldgennext.fast.aquiferPrefill", "false"}, {"worldgennext.fast.orePlacement", "false"},
            {"worldgennext.fast.lazyNoiseWrap", "false"}, {"worldgennext.fast.uniformBiome", "false"}, {"worldgennext.fast.cavePlans", "false"}, {"worldgennext.fast.heightCache", "false"},
            {"worldgennext.asyncIoMailboxBatch", "1"}, {"worldgennext.asyncGroupCommit", "false"}, {"worldgennext.asyncChunkLoad", "false"}, {"worldgennext.parallelMailboxThreads", "false"},
            {"worldgennext.fast.regionChunkCache", "false"}, {"worldgennext.regionHeaderBatch", "false"},
            {"worldgennext.fast.freshRegionShortcut", "false"}, {"worldgennext.fast.shapeCache", "false"},
            {"worldgennext.unloadPacing", "false"}, {"worldgennext.promptTaskRelease", "false"}};

    private static final String DEFAULT_FILE = """
            # WorldgenNext configuration.  Changes take effect on the next server start.
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
            #           two block by block.  Slower than vanilla.  /worldgennext status shows how
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
            # How many chunks /worldgennext pregen works on at once.  Higher is faster and
            # uses more memory; lower it if the server runs out of heap.
            in_flight = 1024
            # Seconds between progress messages.
            progress_seconds = 10
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
                Files.writeString(file, DEFAULT_FILE, StandardCharsets.UTF_8);
                created = true;
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
                    "pregen.in_flight", "pregen.progress_seconds").contains(key)) {
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
        published.put("worldgennext.fast.gpu", gpuMode().equals("check") ? "force" : gpuMode());
        if (gpuMode().equals("check")) published.put("worldgennext.fast.check", "true");
        String parallel = Boolean.toString(parallelSteps());
        published.put("worldgennext.parallelStructureSteps", parallel);
        published.put("worldgennext.parallelSurfaceCarvers", parallel);
        published.put("worldgennext.parallelFeatures", parallel);
        String async = Boolean.toString(asyncSaving());
        published.put("worldgennext.asyncChunkSave", async);
        published.put("worldgennext.asyncChunkCompress", async);
        published.put("worldgennext.asyncChunkCompressLevel", Integer.toString(compressionLevel()));
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
