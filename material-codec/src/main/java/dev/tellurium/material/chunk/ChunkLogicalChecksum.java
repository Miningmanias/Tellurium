// SPDX-License-Identifier: MIT
package dev.tellurium.material.chunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stable logical checksum used as an index; comparison still checks every field. */
public final class ChunkLogicalChecksum {
    private ChunkLogicalChecksum() {}
    public static String of(ChunkNoiseResult result) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String state : result.denseStates()) digest.update((state + "\0").getBytes(StandardCharsets.UTF_8));
            for (var entry : result.heightmaps().maps().entrySet()) {
                digest.update((entry.getKey() + "\0").getBytes(StandardCharsets.UTF_8));
                for (int value : entry.getValue()) digest.update(Integer.toString(value).getBytes(StandardCharsets.UTF_8));
            }
            for (boolean mark : result.postProcessing().fluidMarks()) digest.update((byte) (mark ? 1 : 0));
            for (var entry : result.metadata().fields().entrySet()) {
                digest.update((entry.getKey() + "\0" + entry.getValue() + "\0").getBytes(StandardCharsets.UTF_8));
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
