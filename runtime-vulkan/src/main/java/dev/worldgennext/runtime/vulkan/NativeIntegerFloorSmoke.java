// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan;

import dev.worldgennext.compiler.vulkan.worldgen.WorldgenShaderCompiler;
import dev.worldgennext.runtime.vulkan.production.VulkanWorldgenExecutor;
import dev.worldgennext.semantic.program.*;
import java.util.*;

/** Opt-in physical-device Java floorDiv/floorMod regression, not Minecraft qualification. */
public final class NativeIntegerFloorSmoke {
    private NativeIntegerFloorSmoke() { }
    record Pair(int left, int right) { }

    static List<Pair> vectors() {
        int[] left = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -536870912, -1025, -65,
                -64, -63, -49, -48, -37, -36, -35, -25, -24, -23, -17, -16, -15,
                -13, -12, -11, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 11, 12,
                13, 15, 16, 17, 23, 24, 25, 35, 36, 37, 63, 64, 65, 1025,
                536870912, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        int[] right = {Integer.MIN_VALUE, -1024, -64, -25, -16, -12, -5, -4,
                -3, -2, -1, 0, 1, 2, 3, 4, 5, 12, 16, 25, 64, 1024, Integer.MAX_VALUE};
        var result = new ArrayList<Pair>();
        for (int a : left) for (int b : right) result.add(new Pair(a, b));
        var random = new Random(20260930L);
        for (int i = 0; i < 511; i++) result.add(new Pair(random.nextInt(), random.nextInt()));
        return List.copyOf(result);
    }

    static WorldgenShaderCompiler.Shader shader() {
        var base = new WorldgenShaderCompiler().emit(WorldgenProgram.builder().root("finalDensity",
                new ProgramNode.Constant(ValueType.FP64, -1.0, EvaluationDomain.BLOCK)).build(),
                NumericProfile.GPU_IEEE_BITS, 64);
        int main = base.source().lastIndexOf("\nvoid main()");
        if (main < 0) throw new IllegalStateException("Compiler main entry point missing");
        return new WorldgenShaderCompiler.Shader(base.source().substring(0, main) + """
                \nvoid main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    uint a = inputBits[index * 2u], b = inputBits[index * 2u + 1u];
                    wg_failed = false;
                    uint quotient = wg_i32_floor_div(a, b);
                    uint divisionFailure = wg_failed ? 1u : 0u;
                    wg_failed = false;
                    uint remainder = wg_i32_floor_mod(a, b);
                    uint base = index * 4u;
                    outputStateIds[base] = quotient;
                    outputStateIds[base + 1u] = remainder;
                    outputStateIds[base + 2u] = divisionFailure;
                    outputStateIds[base + 3u] = wg_failed ? 1u : 0u;
                }
                """, base.programHash(), base.profile(), base.localSize());
    }

    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("No arguments supported");
        var pairs = vectors();
        int[] input = new int[pairs.size() * 2];
        for (int i = 0; i < pairs.size(); i++) {
            input[i * 2] = pairs.get(i).left(); input[i * 2 + 1] = pairs.get(i).right();
        }
        try (var executor = new VulkanWorldgenExecutor()) {
            var request = new VulkanWorldgenExecutor.RawRequest(shader(), input, 2, 4,
                    pairs.size(), 0, 0, 0, true, false, "");
            for (int batch : new int[]{pairs.size(), 63, 1}) {
                executor.resetTelemetry();
                var result = executor.executeRawBatched(request, batch);
                int[] output = result.outputWords();
                if (output.length != pairs.size() * 4 || result.elementCount() != pairs.size())
                    throw new IllegalStateException("Incomplete native floor result");
                for (int i = 0; i < pairs.size(); i++) {
                    Pair p = pairs.get(i);
                    int fail = p.right() == 0 ? 1 : 0;
                    int quotient = fail == 1 ? 0 : Math.floorDiv(p.left(), p.right());
                    int remainder = fail == 1 ? 0 : Math.floorMod(p.left(), p.right());
                    if (output[i * 4] != quotient || output[i * 4 + 1] != remainder
                            || output[i * 4 + 2] != fail || output[i * 4 + 3] != fail)
                        throw new IllegalStateException("Device integer floor mismatch pair=" + p
                                + " expected=" + List.of(quotient, remainder, fail, fail)
                                + " actual=" + Arrays.toString(Arrays.copyOfRange(output, i * 4, i * 4 + 4)));
                }
                var telemetry = executor.telemetry();
                if (telemetry.dispatches() != (pairs.size() + batch - 1L) / batch
                        || telemetry.elements() != pairs.size())
                    throw new IllegalStateException("Incomplete native dispatch accounting " + telemetry);
                System.out.println("nativeIntegerFloor DEVICE_PASS qualification=UNQUALIFIED_HELPER_ONLY"
                        + " comparedPairs=" + pairs.size() + " comparedWords=" + output.length
                        + " batch=" + batch + " tail=" + pairs.size() % batch
                        + " device=" + result.device() + " shaderSha256=" + result.shaderHash()
                        + " spirvSha256=" + result.spirvHash() + " telemetry=" + telemetry);
            }
        }
    }
}
