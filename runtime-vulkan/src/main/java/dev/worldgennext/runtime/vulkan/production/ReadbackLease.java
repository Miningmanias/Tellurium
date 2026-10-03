// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan.production;

public final class ReadbackLease implements AutoCloseable {
    private final DeviceArena.Allocation allocation;
    public ReadbackLease(DeviceArena.Allocation allocation) { this.allocation = java.util.Objects.requireNonNull(allocation); }
    public java.nio.ByteBuffer buffer() { return allocation.view().asReadOnlyBuffer(); }
    @Override public void close() { allocation.close(); }
}
