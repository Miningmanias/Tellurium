// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan.production;

import dev.worldgennext.compiler.vulkan.worldgen.WorldgenShaderCompiler;
import dev.worldgennext.runtime.vulkan.production.VulkanWorldgenExecutor.RawRequest;
import dev.worldgennext.semantic.program.NumericProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RawRequestOwnershipTest {
    private static final WorldgenShaderCompiler.Shader SHADER = new WorldgenShaderCompiler.Shader(
            "#version 450\nvoid main() {}", "raw-ownership", NumericProfile.GPU_IEEE_BITS, 64);

    @Test
    void everyPublicConstructorOwnsInputAndEveryPublicReadIsDefensive() {
        for (int constructor = 0; constructor < 3; constructor++) {
            int[] external = {10, 11, 20, 21, 90, 91};
            RawRequest request = switch (constructor) {
                case 0 -> new RawRequest(SHADER, external, 2, 3, 2);
                case 1 -> new RawRequest(SHADER, external, 2, 3, 2, 7, 8, 9);
                default -> request(external, 2);
            };
            assertNotSame(external, request.inputWordsUnsafe());
            external[0] = -100;
            external[5] = -101;
            var policy = request.withPipelineOptimizationDisabled(true).withDontInlineFunctions("wg_");
            var slice = policy.slice(1, 1);
            for (RawRequest view : new RawRequest[]{request, policy, slice}) {
                int[] getter = view.inputWords();
                assertNotSame(view.inputWordsUnsafe(), getter);
                assertNotSame(getter, view.inputWords());
                getter[0] = -200;
                getter[getter.length - 1] = -201;
            }
            assertArrayEquals(new int[]{10, 11, 20, 21, 90, 91}, request.inputWords());
            assertArrayEquals(request.inputWords(), policy.inputWords());
            assertArrayEquals(new int[]{20, 21, 90, 91}, slice.inputWords());
        }
    }

    @Test
    void policyDerivativesShareOnlyOwnedStorageAndRetainGeometryAndShader() {
        var original = request(new int[]{10, 11, 20, 21, 90, 91}, 2);
        var disabled = original.withPipelineOptimizationDisabled(true);
        var inlinePolicy = disabled.withDontInlineFunctions("wg_node_,wg_spline_");
        var restored = inlinePolicy.withDontInlineFunctions(false, null)
                .withPipelineOptimizationDisabled(false);
        for (RawRequest view : new RawRequest[]{disabled, inlinePolicy, restored,
                original.withPipelineOptimizationDisabled(false),
                inlinePolicy.withDontInlineFunctions(true, "wg_other_")}) {
            assertNotSame(original, view);
            assertSame(original.inputWordsUnsafe(), view.inputWordsUnsafe());
            assertGeometry(view, 2, 6);
        }
        assertFalse(original.disablePipelineOptimization());
        assertFalse(original.dontInlineFunctions());
        assertEquals("", original.dontInlinePrefix());
        assertTrue(disabled.disablePipelineOptimization());
        assertFalse(disabled.dontInlineFunctions());
        assertTrue(inlinePolicy.disablePipelineOptimization());
        assertTrue(inlinePolicy.dontInlineFunctions());
        assertEquals("wg_node_,wg_spline_", inlinePolicy.dontInlinePrefix());
        assertFalse(restored.disablePipelineOptimization());
        assertFalse(restored.dontInlineFunctions());
        assertEquals("", restored.dontInlinePrefix());
    }

    @Test
    void slicesRebaseSuffixAcrossPartialTailNestedAndFullRanges() {
        var original = request(new int[]{10, 11, 20, 21, 30, 31, 90, 91, 92}, 3)
                .withPipelineOptimizationDisabled(true).withDontInlineFunctions("wg_");
        var first = original.slice(0, 2);
        var tail = original.slice(2, 1);
        var nested = first.slice(1, 1);
        var full = original.slice(0, 3);
        assertArrayEquals(new int[]{10, 11, 20, 21, 90, 91, 92}, first.inputWords());
        assertArrayEquals(new int[]{30, 31, 90, 91, 92}, tail.inputWords());
        assertArrayEquals(new int[]{20, 21, 90, 91, 92}, nested.inputWords());
        assertArrayEquals(original.inputWords(), full.inputWords());
        assertGeometry(first, 2, 7);
        assertGeometry(tail, 1, 5);
        assertGeometry(nested, 1, 5);
        assertGeometry(full, 3, 9);
        for (RawRequest slice : new RawRequest[]{first, tail, nested, full}) {
            assertNotSame(original.inputWordsUnsafe(), slice.inputWordsUnsafe());
            assertTrue(slice.disablePipelineOptimization());
            assertTrue(slice.dontInlineFunctions());
            assertEquals("wg_", slice.dontInlinePrefix());
            assertSame(slice.inputWordsUnsafe(), slice.withPipelineOptimizationDisabled(false).inputWordsUnsafe());
            assertSame(slice.inputWordsUnsafe(), slice.withDontInlineFunctions(false, "ignored").inputWordsUnsafe());
        }
        assertNotSame(first.inputWordsUnsafe(), nested.inputWordsUnsafe());
        assertNotSame(tail.inputWordsUnsafe(), nested.inputWordsUnsafe());
    }

    @Test
    void noSuffixSlicesContainOnlySelectedRows() {
        var original = request(new int[]{10, 11, 20, 21, 30, 31}, 3);
        assertArrayEquals(new int[]{30, 31}, original.slice(2, 1).inputWords());
        assertArrayEquals(new int[]{20, 21}, original.slice(0, 2).slice(1, 1).inputWords());
        assertEquals(2, original.slice(2, 1).inputWordCount());
    }

    @Test
    void policyValidationAndPrefixNormalizationStillApplyOnOwnedRoute() {
        var original = request(new int[]{10, 11}, 1);
        for (String invalid : new String[]{null, "", " \t\n"}) {
            assertThrows(IllegalArgumentException.class, () -> original.withDontInlineFunctions(true, invalid));
            assertThrows(IllegalArgumentException.class, () -> new RawRequest(
                    SHADER, new int[]{10, 11}, 2, 3, 1, 7, 8, 9, false, true, invalid));
        }
        for (String ignored : new String[]{null, "", "ignored"}) {
            assertEquals("", original.withDontInlineFunctions(false, ignored).dontInlinePrefix());
            assertEquals("", new RawRequest(SHADER, new int[]{10, 11}, 2, 3, 1,
                    7, 8, 9, false, false, ignored).dontInlinePrefix());
        }
        assertEquals(" wg_ ", original.withDontInlineFunctions(" wg_ ").dontInlinePrefix());
        assertArrayEquals(new int[]{10, 11}, original.inputWords());
    }

    @Test
    void invalidGeometryProfilesAndSliceRangesFailClosed() {
        for (int[] geometry : new int[][]{{0, 3, 1}, {-1, 3, 1}, {2, 0, 1},
                {2, -1, 1}, {2, 3, 0}, {2, 3, -1}, {3, 3, 1},
                {Integer.MAX_VALUE, 3, Integer.MAX_VALUE}}) {
            assertThrows(IllegalArgumentException.class, () -> new RawRequest(SHADER, new int[]{10, 11},
                    geometry[0], geometry[1], geometry[2], 7, 8, 9, false, false, ""));
        }
        for (int[] states : new int[][]{{-1, 8, 9}, {7, -1, 9}, {7, 8, -1}}) {
            assertThrows(IllegalArgumentException.class, () -> new RawRequest(SHADER, new int[]{10, 11},
                    2, 3, 1, states[0], states[1], states[2], false, false, ""));
        }
        assertThrows(NullPointerException.class, () -> request(null, 1));
        assertThrows(NullPointerException.class, () -> new RawRequest(null, new int[]{10, 11},
                2, 3, 1, 7, 8, 9, false, false, ""));
        var reference = new WorldgenShaderCompiler.Shader(SHADER.source(), "reference",
                NumericProfile.JAVA_REFERENCE, 64);
        assertThrows(IllegalArgumentException.class, () -> new RawRequest(reference, new int[]{10, 11},
                2, 3, 1, 7, 8, 9, false, false, ""));
        var draft = new WorldgenShaderCompiler.Shader(SHADER.source(), "draft", NumericProfile.GPU_NATIVE_DRAFT, 64);
        assertSame(draft, new RawRequest(draft, new int[]{10, 11}, 2, 3, 1,
                7, 8, 9, false, false, "").shader());
        var original = request(new int[]{10, 11, 20, 21}, 2);
        for (int[] range : new int[][]{{-1, 1}, {0, 0}, {0, -1}, {2, 1}, {1, 2},
                {Integer.MAX_VALUE, Integer.MAX_VALUE}}) {
            assertThrows(IllegalArgumentException.class, () -> original.slice(range[0], range[1]));
        }
    }

    @Test
    void objectMethodsRetainComponentAndArrayIdentitySemantics() {
        var original = request(new int[]{10, 11}, 1);
        var samePolicy = original.withPipelineOptimizationDisabled(false);
        assertEquals(original, samePolicy);
        assertEquals(original.hashCode(), samePolicy.hashCode());
        assertEquals(original.toString(), samePolicy.toString());
        assertNotEquals(original, request(new int[]{10, 11}, 1));
        assertNotEquals(original, original.withPipelineOptimizationDisabled(true));
        assertNotEquals(original, original.withDontInlineFunctions("wg_"));
        assertNotEquals(original, null);
        assertNotEquals(original, "RawRequest");
        assertEquals(3, RawRequest.class.getConstructors().length);
    }

    private static RawRequest request(int[] words, int count) {
        return new RawRequest(SHADER, words, 2, 3, count, 7, 8, 9, false, false, "");
    }

    private static void assertGeometry(RawRequest request, int count, int words) {
        assertSame(SHADER, request.shader());
        assertEquals(2, request.inputWordsPerElement());
        assertEquals(3, request.outputWordsPerElement());
        assertEquals(count, request.elementCount());
        assertEquals(words, request.inputWordCount());
        assertEquals(7, request.defaultStateId());
        assertEquals(8, request.airStateId());
        assertEquals(9, request.invalidStateId());
    }
}
