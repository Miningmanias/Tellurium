// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.fast;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chunks whose SURFACE result was already produced by the fused kernels and
 * written together with NOISE.  The SURFACE step is skipped for them; the mark
 * is persisted in the chunk tag while the chunk is saved at NOISE status so a
 * reload does not run the surface rules a second time.
 */
public final class FastSurfaceState {
    private static final Logger LOG = LoggerFactory.getLogger("worldgennext-fast");
    public static final String TAG = "worldgennext.surface_applied";
    /** Diagnostic: generate NOISE and SURFACE with the original code and compare the kernels' result block by block. */
    public static final boolean VERIFY = Boolean.getBoolean("worldgennext.fast.surfaceVerify") || Boolean.getBoolean("worldgennext.fast.check");

    private static final Set<ChunkAccess> APPLIED = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private static final Map<ChunkAccess, Expected> EXPECTED = Collections.synchronizedMap(new WeakHashMap<>());
    static final AtomicLong verified = new AtomicLong(), mismatched = new AtomicLong();
    private static final AtomicInteger reports = new AtomicInteger();

    private record Expected(byte[] data, int[] heights, BlockState[] palette) {}

    private FastSurfaceState() {}

    public static void mark(ChunkAccess chunk) { APPLIED.add(chunk); }

    public static boolean applied(ChunkAccess chunk) { return APPLIED.contains(chunk); }

    /** Developer fault injection: corrupt the GPU result of one chunk in eight, to exercise the reporting of differences. */
    private static final boolean INJECT_DIFFERENCE = Boolean.getBoolean("worldgennext.fast.checkInjectDifference");

    static void expect(ChunkAccess chunk, byte[] data, int[] heights, BlockState[] palette) {
        if (INJECT_DIFFERENCE && (chunk.getPos().x & 7) == 0) data[0] ^= 1;
        EXPECTED.put(chunk, new Expected(data, heights, palette));
    }

    /** Called after the original SURFACE step ran on a chunk in verify mode. */
    public static void verify(ChunkAccess chunk) {
        Expected expected = EXPECTED.remove(chunk);
        if (expected == null) return;
        verified.incrementAndGet();
        int minY = chunk.getMinBuildHeight();
        int mismatches = 0;
        StringBuilder first = new StringBuilder();
        for (int ly = 0; ly < chunk.getHeight(); ly++) {
            LevelChunkSection section = chunk.getSection(ly >> 4);
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState vanilla = section.getBlockState(x, ly & 15, z);
                int raw = expected.data()[(ly * 16 + z) * 16 + x];
                BlockState gpu = expected.palette()[raw & 0x7F];
                if (vanilla != gpu) {
                    if (mismatches++ < 4) first.append(" (").append(chunk.getPos().getMinBlockX() + x).append(',').append(minY + ly).append(',')
                            .append(chunk.getPos().getMinBlockZ() + z).append(") vanilla ").append(vanilla).append(" gpu ").append(gpu).append(';');
                }
            }
        }
        if (mismatches > 0) {
            mismatched.incrementAndGet();
            if (reports.getAndIncrement() < 40) {
                LOG.warn("Fast GPU SURFACE verify mismatch at chunk {}: {} blocks, first{}", chunk.getPos(), mismatches, first);
            }
        }
    }
}
