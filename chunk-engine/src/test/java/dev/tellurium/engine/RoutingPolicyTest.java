// SPDX-License-Identifier: MIT
package dev.tellurium.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoutingPolicyTest {
    @Test void estimatesIncludeQueueExecutionAndMaterializationAndPreferCpuOnTies() {
        assertEquals(ExecutionRoute.GPU, RoutingPolicy.choose(new RoutingPolicy.Estimate(10, 1, 2, 3), true, true, false));
        assertEquals(ExecutionRoute.CPU_PLANNED, RoutingPolicy.choose(new RoutingPolicy.Estimate(6, 1, 2, 3), true, true, false));
        assertEquals(ExecutionRoute.CPU_PLANNED, RoutingPolicy.choose(new RoutingPolicy.Estimate(10, 1, 2, 9), true, true, false));
    }
    @Test void requiredGpuNeverSilentlyFallsBack() {
        var estimate = new RoutingPolicy.Estimate(1, 10, 20, 30);
        assertThrows(UnsupportedOperationException.class, () -> RoutingPolicy.choose(estimate, true, false, true));
        assertEquals(ExecutionRoute.GPU, RoutingPolicy.choose(estimate, true, true, true));
    }
    @Test void capabilitiesRestrictChoicesAndMissingRoutesAreUnsupported() {
        var estimate = new RoutingPolicy.Estimate(1, 10, 20, 30);
        assertThrows(UnsupportedOperationException.class, () -> RoutingPolicy.choose(estimate, false, false, false));
        assertEquals(ExecutionRoute.GPU, RoutingPolicy.choose(estimate, false, true, false));
        assertEquals(ExecutionRoute.CPU_PLANNED, RoutingPolicy.choose(estimate, true, false, false));
    }
    @Test void saturationAvoidsOverflowChoosingAnExpensiveGpuRoute() {
        var estimate = new RoutingPolicy.Estimate(10, Long.MAX_VALUE, Long.MAX_VALUE, 1);
        assertEquals(Long.MAX_VALUE, estimate.gpuFinishNanos());
        assertEquals(ExecutionRoute.CPU_PLANNED, RoutingPolicy.choose(estimate, true, true, false));
        assertThrows(IllegalArgumentException.class, () -> new RoutingPolicy.Estimate(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new RoutingPolicy.Estimate(0, 0, -1, 0));
    }
}
