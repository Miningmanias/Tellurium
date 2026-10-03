// SPDX-License-Identifier: MIT
package dev.worldgennext.spatial.worldgen;

import java.util.concurrent.atomic.AtomicBoolean;

/** Immutable consumer view over one owned sample array. */
public final class SampleLease implements AutoCloseable {
    private final SampleKey key;
    private final double[] values;
    private final Runnable releaser;
    private final AtomicBoolean closed = new AtomicBoolean();
    public SampleLease(SampleKey key, double[] values, Runnable releaser) {
        this.key = java.util.Objects.requireNonNull(key, "key");
        this.values = java.util.Objects.requireNonNull(values, "values").clone();
        if (this.values.length != key.requestedExtent().volume()) throw new IllegalArgumentException("Sample count does not match extent");
        this.releaser = java.util.Objects.requireNonNull(releaser, "releaser");
    }
    public SampleKey key() { return key; }
    public int size() { return values.length; }
    public double value(int x, int y, int z) { ensureOpen(); return values[key.requestedExtent().index(x, y, z)]; }
    public double[] values() { ensureOpen(); return values.clone(); }
    public boolean isClosed() { return closed.get(); }
    private void ensureOpen() { if (closed.get()) throw new IllegalStateException("Sample lease is closed"); }
    @Override public void close() { if (closed.compareAndSet(false, true)) releaser.run(); }
}
