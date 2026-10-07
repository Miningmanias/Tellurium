// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

import dev.tellurium.semantic.DensityExpression;
import dev.tellurium.semantic.ExpressionValidation;
import java.util.IdentityHashMap;
import java.util.Objects;

/** Native-independent upper bound on expanded arithmetic across one tiny dispatch. */
public final class SmokeWorkBudget {
    public static final long MAX_DISPATCH_OPERATIONS = 2_000_000;
    private SmokeWorkBudget() {}

    public static long validate(DensityExpression expression, SmokeConfig config) {
        Objects.requireNonNull(config, "config");
        ExpressionValidation.validate(expression);
        long total = Math.multiplyExact(cost(expression, new IdentityHashMap<>()), config.sampleCount());
        if (total > MAX_DISPATCH_OPERATIONS) throw new IllegalArgumentException("Synthetic dispatch exceeds expanded operation budget: " + total);
        return total;
    }

    private static long cost(DensityExpression expression, IdentityHashMap<DensityExpression, Long> cache) {
        Long previous = cache.get(expression);
        if (previous != null) return previous;
        long value = switch (expression) {
            case DensityExpression.Constant ignored -> 1;
            case DensityExpression.Coordinate ignored -> 1;
            case DensityExpression.Add node -> 1 + cost(node.left(), cache) + cost(node.right(), cache);
            case DensityExpression.Multiply node -> 1 + cost(node.left(), cache) + cost(node.right(), cache);
            case DensityExpression.RangeChoice node -> 1 + cost(node.input(), cache) + Math.max(cost(node.whenIn(), cache), cost(node.whenOut(), cache));
            case DensityExpression.Interpolated node -> 32 + 8 * cost(node.child(), cache);
        };
        cache.put(expression, value);
        return value;
    }
}
