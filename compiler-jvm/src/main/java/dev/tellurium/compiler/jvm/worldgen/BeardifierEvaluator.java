// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.jvm.worldgen;

import dev.tellurium.semantic.snapshot.BeardifierSnapshot;
import dev.tellurium.semantic.snapshot.StructureBlendSnapshot;

/** Scalar replay of the 1.21.1 Beardifier contribution. */
public final class BeardifierEvaluator {
    private static final int KERNEL_RADIUS = 12;
    private static final int KERNEL_SIZE = KERNEL_RADIUS * 2;
    private static final int KERNEL_AREA = KERNEL_SIZE * KERNEL_SIZE;
    private static final float[] BEARD_KERNEL = createKernel();

    public double compute(StructureBlendSnapshot blend, int x, int y, int z) {
        if (blend == null) return 0.0;
        double result = 0.0;
        for (var rigid : blend.beardifier().pieces()) {
            int horizontalX = Math.max(0, Math.max(rigid.minX() - x, x - rigid.maxX()));
            int horizontalZ = Math.max(0, Math.max(rigid.minZ() - z, z - rigid.maxZ()));
            int groundY = rigid.minY() + rigid.groundLevelDelta();
            int verticalDelta = y - groundY;
            int verticalDistance = switch (rigid.adjustment()) {
                case NONE -> 0;
                case BURY, BEARD_THIN -> verticalDelta;
                case BEARD_BOX -> Math.max(0, Math.max(groundY - y, y - rigid.maxY()));
                case ENCAPSULATE -> Math.max(0, Math.max(rigid.minY() - y, y - rigid.maxY()));
            };
            result += switch (rigid.adjustment()) {
                case NONE -> 0.0;
                case BURY -> bury(horizontalX, verticalDistance / 2.0, horizontalZ);
                case BEARD_THIN, BEARD_BOX -> beard(horizontalX, verticalDistance, horizontalZ, verticalDelta) * 0.8;
                case ENCAPSULATE -> bury(horizontalX / 2.0, verticalDistance / 2.0, horizontalZ / 2.0) * 0.8;
            };
        }
        for (var junction : blend.beardifier().junctions()) {
            int dx = x - junction.sourceX();
            int dy = y - junction.sourceGroundY();
            int dz = z - junction.sourceZ();
            result += beard(dx, dy, dz, dy) * 0.4;
        }
        return result;
    }

    private static double bury(double x, double y, double z) {
        return clampedMap(Math.sqrt(x * x + y * y + z * z), 0.0, 6.0, 1.0, 0.0);
    }

    /** Matches Mth.fastInvSqrt rather than substituting a different native math contract. */
    private static double beard(int x, int y, int z, int groundDelta) {
        int kernelX = x + KERNEL_RADIUS, kernelY = y + KERNEL_RADIUS, kernelZ = z + KERNEL_RADIUS;
        if (kernelX < 0 || kernelX >= KERNEL_SIZE || kernelY < 0 || kernelY >= KERNEL_SIZE || kernelZ < 0 || kernelZ >= KERNEL_SIZE) return 0.0;
        double half = groundDelta + 0.5;
        double distanceSquared = x * (double) x + half * half + z * (double) z;
        double inverse = fastInvSqrt(distanceSquared / 2.0);
        double contribution = -half * inverse / 2.0;
        return contribution * BEARD_KERNEL[kernelZ * KERNEL_AREA + kernelX * KERNEL_SIZE + kernelY];
    }

    /** Minecraft precomputes this table as float values during Beardifier class initialization. */
    private static float[] createKernel() {
        float[] kernel = new float[KERNEL_SIZE * KERNEL_AREA];
        for (int kernelZ = 0; kernelZ < KERNEL_SIZE; kernelZ++) {
            for (int kernelX = 0; kernelX < KERNEL_SIZE; kernelX++) {
                for (int kernelY = 0; kernelY < KERNEL_SIZE; kernelY++) {
                    int x = kernelX - KERNEL_RADIUS;
                    int y = kernelY - KERNEL_RADIUS;
                    int z = kernelZ - KERNEL_RADIUS;
                    double distanceSquared = x * (double) x + (y + 0.5) * (y + 0.5) + z * (double) z;
                    kernel[kernelZ * KERNEL_AREA + kernelX * KERNEL_SIZE + kernelY] =
                            (float) Math.pow(Math.E, -distanceSquared / 16.0);
                }
            }
        }
        return kernel;
    }

    private static double fastInvSqrt(double value) {
        double half = 0.5 * value;
        long bits = Double.doubleToRawLongBits(value);
        bits = 6910469410427058090L - (bits >> 1);
        double estimate = Double.longBitsToDouble(bits);
        return estimate * (1.5 - half * estimate * estimate);
    }

    private static double clampedMap(double value, double inMin, double inMax, double outMin, double outMax) {
        double fraction = Math.max(0.0, Math.min(1.0, (value - inMin) / (inMax - inMin)));
        return outMin + fraction * (outMax - outMin);
    }
}
