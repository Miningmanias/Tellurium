// SPDX-License-Identifier: MIT
package dev.worldgennext.material;

import dev.worldgennext.material.chunk.*;
import dev.worldgennext.semantic.snapshot.RegistrySnapshot;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class V02ChunkResultTest {
    @Test void completeDenseResultRoundTripsThroughLittleEndianCodec() {
        var table = BlockStateTable.fromRegistry(RegistrySnapshot.minimal());
        var header = new ChunkResultHeader(2, -3, -16, 16, "context", table.fingerprint(), "1.0");
        String[] states = new String[4096]; boolean[] fluids = new boolean[4096];
        for (int i = 0; i < states.length; i++) { states[i] = i % 3 == 0 ? "minecraft:air" : i % 3 == 1 ? "minecraft:stone" : "minecraft:water"; fluids[i] = i % 3 == 2; }
        int[] heights = new int[256]; java.util.Arrays.fill(heights, 0);
        var result = ChunkNoiseResult.ofDense(header, table, states, heights, fluids);
        var decoded = ChunkResultCodec.decode(ChunkResultCodec.encode(result));
        assertArrayEquals(states, decoded.denseStates());
        assertArrayEquals(fluids, decoded.postProcessing().fluidMarks());
        assertEquals(result.logicalChecksum(), decoded.logicalChecksum());
    }

    @Test void malformedResultIsRejected() {
        var table = BlockStateTable.fromRegistry(RegistrySnapshot.minimal());
        var header = new ChunkResultHeader(0, 0, 0, 16, "context", table.fingerprint(), "1.0");
        String[] states = new String[4096]; java.util.Arrays.fill(states, "minecraft:air");
        var bytes = ChunkResultCodec.encode(ChunkNoiseResult.ofDense(header, table, states, new int[256], new boolean[4096]));
        bytes[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> ChunkResultCodec.decode(bytes));
    }

    @Test void malformedUtf8AndDuplicateStatePropertiesAreRejected() {
        var table = BlockStateTable.fromRegistry(RegistrySnapshot.minimal());
        var header = new ChunkResultHeader(0, 0, 0, 16, "context", table.fingerprint(), "1.0");
        String[] states = new String[4096]; java.util.Arrays.fill(states, "minecraft:air");
        byte[] bytes = ChunkResultCodec.encode(ChunkNoiseResult.ofDense(header, table, states,
                new int[256], new boolean[4096]));
        // Six little-endian header ints occupy bytes 0..23. The context
        // length is at byte 24 and its first byte at byte 28.
        bytes[28] = (byte) 0xFF;
        assertThrows(IllegalArgumentException.class, () -> ChunkResultCodec.decode(bytes));
        assertThrows(IllegalArgumentException.class,
                () -> invokeStateParser("minecraft:stone[axis=x,axis=y]"));
    }

    private static Object invokeStateParser(String canonical) {
        try {
            var parser = ChunkResultCodec.class.getDeclaredMethod("parseState", String.class);
            parser.setAccessible(true);
            return parser.invoke(null, canonical);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test void wireResultCarriesActualUniformAndPaletteSections() {
        var table = new BlockStateTable(java.util.List.of(
                dev.worldgennext.semantic.snapshot.BlockStateDescriptor.of("minecraft:stone"),
                dev.worldgennext.semantic.snapshot.BlockStateDescriptor.of("minecraft:air"),
                dev.worldgennext.semantic.snapshot.BlockStateDescriptor.of("minecraft:water")));
        var header = new ChunkResultHeader(0, 0, 0, 32, "context", table.fingerprint(), "2.0");
        String[] states = new String[8192];
        java.util.Arrays.fill(states, 0, 4096, "minecraft:stone");
        for (int i = 4096; i < states.length; i++) states[i] = i % 3 == 0 ? "minecraft:air" : i % 3 == 1 ? "minecraft:water" : "minecraft:stone";
        var result = ChunkNoiseResult.ofDense(header, table, states, new int[256], new boolean[8192]);
        assertInstanceOf(UniformSection.class, result.section(0));
        assertInstanceOf(PaletteSection.class, result.section(1));
        assertEquals(SectionCodec.toBytes(result.section(0)).length, result.sections().get(0).length());
        assertEquals(SectionCodec.toBytes(result.section(1)).length, result.sections().get(1).length());

        var decoded = ChunkResultCodec.decode(ChunkResultCodec.encode(result));
        assertArrayEquals(states, decoded.denseStates());
        assertEquals(result.sections(), decoded.sections());
        assertEquals(result.sectionCounts().values(), decoded.sectionCounts().values());
    }

    @Test void nonZeroRegistryAirIdIsExplicitAndSurvivesSectionRoundTrip() {
        int[] states = new int[4096]; java.util.Arrays.fill(states, 3); states[7] = 4; states[8] = 1;
        SectionData section = SectionCodec.encode(states, 3);
        assertEquals(3, section.airStateId());
        assertEquals(2, section.nonAirCount());
        byte[] bytes = SectionCodec.toBytes(section);
        assertEquals(2, java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(4));
        SectionData decoded = SectionCodec.fromBytes(bytes);
        assertEquals(3, decoded.airStateId());
        assertArrayEquals(states, SectionCodec.decode(decoded));
    }

    @Test void namedHeightmapSetRoundTripsAndDoesNotAliasCallerArrays() {
        var table = BlockStateTable.fromRegistry(RegistrySnapshot.minimal());
        var header = new ChunkResultHeader(0, 0, -16, 16, "context", table.fingerprint(), "3.0");
        int[] surface = new int[256];
        int[] motion = new int[256];
        java.util.Arrays.fill(surface, -1);
        java.util.Arrays.fill(motion, -2);
        var maps = new java.util.LinkedHashMap<String, int[]>();
        maps.put("WORLD_SURFACE", surface);
        maps.put("MOTION_BLOCKING", motion);
        var result = ChunkNoiseResult.ofDense(header, table,
                filled("minecraft:air", 4096), new HeightmapPayload(maps), new boolean[4096]);
        surface[0] = 99;
        var decoded = ChunkResultCodec.decode(ChunkResultCodec.encode(result));
        assertEquals(-1, result.heightmaps().value(0, 0));
        assertArrayEquals(motion, decoded.heightmaps().values("MOTION_BLOCKING"));
        assertEquals(result.logicalChecksum(), decoded.logicalChecksum());
        assertThrows(IndexOutOfBoundsException.class, () -> result.heightmaps().value(16, 0));
    }

    @Test void canonicalMetadataRoundTripsWithTheV4ResultAbi() {
        var table = BlockStateTable.fromRegistry(RegistrySnapshot.minimal());
        var header = new ChunkResultHeader(0, 0, 0, 16, "context", table.fingerprint(), "4.0");
        var metadata = new ChunkMetadataPayload(new java.util.LinkedHashMap<>(java.util.Map.of(
                "BIOMES", "{biomes}", "LIFECYCLE", "{status}")));
        var result = ChunkNoiseResult.ofDense(header, table, filled("minecraft:air", 4096),
                new HeightmapPayload(new int[256]), new boolean[4096], metadata);
        var decoded = ChunkResultCodec.decode(ChunkResultCodec.encode(result));
        assertEquals(metadata, decoded.metadata());
        assertEquals(result.logicalChecksum(), decoded.logicalChecksum());
    }

    @Test void contextRebindPreservesValidatedPayloadAndChangesOnlyConsumerIdentity() {
        var table = BlockStateTable.fromRegistry(RegistrySnapshot.minimal());
        var original = ChunkNoiseResult.ofDense(
                new ChunkResultHeader(0, 0, 0, 16, "captured-context", table.fingerprint(), "4.0"),
                table, filled("minecraft:air", 4096), new int[256], new boolean[4096]);

        var rebound = original.withContextIdentity("authoritative-request-context");

        assertEquals("authoritative-request-context", rebound.header().contextIdentity());
        assertEquals(original.header().registryFingerprint(), rebound.header().registryFingerprint());
        assertEquals(original.header().abiVersion(), rebound.header().abiVersion());
        assertArrayEquals(original.denseStates(), rebound.denseStates());
        assertEquals(original.logicalChecksum(), rebound.logicalChecksum());
        assertSame(original, original.withContextIdentity("captured-context"));
    }

    private static String[] filled(String state, int count) {
        String[] values = new String[count];
        java.util.Arrays.fill(values, state);
        return values;
    }
}
