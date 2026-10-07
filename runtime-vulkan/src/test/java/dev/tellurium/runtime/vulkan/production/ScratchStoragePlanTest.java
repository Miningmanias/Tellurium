// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScratchStoragePlanTest {
    @Test
    void retainsFittingCapacitiesForPartialBatches() {
        assertEquals(new ScratchStoragePlan(256, 128), ScratchStoragePlan.fit(12, 8, 256, 128, 384));
        assertEquals(new ScratchStoragePlan(512, 128), ScratchStoragePlan.fit(512, 8, 256, 128, 640));
    }

    @Test
    void opposingHighWaterMarksDoNotExceedBudget() {
        assertEquals(new ScratchStoragePlan(8, 240), ScratchStoragePlan.fit(8, 240, 240, 8, 256));
        assertEquals(new ScratchStoragePlan(8, 8), ScratchStoragePlan.fit(8, 8, 240, 8, 16));
    }

    @Test
    void rejectsOversizedRequestsAndBadGeometryWithoutOverflow() {
        assertThrows(IllegalArgumentException.class, () -> ScratchStoragePlan.fit(8, 9, 0, 0, 16));
        assertThrows(IllegalArgumentException.class, () -> ScratchStoragePlan.fit(0, 8, 0, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> ScratchStoragePlan.fit(8, 8, -1, 0, 100));
        assertThrows(IllegalArgumentException.class,
                () -> ScratchStoragePlan.fit((long) Integer.MAX_VALUE + 1, 8, 0, 0, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ScratchStoragePlan.fit(8, 8, 0, 0, 0));
    }

    @Test
    void sumsCapacitiesAsLongInsteadOfSignedInt() {
        long capacity = Integer.MAX_VALUE;
        assertEquals(new ScratchStoragePlan(capacity, capacity),
                ScratchStoragePlan.fit(capacity, capacity, 0, 0, capacity * 2));
    }
}
