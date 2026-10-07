// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class DeviceArena implements AutoCloseable {
    private final NativeBudgetLedger ledger;
    private final ByteBuffer storage;
    private final BufferLayout layout;
    public DeviceArena(long capacity) { if (capacity > Integer.MAX_VALUE) throw new IllegalArgumentException("Java view too large"); ledger = new NativeBudgetLedger(capacity); storage = ByteBuffer.allocateDirect((int) capacity).order(ByteOrder.LITTLE_ENDIAN); layout = new BufferLayout(capacity); }
    public synchronized Allocation allocate(String name, int bytes, int alignment) {
        if (bytes < 0) throw new IllegalArgumentException("Negative allocation size");
        var lease = ledger.tryReserve(bytes);
        if (lease == null) throw new IllegalStateException("Native budget exhausted");
        try {
            var range = layout.allocate(name, bytes, alignment);
            var view = storage.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    .position(Math.toIntExact(range.offset())).limit(Math.toIntExact(range.endExclusive()))
                    .slice().order(ByteOrder.LITTLE_ENDIAN);
            return new Allocation(range, lease, view);
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }
    public long usedBytes() { return ledger.used(); } public BufferLayout layout() { return layout; }
    @Override public void close() { ledger.close(); }
    public final class Allocation implements AutoCloseable { private final BufferLayout.Range range; private final NativeBudgetLedger.Lease lease; private final ByteBuffer view; private Allocation(BufferLayout.Range range, NativeBudgetLedger.Lease lease, ByteBuffer view) { this.range = range; this.lease = lease; this.view = view; } public BufferLayout.Range range() { return range; } public ByteBuffer view() { return view.duplicate().order(ByteOrder.LITTLE_ENDIAN); } @Override public void close() { lease.close(); } }
}
