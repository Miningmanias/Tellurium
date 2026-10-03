// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.jvm.worldgen;

import dev.worldgennext.semantic.snapshot.StructureBlendSnapshot;
import java.util.List;
import java.util.Objects;

/**
 * Scalar replay of Minecraft 1.21.1's {@code Blender.blendDensity}.
 *
 * <p>The loader captures the old-generation values as immutable quart/cell
 * samples.  Keeping the distance and weighted accumulation here makes the
 * pure evaluator independent of {@code Blender}, {@code BlendingData} and
 * their mutable chunk lookups.</p>
 */
public final class BlendDensityEvaluator {
    private static final double NO_VALUE = Double.MAX_VALUE;

    /**
     * Applies the captured blend to the supplied current density.  Empty or
     * nonmatching captures are deliberately identity operations, matching
     * Minecraft's {@code Blender.EMPTY} behavior.
     */
    public double blend(StructureBlendSnapshot snapshot, int blockX, int blockY, int blockZ,
                        double currentDensity) {
        Objects.requireNonNull(snapshot, "snapshot");
        int quartX = Math.floorDiv(blockX, 4);
        // Minecraft's Blender uses Java integer division here, rather than
        // QuartPos.fromBlock/floor division, because the input is a cell Y.
        int cellY = blockY / 8;
        int quartZ = Math.floorDiv(blockZ, 4);

        double direct = directValue(snapshot, quartX, cellY, quartZ);
        if (direct != NO_VALUE) return direct;

        double weighted = 0.0;
        double weights = 0.0;
        double closest = Double.POSITIVE_INFINITY;
        for (var sample : snapshot.densitySamples()) {
            // Blender calls iterateDensities(..., cellY - 1, cellY + 1),
            // while BlendingData treats the upper bound as exclusive.
            // Therefore only the two values cellY-1 and cellY participate.
            if (sample.cellY() < cellY - 1 || sample.cellY() > cellY) continue;
            int deltaY = (cellY - sample.cellY()) * 2;
            double distance = Math.sqrt((double) (quartX - sample.quartX()) * (quartX - sample.quartX())
                    + (double) deltaY * deltaY
                    + (double) (quartZ - sample.quartZ()) * (quartZ - sample.quartZ()));
            if (!(distance > 2.0)) {
                if (distance < closest) closest = distance;
                if (distance == 0.0) return sample.density();
                double distanceSquared = distance * distance;
                double weight = 1.0 / (distanceSquared * distanceSquared);
                weighted += sample.density() * weight;
                weights += weight;
            }
        }
        if (closest == Double.POSITIVE_INFINITY) return currentDensity;
        double oldDensity = weighted / weights;
        double blend = Math.max(0.0, Math.min(1.0, closest / 3.0));
        // Mth.lerp(a, b, c) = b + a * (c - b).
        return oldDensity + blend * (currentDensity - oldDensity);
    }

    private static double directValue(StructureBlendSnapshot snapshot, int quartX, int cellY, int quartZ) {
        if (!snapshot.directDensitySamples().isEmpty()) {
            int sectionX = Math.floorDiv(quartX, 4);
            int sectionZ = Math.floorDiv(quartZ, 4);
            int localX = quartX - sectionX * 4;
            int localZ = quartZ - sectionZ * 4;
            double direct = directValue(snapshot.directDensitySamples(), sectionX, sectionZ,
                    localX, cellY, localZ);
            if (direct != NO_VALUE) return direct;
            if (localX == 0 && localZ == 0) {
                direct = directValue(snapshot.directDensitySamples(), sectionX - 1, sectionZ - 1,
                        4, cellY, 4);
                if (direct != NO_VALUE) return direct;
            }
            if (localX == 0) {
                direct = directValue(snapshot.directDensitySamples(), sectionX - 1, sectionZ,
                        4, cellY, localZ);
                if (direct != NO_VALUE) return direct;
            }
            if (localZ == 0) {
                direct = directValue(snapshot.directDensitySamples(), sectionX, sectionZ - 1,
                        localX, cellY, 4);
                if (direct != NO_VALUE) return direct;
            }
            return NO_VALUE;
        }
        for (var sample : snapshot.densitySamples()) {
            if (sample.quartX() == quartX && sample.cellY() == cellY && sample.quartZ() == quartZ) {
                return sample.density();
            }
        }
        return NO_VALUE;
    }

    private static double directValue(List<StructureBlendSnapshot.DirectDensitySample> samples,
                                      int sectionX, int sectionZ, int localX, int cellY, int localZ) {
        for (var sample : samples) {
            if (sample.sectionX() == sectionX && sample.sectionZ() == sectionZ
                    && sample.localX() == localX && sample.cellY() == cellY && sample.localZ() == localZ) {
                return sample.density();
            }
        }
        return NO_VALUE;
    }
}
