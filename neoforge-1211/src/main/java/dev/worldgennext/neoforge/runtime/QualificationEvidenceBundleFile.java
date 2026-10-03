// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Version-2 properties container for aggregate exact-context qualification.
 * Each entry retains its own source artifact and hash; a bundle cannot use a
 * wildcard or silently reuse evidence from an unlisted context.
 */
public final class QualificationEvidenceBundleFile {
    public static final int SCHEMA_VERSION = QualifiedHookEvidenceBundle.SCHEMA_VERSION;

    private QualificationEvidenceBundleFile() {}

    public record Loaded(QualifiedHookEvidenceBundle bundle, Path file,
                         Map<String, Path> sourceArtifacts) {
        public Loaded {
            Objects.requireNonNull(bundle, "bundle");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(sourceArtifacts, "sourceArtifacts");
            file = file.toAbsolutePath().normalize();
            var normalized = new LinkedHashMap<String, Path>();
            sourceArtifacts.forEach((key, value) -> normalized.put(
                    Objects.requireNonNull(key, "source artifact context"),
                    Objects.requireNonNull(value, "source artifact" ).toAbsolutePath().normalize()));
            sourceArtifacts = Map.copyOf(normalized);
        }
    }

    /** Reads only the schema marker, allowing the mod to route v1/v2 files. */
    public static boolean hasBundleSchema(Path file) {
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
        return Integer.toString(SCHEMA_VERSION).equals(values.getProperty("schemaVersion"));
    }

    public static Loaded load(Path file) {
        Objects.requireNonNull(file, "file");
        Path normalized = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Qualification evidence bundle is not a regular file: " + normalized);
        }
        Properties values = read(normalized);
        if (integerValue(values, "schemaVersion") != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported qualification evidence bundle schema: "
                    + values.getProperty("schemaVersion"));
        }
        int entryCount = integerValue(values, "entryCount");
        if (entryCount <= 0 || entryCount > 256) {
            throw new IllegalArgumentException("Qualification evidence bundle entryCount is outside 1..256");
        }
        var entries = new java.util.ArrayList<QualifiedHookEvidence>(entryCount);
        var artifacts = new LinkedHashMap<String, Path>();
        for (int index = 0; index < entryCount; index++) {
            String prefix = "entry." + index + ".";
            QualifiedHookEvidence evidence = new QualifiedHookEvidence(
                    required(values, prefix + "contextKey"),
                    required(values, prefix + "route").toUpperCase(Locale.ROOT),
                    longValue(values, prefix + "comparedCases"),
                    longValue(values, prefix + "comparedFields"),
                    longValue(values, prefix + "mismatches"),
                    booleanValue(values, prefix + "completeCoverage"),
                    booleanValue(values, prefix + "independentOracle"),
                    required(values, prefix + "sourceArtifactSha256"),
                    required(values, prefix + "resultAbi"),
                    required(values, prefix + "compilerVersion"));
            Path artifact = artifactPath(normalized, required(values, prefix + "sourceArtifactFile"));
            verifyArtifact(evidence, artifact);
            entries.add(evidence);
            artifacts.put(evidence.contextKey(), artifact);
        }
        QualifiedHookEvidenceBundle bundle = new QualifiedHookEvidenceBundle(entries);
        bundle.requireAdmissible();
        return new Loaded(bundle, normalized, artifacts);
    }

    /** Writes and reloads an aggregate receipt using the same fail-closed checks as startup. */
    public static Loaded write(Path file, QualifiedHookEvidenceBundle bundle,
                               Map<String, Path> sourceArtifacts) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(sourceArtifacts, "sourceArtifacts");
        bundle.requireAdmissible();
        Path normalized = file.toAbsolutePath().normalize();
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException("Refusing to overwrite qualification evidence bundle: " + normalized);
        }
        Path parent = normalized.getParent();
        if (parent == null) throw new IllegalArgumentException("Qualification bundle has no parent directory");
        StringBuilder content = new StringBuilder();
        appendProperty(content, "schemaVersion", Integer.toString(SCHEMA_VERSION));
        appendProperty(content, "entryCount", Integer.toString(bundle.entries().size()));
        for (int index = 0; index < bundle.entries().size(); index++) {
            QualifiedHookEvidence evidence = bundle.entries().get(index);
            Path artifact = sourceArtifacts.get(evidence.contextKey());
            if (artifact == null) throw new IllegalArgumentException(
                    "Missing source artifact for qualification context: " + evidence.contextKey());
            artifact = artifact.toAbsolutePath().normalize();
            if (!artifact.startsWith(parent) || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Qualification source artifact must be a regular file inside the bundle directory: " + artifact);
            }
            verifyArtifact(evidence, artifact);
            String prefix = "entry." + index + ".";
            appendProperty(content, prefix + "contextKey", evidence.contextKey());
            appendProperty(content, prefix + "route", evidence.route());
            appendProperty(content, prefix + "resultAbi", evidence.resultAbi());
            appendProperty(content, prefix + "compilerVersion", evidence.compilerVersion());
            appendProperty(content, prefix + "comparedCases", Long.toString(evidence.comparedCases()));
            appendProperty(content, prefix + "comparedFields", Long.toString(evidence.comparedFields()));
            appendProperty(content, prefix + "mismatches", Long.toString(evidence.mismatches()));
            appendProperty(content, prefix + "completeCoverage", Boolean.toString(evidence.completeCoverage()));
            appendProperty(content, prefix + "independentOracle", Boolean.toString(evidence.independentOracle()));
            appendProperty(content, prefix + "sourceArtifactSha256", evidence.sourceArtifactSha256());
            appendProperty(content, prefix + "sourceArtifactFile",
                    parent.relativize(artifact).toString().replace('\\', '/'));
        }
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
            throw new IllegalArgumentException("Unable to write qualification evidence bundle: " + normalized, failure);
        }
        return load(normalized);
    }

    private static Properties read(Path file) {
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            values.load(reader);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Unable to read qualification evidence bundle: " + file, failure);
        }
        return values;
    }

    private static Path artifactPath(Path receipt, String value) {
        Path relative = Path.of(value);
        if (relative.isAbsolute()) throw new IllegalArgumentException(
                "Qualification bundle sourceArtifactFile must be relative: " + value);
        Path parent = receipt.getParent();
        if (parent == null) throw new IllegalArgumentException("Qualification bundle has no parent directory");
        Path artifact = parent.resolve(relative).toAbsolutePath().normalize();
        if (!artifact.startsWith(parent)) throw new IllegalArgumentException(
                "Qualification bundle source artifact escapes its directory: " + value);
        if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Qualification bundle source artifact is not a regular file: " + artifact);
        }
        return artifact;
    }

    private static void verifyArtifact(QualifiedHookEvidence evidence, Path artifact) {
        String actual;
        try {
            actual = sha256(artifact);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Unable to hash qualification source artifact: " + artifact, failure);
        }
        if (!actual.equals(evidence.sourceArtifactSha256())) {
            throw new IllegalArgumentException("Qualification source artifact hash does not match receipt: " + artifact);
        }
    }

    private static String required(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(
                "Qualification evidence bundle is missing " + key);
        return value.trim();
    }

    private static long longValue(Properties values, String key) {
        try {
            return Long.parseLong(required(values, key));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Qualification evidence bundle has invalid " + key, failure);
        }
    }

    private static int integerValue(Properties values, String key) {
        try {
            return Integer.parseInt(required(values, key));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Qualification evidence bundle has invalid " + key, failure);
        }
    }

    private static boolean booleanValue(Properties values, String key) {
        String value = required(values, key);
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException("Qualification evidence bundle has invalid " + key);
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
