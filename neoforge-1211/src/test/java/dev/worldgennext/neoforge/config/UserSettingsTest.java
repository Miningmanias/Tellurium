// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserSettingsTest {
    @TempDir
    Path configDirectory;

    private UserSettings with(String content) throws Exception {
        Files.writeString(configDirectory.resolve(UserSettings.FILE_NAME), content);
        return UserSettings.read(configDirectory);
    }

    @Test
    void firstStartWritesTheDefaultsAndTheWrittenFileReadsBackAsDefaults() {
        UserSettings first = UserSettings.read(configDirectory);
        assertTrue(first.created());
        assertTrue(Files.isRegularFile(first.file()));

        UserSettings second = UserSettings.read(configDirectory);
        assertFalse(second.created());
        assertEquals(first.systemProperties(), second.systemProperties());
        assertTrue(second.problems().isEmpty(), second.problems().toString());
        assertTrue(second.enabled());
        assertEquals("auto", second.gpuMode());
        assertEquals(1, second.compressionLevel());
        assertEquals(1024, second.pregenInFlight());
        assertEquals("auto", second.systemProperties().get("worldgennext.fast.gpu"));
    }

    @Test
    void tablesCommentsAndQuotingAreUnderstood() throws Exception {
        UserSettings settings = with("""
                enabled = true   # trailing comment
                [gpu]
                mode = "force"   # "auto" is the default
                [saving]
                async = false
                compression_level = 6
                [pregen]
                in_flight = 2_048
                """);
        assertTrue(settings.problems().isEmpty(), settings.problems().toString());
        assertEquals("force", settings.gpuMode());
        assertFalse(settings.asyncSaving());
        assertEquals(6, settings.compressionLevel());
        assertEquals(2048, settings.pregenInFlight());
        Map<String, String> published = settings.systemProperties();
        assertEquals("force", published.get("worldgennext.fast.gpu"));
        assertEquals("false", published.get("worldgennext.asyncChunkSave"));
        assertEquals("false", published.get("worldgennext.asyncChunkCompress"));
        assertEquals("6", published.get("worldgennext.asyncChunkCompressLevel"));
    }

    @Test
    void checkModeRunsTheKernelsForAnyGeneratorAndTurnsTheComparisonOn() throws Exception {
        UserSettings settings = with("""
                [gpu]
                mode = "check"
                """);
        assertTrue(settings.problems().isEmpty(), settings.problems().toString());
        assertEquals("check", settings.gpuMode());
        assertEquals("force", settings.systemProperties().get("worldgennext.fast.gpu"));
        assertEquals("true", settings.systemProperties().get("worldgennext.fast.check"));
        // No other mode asks for the comparison.
        UserSettings forced = with("""
                [gpu]
                mode = "force"
                """);
        assertFalse(forced.systemProperties().containsKey("worldgennext.fast.check"));
    }

    @Test
    void chunkyIsGivenThePregeneratorsWindowUnlessToldNotTo() throws Exception {
        UserSettings defaults = UserSettings.read(configDirectory);
        assertEquals(1024, defaults.chunkyInFlight(16384L << 20));
        assertEquals(170, defaults.chunkyInFlight(2048L << 20));
        assertEquals(32, defaults.chunkyInFlight(128L << 20));
        assertTrue(defaults.systemProperties().containsKey("chunky.maxWorkingCount"));

        UserSettings off = with("""
                [pregen]
                in_flight = 300
                tune_chunky = false
                """);
        assertTrue(off.problems().isEmpty(), off.problems().toString());
        assertFalse(off.systemProperties().containsKey("chunky.maxWorkingCount"));
        assertEquals(300, off.chunkyInFlight(16384L << 20));
    }

    @Test
    void distantHorizonsGeneratorIsHybridUnlessSetAndAFileWithoutTheOptionKeepsTheDefault() throws Exception {
        assertEquals("hybrid", UserSettings.read(configDirectory).systemProperties().get("worldgennext.dh.mode"));
        // A file written by an earlier version has no such table.
        UserSettings older = with("""
                enabled = true
                """);
        assertTrue(older.problems().isEmpty(), older.problems().toString());
        assertEquals("hybrid", older.systemProperties().get("worldgennext.dh.mode"));

        for (String mode : new String[]{"direct", "off", "hybrid"}) {
            UserSettings chosen = with("[distant_horizons]\ngenerator = \"" + mode + "\"\n");
            assertTrue(chosen.problems().isEmpty(), chosen.problems().toString());
            assertEquals(mode, chosen.systemProperties().get("worldgennext.dh.mode"));
        }

        UserSettings wrong = with("""
                [distant_horizons]
                generator = "fastest"
                """);
        assertEquals("hybrid", wrong.systemProperties().get("worldgennext.dh.mode"));
        assertTrue(String.join("\n", wrong.problems()).contains("distant_horizons.generator"), wrong.problems().toString());
    }

    @Test
    void invalidValuesFallBackToDefaultsAndAreReported() throws Exception {
        UserSettings settings = with("""
                [gpu]
                mode = "turbo"
                [saving]
                compression_level = 42
                async = maybe
                [typo]
                speed = 11
                this line is not an assignment
                """);
        assertEquals("auto", settings.gpuMode());
        assertEquals(1, settings.compressionLevel());
        assertTrue(settings.asyncSaving());
        assertEquals("auto", settings.systemProperties().get("worldgennext.fast.gpu"));
        String problems = String.join("\n", settings.problems());
        assertTrue(problems.contains("gpu.mode"), problems);
        assertTrue(problems.contains("saving.compression_level"), problems);
        assertTrue(problems.contains("saving.async"), problems);
        assertTrue(problems.contains("typo.speed"), problems);
        assertTrue(problems.contains("line 8"), problems);
    }

    @Test
    void disablingTurnsOffEverySwitchAndNothingElseIsPublished() throws Exception {
        UserSettings settings = with("""
                enabled = false
                [gpu]
                mode = "force"
                """);
        Map<String, String> published = settings.systemProperties();
        assertEquals("off", published.get("worldgennext.fast.gpu"));
        assertEquals("false", published.get("worldgennext.parallelFeatures"));
        assertEquals("false", published.get("worldgennext.asyncChunkSave"));
        assertEquals("false", published.get("worldgennext.parallelMailboxThreads"));
        assertEquals("1", published.get("worldgennext.asyncIoMailboxBatch"));
        for (Map.Entry<String, String> entry : published.entrySet()) {
            assertTrue(entry.getValue().equals("false") || entry.getValue().equals("off") || entry.getValue().equals("1"),
                    entry.toString());
        }
    }

    @Test
    void anUnreadableFileGivesDefaultsAndSaysSo() throws Exception {
        // A directory where the file should be cannot be read as one.
        Files.createDirectory(configDirectory.resolve(UserSettings.FILE_NAME));
        UserSettings settings = UserSettings.read(configDirectory);
        assertTrue(settings.enabled());
        assertEquals("auto", settings.gpuMode());
        assertEquals(1, settings.problems().size(), settings.problems().toString());
        assertTrue(settings.problems().get(0).contains("could not read"));
    }
}
