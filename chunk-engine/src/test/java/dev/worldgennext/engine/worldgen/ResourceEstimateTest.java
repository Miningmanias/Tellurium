// SPDX-License-Identifier: MIT
package dev.worldgennext.engine.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourceEstimateTest {
    @Test
    void explicitComponentsReserveExactlyTheirCheckedTotal() {
        ResourceEstimate estimate = new ResourceEstimate(3, 5, 7, 11, 13);
        assertEquals(39, estimate.totalBytes());

        ResourceAdmission admission = new ResourceAdmission(39);
        ResourceReservation reservation = admission.tryReserve(estimate).orElseThrow();
        try {
            assertEquals(3, reservation.snapshotBytes());
            assertEquals(5, reservation.stagingBytes());
            assertEquals(7, reservation.outputBytes());
            assertEquals(11, reservation.readbackBytes());
            assertEquals(13, reservation.applicationBytes());
            assertEquals(39, reservation.totalBytes());
            assertEquals(39, admission.usedBytes());
        } finally {
            reservation.close();
        }
        assertEquals(0, admission.usedBytes());
    }

    @Test
    void legacySingleEstimateRemainsConservativeAndExplicit() {
        GenerationRequest request = new GenerationRequest(
                new WorkKey(
                        dev.worldgennext.semantic.identity.ContextIdentity.of(
                                dev.worldgennext.semantic.snapshot.WorldgenSnapshot.builder(1, "minecraft:overworld").build(),
                                dev.worldgennext.semantic.program.WorldgenProgram.builder().build(),
                                dev.worldgennext.semantic.program.NumericProfile.JAVA_REFERENCE,
                                "abi-v2", "compiler-v2", 0, 0),
                        0, 0, dev.worldgennext.engine.GenerationStage.NOISE, "noise"),
                "owner", 0, 8, false);
        assertEquals(40, request.estimatedBytes());
        assertEquals(new ResourceEstimate(8, 8, 8, 8, 8), request.resources());
    }

    @Test
    void negativeAndOverflowEstimatesFailBeforeAdmission() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceEstimate(-1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ResourceEstimate(
                Long.MAX_VALUE, 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ResourceEstimate.uniform(0));

        ResourceAdmission admission = new ResourceAdmission(10);
        assertTrue(admission.tryReserve(new ResourceEstimate(6, 2, 1, 1, 1)).isEmpty());
        assertEquals(0, admission.usedBytes());
    }
}
