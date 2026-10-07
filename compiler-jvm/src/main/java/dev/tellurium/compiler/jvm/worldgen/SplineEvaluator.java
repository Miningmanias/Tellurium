// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.jvm.worldgen;

import java.util.Arrays;

/** Ordered piecewise-linear spline with explicit endpoint extrapolation. */
public final class SplineEvaluator {
    public record Point(double location, double value) {
        public Point { if (!Double.isFinite(location) || !Double.isFinite(value)) throw new IllegalArgumentException("Non-finite spline point"); }
    }
    private final Point[] points;
    public SplineEvaluator(java.util.List<Point> points) {
        if (points == null || points.isEmpty()) throw new IllegalArgumentException("Spline requires points");
        this.points = points.toArray(Point[]::new);
        for (int i = 1; i < this.points.length; i++) if (!(this.points[i - 1].location() < this.points[i].location())) throw new IllegalArgumentException("Spline locations must increase");
    }
    public double sample(double input) {
        if (!Double.isFinite(input)) throw new IllegalArgumentException("Non-finite spline input");
        // One point has no slope: the spline is that point's value everywhere.
        if (points.length == 1) return points[0].value();
        if (input <= points[0].location()) return extrapolate(points[0], points[Math.min(1, points.length - 1)], input);
        for (int i = 1; i < points.length; i++) if (input <= points[i].location()) return interpolate(points[i - 1], points[i], input);
        return extrapolate(points[points.length - 2], points[points.length - 1], input);
    }
    public Point[] points() { return points.clone(); }
    private static double interpolate(Point a, Point b, double x) { double t = (x - a.location()) / (b.location() - a.location()); return a.value() + t * (b.value() - a.value()); }
    private static double extrapolate(Point a, Point b, double x) { return interpolate(a, b, x); }
}
