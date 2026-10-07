// SPDX-License-Identifier: MIT
package dev.tellurium.semantic;

import java.util.Objects;

/** Independent tree interpreter for the finite synthetic language. No compiled callbacks. */
public final class ReferenceInterpreter implements DensityEvaluator {
    @Override
    public double evaluate(DensityExpression expression, SamplePoint point) {
        ExpressionValidation.validate(expression);
        return evaluateNode(expression, Objects.requireNonNull(point, "point"));
    }

    private double evaluateNode(DensityExpression expression, SamplePoint point) {
        return switch (expression) {
            case DensityExpression.Constant n -> n.value();
            case DensityExpression.Coordinate n -> switch (n.axis()) {
                case X -> point.x(); case Y -> point.y(); case Z -> point.z();
            };
            case DensityExpression.Add n -> finite(evaluateNode(n.left(), point) + evaluateNode(n.right(), point));
            case DensityExpression.Multiply n -> finite(evaluateNode(n.left(), point) * evaluateNode(n.right(), point));
            case DensityExpression.RangeChoice n -> {
                double selector = evaluateNode(n.input(), point);
                yield evaluateNode(selector >= n.minInclusive() && selector < n.maxExclusive()
                        ? n.whenIn() : n.whenOut(), point);
            }
            case DensityExpression.Interpolated n -> interpolate(n, point);
        };
    }

    private double interpolate(DensityExpression.Interpolated node, SamplePoint point) {
        int w = node.geometry().width(), h = node.geometry().height();
        // Long multiplication rejects corners outside the public signed-int coordinate domain.
        long lx = (long) Math.floorDiv(point.x(), w) * w;
        long ly = (long) Math.floorDiv(point.y(), h) * h;
        long lz = (long) Math.floorDiv(point.z(), w) * w;
        int x0 = Math.toIntExact(lx), x1 = Math.toIntExact(lx + w);
        int y0 = Math.toIntExact(ly), y1 = Math.toIntExact(ly + h);
        int z0 = Math.toIntExact(lz), z1 = Math.toIntExact(lz + w);
        double tx = (double) Math.floorMod(point.x(), w) / w;
        double ty = (double) Math.floorMod(point.y(), h) / h;
        double tz = (double) Math.floorMod(point.z(), w) / w;
        double[][] yPairs = new double[2][2];
        for (int ix = 0; ix < 2; ix++) {
            for (int iz = 0; iz < 2; iz++) {
                double a = evaluateNode(node.child(), new SamplePoint(ix == 0 ? x0 : x1, y0, iz == 0 ? z0 : z1));
                double b = evaluateNode(node.child(), new SamplePoint(ix == 0 ? x0 : x1, y1, iz == 0 ? z0 : z1));
                yPairs[ix][iz] = lerp(a, b, ty);
            }
        }
        double z0Value = lerp(yPairs[0][0], yPairs[1][0], tx);
        double z1Value = lerp(yPairs[0][1], yPairs[1][1], tx);
        return lerp(z0Value, z1Value, tz);
    }

    private static double lerp(double a, double b, double fraction) {
        double difference = finite(b - a);
        double scaled = finite(fraction * difference);
        return finite(a + scaled);
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) throw new ArithmeticException("Non-finite intermediate outside v0.1 finite language");
        return value;
    }
}
