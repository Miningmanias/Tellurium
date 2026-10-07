// SPDX-License-Identifier: MIT
package dev.tellurium.oracle.schema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;

/** Deterministic line-oriented interchange for the pure corpus artifact. */
public final class SnapshotIo {
    private SnapshotIo() {}
    public static String encode(ChunkSnapshot snapshot) {
        StringBuilder out = new StringBuilder("TELLURIUM-SNAPSHOT-1\n"); out.append("identity=").append(escape(snapshot.identity().key())).append('\n');
        snapshot.fields().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> out.append("field=").append(entry.getKey()).append('=').append(escape(entry.getValue())).append('\n'));
        snapshot.values().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> out.append("value=").append(escape(entry.getKey())).append('=').append(escape(entry.getValue())).append('\n'));
        return out.toString();
    }
    public static void write(Path path, ChunkSnapshot snapshot) throws IOException { Files.createDirectories(path.toAbsolutePath().normalize().getParent()); Files.writeString(path, encode(snapshot), StandardCharsets.UTF_8); }
    public static ChunkSnapshot read(Path path) throws IOException {
        String encoded = Files.readString(path, StandardCharsets.UTF_8);
        String prefix = "identity=";
        String identityLine = java.util.Arrays.stream(encoded.split("\\n", -1)).filter(line -> line.startsWith(prefix)).findFirst().orElseThrow(() -> new IllegalArgumentException("Snapshot identity is missing"));
        return decode(encoded, CaptureIdentity.parseKey(unescape(identityLine.substring(prefix.length()))));
    }
    public static ChunkSnapshot read(Path path, CaptureIdentity identity) throws IOException { return decode(Files.readString(path, StandardCharsets.UTF_8), identity); }
    public static ChunkSnapshot decode(String encoded, CaptureIdentity identity) {
        if (encoded == null || !encoded.startsWith("TELLURIUM-SNAPSHOT-1\n")) throw new IllegalArgumentException("Unknown snapshot format");
        java.util.Objects.requireNonNull(identity, "identity");
        var fields = new java.util.EnumMap<SnapshotField, String>(SnapshotField.class); var values = new java.util.LinkedHashMap<String, String>();
        boolean identitySeen = false;
        for (String line : encoded.substring("TELLURIUM-SNAPSHOT-1\n".length()).split("\\n", -1)) {
            if (line.isBlank()) continue;
            if (line.startsWith("identity=")) {
                if (identitySeen) throw new IllegalArgumentException("Duplicate snapshot identity");
                identitySeen = true;
                String encodedIdentity = unescape(line.substring("identity=".length()));
                if (!identity.key().equals(encodedIdentity)) throw new IllegalArgumentException("Snapshot identity does not match artifact");
                continue;
            }
            int first = line.indexOf('='); int second = separator(line, first + 1); if (first <= 0 || second <= first) throw new IllegalArgumentException("Malformed snapshot line");
            String kind = line.substring(0, first), key = unescape(line.substring(first + 1, second)), value = unescape(line.substring(second + 1));
            if (kind.equals("field")) {
                SnapshotField field = SnapshotField.valueOf(key);
                if (fields.putIfAbsent(field, value) != null) throw new IllegalArgumentException("Duplicate snapshot field: " + key);
            } else if (kind.equals("value")) {
                if (key.isBlank()) throw new IllegalArgumentException("Blank snapshot value key");
                if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate snapshot value: " + key);
            } else throw new IllegalArgumentException("Unknown snapshot line kind: " + kind);
        }
        if (!identitySeen) throw new IllegalArgumentException("Snapshot identity is missing");
        return new ChunkSnapshot(java.util.Objects.requireNonNull(identity, "identity"), fields, values);
    }
    public static String escape(String value) {
        Objects.requireNonNull(value, "value");
        var result = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '=' -> result.append("\\=");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        result.append("\\u");
                        result.append(Character.forDigit((c >>> 12) & 0xf, 16));
                        result.append(Character.forDigit((c >>> 8) & 0xf, 16));
                        result.append(Character.forDigit((c >>> 4) & 0xf, 16));
                        result.append(Character.forDigit(c & 0xf, 16));
                    } else {
                        result.append(c);
                    }
                }
            }
        }
        return result.toString();
    }

    /** Decodes only the escapes emitted by this format; unknown escapes fail closed. */
    public static String unescape(String value) {
        Objects.requireNonNull(value, "value");
        var result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c != '\\') {
                result.append(c);
                continue;
            }
            if (++index >= value.length()) throw new IllegalArgumentException("Dangling escape");
            char escaped = value.charAt(index);
            switch (escaped) {
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case '\\' -> result.append('\\');
                case '=' -> result.append('=');
                case 'u' -> {
                    if (index + 4 >= value.length()) throw new IllegalArgumentException("Truncated unicode escape");
                    int codePoint = 0;
                    for (int digit = 1; digit <= 4; digit++) {
                        int valueDigit = Character.digit(value.charAt(index + digit), 16);
                        if (valueDigit < 0) throw new IllegalArgumentException("Invalid unicode escape");
                        codePoint = (codePoint << 4) | valueDigit;
                    }
                    result.append((char) codePoint);
                    index += 4;
                }
                default -> throw new IllegalArgumentException("Unknown snapshot escape: \\" + escaped);
            }
        }
        return result.toString();
    }
    private static int separator(String value, int start) { boolean escaped = false; for (int i = start; i < value.length(); i++) { char c = value.charAt(i); if (escaped) { escaped = false; continue; } if (c == '\\') { escaped = true; continue; } if (c == '=') return i; } return -1; }
}
