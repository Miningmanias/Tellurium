// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

import dev.tellurium.compiler.vulkan.worldgen.WorldgenShaderCompiler;
import dev.tellurium.compiler.vulkan.worldgen.BeardifierKernelInputStage;
import dev.tellurium.runtime.vulkan.production.VulkanWorldgenExecutor;
import dev.tellurium.semantic.program.NumericProfile;
import dev.tellurium.semantic.snapshot.BeardifierSnapshot;
import dev.tellurium.semantic.snapshot.StructureBlendSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Actual-device structure arithmetic/row regression; not Minecraft/release qualification. */
public final class NativeBeardifierSmoke {
    private record Point(int x, int y, int z) { }

    public static void main(String[] args) {
        boolean inlineHelpers = java.util.Arrays.asList(args).contains("--inline-helpers");
        boolean terralithPiece = java.util.Arrays.asList(args).contains("--terralith-piece");
        boolean inputKernel = java.util.Arrays.asList(args).contains("--input-kernel");
        var pieces = new ArrayList<BeardifierSnapshot.Rigid>();
        if (terralithPiece) {
            pieces.add(new BeardifierSnapshot.Rigid(476, 65, 496, 480, 65, 500,
                    BeardifierSnapshot.Adjustment.BEARD_THIN, 0));
        } else {
            for (var adjustment : BeardifierSnapshot.Adjustment.values()) {
                pieces.add(new BeardifierSnapshot.Rigid(-2, -2, -2, 2, 2, 2, adjustment, 2));
            }
        }
        var beardifier = new BeardifierSnapshot(pieces, terralithPiece ? List.of() : List.of(
                new BeardifierSnapshot.Junction(-3, 0, 4), new BeardifierSnapshot.Junction(3, -1, -4)));
        var blend = new StructureBlendSnapshot("beardifier-regression-v1", Map.of(), Map.of(), beardifier);
        var points = new ArrayList<Point>();
        if (terralithPiece) {
            for (int x = 480; x < 496; x++) {
                for (int y = 50; y <= 80; y++) {
                    for (int z = 480; z < 496; z++) points.add(new Point(x, y, z));
                }
            }
        } else {
            for (int x : new int[]{-15, -12, -3, 0, 3, 11, 12, 15}) {
                for (int y : new int[]{-13, -12, -1, 0, 2, 11, 12}) {
                    points.add(new Point(x, y, -3));
                    points.add(new Point(-x, y, 4));
                }
            }
        }
        int count = points.size();
        try (var executor = new VulkanWorldgenExecutor(1024 * 1024)) {
            for (boolean combine : new boolean[]{false, true}) {
                var shader = new WorldgenShaderCompiler().emitBeardifierStage(blend,
                        NumericProfile.GPU_IEEE_BITS, 64, combine);
                int stride = combine ? 6 : 4;
                var metadata = inputKernel ? BeardifierKernelInputStage.bind(shader.source(), stride) : null;
                if (metadata != null) shader = new WorldgenShaderCompiler.Shader(metadata.source(),
                        shader.programHash() + "/kernel-input-v1", shader.profile(), shader.localSize());
                int[] input = new int[count * stride];
                for (int index = 0; index < count; index++) {
                    Point point = points.get(index);
                    int base = index * stride;
                    input[base] = point.x(); input[base + 1] = point.y(); input[base + 2] = point.z();
                    input[base + 3] = 0x7fc00001; // Reserved carrier must not participate.
                    if (combine) {
                        long density = Double.doubleToRawLongBits(density(index));
                        input[base + 4] = (int) density; input[base + 5] = (int) (density >>> 32);
                    }
                }
                for (boolean disabled : new boolean[]{true, false}) {
                    for (int slice : new int[]{count, terralithPiece ? 127 : 7}) {
                        System.out.println("nativeBeardifier dispatch-start combine=" + combine + " disableOptimization=" + disabled + " slice=" + slice
                                + " inlineHelpers=" + inlineHelpers + " inputKernel=" + inputKernel + " sourceChars=" + shader.source().length());
                        var result = executor.executeRawBatched(new VulkanWorldgenExecutor.RawRequest(
                                shader, metadata == null ? input : metadata.inputRows(input), stride, 2, count).withPipelineOptimizationDisabled(disabled)
                                .withDontInlineFunctions(!inlineHelpers, inlineHelpers ? "" : "wg_beard_,wg_beardifier,wg_u64_,wg_u128_,wg_fp64_,wg_sqrt_"), slice);
                        int[] output = result.outputWords();
                        for (int index = 0; index < count; index++) {
                            Point point = points.get(index);
                            double expected = reference(beardifier, point);
                            if (combine) expected = density(index) + expected;
                            long actual = Integer.toUnsignedLong(output[index * 2]) | ((long) output[index * 2 + 1] << 32);
                            if (actual != Double.doubleToRawLongBits(expected))
                                throw new IllegalStateException("Beardifier differs at " + point + " combine=" + combine
                                        + " expected=" + Double.toHexString(expected) + " actual=" + Double.toHexString(Double.longBitsToDouble(actual)));
                        }
                        System.out.println("nativeBeardifier DEVICE_PASS qualification=UNQUALIFIED_REGRESSION_ONLY comparedElements="
                                + count + " combine=" + combine + " disableOptimization=" + disabled + " slice=" + slice + " inlineHelpers=" + inlineHelpers
                                + " inputKernel=" + inputKernel + " device=" + result.device().name() + " shader=" + result.shaderHash() + " spirv=" + result.spirvHash());
                    }
                }
            }
        }
    }

