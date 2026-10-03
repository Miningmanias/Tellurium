// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan;

import dev.worldgennext.compiler.vulkan.worldgen.WorldgenShaderCompiler;
import dev.worldgennext.runtime.vulkan.production.VulkanWorldgenExecutor;
import dev.worldgennext.semantic.program.EvaluationDomain;
import dev.worldgennext.semantic.program.NumericProfile;
import dev.worldgennext.semantic.program.ProgramNode;
import dev.worldgennext.semantic.program.ValueType;
import dev.worldgennext.semantic.program.WorldgenProgram;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VulkanWorldgenExecutorTest {
    @Test
    void compilationTelemetryRejectsNegativeObservationsWithoutLoadingNativeLibraries() {
        var values = new VulkanWorldgenExecutor.CompilationTelemetry(10, 2, 100, 9, 3, 500);
        assertEquals(10, values.shadercCacheHits());
        assertEquals(500, values.pipelineNanos());
        for (int field = 0; field < 6; field++) {
            long[] fields = new long[6];
            fields[field] = -1;
            assertThrows(IllegalArgumentException.class, () -> new VulkanWorldgenExecutor.CompilationTelemetry(
                    fields[0], fields[1], fields[2], fields[3], fields[4], fields[5]));
        }
    }

    @Test
    void requestCopiesCoordinatesAndRejectsMalformedDenseInput() {
        var shader = shader(new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.BLOCK));
        int[] coordinates = {1, 2, 3, -4, 5, -6};
        var request = new VulkanWorldgenExecutor.Request(shader, coordinates, 7, 8, 9);
        coordinates[0] = 99;
        assertArrayEquals(new int[]{1, 2, 3, -4, 5, -6}, request.coordinates());
        var allowed = new int[]{7, 8, 9, 41};
        var allowlisted = new VulkanWorldgenExecutor.Request(shader, new int[]{0, 0, 0}, 7, 8, 9, allowed);
        allowed[3] = 99;
        assertArrayEquals(new int[]{7, 8, 9, 41}, allowlisted.allowedStateIds());
        assertThrows(IllegalArgumentException.class,
                () -> new VulkanWorldgenExecutor.Request(shader, new int[]{1, 2}, 7, 8, 9));
        assertThrows(IllegalArgumentException.class,
                () -> new VulkanWorldgenExecutor.Request(shader, new int[]{1, 2, 3}, -1, 8, 9));
        assertThrows(IllegalArgumentException.class,
                () -> new VulkanWorldgenExecutor.Request(shader, new int[]{1, 2, 3}, 7, 8, 9, new int[]{7, 8, 41}));
    }

    @Test
    void requestRequiresIntegerCarrierShaderProfile() {
        var shader = new WorldgenShaderCompiler.Shader(
                "#version 450\nvoid main() {}", "program", NumericProfile.JAVA_REFERENCE, 64);
        assertThrows(IllegalArgumentException.class,
                () -> new VulkanWorldgenExecutor.Request(shader, new int[]{0, 0, 0}, 1, 2, 3));
    }

    @Test
    void rawRequestCopiesExplicitStageStridesAndRejectsMismatchedWordCounts() {
        var shader = shader(new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.BLOCK));
        int[] words = {1, 2, 3, 4, 5, 6};
        var request = new VulkanWorldgenExecutor.RawRequest(shader, words, 3, 2, 2,
                7, 8, 9);
        words[0] = 99;
        assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6}, request.inputWords());
        assertThrows(IllegalArgumentException.class,
                () -> new VulkanWorldgenExecutor.RawRequest(shader, new int[]{1, 2}, 3, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new VulkanWorldgenExecutor.RawRequest(shader, new int[]{1, 2, 3}, 3, 0, 1));
    }

    @Test
    void rawSlicesPreserveSharedIntermediateWordsForEveryBoundedDispatch() {
        var shader = shader(new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.BLOCK));
        var request = new VulkanWorldgenExecutor.RawRequest(shader,
                new int[]{10, 11, 12, 20, 21, 22, 90, 91},
                3, 2, 2, 7, 8, 9);

        var tail = request.slice(1, 1);
        assertArrayEquals(new int[]{20, 21, 22, 90, 91}, tail.inputWords());
        assertEquals(1, tail.elementCount());
        assertThrows(IllegalArgumentException.class, () -> request.slice(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> request.slice(1, 2));
    }

    @Test
    void rawPipelinePolicyIsExplicitAndSurvivesSlicing() {
        var shader = shader(new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.BLOCK));
        var request = new VulkanWorldgenExecutor.RawRequest(shader,
                new int[]{10, 11, 12, 20, 21, 22, 90, 91},
                3, 2, 2, 7, 8, 9).withPipelineOptimizationDisabled(true);

        assertTrue(request.disablePipelineOptimization());
        assertTrue(request.slice(1, 1).disablePipelineOptimization());
        assertTrue(!request.withPipelineOptimizationDisabled(false).disablePipelineOptimization());

        var functionPolicy = request.withDontInlineFunctions("wg_node_,wg_spline_");
        assertTrue(functionPolicy.dontInlineFunctions());
        assertEquals("wg_node_,wg_spline_", functionPolicy.dontInlinePrefix());
        assertTrue(functionPolicy.slice(1, 1).dontInlineFunctions());
        assertTrue(!functionPolicy.withDontInlineFunctions(false, "").dontInlineFunctions());
        assertThrows(IllegalArgumentException.class,
                () -> request.withDontInlineFunctions(true, ""));
    }

    @Test
    void chainedStageContractRejectsBadGeometryAndPreservesPolicies() {
        var shader = shader(new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.BLOCK));
        var stage = new VulkanWorldgenExecutor.RawStage(shader, 4, 6,
                true, true, "wg_node_,wg_fp64_");
        assertEquals(4, stage.inputWordsPerElement());
        assertEquals(6, stage.outputWordsPerElement());
        assertTrue(stage.disablePipelineOptimization());
        assertTrue(stage.dontInlineFunctions());
        assertEquals("wg_node_,wg_fp64_", stage.dontInlinePrefix());
        assertThrows(IllegalArgumentException.class,
                () -> new VulkanWorldgenExecutor.RawStage(shader, 0, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new VulkanWorldgenExecutor.RawStage(shader, 2, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new VulkanWorldgenExecutor.RawStage(shader, 2, 2, false, true, ""));
    }

    @Test
    void telemetryRejectsInconsistentCounters() {
        assertEquals(0, new VulkanWorldgenExecutor.Telemetry(0, 0, 0, 0, 0, 0).dispatches());
        assertThrows(IllegalArgumentException.class,
                () -> new VulkanWorldgenExecutor.Telemetry(0, 1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new VulkanWorldgenExecutor.Telemetry(1, 1, 4, 4, 2, 3));
    }

    @Test
    void residentMetadataIsOwnedAndCountedOncePerStageAndSlice() {
        var shader = shader(new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.BLOCK));
        int[] words = {41, 42, 43};
        var first = new VulkanWorldgenExecutor.RawStage(shader, 4, 6).withUniformInputWords(words);
        var second = new VulkanWorldgenExecutor.RawStage(shader, 6, 2).withUniformInputWords(new int[]{99});
        words[0] = 0;
        first.uniformInputWords()[1] = 0;
        assertArrayEquals(new int[]{41, 42, 43}, first.uniformInputWords());
        assertEquals(31, first.inputBufferWordCount(7));
        assertEquals(7, first.inputBufferWordCount(1));
        assertEquals((7 * (4 + 6 + 2) + 4) * 4L,
                VulkanWorldgenExecutor.rawChainBufferBytes(7, java.util.List.of(first, second)));
        assertEquals((4 + 6 + 2 + 4) * 4L,
                VulkanWorldgenExecutor.rawChainBufferBytes(1, java.util.List.of(first, second)));
        assertThrows(NullPointerException.class, () -> first.withUniformInputWords(null));
        assertThrows(IllegalArgumentException.class, () -> first.inputBufferWordCount(0));
        assertThrows(ArithmeticException.class, () -> first.inputBufferWordCount(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> VulkanWorldgenExecutor.rawChainBufferBytes(1, java.util.List.of(first, first)));
    }

    @Test
    void residentExportsAreBoundedAndPreservedWhenMetadataChanges() {
        var shader = shader(new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.BLOCK));
        var first = new VulkanWorldgenExecutor.RawStage(shader, 4, 10).withExportedOutput(4, 6)
                .withUniformInputWords(new int[]{41});
        var last = new VulkanWorldgenExecutor.RawStage(shader, 10, 2);
        assertEquals(4, first.exportOffset());
        assertEquals(6, first.exportWords());
        assertEquals(8, VulkanWorldgenExecutor.rawChainResultWords(java.util.List.of(first, last)));
        assertEquals((7 * (4 + 10 + 2) + 1) * 4L,
                VulkanWorldgenExecutor.rawChainBufferBytes(7, java.util.List.of(first, last)));
        assertThrows(IllegalArgumentException.class, () -> first.withExportedOutput(5, 6));
        assertThrows(IllegalArgumentException.class, () -> first.withExportedOutput(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> first.withExportedOutput(1, 0));
    }

    @Test
    void nativeBudgetIsValidatedBeforeDeviceConstruction() {
        assertEquals(256L * 1024L * 1024L, VulkanWorldgenExecutor.DEFAULT_NATIVE_BUDGET_BYTES);
        assertThrows(IllegalArgumentException.class, () -> new VulkanWorldgenExecutor(0));
        assertThrows(IllegalArgumentException.class, () -> new VulkanWorldgenExecutor(-1));
    }

    private static WorldgenShaderCompiler.Shader shader(ProgramNode root) {
        return new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity", root).build(),
                NumericProfile.GPU_IEEE_BITS, 64);
    }
}
