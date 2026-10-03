// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.pregen;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TileOrderTest {
    private static int x(long packed) { return (int) packed; }
    private static int z(long packed) { return (int) (packed >> 32); }

    /** Every chunk of the square exactly once, for centres on both sides of zero and off the region grid. */
    @Test
    void coversTheSquareExactlyOnce() {
        int[][] cases = {{0, 0, 0}, {0, 0, 1}, {5, -7, 40}, {-33, 31, 16}, {1000, -1000, 47}, {-1, -1, 32}};
        for (int[] c : cases) {
            TileOrder order = new TileOrder(c[0], c[1], c[2]);
            long side = 2L * c[2] + 1;
            assertEquals(side * side, order.size());
            Set<Long> seen = new HashSet<>();
            for (long i = 0; i < order.size(); i++) {
                long packed = order.at(i);
                assertTrue(Math.abs(x(packed) - c[0]) <= c[2] && Math.abs(z(packed) - c[1]) <= c[2], "outside the square at " + i);
                assertTrue(seen.add(packed), "chunk repeated at " + i);
            }
            assertEquals(side * side, seen.size());
        }
    }

    /** A tile is finished before the next one starts, and tiles are region files. */
    @Test
    void visitsOneRegionAtATime() {
        TileOrder order = new TileOrder(3, -70, 100);
        Set<Long> finished = new HashSet<>();
        long current = Long.MIN_VALUE;
        for (long i = 0; i < order.size(); i++) {
            long packed = order.at(i);
            long region = (long) Math.floorDiv(x(packed), 32) << 32 | Math.floorDiv(z(packed), 32) & 0xFFFFFFFFL;
            if (region != current) {
                assertTrue(finished.add(region), "region revisited at " + i);
                current = region;
            }
        }
    }

    /** The first tile holds the centre and tiles move outward ring by ring. */
    @Test
    void startsAtTheCentreAndMovesOutward() {
        TileOrder order = new TileOrder(16, 16, 200);
        long first = order.at(0);
        assertEquals(0, Math.floorDiv(x(first), 32));
        assertEquals(0, Math.floorDiv(z(first), 32));
        int ring = 0;
        for (long i = 0; i < order.size(); i += 97) {
            long packed = order.at(i);
            int distance = Math.max(Math.abs(Math.floorDiv(x(packed), 32)), Math.abs(Math.floorDiv(z(packed), 32)));
            assertTrue(distance >= ring, "moved inward at " + i);
            ring = distance;
        }
    }

    @Test
    void rejectsIndexesOutsideTheOrder() {
        TileOrder order = new TileOrder(0, 0, 2);
        assertThrows(IndexOutOfBoundsException.class, () -> order.at(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> order.at(order.size()));
        assertThrows(IllegalArgumentException.class, () -> new TileOrder(0, 0, -1));
    }
}
