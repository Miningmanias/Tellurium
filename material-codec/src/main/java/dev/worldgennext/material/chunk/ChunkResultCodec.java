// SPDX-License-Identifier: MIT
package dev.worldgennext.material.chunk;

import dev.worldgennext.material.SectionCodec;
import dev.worldgennext.material.SectionData;
import dev.worldgennext.semantic.snapshot.BlockStateDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned bounded little-endian complete-result codec. */
public final class ChunkResultCodec {
    private static final int MAGIC = 0x57474E43;
    private static final int LEGACY_VERSION = 1;
    private static final int VERSION_2 = 2;
    private static final int VERSION_3 = 3;
    private static final int VERSION = 4;
    private static final int MAX_BYTES = 128 * 1024 * 1024;

    private ChunkResultCodec() {}

    /**
     * Version two carries every classified section, its directory entry and the
     * independently reported state counts. The dense state array is intentionally
     * not the wire representation; it is reconstructed and validated on decode.
     */
    public static byte[] encode(ChunkNoiseResult result) {
        if (result == null) throw new IllegalArgumentException("Missing chunk result");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            putInt(out, MAGIC);
            putInt(out, VERSION);
            putInt(out, result.header().chunkX());
            putInt(out, result.header().chunkZ());
            putInt(out, result.header().minY());
            putInt(out, result.header().height());
            putString(out, result.header().contextIdentity());
            putString(out, result.header().registryFingerprint());
            putString(out, result.header().abiVersion());

            putInt(out, result.stateTable().states().size());
            for (var state : result.stateTable().states()) putString(out, state.canonical());

            putInt(out, result.sections().size());
            for (int index = 0; index < result.sections().size(); index++) {
                SectionDirectory directory = result.sections().get(index);
                byte[] section = SectionCodec.toBytes(result.section(index));
                if (directory.length() != section.length) throw new IllegalArgumentException("Section directory length mismatch");
                putInt(out, directory.sectionY());
                putInt(out, directory.offset());
                putInt(out, directory.length());
                putInt(out, directory.nonAirCount());
                out.write(section);
            }

            putInt(out, result.sectionCounts().values().size());
            for (var entry : result.sectionCounts().values().entrySet()) {
                putString(out, entry.getKey());
                putInt(out, entry.getValue());
            }

            putInt(out, result.heightmaps().maps().size());
            for (var heightmap : result.heightmaps().maps().entrySet()) {
                putString(out, heightmap.getKey());
                putInt(out, HeightmapPayload.COLUMN_COUNT);
                for (int value : heightmap.getValue()) putInt(out, value);
            }
            boolean[] marks = result.postProcessing().fluidMarks();
            putInt(out, marks.length);
            for (boolean mark : marks) out.writeByte(mark ? 1 : 0);

            putInt(out, result.metadata().fields().size());
            for (var entry : result.metadata().fields().entrySet()) {
                putString(out, entry.getKey());
                putString(out, entry.getValue());
            }

            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_BYTES) throw new IllegalArgumentException("Chunk result exceeds codec bound");
            return encoded;
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static ChunkNoiseResult decode(byte[] input) {
        if (input == null || input.length < 32 || input.length > MAX_BYTES) {
            throw new IllegalArgumentException("Invalid result bytes");
        }
        try {
            ByteBuffer in = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
            if (in.getInt() != MAGIC) throw new IllegalArgumentException("Unknown result ABI");
            int version = in.getInt();
            return switch (version) {
                case LEGACY_VERSION -> decodeLegacy(in);
                case VERSION_2 -> decodeCurrent(in, false, false);
                case VERSION_3 -> decodeCurrent(in, true, false);
                case VERSION -> decodeCurrent(in, true, true);
                default -> throw new IllegalArgumentException("Unknown result ABI version: " + version);
            };
        } catch (BufferUnderflowException | IndexOutOfBoundsException failure) {
            throw new IllegalArgumentException("Truncated result bytes", failure);
        }
    }

