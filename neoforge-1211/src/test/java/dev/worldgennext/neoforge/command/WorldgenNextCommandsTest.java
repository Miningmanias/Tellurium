// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.command;

import dev.worldgennext.neoforge.config.WorldgenNextConfig;
import dev.worldgennext.neoforge.runtime.NativeDependencyBootstrap;
import dev.worldgennext.engine.worldgen.CommitCoordinator;
import dev.worldgennext.engine.worldgen.ResourceAdmission;
import dev.worldgennext.engine.worldgen.WorldgenCoordinator;
import dev.worldgennext.neoforge.runtime.HookTelemetry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldgenNextCommandsTest {
    @Test
    void statusExposesActionableNativeFailureWithoutBreakingTheStableFields() {
        var bootstrap = new NativeDependencyBootstrap(true);
        assertTrue(!bootstrap.initialize(() -> false));

        String status = WorldgenNextCommands.status(new WorldgenNextConfig(
                WorldgenNextConfig.Mode.AUTO_SUPPORTED, 1, 1, 1, Duration.ofSeconds(1), true),
                bootstrap, false);

        assertTrue(status.contains("native=FAILED"));
        assertTrue(status.contains("initCalls=1"));
        assertTrue(status.contains("nativeFailure=initializer returned false"));
        assertTrue(status.contains("reason=no-qualified-context"));
    }

    @Test
    void statusJsonExposesCoordinatorDimensionsWithoutCollapsingCounters() {
        var bootstrap = new NativeDependencyBootstrap(false);
        var coordinator = new WorldgenCoordinator(Runnable::run, new ResourceAdmission(64),
                (request, reservation) -> java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("not used")),
                new CommitCoordinator((token, result, execution) -> {
                    throw new AssertionError("commit must not run");
                }), 3);
        try {
            String status = WorldgenNextCommands.statusJson(new WorldgenNextConfig(
                    WorldgenNextConfig.Mode.CPU_ONLY, 1, 1, 1, Duration.ofSeconds(1), false),
                    bootstrap, false, coordinator);
            assertTrue(status.contains("\"schemaVersion\":1"));
            assertTrue(status.contains("\"queueCapacity\":3"));
            assertTrue(status.contains("\"resourceBudgetBytes\":64"));
            assertTrue(status.contains("\"requested\":0"));
            assertTrue(status.endsWith("}\n"));
        } finally {
            coordinator.close();
        }
    }

    @Test
    void statusJsonExposesHookTelemetryAsIndependentOperatorCounters() {
        var bootstrap = new NativeDependencyBootstrap(false);
        var coordinator = new WorldgenCoordinator(Runnable::run, new ResourceAdmission(64),
                (request, reservation) -> java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalStateException("not used")),
                new CommitCoordinator((token, result, execution) -> {
                    throw new AssertionError("commit must not run");
                }), 1);
        var telemetry = new HookTelemetry();
        telemetry.recordBypass();
        telemetry.recordFailure();
        var future = new java.util.concurrent.CompletableFuture<Object>();
        telemetry.recordReplacement(future);
        future.complete(new Object());
        try {
            String status = WorldgenNextCommands.statusJson(new WorldgenNextConfig(
                            WorldgenNextConfig.Mode.CPU_ONLY, 1, 1, 1, Duration.ofSeconds(1), false),
                    bootstrap, false, coordinator, telemetry);
            assertTrue(status.contains("\"qualifiedHookEnabled\":false"));
            assertTrue(status.contains("\"qualificationStatus\":\"NOT_CONFIGURED\""));
            assertTrue(status.contains("\"providerRoute\":\"NONE\""));
            assertTrue(status.contains("\"prototypeCpuLive\":false"));
            assertTrue(status.contains("\"prototypeProviderRoute\":\"NONE\""));
            assertTrue(status.contains("\"telemetry\":{\n"));
            assertTrue(status.contains("\"calls\":3"));
            assertTrue(status.contains("\"completed\":1"));
            assertTrue(status.endsWith("}\n"));
        } finally {
            coordinator.close();
        }
    }
}
