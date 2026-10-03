// SPDX-License-Identifier: MIT
package dev.worldgennext.engine.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FixedBatchBuilderTest {
    @Test
    void maximumMustBePositiveAndIsPreserved() {
        for (int maximum : new int[] {Integer.MIN_VALUE, -1, 0})
            assertThrows(IllegalArgumentException.class, () -> new FixedBatchBuilder(maximum));
        for (int maximum : new int[] {1, 2, 4, Integer.MAX_VALUE})
            assertEquals(maximum, new FixedBatchBuilder(maximum).maximum());
    }

    @Test
    void scalarAndSmallVariantsProgressThroughExactBoundariesAndPartialTails() {
        for (int maximum : new int[] {1, 2, 4}) {
            for (int size = 0; size <= 17; size++) {
                List<Integer> input = IntStream.range(0, size).boxed().toList();
                List<List<Integer>> batches = new FixedBatchBuilder(maximum).split(input);
                assertEquals((size + maximum - 1) / maximum, batches.size());
                assertEquals(input, batches.stream().flatMap(List::stream).toList());
                for (int batch = 0; batch < batches.size(); batch++) {
                    assertEquals(Math.min(maximum, size - batch * maximum), batches.get(batch).size());
                    assertFalse(batches.get(batch).isEmpty());
                }
            }
        }
        assertEquals(List.of(List.of(0, 1, 2, 3), List.of(4)),
                new FixedBatchBuilder(4).split(List.of(0, 1, 2, 3, 4)));
        assertEquals(List.of(List.of(0, 1, 2)),
                new FixedBatchBuilder(Integer.MAX_VALUE).split(List.of(0, 1, 2)));
    }

    @Test
    void rangeEndCapsBeforeOverflowAndReachesTheFinalElement() {
        int size = Integer.MAX_VALUE;
        assertEquals(size, FixedBatchBuilder.rangeEnd(0, size, size));
        assertEquals(size - 1, FixedBatchBuilder.rangeEnd(size - 3, size, 2));
        assertEquals(size, FixedBatchBuilder.rangeEnd(size - 2, size, 2));
        assertEquals(size, FixedBatchBuilder.rangeEnd(size - 2, size, 4));
        assertEquals(size, FixedBatchBuilder.rangeEnd(size - 1, size, 1));
        assertEquals(size, FixedBatchBuilder.rangeEnd(1, size, size));
        for (int maximum : new int[] {1, 2, 4, size}) {
            int start = size - 9;
            while (start < size) {
                int end = FixedBatchBuilder.rangeEnd(start, size, maximum);
                assertTrue(end > start && end <= size);
                assertTrue((long) end - start <= maximum);
                start = end;
            }
            assertEquals(size, start);
        }
    }

    @Test
    void rangeEndRejectsInvalidBoundsAndNonpositiveMaximum() {
        int[][] invalid = {
                {-1, 4, 2}, {0, -1, 2}, {0, 0, 2}, {4, 4, 2}, {5, 4, 2},
                {0, 4, 0}, {0, 4, -1}, {0, 4, Integer.MIN_VALUE},
                {Integer.MAX_VALUE, Integer.MAX_VALUE, 1}
        };
        for (int[] range : invalid)
            assertThrows(IllegalArgumentException.class,
                    () -> FixedBatchBuilder.rangeEnd(range[0], range[1], range[2]));
    }

    @Test
    void nullAndEmptyInputsReturnImmutableOwnedEmptyResults() {
        FixedBatchBuilder builder = new FixedBatchBuilder(2);
        ArrayList<Integer> input = new ArrayList<>();
        List<List<Integer>> empty = builder.split(input);
        List<List<Integer>> absent = builder.split(null);
        input.add(1);
        assertEquals(List.of(), empty);
        assertEquals(List.of(), absent);
        assertThrows(UnsupportedOperationException.class, () -> empty.add(List.of(1)));
        assertThrows(UnsupportedOperationException.class, () -> absent.add(List.of(1)));
    }

    @Test
    void outerAndInnerListsAreImmutableSnapshotsOfTheInput() {
        ArrayList<Integer> input = new ArrayList<>(List.of(0, 1, 2, 3, 4));
        List<List<Integer>> batches = new FixedBatchBuilder(2).split(input);
        input.set(0, 99);
        input.clear();
        assertEquals(List.of(List.of(0, 1), List.of(2, 3), List.of(4)), batches);
        assertThrows(UnsupportedOperationException.class, () -> batches.add(List.of(5)));
        assertThrows(UnsupportedOperationException.class, () -> batches.set(0, List.of(5)));
        for (List<Integer> batch : batches) {
            assertThrows(UnsupportedOperationException.class, () -> batch.add(5));
            assertThrows(UnsupportedOperationException.class, () -> batch.set(0, 5));
        }
    }
}
