// SPDX-License-Identifier: MIT
package dev.tellurium.material;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Fixture-only counts and highest non-air Y, index=(y*256)+(z*16)+x. No fluid/ore semantics inferred. */
public final class SectionMetadata {
    private final Map<Integer, Integer> counts;
    private final int[] heights;
    private SectionMetadata(int[] states, int airStateId) {
        TreeMap<Integer, Integer> found = new TreeMap<>();
        heights = new int[256];
        Arrays.fill(heights, -1);
        for (int i = 0; i < states.length; i++) {
            found.merge(states[i], 1, Integer::sum);
            if (states[i] != airStateId) heights[i & 255] = i >>> 8;
        }
        counts = Collections.unmodifiableMap(found);
    }
    public static SectionMetadata of(SectionData section) { return new SectionMetadata(SectionCodec.decode(section), section.airStateId()); }
    public Map<Integer, Integer> stateCounts() { return counts; }
    public int highestNonAir(int x, int z) {
        if (x < 0 || x >= 16 || z < 0 || z >= 16) throw new IndexOutOfBoundsException("Column outside section");
        return heights[z * 16 + x];
    }
    public int[] highestNonAirColumns() { return heights.clone(); }
}
