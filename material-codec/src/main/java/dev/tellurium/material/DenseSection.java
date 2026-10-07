// SPDX-License-Identifier: MIT
package dev.tellurium.material;

/** Immutable fallback for arbitrary nonnegative fixture IDs. */
public final class DenseSection implements SectionData {
    private final int[] states;
    private final int airState;
    private final int nonAir;
    private final long checksum;
    public DenseSection(int[] states) {
        this(states, 0);
    }
    public DenseSection(int[] states, int airState) {
        if (states == null || states.length != BLOCK_COUNT) throw new IllegalArgumentException("Expected 4096 states");
        if (airState < 0) throw new IllegalArgumentException("Negative air state ID");
        this.states = states.clone();
        this.airState = airState;
        checksum = LogicalChecksum.of(this.states);
        int count = 0;
        for (int state : this.states) if (state != airState) count++;
        nonAir = count;
    }
    @Override public int blockStateId(int index) { LogicalChecksum.checkIndex(index); return states[index]; }
    @Override public int nonAirCount() { return nonAir; }
    @Override public long logicalChecksum() { return checksum; }
    @Override public int airStateId() { return airState; }
}
