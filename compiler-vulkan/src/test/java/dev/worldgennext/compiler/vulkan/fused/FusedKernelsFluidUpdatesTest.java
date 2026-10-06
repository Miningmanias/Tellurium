// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan.fused;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FusedKernelsFluidUpdatesTest {
    private static String kernels(FusedNoiseCompiler.FluidUpdates rule) {
        return FusedKernels.kernels(2, 3, 1, rule);
    }

    /** The list of tested generators is keyed on the kernel text, so the earlier rule must not pick up any of the later one. */
    @Test
    void earlierRuleHasNoneOfTheLaterRulesText() {
        String earlier = kernels(FusedNoiseCompiler.FluidUpdates.BETWEEN_AQUIFERS);
        assertFalse(earlier.contains("sameStatus"));
        assertFalse(earlier.contains("i4"));
        assertTrue(earlier.contains("schedule = d1 >= FLOWING_UPDATE_SIMILARITY;"));
    }

    /** Every piece of the later rule goes in; a piece whose place in the text is gone throws instead of being skipped. */
    @Test
    void laterRuleReplacesEveryPiece() {
        String later = kernels(FusedNoiseCompiler.FluidUpdates.WHERE_NEIGHBOURS_DIFFER);
        assertNotEquals(kernels(FusedNoiseCompiler.FluidUpdates.BETWEEN_AQUIFERS), later);
        assertTrue(later.contains("bool sameStatus(ivec2 a, ivec2 b)"));
        assertTrue(later.contains("} else if (i4 >= dist) {"));
        assertTrue(later.contains("schedule = d1 >= FLOWING_UPDATE_SIMILARITY && !sameStatus(s1, ivec2(aquifer[k2 + 3], aquifer[k2 + 4]));"));
        assertTrue(later.contains("similarity(k1, i4) >= FLOWING_UPDATE_SIMILARITY"));
        assertFalse(later.contains("schedule = d1 >= FLOWING_UPDATE_SIMILARITY;"));
        // Only the aquifer function differs: the two texts have the same lines outside it.
        assertEquals(later.lines().filter(line -> line.contains("void main")).count(),
                kernels(FusedNoiseCompiler.FluidUpdates.BETWEEN_AQUIFERS).lines().filter(line -> line.contains("void main")).count());
    }
}
