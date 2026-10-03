// SPDX-License-Identifier: MIT
package dev.worldgennext.spatial;

import dev.worldgennext.semantic.WorldgenIdentity;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TileKeyTest {
    private static final WorldgenIdentity WORLD = new WorldgenIdentity(42, "overworld", "graph", 0);
    @Test void negativeAndExtremeCoordinatesRoundTripToCanonicalTiles() {
        Random random = new Random(30);
        for (int width : new int[]{1, 3, 8, 17, Integer.MAX_VALUE}) {
            for (int iteration = 0; iteration < 1000; iteration++) {
                int x = iteration == 0 ? Integer.MIN_VALUE : iteration == 1 ? Integer.MAX_VALUE : random.nextInt();
                int z = random.nextInt();
                TileKey key = TileKey.atChunk(WORLD, "node", "lattice", "deps", x, z, width);
                assertEquals(x, key.originChunkX() + key.localChunkX(x));
                assertEquals(z, key.originChunkZ() + key.localChunkZ(z));
                assertTrue(key.localChunkX(x) >= 0 && key.localChunkX(x) < width);
            }
        }
        TileKey key = TileKey.atChunk(WORLD, "node", "lattice", "deps", -1, -8, 8);
        assertEquals(-1, key.tileX()); assertEquals(7, key.localChunkX(-1));
        assertEquals(0, key.localChunkZ(-8));
        assertThrows(IllegalArgumentException.class, () -> key.localChunkX(0));
    }
    @Test void everySemanticIdentityDimensionPartitionsTheCache() {
        TileKey base = TileKey.atChunk(WORLD, "n", "d", "h", 0, 0, 8);
        assertEquals(base, TileKey.atChunk(WORLD, "n", "d", "h", 7, 7, 8));
        assertNotEquals(base, TileKey.atChunk(new WorldgenIdentity(43, "overworld", "graph", 0), "n", "d", "h", 0, 0, 8));
        assertNotEquals(base, TileKey.atChunk(new WorldgenIdentity(42, "nether", "graph", 0), "n", "d", "h", 0, 0, 8));
        assertNotEquals(base, TileKey.atChunk(new WorldgenIdentity(42, "overworld", "changed", 0), "n", "d", "h", 0, 0, 8));
        assertNotEquals(base, TileKey.atChunk(new WorldgenIdentity(42, "overworld", "graph", 1), "n", "d", "h", 0, 0, 8));
        assertNotEquals(base, TileKey.atChunk(WORLD, "other", "d", "h", 0, 0, 8));
        assertNotEquals(base, TileKey.atChunk(WORLD, "n", "other", "h", 0, 0, 8));
        assertNotEquals(base, TileKey.atChunk(WORLD, "n", "d", "other", 0, 0, 8));
        assertNotEquals(base, TileKey.atChunk(WORLD, "n", "d", "h", 0, 0, 4));
    }
    @Test void invalidIdentityAndGeometryFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> TileKey.atChunk(WORLD, "n", "d", "h", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> TileKey.atChunk(WORLD, " ", "d", "h", 0, 0, 8));
        assertThrows(IllegalArgumentException.class, () -> TileKey.atChunk(WORLD, "n", "d", null, 0, 0, 8));
    }
}
