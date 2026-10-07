// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

import dev.tellurium.compiler.vulkan.worldgen.IntegerIeeeEmitter;
import dev.tellurium.compiler.vulkan.worldgen.SharedSplineStageEmitter;
import dev.tellurium.compiler.vulkan.worldgen.WorldgenShaderCompiler;
import dev.tellurium.runtime.vulkan.production.VulkanWorldgenExecutor;
import dev.tellurium.semantic.program.NumericProfile;
import java.util.ArrayList;

/** Physical one-knot spline carrier regression, not Minecraft/release qualification. */
public final class NativeSingleKnotSplineSmoke {
    private NativeSingleKnotSplineSmoke() { }

    private record Vector(double coordinate, float location, float derivative, double value) {
        long expectedBits() {
            float value32 = (float) value;
            float result = derivative == 0.0f ? value32
                    : value32 + derivative * ((float) coordinate - location);
            return Double.doubleToRawLongBits(Float.isFinite(result) ? (double) result : Double.NaN);
        }
    }

    public static void main(String[] args) {
        var vectors = new ArrayList<Vector>();
        for (double coordinate : new double[]{-100.0, -3.0, -0.0, 0.0, 1.0,
                Math.nextDown(1.0f), Math.nextUp(1.0f), 17.25, Double.MIN_VALUE,
                -Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE}) {
            for (float derivative : new float[]{-0.0f, 0.0f, -0.25f, 2.0f}) {
                vectors.add(new Vector(coordinate, 1.0f, derivative, 4.0));
                vectors.add(new Vector(coordinate, -3.0f, derivative, -0.0));
            }
        }
        int elements = vectors.size();
        int[] input = new int[elements * 7];
        for (int index = 0; index < elements; index++) {
            Vector vector = vectors.get(index);
            int base = index * 7;
            input[base] = 1;
            storeDouble(input, base + 1, vector.coordinate());
            input[base + 3] = Float.floatToRawIntBits(vector.location());
            input[base + 4] = Float.floatToRawIntBits(vector.derivative());
            storeDouble(input, base + 5, vector.value());
        }
        String source = """
                #version 450
                layout(local_size_x = 64) in;
                layout(std430, binding = 0) readonly buffer Inputs { uint inputBits[]; };
                layout(std430, binding = 1) writeonly buffer Outputs { uint outputBits[]; };
                layout(push_constant) uniform Dispatch { uint count; uint defaultState; uint airState; uint invalidState; } dispatch;
                bool wg_failed = false;
                """ + IntegerIeeeEmitter.source() + SharedSplineStageEmitter.source();
        var shader = new WorldgenShaderCompiler.Shader(source, "single-knot-spline-v1",
                NumericProfile.GPU_IEEE_BITS, 64);
        try (var executor = new VulkanWorldgenExecutor(elements * 9L * Integer.BYTES)) {
            for (boolean disableOptimization : new boolean[]{true, false}) {
                for (int slice : new int[]{elements, 7, 1}) {
                    var request = new VulkanWorldgenExecutor.RawRequest(shader, input, 7, 2, elements)
                            .withDontInlineFunctions(false, "")
                            .withPipelineOptimizationDisabled(disableOptimization);
                    var result = executor.executeRawBatched(request, slice);
                    int[] output = result.outputWords();
                    for (int index = 0; index < elements; index++) {
                        long actual = Integer.toUnsignedLong(output[index * 2]) | ((long) output[index * 2 + 1] << 32);
                        long expected = vectors.get(index).expectedBits();
                        if (actual != expected && !(Double.isNaN(Double.longBitsToDouble(expected))
                                && Double.isNaN(Double.longBitsToDouble(actual)))) {
                            throw new IllegalStateException("One-knot spline differs at " + index + ": "
                                    + vectors.get(index) + " expected=" + Long.toHexString(expected)
                                    + " actual=" + Long.toHexString(actual));
                        }
                    }
                    System.out.println("nativeSingleKnotSpline DEVICE_PASS qualification=UNQUALIFIED_REGRESSION_ONLY"
                            + " comparedElements=" + elements + " slice=" + slice
                            + " disableOptimization=" + disableOptimization + " device=" + result.device().name()
                            + " shader=" + result.shaderHash() + " spirv=" + result.spirvHash());
                }
            }
        }
    }

    private static void storeDouble(int[] words, int offset, double value) {
        long bits = Double.doubleToRawLongBits(value);
        words[offset] = (int) bits;
        words[offset + 1] = (int) (bits >>> 32);
    }
}
