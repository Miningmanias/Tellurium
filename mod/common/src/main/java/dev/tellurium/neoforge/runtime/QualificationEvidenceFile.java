// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * Loads the explicit, operator-supplied qualification receipt used to admit
 * the production NOISE provider.  The file is intentionally a small
 * properties document so the installed jar has no JSON dependency at the
 * admission boundary; the source artifact named by the receipt remains the
 * reproducible comparison bundle.
 */
public final class QualificationEvidenceFile {
    public static final int SCHEMA_VERSION = 1;

    private QualificationEvidenceFile() {}

    public record Loaded(QualifiedHookEvidence evidence, Path file, Path sourceArtifact) {
        public Loaded {
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(sourceArtifact, "sourceArtifact");
            file = file.toAbsolutePath().normalize();
            sourceArtifact = sourceArtifact.toAbsolutePath().normalize();
        }
    }

    public static Loaded load(Path file) {
        Objects.requireNonNull(file, "file");
        Path normalized = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Qualification evidence is not a regular file: " + normalized);
        }
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(normalized, StandardCharsets.UTF_8)) {
            values.load(reader);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Unable to read qualification evidence: " + normalized, failure);
        }
        if (integerValue(values, "schemaVersion") != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported qualification evidence schema: "
                    + values.getProperty("schemaVersion"));
        }
        QualifiedHookEvidence evidence = new QualifiedHookEvidence(
                required(values, "contextKey"),
                required(values, "route").toUpperCase(Locale.ROOT),
                longValue(values, "comparedCases"),
                longValue(values, "comparedFields"),
                longValue(values, "mismatches"),
                booleanValue(values, "completeCoverage"),
                booleanValue(values, "independentOracle"),
                required(values, "sourceArtifactSha256"),
                required(values, "resultAbi"),
                required(values, "compilerVersion"));
        Path sourceArtifact = Path.of(required(values, "sourceArtifactFile"));
        if (!sourceArtifact.isAbsolute()) {
            sourceArtifact = normalized.getParent().resolve(sourceArtifact);
        }
        sourceArtifact = sourceArtifact.toAbsolutePath().normalize();
        Path receiptParent = normalized.getParent();
        if (receiptParent == null || !sourceArtifact.startsWith(receiptParent)) {
            throw new IllegalArgumentException("Qualification source artifact must be inside the receipt directory: "
                    + sourceArtifact);
        }
        if (!Files.isRegularFile(sourceArtifact, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Qualification source artifact is not a regular file: " + sourceArtifact);
        }
        String actualHash;
        try {
            actualHash = sha256(sourceArtifact);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Unable to hash qualification source artifact: " + sourceArtifact, failure);
        }
        if (!actualHash.equals(evidence.sourceArtifactSha256())) {
            throw new IllegalArgumentException("Qualification source artifact hash does not match receipt: "
                    + sourceArtifact);
        }
        return new Loaded(evidence, normalized, sourceArtifact);
    }

    /**
     * Writes a deterministic, self-contained receipt beside its source
     * artifact and reloads it through the same validation path used by the
     * live mod.  The writer refuses incomplete evidence, edited source bytes,
     * symlinked targets and overwrites so a qualification run cannot silently
     * replace an older receipt.
     */
    public static Loaded write(Path file, QualifiedHookEvidence evidence, Path sourceArtifact) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(sourceArtifact, "sourceArtifact");
        evidence.requireAdmissible();

        Path normalized = file.toAbsolutePath().normalize();
        Path source = sourceArtifact.toAbsolutePath().normalize();
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException("Refusing to overwrite qualification evidence: " + normalized);
        }
        if (normalized.equals(source)) {
            throw new IllegalArgumentException("Qualification receipt cannot overwrite its source artifact");
        }
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Qualification source artifact is not a regular file: " + source);
        }
        Path parent = normalized.getParent();
        if (parent == null) throw new IllegalArgumentException("Qualification receipt has no parent directory");
        if (!source.startsWith(parent)) {
            throw new IllegalArgumentException("Qualification source artifact must be inside the receipt directory: "
                    + source);
        }
        String actualHash;
        try {
            actualHash = sha256(source);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Unable to hash qualification source artifact: " + source, failure);
        }
        if (!actualHash.equals(evidence.sourceArtifactSha256())) {
            throw new IllegalArgumentException("Qualification source artifact hash does not match evidence: " + source);
        }

        Path relativeSource = parent.relativize(source);
        String sourceName = relativeSource.toString().replace('\\', '/');
        StringBuilder content = new StringBuilder();
        appendProperty(content, "schemaVersion", Integer.toString(SCHEMA_VERSION));
        appendProperty(content, "contextKey", evidence.contextKey());
        appendProperty(content, "route", evidence.route());
        appendProperty(content, "resultAbi", evidence.resultAbi());
        appendProperty(content, "compilerVersion", evidence.compilerVersion());
        appendProperty(content, "comparedCases", Long.toString(evidence.comparedCases()));
        appendProperty(content, "comparedFields", Long.toString(evidence.comparedFields()));
        appendProperty(content, "mismatches", Long.toString(evidence.mismatches()));
        appendProperty(content, "completeCoverage", Boolean.toString(evidence.completeCoverage()));
        appendProperty(content, "independentOracle", Boolean.toString(evidence.independentOracle()));
        appendProperty(content, "sourceArtifactSha256", evidence.sourceArtifactSha256());
        appendProperty(content, "sourceArtifactFile", sourceName);

        try {
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, normalized.getFileName().toString() + ".", ".tmp");
            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                try {
                    Files.move(temporary, normalized, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, normalized);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Unable to write qualification evidence: " + normalized, failure);
        }
        return load(normalized);
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(
                "Qualification evidence is missing " + key);
        return value.trim();
    }

    private static long longValue(Properties values, String key) {
        String value = required(values, key);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Qualification evidence has invalid " + key, failure);
        }
    }

    private static int integerValue(Properties values, String key) {
        String value = required(values, key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Qualification evidence has invalid " + key, failure);
        }
    }

    private static boolean booleanValue(Properties values, String key) {
        String value = required(values, key);
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException("Qualification evidence has invalid " + key + ": expected true or false");
    }

    private static void appendProperty(StringBuilder output, String key, String value) {
        output.append(key).append('=').append(escapeProperty(value)).append('\n');
    }

    private static String escapeProperty(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '\t' -> escaped.append("\\t");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case ' ', '#', '!', '=', ':' -> escaped.append('\\').append(character);
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static String sha256(Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
}
