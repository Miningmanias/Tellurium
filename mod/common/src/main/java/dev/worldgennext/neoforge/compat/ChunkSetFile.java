// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.compat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.PrimitiveIterator;
import java.util.function.LongConsumer;

/** A set of chunk positions kept in a file: a count, then that many packed positions. */
final class ChunkSetFile {
    private ChunkSetFile() {}

    /** Passes on the file's positions.  A missing file has none; a damaged one throws and may have passed some on. */
    static void read(Path file, LongConsumer into) throws IOException {
        if (!Files.isRegularFile(file)) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int count = in.readInt();
            if (count < 0) throw new IOException("negative count " + count);
            for (int i = 0; i < count; i++) into.accept(in.readLong());
            if (in.read() != -1) throw new IOException("data after the last position");
        }
    }

    /** Writes the set to a temporary file next to the target and moves it into place, so a crash leaves the old file. */
    static void write(Path file, int count, PrimitiveIterator.OfLong positions) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
            out.writeInt(count);
            for (int i = 0; i < count; i++) out.writeLong(positions.nextLong());
        }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
