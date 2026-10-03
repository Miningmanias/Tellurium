// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle;

import dev.worldgennext.oracle.minecraft.ChunkSnapshotComparator;
import dev.worldgennext.oracle.minecraft.ChunkResultSnapshot;
import dev.worldgennext.oracle.schema.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Arrays;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class V02OracleTest {
    private static ChunkSnapshot snapshot(String block) {
        var id = new CaptureIdentity("original", "stack", "0", "minecraft:overworld", 0, 0, "NOISE", "context");
        var fields = new java.util.EnumMap<SnapshotField, String>(SnapshotField.class);
        for (SnapshotField field : SnapshotField.values()) fields.put(field, field == SnapshotField.BLOCK_STATES ? block : field.name());
        return new ChunkSnapshot(id, fields, Map.of("block:0", block));
    }
    private static ChunkSnapshot candidateSnapshot(String block) {
        var id = new CaptureIdentity("candidate", "candidate-stack", "0", "minecraft:overworld", 0, 0, "NOISE", "context");
        var fields = new java.util.EnumMap<SnapshotField, String>(SnapshotField.class);
        for (SnapshotField field : SnapshotField.values()) fields.put(field, field == SnapshotField.BLOCK_STATES ? block : field.name());
        return new ChunkSnapshot(id, fields, Map.of("block:0", block));
    }
    @Test void comparatorDetectsMeaningfulMutation() {
        var equal = new ChunkSnapshotComparator().compare(snapshot("stone"), snapshot("stone"), EnumSet.of(SnapshotField.BLOCK_STATES, SnapshotField.HEIGHTMAPS));
        var changed = new ChunkSnapshotComparator().compare(snapshot("stone"), snapshot("dirt"), EnumSet.of(SnapshotField.BLOCK_STATES, SnapshotField.HEIGHTMAPS));
        assertTrue(equal.passed());
        assertFalse(changed.passed());
        assertFalse(changed.differences().isEmpty());
    }
    @Test void comparatorAllowsSeparateSourceIdentitiesForTheSameLogicalCase() {
        var comparison = new ChunkSnapshotComparator().compare(snapshot("stone"), candidateSnapshot("stone"), EnumSet.of(SnapshotField.BLOCK_STATES, SnapshotField.HEIGHTMAPS));
        assertTrue(comparison.passed(), comparison.reason());
        assertTrue(comparison.coverage().complete());
    }
    @Test void snapshotIoRoundTripsIdentityFromTheArtifact(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("capture.snap");
        SnapshotIo.write(path, snapshot("stone"));
        var read = SnapshotIo.read(path);
        assertEquals(snapshot("stone").identity(), read.identity());
        assertEquals("stone", read.field(SnapshotField.BLOCK_STATES));
    }
    @Test void snapshotIoRejectsDuplicateOrMismatchedArtifactIdentity() {
        String encoded = SnapshotIo.encode(snapshot("stone"));
        assertThrows(IllegalArgumentException.class, () -> SnapshotIo.decode(
                encoded.replace("identity=original/stack/0/minecraft:overworld/0/0/NOISE/context\n",
                        "identity=original/stack/0/minecraft:overworld/0/0/NOISE/context\n"
                                + "identity=original/stack/0/minecraft:overworld/0/0/NOISE/context\n"),
                snapshot("stone").identity()));
        assertThrows(IllegalArgumentException.class, () -> SnapshotIo.decode(
                encoded.replace("identity=original/stack/0/minecraft:overworld/0/0/NOISE/context\n",
                        "identity=other/stack/0/minecraft:overworld/0/0/NOISE/context\n"),
                snapshot("stone").identity()));
        assertThrows(IllegalArgumentException.class, () -> SnapshotIo.decode(
                encoded.replace("identity=original/stack/0/minecraft:overworld/0/0/NOISE/context\n", ""),
                snapshot("stone").identity()));
    }
    @Test void snapshotIoRejectsDuplicateFieldsAndValues() {
        String encoded = SnapshotIo.encode(snapshot("stone"));
        String duplicateField = encoded + "field=BLOCK_STATES=stone\n";
        String duplicateValue = encoded + "value=block:0=stone\n";
        assertThrows(IllegalArgumentException.class, () -> SnapshotIo.decode(duplicateField, snapshot("stone").identity()));
        assertThrows(IllegalArgumentException.class, () -> SnapshotIo.decode(duplicateValue, snapshot("stone").identity()));
    }

    @Test void snapshotIoRoundTripsControlCharactersAndRejectsUnknownEscapes() {
        var identity = snapshot("stone").identity();
        var fields = new java.util.EnumMap<SnapshotField, String>(SnapshotField.class);
        fields.put(SnapshotField.BLOCK_STATES, "line\r\n\t=\\\u0001");
        ChunkSnapshot original = new ChunkSnapshot(identity, fields,
                Map.of("value=key", "line\r\n\t=\\\u0001"));

        String encoded = SnapshotIo.encode(original);
        ChunkSnapshot decoded = SnapshotIo.decode(encoded, identity);
        assertEquals(original.field(SnapshotField.BLOCK_STATES), decoded.field(SnapshotField.BLOCK_STATES));
        assertEquals(original.value("value=key"), decoded.value("value=key"));

        String malformed = "WORLDGENNEXT-SNAPSHOT-1\nidentity=" + identity.key()
                + "\nvalue=key=bad\\q\n";
        assertThrows(IllegalArgumentException.class, () -> SnapshotIo.decode(malformed, identity));
    }
    @Test void cliWritesFailureArtifactForLogicalMismatch(@TempDir Path directory) throws Exception {
        Path expected = directory.resolve("expected.snap");
        Path actual = directory.resolve("actual.snap");
        Path failures = directory.resolve("failures");
        SnapshotIo.write(expected, snapshot("stone"));
        SnapshotIo.write(actual, candidateSnapshot("dirt"));
        int exit = ReplayCli.execute(new String[]{"compare-captures", expected.toString(), actual.toString(),
                "--failure-dir=" + failures}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));
        assertEquals(1, exit);
        try (var files = Files.list(failures)) {
            assertTrue(files.findAny().isPresent());
        }
    }
    @Test void cliRequiresExactCaptureDirectoryCoverage(@TempDir Path directory) throws Exception {
        Path expectedDirectory = Files.createDirectories(directory.resolve("expected"));
        Path actualDirectory = Files.createDirectories(directory.resolve("actual"));
        Path expected = expectedDirectory.resolve("case.snap");
        Path actual = actualDirectory.resolve("case.snap");
        SnapshotIo.write(expected, snapshot("stone"));
        SnapshotIo.write(actual, candidateSnapshot("stone"));
        var output = new PrintStream(new ByteArrayOutputStream());
        assertEquals(0, ReplayCli.execute(new String[]{"compare-corpus", expectedDirectory.toString(), actualDirectory.toString()}, output, output));
        Files.copy(actual, actualDirectory.resolve("unexpected.snap"));
        assertEquals(1, ReplayCli.execute(new String[]{"compare-corpus", expectedDirectory.toString(), actualDirectory.toString()}, output, output));
    }

    @Test void cliMatchesNestedContextPathsWithoutFlatteningSameCaseNames(@TempDir Path directory) throws Exception {
        Path expectedDirectory = Files.createDirectories(directory.resolve("expected"));
        Path actualDirectory = Files.createDirectories(directory.resolve("actual"));
        Path expectedOverworld = expectedDirectory.resolve("minecraft_overworld/case.snap");
        Path actualOverworld = actualDirectory.resolve("minecraft_overworld/case.snap");
        Path expectedNether = expectedDirectory.resolve("minecraft_nether/case.snap");
        Path actualNether = actualDirectory.resolve("minecraft_nether/case.snap");
        SnapshotIo.write(expectedOverworld, snapshotForDimension("stone", "minecraft:overworld"));
        SnapshotIo.write(actualOverworld, snapshotForDimension("stone", "minecraft:overworld"));
        SnapshotIo.write(expectedNether, snapshotForDimension("dirt", "minecraft:the_nether"));
        SnapshotIo.write(actualNether, snapshotForDimension("dirt", "minecraft:the_nether"));

        var output = new PrintStream(new ByteArrayOutputStream());
        assertEquals(0, ReplayCli.execute(new String[]{"compare-corpus", expectedDirectory.toString(), actualDirectory.toString()}, output, output));
        Files.createDirectories(actualDirectory.resolve("minecraft_end"));
        Files.copy(actualNether, actualDirectory.resolve("minecraft_end/case.snap"));
        assertEquals(1, ReplayCli.execute(new String[]{"compare-corpus", expectedDirectory.toString(), actualDirectory.toString()}, output, output));
    }

    @Test void corpusValidatorReportsCoverageFailuresForNullDuplicateAndUnknownEntries() {
        var expected = snapshot("stone");
        var manifest = new CorpusManifest(1, "one-case",
                java.util.List.of(expected.identity()), EnumSet.allOf(SnapshotField.class));
        var validator = new dev.worldgennext.oracle.minecraft.CorpusValidator();

        var empty = validator.validate(manifest, java.util.List.of());
        assertFalse(empty.valid());
        assertTrue(empty.errors().contains("empty snapshots"));
        assertTrue(empty.errors().stream().anyMatch(error -> error.startsWith("missing snapshot: ")));

        var nullEntry = validator.validate(manifest, Arrays.asList((ChunkSnapshot) null));
        assertFalse(nullEntry.valid());
        assertTrue(nullEntry.errors().contains("missing snapshot at index 0"));

        var duplicate = validator.validate(manifest, java.util.List.of(expected, expected));
        assertFalse(duplicate.valid());
        assertTrue(duplicate.errors().stream().anyMatch(error -> error.startsWith("duplicate snapshot: ")));

        var unknown = validator.validate(manifest, java.util.List.of(candidateSnapshot("stone")));
        assertFalse(unknown.valid());
        assertTrue(unknown.errors().stream().anyMatch(error -> error.startsWith("unknown snapshot: ")));
        assertTrue(unknown.errors().stream().anyMatch(error -> error.startsWith("missing snapshot: ")));
    }

    @Test void candidateHeightmapsUseSimpleBitStorageWidthForPowerOfTwoWorldHeight() {
        var table = dev.worldgennext.material.chunk.BlockStateTable.fromRegistry(
                dev.worldgennext.semantic.snapshot.RegistrySnapshot.minimal());
        var header = new dev.worldgennext.material.chunk.ChunkResultHeader(0, 0, 0, 256,
                "context", table.fingerprint(), "3.0");
        int[] heights = new int[256];
        java.util.Arrays.fill(heights, 256);
        var result = dev.worldgennext.material.chunk.ChunkNoiseResult.ofDense(header, table,
                filled("minecraft:air", 256 * 256),
                new dev.worldgennext.material.chunk.HeightmapPayload(heights), new boolean[256 * 256]);
        String encoded = dev.worldgennext.oracle.minecraft.ChunkResultSnapshot.canonicalHeightmaps(result);
        String words = encoded.substring(encoded.indexOf("[L;") + 3, encoded.length() - 2);
        assertEquals(37, words.split(",").length,
                "ceil(log2(height + 1)) must be used when height is a power of two");
    }

    @Test void candidatePostProcessingPreservesMinecraftCellTraversalOrder() {
        var table = dev.worldgennext.material.chunk.BlockStateTable.fromRegistry(
                dev.worldgennext.semantic.snapshot.RegistrySnapshot.minimal());
        var header = new dev.worldgennext.material.chunk.ChunkResultHeader(0, 0, 0, 16,
                "context", table.fingerprint(), "4.0");
        boolean[] marks = new boolean[4096];
        marks[(1 * 16 + 0) * 16 + 0] = true; // cell (0,0), y=1
        marks[(2 * 16 + 4) * 16 + 0] = true; // cell (0,4), y=2
        marks[(3 * 16 + 0) * 16 + 4] = true; // cell (4,0), y=3
        var result = dev.worldgennext.material.chunk.ChunkNoiseResult.ofDense(header, table,
                filled("minecraft:air", 4096), new dev.worldgennext.material.chunk.HeightmapPayload(new int[256]), marks);
        assertEquals("[[16s,1056s,52s]]", ChunkResultSnapshot.canonicalPostProcessing(result));
    }

    private static String[] filled(String state, int count) {
        String[] values = new String[count];
        java.util.Arrays.fill(values, state);
        return values;
    }

    private static ChunkSnapshot snapshotForDimension(String block, String dimension) {
        var id = new CaptureIdentity("original", "stack", "0", dimension, 0, 0, "NOISE", "context-" + dimension);
        var fields = new java.util.EnumMap<SnapshotField, String>(SnapshotField.class);
        for (SnapshotField field : SnapshotField.values()) {
            fields.put(field, field == SnapshotField.BLOCK_STATES ? block : field.name());
        }
        return new ChunkSnapshot(id, fields, Map.of("block:0", block));
    }
}
