// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import dev.tellurium.engine.worldgen.CommitCoordinator;
import dev.tellurium.material.chunk.BlockStateTable;
import dev.tellurium.material.chunk.ChunkNoiseResult;
import dev.tellurium.material.chunk.ChunkResultHeader;
import dev.tellurium.material.chunk.HeightmapPayload;
import dev.tellurium.semantic.execution.ExecutionReceipt;
import dev.tellurium.semantic.identity.ContextIdentity;
import dev.tellurium.semantic.program.NumericProfile;
import dev.tellurium.semantic.program.WorldgenProgram;
import dev.tellurium.semantic.snapshot.RegistrySnapshot;
import dev.tellurium.semantic.snapshot.WorldgenSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveNoiseBridgeTest {
    @Test
    void capturesComputesCommitsAndContinuesOnlyInThatOrder() {
        RuntimeComposition runtime = runningRuntime();
        ContextIdentity context = context();
        MinecraftOwnershipToken ownership = ownership(context);
        ChunkNoiseResult result = result(context);
        ExecutionReceipt execution = execution(context);
        var events = new ArrayList<String>();
        CommitCoordinator commits = new CommitCoordinator((token, candidate, receipt) -> {
            events.add("commit");
            return new dev.tellurium.engine.worldgen.CommitReceipt(true, "published", token.expectedRevision(), receipt);
        });

        var decision = new LiveNoiseBridge(runtime).interceptAndCommit("known", new Object(), ownership,
                new MinecraftStageAdapter() {
                    @Override public Object captureInputs(Object chunk, MinecraftOwnershipToken token) {
                        events.add("capture");
                        return "captured";
                    }
                    @Override public void continueOriginalStages(Object chunk, ChunkNoiseResult candidate) {
                        events.add("continue");
                    }
                }, commits, captured -> {
                    events.add("compute");
                    return new LiveNoiseBridge.Produced(result, execution);
                });

        assertTrue(decision.intercepted());
        assertEquals(LiveNoiseBridge.State.COMMITTED, decision.state());
        assertEquals("GPU_IEEE_BITS", decision.route());
        assertEquals(java.util.List.of("capture", "compute", "commit", "continue"), events);
    }

    @Test
    void downstreamFailureKeepsCommitEvidenceButIsNotAHealthyCompletion() {
        RuntimeComposition runtime = runningRuntime();
        ContextIdentity context = context();
        MinecraftOwnershipToken ownership = ownership(context);
        ChunkNoiseResult result = result(context);
        CommitCoordinator commits = new CommitCoordinator((token, candidate, receipt) ->
                new dev.tellurium.engine.worldgen.CommitReceipt(true, "published", token.expectedRevision(), receipt));

        var decision = new LiveNoiseBridge(runtime).interceptAndCommit("known", new Object(), ownership,
                new MinecraftStageAdapter() {
                    @Override public Object captureInputs(Object chunk, MinecraftOwnershipToken token) {
                        return "captured";
                    }
                    @Override public void continueOriginalStages(Object chunk, ChunkNoiseResult candidate) {
                        throw new IllegalStateException("downstream seam failed");
                    }
                }, commits, captured -> new LiveNoiseBridge.Produced(result, execution(context)));

        assertTrue(decision.intercepted(), "the NOISE mutation did commit before the downstream failure");
        assertEquals(LiveNoiseBridge.State.COMMITTED_DOWNSTREAM_FAILED, decision.state());
        assertTrue(decision.commit().committed());
        assertTrue(decision.reason().contains("downstream seam failed"));
    }

    @Test
    void reloadDuringComputePreventsCommitAndLeavesOriginalRouteAvailable() {
        RuntimeComposition runtime = runningRuntime();
        ContextIdentity context = context();
        MinecraftOwnershipToken ownership = ownership(context);
        AtomicInteger commits = new AtomicInteger();
        var decision = new LiveNoiseBridge(runtime).interceptAndCommit("known", new Object(), ownership,
                new MinecraftStageAdapter() {
                    @Override public Object captureInputs(Object chunk, MinecraftOwnershipToken token) { return "captured"; }
                    @Override public void continueOriginalStages(Object chunk, ChunkNoiseResult result) { throw new AssertionError("must not continue"); }
                }, new CommitCoordinator((token, result, execution) -> {
                    commits.incrementAndGet();
                    throw new AssertionError("stale work must not commit");
                }), captured -> {
                    runtime.worldReloaded();
                    return new LiveNoiseBridge.Produced(result(context), execution(context));
                });

        assertFalse(decision.intercepted());
        assertEquals(LiveNoiseBridge.State.STALE, decision.state());
        assertEquals("CPU_ORIGINAL_PLANNED", decision.route());
        assertEquals(0, commits.get());
    }

    @Test
    void cancellationAfterComputePreventsCommitAndDownstreamContinuation() {
        RuntimeComposition runtime = runningRuntime();
        ContextIdentity context = context();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger commits = new AtomicInteger();
        AtomicInteger continuations = new AtomicInteger();
        var decision = new LiveNoiseBridge(runtime).interceptAndCommit("known", new Object(), ownership(context),
                new MinecraftStageAdapter() {
                    @Override public Object captureInputs(Object chunk, MinecraftOwnershipToken token) { return "captured"; }
                    @Override public void continueOriginalStages(Object chunk, ChunkNoiseResult result) { continuations.incrementAndGet(); }
                }, new CommitCoordinator((token, result, execution) -> {
                    commits.incrementAndGet();
                    return new dev.tellurium.engine.worldgen.CommitReceipt(true, "published",
                            token.expectedRevision(), execution);
                }), captured -> {
                    cancelled.set(true);
                    return new LiveNoiseBridge.Produced(result(context), execution(context));
                }, cancelled::get);

        assertFalse(decision.intercepted());
        assertEquals(LiveNoiseBridge.State.CANCELLED, decision.state());
        assertEquals(0, commits.get());
        assertEquals(0, continuations.get());
    }

    @Test
    void failedQualifiedAttemptRefusesOriginalRecoveryWithoutProof() {
        RuntimeComposition runtime = runningRuntime();
        var decision = new LiveNoiseBridge(runtime).interceptAndCommit("known", new Object(), ownership(context()),
                new MinecraftStageAdapter() {
                    @Override public Object captureInputs(Object chunk, MinecraftOwnershipToken ownership) { return "captured"; }
                    @Override public void continueOriginalStages(Object chunk, ChunkNoiseResult result) { }
                }, new CommitCoordinator((token, result, execution) -> {
                    throw new AssertionError("not reached");
                }), captured -> { throw new IllegalStateException("backend failed"); });

        assertFalse(decision.intercepted());
        assertEquals(LiveNoiseBridge.State.UNSAFE, decision.state());
        assertEquals("QUALIFIED_ROUTE_FAILED_UNSAFE", decision.route());
        assertTrue(decision.reason().contains("backend failed"));
    }

    private static RuntimeComposition runningRuntime() {
        var config = new dev.tellurium.neoforge.config.TelluriumConfig(
                dev.tellurium.neoforge.config.TelluriumConfig.Mode.AUTO_SUPPORTED,
                1, 1, 1, Duration.ofSeconds(1), true);
        var compatibility = new dev.tellurium.neoforge.compat.CompatibilityRegistry();
        compatibility.register("known", new dev.tellurium.neoforge.compat.CompatibilityRegistry.Decision(
                true, "GPU_IEEE_BITS", "qualified"));
        RuntimeComposition runtime = new RuntimeComposition(config, compatibility);
        runtime.serverStarting();
        runtime.serverReady();
        return runtime;
    }

    private static ContextIdentity context() {
        WorldgenSnapshot snapshot = WorldgenSnapshot.builder(91L, "minecraft:overworld").build();
        WorldgenProgram program = WorldgenProgram.builder().build();
        return ContextIdentity.of(snapshot, program, NumericProfile.JAVA_REFERENCE,
                "abi", "compiler", 0, 0);
    }

    private static MinecraftOwnershipToken ownership(ContextIdentity context) {
        return new MinecraftOwnershipToken(context, 0, 7, "BIOMES", "owner");
    }

    private static ExecutionReceipt execution(ContextIdentity context) {
        return ExecutionReceipt.issued("execution", "cpu", context, NumericProfile.JAVA_REFERENCE,
                context.programHash(), "abi", 0, 1).completed(1).validated(1);
    }

    private static ChunkNoiseResult result(ContextIdentity context) {
        BlockStateTable table = BlockStateTable.fromRegistry(RegistrySnapshot.minimal());
        String[] states = new String[4096];
        Arrays.fill(states, "minecraft:air");
        int[] heightmap = new int[256];
        Arrays.fill(heightmap, 16);
        return ChunkNoiseResult.ofDense(
                new ChunkResultHeader(0, 0, 0, 16, context.worldKey(), context.registryHash(), "abi"),
                table, states, new HeightmapPayload(Map.of("WORLD_SURFACE_WG", heightmap)), new boolean[4096]);
    }
}
