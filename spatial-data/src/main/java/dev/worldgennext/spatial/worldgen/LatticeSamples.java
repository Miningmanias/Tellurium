// SPDX-License-Identifier: MIT
package dev.worldgennext.spatial.worldgen;

public final class LatticeSamples {
    private final SampleExtent extent; private final double[] values;
    public LatticeSamples(SampleExtent extent, double[] values) { this.extent = java.util.Objects.requireNonNull(extent); this.values = java.util.Objects.requireNonNull(values, "values").clone(); if (values.length != extent.volume()) throw new IllegalArgumentException("Lattice value count mismatch"); }
    public SampleExtent extent() { return extent; }
    public double value(int x, int y, int z) { return values[extent.index(x, y, z)]; }
    public double[] values() { return values.clone(); }
}
