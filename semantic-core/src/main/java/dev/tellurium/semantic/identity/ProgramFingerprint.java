// SPDX-License-Identifier: MIT
package dev.tellurium.semantic.identity;

import dev.tellurium.semantic.program.WorldgenProgram;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ProgramFingerprint {
    private ProgramFingerprint() {}
    public static String of(WorldgenProgram program) { return program.fingerprint(); }
    public static String combine(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) digest.update((value == null ? "<null>" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
