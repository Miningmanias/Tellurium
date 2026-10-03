// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan.production;

public record MemoryVisibility(boolean coherent, int atomSize) {
    public MemoryVisibility { if (atomSize <= 0 || Integer.bitCount(atomSize) != 1) throw new IllegalArgumentException("Invalid non-coherent atom size"); }
    public long alignedStart(long offset) { if (offset < 0) throw new IllegalArgumentException("Negative offset"); return offset - offset % atomSize; }
    public long alignedLength(long offset, long length) { if (length < 0) throw new IllegalArgumentException("Negative length"); long start = alignedStart(offset); long end = Math.addExact(offset, length); long alignedEnd = Math.addExact(end, (atomSize - end % atomSize) % atomSize); return alignedEnd - start; }
}
