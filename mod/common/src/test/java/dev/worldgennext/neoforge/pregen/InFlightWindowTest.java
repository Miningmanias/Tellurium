// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.pregen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InFlightWindowTest {
    private static final long MB = 1L << 20;

    @Test
    void largeHeapsKeepTheConfiguredWindow() {
        assertEquals(1024, InFlightWindow.size(1024, 16384 * MB));
        assertEquals(4096, InFlightWindow.size(4096, 65536 * MB));
    }

    @Test
    void smallHeapsShrinkTheWindowButNeverBelowTheFloor() {
        assertEquals(682, InFlightWindow.size(1024, 8192 * MB));
        assertEquals(170, InFlightWindow.size(1024, 2048 * MB));
        assertEquals(32, InFlightWindow.size(1024, 256 * MB));
    }

    @Test
    void aSmallerConfiguredWindowIsNeverRaised() {
        assertEquals(64, InFlightWindow.size(64, 16384 * MB));
        // The floor of 32 applies to the heap rule only.
        assertEquals(16, InFlightWindow.size(16, 16384 * MB));
        assertEquals(16, InFlightWindow.size(16, 256 * MB));
    }
}
