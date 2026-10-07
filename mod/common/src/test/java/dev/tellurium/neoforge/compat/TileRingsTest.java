// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.compat;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TileRingsTest {
    @Test
    void aRingIsEveryTileAtThatDistanceExactlyOnce() {
        for (int ring = 1; ring <= 12; ring++) {
            Set<Long> seen = new HashSet<>();
            for (int[] offset : TileRings.offsets(ring)) {
                assertEquals(ring, Math.max(Math.abs(offset[0]), Math.abs(offset[1])), "ring " + ring);
                assertTrue(seen.add(((long) offset[0] << 32) ^ (offset[1] & 0xFFFFFFFFL)), "repeated tile in ring " + ring);
            }
            // A square of side 2r+1 less the square of side 2r-1 inside it.
            assertEquals((2 * ring + 1) * (2 * ring + 1) - (2 * ring - 1) * (2 * ring - 1), seen.size(), "ring " + ring);
        }
    }

    @Test
    void ringsOneToNTogetherCoverTheSquareAroundTheTileWithoutTheTileItself() {
        int rings = 6;
        Set<Long> seen = new HashSet<>();
        for (int ring = 1; ring <= rings; ring++) {
            for (int[] offset : TileRings.offsets(ring)) {
                assertTrue(seen.add(((long) offset[0] << 32) ^ (offset[1] & 0xFFFFFFFFL)));
            }
        }
        assertEquals((2 * rings + 1) * (2 * rings + 1) - 1, seen.size());
        assertTrue(!seen.contains(0L));
    }

    @Test
    void thereIsNoRingZero() {
        assertThrows(IllegalArgumentException.class, () -> TileRings.offsets(0));
    }
}
