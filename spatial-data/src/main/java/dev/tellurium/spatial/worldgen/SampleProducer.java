// SPDX-License-Identifier: MIT
package dev.tellurium.spatial.worldgen;

import java.util.Objects;

@FunctionalInterface
public interface SampleProducer {
    double[] produce(SampleKey key) throws Exception;

    /**
     * A checked factory for a typed sample container. The adapter validates
     * the domain and requested extent before exposing immutable values to a
     * store, keeping typed producer errors explicit at the boundary.
     */
    @FunctionalInterface
    interface TypedFactory<T> {
        T produce(SampleKey key) throws Exception;
    }

    static SampleProducer lattice(TypedFactory<LatticeSamples> factory) {
        Objects.requireNonNull(factory, "factory");
        return key -> {
            requireDomain(key, SampleDomain.LATTICE);
            LatticeSamples samples = Objects.requireNonNull(factory.produce(key), "lattice factory returned null");
            requireExtent(key, samples.extent());
            return samples.values();
        };
    }

    static SampleProducer column(TypedFactory<ColumnSamples> factory) {
        Objects.requireNonNull(factory, "factory");
        return key -> {
            requireDomain(key, SampleDomain.COLUMN);
            ColumnSamples samples = Objects.requireNonNull(factory.produce(key), "column factory returned null");
            requireExtent(key, samples.extent());
            return samples.values();
        };
    }

    /**
     * Adapts a surface-height atlas to the scalar sample ABI. Missing columns
     * are rejected instead of becoming an invented height; callers with a
     * partial tile must request only the available extent.
     */
    static SampleProducer surfaceHeights(TypedFactory<SurfaceColumnAtlas> factory) {
        Objects.requireNonNull(factory, "factory");
        return key -> {
            requireDomain(key, SampleDomain.SURFACE_COLUMN);
            SampleExtent extent = key.requestedExtent();
            if (extent.height() != 1) throw new IllegalArgumentException("Surface columns require a one-level extent");
            SurfaceColumnAtlas atlas = Objects.requireNonNull(factory.produce(key), "surface factory returned null");
            double[] values = new double[extent.volume()];
            for (int z = extent.minZ(); z < extent.maxZExclusive(); z++) {
                for (int x = extent.minX(); x < extent.maxXExclusive(); x++) {
                    Integer height = atlas.height(x, z);
                    if (height == null) throw new IllegalArgumentException("Surface atlas is incomplete at " + x + "," + z);
                    values[extent.index(x, extent.minY(), z)] = height;
                }
            }
            return values;
        };
    }

    /**
     * Adapts the scalar aquifer level from a cell atlas. State and barrier
     * metadata remain available on the typed atlas; this adapter is only for
     * consumers whose requested value is the level field.
     */
    static SampleProducer aquiferLevels(TypedFactory<AquiferCellAtlas> factory) {
        Objects.requireNonNull(factory, "factory");
        return key -> {
            requireDomain(key, SampleDomain.AQUIFER_CELL);
            AquiferCellAtlas atlas = Objects.requireNonNull(factory.produce(key), "aquifer factory returned null");
            SampleExtent extent = key.requestedExtent();
            double[] values = new double[extent.volume()];
            for (int y = extent.minY(); y < extent.maxYExclusive(); y++) {
                for (int z = extent.minZ(); z < extent.maxZExclusive(); z++) {
                    for (int x = extent.minX(); x < extent.maxXExclusive(); x++) {
                        AquiferCellAtlas.Cell cell = atlas.cell(AquiferCellAtlas.key(x, y, z));
                        if (cell == null || cell.x() != x || cell.y() != y || cell.z() != z) {
                            throw new IllegalArgumentException("Aquifer atlas is incomplete at " + x + "," + y + "," + z);
                        }
                        values[extent.index(x, y, z)] = cell.level();
                    }
                }
            }
            return values;
        };
    }

    private static void requireDomain(SampleKey key, SampleDomain expected) {
        Objects.requireNonNull(key, "key");
        if (key.domain() != expected) throw new IllegalArgumentException("Expected " + expected + " sample domain, got " + key.domain());
    }

    private static void requireExtent(SampleKey key, SampleExtent actual) {
        if (!key.requestedExtent().equals(Objects.requireNonNull(actual, "sample extent"))) {
            throw new IllegalArgumentException("Typed sample extent differs from requested extent");
        }
    }
}
