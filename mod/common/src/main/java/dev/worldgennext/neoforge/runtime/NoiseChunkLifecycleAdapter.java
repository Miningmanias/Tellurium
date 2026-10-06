// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

public final class NoiseChunkLifecycleAdapter {
    public enum State { CAPTURED, RELEASED_FOR_COMPUTE, RESULT_READY, PUBLISHED, INVALIDATED }
    private State state = State.CAPTURED;
    public synchronized State state() { return state; }
    public synchronized void releaseForCompute() { require(State.CAPTURED); state = State.RELEASED_FOR_COMPUTE; }
    public synchronized void resultReady() { require(State.RELEASED_FOR_COMPUTE); state = State.RESULT_READY; }
    public synchronized void published() { require(State.RESULT_READY); state = State.PUBLISHED; }
    public synchronized void invalidate() { if (state != State.PUBLISHED) state = State.INVALIDATED; }
    private void require(State expected) { if (state != expected) throw new IllegalStateException("NoiseChunk lifecycle is " + state + ", expected " + expected); }
}
