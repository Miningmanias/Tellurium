// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan;

import dev.tellurium.compiler.vulkan.worldgen.NativeDraftMath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeDraftMathTest {
    private static final String COMPARISONS = """
            #version 450
            bool wg_fp64_equal(uvec2 left, uvec2 right) { return old_equal(left, right); }
            bool wg_fp64_less(uvec2 left, uvec2 right) { return old_less(left, right); }
            bool wg_fp64_less_equal(uvec2 left, uvec2 right) { return old_le(left, right); }
            """;

    @Test
    void fp64DraftComparisonsUseHardwareIncludingSignedZeroRangeBoundaries() {
        String source = NativeDraftMath.rewrite(COMPARISONS, true, true);
        assertTrue(source.contains("packDouble2x32(left) == packDouble2x32(right)"));
        assertTrue(source.contains("packDouble2x32(left) < packDouble2x32(right)"));
        assertTrue(source.contains("packDouble2x32(left) <= packDouble2x32(right)"));
        assertTrue(!source.contains("old_less"));
    }

    @Test
    void fp32OnlyDraftDoesNotChangeTheFp64ComparisonDomain() {
        String source = NativeDraftMath.rewrite(COMPARISONS, true, false);
        assertTrue(source.contains("old_equal(left, right)"));
        assertTrue(source.contains("old_less(left, right)"));
        assertTrue(source.contains("old_le(left, right)"));
    }
}
