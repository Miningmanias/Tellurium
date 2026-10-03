// SPDX-License-Identifier: MIT
package dev.worldgennext.spatial.worldgen;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One consumer-owned view over a demand-driven spatial window.
 *
 * <p>The window has a stable, contiguous value view even when the producer
 * computed it as several smaller tiles.  Its child leases remain held until
 * this window is closed, so a caller that needs to retain the values also
 * retains the corresponding bounded residency explicitly.</p>
 */
public final class SampleWindow implements AutoCloseable {
    private final SampleKey key;
    private final SampleExtent extent;
    private final double[] values;
    private final List<SampleLease> leases;
    private final AtomicBoolean closed = new AtomicBoolean();

    SampleWindow(SampleKey key, SampleExtent extent, double[] values, List<SampleLease> leases) {
        this.key = Objects.requireNonNull(key, "key");
        this.extent = Objects.requireNonNull(extent, "extent");
        if (!extent.equals(key.requestedExtent())) {
            throw new IllegalArgumentException("Window extent differs from requested sample extent");
        }
        this.values = Objects.requireNonNull(values, "values").clone();
        if (this.values.length != extent.volume()) {
            throw new IllegalArgumentException("Window value count differs from extent");
        }
        this.leases = List.copyOf(Objects.requireNonNull(leases, "leases"));
        this.leases.forEach(lease -> {
            Objects.requireNonNull(lease, "window lease");
            if (!lease.key().requestedExtent().equals(lease.key().extent())) {
                throw new IllegalArgumentException("Window child lease must not carry a halo");
            }
        });
    }

    public SampleKey key() { return key; }
    public SampleExtent extent() { return extent; }
    public int size() { return values.length; }

    public double value(int x, int y, int z) {
        ensureOpen();
        return values[extent.index(x, y, z)];
    }

    public double[] values() {
        ensureOpen();
        return values.clone();
    }

    public boolean isClosed() { return closed.get(); }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            leases.forEach(SampleLease::close);
        }
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("Sample window is closed");
    }
}
