// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.jvm;

import dev.worldgennext.semantic.*;
import java.util.IdentityHashMap;
import java.util.Objects;

/** Prebinds immutable nodes to reusable callbacks; does not generate JVM bytecode. */
public final class CpuCompiler {
    public CpuCompiler() {}

    public CompiledDensity compile(DensityExpression expression) {
        ExpressionValidation.validate(expression);
        CompiledDensity program = bind(expression, new IdentityHashMap<>());
        return point -> program.sample(Objects.requireNonNull(point, "point"));
    }

    private CompiledDensity bind(DensityExpression expression, IdentityHashMap<DensityExpression, CompiledDensity> cache) {
        CompiledDensity previous = cache.get(expression);
        if (previous != null) return previous;
        CompiledDensity program = switch (expression) {
            case DensityExpression.Constant n -> p -> n.value();
            case DensityExpression.Coordinate n -> switch (n.axis()) {
                case X -> p -> p.x(); case Y -> p -> p.y(); case Z -> p -> p.z();
            };
            case DensityExpression.Add n -> {
                CompiledDensity left = bind(n.left(), cache), right = bind(n.right(), cache);
                yield p -> checked(left.sample(p) + right.sample(p));
            }
            case DensityExpression.Multiply n -> {
                CompiledDensity left = bind(n.left(), cache), right = bind(n.right(), cache);
                yield p -> checked(left.sample(p) * right.sample(p));
            }
            case DensityExpression.RangeChoice n -> {
                CompiledDensity input = bind(n.input(), cache), in = bind(n.whenIn(), cache), out = bind(n.whenOut(), cache);
                double minimum = n.minInclusive(), maximum = n.maxExclusive();
                yield p -> { double value = input.sample(p); return (value >= minimum && value < maximum ? in : out).sample(p); };
            }
            case DensityExpression.Interpolated n -> bindBoundary(bind(n.child(), cache), n.geometry());
        };
        cache.put(expression, program);
        return program;
    }

    private static CompiledDensity bindBoundary(CompiledDensity child, CellGeometry geometry) {
        int width = geometry.width(), height = geometry.height();
        return p -> {
            // Deliberately independent of the reference interpreter's floorDiv implementation.
            int rx = p.x() % width; if (rx < 0) rx += width;
            int ry = p.y() % height; if (ry < 0) ry += height;
            int rz = p.z() % width; if (rz < 0) rz += width;
            int x0 = Math.toIntExact((long) p.x() - rx), x1 = Math.addExact(x0, width);
            int y0 = Math.toIntExact((long) p.y() - ry), y1 = Math.addExact(y0, height);
            int z0 = Math.toIntExact((long) p.z() - rz), z1 = Math.addExact(z0, width);
            double fy = (double) ry / height, fx = (double) rx / width, fz = (double) rz / width;
            double a = child.sample(new SamplePoint(x0, y0, z0));
            double b = child.sample(new SamplePoint(x0, y1, z0));
            double c = child.sample(new SamplePoint(x0, y0, z1));
            double d = child.sample(new SamplePoint(x0, y1, z1));
            double e = child.sample(new SamplePoint(x1, y0, z0));
            double f = child.sample(new SamplePoint(x1, y1, z0));
            double g = child.sample(new SamplePoint(x1, y0, z1));
            double h = child.sample(new SamplePoint(x1, y1, z1));
            double y00 = orderedLerp(fy, a, b), y01 = orderedLerp(fy, c, d);
            double y10 = orderedLerp(fy, e, f), y11 = orderedLerp(fy, g, h);
            double x0z = orderedLerp(fx, y00, y10), x1z = orderedLerp(fx, y01, y11);
            return orderedLerp(fz, x0z, x1z);
        };
    }

    private static double orderedLerp(double fraction, double low, double high) {
        double delta = checked(high - low);
        double product = checked(fraction * delta);
        return checked(low + product);
    }

    private static double checked(double value) {
        if (!Double.isFinite(value)) throw new ArithmeticException("Compiled sample produced non-finite intermediate");
        return value;
    }
}
