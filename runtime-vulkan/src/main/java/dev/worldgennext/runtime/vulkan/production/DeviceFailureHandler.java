// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan.production;

public final class DeviceFailureHandler {
    public enum State { READY, DRAINING, DISABLED, LOST }
    private State state = State.READY;
    public synchronized State state() { return state; }
    public synchronized void disable() { if (state == State.READY) state = State.DISABLED; }
    public synchronized void beginDrain() { if (state == State.READY) state = State.DRAINING; }
    public synchronized void lost() { state = State.LOST; }
    public synchronized boolean admits() { return state == State.READY; }
}