    private static ChunkNoiseResult decodeLegacy(ByteBuffer in) {
        ChunkResultHeader header = readHeader(in);
        BlockStateTable table = readTable(in, header.registryFingerprint());
        int stateCount = positive(in.getInt(), Math.multiplyExact(header.height(), 256));
        if (stateCount != Math.multiplyExact(header.height(), 256)) throw new IllegalArgumentException("State count does not match header");
        String[] dense = new String[stateCount];
        for (int i = 0; i < stateCount; i++) dense[i] = table.state(in.getInt()).canonical();
        int[] heights = readHeightmaps(in);
        boolean[] marks = readFluidMarks(in, stateCount);
        if (in.hasRemaining()) throw new IllegalArgumentException("Trailing result bytes");
        return ChunkNoiseResult.ofDense(header, table, dense, heights, marks);
    }

    private static ChunkNoiseResult decodeCurrent(ByteBuffer in, boolean namedHeightmaps, boolean metadataPresent) {
        ChunkResultHeader header = readHeader(in);
        BlockStateTable table = readTable(in, header.registryFingerprint());
        int sectionCount = positive(in.getInt(), header.sectionCount());
        if (sectionCount != header.sectionCount()) throw new IllegalArgumentException("Section count does not match header");
        String[] dense = new String[Math.multiplyExact(header.height(), 256)];
        var wireDirectories = new ArrayList<SectionDirectory>(sectionCount);
        int expectedOffset = 0;
        for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
            int sectionY = in.getInt();
            int offset = in.getInt();
            int length = positive(in.getInt(), SectionCodec.MAX_ENCODED_BYTES);
            int nonAir = bounded(in.getInt(), 0, SectionData.BLOCK_COUNT);
            if (offset != expectedOffset) throw new IllegalArgumentException("Section directory offset is not contiguous");
            if (sectionY != Math.floorDiv(header.minY(), 16) + sectionIndex) throw new IllegalArgumentException("Unexpected section Y");
            if (length > in.remaining()) throw new IllegalArgumentException("Truncated section payload");
            byte[] payload = new byte[length];
            in.get(payload);
            SectionData section = SectionCodec.fromBytes(payload);
            if (section.nonAirCount() != nonAir) throw new IllegalArgumentException("Section non-air count mismatch");
            int[] ids = SectionCodec.decode(section);
            int base = Math.multiplyExact(sectionIndex, SectionData.BLOCK_COUNT);
            for (int index = 0; index < ids.length; index++) dense[base + index] = table.state(ids[index]).canonical();
            wireDirectories.add(new SectionDirectory(sectionY, offset, length, nonAir));
            expectedOffset = Math.addExact(expectedOffset, length);
        }

