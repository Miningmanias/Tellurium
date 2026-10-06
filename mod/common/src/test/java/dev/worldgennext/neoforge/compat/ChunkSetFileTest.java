// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSetFileTest {
    @TempDir
    Path directory;

    private static void write(Path file, long... positions) throws IOException {
        ChunkSetFile.write(file, positions.length, Arrays.stream(positions).iterator());
    }

    private static Set<Long> read(Path file) throws IOException {
        Set<Long> positions = new HashSet<>();
        ChunkSetFile.read(file, positions::add);
        return positions;
    }

    @Test
    void whatIsWrittenIsReadBackIncludingNegativeAndExtremePositions() throws IOException {
        Path file = directory.resolve("nested").resolve("set.bin");
        write(file, 0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x7FFFFFFF_80000000L, 42L);
        assertEquals(Set.of(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x7FFFFFFF_80000000L, 42L), read(file));
        assertFalse(Files.exists(file.resolveSibling("set.bin.tmp")));
    }

    @Test
    void aMissingFileIsAnEmptySetAndAnEmptySetRoundTrips() throws IOException {
        assertTrue(read(directory.resolve("absent.bin")).isEmpty());
        write(directory.resolve("empty.bin"));
        assertTrue(read(directory.resolve("empty.bin")).isEmpty());
    }

    @Test
    void writingReplacesTheOldContentCompletely() throws IOException {
        Path file = directory.resolve("set.bin");
        write(file, 1, 2, 3, 4, 5);
        write(file, 9);
        assertEquals(Set.of(9L), read(file));
    }

    @Test
    void aTruncatedFileOrOneWithTrailingDataIsRejected() throws IOException {
        Path file = directory.resolve("set.bin");
        write(file, 1, 2, 3);
        byte[] good = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(good, good.length - 3));
        assertThrows(IOException.class, () -> read(file));
        Files.write(file, Arrays.copyOf(good, good.length + 1));
        assertThrows(IOException.class, () -> read(file));
        Files.write(file, new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
        assertThrows(IOException.class, () -> read(file));
    }
}
