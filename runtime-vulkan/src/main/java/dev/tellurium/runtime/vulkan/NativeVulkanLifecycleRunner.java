// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

import dev.tellurium.compiler.vulkan.worldgen.WorldgenShaderCompiler;
import dev.tellurium.runtime.vulkan.production.VulkanWorldgenExecutor;
import dev.tellurium.semantic.program.EvaluationDomain;
import dev.tellurium.semantic.program.NumericProfile;
import dev.tellurium.semantic.program.ProgramNode;
import dev.tellurium.semantic.program.ValueType;
import dev.tellurium.semantic.program.WorldgenProgram;

import java.util.Arrays;
import java.util.concurrent.CompletionStage;

/**
 * Opt-in native lifecycle proof for the persistent worldgen executor.
 *
 * <p>The two submissions are queued before either result is joined.  They
 * therefore exercise the executor's one queue-owner thread, asynchronous
 * completion and persistent shader/pipeline cache in one device lifetime.
 * This remains separate from the integer conformance and dense Minecraft
 * candidate campaigns.</p>
 */
public final class NativeVulkanLifecycleRunner {
    private NativeVulkanLifecycleRunner() {}

    public static void main(String[] args) {
        var shader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity",
                        new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.BLOCK)).build(),
                NumericProfile.GPU_IEEE_BITS, 64);
        int[] firstCoordinates = {-16, -32, 48, 0, 0, 0, 17, 8, -9};
        int[] secondCoordinates = {1, 4, 2, -7, 12, 19, 31, 0, -31, 64, 16, 64, -64, -16, 32};
        var firstRequest = new VulkanWorldgenExecutor.Request(shader, firstCoordinates, 17, 23, 99);
        var secondRequest = new VulkanWorldgenExecutor.Request(shader, secondCoordinates, 17, 23, 99);
        try (var executor = new VulkanWorldgenExecutor()) {
            CompletionStage<VulkanWorldgenExecutor.Result> first = executor.submit(firstRequest);
            CompletionStage<VulkanWorldgenExecutor.Result> second = executor.submit(secondRequest);
            VulkanWorldgenExecutor.Result firstResult = first.toCompletableFuture().join();
            VulkanWorldgenExecutor.Result secondResult = second.toCompletableFuture().join();

            requireAllDefault(firstResult, firstRequest.elementCount());
            requireAllDefault(secondResult, secondRequest.elementCount());
            if (!firstResult.device().equals(secondResult.device())) {
                throw new IllegalStateException("Persistent executor changed device between submissions");
            }
            if (!firstResult.shaderHash().equals(secondResult.shaderHash())
                    || !firstResult.spirvHash().equals(secondResult.spirvHash())) {
                throw new IllegalStateException("Persistent executor changed shader provenance between submissions");
            }
            System.out.println("nativeVulkanLifecycle PASS device=" + firstResult.device().name()
                    + " submissions=2 elements=" + (firstResult.elementCount() + secondResult.elementCount())
                    + " shaderSha256=" + firstResult.shaderHash()
                    + " spirvSha256=" + firstResult.spirvHash());
        }
    }

    private static void requireAllDefault(VulkanWorldgenExecutor.Result result, int expectedCount) {
        if (result.elementCount() != expectedCount || result.stateIds().length != expectedCount) {
            throw new IllegalStateException("Persistent result cardinality changed: expected=" + expectedCount
                    + " actual=" + result.elementCount());
        }
        int[] expected = new int[expectedCount];
        Arrays.fill(expected, 17);
        if (!Arrays.equals(expected, result.stateIds())) {
            throw new IllegalStateException("Persistent result mismatch: expected="
                    + Arrays.toString(expected) + " actual=" + Arrays.toString(result.stateIds()));
        }
    }
}
