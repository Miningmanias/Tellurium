// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.jvm.worldgen;

import dev.tellurium.semantic.program.InterpolationGeometry;

/** Request-local interpolation cursor; it never stores a reusable world cache. */
public final class InterpolationCursor {
    private final InterpolationGeometry geometry;
    private int x, y, z;
    public InterpolationCursor(InterpolationGeometry geometry) { this.geometry = java.util.Objects.requireNonNull(geometry); }
    public void moveTo(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    public int cellX() { return Math.floorDiv(x, geometry.horizontalCell()); }
    public int cellY() { return Math.floorDiv(y, geometry.verticalCell()); }
    public int cellZ() { return Math.floorDiv(z, geometry.horizontalCell()); }
    public double fractionX() { return (double) Math.floorMod(x, geometry.horizontalCell()) / geometry.horizontalCell(); }
    public double fractionY() { return (double) Math.floorMod(y, geometry.verticalCell()) / geometry.verticalCell(); }
    public double fractionZ() { return (double) Math.floorMod(z, geometry.horizontalCell()) / geometry.horizontalCell(); }
}
