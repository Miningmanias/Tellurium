// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.threading;

import com.google.common.collect.MapMaker;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentMap;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Region payloads compressed ahead of the IO worker.
 *
 * <p>The vanilla IO worker serializes and deflates every chunk on its single
 * thread, which caps durable saving well below the generation rate.  For a
 * chunk saved through the asynchronous unload path the same bytes
 * RegionFile.ChunkBuffer would produce (length, compression id, compressed
 * NBT) are built on a worker; the IO thread then only places them in the
 * region file.  A tag without a prepared payload, or a region file using a
 * different compression, takes the original path.</p>
 */
public final class PrecompressedChunks {
    public static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("tellurium.asyncChunkCompress", "true"));

    /** Identity-keyed and weak: a tag that is superseded before it is written just drops its payload. */
    private static final ConcurrentMap<CompoundTag, Payload> PAYLOADS = new MapMaker().weakKeys().makeMap();

    public record Payload(int versionId, byte[] bytes, int length) {
        public ByteBuffer buffer() { return ByteBuffer.wrap(bytes, 0, length); }
    }

    private static final class Buffer extends ByteArrayOutputStream {
        Buffer() { super(8096); }
        byte[] array() { return buf; }
    }

    private PrecompressedChunks() {}

    /** Called on a worker once the tag is complete and before it is handed to the IO worker. */
    public static void prepare(CompoundTag tag) {
        if (!ENABLED) return;
        try {
            RegionFileVersion version = RegionFileVersion.getSelected();
            Buffer buffer = new Buffer();
            for (int i = 0; i < 4; i++) buffer.write(0);
            buffer.write(version.getId());
            try (DataOutputStream out = new DataOutputStream(wrap(version, buffer))) {
                NbtIo.write(tag, out);
            }
            int length = buffer.size();
            byte[] bytes = buffer.array();
            ByteBuffer.wrap(bytes).putInt(0, length - 5 + 1);
            PAYLOADS.put(tag, new Payload(version.getId(), bytes, length));
        } catch (IOException | RuntimeException failure) {
            // no payload: the IO worker compresses this chunk itself
        }
    }

    /**
     * Deflate level for region payloads prepared here; -1 keeps the game's own stream (level 6).
     * Any level is a standard zlib stream the unmodified game reads back to the same chunk; lower
     * levels trade a larger file for less CPU.  At generation rates deflate level 6 costs about as
     * much CPU as eight worker threads, so the default is level 1 (region files about 12% larger).
     */
    private static final int DEFLATE_LEVEL = Integer.getInteger("tellurium.asyncChunkCompressLevel", 1);

    private static OutputStream wrap(RegionFileVersion version, OutputStream out) throws IOException {
        if (DEFLATE_LEVEL >= 0 && DEFLATE_LEVEL <= 9 && version == RegionFileVersion.VERSION_DEFLATE) {
            Deflater deflater = new Deflater(DEFLATE_LEVEL);
            return new BufferedOutputStream(new DeflaterOutputStream(out, deflater) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        deflater.end();
                    }
                }
            });
        }
        return version.wrap(out);
    }

    public static Payload take(CompoundTag tag) {
        return ENABLED ? PAYLOADS.remove(tag) : null;
    }
}
