// SPDX-License-Identifier: MIT
package dev.tellurium.engine.worldgen;

import java.util.concurrent.atomic.AtomicLong;

/** World/reload epoch separate from device generation. */
public final class WorldEpoch {
    private final AtomicLong value;
    public WorldEpoch(long initial) { if (initial < 0) throw new IllegalArgumentException("Negative epoch"); value = new AtomicLong(initial); }
    public long current() { return value.get(); }
    public long advance() { return value.incrementAndGet(); }
    public boolean isCurrent(long candidate) { return candidate == value.get(); }
}
