// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan.production;

import java.util.ArrayList;
import java.util.List;

/** Checked non-overlapping logical ranges inside one arena. */
public final class BufferLayout {
    public record Range(String name, long offset, long length) {
        public Range { if (name == null || name.isBlank() || offset < 0 || length < 0) throw new IllegalArgumentException("Invalid buffer range"); }
        public long endExclusive() { return Math.addExact(offset, length); }
    }
    private final long capacity; private final List<Range> ranges = new ArrayList<>();
    public BufferLayout(long capacity) { if (capacity < 0) throw new IllegalArgumentException("Negative buffer capacity"); this.capacity = capacity; }
    public synchronized Range allocate(String name, long length, long alignment) {
        if (length < 0 || alignment <= 0 || Long.bitCount(alignment) != 1) throw new IllegalArgumentException("Invalid allocation");
        long offset = align(ranges.isEmpty() ? 0 : ranges.get(ranges.size() - 1).endExclusive(), alignment);
        Range range = new Range(name, offset, length); if (range.endExclusive() > capacity) throw new IllegalStateException("Buffer arena exhausted"); ranges.add(range); return range;
    }
    public long capacity() { return capacity; }
    public synchronized List<Range> ranges() { return List.copyOf(ranges); }
    private static long align(long value, long alignment) { return Math.addExact(value, (alignment - value % alignment) % alignment); }
}
