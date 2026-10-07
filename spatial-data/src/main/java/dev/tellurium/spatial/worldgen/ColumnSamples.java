// SPDX-License-Identifier: MIT
package dev.tellurium.spatial.worldgen;

public final class ColumnSamples {
    private final SampleExtent extent; private final double[] values;
    public ColumnSamples(SampleExtent extent, double[] values) { if (extent == null || extent.width() != 1 || extent.depth() != 1) throw new IllegalArgumentException("Column samples require one X/Z column"); this.extent = extent; this.values = java.util.Objects.requireNonNull(values, "values").clone(); if (values.length != extent.height()) throw new IllegalArgumentException("Column value count mismatch"); }
    public SampleExtent extent() { return extent; }
    public double value(int y) { return values[extent.index(extent.minX(), y, extent.minZ())]; }
    public double[] values() { return values.clone(); }
}
