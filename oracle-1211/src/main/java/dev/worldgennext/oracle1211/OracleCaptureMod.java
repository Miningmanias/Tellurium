// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle1211;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * Original-only capture process. This module intentionally has no dependency on
 * WorldgenNext, its compiler, its runtime, or its mutation hook.
 */
@Mod(OracleCaptureMod.MOD_ID)
public final class OracleCaptureMod {
    public static final String MOD_ID = "worldgennext_oracle";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String FORMAT = "WORLDGENNEXT-SNAPSHOT-1\n";
    private static final List<String> FIELD_ORDER = List.of(
            "BLOCK_STATES", "BIOMES", "HEIGHTMAPS", "POSTPROCESSING", "LIGHT",
            "TICKS", "BLOCK_ENTITIES", "STRUCTURES", "ENTITIES", "LIFECYCLE");
    private record CaptureCase(int chunkX, int chunkZ, Path output) {}

    public OracleCaptureMod() {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        LOGGER.info("Independent WorldgenNext oracle loaded; candidate runtime is not a dependency");
    }

    private void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.parseBoolean(System.getProperty("worldgennext.oracle.capture", "false"))) return;
        event.getServer().execute(() -> captureAndStop(event.getServer()));
    }

    private void captureAndStop(MinecraftServer server) {
        try {
            List<CaptureCase> cases = captureCases();
            String requestedEndpoint = System.getProperty("worldgennext.oracle.endpoint", "NOISE").trim().toUpperCase(Locale.ROOT);
            // SAVED is a logical endpoint, not a Minecraft ChunkStatus.  It
            // observes the fully generated chunk after the explicit save
            // barrier; a separate process invocation is responsible for the
            // reopened observation.
            boolean savedEndpoint = requestedEndpoint.equals("SAVED") || requestedEndpoint.equals("SAVED_REOPENED");
            String endpoint = savedEndpoint ? "SAVED" : requestedEndpoint;
            ChunkStatus status = savedEndpoint
                    ? ChunkStatus.FULL
                    : ChunkStatus.byName(endpoint.toLowerCase(Locale.ROOT));
            if (status == null) throw new IllegalArgumentException("Unknown chunk endpoint: " + endpoint);

            String requestedDimension = textProperty("worldgennext.oracle.dimension", "minecraft:overworld");
            String source = textProperty("worldgennext.oracle.source", "ORIGINAL");
            String stack = textProperty("worldgennext.oracle.stack", "minecraft-1.21.1-neoforge-21.1.176");
            for (CaptureCase captureCase : cases) {
                captureOne(server, captureCase, requestedDimension, status, endpoint, savedEndpoint, source, stack);
            }
            server.saveEverything(false, true, false);
            LOGGER.info("Completed independent oracle capture batch cases={}", cases.size());
        } catch (Throwable failure) {
            LOGGER.error("Independent oracle capture failed", failure);
        } finally {
            server.halt(false);
        }
    }

    private static void captureOne(MinecraftServer server, CaptureCase captureCase, String requestedDimension,
                                   ChunkStatus status, String endpoint, boolean savedEndpoint,
                                   String source, String stack) throws IOException {
        int chunkX = captureCase.chunkX();
        int chunkZ = captureCase.chunkZ();
        ServerLevel level = findLevel(server, requestedDimension);
        ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, status, true);
        if (chunk == null) throw new IllegalStateException("Minecraft returned no chunk for " + chunkX + "," + chunkZ);
        String seed = Long.toString(server.getWorldData().worldGenOptions().seed());
        String dimension = level.dimension().location().toString();
        String context = sha256(seed + "|" + dimension + "|" + endpoint + "|" + chunkX + "|" + chunkZ);
        String identity = String.join("/", source, stack, seed, dimension, Integer.toString(chunkX),
                Integer.toString(chunkZ), endpoint, context);

        if (savedEndpoint) {
            server.saveEverything(false, true, false);
            LOGGER.info("Completed logical save barrier before SAVED capture at chunk {},{}", chunkX, chunkZ);
        }
        // Serialize after the barrier for SAVED so the artifact is the
        // post-flush logical view, not merely the pre-save in-memory view.
        CompoundTag serialized = ChunkSerializer.write(level, chunk);
        writeSnapshot(captureCase.output(), identity, serialized, seed, dimension, endpoint, chunk);
        LOGGER.info("Wrote independent oracle capture {} for {} {} at chunk {},{}",
                captureCase.output(), dimension, endpoint, chunkX, chunkZ);
    }

    /**
     * Reads the optional bounded batch manifest.  Each non-empty line is
     * {@code chunkX<TAB>chunkZ<TAB>absolute-output-path}; the server process,
     * endpoint, dimension and stack remain fixed for the whole batch.  The
     * manifest is only a launch optimization: each output still uses CREATE_NEW
     * and retains its own complete identity.
     */
    private static List<CaptureCase> captureCases() throws IOException {
        String caseFile = System.getProperty("worldgennext.oracle.caseFile", "").trim();
        if (caseFile.isEmpty()) {
            return List.of(new CaptureCase(integerProperty("worldgennext.oracle.chunkX", 0),
                    integerProperty("worldgennext.oracle.chunkZ", 0),
                    requiredPath("worldgennext.oracle.output")));
        }
        Path manifest = Path.of(caseFile).toAbsolutePath().normalize();
        if (!Files.isRegularFile(manifest, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Oracle case manifest is not a regular file: " + manifest);
        }
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        var result = new ArrayList<CaptureCase>();
        Set<String> coordinates = new HashSet<>();
        Set<Path> outputs = new HashSet<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 3 || fields[2].isBlank()) {
                throw new IllegalArgumentException("Malformed oracle case manifest line " + (index + 1));
            }
            int chunkX;
            int chunkZ;
            try {
                chunkX = Integer.parseInt(fields[0]);
                chunkZ = Integer.parseInt(fields[1]);
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("Invalid oracle case coordinates at line " + (index + 1), failure);
            }
            if (!coordinates.add(chunkX + "," + chunkZ)) {
                throw new IllegalArgumentException("Duplicate oracle case coordinates at line " + (index + 1));
            }
            Path output = Path.of(fields[2]).toAbsolutePath().normalize();
            if (!outputs.add(output)) {
                throw new IllegalArgumentException("Duplicate oracle case output at line " + (index + 1));
            }
            result.add(new CaptureCase(chunkX, chunkZ, output));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Oracle case manifest is empty: " + manifest);
        return List.copyOf(result);
    }

    private static void writeSnapshot(Path output, String identity, CompoundTag serialized,
                                      String seed, String dimension, String endpoint, ChunkAccess chunk) throws IOException {
        List<String> fields = new ArrayList<>();
        fields.add("BLOCK_STATES=" + canonicalPalettedSections(serialized, "block_states"));
        fields.add("BIOMES=" + canonicalPalettedSections(serialized, "biomes"));
        fields.add("HEIGHTMAPS=" + canonical(serialized.get("Heightmaps")));
        fields.add("POSTPROCESSING=" + canonicalPostProcessing(serialized.get("PostProcessing")));
        CompoundTag light = new CompoundTag();
        light.put("block", sectionProjection(serialized, "BlockLight"));
        light.put("sky", sectionProjection(serialized, "SkyLight"));
        if (serialized.contains("isLightOn")) light.put("isLightOn", serialized.get("isLightOn").copy());
        fields.add("LIGHT=" + canonical(light));
        CompoundTag ticks = new CompoundTag();
        if (serialized.contains("block_ticks")) ticks.put("block_ticks", serialized.get("block_ticks").copy());
        if (serialized.contains("fluid_ticks")) ticks.put("fluid_ticks", serialized.get("fluid_ticks").copy());
        fields.add("TICKS=" + canonical(ticks));
        fields.add("BLOCK_ENTITIES=" + canonical(serialized.get("block_entities")));
        fields.add("STRUCTURES=" + canonical(serialized.get("structures")));
        fields.add("ENTITIES=" + canonical(serialized.get("entities")));
        CompoundTag lifecycle = new CompoundTag();
        for (String key : List.of("xPos", "yPos", "zPos", "Status", "isLightOn")) {
            if (serialized.contains(key)) lifecycle.put(key, serialized.get(key).copy());
        }
        lifecycle.putString("minBuildHeight", Integer.toString(chunk.getMinBuildHeight()));
        lifecycle.putString("maxBuildHeight", Integer.toString(chunk.getMaxBuildHeight()));
        fields.add("LIFECYCLE=" + canonical(lifecycle));

        StringBuilder out = new StringBuilder(FORMAT);
        out.append("identity=").append(escape(identity)).append('\n');
        for (String field : FIELD_ORDER) {
            String value = fields.stream().filter(candidate -> candidate.startsWith(field + "=")).findFirst().orElseThrow();
            out.append("field=").append(field).append('=').append(escape(value.substring(field.length() + 1))).append('\n');
        }
        out.append("value=seed=").append(escape(seed)).append('\n');
        out.append("value=dimension=").append(escape(dimension)).append('\n');
        out.append("value=endpoint=").append(escape(endpoint)).append('\n');
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        Files.writeString(output, out.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static CompoundTag sectionProjection(CompoundTag serialized, String key) {
        CompoundTag result = new CompoundTag();
        Tag sectionsTag = serialized.get("sections");
        if (!(sectionsTag instanceof ListTag sections)) return result;
        ListTag selected = new ListTag();
        for (int index = 0; index < sections.size(); index++) {
            Tag raw = sections.get(index);
            if (!(raw instanceof CompoundTag section) || !section.contains(key)) continue;
            CompoundTag item = new CompoundTag();
            if (section.contains("Y")) item.put("Y", section.get("Y").copy());
            item.put(key, section.get(key).copy());
            selected.add(item);
        }
        result.put("sections", selected);
        return result;
    }

    /**
     * Canonicalizes a paletted section by semantic palette values and decoded
     * entries. Serialized palette order is an implementation detail and may
     * vary when the same section is populated by different worker schedules.
     */
    private static String canonicalPalettedSections(CompoundTag serialized, String key) {
        StringBuilder out = new StringBuilder("{\"sections\":[");
        Tag sectionsTag = serialized.get("sections");
        if (!(sectionsTag instanceof ListTag sections)) return out.append("]}").toString();

        boolean firstSection = true;
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            Tag raw = sections.get(sectionIndex);
            if (!(raw instanceof CompoundTag section) || !section.contains(key)) continue;
            if (!firstSection) out.append(',');
            firstSection = false;

            Tag containerTag = section.get(key);
            if (!(containerTag instanceof CompoundTag container)) {
                throw new IllegalStateException("Expected paletted compound for section field " + key);
            }
            List<String> palette = readPalette(container, key);
            int[] decoded = decodePalette(container, palette.size(), key, key.equals("biomes") ? 64 : 4096);
            List<String> canonicalPalette = palette.stream().distinct().sorted().toList();
            Map<String, Integer> canonicalIds = new HashMap<>();
            for (int index = 0; index < canonicalPalette.size(); index++) {
                canonicalIds.put(canonicalPalette.get(index), index);
            }

            out.append('{');
            if (section.contains("Y")) {
                out.append("\"Y\":").append(canonical(section.get("Y"))).append(',');
            }
            out.append("\"palette\":[");
            for (int index = 0; index < canonicalPalette.size(); index++) {
                if (index > 0) out.append(',');
                out.append(quote(canonicalPalette.get(index)));
            }
            out.append("],\"indices\":[");
            for (int index = 0; index < decoded.length; index++) {
                if (index > 0) out.append(',');
                String semanticValue = palette.get(decoded[index]);
                Integer canonicalId = canonicalIds.get(semanticValue);
                if (canonicalId == null) {
                    throw new IllegalStateException("Palette remap lost value for " + key);
                }
                out.append(canonicalId);
            }
            out.append("]}");
        }
        return out.append("]}").toString();
    }

    private static List<String> readPalette(CompoundTag container, String key) {
        Tag paletteTag = container.get("palette");
        if (!(paletteTag instanceof ListTag palette) || palette.isEmpty()) {
            throw new IllegalStateException("Missing or empty palette for section field " + key);
        }
        List<String> values = new ArrayList<>(palette.size());
        for (int index = 0; index < palette.size(); index++) {
            values.add(canonical(palette.get(index)));
        }
        return values;
    }

    private static int[] decodePalette(CompoundTag container, int paletteSize, String key, int entryCount) {
        Tag dataTag = container.get("data");
        int[] decoded = new int[entryCount];
        if (!(dataTag instanceof LongArrayTag data)) {
            if (paletteSize != 1) {
                throw new IllegalStateException("Missing packed data for multi-value palette " + key);
            }
            return decoded;
        }

        long[] words = data.getAsLongArray();
        if (words.length == 0) {
            throw new IllegalStateException("Invalid packed data length for " + key + ": 0");
        }

        // SimpleBitStorage does not pack values across 64-bit words.  It
        // stores floor(64 / bits) entries in each word and leaves the tail
        // bits unused.  Inferring bits as words*64/entryCount therefore
        // misreads every 3-bit palette (the common eight-biome case) as a
        // 4-bit palette and produces impossible indices.
        int minimumBits = key.equals("block_states") ? 4 : 1;
        int paletteBits = ceilLog2(paletteSize);
        int minimumCandidate = Math.max(minimumBits, paletteBits);
        for (int bits = minimumCandidate; bits <= 32; bits++) {
            int valuesPerWord = 64 / bits;
            long expectedWords = (entryCount + (long) valuesPerWord - 1L) / valuesPerWord;
            if (expectedWords != words.length) continue;
            long mask = (1L << bits) - 1L;
            boolean valid = true;
            for (int index = 0; index < decoded.length; index++) {
                int wordIndex = index / valuesPerWord;
                int bitOffset = (index % valuesPerWord) * bits;
                int paletteIndex = (int) ((words[wordIndex] >>> bitOffset) & mask);
                if (paletteIndex >= paletteSize) {
                    valid = false;
                    break;
                }
                decoded[index] = paletteIndex;
            }
            if (valid) return decoded;
        }
        throw new IllegalStateException("Packed data does not match a valid " + key
                + " palette width: words=" + words.length + ", palette=" + paletteSize);
    }

    private static int ceilLog2(int value) {
        if (value <= 0) throw new IllegalStateException("Palette size must be positive");
        return value <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(value - 1);
    }

    private static String canonical(Tag tag) {
        if (tag == null) return "<missing>";
        if (tag instanceof CompoundTag compound) {
            StringBuilder out = new StringBuilder("{");
            compound.getAllKeys().stream().sorted().forEachOrdered(key -> {
                if (out.length() > 1) out.append(',');
                out.append(quote(key)).append(':').append(canonical(compound.get(key)));
            });
            return out.append('}').toString();
        }
        if (tag instanceof ListTag list) {
            StringBuilder out = new StringBuilder("[");
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) out.append(',');
                out.append(canonical(list.get(index)));
            }
            return out.append(']').toString();
        }
        return tag.toString();
    }

    /** Post-processing is a per-section set of packed offsets, not an ordered event log. */
    private static String canonicalPostProcessing(Tag tag) {
        if (!(tag instanceof ListTag sections)) return canonical(tag);
        StringBuilder out = new StringBuilder("[");
        for (int section = 0; section < sections.size(); section++) {
            if (section > 0) out.append(',');
            Tag raw = sections.get(section);
            if (!(raw instanceof ListTag offsets)) throw new IllegalStateException("Invalid PostProcessing section");
            var values = new ArrayList<Integer>(offsets.size());
            for (int index = 0; index < offsets.size(); index++) values.add((int) offsets.getShort(index));
            out.append('[');
            for (int index = 0; index < values.size(); index++) {
                if (index > 0) out.append(',');
                out.append(values.get(index)).append('s');
            }
            out.append(']');
        }
        return out.append(']').toString();
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\="); }

    private static Path requiredPath(String property) {
        String value = System.getProperty(property, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Missing system property " + property);
        return Path.of(value);
    }

    private static String textProperty(String property, String fallback) {
        String value = System.getProperty(property, fallback).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Blank system property " + property);
        return value;
    }

    private static int integerProperty(String property, int fallback) {
        try { return Integer.parseInt(System.getProperty(property, Integer.toString(fallback))); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid integer property " + property, failure); }
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) return level;
        }
        throw new IllegalArgumentException("Oracle dimension is not loaded: " + dimension);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
