// SPDX-License-Identifier: MIT
package dev.worldgennext.material.chunk;

import java.util.Arrays;

/** Fluid/postprocessing marks are carried with the result instead of inferred from density. */
public final class PostProcessingPayload {
    private final boolean[] fluidMarks;
    public PostProcessingPayload(boolean[] fluidMarks, int expectedBlockCount) {
        if (fluidMarks == null || fluidMarks.length != expectedBlockCount) throw new IllegalArgumentException("Invalid postprocessing marks");
        this.fluidMarks = fluidMarks.clone();
    }
    public boolean fluidAt(int index) { if (index < 0 || index >= fluidMarks.length) throw new IndexOutOfBoundsException(index); return fluidMarks[index]; }
    public boolean[] fluidMarks() { return fluidMarks.clone(); }
    @Override public boolean equals(Object other) { return other instanceof PostProcessingPayload that && Arrays.equals(fluidMarks, that.fluidMarks); }
    @Override public int hashCode() { return Arrays.hashCode(fluidMarks); }
}
