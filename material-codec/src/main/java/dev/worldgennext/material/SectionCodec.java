// SPDX-License-Identifier: MIT
package dev.worldgennext.material;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Versioned bounded section codec. Version one is the historical fixture format
 * whose air ID is zero; version two carries an explicit registry-local air ID.
 */
public final class SectionCodec {
    private static final int MAGIC = 0x57474e31;
    private static final int VERSION = 1;
    private static final int EXPLICIT_AIR_VERSION = 2;
    public static final int HEADER_BYTES = 28;
    private static final int EXPLICIT_AIR_HEADER_BYTES = 32;
    public static final int MAX_ENCODED_BYTES = EXPLICIT_AIR_HEADER_BYTES + SectionData.BLOCK_COUNT * Integer.BYTES;

    private SectionCodec() {}

    public static SectionData encode(int[] states) {
        return encode(states, 0);
    }

    /** Classifies the complete section after final material state assignment. */
    public static SectionData encode(int[] states, int airStateId) {
        DenseSection dense = new DenseSection(states, airStateId);
        int first = dense.blockStateId(0);
        boolean uniform = true;
        for (int i = 1; i < SectionData.BLOCK_COUNT; i++) {
            if (dense.blockStateId(i) != first) {
                uniform = false;
                break;
            }
        }
        if (uniform) return new UniformSection(first, airStateId);
        PaletteSection palette = new PaletteSection(decode(dense), airStateId);
        return 4L + (long) palette.palette().length * 4 + (long) palette.packedWords().length * 8
                < (long) SectionData.BLOCK_COUNT * 4 ? palette : dense;
    }

    /** Snapshot every logical state once, then validate reported metadata before publishing it. */
    public static int[] decode(SectionData section) {
        if (section == null) throw new IllegalArgumentException("Missing section");
        int airStateId = section.airStateId();
        if (airStateId < 0) throw new IllegalArgumentException("Negative air state ID");
        int[] result = new int[SectionData.BLOCK_COUNT];
        int nonAir = 0;
        for (int i = 0; i < result.length; i++) {
            result[i] = section.blockStateId(i);
            if (result[i] < 0) throw new IllegalArgumentException("Negative fixture state ID");
            if (result[i] != airStateId) nonAir++;
        }
        if (section.nonAirCount() != nonAir
                || section.logicalChecksum() != LogicalChecksum.of(result)) {
            throw new IllegalArgumentException("Section metadata/checksum mismatch");
        }
        return result;
    }

    public static byte[] toBytes(SectionData input) {
        if (input == null) throw new IllegalArgumentException("Missing section");
        SectionData section = encode(decode(input), input.airStateId());
        int type = typeOf(section);
        int payload = payloadBytes(section, type);
        boolean explicitAir = section.airStateId() != 0;
        int header = explicitAir ? EXPLICIT_AIR_HEADER_BYTES : HEADER_BYTES;
        ByteBuffer out = ByteBuffer.allocate(Math.addExact(header, payload)).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(MAGIC);
        out.putInt(explicitAir ? EXPLICIT_AIR_VERSION : VERSION);
        if (explicitAir) out.putInt(section.airStateId());
        out.putInt(type).putInt(payload).putInt(section.nonAirCount()).putLong(section.logicalChecksum());
        writePayload(out, section, type);
        return out.array();
    }

