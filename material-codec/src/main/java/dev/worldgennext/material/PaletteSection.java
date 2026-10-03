// SPDX-License-Identifier: MIT
package dev.worldgennext.material;

import java.util.Arrays;

/** Canonical sorted palette; each index stays within one packed 64-bit word. */
public final class PaletteSection implements SectionData {
    private final int[] palette;
    private final long[] words;
    private final int airState;
    private final int bits, perWord, nonAir;
    private final long checksum;
    public PaletteSection(int[] states) {
        this(states, 0);
    }
    public PaletteSection(int[] states, int airState) {
        if (states == null || states.length != BLOCK_COUNT) throw new IllegalArgumentException("Expected 4096 states");
        if (airState < 0) throw new IllegalArgumentException("Negative air state ID");
        int[] owned = states.clone();
        this.airState = airState;
        checksum = LogicalChecksum.of(owned);
        palette = Arrays.stream(owned).distinct().sorted().toArray();
        bits = bitsFor(palette.length);
        perWord = 64 / bits;
        words = new long[wordCount(palette.length)];
        int count = 0;
        for (int i = 0; i < BLOCK_COUNT; i++) {
            if (owned[i] != airState) count++;
            words[i / perWord] |= (long) Arrays.binarySearch(palette, owned[i]) << ((i % perWord) * bits);
        }
        nonAir = count;
    }
    static int bitsFor(int size) {
        if (size < 1 || size > BLOCK_COUNT) throw new IllegalArgumentException("Invalid palette size");
        return Math.max(1, 32 - Integer.numberOfLeadingZeros(size - 1));
    }
    static int wordCount(int size) { int perWord = 64 / bitsFor(size); return (BLOCK_COUNT + perWord - 1) / perWord; }
    public int[] palette() { return palette.clone(); }
    public long[] packedWords() { return words.clone(); }
    public int bitsPerEntry() { return bits; }
    @Override public int blockStateId(int index) {
        LogicalChecksum.checkIndex(index);
        return palette[(int) ((words[index / perWord] >>> ((index % perWord) * bits)) & ((1L << bits) - 1))];
    }
    @Override public int nonAirCount() { return nonAir; }
    @Override public long logicalChecksum() { return checksum; }
    @Override public int airStateId() { return airState; }
}
