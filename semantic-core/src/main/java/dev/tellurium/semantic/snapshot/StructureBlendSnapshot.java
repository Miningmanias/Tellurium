// SPDX-License-Identifier: MIT
package dev.tellurium.semantic.snapshot;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Captured old/new terrain blend and beardifier inputs. */
public record StructureBlendSnapshot(String identity, Map<String, Double> densities, Map<String, Double> alphas,
                                     BeardifierSnapshot beardifier, List<DensitySample> densitySamples,
                                     List<DirectDensitySample> directDensitySamples,
                                     List<HeightSample> heightSamples,
                                     List<DirectHeightSample> directHeightSamples,
                                     boolean hasHeightAndBiomeData) {
    public StructureBlendSnapshot(String identity, Map<String, Double> densities, Map<String, Double> alphas) {
        this(identity, densities, alphas, BeardifierSnapshot.empty(), List.of(), List.of(), List.of(), List.of(), false);
    }
    public StructureBlendSnapshot(String identity, Map<String, Double> densities, Map<String, Double> alphas,
                                  BeardifierSnapshot beardifier) {
        this(identity, densities, alphas, beardifier, List.of(), List.of(), List.of(), List.of(), false);
    }
    /** Compatibility constructor for snapshots that only contain weighted samples. */
    public StructureBlendSnapshot(String identity, Map<String, Double> densities, Map<String, Double> alphas,
                                  BeardifierSnapshot beardifier, List<DensitySample> densitySamples) {
        this(identity, densities, alphas, beardifier, densitySamples, List.of(), List.of(), List.of(), false);
    }
    /** Compatibility constructor for snapshots without height/biome data. */
    public StructureBlendSnapshot(String identity, Map<String, Double> densities, Map<String, Double> alphas,
                                  BeardifierSnapshot beardifier, List<DensitySample> densitySamples,
                                  List<DirectDensitySample> directDensitySamples) {
        this(identity, densities, alphas, beardifier, densitySamples, directDensitySamples, List.of(), List.of(), false);
    }
    /** Compatibility constructor for a height/biome presence flag without captured height samples. */
    public StructureBlendSnapshot(String identity, Map<String, Double> densities, Map<String, Double> alphas,
                                  BeardifierSnapshot beardifier, List<DensitySample> densitySamples,
                                  List<DirectDensitySample> directDensitySamples,
                                  boolean hasHeightAndBiomeData) {
        this(identity, densities, alphas, beardifier, densitySamples, directDensitySamples,
                List.of(), List.of(), hasHeightAndBiomeData);
    }
    public StructureBlendSnapshot {
        if (identity == null || identity.isBlank()) throw new IllegalArgumentException("Blend identity required");
        densities = immutableFiniteMap(densities); alphas = immutableFiniteMap(alphas);
        beardifier = Objects.requireNonNull(beardifier, "beardifier");
        densitySamples = immutableSamples(densitySamples);
        directDensitySamples = immutableDirectSamples(directDensitySamples);
        heightSamples = immutableHeights(heightSamples);
        directHeightSamples = immutableDirectHeights(directHeightSamples);
    }
    public static StructureBlendSnapshot empty() {
        return new StructureBlendSnapshot("none", Map.of(), Map.of(), BeardifierSnapshot.empty(),
                List.of(), List.of(), List.of(), List.of(), false);
    }

    /**
     * One value exposed by Minecraft's old-generation blending data.  The
     * horizontal coordinates are quart coordinates and cellY is the
     * two-cells-per-section vertical coordinate used by Blender.
     */
    public record DensitySample(int quartX, int cellY, int quartZ, double density) {
        public DensitySample {
            if (!Double.isFinite(density)) throw new IllegalArgumentException("Blend density must be finite");
        }
    }

    /**
     * One source-local value returned by {@code BlendingData.getDensity}.
     * Keeping the source section and local coordinates is necessary because
     * Blender probes the current section first, then its negative-X/Z
     * neighbours at quart boundaries.  Flattening these values into absolute
     * coordinates loses that precedence when adjacent old chunks disagree.
     */
    public record DirectDensitySample(int sectionX, int sectionZ, int localX, int cellY, int localZ,
                                      double density) {
        public DirectDensitySample {
            if (localX < 0 || localX > 4 || localZ < 0 || localZ > 4) {
                throw new IllegalArgumentException("Direct blend local coordinate must be in [0,4]");
            }
            if (!Double.isFinite(density)) throw new IllegalArgumentException("Blend density must be finite");
        }
    }

    /** One weighted old-generation height sample in quart X/Z coordinates. */
    public record HeightSample(int quartX, int quartZ, double height) {
        public HeightSample {
            if (!Double.isFinite(height)) throw new IllegalArgumentException("Blend height must be finite");
        }
    }

    /** One source-local value returned by {@code BlendingData.getHeight}. */
    public record DirectHeightSample(int sectionX, int sectionZ, int localX, int localZ, double height) {
        public DirectHeightSample {
            if (localX < 0 || localX > 4 || localZ < 0 || localZ > 4) {
                throw new IllegalArgumentException("Direct blend height local coordinate must be in [0,4]");
            }
            if (!Double.isFinite(height)) throw new IllegalArgumentException("Blend height must be finite");
        }
    }

    private static List<DensitySample> immutableSamples(List<DensitySample> input) {
        if (input == null) return List.of();
        var result = new java.util.ArrayList<DensitySample>(input.size());
        for (DensitySample sample : input) result.add(Objects.requireNonNull(sample, "density sample"));
        return List.copyOf(result);
    }
    private static List<DirectDensitySample> immutableDirectSamples(List<DirectDensitySample> input) {
        if (input == null) return List.of();
        var result = new java.util.ArrayList<DirectDensitySample>(input.size());
        for (DirectDensitySample sample : input) result.add(Objects.requireNonNull(sample, "direct density sample"));
        return List.copyOf(result);
    }
    private static List<HeightSample> immutableHeights(List<HeightSample> input) {
        if (input == null) return List.of();
        var result = new java.util.ArrayList<HeightSample>(input.size());
        for (HeightSample sample : input) result.add(Objects.requireNonNull(sample, "height sample"));
        return List.copyOf(result);
    }
    private static List<DirectHeightSample> immutableDirectHeights(List<DirectHeightSample> input) {
        if (input == null) return List.of();
        var result = new java.util.ArrayList<DirectHeightSample>(input.size());
        for (DirectHeightSample sample : input) result.add(Objects.requireNonNull(sample, "direct height sample"));
        return List.copyOf(result);
    }
    private static Map<String, Double> immutableFiniteMap(Map<String, Double> input) {
        var sorted = new TreeMap<String, Double>();
        if (input != null) input.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null || !Double.isFinite(value)) throw new IllegalArgumentException("Invalid blend input");
            sorted.put(key, value);
        });
        return Collections.unmodifiableMap(sorted);
    }
}
