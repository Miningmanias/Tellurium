// SPDX-License-Identifier: MIT
package dev.tellurium.spatial;

import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

class ByteBudgetTest {
    @Test void exactAdmissionAndIdempotentRelease() {
        ByteBudget budget = new ByteBudget(10);
        var six = budget.tryReserve(6).orElseThrow(); var four = budget.tryReserve(4).orElseThrow();
        assertEquals(10, budget.usedBytes()); assertTrue(budget.tryReserve(1).isEmpty());
        six.close(); six.close(); assertTrue(six.isClosed()); assertEquals(4, budget.usedBytes());
        four.close(); assertEquals(10, budget.availableBytes());
    }
    @Test void maximumCapacityDoesNotOverflowAndZeroIsLegal() {
        ByteBudget budget = new ByteBudget(Long.MAX_VALUE);
        var reservation = budget.tryReserve(Long.MAX_VALUE).orElseThrow();
        assertTrue(budget.tryReserve(1).isEmpty());
        budget.tryReserve(0).orElseThrow().close(); assertEquals(Long.MAX_VALUE, budget.usedBytes());
        reservation.close(); assertEquals(0, budget.usedBytes());
        assertThrows(IllegalArgumentException.class, () -> new ByteBudget(-1));
        assertThrows(IllegalArgumentException.class, () -> budget.tryReserve(-1));
    }
    @Test @Timeout(10) void concurrentAdmissionsAndReleasesStayWithinCapacity() throws Exception {
        ByteBudget budget = new ByteBudget(64);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            var work = new ArrayList<Future<?>>();
            for (int n = 0; n < 8; n++) work.add(pool.submit(() -> {
                for (int i = 0; i < 2000; i++) budget.tryReserve(8).ifPresent(reservation -> {
                    assertTrue(budget.usedBytes() <= 64); reservation.close(); reservation.close();
                });
            }));
            for (var future : work) future.get();
        }
        assertEquals(0, budget.usedBytes());
    }
}
