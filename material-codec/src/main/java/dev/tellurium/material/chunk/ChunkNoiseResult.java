// SPDX-License-Identifier: MIT
package dev.tellurium.material.chunk;

import dev.tellurium.material.SectionCodec;
import dev.tellurium.material.SectionData;
import java.util.Arrays;
import java.util.Objects;

/** Complete dense logical NOISE result before loader-side publication. */
public final class ChunkNoiseResult {
    private final ChunkResultHeader header;
    private final BlockStateTable stateTable;
    private final String[] states;
    private final HeightmapPayload heightmaps;
    private final PostProcessingPayload postProcessing;
    private final SectionCounts sectionCounts;
    private final java.util.List<SectionDirectory> sections;
    private final java.util.List<SectionData> sectionData;
    private final ChunkMetadataPayload metadata;
    public ChunkNoiseResult(ChunkResultHeader header, BlockStateTable stateTable, String[] states,
                            HeightmapPayload heightmaps, PostProcessingPayload postProcessing,
                            SectionCounts sectionCounts, java.util.List<SectionDirectory> sections) {
        this(header, stateTable, states, heightmaps, postProcessing, sectionCounts,
                classifySections(header, stateTable, states), sections, ChunkMetadataPayload.empty());
    }

    public ChunkNoiseResult(ChunkResultHeader header, BlockStateTable stateTable, String[] states,
                            HeightmapPayload heightmaps, PostProcessingPayload postProcessing,
                            SectionCounts sectionCounts, java.util.List<SectionDirectory> sections,
                            ChunkMetadataPayload metadata) {
        this(header, stateTable, states, heightmaps, postProcessing, sectionCounts,
                classifySections(header, stateTable, states), sections, metadata);
    }

    private ChunkNoiseResult(ChunkResultHeader header, BlockStateTable stateTable, String[] states,
                             HeightmapPayload heightmaps, PostProcessingPayload postProcessing,
                             SectionCounts sectionCounts, java.util.List<SectionData> sectionData,
                             java.util.List<SectionDirectory> sections, ChunkMetadataPayload metadata) {
        this.header = Objects.requireNonNull(header, "header"); this.stateTable = Objects.requireNonNull(stateTable, "stateTable");
        if (!header.registryFingerprint().equals(stateTable.fingerprint())) {
            throw new IllegalArgumentException("Result header registry identity does not match its state table");
        }
        int expectedStateCount = Math.multiplyExact(header.height(), 256);
        if (states == null || states.length != expectedStateCount) throw new IllegalArgumentException("Dense state count does not match geometry");
        this.states = states.clone();
        for (String state : this.states) if (state == null || stateTable.id(state) < 0) throw new IllegalArgumentException("State not present in table");
        this.heightmaps = Objects.requireNonNull(heightmaps, "heightmaps"); this.postProcessing = Objects.requireNonNull(postProcessing, "postProcessing");
        if (postProcessing.fluidMarks().length != this.states.length) throw new IllegalArgumentException("Postprocessing length mismatch");
        long lowerBound = (long) header.minY() - 1L;
        long upperBound = header.maxYExclusive();
        for (int[] columns : heightmaps.maps().values()) {
            for (int heightmap : columns) {
                if (heightmap < lowerBound || heightmap > upperBound) throw new IllegalArgumentException("Heightmap value outside result geometry");
            }
        }
        this.sectionCounts = Objects.requireNonNull(sectionCounts, "sectionCounts");
        this.sectionData = java.util.List.copyOf(Objects.requireNonNull(sectionData, "sectionData"));
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        if (this.sectionData.size() != header.sectionCount()) throw new IllegalArgumentException("Section representation count mismatch");
        this.sections = validateDirectories(header, stateTable, this.states, this.sectionData, sections);
        validateCounts();
    }
    public ChunkResultHeader header() { return header; }
    public BlockStateTable stateTable() { return stateTable; }
    public String state(int x, int worldY, int z) { return states[index(x, worldY, z)]; }
    public String[] denseStates() { return states.clone(); }
    public HeightmapPayload heightmaps() { return heightmaps; }
    public PostProcessingPayload postProcessing() { return postProcessing; }
    public SectionCounts sectionCounts() { return sectionCounts; }
    public java.util.List<SectionDirectory> sections() { return sections; }
    public SectionData section(int sectionIndex) { if (sectionIndex < 0 || sectionIndex >= sectionData.size()) throw new IndexOutOfBoundsException(sectionIndex); return sectionData.get(sectionIndex); }
    public java.util.List<SectionData> sectionData() { return sectionData; }
    public ChunkMetadataPayload metadata() { return metadata; }

    /**
     * Rebinds only the result's consumer-facing context identity after a
     * loader has associated an otherwise validated candidate with its
     * authoritative request.  Geometry, registry identity, ABI, material
     * data and metadata are reconstructed through the normal constructor so
     * an identity rewrite cannot bypass result validation.
     */
    public ChunkNoiseResult withContextIdentity(String contextIdentity) {
        Objects.requireNonNull(contextIdentity, "contextIdentity");
        if (header.contextIdentity().equals(contextIdentity)) return this;
        ChunkResultHeader rebound = new ChunkResultHeader(header.chunkX(), header.chunkZ(), header.minY(),
                header.height(), contextIdentity, header.registryFingerprint(), header.abiVersion());
        return new ChunkNoiseResult(rebound, stateTable, states, heightmaps, postProcessing,
                sectionCounts, sections, metadata);
    }

