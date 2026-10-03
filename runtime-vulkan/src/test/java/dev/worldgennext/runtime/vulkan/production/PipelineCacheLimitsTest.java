// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan.production;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PipelineCacheLimitsTest {
    @Test void absenceRetainsCurrentCheckpointDefaultsWithoutNativeLoading() {
        assertEquals(new PipelineCacheLimits(8, 32), PipelineCacheLimits.fromProperties(key -> null));
    }

    @Test void independentOptionsAndMaximumBoundsAreAccepted() {
        assertEquals(new PipelineCacheLimits(256, 128), PipelineCacheLimits.fromProperties(
                Map.of(PipelineCacheLimits.ORDINARY_PROPERTY, "256", PipelineCacheLimits.REUSABLE_PROPERTY, "128")::get));
        assertEquals(new PipelineCacheLimits(64, 32), PipelineCacheLimits.fromProperties(
                Map.of(PipelineCacheLimits.ORDINARY_PROPERTY, "64")::get));
        assertEquals(new PipelineCacheLimits(8, 1), PipelineCacheLimits.fromProperties(
                Map.of(PipelineCacheLimits.REUSABLE_PROPERTY, "1")::get));
    }

    @Test void malformedAndOversizedOptionsFailClosed() {
        for (String value : new String[]{"", "0", "-1", "+1", "01", " 8", "8 ", "1.5", "257", "999999999999"}) {
            assertThrows(IllegalArgumentException.class, () -> PipelineCacheLimits.fromProperties(
                    Map.of(PipelineCacheLimits.ORDINARY_PROPERTY, value)::get), value);
        }
        assertThrows(IllegalArgumentException.class, () -> PipelineCacheLimits.fromProperties(
                Map.of(PipelineCacheLimits.REUSABLE_PROPERTY, "129")::get));
        assertThrows(NullPointerException.class, () -> PipelineCacheLimits.fromProperties(null));
    }

    @Test void directConstructionCannotBypassBounds() {
        for (int value : new int[]{-1, 0, 257, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new PipelineCacheLimits(value, 32));
        }
        for (int value : new int[]{-1, 0, 129, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new PipelineCacheLimits(8, value));
        }
    }

    @Test void enlargedCacheStillEvictsAndReturnsEveryOwnedValueForDisposal() {
        var limits = new PipelineCacheLimits(64, 32);
        var cache = new PipelineCache<Integer>(limits.ordinaryEntries());
        for (int i = 0; i < 64; i++) assertTrue(cache.putAndCollectEvicted("stage-" + i, i).isEmpty());
        assertEquals(0, cache.get("stage-0"));
        assertEquals(java.util.List.of(1), cache.putAndCollectEvicted("stage-64", 64));
        assertEquals(64, cache.size());
        assertNull(cache.get("stage-1"));
        assertEquals(64, cache.clear().stream().distinct().count());
        assertEquals(0, cache.size());
    }
}