    private static double density(int index) { return (index % 7 - 3) / 16.0; }

    /** Plain Java arithmetic, independently computed table entries and explicit original operation order. */
    private static double reference(BeardifierSnapshot snapshot, Point point) {
        double result = 0.0;
        for (var piece : snapshot.pieces()) {
            int dx = Math.max(0, Math.max(piece.minX() - point.x(), point.x() - piece.maxX()));
            int dz = Math.max(0, Math.max(piece.minZ() - point.z(), point.z() - piece.maxZ()));
            int ground = piece.minY() + piece.groundLevelDelta();
            int dy = point.y() - ground;
            result += switch (piece.adjustment()) {
                case NONE -> 0.0;
                case BURY -> bury(dx, dy / 2.0, dz);
                case BEARD_THIN -> beard(dx, dy, dz, dy) * 0.8;
                case BEARD_BOX -> beard(dx, Math.max(0, Math.max(ground - point.y(), point.y() - piece.maxY())), dz, dy) * 0.8;
                case ENCAPSULATE -> bury(dx / 2.0,
                        Math.max(0, Math.max(piece.minY() - point.y(), point.y() - piece.maxY())) / 2.0, dz / 2.0) * 0.8;
            };
        }
        for (var junction : snapshot.junctions()) {
            int dy = point.y() - junction.sourceGroundY();
            result += beard(point.x() - junction.sourceX(), dy, point.z() - junction.sourceZ(), dy) * 0.4;
        }
        return result;
    }

    private static double bury(double x, double y, double z) {
        double distance = Math.sqrt(x * x + y * y + z * z);
        double fraction = Math.max(0.0, Math.min(1.0, distance / 6.0));
        return 1.0 + fraction * -1.0;
    }

    private static double beard(int x, int y, int z, int groundDelta) {
        if (x < -12 || x >= 12 || y < -12 || y >= 12 || z < -12 || z >= 12) return 0.0;
        double half = groundDelta + 0.5;
        double input = (x * (double) x + half * half + z * (double) z) / 2.0;
        double estimate = Double.longBitsToDouble(6910469410427058090L - (Double.doubleToRawLongBits(input) >> 1));
        double inverse = estimate * (1.5 - 0.5 * input * estimate * estimate);
        float kernel = (float) Math.pow(Math.E, -(x * (double) x + (y + 0.5) * (y + 0.5) + z * (double) z) / 16.0);
        return -half * inverse / 2.0 * kernel;
    }
}
