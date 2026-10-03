// SPDX-License-Identifier: MIT
package dev.worldgennext.material;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionCodecTest {
    private static int[] pattern(int count) {
        int[] states = new int[4096];
        for (int i = 0; i < states.length; i++) states[i] = i % count;
        return states;
    }
    private static ByteBuffer buffer(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }

    @Test void selectsUniformPaletteAndDenseFromCompleteMaterialStates() {
        assertInstanceOf(UniformSection.class, SectionCodec.encode(new int[4096]));
        assertInstanceOf(PaletteSection.class, SectionCodec.encode(pattern(3)));
        assertInstanceOf(DenseSection.class, SectionCodec.encode(pattern(4096)));
    }
    @Test void representationIndependentChecksumsAndCanonicalBytes() {
        int[] states = new int[4096]; Arrays.fill(states, Integer.MAX_VALUE);
        SectionData[] variants = {new UniformSection(Integer.MAX_VALUE), new DenseSection(states), new PaletteSection(states)};
        for (SectionData variant : variants) {
            assertEquals(variants[0].logicalChecksum(), variant.logicalChecksum());
            assertEquals(4096, variant.nonAirCount());
            assertArrayEquals(states, SectionCodec.decode(variant));
            assertArrayEquals(SectionCodec.toBytes(variants[0]), SectionCodec.toBytes(variant));
        }
    }
    @Test void zeroChecksumHasIndependentKnownAnswer() {
        // Independently generated FNV-1a over 16,384 zero bytes.
        assertEquals(0x9c1bda7f8c872325L, new UniformSection(0).logicalChecksum());
    }
    @Test void seededRoundTripsCoverPackingWidthsAndArbitraryIds() {
        Random random = new Random(0x71e5L);
        for (int unique : new int[]{1, 2, 3, 4, 5, 17, 32, 65, 257, 1025, 4096}) {
            for (int trial = 0; trial < 5; trial++) {
                int[] states = new int[4096];
                for (int i = 0; i < states.length; i++) states[i] = random.nextInt(unique) * 524_287;
                SectionData encoded = SectionCodec.encode(states);
                byte[] bytes = SectionCodec.toBytes(encoded);
                assertTrue(bytes.length <= SectionCodec.MAX_ENCODED_BYTES);
                SectionData decoded = SectionCodec.fromBytes(bytes);
                assertArrayEquals(states, SectionCodec.decode(decoded));
                assertEquals(Arrays.stream(states).filter(id -> id != 0).count(), decoded.nonAirCount());
                assertArrayEquals(bytes, SectionCodec.toBytes(decoded));
            }
        }
    }
    @Test void callerArraysAndAccessorsCannotMutateSections() {
        int[] states = pattern(3);
        DenseSection dense = new DenseSection(states); PaletteSection palette = new PaletteSection(states);
        int[] expected = states.clone(); Arrays.fill(states, 42);
        Arrays.fill(palette.palette(), 999); Arrays.fill(palette.packedWords(), -1);
        Arrays.fill(SectionCodec.decode(dense), 123);
        assertArrayEquals(expected, SectionCodec.decode(dense));
        assertArrayEquals(expected, SectionCodec.decode(palette));
        byte[] bytes = SectionCodec.toBytes(palette);
        SectionData restored = SectionCodec.fromBytes(bytes); Arrays.fill(bytes, (byte) 0);
        assertArrayEquals(expected, SectionCodec.decode(restored));
    }
    @Test void validatesFixtureIdsLengthsAndIndices() {
        assertThrows(IllegalArgumentException.class, () -> SectionCodec.encode(null));
        assertThrows(IllegalArgumentException.class, () -> SectionCodec.encode(new int[4095]));
        int[] negative = new int[4096]; negative[2048] = -1;
        assertThrows(IllegalArgumentException.class, () -> SectionCodec.encode(negative));
        assertThrows(IllegalArgumentException.class, () -> new UniformSection(-1));
        for (SectionData section : new SectionData[]{new UniformSection(1), new DenseSection(pattern(4096)), new PaletteSection(pattern(3))}) {
            assertThrows(IndexOutOfBoundsException.class, () -> section.blockStateId(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> section.blockStateId(4096));
        }
    }

    @Test void explicitAirIdentityChangesNonAirAccountingWithoutChangingLogicalValues() {
        int[] states = new int[4096]; Arrays.fill(states, 7); states[0] = 2;
        SectionData section = SectionCodec.encode(states, 7);
        assertEquals(7, section.airStateId());
        assertEquals(1, section.nonAirCount());
        assertArrayEquals(states, SectionCodec.decode(section));
        assertArrayEquals(states, SectionCodec.decode(SectionCodec.fromBytes(SectionCodec.toBytes(section))));
    }
    @Test void rejectsTruncationTrailingDataAndUnboundedHeaders() {
        byte[] valid = SectionCodec.toBytes(SectionCodec.encode(pattern(3)));
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            assertThrows(IllegalArgumentException.class, () -> SectionCodec.fromBytes(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> SectionCodec.fromBytes(null));
        assertThrows(IllegalArgumentException.class, () -> SectionCodec.fromBytes(Arrays.copyOf(valid, valid.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> SectionCodec.fromBytes(new byte[SectionCodec.MAX_ENCODED_BYTES + 1]));
        for (int offset : new int[]{0, 4, 8, 12, 16, 28}) {
            byte[] bad = valid.clone(); buffer(bad).putInt(offset, Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class, () -> SectionCodec.fromBytes(bad), "offset " + offset);
        }
    }
    @Test void rejectsChecksumAndNonAirMismatch() {
        byte[] valid = SectionCodec.toBytes(new UniformSection(7));
        byte[] checksum = valid.clone(); checksum[20] ^= 1;
        byte[] count = valid.clone(); buffer(count).putInt(16, 0);
        assertThrows(IllegalArgumentException.class, () -> SectionCodec.fromBytes(checksum));
        assertThrows(IllegalArgumentException.class, () -> SectionCodec.fromBytes(count));
    }
    @Test void rejectsPaletteOrderAndOutOfRangeIndices() {
        byte[] valid = SectionCodec.toBytes(SectionCodec.encode(pattern(3)));
        byte[] duplicate = valid.clone(); buffer(duplicate).putInt(36, 0);
        byte[] badIndex = valid.clone(); badIndex[44] |= 3;
        byte[] negative = valid.clone(); buffer(negative).putInt(32, -1);
        assertThrows(IllegalArgumentException.class, () -> SectionCodec.fromBytes(duplicate));
        assertThrows(IllegalArgumentException.class, () -> SectionCodec.fromBytes(badIndex));
        assertThrows(IllegalArgumentException.class, () -> SectionCodec.fromBytes(negative));
    }
    @Test void rejectsUnusedPaletteEntriesAndNonzeroPackingPadding() {
        byte[] valid = SectionCodec.toBytes(SectionCodec.encode(pattern(5)));
        byte[] padding = valid.clone(); int wordsOffset = 28 + 4 + 5 * 4;
        buffer(padding).putLong(wordsOffset, buffer(padding).getLong(wordsOffset) | Long.MIN_VALUE);
        assertThrows(IllegalArgumentException.class, () -> SectionCodec.fromBytes(padding));
        byte[] unused = valid.clone(); Arrays.fill(unused, wordsOffset, unused.length, (byte) 0);
        assertThrows(IllegalArgumentException.class, () -> SectionCodec.fromBytes(unused));
        byte[] lastPadding = valid.clone(); lastPadding[lastPadding.length - 1] |= (byte) 128;
        assertThrows(IllegalArgumentException.class, () -> SectionCodec.fromBytes(lastPadding));
    }
    @Test void validatesExternalSectionMetadataBeforePublishingSnapshot() {
        SectionData liar = new SectionData() {
            public int blockStateId(int index) { return 0; }
            public int nonAirCount() { return 4096; }
            public long logicalChecksum() { return new UniformSection(0).logicalChecksum(); }
        };
        assertThrows(IllegalArgumentException.class, () -> SectionCodec.decode(liar));
        assertThrows(IllegalArgumentException.class, () -> SectionCodec.toBytes(liar));
    }
    @Test void singleBitPayloadCorruptionNeverPassesChecksum() {
        byte[] valid = SectionCodec.toBytes(SectionCodec.encode(pattern(3)));
        Random random = new Random(292);
        for (int i = 0; i < 128; i++) {
            byte[] corrupt = valid.clone();
            corrupt[28 + random.nextInt(valid.length - 28)] ^= (byte) (1 << random.nextInt(8));
            assertThrows(IllegalArgumentException.class, () -> SectionCodec.fromBytes(corrupt));
        }
    }
    @Test void metadataUsesEveryMaterialAndExactColumnCoordinates() {
        int[] states = new int[4096]; states[3 * 256 + 2 * 16 + 1] = 7; states[15 * 256 + 2 * 16 + 1] = 11;
        SectionMetadata metadata = SectionMetadata.of(SectionCodec.encode(states));
        assertEquals(15, metadata.highestNonAir(1, 2)); assertEquals(-1, metadata.highestNonAir(2, 1));
        assertEquals(4094, metadata.stateCounts().get(0)); assertEquals(1, metadata.stateCounts().get(11));
        assertThrows(UnsupportedOperationException.class, () -> metadata.stateCounts().put(123, 1));
        Arrays.fill(metadata.highestNonAirColumns(), 0); assertEquals(15, metadata.highestNonAir(1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> metadata.highestNonAir(16, 0));
    }
}
