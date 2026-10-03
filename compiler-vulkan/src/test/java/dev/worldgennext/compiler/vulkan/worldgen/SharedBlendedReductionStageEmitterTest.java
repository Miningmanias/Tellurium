// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan.worldgen;

import dev.worldgennext.semantic.program.NumericProfile;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source contracts and a Java scaling identity only; no native execution proof. */
class SharedBlendedReductionStageEmitterTest {
    @Test void abiBoundsAndFailureCarriersAreExplicit() {
        assertEquals(84, SharedBlendedReductionStageEmitter.SAMPLE_WORDS);
        assertEquals(8, SharedBlendedReductionStageEmitter.DIVISION_INPUT_WORDS);
        assertEquals(86, SharedBlendedReductionStageEmitter.FINISH_WORDS);
        String prepare = SharedBlendedReductionStageEmitter.prepareSource(64);
        String finish = SharedBlendedReductionStageEmitter.finishSource(64);
        for (String source : new String[]{prepare, finish}) {
            assertTrue(source.startsWith("#version 450\n"));
            assertTrue(source.contains("readonly buffer Inputs { uint inputBits[]; }"));
            assertTrue(source.contains("writeonly buffer Outputs { uint outputBits[]; }"));
            assertOrdered(source, "uint count;", "uint defaultStateId;", "uint airStateId;", "uint invalidStateId;");
            assertTrue(source.contains("bool wg_failed = false;"));
            String main = source.substring(source.lastIndexOf("void main()"));
            assertOrdered(main, "if (index >= dispatch.count) return;", "wg_failed = false;", "uint base =");
        }
        assertTrue(prepare.contains("uint base = index * 84u;"));
        assertTrue(prepare.contains("uint outputBase = index * 8u;"));
        for (int word = 0; word < 4; word++) {
            String suffix = word == 0 ? "" : " + " + word + "u";
            assertTrue(prepare.contains("outputBits[outputBase" + suffix + "] = inputBits[base" + suffix + "];"));
        }
        assertTrue(prepare.contains("outputBits[outputBase + 4u] = mainSum.x;"));
        assertTrue(prepare.contains("outputBits[outputBase + 5u] = mainSum.y;"));
        assertTrue(prepare.contains("outputBits[outputBase + 6u] = 0u;"));
        assertTrue(prepare.contains("outputBits[outputBase + 7u] = 0x40240000u;"));
        assertTrue(prepare.contains("if (wg_failed || !wg_fp64_finite(mainSum)) mainSum = wg_fp64_qnan();"));
        assertTrue(finish.contains("uvec2(inputBits[base + 84u], inputBits[base + 85u])"));
        assertTrue(finish.contains("uint base = index * 86u;"));
        assertTrue(finish.contains("uint outputBase = index * 2u;"));
        assertTrue(finish.contains("outputBits[outputBase] = result.x;"));
        assertTrue(finish.contains("outputBits[outputBase + 1u] = result.y;"));
        assertTrue(finish.contains("if (wg_failed || !wg_fp64_finite(result)) result = wg_fp64_qnan();"));
    }

    @Test void loopsPreserveOctaveOrderAndBranchesReadOnlyWhenAdmitted() {
        String prepare = SharedBlendedReductionStageEmitter.prepareSource(64);
        assertOrdered(prepare.substring(prepare.indexOf("void main()")),
                "uvec2 mainSum = uvec2(0u);", "for (uint octave = 0u; octave < 8u; octave++)",
                "uint slot = base + 4u + 2u * octave;", "if (!wg_fp64_finite(octaveValue))",
                "uvec2 factor = uvec2(0u, 0x3ff00000u + (octave << 20u));",
                "uvec2 scaled = wg_fp64_mul(octaveValue, factor);", "mainSum = wg_fp64_add(mainSum, scaled);",
                "if (!wg_fp64_finite(mainSum))");
        String finish = SharedBlendedReductionStageEmitter.finishSource(64);
        String reduction = finish.substring(finish.indexOf("uvec2 wg_shared_blended_finish"), finish.lastIndexOf("void main()"));
        assertOrdered(reduction, "wg_fp64_add(quotient, one)",
                "wg_fp64_mul(shifted, uvec2(0u, 0x3fe00000u))",
                "bool skipMin = wg_fp64_less_equal(one, d16);",
                "bool skipMax = wg_fp64_less_equal(d16, zero);", "uvec2 d8 = zero;", "uvec2 d9 = zero;");
        for (boolean min : new boolean[]{true, false}) {
            String branch = block(reduction, "if (!skip" + (min ? "Min" : "Max") + ")");
            String sum = min ? "d8" : "d9";
            String slot = "uint slot = base + " + (min ? 20 : 52) + "u + 2u * octave;";
            assertOrdered(branch, "for (uint octave = 0u; octave < 16u; octave++)", slot,
                    "uvec2 octaveValue = uvec2(inputBits[slot], inputBits[slot + 1u]);",
                    "if (!wg_fp64_finite(octaveValue)) { wg_failed = true; return wg_fp64_qnan(); }",
                    "uvec2 factor = uvec2(0u, 0x3ff00000u + (octave << 20u));",
                    "uvec2 scaled = wg_fp64_mul(octaveValue, factor);",
                    sum + " = wg_fp64_add(" + sum + ", scaled);",
                    "if (!wg_fp64_finite(" + sum + ")) { wg_failed = true; return wg_fp64_qnan(); }");
            assertEquals(1, occurrences(reduction, slot), "branch reads must not be hoisted");
        }
        assertEquals(2, occurrences(reduction, "uvec2 octaveValue ="));
        assertFalse(reduction.contains("base + 3u"));
        assertOrdered(reduction,
                "uvec2 lower = wg_fp64_mul(d8, uvec2(0u, 0x3f600000u));",
                "uvec2 upper = wg_fp64_mul(d9, uvec2(0u, 0x3f600000u));",
                "uvec2 blend = wg_fp64_max(zero, wg_fp64_min(one, d16));",
                "uvec2 delta = wg_fp64_sub(upper, lower);",
                "uvec2 weighted = wg_fp64_mul(blend, delta);",
                "uvec2 lerp = wg_fp64_add(lower, weighted);",
                "return wg_fp64_mul(lerp, uvec2(0u, 0x3f800000u));");
    }

