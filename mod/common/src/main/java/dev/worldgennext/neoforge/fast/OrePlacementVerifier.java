// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.fast;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Differential check of {@link FastOrePlacement} against the original
 * OreFeature.doPlace on live veins ({@code -Dworldgennext.fast.oreVerify=true}).
 *
 * <p>The original runs first with its random draws recorded; the blocks of the
 * vein's box are snapshotted and restored; the replacement then runs on the
 * recorded draws.  The two must make the same draws in the same order, return
 * the same value, and leave every block of the box (plus a margin) equal.</p>
 */
public final class OrePlacementVerifier {
    public static final boolean ENABLED = Boolean.getBoolean("worldgennext.fast.oreVerify");
    private static final Logger LOGGER = LoggerFactory.getLogger("worldgennext-fast");
    private static final AtomicLong VERIFIED = new AtomicLong();
    private static final AtomicLong MISMATCHES = new AtomicLong();
    private static final int MARGIN = 2;

    static {
        if (ENABLED) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("[worldgennext] Ore placement verification total: "
                    + VERIFIED.get() + " veins identical, " + MISMATCHES.get() + " mismatches"), "worldgennext-ore-verify-summary"));
        }
    }

    private OrePlacementVerifier() {}

    public static long verified() { return VERIFIED.get(); }
    public static long mismatches() { return MISMATCHES.get(); }

    public static boolean run(WorldGenLevel level, RandomSource random, OreConfiguration config,
                              double minXCentre, double maxXCentre, double minZCentre, double maxZCentre,
                              double minYCentre, double maxYCentre, int x, int y, int z, int width, int height,
                              Function<RandomSource, Boolean> original) {
        int sx = width + 2 * MARGIN, sy = height + 2 * MARGIN, sz = width + 2 * MARGIN;
        int ox = x - MARGIN, oy = y - MARGIN, oz = z - MARGIN;
        BlockState[] before = snapshot(level, ox, oy, oz, sx, sy, sz);

        Recording recording = new Recording(random);
        boolean expected = original.apply(recording);
        BlockState[] afterOriginal = snapshot(level, ox, oy, oz, sx, sy, sz);

        // Put the box back the way the original found it.
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int index = 0;
        for (int bx = 0; bx < sx; bx++) {
            for (int by = 0; by < sy; by++) {
                for (int bz = 0; bz < sz; bz++, index++) {
                    if (before[index] != afterOriginal[index]) {
                        pos.set(ox + bx, oy + by, oz + bz);
                        ChunkAccess chunk = level.getChunk(pos);
                        chunk.getSection(chunk.getSectionIndex(pos.getY())).setBlockState(SectionPos.sectionRelative(pos.getX()),
                                SectionPos.sectionRelative(pos.getY()), SectionPos.sectionRelative(pos.getZ()), before[index], false);
                    }
                }
            }
        }

        Replay replay = new Replay(recording.calls);
        String failure = null;
        boolean actual = false;
        try {
            actual = FastOrePlacement.doPlace(level, replay, config, minXCentre, maxXCentre, minZCentre, maxZCentre,
                    minYCentre, maxYCentre, x, y, z, width, height);
        } catch (IllegalStateException divergence) {
            failure = divergence.getMessage();
        }
        if (failure == null && replay.next != recording.calls.size()) {
            failure = "replacement made " + replay.next / 2 + " random draws, original " + recording.calls.size() / 2;
        }
        if (failure == null && actual != expected) failure = "result " + actual + ", original " + expected;
        if (failure == null) {
            BlockState[] afterReplacement = snapshot(level, ox, oy, oz, sx, sy, sz);
            for (int i = 0; i < afterReplacement.length; i++) {
                if (afterReplacement[i] != afterOriginal[i]) {
                    failure = "block " + i + " is " + afterReplacement[i] + ", original " + afterOriginal[i];
                    break;
                }
            }
        }
        if (failure != null) {
            MISMATCHES.incrementAndGet();
            throw new IllegalStateException("Ore placement verification failed at " + x + "," + y + "," + z + " size " + config.size + ": " + failure);
        }
        long count = VERIFIED.incrementAndGet();
        if (count % 500_000 == 0) LOGGER.info("Ore placement verification: {} veins identical, {} mismatches", count, MISMATCHES.get());
        return actual;
    }

    private static BlockState[] snapshot(WorldGenLevel level, int ox, int oy, int oz, int sx, int sy, int sz) {
        BlockState[] states = new BlockState[sx * sy * sz];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int index = 0;
        for (int bx = 0; bx < sx; bx++) {
            for (int by = 0; by < sy; by++) {
                for (int bz = 0; bz < sz; bz++) {
                    pos.set(ox + bx, oy + by, oz + bz);
                    // Outside the region's chunks nothing can have been written by either implementation.
                    states[index++] = level.isOutsideBuildHeight(pos.getY()) || !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                            ? null : level.getBlockState(pos);
                }
            }
        }
        return states;
    }

    private static final int INT = 1, INT_BOUND = 2, LONG = 3, BOOLEAN = 4, FLOAT = 5, DOUBLE = 6, GAUSSIAN = 7;

    /** Passes draws through and records (kind, bits) pairs. */
    private static final class Recording implements RandomSource {
        final RandomSource delegate;
        final LongArrayList calls = new LongArrayList();

        Recording(RandomSource delegate) { this.delegate = delegate; }

        private void record(long kind, long bits) { calls.add(kind); calls.add(bits); }

        @Override public RandomSource fork() { throw new UnsupportedOperationException("fork during ore placement"); }
        @Override public PositionalRandomFactory forkPositional() { throw new UnsupportedOperationException("forkPositional during ore placement"); }
        @Override public void setSeed(long seed) { throw new UnsupportedOperationException("setSeed during ore placement"); }
        @Override public int nextInt() { int v = delegate.nextInt(); record(INT, v); return v; }
        @Override public int nextInt(int bound) { int v = delegate.nextInt(bound); record(INT_BOUND | (long) bound << 8, v); return v; }
        @Override public long nextLong() { long v = delegate.nextLong(); record(LONG, v); return v; }
        @Override public boolean nextBoolean() { boolean v = delegate.nextBoolean(); record(BOOLEAN, v ? 1 : 0); return v; }
        @Override public float nextFloat() { float v = delegate.nextFloat(); record(FLOAT, Float.floatToRawIntBits(v)); return v; }
        @Override public double nextDouble() { double v = delegate.nextDouble(); record(DOUBLE, Double.doubleToRawLongBits(v)); return v; }
        @Override public double nextGaussian() { double v = delegate.nextGaussian(); record(GAUSSIAN, Double.doubleToRawLongBits(v)); return v; }
    }

    /** Hands back recorded draws; a different kind or an extra draw is a divergence. */
    private static final class Replay implements RandomSource {
        final LongArrayList calls;
        int next;

        Replay(LongArrayList calls) { this.calls = calls; }

        private long take(long kind) {
            if (next >= calls.size()) throw new IllegalStateException("replacement made an extra random draw (#" + next / 2 + ")");
            long recorded = calls.getLong(next);
            if (recorded != kind) throw new IllegalStateException("random draw #" + next / 2 + " is of kind " + kind + ", original " + recorded);
            long bits = calls.getLong(next + 1);
            next += 2;
            return bits;
        }

        @Override public RandomSource fork() { throw new IllegalStateException("fork during ore placement"); }
        @Override public PositionalRandomFactory forkPositional() { throw new IllegalStateException("forkPositional during ore placement"); }
        @Override public void setSeed(long seed) { throw new IllegalStateException("setSeed during ore placement"); }
        @Override public int nextInt() { return (int) take(INT); }
        @Override public int nextInt(int bound) { return (int) take(INT_BOUND | (long) bound << 8); }
        @Override public long nextLong() { return take(LONG); }
        @Override public boolean nextBoolean() { return take(BOOLEAN) != 0; }
        @Override public float nextFloat() { return Float.intBitsToFloat((int) take(FLOAT)); }
        @Override public double nextDouble() { return Double.longBitsToDouble(take(DOUBLE)); }
        @Override public double nextGaussian() { return Double.longBitsToDouble(take(GAUSSIAN)); }
    }
}
