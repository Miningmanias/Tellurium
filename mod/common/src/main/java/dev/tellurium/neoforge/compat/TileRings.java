// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.compat;

/** Square rings of tiles around a tile, used to decide which tiles to start ahead of a request. */
final class TileRings {
    private TileRings() {}

    /**
     * The tiles at exactly the given distance from a tile, the distance being the larger of the two axis
     * distances, as {x, z} offsets in tiles.  Ring 1 is the eight neighbours.
     */
    static int[][] offsets(int ring) {
        if (ring < 1) throw new IllegalArgumentException("ring " + ring);
        int[][] offsets = new int[8 * ring][];
        int count = 0;
        for (int dz = -ring; dz <= ring; dz++) {
            boolean edge = dz == -ring || dz == ring;
            for (int dx = -ring; dx <= ring; dx += edge ? 1 : 2 * ring) offsets[count++] = new int[]{dx, dz};
        }
        return offsets;
    }
}
