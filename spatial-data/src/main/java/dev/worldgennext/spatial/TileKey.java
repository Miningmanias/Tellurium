// SPDX-License-Identifier: MIT
package dev.worldgennext.spatial;

import dev.worldgennext.semantic.WorldgenIdentity;
import java.util.Objects;

/** Pure sample identity. Caller must put every dynamic dependency into program/dependency identities. */
public record TileKey(WorldgenIdentity world, String nodeId, String domain, String dependencyHash,
                      int tileX, int tileZ, int widthChunks) {
    public TileKey {
        Objects.requireNonNull(world, "world");
        requireText(nodeId); requireText(domain); requireText(dependencyHash);
        if (widthChunks <= 0) throw new IllegalArgumentException("Tile width must be positive");
    }
    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing semantic identity");
    }
    public static TileKey atChunk(WorldgenIdentity world, String nodeId, String domain,
                                  String dependencyHash, int chunkX, int chunkZ, int widthChunks) {
        if (widthChunks <= 0) throw new IllegalArgumentException("Tile width must be positive");
        return new TileKey(world, nodeId, domain, dependencyHash,
                Math.floorDiv(chunkX, widthChunks), Math.floorDiv(chunkZ, widthChunks), widthChunks);
    }
    public long originChunkX() { return (long) tileX * widthChunks; }
    public long originChunkZ() { return (long) tileZ * widthChunks; }
    public int localChunkX(int chunkX) { return local(chunkX, originChunkX()); }
    public int localChunkZ(int chunkZ) { return local(chunkZ, originChunkZ()); }
    private int local(int coordinate, long origin) {
        long local = coordinate - origin;
        if (local < 0 || local >= widthChunks) throw new IllegalArgumentException("Coordinate outside tile");
        return (int) local;
    }
}
