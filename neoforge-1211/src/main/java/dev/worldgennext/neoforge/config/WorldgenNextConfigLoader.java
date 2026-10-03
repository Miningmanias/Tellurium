// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.Properties;

/** Loads the installed-server configuration without initializing any native library. */
public final class WorldgenNextConfigLoader {
    public static final String FILE_NAME = "worldgennext.properties";

    private WorldgenNextConfigLoader() {}

    public record Loaded(WorldgenNextConfig config, Path file, boolean filePresent) {
        public Loaded {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(file, "file");
            file = file.toAbsolutePath().normalize();
        }
    }

    /**
     * Reads the one deliberately supported installed-mode file. Missing configuration is a normal
     * first-run condition and uses the immutable product defaults.
     */
    public static Loaded load(Path configDirectory) {
        Objects.requireNonNull(configDirectory, "configDirectory");
        Path directory = configDirectory.toAbsolutePath().normalize();
        Path file = directory.resolve(FILE_NAME).normalize();
        if (Files.notExists(file)) return new Loaded(WorldgenNextConfig.defaults(), file, false);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("WorldgenNext configuration is not a regular file: " + file);
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read WorldgenNext configuration: " + file, failure);
        }
        try {
            return new Loaded(WorldgenNextConfig.from(properties), file, true);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid WorldgenNext configuration in " + file + ": "
                    + failure.getMessage(), failure);
        }
    }

    /**
     * Writes a deterministic default file for an operator who explicitly
     * requested one. Existing files, symlinks and directories are never
     * replaced; a running runtime is not reconfigured by this method.
     */
    public static Path writeDefaults(Path configDirectory) {
        Objects.requireNonNull(configDirectory, "configDirectory");
        Path directory = configDirectory.toAbsolutePath().normalize();
        Path file = directory.resolve(FILE_NAME).normalize();
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException("Refusing to overwrite existing WorldgenNext configuration: " + file);
        }
        WorldgenNextConfig defaults = WorldgenNextConfig.defaults();
        Properties properties = defaults.toProperties();
        StringBuilder content = new StringBuilder();
        properties.stringPropertyNames().stream()
                .sorted(Comparator.naturalOrder())
                .forEach(key -> content.append(key).append('=').append(properties.getProperty(key)).append('\n'));
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, FILE_NAME + ".", ".tmp");
            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, file);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Unable to write default WorldgenNext configuration: " + file, failure);
        }
        return file;
    }
}
