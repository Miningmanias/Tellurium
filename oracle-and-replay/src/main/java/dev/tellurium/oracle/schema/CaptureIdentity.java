// SPDX-License-Identifier: MIT
package dev.tellurium.oracle.schema;

import java.util.Objects;

public record CaptureIdentity(String source, String stackFingerprint, String seed, String dimension,
                              int chunkX, int chunkZ, String endpoint, String contextFingerprint) {
    public CaptureIdentity {
        requireText(source, "source"); requireText(stackFingerprint, "stackFingerprint"); requireText(seed, "seed"); requireText(dimension, "dimension"); requireText(endpoint, "endpoint"); requireText(contextFingerprint, "contextFingerprint");
        try { Long.parseLong(seed); } catch (NumberFormatException failure) { throw new IllegalArgumentException("Seed must be decimal signed 64-bit text", failure); }
    }
    public String key() { return String.join("/", source, stackFingerprint, seed, dimension, Integer.toString(chunkX), Integer.toString(chunkZ), endpoint, contextFingerprint); }
    /** Logical comparison identity shared by original and candidate captures. */
    public String caseKey() { return String.join("/", seed, dimension, Integer.toString(chunkX), Integer.toString(chunkZ), endpoint, contextFingerprint); }
    public static CaptureIdentity parseKey(String key) {
        if (key == null) throw new IllegalArgumentException("Capture key is required");
        String[] parts = key.split("/", -1);
        if (parts.length != 8) throw new IllegalArgumentException("Capture key must contain eight slash-delimited fields");
        try {
            return new CaptureIdentity(parts[0], parts[1], parts[2], parts[3], Integer.parseInt(parts[4]),
                    Integer.parseInt(parts[5]), parts[6], parts[7]);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Capture coordinates are not integers", failure);
        }
    }
    private static void requireText(String value, String name) { if (Objects.requireNonNull(value, name).isBlank()) throw new IllegalArgumentException(name + " is blank"); }
}
