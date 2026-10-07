// SPDX-License-Identifier: MIT
package dev.tellurium.engine.worldgen;

import dev.tellurium.spatial.ByteBudget;
import java.util.Optional;

/** All-or-nothing reservation of every resource class needed by one admitted work item. */
public final class ResourceAdmission implements AutoCloseable {
    private final ByteBudget budget;
    private boolean closed;
    public ResourceAdmission(long totalBytes) { budget = new ByteBudget(totalBytes); }
    public synchronized Optional<ResourceReservation> tryReserve(ResourceEstimate estimate) {
        Optional<ResourceEstimate> checked = Optional.ofNullable(estimate);
        return checked.flatMap(value -> tryReserve(value.snapshotBytes(), value.stagingBytes(),
                value.outputBytes(), value.readbackBytes(), value.applicationBytes()));
    }
    public synchronized Optional<ResourceReservation> tryReserve(long snapshot, long staging, long output, long readback, long application) {
        if (closed || snapshot < 0 || staging < 0 || output < 0 || readback < 0 || application < 0) return Optional.empty();
        long total;
        try { total = Math.addExact(Math.addExact(Math.addExact(Math.addExact(snapshot, staging), output), readback), application); }
        catch (ArithmeticException overflow) { return Optional.empty(); }
        var all = budget.tryReserve(total); if (all.isEmpty()) return Optional.empty();
        // Split accounting logically while holding one physical budget lease.
        ByteBudget.Reservation shared = all.orElseThrow();
        return Optional.of(new ResourceReservation(shared, snapshot, staging, output, readback, application));
    }
    public synchronized long usedBytes() { return budget.usedBytes(); }
    public long capacityBytes() { return budget.capacity(); }
    @Override public synchronized void close() { closed = true; }
}
