// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.jvm.worldgen;

import dev.tellurium.semantic.snapshot.StructureBlendSnapshot;

import java.util.Objects;

/**
 * Scalar replay of Minecraft 1.21.1's {@code Blender.blendOffsetAndFactor}.
 *
 * <p>The game stores old-generation surface heights in a broad source map and
 * first probes the current section (then the negative-X/Z boundary
 * neighbours).  Only when that direct lookup misses does it perform the
 * weighted height search.  The snapshot keeps both forms so this evaluator
 * does not flatten away that precedence.</p>
 */
public final class BlendOffsetEvaluator {
    private static final double NO_VALUE = Double.MAX_VALUE;
    private static final int HEIGHT_BLENDING_RANGE_CELLS = 27;

    public record Output(double alpha, double blendingOffset) {
        public Output {
            if (!Double.isFinite(alpha) || !Double.isFinite(blendingOffset)) {
                throw new IllegalArgumentException("Blend output must be finite");
            }
        }
    }

    public Output blend(StructureBlendSnapshot snapshot, int blockX, int blockZ) {
        Objects.requireNonNull(snapshot, "snapshot");
        int quartX = Math.floorDiv(blockX, 4);
        int quartZ = Math.floorDiv(blockZ, 4);
        double direct = directValue(snapshot, quartX, quartZ);
        if (direct != NO_VALUE) return new Output(0.0, heightToOffset(direct));

        double weighted = 0.0;
        double weights = 0.0;
        double closest = Double.POSITIVE_INFINITY;
        for (var sample : snapshot.heightSamples()) {
            double distance = Math.sqrt((double) (quartX - sample.quartX()) * (quartX - sample.quartX())
                    + (double) (quartZ - sample.quartZ()) * (quartZ - sample.quartZ()));
            if (!(distance > HEIGHT_BLENDING_RANGE_CELLS)) {
                if (distance < closest) closest = distance;
                // A zero distance is normally handled by Blender's direct
                // lookup.  Preserve the mathematically intended result if a
                // captured source has an interior-only height sample.
                if (distance == 0.0) return new Output(0.0, heightToOffset(sample.height()));
                double distanceSquared = distance * distance;
                double weight = 1.0 / (distanceSquared * distanceSquared);
                weighted += sample.height() * weight;
                weights += weight;
            }
        }
        if (closest == Double.POSITIVE_INFINITY) return new Output(1.0, 0.0);
        double oldHeight = weighted / weights;
        double blend = clamp(closest / (HEIGHT_BLENDING_RANGE_CELLS + 1.0), 0.0, 1.0);
        blend = 3.0 * blend * blend - 2.0 * blend * blend * blend;
        return new Output(blend, heightToOffset(oldHeight));
    }

    private static double directValue(StructureBlendSnapshot snapshot, int quartX, int quartZ) {
        var samples = snapshot.directHeightSamples();
        if (samples.isEmpty()) return NO_VALUE;
        int sectionX = Math.floorDiv(quartX, 4);
        int sectionZ = Math.floorDiv(quartZ, 4);
        int localX = Math.floorMod(quartX, 4);
        int localZ = Math.floorMod(quartZ, 4);
        double value = directValue(samples, sectionX, sectionZ, localX, localZ);
        if (value != NO_VALUE) return value;
        if (localX == 0 && localZ == 0) {
            value = directValue(samples, sectionX - 1, sectionZ - 1, 4, 4);
            if (value != NO_VALUE) return value;
        }
        if (localX == 0) {
            value = directValue(samples, sectionX - 1, sectionZ, 4, localZ);
            if (value != NO_VALUE) return value;
        }
        if (localZ == 0) {
            value = directValue(samples, sectionX, sectionZ - 1, localX, 4);
            if (value != NO_VALUE) return value;
        }
        return NO_VALUE;
    }

    private static double directValue(java.util.List<StructureBlendSnapshot.DirectHeightSample> samples,
                                      int sectionX, int sectionZ, int localX, int localZ) {
        for (var sample : samples) {
            if (sample.sectionX() == sectionX && sample.sectionZ() == sectionZ
                    && sample.localX() == localX && sample.localZ() == localZ) {
                return sample.height();
            }
        }
        return NO_VALUE;
    }

    private static double heightToOffset(double height) {
        double shifted = height + 0.5;
        double modulo = positiveModulo(shifted, 8.0);
        return (32.0 * (shifted - 128.0) - 3.0 * (shifted - 120.0) * modulo
                + 3.0 * modulo * modulo) / (128.0 * (32.0 - 3.0 * modulo));
    }

    private static double positiveModulo(double value, double modulus) {
        double result = value % modulus;
        return result < 0.0 ? result + modulus : result;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
