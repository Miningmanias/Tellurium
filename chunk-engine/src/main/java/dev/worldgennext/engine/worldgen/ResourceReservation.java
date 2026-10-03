// SPDX-License-Identifier: MIT
package dev.worldgennext.engine.worldgen;

import dev.worldgennext.spatial.ByteBudget;
import java.util.concurrent.atomic.AtomicBoolean;

/** Atomic bundle of host/staging/GPU/application budget reservations. */
public final class ResourceReservation implements AutoCloseable {
    private final ByteBudget.Reservation shared;
    private final long snapshot, staging, output, readback, application;
    private final AtomicBoolean closed = new AtomicBoolean();
    ResourceReservation(ByteBudget.Reservation shared, long snapshot, long staging, long output, long readback, long application) {
        this.shared = shared; this.snapshot = snapshot; this.staging = staging; this.output = output; this.readback = readback; this.application = application;
    }
    public long snapshotBytes() { return snapshot; }
    public long stagingBytes() { return staging; }
    public long outputBytes() { return output; }
    public long readbackBytes() { return readback; }
    public long applicationBytes() { return application; }
    public long totalBytes() {
        try {
            return Math.addExact(
                    Math.addExact(Math.addExact(Math.addExact(snapshot, staging), output), readback),
                    application);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("Resource reservation total overflows", overflow);
        }
    }
    public boolean isClosed() { return closed.get(); }
    @Override public void close() { if (closed.compareAndSet(false, true)) shared.close(); }
}
