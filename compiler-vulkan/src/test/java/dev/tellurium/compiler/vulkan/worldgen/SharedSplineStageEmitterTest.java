// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.worldgen;

import java.util.HashSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Source contracts only: no numerical or GPU qualification is implied. */
class SharedSplineStageEmitterTest {
    @Test void fragmentProvidesOnlySplineAndMainWithExistingHelperRoots() {
        String source = SharedSplineStageEmitter.source();
        assertTrue(source.contains("uint wg_shared_spline_value(uint base, uint knots)"));
        assertTrue(source.contains("void main()"));
        assertFalse(source.contains("#version"));
        assertFalse(source.contains("layout("));
        assertFalse(source.contains("uint inputBits[]"));
        assertFalse(source.contains("uint outputBits[]"));
        assertFalse(source.contains("bool wg_failed"));
        assertFalse(Pattern.compile("\\b(?:uint|bool|uvec2)\\s+wg_fp(?:32|64)_").matcher(source).find());
        assertFalse(Pattern.compile("\\buint\\s+\\w+\\s*\\[").matcher(source).find());

        var references = new HashSet<String>();
        var calls = Pattern.compile("\\b(wg_fp(?:32|64)_\\w+)\\s*\\(").matcher(source);
        while (calls.find()) references.add(calls.group(1));
        assertEquals(references, SharedSplineStageEmitter.helperRoots());
        String ieee = IntegerIeeeEmitter.source();
        for (String root : references) assertTrue(ieee.contains(root + "("), root);
        assertThrows(UnsupportedOperationException.class,
                () -> SharedSplineStageEmitter.helperRoots().add("unexpected"));
    }

    @Test void boundsPrecedeHeaderReadAndStrideAndInvalidOutputStaysNan() {
        String main = SharedSplineStageEmitter.source().split("void main\\(\\)", 2)[1];
        assertOrdered(main, "if (index >= dispatch.count) return;", "uint knots = inputBits[0];",
                "uvec2 result = wg_fp64_qnan();", "if (knots >= 1u && knots <= 64u) {",
                "uint stride = 3u + 4u * knots;", "uint base = index * stride;",
                "uint value = wg_shared_spline_value(base, knots);",
                "if (!wg_failed && wg_fp32_finite(value)) result = wg_fp64_from_fp32(value);",
                "outputBits[index * 2u] = result.x;", "outputBits[index * 2u + 1u] = result.y;");
        assertEquals(2, main.split("outputBits\\[", -1).length - 1);
        assertTrue(main.contains("wg_fp64_from_fp32(value);\n    }\n    outputBits"));
        assertOrdered(SharedSplineStageEmitter.source(),
                "if (knots < 1u || knots > 64u) return wg_fp32_qnan();",
                "uint coordinate = wg_fp64_to_fp32(uvec2(inputBits[base + 1u], inputBits[base + 2u]));");
    }

    @Test void intervalSelectionAndEndpointsReadOnlyChosenValues() {
        String source = SharedSplineStageEmitter.source();
        assertOrdered(source, "uint first = base + 3u;",
                "if (wg_fp32_less(coordinate, location0)) {",
                "uint value0 = wg_fp64_to_fp32(uvec2(inputBits[first + 2u], inputBits[first + 3u]));",
                "if (wg_fp32_zero(derivative0)) return value0;",
                "return wg_fp32_add(value0, wg_fp32_mul(derivative0, wg_fp32_sub(coordinate, location0)));",
                "for (uint segment = 0u; segment < knots - 1u; segment++) {",
                "uint left = first + 4u * segment;", "uint right = left + 4u;",
                "if (wg_fp32_less(coordinate, location1)) {",
                "uint value0 = wg_fp64_to_fp32(uvec2(inputBits[left + 2u], inputBits[left + 3u]));",
                "uint value1 = wg_fp64_to_fp32(uvec2(inputBits[right + 2u], inputBits[right + 3u]));",
                "uint last = first + 4u * (knots - 1u);",
                "uint value = wg_fp64_to_fp32(uvec2(inputBits[last + 2u], inputBits[last + 3u]));",
                "if (wg_fp32_zero(derivative)) return value;",
                "return wg_fp32_add(value, wg_fp32_mul(derivative, wg_fp32_sub(coordinate, inputBits[last])));");
        assertFalse(source.contains("wg_fp32_less_equal"));
    }

    @Test void cubicArithmeticPreservesCompilerOrderAndLerpExpansion() {
        assertOrdered(SharedSplineStageEmitter.source(),
                "uint width = wg_fp32_sub(location1, location0);",
                "uint t = wg_fp32_div(wg_fp32_sub(coordinate, location0), width);",
                "uint delta = wg_fp32_sub(value1, value0);",
                "uint a = wg_fp32_sub(wg_fp32_mul(derivative0, width), delta);",
                "uint b = wg_fp32_add(wg_fp32_negate(wg_fp32_mul(derivative1, width)), delta);",
                "uint interpolated = wg_fp32_add(value0, wg_fp32_mul(t, wg_fp32_sub(value1, value0)));",
                "uint correctionLerp = wg_fp32_add(a, wg_fp32_mul(t, wg_fp32_sub(b, a)));",
                "uint correction = wg_fp32_mul(wg_fp32_mul(t, wg_fp32_sub(0x3f800000u, t)), correctionLerp);",
                "return wg_fp32_add(interpolated, correction);");
    }

    private static void assertOrdered(String source, String... fragments) {
        int cursor = 0;
        for (String fragment : fragments) {
            int index = source.indexOf(fragment, cursor);
            assertTrue(index >= cursor, "Missing or out-of-order source fragment: " + fragment);
            cursor = index + fragment.length();
        }
    }
}
