// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ChainStoragePlanTest {
    @Test
    void smallerRawRequestRetainsAllThreeChainSlotsWithinBudget() {
        assertArrayEquals(new long[]{452, 300, 148},
                ChainStoragePlan.fit(new long[]{444, 296}, new long[]{452, 300, 148}, 900).capacities());
    }

    @Test
    void oppositeShapeDropsUnusedSlotsAndCombinedHighWaterMarks() {
        assertArrayEquals(new long[]{152, 444},
                ChainStoragePlan.fit(new long[]{152, 444}, new long[]{452, 300, 148}, 900).capacities());
        assertArrayEquals(new long[]{452, 300, 148},
                ChainStoragePlan.fit(new long[]{452, 300, 148}, new long[]{152, 444}, 900).capacities());
    }

    @Test
    void capacitiesAreOwnedAndNewChainSlotsAreAdmitted() {
        long[] requested = {40, 32, 24};
        var plan = ChainStoragePlan.fit(requested, new long[]{16, 64}, 128);
        requested[0] = 120;
        long[] exposed = plan.capacities();
        exposed[1] = 0;
        assertArrayEquals(new long[]{40, 64, 24}, plan.capacities());
    }

    @Test
    void rejectsMissingGeometryInvalidSizesAndInsufficientAvailableBudget() {
        assertThrows(IllegalArgumentException.class, () -> ChainStoragePlan.fit(null, new long[0], 10));
        assertThrows(IllegalArgumentException.class, () -> ChainStoragePlan.fit(new long[]{1, 1}, null, 10));
        assertThrows(IllegalArgumentException.class, () -> ChainStoragePlan.fit(new long[]{1}, new long[0], 10));
        assertThrows(IllegalArgumentException.class, () -> ChainStoragePlan.fit(new long[]{1, 0}, new long[0], 10));
        assertThrows(IllegalArgumentException.class, () -> ChainStoragePlan.fit(new long[]{1, 1}, new long[]{-1}, 10));
        assertThrows(IllegalArgumentException.class, () -> ChainStoragePlan.fit(new long[]{Integer.MAX_VALUE + 1L, 1}, new long[0], Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ChainStoragePlan.fit(new long[]{1, 1}, new long[]{Integer.MAX_VALUE + 1L}, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ChainStoragePlan.fit(new long[]{1, 1}, new long[0], 1));
        assertThrows(IllegalArgumentException.class, () -> ChainStoragePlan.fit(new long[]{1, 1}, new long[0], 0));
    }
}
