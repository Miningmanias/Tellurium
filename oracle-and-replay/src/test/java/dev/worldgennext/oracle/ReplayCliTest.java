// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle;

import dev.worldgennext.material.chunk.BlockStateTable;
import dev.worldgennext.material.chunk.ChunkNoiseResult;
import dev.worldgennext.material.chunk.ChunkResultCodec;
import dev.worldgennext.material.chunk.ChunkResultHeader;
import dev.worldgennext.oracle.schema.CaptureIdentity;
import dev.worldgennext.oracle.schema.ChunkSnapshot;
import dev.worldgennext.oracle.schema.SnapshotIo;
import dev.worldgennext.oracle.schema.SnapshotField;
import dev.worldgennext.semantic.snapshot.BlockStateDescriptor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayCliTest {
    @Test
    void candidateCorpusComparisonUsesMirroredResultPathsAndRejectsMissingCases() throws Exception {
        var directory = Files.createTempDirectory("worldgennext-corpus-cli-");
        var expected = Files.createDirectories(directory.resolve("expected"));
        var candidate = Files.createDirectories(directory.resolve("candidate"));
        var table = new BlockStateTable(java.util.List.of(BlockStateDescriptor.of("minecraft:air")));
        var states = new String[16 * 256];
        java.util.Arrays.fill(states, "minecraft:air");
        var metadata = new dev.worldgennext.material.chunk.ChunkMetadataPayload(Map.of(
                "BIOMES", "biomes", "LIGHT", "light", "TICKS", "ticks",
                "BLOCK_ENTITIES", "block-entities", "STRUCTURES", "structures",
                "ENTITIES", "entities", "LIFECYCLE", "lifecycle"));
        var result = ChunkNoiseResult.ofDense(
                new ChunkResultHeader(0, 0, 0, 16, "context", table.fingerprint(), "chunk-result-v4"),
                table, states, new dev.worldgennext.material.chunk.HeightmapPayload(new int[256]),
                new boolean[states.length], metadata);
        var candidateIdentity = new CaptureIdentity("CANDIDATE", "worldgennext-candidate", "0",
                "minecraft:overworld", 0, 0, "NOISE", "context");
        var candidateSnapshot = dev.worldgennext.oracle.minecraft.ChunkResultSnapshot.candidate(result,
                candidateIdentity, Map.of("seed", "0", "dimension", "minecraft:overworld", "endpoint", "NOISE"));
        var expectedIdentity = new CaptureIdentity("ORIGINAL", "stack", "0", "minecraft:overworld",
                0, 0, "NOISE", "context");
        SnapshotIo.write(expected.resolve("case.snap"), new dev.worldgennext.oracle.schema.ChunkSnapshot(
                expectedIdentity, candidateSnapshot.fields(), candidateSnapshot.values()));
        Files.write(candidate.resolve("case.chunk"), ChunkResultCodec.encode(result));

        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int exit = ReplayCli.execute(new String[]{"compare-candidate-corpus", expected.toString(), candidate.toString()},
                new PrintStream(out), new PrintStream(err));
        assertEquals(0, exit);
        assertTrue(out.toString().contains("CANDIDATE_CORPUS_COMPARISON cases=1"));

        Files.delete(candidate.resolve("case.chunk"));
        out.reset();
        exit = ReplayCli.execute(new String[]{"compare-candidate-corpus", expected.toString(), candidate.toString()},
                new PrintStream(out), new PrintStream(err));
        assertEquals(1, exit);
        assertTrue(out.toString().contains("missing candidate result"));
    }

    @Test
    void candidateResultComparisonRejectsFullUntilTheEndpointAbiExists() throws Exception {
        var directory = Files.createTempDirectory("worldgennext-cli-");
        var expectedPath = directory.resolve("expected.snap");
        var resultPath = directory.resolve("candidate.chunk");
        var identity = new CaptureIdentity("original", "stack", "0", "minecraft:overworld",
                0, 0, "FULL", "context");
        var fields = new EnumMap<SnapshotField, String>(SnapshotField.class);
        fields.put(SnapshotField.LIFECYCLE, "full");
        SnapshotIo.write(expectedPath, new ChunkSnapshot(identity, fields, Map.of()));

        var table = new BlockStateTable(java.util.List.of(BlockStateDescriptor.of("minecraft:air")));
        var header = new ChunkResultHeader(0, 0, 0, 16, "context", table.fingerprint(), "chunk-result-v4");
        var states = new String[16 * 256];
        java.util.Arrays.fill(states, "minecraft:air");
        var heightmaps = new int[256];
        java.util.Arrays.fill(heightmaps, -1);
        Files.write(resultPath, ChunkResultCodec.encode(ChunkNoiseResult.ofDense(
                header, table, states, heightmaps, new boolean[states.length])));

        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int exit = ReplayCli.execute(new String[]{"compare-candidate-result", expectedPath.toString(), resultPath.toString()},
                new PrintStream(out), new PrintStream(err));
        assertEquals(2, exit);
        assertTrue(err.toString().contains("supports NOISE only"));
    }
}
