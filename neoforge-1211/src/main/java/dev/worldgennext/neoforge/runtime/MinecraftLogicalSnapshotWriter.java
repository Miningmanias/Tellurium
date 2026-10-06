// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import dev.worldgennext.neoforge.version.Version;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes the loader-owned logical endpoint artifact in the same stable,
 * field-by-field format consumed by the independent oracle comparator.
 *
 * <p>This writer intentionally lives in the candidate loader module rather
 * than depending on {@code oracle-1211}.  The oracle process must remain free
 * of candidate classes, while candidate FULL/SAVED verification must still
 * compare the complete logical state rather than a checksum or raw NBT byte
 * stream.</p>
 */
public final class MinecraftLogicalSnapshotWriter {
    private static final String FORMAT = "WORLDGENNEXT-SNAPSHOT-1\n";
    private static final List<String> FIELD_ORDER = List.of(
            "BLOCK_STATES", "BIOMES", "HEIGHTMAPS", "POSTPROCESSING", "LIGHT",
            "TICKS", "BLOCK_ENTITIES", "STRUCTURES", "ENTITIES", "LIFECYCLE");

    private MinecraftLogicalSnapshotWriter() { }

    /**
     * Serializes a chunk after the caller's endpoint barrier.
     * Only the logical fields that the independent oracle defines are
     * included; serialized palette numbering and NBT key order are canonical.
     */
    public static void write(Path output, ServerLevel level, ChunkAccess chunk,
                             String source, String stackFingerprint, String endpoint) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(chunk, "chunk");
        requireText(source, "source");
        requireText(stackFingerprint, "stackFingerprint");
        String normalizedEndpoint = requireText(endpoint, "endpoint").toUpperCase(java.util.Locale.ROOT);
        if (!isLogicalEndpoint(normalizedEndpoint)) {
            throw new IllegalArgumentException("Candidate logical endpoint is unsupported: " + endpoint);
        }

        String seed = Long.toString(level.getServer().getWorldData().worldGenOptions().seed());
        String dimension = level.dimension().location().toString();
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        String context = contextFingerprint(seed, dimension, normalizedEndpoint, chunkX, chunkZ);
        String identity = String.join("/", source, stackFingerprint, seed, dimension,
                Integer.toString(chunkX), Integer.toString(chunkZ), normalizedEndpoint, context);
        CompoundTag serialized = Version.chunkNbt(level, chunk);

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
        lifecycle.putString("minBuildHeight", Integer.toString(Version.minY(chunk)));
        lifecycle.putString("maxBuildHeight", Integer.toString(Version.maxYExclusive(chunk)));
        fields.add("LIFECYCLE=" + canonical(lifecycle));

        StringBuilder encoded = new StringBuilder(FORMAT);
        encoded.append("identity=").append(escape(identity)).append('\n');
        for (String field : FIELD_ORDER) {
            String value = fields.stream()
                    .filter(candidate -> candidate.startsWith(field + "="))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Missing logical field " + field));
            encoded.append("field=").append(field).append('=')
                    .append(escape(value.substring(field.length() + 1))).append('\n');
        }
        encoded.append("value=seed=").append(escape(seed)).append('\n');
        encoded.append("value=dimension=").append(escape(dimension)).append('\n');
        encoded.append("value=endpoint=").append(escape(normalizedEndpoint)).append('\n');

        Path normalized = output.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(normalized, encoded.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    public static String contextFingerprint(String seed, String dimension, String endpoint,
                                             int chunkX, int chunkZ) {
        requireText(seed, "seed");
        requireText(dimension, "dimension");
        requireText(endpoint, "endpoint");
        String input = seed + "|" + dimension + "|" + endpoint + "|" + chunkX + "|" + chunkZ;
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static boolean isLogicalEndpoint(String endpoint) {
        return switch (endpoint) {
            case "NOISE", "SURFACE", "CARVERS", "FEATURES", "INITIALIZE_LIGHT", "LIGHT", "SPAWN", "FULL", "SAVED" -> true;
            default -> false;
        };
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
            int entries = key.equals("biomes") ? 64 : 4096;
            int[] decoded = decodePalette(container, palette.size(), key, entries);
            List<String> canonicalPalette = palette.stream().distinct().sorted().toList();
            Map<String, Integer> canonicalIds = new HashMap<>();
            for (int index = 0; index < canonicalPalette.size(); index++) {
                canonicalIds.put(canonicalPalette.get(index), index);
            }

            out.append('{');
            if (section.contains("Y")) out.append("\"Y\":").append(canonical(section.get("Y"))).append(',');
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
                if (canonicalId == null) throw new IllegalStateException("Palette remap lost value for " + key);
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
        for (int index = 0; index < palette.size(); index++) values.add(canonical(palette.get(index)));
        return values;
    }

    private static int[] decodePalette(CompoundTag container, int paletteSize, String key, int entryCount) {
        Tag dataTag = container.get("data");
        int[] decoded = new int[entryCount];
        if (!(dataTag instanceof LongArrayTag data)) {
            if (paletteSize != 1) throw new IllegalStateException("Missing packed data for multi-value palette " + key);
            return decoded;
        }
        long[] words = data.getAsLongArray();
        if (words.length == 0) throw new IllegalStateException("Invalid packed data length for " + key + ": 0");
        int minimumBits = key.equals("block_states") ? 4 : 1;
        int minimumCandidate = Math.max(minimumBits, ceilLog2(paletteSize));
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

    private static String canonicalPostProcessing(Tag tag) {
        if (!(tag instanceof ListTag sections)) return canonical(tag);
        StringBuilder out = new StringBuilder("[");
        for (int section = 0; section < sections.size(); section++) {
            if (section > 0) out.append(',');
            Tag raw = sections.get(section);
            if (!(raw instanceof ListTag offsets)) throw new IllegalStateException("Invalid PostProcessing section");
            out.append('[');
            for (int index = 0; index < offsets.size(); index++) {
                if (index > 0) out.append(',');
                out.append((int) Version.shortAt(offsets, index)).append('s');
            }
            out.append(']');
        }
        return out.append(']').toString();
    }

    private static String canonical(Tag tag) {
        if (tag == null) return "<missing>";
        if (tag instanceof CompoundTag compound) {
            StringBuilder out = new StringBuilder("{");
            Version.keys(compound).stream().sorted().forEachOrdered(key -> {
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

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\=");
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) throw new IllegalArgumentException(name + " is blank");
        return value;
    }
}
