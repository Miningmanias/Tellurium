// SPDX-License-Identifier: MIT
package dev.worldgennext.material.chunk;

import java.util.Objects;

/** Fixed identity and geometry header for a complete NOISE candidate result. */
public record ChunkResultHeader(int chunkX, int chunkZ, int minY, int height,
                                String contextIdentity, String registryFingerprint, String abiVersion) {
    public ChunkResultHeader {
        if (height <= 0 || height % 16 != 0) throw new IllegalArgumentException("Chunk height must be a positive multiple of 16");
        // maxYExclusive is part of the public int-shaped ABI.  Accepting the
        // mathematical value Integer.MAX_VALUE + 1 would make maxYExclusive()
        // wrap and would let a later allocation/coordinate check observe a
        // different geometry than the header advertised.
        if ((long) minY + height > Integer.MAX_VALUE) throw new IllegalArgumentException("Chunk bounds overflow");
        requireText(contextIdentity, "contextIdentity"); requireText(registryFingerprint, "registryFingerprint"); requireText(abiVersion, "abiVersion");
    }
    public int maxYExclusive() { return Math.addExact(minY, height); }
    public int sectionCount() { return height / 16; }
    private static void requireText(String value, String name) { if (Objects.requireNonNull(value, name).isBlank()) throw new IllegalArgumentException(name + " is blank"); }
}
