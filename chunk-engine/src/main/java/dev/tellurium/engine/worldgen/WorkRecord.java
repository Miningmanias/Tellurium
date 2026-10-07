// SPDX-License-Identifier: MIT
package dev.tellurium.engine.worldgen;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Unique work lifecycle and finite subscriber set. */
public final class WorkRecord {
    public enum State { WAITING, ADMITTED, EXECUTING, RESULT_READY, VALIDATING, COMMITTING, COMMITTED, REJECTED, CANCELLED, STALE, FAILED }
    public record Terminal(State state, String detail, BackendResult result) {}
    private final GenerationRequest request;
    private final StageExecutor stageExecutor;
    private final CommitCoordinator commits;
    private final Set<RequestSubscription> subscribers = new HashSet<>();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private volatile State state = State.WAITING;
    private volatile boolean invalidated;
    private Terminal outcome;
    private Set<RequestSubscription> pendingNotifications = Set.of();
    /**
     * Keeps the original model-only constructor available for focused state
     * tests. Coordinator-created records always carry both execution handlers
     * so a shared work key cannot accidentally run a different backend or
     * publish into a different authoritative target.
     */
    public WorkRecord(GenerationRequest request) {
        this(request, null, null);
    }

    WorkRecord(GenerationRequest request, StageExecutor stageExecutor, CommitCoordinator commits) {
        this.request = java.util.Objects.requireNonNull(request, "request");
        this.stageExecutor = stageExecutor;
        this.commits = commits;
    }
    public GenerationRequest request() { return request; }
    StageExecutor stageExecutor() {
        return java.util.Objects.requireNonNull(stageExecutor, "coordinator stage executor");
    }
    CommitCoordinator commits() {
        return java.util.Objects.requireNonNull(commits, "coordinator commit coordinator");
    }
    boolean uses(StageExecutor candidateStageExecutor, CommitCoordinator candidateCommits) {
        return stageExecutor == candidateStageExecutor && commits == candidateCommits;
    }
    public synchronized State state() { return state; }
    public boolean invalidated() { return invalidated; }
    public synchronized int subscriberCount() { return subscribers.size(); }
    synchronized void attach(RequestSubscription subscription) {
        if (terminal.get()) subscription.complete(outcome);
        else subscribers.add(subscription);
    }
    synchronized boolean detach(RequestSubscription subscription) {
        if (terminal.get()) return false;
        return subscribers.remove(subscription);
    }
    public synchronized void state(State next) { if (terminal.get()) throw new IllegalStateException("Work is terminal"); state = next; }
    public void finish(State terminalState, String detail, BackendResult result) {
        // A public record can be finished by more than one backend/failure
        // path. Only the winner owns the notification drain; otherwise a
        // losing finish can consume notifications belonging to the winner.
        if (finishState(terminalState, detail, result)) notifyTerminalSubscribers();
    }

    /** Coordinator-only transition; publication callbacks run after retention is retired. */
    void finishForCoordinator(State terminalState, String detail, BackendResult result) {
        finishState(terminalState, detail, result);
    }

    void notifyTerminalSubscribers() {
        Set<RequestSubscription> listeners;
        Terminal completed;
        synchronized (this) {
            listeners = pendingNotifications;
            pendingNotifications = Set.of();
            completed = outcome;
        }
        listeners.forEach(listener -> listener.complete(completed));
    }

    /**
     * Marks a non-terminal record stale.  Queued work can become terminal
     * immediately; an admitted/executing record stays retained until its
     * backend callback releases the reservation.
     */
    boolean invalidate(String detail) {
        synchronized (this) {
            if (terminal.get()) return false;
            invalidated = true;
            if (state == State.WAITING) {
                state = State.STALE;
                outcome = new Terminal(State.STALE, detail == null || detail.isBlank() ? "work invalidated" : detail, null);
                terminal.set(true);
                pendingNotifications = Set.copyOf(subscribers);
                subscribers.clear();
            }
        }
        return true;
    }

    private boolean finishState(State terminalState, String detail, BackendResult result) {
        if (terminalState == null || switch (terminalState) { case COMMITTED, REJECTED, CANCELLED, STALE, FAILED -> false; default -> true; }) throw new IllegalArgumentException("Not a terminal state");
        synchronized (this) {
            // Publish the terminal payload and terminal bit under the same
            // monitor.  Otherwise attach() could observe terminal=true before
            // outcome was assigned and complete a late subscriber with null.
            if (terminal.get()) return false;
            state = terminalState;
            outcome = new Terminal(terminalState, detail == null ? "" : detail, result);
            terminal.set(true);
            pendingNotifications = Set.copyOf(subscribers);
            subscribers.clear();
            return true;
        }
    }
    public synchronized Terminal outcome() { return outcome; }
}
