// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpirvModuleCacheTest {
    @Test void byteBoundAndLruHitsEvictOnlyTheLeastRecentlyUsedModule() {
        var cache = new SpirvModuleCache(8, 12);
        cache.put("a", new byte[4]);
        cache.put("b", new byte[4]);
        cache.put("c", new byte[4]);
        assertNotNull(cache.get("a"));
        cache.put("d", new byte[4]);
        assertNull(cache.get("b"));
        assertNotNull(cache.get("a"));
        assertEquals(12, cache.telemetry().retainedBytes());
        assertEquals(3, cache.telemetry().retainedEntries());
        assertEquals(1, cache.telemetry().evictions());
    }

    @Test void entryBoundAppliesEvenWhenTheByteBudgetHasSpace() {
        var cache = new SpirvModuleCache(2, Long.MAX_VALUE);
        cache.put("a", new byte[4]);
        cache.put("b", new byte[8]);
        cache.put("c", new byte[4]);
        assertNull(cache.get("a"));
        assertEquals(2, cache.telemetry().retainedEntries());
        assertEquals(12, cache.telemetry().retainedBytes());
    }

    @Test void replacementAndOversizedRejectionDoNotLeakBytesOrKeepStaleIdentity() {
        var cache = new SpirvModuleCache(8, 12);
        cache.put("a", new byte[4]);
        cache.put("b", new byte[4]);
        cache.put("a", new byte[8]);
        assertEquals(12, cache.telemetry().retainedBytes());
        assertEquals(0, cache.telemetry().evictions());
        assertFalse(cache.put("a", new byte[16]));
        assertNull(cache.get("a"));
        assertNotNull(cache.get("b"));
        assertEquals(4, cache.telemetry().retainedBytes());
        assertEquals(1, cache.telemetry().oversizedRejections());
        cache.clear();
        assertNull(cache.get("b"));
        assertEquals(0, cache.telemetry().retainedBytes());
        assertEquals(0, cache.telemetry().retainedEntries());
        assertEquals(1, cache.telemetry().oversizedRejections());
    }

    @Test void putAndGetDefensivelyOwnModules() {
        var cache = new SpirvModuleCache(2, 8);
        byte[] source = {1, 2, 3, 4};
        cache.put("a", source);
        source[0] = 99;
        byte[] first = cache.get("a");
        assertEquals(1, first[0]);
        first[0] = 88;
        assertEquals(1, cache.get("a")[0]);
    }

    @Test void invalidRequestsLeaveExistingStorageUntouched() {
        assertThrows(IllegalArgumentException.class, () -> new SpirvModuleCache(0, 4));
        assertThrows(IllegalArgumentException.class, () -> new SpirvModuleCache(1, 0));
        var cache = new SpirvModuleCache(1, 4);
        cache.put("a", new byte[4]);
        var before = cache.telemetry();
        assertThrows(IllegalArgumentException.class, () -> cache.put("a", new byte[3]));
        assertThrows(IllegalArgumentException.class, () -> cache.put("a", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> cache.put("a", null));
        assertThrows(IllegalArgumentException.class, () -> cache.put(" ", new byte[4]));
        assertThrows(NullPointerException.class, () -> cache.get(null));
        assertEquals(before, cache.telemetry());
        assertNotNull(cache.get("a"));
        assertThrows(IllegalArgumentException.class, () -> new SpirvModuleCache.Telemetry(2, 4, 1, 4, 0, 0));
    }
}
