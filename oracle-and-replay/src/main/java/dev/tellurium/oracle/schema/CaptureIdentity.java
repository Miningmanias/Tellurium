// SPDX-License-Identifier: MIT
package dev.tellurium.oracle.schema;

import java.util.Objects;

public record CaptureIdentity(String source, String stackFingerprint, String seed, String dimension,
                              int chunkX, int chunkZ, String endpoint, String contextFingerprint) {
    public CaptureIdentity {
        requireText(source, "source"); requireText(stackFingerprint, "stackFingerprint"); requireText(seed, "seed"); requireText(dimension, "dimension"); requireText(endpoint, "endpoint"); requireText(contextFingerprint, "contextFingerprint");
        try { Long.parseLong(seed); } catch (NumberFormatException failure) { throw new IllegalArgumentException("Seed must be decimal signed 64-bit text", failure); }
    }
    public String key() { return String.join("/", escape(source), escape(stackFingerprint), seed, escape(dimension), Integer.toString(chunkX), Integer.toString(chunkZ), escape(endpoint), escape(contextFingerprint)); }
    /**
     * A component as it stands in a key: a slash in it (a dimension such as mod:sky/islands) is written %2F and a
     * percent sign %25, so the eight fields can always be told apart.  Components without either read as before.
     */
    private static String escape(String component) { return component.replace("%", "%25").replace("/", "%2F"); }
    private static String unescape(String component) { return component.replace("%2F", "/").replace("%25", "%"); }
    /** Logical comparison identity shared by original and candidate captures. */
    public String caseKey() { return String.join("/", seed, escape(dimension), Integer.toString(chunkX), Integer.toString(chunkZ), escape(endpoint), escape(contextFingerprint)); }
    public static CaptureIdentity parseKey(String key) {
        if (key == null) throw new IllegalArgumentException("Capture key is required");
        String[] parts = key.split("/", -1);
        if (parts.length != 8) throw new IllegalArgumentException("Capture key must contain eight slash-delimited fields");
        try {
            return new CaptureIdentity(unescape(parts[0]), unescape(parts[1]), parts[2], unescape(parts[3]), Integer.parseInt(parts[4]),
                    Integer.parseInt(parts[5]), unescape(parts[6]), unescape(parts[7]));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Capture coordinates are not integers", failure);
        }
    }
    private static void requireText(String value, String name) { if (Objects.requireNonNull(value, name).isBlank()) throw new IllegalArgumentException(name + " is blank"); }
}
