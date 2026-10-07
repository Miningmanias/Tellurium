// SPDX-License-Identifier: MIT
package dev.tellurium.frontend.mc1211;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class SourceStackFingerprint {
    private SourceStackFingerprint() {}
    public static String of(String minecraft, String neoforge, java.util.Map<String, String> dependencies) {
        try { var digest = MessageDigest.getInstance("SHA-256"); digest.update((minecraft + "\0" + neoforge + "\0" + new java.util.TreeMap<>(dependencies == null ? java.util.Map.of() : dependencies)).getBytes(StandardCharsets.UTF_8)); return java.util.HexFormat.of().formatHex(digest.digest()); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
