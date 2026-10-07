// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

import dev.tellurium.compiler.vulkan.worldgen.WorldgenShaderCompiler;
import dev.tellurium.runtime.vulkan.production.VulkanWorldgenExecutor;
import dev.tellurium.semantic.program.NumericProfile;
import java.util.Arrays;
import java.util.List;

/** Physical-device suffix/visibility/layout regression, not worldgen qualification. */
public final class NativeRawChainSuffixSmoke {
    private NativeRawChainSuffixSmoke() { }

    public static void main(String[] args) {
        int elements = 37;
        int[] input = new int[elements * 3];
        int[] expected = new int[elements];
        int factor = 0x80000005;
        int salt = 0xffffabcd;
        int add = 0x7fffffe7;
        for (int index = 0; index < elements; index++) {
            int base = index * 3;
            input[base] = index * 0x1234567;
            input[base + 1] = -index - 1;
            input[base + 2] = Integer.MIN_VALUE + index;
            expected[index] = ((input[base] * factor + input[base + 1]) ^ salt)
                    ^ ((input[base + 2] ^ salt) + add);
        }
        var first = new VulkanWorldgenExecutor.RawStage(shader(3, 2, """
                uint metadata = dispatch.count * 3u;
                uint base = index * 3u;
                uint target = index * 2u;
                if (inputBits.length() != metadata + 2u || outputBits.length() != dispatch.count * 2u) {
                    outputBits[target] = 0xdeadc0deu;
                    outputBits[target + 1u] = 0u;
                    return;
                }
                outputBits[target] = (inputBits[base] * inputBits[metadata] + inputBits[base + 1u]) ^ inputBits[metadata + 1u];
                outputBits[target + 1u] = inputBits[base + 2u] ^ inputBits[metadata + 1u];
                """), 3, 2, false, false, "").withUniformInputWords(new int[]{factor, salt});
        var second = new VulkanWorldgenExecutor.RawStage(shader(2, 1, """
                uint metadata = dispatch.count * 2u;
                uint base = index * 2u;
                if (inputBits.length() != metadata + 1u || outputBits.length() != dispatch.count) {
                    outputBits[index] = 0xdeadc0deu;
                    return;
                }
                outputBits[index] = inputBits[base] ^ (inputBits[base + 1u] + inputBits[metadata]);
                """), 2, 1, false, false, "").withUniformInputWords(new int[]{add});
        List<VulkanWorldgenExecutor.RawStage> stages = List.of(first, second);
        long budget = VulkanWorldgenExecutor.rawChainBufferBytes(elements, stages);
        try (var executor = new VulkanWorldgenExecutor(budget)) {
            for (int slice : new int[]{37, 7, 1}) {
                executor.resetTelemetry();
                var result = executor.executeRawChainBatched(input, 3, elements, 0, 0, 0, stages, slice);
                if (!Arrays.equals(expected, result.outputWords())) {
                    throw new IllegalStateException("Resident suffix result differs at slice=" + slice);
                }
                long submissions = (elements + slice - 1L) / slice;
                var telemetry = executor.telemetry();
                if (telemetry.dispatches() != submissions * 2 || telemetry.elements() != elements * 2L
                        || telemetry.inputBytes() != input.length * 4L + submissions * 3 * 4
                        || telemetry.outputBytes() != elements * 4L) {
                    throw new IllegalStateException("Incorrect resident chain traffic/coverage: " + telemetry);
                }
                System.out.println("nativeRawChainSuffix DEVICE_PASS qualification=UNQUALIFIED_ABI_ONLY"
                        + " comparedElements=" + elements + " slice=" + slice
                        + " budget=" + budget + " telemetry=" + telemetry
                        + " shader=" + result.shaderHash() + " spirv=" + result.spirvHash());
            }
            if (executor.storageTelemetry().bufferAllocations() != 3
                    || executor.storageTelemetry().retainedBytes() != budget) {
                throw new IllegalStateException("Resident slices did not reuse the three bounded buffers: "
                        + executor.storageTelemetry());
            }
            List<VulkanWorldgenExecutor.RawStage> exports = List.of(first.withExportedOutput(0, 2), second);
            int[] exportedExpected = new int[elements * 3];
            for (int index = 0; index < elements; index++) {
                int source = index * 3;
                exportedExpected[source] = (input[source] * factor + input[source + 1]) ^ salt;
                exportedExpected[source + 1] = input[source + 2] ^ salt;
                exportedExpected[source + 2] = expected[index];
            }
            for (int slice : new int[]{37, 7, 1}) {
                executor.resetTelemetry();
                var exported = executor.executeRawChainBatched(input, 3, elements, 0, 0, 0, exports, slice);
                if (exported.outputWordsPerElement() != 3 || !Arrays.equals(exportedExpected, exported.outputWords())
                        || executor.telemetry().outputBytes() != exportedExpected.length * 4L) {
                    throw new IllegalStateException("Resident intermediate export failed slice=" + slice);
                }
                System.out.println("nativeRawChainSuffix EXPORT_PASS comparedElements=" + elements + " slice=" + slice);
            }
            int[] suffixedInput = Arrays.copyOf(input, input.length + 2);
            suffixedInput[input.length] = factor;
            suffixedInput[input.length + 1] = salt;
            var raw = executor.executeRaw(new VulkanWorldgenExecutor.RawRequest(
                    first.shader(), suffixedInput, 3, 2, elements, 0, 0, 0));
            for (int index = 0; index < elements; index++) {
                if (raw.outputWords()[index * 2] != exportedExpected[index * 3]
                        || raw.outputWords()[index * 2 + 1] != exportedExpected[index * 3 + 1]) {
                    throw new IllegalStateException("Raw-after-chain result changed index=" + index);
                }
            }
            // Identical SPIR-V under a different compute-pipeline policy must
            // not reuse the old native pipeline. Shader-stage flags stay zero.
            long beforePolicy = executor.compilationTelemetry().pipelineCreations();
            var disabledRequest = new VulkanWorldgenExecutor.RawRequest(
                    first.shader(), suffixedInput, 3, 2, elements, 0, 0, 0)
                    .withPipelineOptimizationDisabled(true);
            var disabled = executor.executeRaw(disabledRequest);
            if (!Arrays.equals(raw.outputWords(), disabled.outputWords())
                    || executor.compilationTelemetry().pipelineCreations() != beforePolicy + 1) {
                throw new IllegalStateException("Compute policy did not preserve output and split pipeline identity");
            }
            long beforePolicyHit = executor.compilationTelemetry().pipelineCacheHits();
            if (!Arrays.equals(raw.outputWords(), executor.executeRaw(disabledRequest).outputWords())
                    || executor.compilationTelemetry().pipelineCacheHits() != beforePolicyHit + 1) {
                throw new IllegalStateException("Identical compute policy did not reuse its native pipeline");
            }
            System.out.println("nativeRawChainSuffix COMPUTE_POLICY_PASS computeFlags=0/1 stageFlags=0"
                    + " compilation=" + executor.compilationTelemetry());
            int newFactor = 19;
            int newSalt = 0x10203040;
            int newAdd = -7;
            var updatedStages = List.of(first.withUniformInputWords(new int[]{newFactor, newSalt}),
                    second.withUniformInputWords(new int[]{newAdd}));
            var updated = executor.executeRawChainBatched(input, 3, elements, 0, 0, 0, updatedStages, 7);
            for (int index = 0; index < elements; index++) {
                int base = index * 3;
                int value = ((input[base] * newFactor + input[base + 1]) ^ newSalt)
                        ^ ((input[base + 2] ^ newSalt) + newAdd);
                if (updated.outputWords()[index] != value) {
                    throw new IllegalStateException("Reused chain kept stale metadata at index=" + index);
                }
            }
            if (executor.storageTelemetry().bufferAllocations() != 3) {
                throw new IllegalStateException("Raw/chain transition allocated again: " + executor.storageTelemetry());
            }
            System.out.println("nativeRawChainSuffix REUSE_PASS metadataChanged=true rawChainRoundTrip=true"
                    + " storage=" + executor.storageTelemetry());

            // The opposite shape would combine high-water marks above 900.
            // Force safe shrink/eviction, then regrow the original three slots.
            var opposite = new VulkanWorldgenExecutor.RawStage(shader(1, 3, """
                    uint target = index * 3u;
                    if (inputBits.length() != dispatch.count + 1u || outputBits.length() != dispatch.count * 3u) {
                        outputBits[target] = 0xdeadc0deu;
                        return;
                    }
                    outputBits[target] = inputBits[index];
                    outputBits[target + 1u] = inputBits[index] ^ inputBits[dispatch.count];
                    outputBits[target + 2u] = inputBits[index] + 1u;
                    """), 1, 3, false, false, "").withUniformInputWords(new int[]{newSalt});
            var oppositeResult = executor.executeRawChain(expected, 1, elements, 0, 0, 0, List.of(opposite));
            int[] oppositeWords = oppositeResult.outputWords();
            for (int index = 0; index < elements; index++) {
                if (oppositeWords[index * 3] != expected[index]
                        || oppositeWords[index * 3 + 1] != (expected[index] ^ newSalt)
                        || oppositeWords[index * 3 + 2] != expected[index] + 1) {
                    throw new IllegalStateException("Opposite-shape chain failed at index=" + index);
                }
            }
            if (executor.storageTelemetry().retainedBytes() != 596) {
                throw new IllegalStateException("Unused slots/high-water marks escaped the budget: " + executor.storageTelemetry());
            }
            var regrown = executor.executeRawChain(input, 3, elements, 0, 0, 0, stages);
            if (!Arrays.equals(expected, regrown.outputWords()) || executor.storageTelemetry().retainedBytes() != budget) {
                throw new IllegalStateException("Regrown chain failed or exceeded native budget");
            }
            System.out.println("nativeRawChainSuffix BUDGET_SHAPE_PASS budget=" + budget);
            executor.recreateDevice();
            var afterRecreate = executor.executeRawChainBatched(input, 3, elements, 0, 0, 0, stages, 7);
            if (!Arrays.equals(expected, afterRecreate.outputWords())) {
                throw new IllegalStateException("Resident suffix failed after device recreation");
            }
            System.out.println("nativeRawChainSuffix DEVICE_RECREATE_PASS comparedElements=" + elements);
        }
        wideBatchControl();
    }

