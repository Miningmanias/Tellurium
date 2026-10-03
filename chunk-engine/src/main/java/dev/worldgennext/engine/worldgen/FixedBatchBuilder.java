// SPDX-License-Identifier: MIT
package dev.worldgennext.engine.worldgen;

import java.util.ArrayList;
import java.util.List;

/** Fixed-size batching with partial tail progress; batch one is always legal. */
public final class FixedBatchBuilder {
    private final int maximum;
    public FixedBatchBuilder(int maximum) { if (maximum <= 0) throw new IllegalArgumentException("Maximum batch must be positive"); this.maximum = maximum; }
    public <T> List<List<T>> split(List<T> values) {
        if (values == null || values.isEmpty()) return List.of();
        var result = new ArrayList<List<T>>();
        int size = values.size();
        for (int start = 0; start < size;) {
            int end = rangeEnd(start, size, maximum);
            result.add(List.copyOf(values.subList(start, end)));
            start = end;
        }
        return List.copyOf(result);
    }
    /** End of a nonempty batch; the increment never exceeds the remaining range. */
    static int rangeEnd(int start, int size, int maximum) {
        if (start < 0 || start >= size || maximum <= 0)
            throw new IllegalArgumentException("Invalid batch range or maximum");
        return start + Math.min(maximum, size - start);
    }
    public int maximum() { return maximum; }
}
