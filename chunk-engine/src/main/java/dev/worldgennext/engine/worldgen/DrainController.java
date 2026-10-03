// SPDX-License-Identifier: MIT
package dev.worldgennext.engine.worldgen;

import java.util.concurrent.atomic.AtomicReference;

public final class DrainController {
    public enum State { RUNNING, DRAINING, CLOSED }
    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
    public State state() { return state.get(); }
    public boolean beginDrain() { return state.compareAndSet(State.RUNNING, State.DRAINING); }
    public boolean close() { return state.getAndSet(State.CLOSED) != State.CLOSED; }
    public boolean admits() { return state.get() == State.RUNNING; }
}
