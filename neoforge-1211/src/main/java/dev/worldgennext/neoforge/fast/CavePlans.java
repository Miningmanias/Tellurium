// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.fast;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cave systems, walked once per starting chunk instead of once per chunk they might reach.
 *
 * <p>Carving a chunk asks every chunk within eight chunks of it whether a cave
 * system starts there and, if so, walks all of that system's tunnels step by
 * step, carving the steps that touch the chunk.  Where a tunnel goes is decided
 * by a random source seeded from the starting chunk alone, so the 289 chunks
 * around a starting chunk each repeat the same walk.</p>
 *
 * <p>A plan is that walk recorded: for each system its floor level, its rooms,
 * and its tunnels as the ellipsoids that would be offered for carving, in
 * order, with the two branches a tunnel may end in.  Carving a chunk replays
 * the plan with the same reach test and the same carving call per step, so the
 * same ellipsoids are offered to the same chunk in the same order.</p>
 */
public final class CavePlans {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("worldgennext.fast.cavePlans", "true"));
    private static final int GENERATION_LIMIT = 2048;

    /**
     * What a plan depends on: the level's random state (seed and heights), the carver and its configuration,
     * the starting chunk, and the random source's state when carving was called.
     */
    public record Key(Object randomState, Object carver, Object configuration, long startChunk, long randomSeed) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && randomState == key.randomState && carver == key.carver
                    && configuration == key.configuration && startChunk == key.startChunk && randomSeed == key.randomSeed;
        }

        @Override
        public int hashCode() {
            int hash = System.identityHashCode(randomState) * 31 + System.identityHashCode(configuration);
            hash = hash * 31 + Long.hashCode(startChunk);
            return hash * 31 + Long.hashCode(randomSeed);
        }
    }

    /**
     * The x/z range of chunk centres an ellipsoid, tunnel or cave system can carve into.  The carving call
     * itself does nothing unless the chunk's centre is within 16 + 2 * radius blocks of the ellipsoid's
     * centre on both axes; a box over all ellipsoids of a tunnel (with its branches) therefore tells, without
     * visiting them, that none of them carves a chunk outside it.  One block of slack keeps the box test on
     * the safe side of the per-ellipsoid test's rounding.
     */
    public static final class Bounds {
        private double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        private double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;

        public void include(double x, double z, double horizontalRadius) {
            double reach = 16.0 + horizontalRadius * 2.0 + 1.0;
            minX = Math.min(minX, x - reach);
            maxX = Math.max(maxX, x + reach);
            minZ = Math.min(minZ, z - reach);
            maxZ = Math.max(maxZ, z + reach);
        }

        public void include(Bounds other) {
            minX = Math.min(minX, other.minX);
            maxX = Math.max(maxX, other.maxX);
            minZ = Math.min(minZ, other.minZ);
            maxZ = Math.max(maxZ, other.maxZ);
        }

        /** False only when nothing inside can carve a chunk with this centre. */
        public boolean mayCarve(double chunkMiddleX, double chunkMiddleZ) {
            return chunkMiddleX >= minX && chunkMiddleX <= maxX && chunkMiddleZ >= minZ && chunkMiddleZ <= maxZ;
        }
    }

    /** One cave system: rooms and tunnels in the order the original carves them. */
    public record Cave(double floorLevel, List<Object> parts, Bounds bounds) {}

    /** A room: one ellipsoid, carved without a reach test. */
    public record Room(double x, double y, double z, double horizontalRadius, double verticalRadius) {}

    /**
     * A tunnel: per offered step its index and ellipsoid; {@code first}/{@code second} are the branches it
     * splits into, or null.  {@code steps} holds x, y, z, horizontal radius, vertical radius per step.
     * {@code bounds} covers the steps and both branches.
     */
    public record Tunnel(float thickness, int stepCount, int[] stepIndex, double[] steps, Tunnel first, Tunnel second, Bounds bounds) {}

    // Two generations: when the young one fills up it becomes the old one, and plans still in use move back on access.
    private static volatile ConcurrentHashMap<Key, List<Cave>> YOUNG = new ConcurrentHashMap<>();
    private static volatile ConcurrentHashMap<Key, List<Cave>> OLD = new ConcurrentHashMap<>();

    private CavePlans() {}

    public static List<Cave> get(Key key) {
        List<Cave> plan = YOUNG.get(key);
        if (plan != null) return plan;
        plan = OLD.get(key);
        if (plan != null) put(key, plan);
        return plan;
    }

    public static void put(Key key, List<Cave> plan) {
        ConcurrentHashMap<Key, List<Cave>> young = YOUNG;
        if (young.size() >= GENERATION_LIMIT) {
            synchronized (CavePlans.class) {
                if (YOUNG == young) {
                    OLD = young;
                    YOUNG = new ConcurrentHashMap<>();
                }
            }
        }
        YOUNG.put(key, plan);
    }

    /** Server stop: plans belong to the level that is going away. */
    public static void clear() {
        YOUNG = new ConcurrentHashMap<>();
        OLD = new ConcurrentHashMap<>();
    }
}