    @Test void canonicalSourcesAreIntegerOnlyAndHaveCompleteSelectedHelperClosures() {
        for (boolean prepare : new boolean[]{true, false}) {
            String source = prepare ? SharedBlendedReductionStageEmitter.prepareSource(64)
                    : SharedBlendedReductionStageEmitter.finishSource(64);
            assertEquals(source, prepare ? SharedBlendedReductionStageEmitter.prepareSource(64)
                    : SharedBlendedReductionStageEmitter.finishSource(64));
            SpirvNumericContract.require(source, NumericProfile.GPU_IEEE_BITS);
            for (String forbidden : new String[]{"wg_fp64_div", "wg_fp64_lerp", "wg_fp32_", "wg_noise_", "wg_node_", "permutation"}) {
                assertFalse(source.contains(forbidden), forbidden);
            }
            Set<String> roots = prepare
                    ? Set.of("wg_fp64_finite", "wg_fp64_qnan", "wg_fp64_mul", "wg_fp64_add")
                    : Set.of("wg_fp64_finite", "wg_fp64_qnan", "wg_fp64_mul", "wg_fp64_add",
                            "wg_fp64_sub", "wg_fp64_less_equal", "wg_fp64_min", "wg_fp64_max");
            String closure = IntegerIeeeEmitter.helperSource(roots);
            assertTrue(source.contains(closure));
            var declarations = Pattern.compile("(?m)^(?:uint|int|bool|uvec2|uvec4)\\s+(wg_\\w+)\\(").matcher(source);
            var names = new HashSet<String>();
            while (declarations.find()) assertTrue(names.add(declarations.group(1)), "duplicate helper");
            var calls = Pattern.compile("\\b(wg_\\w+)\\s*\\(").matcher(source);
            while (calls.find()) assertTrue(names.contains(calls.group(1)), "unresolved helper " + calls.group(1));
        }
    }

    @Test void localSizeLimitsMatchSharedDivisionConvention() {
        for (int bad : new int[]{Integer.MIN_VALUE, -1, 0, 1025, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> SharedBlendedReductionStageEmitter.prepareSource(bad));
            assertThrows(IllegalArgumentException.class, () -> SharedBlendedReductionStageEmitter.finishSource(bad));
        }
        for (int valid : new int[]{1, 64, 1024}) {
            assertTrue(SharedBlendedReductionStageEmitter.prepareSource(valid).contains("local_size_x=" + valid + ","));
            assertTrue(SharedBlendedReductionStageEmitter.finishSource(valid).contains("local_size_x=" + valid + ","));
        }
    }

    @Test void normalPowerTwoCarriersMatchJavaDivisionForAdversarialFiniteBits() {
        // This proves the Java arithmetic substitution, not execution of GLSL helpers.
        long[] magnitudes = {0L, 1L, 2L, 3L, 127L, 128L, 129L, 255L, 256L, 257L,
                511L, 512L, 513L, 0x0007ffffffffffffL, 0x000ffffffffffffeL,
                0x000fffffffffffffL, 0x0010000000000000L, 0x0010000000000001L,
                0x00100000000001ffL, 0x3fefffffffffffffL, 0x3ff0000000000000L,
                0x3ff0000000000001L, 0x7feffffffffffffeL, 0x7fefffffffffffffL};
        int[] exponents = {-9, -7, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
        for (int exponent : exponents) {
            long factorBits = Integer.toUnsignedLong(0x3ff00000 + (exponent << 20)) << 32;
            double factor = Double.longBitsToDouble(factorBits);
            assertEquals(Double.doubleToRawLongBits(Math.scalb(1.0, exponent)), factorBits);
            double divisor = Math.scalb(1.0, -exponent);
            for (long magnitude : magnitudes) {
                for (long sign : new long[]{0L, Long.MIN_VALUE}) {
                    long bits = magnitude | sign;
                    double value = Double.longBitsToDouble(bits);
                    assertTrue(Double.isFinite(value));
                    assertEquals(Double.doubleToRawLongBits(value / divisor),
                            Double.doubleToRawLongBits(value * factor),
                            "bits=" + Long.toHexString(bits) + " exponent=" + exponent);
                }
            }
        }
    }

    private static int occurrences(String source, String text) {
        return source.split(Pattern.quote(text), -1).length - 1;
    }

    private static void assertOrdered(String source, String... fragments) {
        int next = 0;
        for (String fragment : fragments) {
            int position = source.indexOf(fragment, next);
            assertTrue(position >= 0, "missing/out-of-order: " + fragment);
            next = position + fragment.length();
        }
    }

    private static String block(String source, String marker) {
        int start = source.indexOf(marker);
        assertTrue(start >= 0, marker);
        int opening = source.indexOf('{', start);
        int depth = 1;
        int end = opening + 1;
        while (depth > 0 && end < source.length()) {
            char character = source.charAt(end++);
            if (character == '{') depth++;
            if (character == '}') depth--;
        }
        assertEquals(0, depth, "unterminated branch");
        return source.substring(opening + 1, end - 1);
    }
}
