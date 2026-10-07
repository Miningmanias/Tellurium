// SPDX-License-Identifier: MIT
package dev.tellurium.semantic;

/** Immutable synthetic expression language; this is not Minecraft graph lowering. */
public sealed interface DensityExpression {
    enum Axis { X, Y, Z }
    record Constant(double value) implements DensityExpression {
        public Constant { requireFinite(value, "constant"); }
    }
    record Coordinate(Axis axis) implements DensityExpression {
        public Coordinate { java.util.Objects.requireNonNull(axis, "axis"); }
    }
    record Add(DensityExpression left, DensityExpression right) implements DensityExpression {
        public Add { requireChildren(left, right); }
    }
    record Multiply(DensityExpression left, DensityExpression right) implements DensityExpression {
        public Multiply { requireChildren(left, right); }
    }
    /** Selects exactly one branch; the lower bound is inclusive and upper exclusive. */
    record RangeChoice(DensityExpression input, double minInclusive, double maxExclusive,
                       DensityExpression whenIn, DensityExpression whenOut) implements DensityExpression {
        public RangeChoice {
            java.util.Objects.requireNonNull(input, "input");
            requireChildren(whenIn, whenOut);
            requireFinite(minInclusive, "minimum");
            requireFinite(maxExclusive, "maximum");
            if (!(minInclusive < maxExclusive)) throw new IllegalArgumentException("Empty or reversed range");
        }
    }
    /** Child is sampled at eight corners, then interpolated Y, X, Z in that order. */
    record Interpolated(DensityExpression child, CellGeometry geometry) implements DensityExpression {
        public Interpolated {
            java.util.Objects.requireNonNull(child, "child");
            java.util.Objects.requireNonNull(geometry, "geometry");
        }
    }
    private static void requireChildren(DensityExpression left, DensityExpression right) {
        java.util.Objects.requireNonNull(left, "left/whenIn");
        java.util.Objects.requireNonNull(right, "right/whenOut");
    }
    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
    }
}
