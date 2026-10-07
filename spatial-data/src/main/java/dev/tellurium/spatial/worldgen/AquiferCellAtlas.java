// SPDX-License-Identifier: MIT
package dev.tellurium.spatial.worldgen;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class AquiferCellAtlas {
    public record Cell(int x, int y, int z, double level, String state, boolean barrier) {
        public Cell { if (!Double.isFinite(level) || state == null || state.isBlank()) throw new IllegalArgumentException("Invalid aquifer cell"); }
    }
    private final Map<Long, Cell> cells;
    public AquiferCellAtlas(Map<Long, Cell> cells) {
        Objects.requireNonNull(cells, "cells");
        var copy = new LinkedHashMap<Long, Cell>();
        cells.forEach((key, cell) -> {
            if (key == null || cell == null) throw new IllegalArgumentException("Aquifer atlas contains a null entry");
            if (key.longValue() != key(cell.x(), cell.y(), cell.z())) throw new IllegalArgumentException("Aquifer cell key does not match coordinates");
            copy.put(key, cell);
        });
        this.cells = Collections.unmodifiableMap(copy);
    }
    public Cell cell(long key) { return cells.get(key); }
    public Map<Long, Cell> cells() { return cells; }
    public static long key(int x, int y, int z) { return ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) y * 0xC2B2AE3D27D4EB4FL) ^ z; }
}
