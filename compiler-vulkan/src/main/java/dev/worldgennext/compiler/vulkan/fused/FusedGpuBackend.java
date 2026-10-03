// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan.fused;

import java.nio.ByteBuffer;

/**
 * Loader-neutral view of the native fused NOISE runtime.  The implementation
 * lives with the Vulkan bindings and may be loaded through an isolated class
 * loader; callers only see JDK types and this module's records.
 */
public interface FusedGpuBackend extends AutoCloseable {
    int CHUNK_INFO_INTS = 8;

    String deviceName();
    boolean lost();
    String lostReason();
    Program load(FusedNoiseCompiler.Compiled compiled, int maxBatchChunks, int slotCount);
    boolean profiling();
    boolean debugBuffers();
    String profileSummary();
    @Override void close();

    interface Program {
        FusedNoiseCompiler.Compiled compiled();
        int slotCount();
        Slot slot(int index);
        long compileNanos();
    }

    /** One in-flight batch; its buffers stay owned until the fence signals. */
    interface Slot {
        boolean pending();
        ByteBuffer chunkInfo();
        ByteBuffer beardData();
        /** Per chunk, 31x31 indices into the batch's unique preliminary-surface column list. */
        ByteBuffer prelimIndex();
        /** Unique quart-aligned column block coordinates (x, z pairs) for this batch. */
        ByteBuffer prelimColumns();
        int prelimColumnCapacity();
        /** Per chunk, 16-bit biome ids over the 6x6 quart-column window around the chunk (surface stage input). */
        ByteBuffer biomes();
        /** @param surface run the surface kernels (chunks opt in through their ChunkInfo surface header) */
        void submit(int count, int prelimCount, boolean surface);
        boolean poll();
        ByteBuffer blocks();
        ByteBuffer heights();
        /** Per chunk, eight ints per aquifer cell (x, y, z, fluid level, fluid type code, unused); valid when aquifers are compiled in. */
        ByteBuffer aquifers();
        int flags(int chunk);
        ByteBuffer debugColumns();
        ByteBuffer debugCorners();
    }
}
