// SPDX-License-Identifier: MIT
package dev.worldgennext.engine;

import dev.worldgennext.engine.worldgen.CoordinatorSnapshot;
import dev.worldgennext.engine.worldgen.DrainController;
import dev.worldgennext.engine.worldgen.ResourceAdmission;
import dev.worldgennext.engine.worldgen.WorldgenCoordinator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinatorSnapshotTest {
    @Test
    void snapshotSeparatesQueueReservationsAndTerminalRetention() {
        var coordinator = new WorldgenCoordinator(Runnable::run,
                new ResourceAdmission(100),
                (request, reservation) -> java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("not used")),
                new dev.worldgennext.engine.worldgen.CommitCoordinator(
                        (token, result, receipt) -> throwFailure()), 2);
        try {
            CoordinatorSnapshot running = coordinator.snapshot();
            assertEquals(DrainController.State.RUNNING, running.lifecycle());
            assertTrue(running.admitting());
            assertEquals(0, running.queueDepth());
            assertEquals(2, running.queueCapacity());
            assertEquals(0, running.activeRecords());
            assertEquals(0, running.retainedTerminalRecords());
            assertEquals(0, running.reservedBytes());
            assertEquals(100, running.resourceBudgetBytes());
            assertEquals(0, running.counters().requested());

            coordinator.drain(Duration.ofSeconds(1)).toCompletableFuture().join();
            CoordinatorSnapshot draining = coordinator.snapshot();
            assertEquals(DrainController.State.DRAINING, draining.lifecycle());
            assertFalse(draining.admitting());
        } finally {
            coordinator.close();
        }
    }

    private static dev.worldgennext.engine.worldgen.CommitReceipt throwFailure() {
        throw new AssertionError("commit handler must not be called");
    }
}
