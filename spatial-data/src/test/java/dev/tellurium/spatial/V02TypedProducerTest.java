// SPDX-License-Identifier: MIT
package dev.tellurium.spatial;

import dev.tellurium.semantic.identity.ContextIdentity;
import dev.tellurium.semantic.identity.DynamicInputIdentity;
import dev.tellurium.semantic.program.NumericProfile;
import dev.tellurium.spatial.worldgen.AquiferCellAtlas;
import dev.tellurium.spatial.worldgen.ColumnSamples;
import dev.tellurium.spatial.worldgen.LatticeSamples;
import dev.tellurium.spatial.worldgen.SampleDomain;
import dev.tellurium.spatial.worldgen.SampleExtent;
import dev.tellurium.spatial.worldgen.SampleKey;
import dev.tellurium.spatial.worldgen.SampleLease;
import dev.tellurium.spatial.worldgen.SampleProducer;
import dev.tellurium.spatial.worldgen.SurfaceColumnAtlas;
import dev.tellurium.spatial.worldgen.UncachedSampleStore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class V02TypedProducerTest {
    private static final ContextIdentity CONTEXT = new ContextIdentity(
            "snapshot", "registry", "program", NumericProfile.JAVA_REFERENCE,
            "abi", "compiler", 0, DynamicInputIdentity.empty(), 0);

    @Test
    void latticeAndColumnAdaptersEnforceDomainAndExtent() {
        SampleKey latticeKey = new SampleKey("density", SampleDomain.LATTICE,
                new SampleExtent(-1, 0, -1, 1, 2, 1), 1, CONTEXT);
        var expectedExtent = latticeKey.requestedExtent();
        SampleProducer lattice = SampleProducer.lattice(key ->
                new LatticeSamples(expectedExtent, new double[expectedExtent.volume()]));
        try (SampleLease lease = new UncachedSampleStore().acquire(latticeKey, lattice)
                .toCompletableFuture().join()) {
            assertEquals(expectedExtent.volume(), lease.size());
        }

        SampleKey columnKey = new SampleKey("column", SampleDomain.COLUMN,
                new SampleExtent(4, -2, 7, 5, 1, 8), 0, CONTEXT);
        SampleProducer column = SampleProducer.column(key ->
                new ColumnSamples(key.requestedExtent(), new double[]{10, 11, 12}));
        try (SampleLease lease = new UncachedSampleStore().acquire(columnKey, column)
                .toCompletableFuture().join()) {
            assertEquals(11, lease.value(4, -1, 7));
        }

        SampleKey wrongDomain = new SampleKey("column", SampleDomain.BLOCK,
                columnKey.extent(), 0, CONTEXT);
        assertThrows(CompletionException.class, () -> new UncachedSampleStore()
                .acquire(wrongDomain, column).toCompletableFuture().join());
        assertThrows(CompletionException.class, () -> new UncachedSampleStore()
                .acquire(latticeKey, key -> new double[key.requestedExtent().volume() - 1])
                .toCompletableFuture().join());
    }

    @Test
    void surfaceAndAquiferAdaptersRejectIncompleteTypedTiles() {
        SampleExtent surfaceExtent = new SampleExtent(-1, 0, -1, 1, 1, 1);
        SampleKey surfaceKey = new SampleKey("surface", SampleDomain.SURFACE_COLUMN,
                surfaceExtent, 0, CONTEXT);
        Map<Long, Integer> heights = new LinkedHashMap<>();
        heights.put(SurfaceColumnAtlas.key(-1, -1), 63);
        heights.put(SurfaceColumnAtlas.key(0, -1), 64);
        heights.put(SurfaceColumnAtlas.key(-1, 0), 65);
        heights.put(SurfaceColumnAtlas.key(0, 0), 66);
        SampleProducer surface = SampleProducer.surfaceHeights(key -> new SurfaceColumnAtlas(heights));
        try (SampleLease lease = new UncachedSampleStore().acquire(surfaceKey, surface)
                .toCompletableFuture().join()) {
            assertArrayEquals(new double[]{63, 64, 65, 66}, lease.values());
        }

        SampleExtent aquiferExtent = new SampleExtent(0, 0, 0, 2, 1, 1);
        SampleKey aquiferKey = new SampleKey("aquifer", SampleDomain.AQUIFER_CELL,
                aquiferExtent, 0, CONTEXT);
        Map<Long, AquiferCellAtlas.Cell> cells = new LinkedHashMap<>();
        cells.put(AquiferCellAtlas.key(0, 0, 0), new AquiferCellAtlas.Cell(0, 0, 0, 12, "minecraft:water", false));
        cells.put(AquiferCellAtlas.key(1, 0, 0), new AquiferCellAtlas.Cell(1, 0, 0, 8, "minecraft:water", true));
        SampleProducer aquifer = SampleProducer.aquiferLevels(key -> new AquiferCellAtlas(cells));
        try (SampleLease lease = new UncachedSampleStore().acquire(aquiferKey, aquifer)
                .toCompletableFuture().join()) {
            assertArrayEquals(new double[]{12, 8}, lease.values());
        }

        Map<Long, Integer> partial = Map.of(SurfaceColumnAtlas.key(0, 0), 65);
        SampleProducer incomplete = SampleProducer.surfaceHeights(key -> new SurfaceColumnAtlas(partial));
        assertThrows(CompletionException.class, () -> new UncachedSampleStore()
                .acquire(surfaceKey, incomplete).toCompletableFuture().join());
    }

    @Test
    void uncachedStoreNeverTurnsNullOrMalformedProducerOutputIntoZeros() {
        SampleKey key = new SampleKey("density", SampleDomain.LATTICE,
                new SampleExtent(0, 0, 0, 2, 1, 1), 0, CONTEXT);
        var store = new UncachedSampleStore();
        assertThrows(CompletionException.class, () -> store.acquire(key, ignored -> null)
                .toCompletableFuture().join());
        assertThrows(CompletionException.class, () -> store.acquire(key, ignored -> new double[]{1})
                .toCompletableFuture().join());
        assertThrows(NullPointerException.class, () -> new SampleLease(key, null, () -> {}));
    }
}
