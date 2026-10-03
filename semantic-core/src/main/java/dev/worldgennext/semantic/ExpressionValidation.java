// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic;

import java.util.IdentityHashMap;
import java.util.Objects;

/** Bounds the synthetic program before recursive interpretation or code generation. */
public final class ExpressionValidation {
    public static final int MAX_DEPTH = 128;
    public static final int MAX_NODES = 4096;
    public static final long MAX_SAMPLE_OPERATIONS = 65_536;

    private ExpressionValidation() {}

    public static void validate(DensityExpression expression) {
        visit(Objects.requireNonNull(expression, "expression"), 1, new IdentityHashMap<>());
    }

    private record Bound(long cost, int height) {}

    private static Bound visit(DensityExpression expression, int depth,
                               IdentityHashMap<DensityExpression, Bound> costs) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Expression depth exceeds " + MAX_DEPTH);
        Bound prior = costs.get(expression);
        if (prior != null) {
            if (depth + prior.height() - 1 > MAX_DEPTH) throw new IllegalArgumentException("Expression DAG path exceeds depth budget");
            return prior;
        }
        Bound bound = switch (expression) {
            case DensityExpression.Constant ignored -> new Bound(1, 1);
            case DensityExpression.Coordinate ignored -> new Bound(1, 1);
            case DensityExpression.Add n -> pair(visit(n.left(), depth + 1, costs), visit(n.right(), depth + 1, costs));
            case DensityExpression.Multiply n -> pair(visit(n.left(), depth + 1, costs), visit(n.right(), depth + 1, costs));
            case DensityExpression.RangeChoice n -> {
                Bound input = visit(n.input(), depth + 1, costs), in = visit(n.whenIn(), depth + 1, costs), out = visit(n.whenOut(), depth + 1, costs);
                yield new Bound(1 + input.cost() + Math.max(in.cost(), out.cost()), 1 + Math.max(input.height(), Math.max(in.height(), out.height())));
            }
            case DensityExpression.Interpolated n -> {
                Bound child = visit(n.child(), depth + 1, costs);
                yield new Bound(32 + 8 * child.cost(), 1 + child.height());
            }
        };
        if (bound.cost() > MAX_SAMPLE_OPERATIONS) throw new IllegalArgumentException("Expanded sample exceeds operation budget");
        costs.put(expression, bound);
        if (costs.size() > MAX_NODES) throw new IllegalArgumentException("Expression exceeds " + MAX_NODES + " nodes");
        return bound;
    }

    private static Bound pair(Bound left, Bound right) {
        return new Bound(1 + left.cost() + right.cost(), 1 + Math.max(left.height(), right.height()));
    }
}
