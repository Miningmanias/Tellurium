// SPDX-License-Identifier: MIT
package dev.tellurium.oracle.minecraft;

import org.junit.jupiter.api.Test;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessHarnessTest {
    @Test
    void positiveSubMillisecondTimeoutRetainsAUsableDeadline() {
        assertEquals(1L, ProcessHarness.timeoutMillis(Duration.ofNanos(1)));
        assertEquals(25L, ProcessHarness.timeoutMillis(Duration.ofMillis(25)));
    }

    @Test
    void oversizedTimeoutIsRejectedBeforeAChildIsStarted() {
        assertThrows(IllegalArgumentException.class,
                () -> ProcessHarness.timeoutMillis(Duration.ofSeconds(Long.MAX_VALUE)));
    }
}
