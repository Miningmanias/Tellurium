// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

public final class ServerLifecycle {
    public enum State { STOPPED, STARTING, RUNNING, STOPPING }
    private State state = State.STOPPED;

    public synchronized State state() { return state; }

    public synchronized boolean running() { return state == State.RUNNING; }

    /** Generation may begin while the server is constructing its start region. */
    public synchronized boolean acceptingGeneration() {
        return state == State.STARTING || state == State.RUNNING;
    }

    public synchronized void start() {
        if (state != State.STOPPED) throw new IllegalStateException("Server lifecycle is " + state);
        state = State.STARTING;
    }

    public synchronized void ready() {
        if (state != State.STARTING) throw new IllegalStateException("Server is not starting");
        state = State.RUNNING;
    }

    /** Idempotent for duplicate stopping events, but never revives a server. */
    public synchronized void stop() {
        if (state == State.STOPPED || state == State.STOPPING) return;
        state = State.STOPPING;
    }

    public synchronized void stopped() {
        if (state != State.STOPPING && state != State.STOPPED) {
            throw new IllegalStateException("Server stopped event arrived while " + state);
        }
        state = State.STOPPED;
    }

    public synchronized void requireRunning() {
        if (state != State.RUNNING) throw new IllegalStateException("Server lifecycle is " + state);
    }
}
