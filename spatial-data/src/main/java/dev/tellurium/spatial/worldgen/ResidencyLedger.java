// SPDX-License-Identifier: MIT
package dev.tellurium.spatial.worldgen;

import dev.tellurium.spatial.ByteBudget;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Accounts retained capacities independently from caller object sizes. */
public final class ResidencyLedger implements AutoCloseable {
    private final ByteBudget budget;
    private final Map<SampleKey, ByteBudget.Reservation> reservations = new LinkedHashMap<>();
    private boolean closed;
    public ResidencyLedger(long capacity) { budget = new ByteBudget(capacity); }
    public synchronized java.util.Optional<Lease> tryReserve(SampleKey key, long bytes) {
        Objects.requireNonNull(key, "key");
        if (bytes < 0) throw new IllegalArgumentException("Negative residency reservation");
        if (closed || reservations.containsKey(key)) return java.util.Optional.empty();
        var reservation = budget.tryReserve(bytes); if (reservation.isEmpty()) return java.util.Optional.empty();
        reservations.put(key, reservation.orElseThrow()); return java.util.Optional.of(new Lease(key, reservation.orElseThrow()));
    }
    public synchronized long usedBytes() { return budget.usedBytes(); }
    public synchronized int residentEntries() { return reservations.size(); }
    public synchronized Map<SampleKey, Long> snapshot() { var copy = new LinkedHashMap<SampleKey, Long>(); reservations.forEach((key, value) -> copy.put(key, value.bytes())); return Collections.unmodifiableMap(copy); }
    public synchronized void release(SampleKey key) { var reservation = reservations.remove(key); if (reservation != null) reservation.close(); }
    @Override public synchronized void close() { closed = true; reservations.values().forEach(ByteBudget.Reservation::close); reservations.clear(); }
    public final class Lease implements AutoCloseable {
        private final SampleKey key; private final ByteBudget.Reservation reservation; private boolean closed;
        private Lease(SampleKey key, ByteBudget.Reservation reservation) { this.key = key; this.reservation = reservation; }
        public SampleKey key() { return key; } public long bytes() { return reservation.bytes(); }
        @Override public void close() { synchronized (ResidencyLedger.this) { if (!closed) { closed = true; reservations.remove(key, reservation); reservation.close(); } } }
    }
}
