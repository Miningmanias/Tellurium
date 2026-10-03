// SPDX-License-Identifier: MIT
package dev.worldgennext.material;

import java.util.Arrays;

/** A complete fixture section whose actual material state is uniform. */
public final class UniformSection implements SectionData {
    private final int state;
    private final int airState;
    private final long checksum;
    public UniformSection(int state) {
        this(state, 0);
    }
    public UniformSection(int state, int airState) {
        if (state < 0) throw new IllegalArgumentException("Negative fixture state ID");
        if (airState < 0) throw new IllegalArgumentException("Negative air state ID");
        this.state = state;
        this.airState = airState;
        int[] expanded = new int[BLOCK_COUNT];
        Arrays.fill(expanded, state);
        checksum = LogicalChecksum.of(expanded);
    }
    public int stateId() { return state; }
    @Override public int blockStateId(int index) { LogicalChecksum.checkIndex(index); return state; }
    @Override public int nonAirCount() { return state == airState ? 0 : BLOCK_COUNT; }
    @Override public long logicalChecksum() { return checksum; }
    @Override public int airStateId() { return airState; }
}