    public String logicalChecksum() { return ChunkLogicalChecksum.of(this); }
    public static ChunkNoiseResult ofDense(ChunkResultHeader header, BlockStateTable table, String[] states, int[] heightmaps, boolean[] fluidMarks) {
        var counts = new java.util.TreeMap<String, Integer>(); for (String state : states) counts.merge(state, 1, Integer::sum);
        return new ChunkNoiseResult(header, table, states, new HeightmapPayload(heightmaps), new PostProcessingPayload(fluidMarks, states.length), new SectionCounts(counts), java.util.List.of());
    }

    public static ChunkNoiseResult ofDense(ChunkResultHeader header, BlockStateTable table, String[] states,
                                           HeightmapPayload heightmaps, boolean[] fluidMarks) {
        var counts = new java.util.TreeMap<String, Integer>(); for (String state : states) counts.merge(state, 1, Integer::sum);
        return new ChunkNoiseResult(header, table, states, heightmaps,
                new PostProcessingPayload(fluidMarks, states.length), new SectionCounts(counts), java.util.List.of(), ChunkMetadataPayload.empty());
    }

    public static ChunkNoiseResult ofDense(ChunkResultHeader header, BlockStateTable table, String[] states,
                                           HeightmapPayload heightmaps, boolean[] fluidMarks,
                                           ChunkMetadataPayload metadata) {
        var counts = new java.util.TreeMap<String, Integer>(); for (String state : states) counts.merge(state, 1, Integer::sum);
        return new ChunkNoiseResult(header, table, states, heightmaps,
                new PostProcessingPayload(fluidMarks, states.length), new SectionCounts(counts), java.util.List.of(), metadata);
    }
    private int index(int x, int worldY, int z) { if (x < 0 || x >= 16 || z < 0 || z >= 16 || worldY < header.minY() || worldY >= header.maxYExclusive()) throw new IndexOutOfBoundsException(); return ((worldY - header.minY()) * 16 + z) * 16 + x; }
    private void validateCounts() {
        var actual = new java.util.TreeMap<String, Integer>(); for (String state : states) actual.merge(state, 1, Integer::sum);
        if (!actual.equals(sectionCounts.values()) || sectionCounts.total() != states.length) throw new IllegalArgumentException("State counts do not match dense result");
    }

    private static java.util.List<SectionData> classifySections(ChunkResultHeader header, BlockStateTable table, String[] states) {
        Objects.requireNonNull(header, "header"); Objects.requireNonNull(table, "stateTable"); Objects.requireNonNull(states, "states");
        int airState = table.id("minecraft:air");
        if (airState < 0) throw new IllegalArgumentException("Registry table is missing minecraft:air");
        var result = new java.util.ArrayList<SectionData>(header.sectionCount());
        for (int section = 0; section < header.sectionCount(); section++) {
            int[] ids = new int[SectionData.BLOCK_COUNT];
            for (int index = 0; index < ids.length; index++) {
                String state = states[section * ids.length + index];
                ids[index] = table.id(state);
                if (ids[index] < 0) throw new IllegalArgumentException("State is absent from result table: " + state);
            }
            result.add(SectionCodec.encode(ids, airState));
        }
        return result;
    }

    private static java.util.List<SectionDirectory> validateDirectories(ChunkResultHeader header, BlockStateTable table,
                                                                         String[] states, java.util.List<SectionData> representations,
                                                                         java.util.List<SectionDirectory> supplied) {
        int airState = table.id("minecraft:air");
        if (airState < 0) throw new IllegalArgumentException("Registry table is missing minecraft:air");
        var expected = new java.util.ArrayList<SectionDirectory>(representations.size());
        int offset = 0;
        for (int section = 0; section < representations.size(); section++) {
            SectionData data = Objects.requireNonNull(representations.get(section), "section representation");
            if (data.airStateId() != airState) throw new IllegalArgumentException("Section air identity does not match registry");
            int[] decoded = SectionCodec.decode(data);
            for (int index = 0; index < decoded.length; index++) {
                String expectedState = states[section * decoded.length + index];
                if (table.id(expectedState) != decoded[index]) throw new IllegalArgumentException("Section representation differs from dense result");
            }
            int length = SectionCodec.toBytes(data).length;
            expected.add(new SectionDirectory(Math.floorDiv(header.minY(), 16) + section, offset, length, data.nonAirCount()));
            offset = Math.addExact(offset, length);
        }
        var actual = supplied == null || supplied.isEmpty() ? expected : java.util.List.copyOf(supplied);
        if (!actual.equals(expected)) throw new IllegalArgumentException("Section directory does not match encoded representations");
        return java.util.List.copyOf(expected);
    }
}
