// SPDX-License-Identifier: MIT
package dev.worldgennext.material;

/** FNV-1a over exactly 4096 unsigned little-endian 32-bit fixture IDs. Not a parity oracle. */
public final class LogicalChecksum {
    private LogicalChecksum() {}
    public static long of(int[] states) {
        if (states == null || states.length != SectionData.BLOCK_COUNT) throw new IllegalArgumentException("Expected 4096 states");
        long hash = 0xcbf29ce484222325L;
        for (int state : states) {
            if (state < 0) throw new IllegalArgumentException("Negative fixture state ID");
            for (int shift = 0; shift < 32; shift += 8) hash = (hash ^ ((state >>> shift) & 255)) * 0x100000001b3L;
        }
        return hash;
    }
    static void checkIndex(int index) {
        if (index < 0 || index >= SectionData.BLOCK_COUNT) throw new IndexOutOfBoundsException(index);
    }
}
