// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle.minecraft;

import dev.worldgennext.material.SectionCodec;
import dev.worldgennext.material.chunk.ChunkNoiseResult;
import dev.worldgennext.material.chunk.HeightmapPayload;
import dev.worldgennext.oracle.schema.CaptureIdentity;
import dev.worldgennext.oracle.schema.ChunkSnapshot;
import dev.worldgennext.oracle.schema.SnapshotField;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Adapts the candidate result ABI to the oracle's logical fields.
 *
 * <p>The legacy {@link #blockStates} method is intentionally a field-limited
 * bridge.  {@link #candidate} exposes the additional fields that the current
 * result actually carries, while leaving every other oracle field absent.  A
 * caller that asks the comparator for all oracle fields therefore fails closed
 * instead of accidentally turning a block-only result into a complete chunk
 * claim.</p>
 */
public final class ChunkResultSnapshot {
    private ChunkResultSnapshot() {}

    public record BlockStateComparison(int expectedBlocks, int actualBlocks, int mismatches,
                                       String firstMismatch) {
        public boolean passed() { return expectedBlocks == actualBlocks && mismatches == 0; }
    }

    public static ChunkSnapshot blockStates(ChunkNoiseResult result, CaptureIdentity identity,
                                            Map<String, String> values) {
        validateIdentity(result, identity);
        return new ChunkSnapshot(identity,
                Map.of(SnapshotField.BLOCK_STATES, canonicalBlockStates(result)), values);
    }

    /**
     * Builds the strongest snapshot currently representable by a candidate
     * result. Block states, named heightmaps, post-processing marks and any
     * loader-captured canonical metadata are represented.  Fields not carried
     * by the result remain absent so the comparator continues to fail closed.
     */
    public static ChunkSnapshot candidate(ChunkNoiseResult result, CaptureIdentity identity,
                                          Map<String, String> values) {
        validateIdentity(result, identity);
        var fields = new java.util.EnumMap<SnapshotField, String>(SnapshotField.class);
        fields.put(SnapshotField.BLOCK_STATES, canonicalBlockStates(result));
        fields.put(SnapshotField.HEIGHTMAPS, canonicalHeightmaps(result));
        fields.put(SnapshotField.POSTPROCESSING, canonicalPostProcessing(result));
        result.metadata().fields().forEach((name, value) -> {
            try {
                SnapshotField field = SnapshotField.valueOf(name);
                fields.putIfAbsent(field, value);
            } catch (IllegalArgumentException ignored) {
                // The material ABI can carry fields introduced by a newer
                // loader; this older oracle view cannot compare them yet.
            }
        });
        return new ChunkSnapshot(identity, fields, values);
    }

    /**
     * Canonicalizes the boolean post-processing ABI to Minecraft's packed
     * section offsets.  The order mirrors NoiseBasedChunkGenerator's pinned
     * fill traversal (Y descending, then local X and Z), so this lossy boolean
     * transport can still reproduce the serialized endpoint exactly.
     */
    public static String canonicalPostProcessing(ChunkNoiseResult result) {
        Objects.requireNonNull(result, "result");
        boolean[] marks = result.postProcessing().fluidMarks();
        StringBuilder out = new StringBuilder("[");
        for (int section = 0; section < result.header().sectionCount(); section++) {
            if (section > 0) out.append(',');
            var offsets = new ArrayList<Integer>();
            int sectionBase = section * 16 * 256;
            // Vanilla's fillFromNoise traverses the four-by-four cell grid
            // before descending through Y.  Preserve that order in this
            // compact boolean-to-offset view.
            for (int cellX = 0; cellX < 16; cellX += 4) {
                for (int cellZ = 0; cellZ < 16; cellZ += 4) {
                    for (int localY = 15; localY >= 0; localY--) {
                        int yBase = sectionBase + localY * 256;
                        for (int x = cellX; x < cellX + 4; x++) {
                            for (int z = cellZ; z < cellZ + 4; z++) {
                                if (marks[yBase + z * 16 + x]) offsets.add(x | (localY << 4) | (z << 8));
                            }
                        }
                    }
                }
            }
            out.append('[');
            for (int index = 0; index < offsets.size(); index++) {
                if (index > 0) out.append(',');
                out.append(offsets.get(index)).append('s');
            }
            out.append(']');
        }
        return out.append(']').toString();
    }

    /**
     * Encodes logical heights with Minecraft's SimpleBitStorage convention.
     * Heightmap values are first-available Y coordinates; the stored values are
     * relative to the result's minimum Y and packed least-significant bits
     * first into each long.
     */
    public static String canonicalHeightmaps(ChunkNoiseResult result) {
        Objects.requireNonNull(result, "result");
        int height = result.header().height();
        long heightPlusOne = (long) height + 1L;
        int bits = 64 - Long.numberOfLeadingZeros(heightPlusOne);
        if (bits <= 0 || bits > 32) throw new IllegalArgumentException("Unsupported heightmap width: " + bits);
        int valuesPerLong = 64 / bits;
        int wordCount = (HeightmapPayload.COLUMN_COUNT + valuesPerLong - 1) / valuesPerLong;
        long mask = (1L << bits) - 1L;
        StringBuilder out = new StringBuilder("{");
        boolean firstMap = true;
        for (var entry : result.heightmaps().maps().entrySet()) {
            if (!firstMap) out.append(',');
            firstMap = false;
            out.append(quote(entry.getKey())).append(':').append("[L;");
            long[] words = new long[wordCount];
            int[] columns = entry.getValue();
            for (int column = 0; column < columns.length; column++) {
                long relative = (long) columns[column] - result.header().minY();
                if (relative < 0 || relative > height) {
                    throw new IllegalArgumentException("Heightmap value outside result geometry: "
                            + entry.getKey() + "[" + column + "]=" + columns[column]);
                }
                int word = column / valuesPerLong;
                int shift = (column % valuesPerLong) * bits;
                words[word] |= (relative & mask) << shift;
            }
            for (int word = 0; word < words.length; word++) {
                if (word > 0) out.append(',');
                out.append(words[word]).append('L');
            }
            out.append(']');
        }
        return out.append('}').toString();
    }

    private static void validateIdentity(ChunkNoiseResult result, CaptureIdentity identity) {
        if (result == null) throw new IllegalArgumentException("Missing candidate result");
        if (identity == null) throw new IllegalArgumentException("Missing candidate identity");
        if (result.header().chunkX() != identity.chunkX() || result.header().chunkZ() != identity.chunkZ()) {
            throw new IllegalArgumentException("Candidate result coordinates do not match capture identity");
        }
    }

    /** Canonical form shared with the independent oracle's block-state field. */
    public static String canonicalBlockStates(ChunkNoiseResult result) {
        Objects.requireNonNull(result, "result");
        StringBuilder out = new StringBuilder("{\"sections\":[");
        for (int sectionIndex = 0; sectionIndex < result.header().sectionCount(); sectionIndex++) {
            if (sectionIndex > 0) out.append(',');
            int sectionY = Math.floorDiv(result.header().minY(), 16) + sectionIndex;
            int[] ids = SectionCodec.decode(result.section(sectionIndex));
            List<String> palette = new ArrayList<>();
            for (int id : ids) {
                String state = stateNbt(result.stateTable().state(id));
                if (!palette.contains(state)) palette.add(state);
            }
            palette.sort(String::compareTo);
            Map<String, Integer> canonicalIds = new HashMap<>();
            for (int index = 0; index < palette.size(); index++) canonicalIds.put(palette.get(index), index);

            out.append("{\"Y\":").append(sectionY).append('b').append(",\"palette\":[");
            for (int index = 0; index < palette.size(); index++) {
                if (index > 0) out.append(',');
                out.append(quote(palette.get(index)));
            }
            out.append("],\"indices\":[");
            for (int index = 0; index < ids.length; index++) {
                if (index > 0) out.append(',');
                String state = stateNbt(result.stateTable().state(ids[index]));
                Integer canonicalId = canonicalIds.get(state);
                if (canonicalId == null) throw new IllegalStateException("Candidate palette remap lost state");
                out.append(canonicalId);
            }
            out.append("]}");
        }
        return out.append("]}").toString();
    }

    /**
     * Compares the canonical section representation and reports block-level
     * evidence without putting the multi-megabyte field in the operator log.
     */
    public static BlockStateComparison compareBlockStates(String expected, String actual) {
        List<Section> left = parseSections(expected);
        List<Section> right = parseSections(actual);
        int expectedBlocks = left.stream().mapToInt(section -> section.indices().size()).sum();
        int actualBlocks = right.stream().mapToInt(section -> section.indices().size()).sum();
        int mismatches = 0;
        String first = null;
        int sections = Math.max(left.size(), right.size());
        for (int sectionIndex = 0; sectionIndex < sections; sectionIndex++) {
            Section a = sectionIndex < left.size() ? left.get(sectionIndex) : null;
            Section b = sectionIndex < right.size() ? right.get(sectionIndex) : null;
            int count = Math.max(a == null ? 0 : a.indices().size(), b == null ? 0 : b.indices().size());
            for (int index = 0; index < count; index++) {
                String av = stateAt(a, index), bv = stateAt(b, index);
                if (!java.util.Objects.equals(av, bv)) {
                    mismatches++;
                    if (first == null) {
                        int sectionY = a != null ? a.y() : b.y();
                        int x = index & 15;
                        int z = (index >>> 4) & 15;
                        int y = sectionY * 16 + (index >>> 8);
                        first = "sectionY=" + sectionY + " index=" + index + " block=" + x + "," + y + "," + z
                                + " expected=" + av + " actual=" + bv;
                    }
                }
            }
        }
        return new BlockStateComparison(expectedBlocks, actualBlocks, mismatches,
                first == null ? "none" : first);
    }

    /**
     * Returns the semantic state substitutions in a comparison.  This is
     * intentionally derived from the canonical fields instead of process-local
     * palette ids, making a failed candidate replay actionable without dumping
     * the entire 98k-block field into an operator log.
     */
    public static Map<String, Integer> mismatchPairs(String expected, String actual) {
        List<Section> left = parseSections(expected);
        List<Section> right = parseSections(actual);
        Map<String, Integer> pairs = new java.util.TreeMap<>();
        int sections = Math.max(left.size(), right.size());
        for (int sectionIndex = 0; sectionIndex < sections; sectionIndex++) {
            Section a = sectionIndex < left.size() ? left.get(sectionIndex) : null;
            Section b = sectionIndex < right.size() ? right.get(sectionIndex) : null;
            int count = Math.max(a == null ? 0 : a.indices().size(), b == null ? 0 : b.indices().size());
            for (int index = 0; index < count; index++) {
                String expectedState = stateAt(a, index), actualState = stateAt(b, index);
                if (!java.util.Objects.equals(expectedState, actualState)) {
                    String key = String.valueOf(expectedState) + " -> " + String.valueOf(actualState);
                    pairs.merge(key, 1, Integer::sum);
                }
            }
        }
        return java.util.Collections.unmodifiableMap(pairs);
    }

    private static String stateAt(Section section, int index) {
        if (section == null || index >= section.indices().size()) return null;
        int paletteIndex = section.indices().get(index);
        return paletteIndex >= 0 && paletteIndex < section.palette().size() ? section.palette().get(paletteIndex) : "<invalid-palette-index>";
    }

    private record Section(int y, List<String> palette, List<Integer> indices) {}

    private static List<Section> parseSections(String encoded) {
        Object root = new Json(encoded).value();
        if (!(root instanceof Map<?, ?> object) || !(object.get("sections") instanceof List<?> values)) {
            throw new IllegalArgumentException("Canonical block-state field is missing sections");
        }
        var result = new ArrayList<Section>(values.size());
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> section)) throw new IllegalArgumentException("Canonical section is not an object");
            Object y = section.get("Y");
            Object paletteValue = section.get("palette");
            Object indicesValue = section.get("indices");
            if (!(y instanceof String yText) || !(paletteValue instanceof List<?> paletteValues)
                    || !(indicesValue instanceof List<?> indexValues)) {
                throw new IllegalArgumentException("Canonical section is incomplete");
            }
            var palette = new ArrayList<String>(paletteValues.size());
            for (Object paletteEntry : paletteValues) {
                if (!(paletteEntry instanceof String text)) throw new IllegalArgumentException("Canonical palette entry is not a string");
                palette.add(text);
            }
            var indices = new ArrayList<Integer>(indexValues.size());
            for (Object index : indexValues) {
                if (!(index instanceof String text)) throw new IllegalArgumentException("Canonical index is not numeric");
                indices.add(parseInteger(text));
            }
            result.add(new Section(parseInteger(yText), List.copyOf(palette), List.copyOf(indices)));
        }
        return List.copyOf(result);
    }

    private static int parseInteger(String text) {
        String value = text.endsWith("b") || text.endsWith("B") ? text.substring(0, text.length() - 1) : text;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("Canonical integer is invalid: " + text, failure); }
    }

    /** Tiny JSON reader for the fixed canonical oracle field; it has no external parser dependency. */
    private static final class Json {
        private final String input;
        private int cursor;
        private Json(String input) { this.input = java.util.Objects.requireNonNull(input, "input"); }
        private Object value() {
            skipWhitespace();
            Object result = switch (peek()) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                default -> scalar();
            };
            skipWhitespace();
            if (cursor != input.length()) throw error("Trailing JSON");
            return result;
        }
        private Map<String, Object> object() {
            expect('{');
            var result = new java.util.LinkedHashMap<String, Object>();
            skipWhitespace();
            if (consume('}')) return result;
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace(); expect(':');
                result.put(key, nested());
                skipWhitespace();
                if (consume('}')) return result;
                expect(',');
            }
        }
        private List<Object> array() {
            expect('[');
            var result = new ArrayList<Object>();
            skipWhitespace();
            if (consume(']')) return result;
            while (true) {
                result.add(nested());
                skipWhitespace();
                if (consume(']')) return result;
                expect(',');
            }
        }
        private Object nested() {
            skipWhitespace();
            return switch (peek()) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                default -> scalar();
            };
        }
        private String string() {
            expect('"');
            var result = new StringBuilder();
            while (cursor < input.length()) {
                char c = input.charAt(cursor++);
                if (c == '"') return result.toString();
                if (c != '\\') { result.append(c); continue; }
                if (cursor >= input.length()) throw error("Dangling JSON escape");
                char escaped = input.charAt(cursor++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (cursor + 4 > input.length()) throw error("Truncated unicode escape");
                        try { result.append((char) Integer.parseInt(input.substring(cursor, cursor + 4), 16)); }
                        catch (NumberFormatException failure) { throw error("Invalid unicode escape"); }
                        cursor += 4;
                    }
                    default -> throw error("Unknown JSON escape");
                }
            }
            throw error("Unterminated JSON string");
        }
        private String scalar() {
            int start = cursor;
            while (cursor < input.length()) {
                char c = input.charAt(cursor);
                if (c == ',' || c == ']' || c == '}' || Character.isWhitespace(c)) break;
                cursor++;
            }
            if (start == cursor) throw error("Missing JSON value");
            return input.substring(start, cursor);
        }
        private char peek() { if (cursor >= input.length()) throw error("Unexpected end of JSON"); return input.charAt(cursor); }
        private void expect(char expected) { if (peek() != expected) throw error("Expected " + expected); cursor++; }
        private boolean consume(char expected) { if (cursor < input.length() && input.charAt(cursor) == expected) { cursor++; return true; } return false; }
        private void skipWhitespace() { while (cursor < input.length() && Character.isWhitespace(input.charAt(cursor))) cursor++; }
        private IllegalArgumentException error(String message) { return new IllegalArgumentException(message + " at offset " + cursor); }
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }

    private static String stateNbt(dev.worldgennext.semantic.snapshot.BlockStateDescriptor state) {
        StringBuilder out = new StringBuilder("{\"Name\":").append(quote(state.name()));
        if (!state.properties().isEmpty()) {
            out.append(",\"Properties\":{");
            boolean first = true;
            for (var entry : state.properties().entrySet()) {
                if (!first) out.append(',');
                out.append(quote(entry.getKey())).append(':').append(quote(entry.getValue()));
                first = false;
            }
            out.append('}');
        }
        return out.append('}').toString();
    }
}
