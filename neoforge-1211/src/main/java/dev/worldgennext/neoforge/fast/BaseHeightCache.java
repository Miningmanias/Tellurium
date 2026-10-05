// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.fast;

import java.util.concurrent.atomic.LongAdder;

/**
 * Remembers answers of NoiseBasedChunkGenerator.getBaseHeight.
 *
 * <p>Structure placement asks for the terrain height at single positions, and
 * each answer costs a NoiseChunk of its own and a top-down walk of the density
 * column.  Jigsaw structures ask again and again for the same few positions
 * while they try candidate pieces.  The answer depends only on the generator,
 * the level's random state and heights, the position and the heightmap type,
 * so it is kept in a small table per thread, keyed by all of those.</p>
 */
public final class BaseHeightCache {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("worldgennext.fast.heightCache", "true"));
    private static final int SIZE = 2048;

    public static final LongAdder HITS = new LongAdder(), MISSES = new LongAdder();

    private static final class Table {
        final Object[] generator = new Object[SIZE], randomState = new Object[SIZE];
        final long[] position = new long[SIZE];
        /** Heightmap type ordinal, minimum build height and height, packed. */
        final long[] context = new long[SIZE];
        final int[] value = new int[SIZE];
    }

    private static final ThreadLocal<Table> TABLE = ThreadLocal.withInitial(Table::new);

    private BaseHeightCache() {}

    private static int slot(long position, long context) {
        long h = position * 0x9E3779B97F4A7C15L + context;
        return (int) (h >>> 40) & (SIZE - 1);
    }

    private static long position(int x, int z) {
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }

    private static long context(int type, int minBuildHeight, int height) {
        return (long) type << 48 | ((long) minBuildHeight & 0xFFFFFFL) << 24 | (long) height & 0xFFFFFFL;
    }

    /** The remembered height, or Integer.MIN_VALUE. */
    public static int get(Object generator, Object randomState, int x, int z, int type, int minBuildHeight, int height) {
        Table table = TABLE.get();
        long position = position(x, z), context = context(type, minBuildHeight, height);
        int slot = slot(position, context);
        if (table.generator[slot] == generator && table.randomState[slot] == randomState && table.position[slot] == position
                && table.context[slot] == context) {
            HITS.increment();
            return table.value[slot];
        }
        MISSES.increment();
        return Integer.MIN_VALUE;
    }

    public static void put(Object generator, Object randomState, int x, int z, int type, int minBuildHeight, int height, int value) {
        Table table = TABLE.get();
        long position = position(x, z), context = context(type, minBuildHeight, height);
        int slot = slot(position, context);
        table.generator[slot] = generator;
        table.randomState[slot] = randomState;
        table.position[slot] = position;
        table.context[slot] = context;
        table.value[slot] = value;
    }
}
