// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkMutationJournalTest {
    @Test
    void preservesNamedSeamsInRegistrationOrder() {
        var journal = new ChunkMutationJournal();

        journal.record("sections/storage/counts", () -> {});
        journal.record("heightmaps", () -> {});
        journal.record("fluid/postprocessing metadata", () -> {});

        assertEquals(List.of(
                "sections/storage/counts",
                "heightmaps",
                "fluid/postprocessing metadata"), journal.seams());
        assertEquals(3, journal.mutationCount());
        assertEquals(ChunkMutationJournal.State.OPEN, journal.state());
    }

    @Test
    void commitClosesJournalClearsUndoAndDoesNotRunRollbackActions() {
        var rollbackCalls = new AtomicInteger();
        var journal = new ChunkMutationJournal();
        journal.record("blocks", rollbackCalls::incrementAndGet);

        journal.commit();
        journal.rollback();

        assertEquals(ChunkMutationJournal.State.COMMITTED, journal.state());
        assertEquals(0, rollbackCalls.get());
        assertEquals(0, journal.mutationCount());
        assertEquals(List.of(), journal.seams());
        assertThrows(IllegalStateException.class, () -> journal.record("late", () -> {}));
        assertThrows(IllegalStateException.class, journal::commit);
    }

    @Test
    void rollbackRunsUndoActionsInReverseOrderAndClosesJournal() {
        var order = new ArrayList<String>();
        var journal = new ChunkMutationJournal();
        journal.record("sections", () -> order.add("sections"));
        journal.record("heightmaps", () -> order.add("heightmaps"));
        journal.record("dirty/lifecycle", () -> order.add("dirty/lifecycle"));

        journal.rollback();

        assertEquals(List.of("dirty/lifecycle", "heightmaps", "sections"), order);
        assertEquals(ChunkMutationJournal.State.ROLLED_BACK, journal.state());
        assertEquals(0, journal.mutationCount());
        assertEquals(List.of(), journal.seams());
    }

    @Test
    void rollbackIsIdempotentAndClosedJournalRejectsNewMutations() {
        var rollbackCalls = new AtomicInteger();
        var journal = new ChunkMutationJournal();
        journal.record("blocks", rollbackCalls::incrementAndGet);

        journal.rollback();
        journal.rollback();

        assertEquals(1, rollbackCalls.get());
        assertEquals(ChunkMutationJournal.State.ROLLED_BACK, journal.state());
        assertThrows(IllegalStateException.class, () -> journal.record("late", () -> {}));
        assertThrows(IllegalStateException.class, journal::commit);
    }

    @Test
    void rollbackFailureRunsOtherSeamsAndClassifiesTargetAsUnsafe() {
        var order = new ArrayList<String>();
        var journal = new ChunkMutationJournal();
        journal.record("sections", () -> order.add("sections"));
        journal.record("heightmaps", () -> {
            order.add("heightmaps");
            throw new AssertionError("heightmap restore failed");
        });
        journal.record("dirty/lifecycle", () -> order.add("dirty/lifecycle"));

        var failure = assertThrows(IllegalStateException.class, journal::rollback);

        assertEquals(List.of("dirty/lifecycle", "heightmaps", "sections"), order);
        assertEquals(ChunkMutationJournal.State.UNSAFE, journal.state());
        assertEquals(3, journal.mutationCount());
        assertEquals(List.of("sections", "heightmaps", "dirty/lifecycle"), journal.seams());
        assertEquals("Chunk rollback failed; target is unsafe", failure.getMessage());
        assertThrows(IllegalStateException.class, () -> journal.record("late", () -> {}));
        assertThrows(IllegalStateException.class, journal::commit);

        journal.rollback();
        assertEquals(List.of("dirty/lifecycle", "heightmaps", "sections"), order);
        assertEquals(ChunkMutationJournal.State.UNSAFE, journal.state());
    }
}
