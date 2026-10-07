// SPDX-License-Identifier: MIT
package dev.tellurium.semantic.execution;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded host-visible result ownership; native handles never cross this interface. */
public final class ResultBufferLease implements AutoCloseable {
    private final ByteBuffer buffer;
    private final String allocationId;
    private final Runnable releaser;
    private final AtomicBoolean closed = new AtomicBoolean();
    public ResultBufferLease(ByteBuffer buffer, String allocationId, Runnable releaser) {
        this.buffer = Objects.requireNonNull(buffer, "buffer").asReadOnlyBuffer();
        this.allocationId = allocationId == null || allocationId.isBlank() ? "anonymous" : allocationId;
        this.releaser = Objects.requireNonNull(releaser, "releaser");
    }
    public String allocationId() { return allocationId; }
    public int capacity() { return buffer.capacity(); }
    public boolean isClosed() { return closed.get(); }
    public ByteBuffer readOnlyBuffer() {
        if (closed.get()) throw new IllegalStateException("Result lease is closed");
        return buffer.asReadOnlyBuffer();
    }
    @Override public void close() { if (closed.compareAndSet(false, true)) releaser.run(); }
}
