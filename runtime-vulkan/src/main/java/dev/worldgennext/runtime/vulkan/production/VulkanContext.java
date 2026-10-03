// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan.production;

import dev.worldgennext.runtime.vulkan.DeviceCapabilities;
import java.util.Objects;

/** Persistent context identity; construction itself does not load Vulkan. */
public final class VulkanContext implements AutoCloseable {
    public enum State { NEW, READY, DRAINING, LOST, CLOSED }
    private final DeviceCapabilities capabilities;
    private DeviceGeneration generation;
    private State state = State.NEW;
    public VulkanContext(DeviceCapabilities capabilities, DeviceGeneration generation) { this.capabilities = Objects.requireNonNull(capabilities); this.generation = Objects.requireNonNull(generation); }
    public synchronized DeviceCapabilities capabilities() { return capabilities; }
    public synchronized DeviceGeneration generation() { return generation; }
    public synchronized State state() { return state; }
    public synchronized void start() { if (state != State.NEW) throw new IllegalStateException("Context cannot start from " + state); state = State.READY; }
    public synchronized void beginDrain() { if (state == State.READY) state = State.DRAINING; }
    /** Invalidate every receipt issued for the previous device generation. */
    public synchronized void markLost() { if (state != State.CLOSED) { generation = generation.next(); state = State.LOST; } }
    @Override public synchronized void close() { state = State.CLOSED; }
}
