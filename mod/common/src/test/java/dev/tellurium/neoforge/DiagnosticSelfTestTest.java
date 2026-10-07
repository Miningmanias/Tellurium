// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticSelfTestTest {
    @Test void pureSelftestHasCompleteCoverage() {
        var result = DiagnosticSelfTest.run();
        assertEquals(65, result.densityCompared());
        assertEquals(4096, result.materialCompared());
        assertTrue(result.passed());
    }
    @Test void partialCoverageCannotPass() {
        assertFalse(new DiagnosticSelfTest.Result(0, 0, 0).passed());
        assertFalse(new DiagnosticSelfTest.Result(65, 4096, 1).passed());
    }
}
