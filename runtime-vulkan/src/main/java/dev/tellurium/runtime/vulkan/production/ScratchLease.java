// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

public final class ScratchLease implements AutoCloseable {
    private final DeviceArena.Allocation allocation;
    public ScratchLease(DeviceArena.Allocation allocation) { this.allocation = java.util.Objects.requireNonNull(allocation); }
    public java.nio.ByteBuffer buffer() { return allocation.view(); }
    @Override public void close() { allocation.close(); }
}
