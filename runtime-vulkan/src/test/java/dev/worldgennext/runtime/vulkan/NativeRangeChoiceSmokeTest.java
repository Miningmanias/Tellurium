// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan;

import dev.worldgennext.runtime.vulkan.production.VulkanWorldgenExecutor;
import dev.worldgennext.semantic.program.NumericProfile;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Driver-free checks; none instantiate an executor or compile SPIR-V. */
class NativeRangeChoiceSmokeTest {
    @Test
    void signedZerosAndEndpointNeighborsSelectTheJavaHalfOpenRange() {
        for (double selector : new double[]{+0.0, -0.0, -60.0, Math.nextUp(-60.0),
                Math.nextDown(321.0), Double.MIN_VALUE, -Double.MIN_VALUE}) {
            assertArrayEquals(new int[]{0, 0x80000000, 0, 0x80000000, 0, 1},
                    NativeRangeChoiceSmoke.reference(vector(selector, -0.0, 17.0)));
        }
        for (double selector : new double[]{Math.nextDown(-60.0), 321.0, Math.nextUp(321.0)}) {
            assertArrayEquals(new int[]{0, 0x40310000, 0, 0x40310000, 0, 1},
                    NativeRangeChoiceSmoke.reference(vector(selector, -0.0, 17.0)));
        }
    }

    @Test
    void stageFailureIsStickyButUnselectedNonfiniteChildrenAreLazy() {
        // Valid selector with an unread NaN/Inf preserves the selected negative zero.
        assertArrayEquals(new int[]{0, 0x80000000, 0, 0x80000000, 0, 1},
                NativeRangeChoiceSmoke.reference(vector(0.0, -0.0, Double.NaN)));
        assertArrayEquals(new int[]{0, 0x80000000, 0, 0x80000000, 0, 1},
                NativeRangeChoiceSmoke.reference(vector(321.0, Double.POSITIVE_INFINITY, -0.0)));
        // Reading a nonfinite selected child canonicalizes both parent and final result.
        assertArrayEquals(new int[]{0, 0x7ff80000, 0, 0x7ff80000, 1, 0},
                NativeRangeChoiceSmoke.reference(vector(0.0, Double.NEGATIVE_INFINITY, 17.0)));
        // An invalid selector becomes NaN, chooses out, then poisons the final result.
        for (long selector : new long[]{0x7ff0000000000000L, 0xfff0000000000000L,
                0x7ff0000000000001L, 0xfff8123456789abcL}) {
            assertArrayEquals(new int[]{0, 0x7ff80000, 0, 0x40310000, 1, 1},
                    NativeRangeChoiceSmoke.reference(new NativeRangeChoiceSmoke.Vector(
                            selector, Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(17.0))));
        }
    }

    @Test
    void comparisonRejectsMissingCoverageAndChecksEveryOutputWord() {
        var vectors = List.of(vector(0.0, -0.0, 17.0));
        int[] expected = {0, 0x80000000, 0, 0x80000000, 0, 1};
        assertEquals(0, NativeRangeChoiceSmoke.mismatches(vectors, expected));
        for (int word = 0; word < expected.length; word++) {
            int[] corrupted = expected.clone();
            corrupted[word] ^= 1;
            assertEquals(1, NativeRangeChoiceSmoke.mismatches(vectors, corrupted));
        }
        assertThrows(IllegalArgumentException.class,
                () -> NativeRangeChoiceSmoke.mismatches(vectors, new int[5]));
        assertThrows(IllegalArgumentException.class,
                () -> NativeRangeChoiceSmoke.mismatches(List.of(), new int[0]));
    }

    @Test
    void partialSlicesPreserveAllThreeRawCarriersAndCoordinates() {
        var vectors = NativeRangeChoiceSmoke.vectors();
        assertEquals(192, vectors.size());
        assertTrue(vectors.size() <= NativeRangeChoiceSmoke.MAX_CASES);
        int[] input = NativeRangeChoiceSmoke.inputWords(vectors);
        var request = new VulkanWorldgenExecutor.RawRequest(
                NativeRangeChoiceSmoke.shader(NumericProfile.GPU_IEEE_BITS),
                input, 10, 6, vectors.size());
        int visited = 0;
        for (int offset = 0; offset < vectors.size(); offset += 7) {
            int count = Math.min(7, vectors.size() - offset);
            var slice = request.slice(offset, count);
            assertEquals(count, slice.elementCount());
            assertArrayEquals(Arrays.copyOfRange(input, offset * 10, (offset + count) * 10),
                    slice.inputWords());
            visited += count;
        }
        assertEquals(vectors.size(), visited);
        assertEquals(3, request.slice(189, 3).elementCount());
        input[4] ^= 1;
        assertNotEquals(input[4], request.inputWords()[4]);
    }

    @Test
    void optionsAndProfilesFailClosedAndDraftIsExplicit() {
        assertEquals(new NativeRangeChoiceSmoke.Options(7, false),
                NativeRangeChoiceSmoke.Options.parse(new String[0]));
        assertEquals(new NativeRangeChoiceSmoke.Options(1, true),
                NativeRangeChoiceSmoke.Options.parse(new String[]{"--batch-size=1", "--native-draft"}));
        for (String arg : new String[]{"--batch-size=0", "--batch-size=65", "--cpu", "--batch-size=x"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> NativeRangeChoiceSmoke.Options.parse(new String[]{arg}));
        }
        assertThrows(IllegalArgumentException.class,
                () -> NativeRangeChoiceSmoke.shader(NumericProfile.JAVA_REFERENCE));
        String integer = NativeRangeChoiceSmoke.shader(NumericProfile.GPU_IEEE_BITS).source();
        assertFalse(java.util.regex.Pattern.compile("\\b(float|double|vec[234]|dvec[234])\\b")
                .matcher(integer.replaceAll("(?m)//.*$", "")).find());
        assertFalse(integer.contains("packDouble2x32"));
        String draft = NativeRangeChoiceSmoke.shader(NumericProfile.GPU_NATIVE_DRAFT).source();
        assertTrue(draft.contains("GPU_NATIVE_DRAFT"));
        assertTrue(draft.contains("return packDouble2x32(left) <= packDouble2x32(right);"));
    }

    private static NativeRangeChoiceSmoke.Vector vector(double selector, double in, double out) {
        return new NativeRangeChoiceSmoke.Vector(Double.doubleToRawLongBits(selector),
                Double.doubleToRawLongBits(in), Double.doubleToRawLongBits(out));
    }
}
