// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.snapshot;

import dev.worldgennext.semantic.snapshot.BeardifierSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StructureBlendReaderTest {
    @Test
    void capturesLoaderDensityCallbacksAsImmutableQuartCellSamples() {
        var blender = new FakeBlender(Map.of(chunkKey(-2, 3), new FakeBlendingData()));
        var captured = new StructureBlendReader().capture(blender, BeardifierSnapshot.empty());
        assertEquals("blender:" + FakeBlender.class.getName(), captured.identity());
        assertEquals(List.of(
                new dev.worldgennext.semantic.snapshot.StructureBlendSnapshot.DensitySample(-8, 0, 12, 1.25),
                new dev.worldgennext.semantic.snapshot.StructureBlendSnapshot.DensitySample(-7, 1, 13, -0.5)),
                captured.densitySamples());
    }

    @Test
    void rejectsAblendedObjectWithoutTheVersionPinnedDensityMap() {
        assertThrows(IllegalArgumentException.class, () -> new StructureBlendReader().capture(new Object()));
    }

    @Test
    void capturesDirectLookupValuesWithTheirSourceSectionCoordinates() {
        var blender = new FakeBlender(Map.of(chunkKey(-2, 3), new DirectFakeBlendingData()));
        var captured = new StructureBlendReader().capture(blender);
        assertEquals(List.of(
                new dev.worldgennext.semantic.snapshot.StructureBlendSnapshot.DirectDensitySample(
                        -2, 3, 0, 0, 0, 0.1),
                new dev.worldgennext.semantic.snapshot.StructureBlendSnapshot.DirectDensitySample(
                -2, 3, 4, 1, 4, 2.5)), captured.directDensitySamples());
    }

    @Test
    void capturesDirectDensityLookupFromTheBroaderHeightBlendMap() {
        var blender = new FakeBlender(Map.of(),
                Map.of(chunkKey(11, -7), new DirectFakeBlendingData()));
        var captured = new StructureBlendReader().capture(blender);
        assertEquals(List.of(
                new dev.worldgennext.semantic.snapshot.StructureBlendSnapshot.DirectDensitySample(
                        11, -7, 0, 0, 0, 0.1),
                new dev.worldgennext.semantic.snapshot.StructureBlendSnapshot.DirectDensitySample(
                        11, -7, 4, 1, 4, 2.5)), captured.directDensitySamples());
    }

    @Test
    void rejectsAnOverflowingDirectBlenderYRange() {
        var blender = new FakeBlender(Map.of(chunkKey(0, 0),
                new DirectFakeBlendingData(Integer.MAX_VALUE, 1)));
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new StructureBlendReader().capture(blender));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("Y range"));
    }

    @Test
    void recordsNonEmptyHeightAndBiomeDataInsteadOfTreatingBlendFunctionsAsIdentity() {
        var blender = new FakeBlender(Map.of(), Map.of(chunkKey(1, -1), new FakeBlendingData()));
        var captured = new StructureBlendReader().capture(blender);
        org.junit.jupiter.api.Assertions.assertTrue(captured.hasHeightAndBiomeData());
    }

    private static final class FakeBlender {
        private final Map<Long, Object> densityBlendingData;
        private final Map<Long, Object> heightAndBiomeBlendingData;

        private FakeBlender(Map<Long, ?> densityBlendingData) {
            this(densityBlendingData, Map.of());
        }

        private FakeBlender(Map<Long, ?> densityBlendingData, Map<Long, ?> heightAndBiomeBlendingData) {
            this.densityBlendingData = new java.util.LinkedHashMap<>(densityBlendingData);
            this.heightAndBiomeBlendingData = new java.util.LinkedHashMap<>(heightAndBiomeBlendingData);
        }
    }

    private static long chunkKey(int x, int z) {
        return (x & 0xffff_ffffL) | ((z & 0xffff_ffffL) << 32);
    }

    private static final class FakeBlendingData {
        protected void iterateDensities(int baseX, int baseZ, int minY, int maxY, DensityConsumer consumer) {
            consumer.consume(baseX, 0, baseZ, 1.25);
            consumer.consume(baseX + 1, 1, baseZ + 1, -0.5);
        }

        protected void iterateHeights(int baseX, int baseZ, HeightConsumer consumer) {
            consumer.consume(baseX, baseZ, 64.0);
        }

        protected double getHeight(int x, int y, int z) {
            return x == 0 && z == 0 ? 64.0 : Double.MAX_VALUE;
        }
    }

    private static final class DirectFakeBlendingData {
        private final int minY;
        private final int cellCount;

        private DirectFakeBlendingData() {
            this(0, 1);
        }

        private DirectFakeBlendingData(int minY, int cellCount) {
            this.minY = minY;
            this.cellCount = cellCount;
        }

        protected void iterateDensities(int baseX, int baseZ, int minY, int maxY, DensityConsumer consumer) {}
        protected void iterateHeights(int baseX, int baseZ, HeightConsumer consumer) {
            consumer.consume(baseX, baseZ, 64.0);
        }
        private int getMinY() { return minY; }
        private int cellCountPerColumn() { return cellCount; }
        protected double getHeight(int x, int y, int z) {
            return x == 0 && z == 0 ? 64.0 : Double.MAX_VALUE;
        }
        protected double getDensity(int x, int y, int z) {
            if (x == 0 && y == 0 && z == 0) return 0.1;
            if (x == 4 && y == 1 && z == 4) return 2.5;
            return Double.MAX_VALUE;
        }
    }

    @FunctionalInterface
    private interface DensityConsumer {
        void consume(int x, int y, int z, double density);
    }

    @FunctionalInterface
    private interface HeightConsumer {
        void consume(int x, int z, double height);
    }
}
