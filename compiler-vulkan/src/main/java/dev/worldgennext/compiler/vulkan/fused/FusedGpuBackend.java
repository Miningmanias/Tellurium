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
        void submit(int count);
        boolean poll();
        ByteBuffer blocks();
        ByteBuffer heights();
        int flags(int chunk);
        ByteBuffer debugColumns();
        ByteBuffer debugCorners();
    }
}
