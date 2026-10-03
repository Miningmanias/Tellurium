// SPDX-License-Identifier: MIT
package dev.worldgennext.spatial.worldgen;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class SurfaceColumnAtlas {
    private final Map<Long, Integer> heights;
    public SurfaceColumnAtlas(Map<Long, Integer> heights) {
        Objects.requireNonNull(heights, "heights");
        var copy = new LinkedHashMap<Long, Integer>();
        heights.forEach((key, height) -> {
            if (key == null || height == null) throw new IllegalArgumentException("Surface atlas contains a null entry");
            copy.put(key, height);
        });
        this.heights = Map.copyOf(copy);
    }
    public Integer height(int x, int z) { return heights.get(key(x, z)); }
    public Map<Long, Integer> values() { return heights; }
    public static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