    private static void wideBatchControl() {
        int elements = VulkanWorldgenExecutor.MAX_DISPATCH_ELEMENTS + 3;
        int slice = VulkanWorldgenExecutor.MAX_DISPATCH_ELEMENTS - 1;
        int factor = 0x80000123;
        int salt = 0xfeed0bad;
        int[] input = new int[elements];
        int[] expected = new int[elements];
        for (int index = 0; index < elements; index++) {
            input[index] = index * 0x87654321;
            expected[index] = (input[index] * factor) ^ salt;
        }
        var stage = new VulkanWorldgenExecutor.RawStage(shader(1, 1, """
                if (inputBits.length() != dispatch.count + 2u || outputBits.length() != dispatch.count) {
                    outputBits[index] = 0xdeadc0deu;
                    return;
                }
                outputBits[index] = (inputBits[index] * inputBits[dispatch.count]) ^ inputBits[dispatch.count + 1u];
                """), 1, 1, false, false, "").withUniformInputWords(new int[]{factor, salt});
        long budget = VulkanWorldgenExecutor.rawChainBufferBytes(elements, List.of(stage));
        try (var executor = new VulkanWorldgenExecutor(budget)) {
            var chained = executor.executeRawChainBatched(input, 1, elements, 0, 0, 0, List.of(stage), slice);
            int[] suffixed = Arrays.copyOf(input, elements + 2);
            suffixed[elements] = factor;
            suffixed[elements + 1] = salt;
            var request = new VulkanWorldgenExecutor.RawRequest(stage.shader(), suffixed, 1, 1, elements, 0, 0, 0);
            var raw = executor.executeRawBatched(request, slice);
            var atLimit = executor.executeRaw(request.slice(0, VulkanWorldgenExecutor.MAX_DISPATCH_ELEMENTS));
            if (!Arrays.equals(expected, chained.outputWords()) || !Arrays.equals(expected, raw.outputWords())
                    || !Arrays.equals(Arrays.copyOf(expected, VulkanWorldgenExecutor.MAX_DISPATCH_ELEMENTS), atLimit.outputWords())
                    || executor.storageTelemetry().retainedBytes() > budget) {
                throw new IllegalStateException("Wide batch/suffix/partial-tail/budget control failed");
            }
            System.out.println("nativeRawChainSuffix WIDE_BATCH_PASS qualification=UNQUALIFIED_ABI_ONLY"
                    + " comparedElementsPerRoute=" + elements + " slice=" + slice
                    + " singleDispatchElements=" + atLimit.elementCount()
                    + " storage=" + executor.storageTelemetry());
        }
    }

    private static WorldgenShaderCompiler.Shader shader(int inputStride, int outputStride, String body) {
        String source = """
                #version 450
                layout(local_size_x = 64) in;
                layout(std430, binding = 0) readonly buffer Inputs { uint inputBits[]; };
                layout(std430, binding = 1) writeonly buffer Outputs { uint outputBits[]; };
                layout(push_constant) uniform Dispatch { uint count; uint defaultState; uint airState; uint invalidState; } dispatch;
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                """ + body + "\n}\n";
        return new WorldgenShaderCompiler.Shader(source, "suffix-layout-" + inputStride + "-" + outputStride,
                NumericProfile.GPU_IEEE_BITS, 64);
    }
}
