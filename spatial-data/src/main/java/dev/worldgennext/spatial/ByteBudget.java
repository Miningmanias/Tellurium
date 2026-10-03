// SPDX-License-Identifier: MIT
package dev.worldgennext.spatial;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Nonblocking byte admission. Reservations are explicit, thread-safe and released once. */
public final class ByteBudget {
    private final long capacity;
    private long used;
    public ByteBudget(long capacity) {
        if (capacity < 0) throw new IllegalArgumentException("Negative capacity");
        this.capacity = capacity;
    }
    public long capacity() { return capacity; }
    public synchronized long usedBytes() { return used; }
    public synchronized long availableBytes() { return capacity - used; }
    public synchronized Optional<Reservation> tryReserve(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative reservation");
        if (bytes > capacity - used) return Optional.empty();
        used += bytes;
        return Optional.of(new Reservation(this, bytes));
    }
    private synchronized void release(long bytes) {
        if (bytes > used) throw new IllegalStateException("Budget accounting underflow");
        used -= bytes;
    }
    public static final class Reservation implements AutoCloseable {
        private final ByteBudget owner;
        private final long bytes;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Reservation(ByteBudget owner, long bytes) { this.owner = owner; this.bytes = bytes; }
        public long bytes() { return bytes; }
        public boolean isClosed() { return closed.get(); }
        @Override public void close() { if (closed.compareAndSet(false, true)) owner.release(bytes); }
    }
}
