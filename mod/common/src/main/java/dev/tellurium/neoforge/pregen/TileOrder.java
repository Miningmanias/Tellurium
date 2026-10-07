// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.pregen;

import java.util.ArrayList;
import java.util.List;

/**
 * Visit order for a square of chunks: one region-file-sized tile (32x32
 * chunks, aligned to region files) at a time, tiles in an outward spiral from
 * the centre, rows inside a tile.
 *
 * <p>Generating a chunk needs its neighbours out to a dozen chunks in earlier
 * stages.  Walking one-chunk-wide rings re-reads that whole band from disk on
 * every lap once a lap is longer than the in-flight window; a tile only pays
 * for its border.  Positions are computed from the index, so a large area
 * costs no memory.</p>
 */
public final class TileOrder {
    private static final int TILE = 32;

    private final int minX, minZ, maxX, maxZ;
    /** Tile coordinates (region x, region z) in visit order, two ints per tile. */
    private final int[] tiles;
    /** Chunks before each tile; one extra entry holds the total. */
    private final long[] offsets;

    public TileOrder(int centerX, int centerZ, int radius) {
        if (radius < 0) throw new IllegalArgumentException("radius must not be negative");
        this.minX = centerX - radius;
        this.minZ = centerZ - radius;
        this.maxX = centerX + radius;
        this.maxZ = centerZ + radius;
        int tileMinX = Math.floorDiv(minX, TILE), tileMaxX = Math.floorDiv(maxX, TILE);
        int tileMinZ = Math.floorDiv(minZ, TILE), tileMaxZ = Math.floorDiv(maxZ, TILE);
        int centreX = Math.floorDiv(centerX, TILE), centreZ = Math.floorDiv(centerZ, TILE);
        int rings = Math.max(Math.max(centreX - tileMinX, tileMaxX - centreX), Math.max(centreZ - tileMinZ, tileMaxZ - centreZ));
        List<int[]> visit = new ArrayList<>();
        addIfInside(visit, centreX, centreZ, tileMinX, tileMaxX, tileMinZ, tileMaxZ);
        for (int ring = 1; ring <= rings; ring++) {
            for (int x = -ring; x <= ring; x++) addIfInside(visit, centreX + x, centreZ - ring, tileMinX, tileMaxX, tileMinZ, tileMaxZ);
            for (int z = -ring + 1; z <= ring; z++) addIfInside(visit, centreX + ring, centreZ + z, tileMinX, tileMaxX, tileMinZ, tileMaxZ);
            for (int x = ring - 1; x >= -ring; x--) addIfInside(visit, centreX + x, centreZ + ring, tileMinX, tileMaxX, tileMinZ, tileMaxZ);
            for (int z = ring - 1; z >= -ring + 1; z--) addIfInside(visit, centreX - ring, centreZ + z, tileMinX, tileMaxX, tileMinZ, tileMaxZ);
        }
        this.tiles = new int[visit.size() * 2];
        this.offsets = new long[visit.size() + 1];
        long total = 0;
        for (int i = 0; i < visit.size(); i++) {
            int[] tile = visit.get(i);
            tiles[i * 2] = tile[0];
            tiles[i * 2 + 1] = tile[1];
            offsets[i] = total;
            total += (long) width(tile[0]) * depth(tile[1]);
        }
        offsets[visit.size()] = total;
        long side = 2L * radius + 1;
        if (total != side * side) throw new IllegalStateException("tile order covers " + total + " of " + side * side + " chunks");
    }

    private static void addIfInside(List<int[]> visit, int x, int z, int minX, int maxX, int minZ, int maxZ) {
        if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) visit.add(new int[]{x, z});
    }

    private int firstX(int tileX) { return Math.max(minX, tileX * TILE); }
    private int firstZ(int tileZ) { return Math.max(minZ, tileZ * TILE); }
    private int width(int tileX) { return Math.min(maxX, tileX * TILE + TILE - 1) - firstX(tileX) + 1; }
    private int depth(int tileZ) { return Math.min(maxZ, tileZ * TILE + TILE - 1) - firstZ(tileZ) + 1; }

    /** Number of chunks in the square. */
    public long size() {
        return offsets[offsets.length - 1];
    }

    /** The chunk at a position of the order, packed like ChunkPos.asLong (x in the low 32 bits). */
    public long at(long index) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException("index " + index);
        int low = 0, high = offsets.length - 2;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (offsets[middle] <= index) low = middle;
            else high = middle - 1;
        }
        int tileX = tiles[low * 2], tileZ = tiles[low * 2 + 1];
        long local = index - offsets[low];
        int width = width(tileX);
        int x = firstX(tileX) + (int) (local % width);
        int z = firstZ(tileZ) + (int) (local / width);
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }
}
