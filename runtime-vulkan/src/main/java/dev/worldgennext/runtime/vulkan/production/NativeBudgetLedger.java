// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan.production;

import java.util.concurrent.atomic.AtomicBoolean;

public final class NativeBudgetLedger implements AutoCloseable {
    private final long capacity; private long used; private boolean closed;
    public NativeBudgetLedger(long capacity) { if (capacity < 0) throw new IllegalArgumentException("Negative native budget"); this.capacity = capacity; }
    public synchronized long capacity() { return capacity; } public synchronized long used() { return used; } public synchronized long available() { return capacity - used; }
    public synchronized Lease tryReserve(long bytes) { if (closed || bytes < 0 || bytes > capacity - used) return null; used += bytes; return new Lease(bytes); }
    @Override public synchronized void close() { closed = true; }
    public final class Lease implements AutoCloseable { private final long bytes; private final AtomicBoolean closed = new AtomicBoolean(); private Lease(long bytes) { this.bytes = bytes; } public long bytes() { return bytes; } public boolean isClosed() { return closed.get(); } @Override public void close() { if (closed.compareAndSet(false, true)) synchronized (NativeBudgetLedger.this) { used -= bytes; } } }
}
