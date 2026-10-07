// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Complete mutation journal seam. A rollback failure makes the target unsafe. */
public final class ChunkMutationJournal {
    public enum State { OPEN, COMMITTED, ROLLED_BACK, UNSAFE }
    private record Undo(String seam, Runnable action) {}
    private final List<Undo> undo = new ArrayList<>(); private State state = State.OPEN;
    public synchronized void record(Runnable rollback) { record("unnamed", rollback); }
    /** Records the exact loader mutation seam so failures can be diagnosed without guessing. */
    public synchronized void record(String seam, Runnable rollback) {
        if (state != State.OPEN) throw new IllegalStateException("Journal is " + state);
        if (seam == null || seam.isBlank()) throw new IllegalArgumentException("Mutation seam is required");
        undo.add(new Undo(seam, java.util.Objects.requireNonNull(rollback, "rollback")));
    }
    public synchronized void commit() { if (state != State.OPEN) throw new IllegalStateException("Journal is " + state); state = State.COMMITTED; undo.clear(); }
    public synchronized void rollback() {
        if (state != State.OPEN) return;
        RuntimeException failure = null;
        for (int i = undo.size() - 1; i >= 0; i--) {
            try { undo.get(i).action().run(); }
            catch (Throwable error) {
                IllegalStateException seamFailure = new IllegalStateException("Rollback seam failed: " + undo.get(i).seam(), error);
                if (failure == null) failure = seamFailure; else failure.addSuppressed(seamFailure);
            }
        }
        if (failure != null) {
            state = State.UNSAFE;
            throw new IllegalStateException("Chunk rollback failed; target is unsafe", failure);
        }
        state = State.ROLLED_BACK;
        undo.clear();
    }
    public synchronized State state() { return state; }
    public synchronized int mutationCount() { return undo.size(); }
    public synchronized List<String> seams() { return Collections.unmodifiableList(undo.stream().map(Undo::seam).toList()); }
}