        int countEntries = bounded(in.getInt(), 1, table.states().size());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int index = 0; index < countEntries; index++) {
            String state = getString(in);
            int count = positive(in.getInt(), dense.length);
            if (counts.put(state, count) != null) throw new IllegalArgumentException("Duplicate state count");
        }
        HeightmapPayload heights = namedHeightmaps ? readNamedHeightmaps(in) : new HeightmapPayload(readHeightmaps(in));
        boolean[] marks = readFluidMarks(in, dense.length);
        ChunkMetadataPayload metadata = metadataPresent ? readMetadata(in) : ChunkMetadataPayload.empty();
        if (in.hasRemaining()) throw new IllegalArgumentException("Trailing result bytes");

        ChunkNoiseResult result = ChunkNoiseResult.ofDense(header, table, dense, heights, marks, metadata);
        if (!result.sectionCounts().values().equals(new SectionCounts(counts).values())) {
            throw new IllegalArgumentException("State counts do not match reconstructed sections");
        }
        if (!result.sections().equals(wireDirectories)) throw new IllegalArgumentException("Section directory does not match reconstructed sections");
        return result;
    }

    private static ChunkResultHeader readHeader(ByteBuffer in) {
        return new ChunkResultHeader(in.getInt(), in.getInt(), in.getInt(), in.getInt(),
                getString(in), getString(in), getString(in));
    }

    private static BlockStateTable readTable(ByteBuffer in, String fingerprint) {
        int tableSize = positive(in.getInt(), 1_000_000);
        var states = new ArrayList<BlockStateDescriptor>(tableSize);
        for (int i = 0; i < tableSize; i++) states.add(parseState(getString(in)));
        return BlockStateTable.fromEncoded(states, fingerprint);
    }

    private static int[] readHeightmaps(ByteBuffer in) {
        if (in.getInt() != HeightmapPayload.COLUMN_COUNT) throw new IllegalArgumentException("Missing heightmap");
        int[] heights = new int[HeightmapPayload.COLUMN_COUNT];
        for (int i = 0; i < heights.length; i++) heights[i] = in.getInt();
        return heights;
    }

    private static HeightmapPayload readNamedHeightmaps(ByteBuffer in) {
        int mapCount = bounded(in.getInt(), 1, 128);
        Map<String, int[]> maps = new LinkedHashMap<>();
        for (int mapIndex = 0; mapIndex < mapCount; mapIndex++) {
            String kind = getString(in);
            if (kind.isBlank() || maps.containsKey(kind)) throw new IllegalArgumentException("Duplicate or blank heightmap kind");
            if (in.getInt() != HeightmapPayload.COLUMN_COUNT) throw new IllegalArgumentException("Invalid heightmap column count");
            int[] heights = new int[HeightmapPayload.COLUMN_COUNT];
            for (int column = 0; column < heights.length; column++) heights[column] = in.getInt();
            maps.put(kind, heights);
        }
        return new HeightmapPayload(maps);
    }

    private static boolean[] readFluidMarks(ByteBuffer in, int expected) {
        int marksCount = positive(in.getInt(), expected);
        if (marksCount != expected) throw new IllegalArgumentException("Invalid postprocessing count");
        boolean[] marks = new boolean[expected];
        for (int i = 0; i < expected; i++) {
            int mark = Byte.toUnsignedInt(in.get());
            if (mark > 1) throw new IllegalArgumentException("Invalid fluid mark");
            marks[i] = mark == 1;
        }
        return marks;
    }

    private static ChunkMetadataPayload readMetadata(ByteBuffer in) {
        int count = bounded(in.getInt(), 0, 128);
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String name = getString(in);
            if (name.isBlank() || fields.containsKey(name)) throw new IllegalArgumentException("Duplicate or blank metadata field");
            fields.put(name, getString(in));
        }
        return new ChunkMetadataPayload(fields);
    }

    private static int positive(int value, int max) {
        if (value <= 0 || value > max) throw new IllegalArgumentException("Invalid bounded count: " + value);
        return value;
    }

    private static int bounded(int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException("Invalid bounded value: " + value);
        return value;
    }

    private static void putInt(DataOutputStream out, int value) throws IOException {
        out.writeInt(Integer.reverseBytes(value));
    }

    private static void putString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1_000_000) throw new IllegalArgumentException("String too large");
        putInt(out, bytes.length);
        out.write(bytes);
    }

    private static String getString(ByteBuffer in) {
        int length = bounded(in.getInt(), 0, 1_000_000);
        if (length > in.remaining()) throw new IllegalArgumentException("Truncated string");
        String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(in.slice(in.position(), length)).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Invalid UTF-8 string in result", failure);
        }
        in.position(in.position() + length);
        return value;
    }

    private static BlockStateDescriptor parseState(String value) {
        int bracket = value.indexOf('[');
        if (bracket < 0) return BlockStateDescriptor.of(value);
        if (!value.endsWith("]")) throw new IllegalArgumentException("Malformed state descriptor");
        var properties = new LinkedHashMap<String, String>();
        String body = value.substring(bracket + 1, value.length() - 1);
        for (String part : body.split(",", -1)) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2) throw new IllegalArgumentException("Malformed state property");
            if (properties.put(pair[0], pair[1]) != null) {
                throw new IllegalArgumentException("Duplicate state property");
            }
        }
        return new BlockStateDescriptor(value.substring(0, bracket), properties);
    }
}
