// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import dev.worldgennext.material.chunk.BlockStateTable;
import dev.worldgennext.material.chunk.ChunkNoiseResult;
import dev.worldgennext.material.chunk.ChunkResultHeader;
import dev.worldgennext.neoforge.compat.CompatibilityRegistry;
import dev.worldgennext.neoforge.config.WorldgenNextConfig;
import dev.worldgennext.runtime.vulkan.DeviceCapabilities;
import dev.worldgennext.semantic.execution.ExecutionReceipt;
import dev.worldgennext.semantic.identity.ContextIdentity;
import dev.worldgennext.semantic.identity.DynamicInputIdentity;
import dev.worldgennext.semantic.program.NumericProfile;
import dev.worldgennext.semantic.snapshot.RegistrySnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class IsolatedGpuEvidenceTest {
    private static final String SHADER = "a".repeat(64);
    private static final String SPIRV = "b".repeat(64);

    @Test void logicalBackendSelectionCannotBecomeNativeDraftOrOriginalFallback() {
        assertEquals(LogicalCandidateBackend.CPU_OWNED, LogicalCandidateBackend.fromExternal("CPU_OWNED"));
        assertEquals(LogicalCandidateBackend.GPU_IEEE_BITS, LogicalCandidateBackend.fromExternal("GPU_IEEE_BITS"));
        for (String invalid : new String[]{"GPU_NATIVE_DRAFT", "CPU_ORIGINAL_PLANNED", "gpu", "", "AUTO"}) {
            assertThrows(IllegalArgumentException.class, () -> LogicalCandidateBackend.fromExternal(invalid));
        }
        assertThrows(NullPointerException.class, () -> LogicalCandidateBackend.fromExternal(null));
    }

    @Test void cpuOnlyRejectsGpuPreflightBeforeAnyNativeInitialization() {
        var runtime = new RuntimeComposition(new WorldgenNextConfig(WorldgenNextConfig.Mode.CPU_ONLY,
                1024, 1024, 1, java.time.Duration.ofSeconds(1), false), new CompatibilityRegistry());
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> LogicalCandidateBackend.GPU_IEEE_BITS.requireRuntimeCompatibility(runtime));
            LogicalCandidateBackend.CPU_OWNED.requireRuntimeCompatibility(runtime);
            assertEquals(0, runtime.nativeBootstrap().initializationCalls());
            assertEquals(NativeDependencyBootstrap.State.DISABLED, runtime.nativeBootstrap().state());
        } finally { runtime.closeCoordinator(); }
    }

    @Test void deviceCompletionAndPublishedCommitHaveSeparateCounts() {
        var fixture = fixture();
        String backend = IsolatedGpuEvidence.backendJson(32, -32, "checksum", fixture.receipt(), fixture.generation());
        assertTrue(backend.contains("\"status\":\"BACKEND_VALIDATED\""));
        assertTrue(backend.contains("\"committed\":0"));
        assertTrue(backend.contains("\"logicalGpuElements\":2048"));
        assertTrue(backend.contains("\"storageBlocks\":4096"));
        assertTrue(backend.contains("\"independentParityCompared\":false"));
        String terminal = IsolatedGpuEvidence.terminalJson(32, -32, fixture.receipt(), null);
        assertTrue(terminal.contains("\"status\":\"COMMITTED\""));
        assertTrue(terminal.contains("\"committed\":4096"));
        assertTrue(terminal.contains("\"executionId\":\"gpu-live-7\""));
        assertTrue(terminal.contains("\"releaseQualification\":false"));
    }

    @Test void prematureAndUnlinkedBackendReceiptsAreRejected() {
        var fixture = fixture();
        assertThrows(IllegalArgumentException.class, () -> IsolatedGpuEvidence.backendJson(32, -32, "c",
                fixture.receipt().committed(4096), fixture.generation()));
        assertThrows(IllegalArgumentException.class, () -> IsolatedGpuEvidence.backendJson(32, -32, "c",
                fixture.receipt().withShaderProvenance("c".repeat(64), SPIRV), fixture.generation()));
        assertThrows(IllegalArgumentException.class, () -> IsolatedGpuEvidence.backendJson(32, -32, "c",
                fixture.receipt().validated(2048), fixture.generation()));
        assertThrows(IllegalArgumentException.class, () -> IsolatedGpuEvidence.terminalJson(32, -32, null, null));
        assertThrows(IllegalArgumentException.class, () -> IsolatedGpuEvidence.terminalJson(32, -32,
                fixture.receipt().validated(0), null));
    }

    @Test void failedTerminalNeverClaimsCommitAndEscapesAllJsonControlCharacters() {
        String failed = IsolatedGpuEvidence.terminalJson(32, -32, fixture().receipt(),
                new IllegalStateException("bad\t\"input\"\n\\path"));
        assertTrue(failed.contains("\"status\":\"FAILED\""));
        assertTrue(failed.contains("\"committed\":0"));
        assertTrue(failed.contains("bad\\u0009\\\"input\\\"\\u000a\\\\path"));
        assertFalse(IsolatedGpuEvidence.terminalJson(32, -32, null, new IllegalStateException("capture"))
                .contains("\"status\":\"COMMITTED\""));
    }

    @Test void actualCommitAdapterMarksPublicationOnlyAfterTheMutationSeamSucceeds() {
        var fixture = fixture();
        var mutated = new java.util.concurrent.atomic.AtomicBoolean();
        var committer = new MinecraftChunkCommitter(ChunkMutationJournal::new, (result, journal) -> {
            assertEquals(0, fixture.receipt().committed());
            mutated.set(true);
        });
        var token = new dev.worldgennext.engine.worldgen.CommitToken("holder", fixture.receipt().context(), 1, 0);
        var publication = new dev.worldgennext.engine.worldgen.CommitCoordinator(committer)
                .publish(token, fixture.generation().gpuResult(), fixture.receipt());
        assertTrue(mutated.get());
        assertTrue(publication.committed());
        assertEquals(4096, publication.execution().committed());
        assertEquals(0, fixture.receipt().committed());
        assertEquals(fixture.receipt().executionId(), publication.execution().executionId());

        var failed = new MinecraftChunkCommitter(ChunkMutationJournal::new, (result, journal) -> {
            throw new IllegalStateException("injected mutation failure");
        });
        assertThrows(IllegalStateException.class, () -> new dev.worldgennext.engine.worldgen.CommitCoordinator(failed)
                .publish(new dev.worldgennext.engine.worldgen.CommitToken("holder-fail", fixture.receipt().context(), 1, 0),
                        fixture.generation().gpuResult(), fixture.receipt()));
        assertEquals(0, fixture.receipt().committed());
    }

    @Test void duplicateEvidenceCannotOverwriteAnEarlierExecution(@TempDir Path directory) throws Exception {
        String property = "worldgennext.candidate.liveNoiseResultDir";
        String previous = System.getProperty(property);
        try {
            System.setProperty(property, directory.toString());
            var fixture = fixture();
            IsolatedGpuEvidence.writeBackend(32, -32, fixture.generation().gpuResult(), fixture.receipt(), fixture.generation());
            Path output = directory.resolve("chunk-32--32.gpu-receipt.json");
            String original = Files.readString(output);
            assertThrows(IllegalStateException.class, () -> IsolatedGpuEvidence.writeBackend(32, -32,
                    fixture.generation().gpuResult(), fixture.receipt(), fixture.generation()));
            assertEquals(original, Files.readString(output));
            IsolatedGpuEvidence.writeTerminal(32, -32, fixture.receipt(), null);
            assertEquals("PASS\nCOMMITTED\n", Files.readString(directory.resolve("chunk-32--32.candidate-status")));
            assertThrows(IllegalStateException.class, () -> IsolatedGpuEvidence.writeTerminal(32, -32,
                    fixture.receipt(), new IllegalStateException("late")));
        } finally {
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
        }
    }

    private record Fixture(ExecutionReceipt receipt, MinecraftGpuCandidate.DeviceGeneration generation) { }

    private static Fixture fixture() {
        var table = BlockStateTable.fromRegistry(RegistrySnapshot.minimal());
        var context = new ContextIdentity("snapshot", table.fingerprint(), "program", NumericProfile.GPU_IEEE_BITS,
                MinecraftGpuNoiseProvider.RESULT_ABI, MinecraftGpuNoiseProvider.COMPILER_VERSION,
                1, DynamicInputIdentity.empty(), 2);
        String[] states = new String[4096];
        Arrays.fill(states, "minecraft:air");
        var result = ChunkNoiseResult.ofDense(new ChunkResultHeader(32, -32, 0, 16,
                context.worldKey(), table.fingerprint(), MinecraftGpuNoiseProvider.RESULT_ABI),
                table, states, new int[256], new boolean[4096]);
        var receipt = ExecutionReceipt.issued("gpu-live-7", "gpu", context, NumericProfile.GPU_IEEE_BITS,
                "program", MinecraftGpuNoiseProvider.RESULT_ABI, SHADER, SPIRV, 2, 4096).completed(4096).validated(4096);
        var generation = new MinecraftGpuCandidate.DeviceGeneration(result, 2048,
                new DeviceCapabilities("fixture", 0, 0, 2, 0, false, false, false, false),
                SHADER, SPIRV, NumericProfile.GPU_IEEE_BITS);
        return new Fixture(receipt, generation);
    }
}