    public static SectionData fromBytes(byte[] input) {
        if (input == null || input.length < HEADER_BYTES || input.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("Invalid section byte length");
        }
        ByteBuffer in = ByteBuffer.wrap(input.clone()).order(ByteOrder.LITTLE_ENDIAN);
        if (in.getInt() != MAGIC) throw new IllegalArgumentException("Unknown section format");
        int version = in.getInt();
        int airStateId;
        int type;
        int payload;
        int nonAir;
        long checksum;
        if (version == VERSION) {
            airStateId = 0;
            type = in.getInt();
            payload = in.getInt();
            nonAir = in.getInt();
            checksum = in.getLong();
        } else if (version == EXPLICIT_AIR_VERSION) {
            if (input.length < EXPLICIT_AIR_HEADER_BYTES) throw new IllegalArgumentException("Truncated explicit-air section");
            airStateId = in.getInt();
            if (airStateId < 0) throw new IllegalArgumentException("Negative air state ID");
            type = in.getInt();
            payload = in.getInt();
            nonAir = in.getInt();
            checksum = in.getLong();
        } else {
            throw new IllegalArgumentException("Unknown section format");
        }
        if (payload < 0 || payload != in.remaining() || nonAir < 0 || nonAir > SectionData.BLOCK_COUNT) {
            throw new IllegalArgumentException("Invalid header counts");
        }
        int[] states = new int[SectionData.BLOCK_COUNT];
        if (type == 1 && payload == 4) {
            Arrays.fill(states, in.getInt());
        } else if (type == 2 && payload == states.length * 4) {
            for (int i = 0; i < states.length; i++) states[i] = in.getInt();
        } else if (type == 3 && payload >= 4) {
            readPalette(in, states);
        } else {
            throw new IllegalArgumentException("Invalid encoding type or payload length");
        }
        SectionData section = encode(states, airStateId);
        if (section.nonAirCount() != nonAir || section.logicalChecksum() != checksum) {
            throw new IllegalArgumentException("Section metadata/checksum mismatch");
        }
        return section;
    }

    private static int typeOf(SectionData section) {
        if (section instanceof UniformSection) return 1;
        if (section instanceof PaletteSection) return 3;
        return 2;
    }

    private static int payloadBytes(SectionData section, int type) {
        if (type == 1) return 4;
        if (type == 2) return Math.multiplyExact(SectionData.BLOCK_COUNT, Integer.BYTES);
        PaletteSection palette = (PaletteSection) section;
        return Math.addExact(Math.addExact(4, Math.multiplyExact(palette.palette().length, 4)),
                Math.multiplyExact(palette.packedWords().length, Long.BYTES));
    }

    private static void writePayload(ByteBuffer out, SectionData section, int type) {
        if (type == 1) {
            out.putInt(((UniformSection) section).stateId());
        } else if (type == 2) {
            for (int state : decode(section)) out.putInt(state);
        } else {
            PaletteSection palette = (PaletteSection) section;
            int[] values = palette.palette();
            out.putInt(values.length);
            for (int state : values) out.putInt(state);
            for (long word : palette.packedWords()) out.putLong(word);
        }
    }

    private static void readPalette(ByteBuffer in, int[] states) {
        int size = in.getInt();
        int bits = PaletteSection.bitsFor(size);
        int perWord = 64 / bits;
        int expected = Math.addExact(Math.multiplyExact(size, 4), Math.multiplyExact(PaletteSection.wordCount(size), 8));
        if (in.remaining() != expected) throw new IllegalArgumentException("Invalid palette payload size");
        int[] palette = new int[size];
        for (int i = 0; i < size; i++) {
            palette[i] = in.getInt();
            if (palette[i] < 0 || (i > 0 && palette[i] <= palette[i - 1])) {
                throw new IllegalArgumentException("Palette must be strictly increasing");
            }
        }
        boolean[] used = new boolean[size];
        int index = 0;
        while (in.hasRemaining()) {
            long word = in.getLong();
            int entries = Math.min(perWord, states.length - index);
            int usedBits = entries * bits;
            if (usedBits < 64 && (word >>> usedBits) != 0) {
                throw new IllegalArgumentException("Nonzero packed padding");
            }
            for (int i = 0; i < entries; i++) {
                int paletteIndex = (int) ((word >>> (i * bits)) & ((1L << bits) - 1));
                if (paletteIndex >= size) throw new IllegalArgumentException("Palette index outside palette");
                used[paletteIndex] = true;
                states[index++] = palette[paletteIndex];
            }
        }
        if (index != states.length) throw new IllegalArgumentException("Palette did not fill section");
        for (boolean present : used) if (!present) throw new IllegalArgumentException("Unused palette entry");
    }
}
