// SPDX-License-Identifier: MIT
package dev.tellurium.material.chunk;

import dev.tellurium.semantic.snapshot.BlockStateDescriptor;
import dev.tellurium.semantic.snapshot.RegistrySnapshot;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkResultValidatorTest {
    private static final String CONTEXT = "minecraft:overworld";
    private static final String ABI = "test-abi";
    private static final BlockStateTable TABLE = BlockStateTable.fromRegistry(RegistrySnapshot.minimal());

    @Test
    void acceptsCompleteResultAndReturnsItsLogicalChecksum() {
        ChunkNoiseResult result = validResult();

        ChunkResultValidator.Validation validation = ChunkResultValidator.validate(
                result, CONTEXT, TABLE.fingerprint());

        assertTrue(validation.valid());
        assertEquals("valid", validation.reason());
        assertEquals(result.logicalChecksum(), validation.checksum());
        assertDoesNotThrow(() -> ChunkResultValidator.requireValid(result, CONTEXT, TABLE.fingerprint()));
    }

    @Test
    void rejectsMissingResultAndMissingExpectedIdentityWithoutTouchingPayload() {
        ChunkResultValidator.Validation missing = ChunkResultValidator.validate(
                null, CONTEXT, TABLE.fingerprint());
        assertFalse(missing.valid());
        assertEquals("missing result", missing.reason());
        assertEquals("", missing.checksum());

        ChunkNoiseResult result = validResult();
        ChunkResultValidator.Validation missingContext = ChunkResultValidator.validate(
                result, null, TABLE.fingerprint());
        ChunkResultValidator.Validation missingRegistry = ChunkResultValidator.validate(
                result, CONTEXT, null);
        assertFalse(missingContext.valid());
        assertEquals("missing expected identity", missingContext.reason());
        assertEquals("", missingContext.checksum());
        assertFalse(missingRegistry.valid());
        assertEquals("missing expected identity", missingRegistry.reason());
        assertEquals("", missingRegistry.checksum());

        assertThrows(IllegalArgumentException.class,
                () -> ChunkResultValidator.requireValid(result, null, TABLE.fingerprint()));
    }

    @Test
    void rejectsWrongWorldKeyAndRegistryIdentityWithDiagnosticChecksums() {
        ChunkNoiseResult wrongWorld = resultWithIdentity("minecraft:the_nether", TABLE.fingerprint());
        ChunkResultValidator.Validation worldValidation = ChunkResultValidator.validate(
                wrongWorld, CONTEXT, TABLE.fingerprint());
        assertFalse(worldValidation.valid());
        assertEquals("context identity mismatch", worldValidation.reason());
        assertEquals(wrongWorld.logicalChecksum(), worldValidation.checksum());

        BlockStateTable foreignTable = new BlockStateTable(List.of(BlockStateDescriptor.of("minecraft:air")));
        ChunkNoiseResult wrongRegistry = resultWithTable(CONTEXT, foreignTable);
        ChunkResultValidator.Validation registryValidation = ChunkResultValidator.validate(
                wrongRegistry, CONTEXT, TABLE.fingerprint());
        assertFalse(registryValidation.valid());
        assertEquals("registry identity mismatch", registryValidation.reason());
        assertEquals(wrongRegistry.logicalChecksum(), registryValidation.checksum());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ChunkResultValidator.requireValid(wrongRegistry, CONTEXT, TABLE.fingerprint()));
        assertTrue(failure.getMessage().contains("registry identity mismatch"));
    }

    @Test
    void rejectsWrongBlockCoordinatesAtThePublicDenseAccessorBoundary() {
        ChunkNoiseResult result = validResult();
        int minY = result.header().minY();
        int maxY = result.header().maxYExclusive();

        assertEquals("minecraft:air", result.state(0, minY, 0));
        assertEquals("minecraft:air", result.state(15, maxY - 1, 15));
        assertThrows(IndexOutOfBoundsException.class, () -> result.state(-1, minY, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> result.state(16, minY, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> result.state(0, minY, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> result.state(0, minY, 16));
        assertThrows(IndexOutOfBoundsException.class, () -> result.state(0, minY - 1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> result.state(0, maxY, 0));
    }

    @Test
    void rejectsInvalidHeaderGeometryAndDenseOrPostProcessingSizes() {
        assertThrows(IllegalArgumentException.class, () ->
                new ChunkResultHeader(0, 0, 0, 0, CONTEXT, TABLE.fingerprint(), ABI));
        assertThrows(IllegalArgumentException.class, () ->
                new ChunkResultHeader(0, 0, 0, 15, CONTEXT, TABLE.fingerprint(), ABI));
        assertThrows(IllegalArgumentException.class, () ->
                new ChunkResultHeader(0, 0, 0, 17, CONTEXT, TABLE.fingerprint(), ABI));
        // A bottom off a section boundary: the sections would be named for a different range than they hold.
        assertThrows(IllegalArgumentException.class, () ->
                new ChunkResultHeader(0, 0, 1, 16, CONTEXT, TABLE.fingerprint(), ABI));
        assertThrows(IllegalArgumentException.class, () ->
                new ChunkResultHeader(0, 0, -63, 16, CONTEXT, TABLE.fingerprint(), ABI));
        assertEquals(-64, new ChunkResultHeader(0, 0, -64, 16, CONTEXT, TABLE.fingerprint(), ABI).minY());
        assertThrows(IllegalArgumentException.class, () ->
                new ChunkResultHeader(0, 0, 0, -16, CONTEXT, TABLE.fingerprint(), ABI));
        assertThrows(IllegalArgumentException.class, () ->
                new ChunkResultHeader(0, 0, Integer.MAX_VALUE, 16, CONTEXT, TABLE.fingerprint(), ABI));
        assertThrows(IllegalArgumentException.class, () ->
                new ChunkResultHeader(0, 0, 0, 16, "", TABLE.fingerprint(), ABI));
        assertThrows(IllegalArgumentException.class, () ->
                new ChunkResultHeader(0, 0, 0, 16, CONTEXT, "", ABI));
        assertThrows(IllegalArgumentException.class, () ->
                new ChunkResultHeader(0, 0, 0, 16, CONTEXT, TABLE.fingerprint(), ""));

        ChunkResultHeader header = header();
        assertThrows(RuntimeException.class, () ->
                ChunkNoiseResult.ofDense(header, TABLE, airStates(4095), new int[256], new boolean[4095]));
        assertThrows(IllegalArgumentException.class, () ->
                ChunkNoiseResult.ofDense(header, TABLE, airStates(4097), new int[256], new boolean[4097]));
        assertThrows(RuntimeException.class, () ->
                ChunkNoiseResult.ofDense(header, TABLE, null, new int[256], new boolean[4096]));
        assertThrows(IllegalArgumentException.class, () ->
                new PostProcessingPayload(new boolean[4095], 4096));
    }

    @Test
    void acceptsTheDocumentedInclusiveHeightmapBounds() {
        ChunkResultHeader header = header();
        int[] heights = new int[HeightmapPayload.COLUMN_COUNT];
        Arrays.fill(heights, header.minY() - 1);
        heights[0] = header.maxYExclusive();

        ChunkNoiseResult result = ChunkNoiseResult.ofDense(
                header, TABLE, airStates(4096), heights, new boolean[4096]);

        assertTrue(ChunkResultValidator.validate(result, CONTEXT, TABLE.fingerprint()).valid());
        assertEquals(header.minY() - 1, result.heightmaps().value(1, 0));
        assertEquals(header.maxYExclusive(), result.heightmaps().value(0, 0));
    }

    @Test
    void rejectsMalformedHeightmapPayloadsAndOutOfGeometryValues() {
        assertThrows(RuntimeException.class, () -> new HeightmapPayload((int[]) null));
        assertThrows(IllegalArgumentException.class, () -> new HeightmapPayload(new int[255]));
        assertThrows(IllegalArgumentException.class, () -> new HeightmapPayload(Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new HeightmapPayload(Map.of("", new int[256])));
        assertThrows(IllegalArgumentException.class, () ->
                new HeightmapPayload(Map.of("WORLD_SURFACE", new int[255])));

        Map<String, int[]> nullColumns = new HashMap<>();
        nullColumns.put("WORLD_SURFACE", null);
        assertThrows(IllegalArgumentException.class, () -> new HeightmapPayload(nullColumns));

        Map<String, int[]> nullKind = new HashMap<>();
        nullKind.put(null, new int[256]);
        assertThrows(IllegalArgumentException.class, () -> new HeightmapPayload(nullKind));

        int[] outside = new int[256];
        Arrays.fill(outside, header().maxYExclusive() + 1);
        assertThrows(IllegalArgumentException.class, () -> ChunkNoiseResult.ofDense(
                header(), TABLE, airStates(4096), outside, new boolean[4096]));
    }

    @Test
    void rejectsMalformedBlockStateTableAndDenseMaterial() {
        BlockStateDescriptor air = BlockStateDescriptor.of("minecraft:air");
        assertThrows(IllegalArgumentException.class, () ->
                new BlockStateTable(List.of(air, air)));
        BlockStateTable withoutAir = new BlockStateTable(List.of(
                BlockStateDescriptor.of("minecraft:stone")));
        assertThrows(IllegalArgumentException.class, () -> ChunkNoiseResult.ofDense(
                header(), withoutAir, filled("minecraft:stone", 4096), new int[256], new boolean[4096]));

        BlockStateTable foreign = new BlockStateTable(List.of(BlockStateDescriptor.of("minecraft:air")));
        assertThrows(IllegalArgumentException.class, () -> ChunkNoiseResult.ofDense(
                header(), foreign, airStates(4096), new int[256], new boolean[4096]));

        String[] unknownState = airStates(4096);
        unknownState[0] = "minecraft:missing_material";
        assertThrows(IllegalArgumentException.class, () -> ChunkNoiseResult.ofDense(
                header(), TABLE, unknownState, new int[256], new boolean[4096]));

        String[] nullState = airStates(4096);
        nullState[0] = null;
        assertThrows(RuntimeException.class, () -> ChunkNoiseResult.ofDense(
                header(), TABLE, nullState, new int[256], new boolean[4096]));
    }

    @Test
    void rejectsMalformedSectionCountsAndMaterialDirectory() {
        ChunkNoiseResult valid = validResult();
        SectionDirectory directory = valid.sections().get(0);
        SectionDirectory wrongOffset = new SectionDirectory(
                directory.sectionY(), directory.offset() + 1, directory.length(), directory.nonAirCount());
        assertThrows(IllegalArgumentException.class, () -> new ChunkNoiseResult(
                valid.header(), TABLE, valid.denseStates(), valid.heightmaps(), valid.postProcessing(),
                valid.sectionCounts(), List.of(wrongOffset)));

        SectionCounts wrongCounts = new SectionCounts(Map.of("minecraft:air", 4095, "minecraft:stone", 1));
        assertThrows(IllegalArgumentException.class, () -> new ChunkNoiseResult(
                valid.header(), TABLE, valid.denseStates(), valid.heightmaps(), valid.postProcessing(),
                wrongCounts, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SectionDirectory(0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SectionDirectory(0, 0, 0, 4097));
    }

    @Test
    void rejectsMalformedBiomeMetadataContainers() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkMetadataPayload(null));
        assertThrows(IllegalArgumentException.class, () ->
                new ChunkMetadataPayload(Map.of("", "minecraft:plains")));

        Map<String, String> nullName = new LinkedHashMap<>();
        nullName.put(null, "minecraft:plains");
        assertThrows(IllegalArgumentException.class, () -> new ChunkMetadataPayload(nullName));

        Map<String, String> nullValue = new LinkedHashMap<>();
        nullValue.put("BIOMES", null);
        assertThrows(IllegalArgumentException.class, () -> new ChunkMetadataPayload(nullValue));
    }

    @Test
    void validatedResultOwnsMutablePayloadInputs() {
        ChunkResultHeader header = header();
        String[] states = airStates(4096);
        int[] heights = new int[256];
        boolean[] fluids = new boolean[4096];
        ChunkNoiseResult result = ChunkNoiseResult.ofDense(header, TABLE, states, heights, fluids);
        String checksum = result.logicalChecksum();

        states[0] = "minecraft:stone";
        heights[0] = header.maxYExclusive();
        fluids[0] = true;

        ChunkResultValidator.Validation validation = ChunkResultValidator.validate(
                result, CONTEXT, TABLE.fingerprint());
        assertTrue(validation.valid());
        assertEquals(checksum, validation.checksum());
        assertEquals("minecraft:air", result.state(0, header.minY(), 0));
        assertEquals(0, result.heightmaps().value(0, 0));
        assertFalse(result.postProcessing().fluidAt(0));
    }

    @Test
    void validationRecordRejectsNullDiagnosticFields() {
        assertThrows(NullPointerException.class, () ->
                new ChunkResultValidator.Validation(false, null, ""));
        assertThrows(NullPointerException.class, () ->
                new ChunkResultValidator.Validation(false, "reason", null));
    }

    private static ChunkNoiseResult validResult() {
        return resultWithIdentity(CONTEXT, TABLE.fingerprint());
    }

    private static ChunkNoiseResult resultWithIdentity(String context, String registry) {
        ChunkResultHeader header = new ChunkResultHeader(7, -11, -16, 16, context, registry, ABI);
        return ChunkNoiseResult.ofDense(header, TABLE, airStates(4096), new int[256], new boolean[4096]);
    }

    private static ChunkNoiseResult resultWithTable(String context, BlockStateTable table) {
        ChunkResultHeader header = new ChunkResultHeader(7, -11, -16, 16, context, table.fingerprint(), ABI);
        return ChunkNoiseResult.ofDense(header, table, airStates(4096), new int[256], new boolean[4096]);
    }

    private static ChunkResultHeader header() {
        return new ChunkResultHeader(7, -11, -16, 16, CONTEXT, TABLE.fingerprint(), ABI);
    }

    private static String[] airStates(int count) {
        return filled("minecraft:air", count);
    }

    private static String[] filled(String state, int count) {
        String[] values = new String[count];
        Arrays.fill(values, state);
        return values;
    }
}
