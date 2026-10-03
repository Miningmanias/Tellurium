// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldgenNextConfigLoaderTest {
    @TempDir
    Path configDirectory;

    @Test
    void missingInstalledConfigUsesDefaultsWithoutCreatingNativeState() {
        var loaded = WorldgenNextConfigLoader.load(configDirectory);

        assertFalse(loaded.filePresent());
        assertEquals(WorldgenNextConfig.defaults(), loaded.config());
        assertEquals(configDirectory.resolve(WorldgenNextConfigLoader.FILE_NAME).toAbsolutePath().normalize(), loaded.file());
        assertFalse(Files.exists(loaded.file()));
    }

    @Test
    void installedPropertiesSelectCpuOnlyMode() throws Exception {
        Path file = configDirectory.resolve(WorldgenNextConfigLoader.FILE_NAME);
        Files.writeString(file, "mode=CPU_ONLY\n"
                + "hostBudgetBytes=4096\n"
                + "nativeBudgetBytes=8192\n"
                + "queueCapacity=2\n"
                + "requestTimeoutMillis=250\n"
                + "enableQualifiedHook=false\n");

        var loaded = WorldgenNextConfigLoader.load(configDirectory);

        assertTrue(loaded.filePresent());
        assertEquals(WorldgenNextConfig.Mode.CPU_ONLY, loaded.config().mode());
        assertEquals(4096L, loaded.config().hostBudgetBytes());
        assertEquals(8192L, loaded.config().nativeBudgetBytes());
        assertEquals(250L, loaded.config().requestTimeout().toMillis());
    }

    @Test
    void aDirectoryCannotMasqueradeAsTheInstalledConfig() throws Exception {
        Files.createDirectory(configDirectory.resolve(WorldgenNextConfigLoader.FILE_NAME));

        assertThrows(IllegalArgumentException.class, () -> WorldgenNextConfigLoader.load(configDirectory));
    }

    @Test
    void defaultWriterIsDeterministicAndRefusesASecondWrite() throws Exception {
        Path file = WorldgenNextConfigLoader.writeDefaults(configDirectory);
        String content = Files.readString(file);
        assertTrue(content.contains("mode=AUTO_SUPPORTED\n"));
        assertTrue(content.contains("enableQualifiedHook=false\n"));
        assertEquals(content, Files.readString(file));
        assertThrows(IllegalArgumentException.class, () -> WorldgenNextConfigLoader.writeDefaults(configDirectory));
        assertEquals(WorldgenNextConfig.defaults(), WorldgenNextConfigLoader.load(configDirectory).config());
    }
}
