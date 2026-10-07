// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

public final class UploadLease implements AutoCloseable {
    private final DeviceArena.Allocation allocation;
    public UploadLease(DeviceArena.Allocation allocation) { this.allocation = java.util.Objects.requireNonNull(allocation); }
    public java.nio.ByteBuffer buffer() { return allocation.view(); }
    @Override public void close() { allocation.close(); }
}
