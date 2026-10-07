// SPDX-License-Identifier: MIT
package dev.tellurium.semantic;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static dev.tellurium.semantic.DensityExpression.*;
import static org.junit.jupiter.api.Assertions.*;

class SemanticContractTest {
    private final DensityEvaluator interpreter = new ReferenceInterpreter();
    private static final Coordinate X = new Coordinate(Axis.X);
    private static final Coordinate Y = new Coordinate(Axis.Y);
    private static final Coordinate Z = new Coordinate(Axis.Z);

    @Test void coordinateAndOrderedArithmeticHaveIndependentAnalyticAnswer() {
        DensityExpression expression = new Add(new Multiply(new Constant(3), X), new Add(new Multiply(new Constant(-2), Y), Z));
        assertEquals(-22, interpreter.evaluate(expression, new SamplePoint(-7, 4, 7)));
        DensityExpression cancellation = new Add(new Add(new Constant(1e16), new Constant(-1e16)), new Constant(1));
        assertEquals(1, interpreter.evaluate(cancellation, new SamplePoint(0, 0, 0)));
    }

    @Test void interpolationDoesNotMoveAnOuterNonlinearOperation() {
        DensityExpression boundary = new Interpolated(X, new CellGeometry(4, 8));
        DensityExpression outside = new Multiply(boundary, boundary);
        DensityExpression inside = new Interpolated(new Multiply(X, X), new CellGeometry(4, 8));
        SamplePoint point = new SamplePoint(1, 3, 2);
        assertEquals(1, interpreter.evaluate(outside, point));
        assertEquals(4, interpreter.evaluate(inside, point));
    }

    @Test void negativeCoordinatesUseFloorCells() {
        DensityExpression expression = new Interpolated(new Multiply(X, X), new CellGeometry(4, 8));
        assertEquals(4, interpreter.evaluate(expression, new SamplePoint(-1, -1, -1)));
        assertEquals(28, interpreter.evaluate(expression, new SamplePoint(-5, -8, -4)));
        assertEquals(16, interpreter.evaluate(expression, new SamplePoint(-4, 0, 0)));
    }

    @Test void interpolationOrderMatchesIndependentGoldenBits() {
        // Offline IEEE-754 golden; x-then-y-then-z gives ...0002 instead.
        double[] corners = {5e-7, -390000, 300000, 3.4e-8, -500, 20000000000d, -4.3e-8, 1.2000000000000002e-7};
        DensityExpression lookup = cornerLookup(corners);
        double value = interpreter.evaluate(new Interpolated(lookup, new CellGeometry(4, 8)), new SamplePoint(1, 3, 2));
        assertEquals(0x41cbf0acd2d80001L, Double.doubleToRawLongBits(value));
    }

    @Test void nestedBoundariesEvaluateTheirOwnChildCoordinates() {
        DensityExpression inner = new Interpolated(new Multiply(X, X), new CellGeometry(4, 8));
        DensityExpression outer = new Interpolated(inner, new CellGeometry(8, 4));
        assertEquals(8, interpreter.evaluate(outer, new SamplePoint(1, -1, 2)));
        assertEquals(8, interpreter.evaluate(outer, new SamplePoint(-1, -1, 2)));
    }

    @Test void rangeIsLazyLowerInclusiveAndUpperExclusive() {
        DensityExpression overflow = new Multiply(new Constant(Double.MAX_VALUE), new Constant(2));
        DensityExpression range = new RangeChoice(X, -2, 3, new Constant(7), overflow);
        assertEquals(7, interpreter.evaluate(range, new SamplePoint(-2, 0, 0)));
        assertEquals(7, interpreter.evaluate(range, new SamplePoint(2, 0, 0)));
        assertThrows(ArithmeticException.class, () -> interpreter.evaluate(range, new SamplePoint(3, 0, 0)));
        assertThrows(ArithmeticException.class, () -> interpreter.evaluate(range, new SamplePoint(-3, 0, 0)));
        assertEquals(11, interpreter.evaluate(new RangeChoice(X, 2, 3, overflow, new Constant(11)), new SamplePoint(0, 0, 0)));
    }

    @Test void invalidSelectorCannotBeMaskedByEitherFiniteBranch() {
        DensityExpression overflow = new Multiply(new Constant(Double.MAX_VALUE), new Constant(2));
        DensityExpression range = new RangeChoice(overflow, -1, 1, new Constant(7), new Constant(11));
        assertThrows(ArithmeticException.class, () -> interpreter.evaluate(range, new SamplePoint(0, 0, 0)));
    }

    @Test void signedZeroAndSubnormalBitsArePreserved() {
        SamplePoint point = new SamplePoint(0, 0, 0);
        assertEquals(Long.MIN_VALUE, bits(new Constant(-0.0), point));
        assertEquals(Long.MIN_VALUE, bits(new Multiply(new Constant(-0.0), new Constant(2)), point));
        assertEquals(Long.MIN_VALUE, bits(new Add(new Constant(-0.0), new Constant(-0.0)), point));
        assertEquals(1L, bits(new Constant(Double.MIN_VALUE), point));
        assertEquals(0L, bits(new Multiply(new Constant(Double.MIN_VALUE), new Constant(0.5)), point));
        // Do not fold a constant boundary: -0 + (+0) in ordered lerp is +0.
        assertEquals(0L, bits(new Interpolated(new Constant(-0.0), new CellGeometry(4, 8)), point));
    }

    @Test void finiteInputsCanStillHaveRejectedNonfiniteIntermediates() {
        DensityExpression oppositeCorners = new RangeChoice(X, 0, 4, new Constant(-Double.MAX_VALUE), new Constant(Double.MAX_VALUE));
        DensityExpression boundary = new Interpolated(oppositeCorners, new CellGeometry(4, 8));
        // Even t=0 must not erase the overflowing difference in canonical lerp.
        assertThrows(ArithmeticException.class, () -> interpreter.evaluate(boundary, new SamplePoint(0, 0, 0)));
        assertThrows(ArithmeticException.class, () -> interpreter.evaluate(new Add(new Constant(Double.MAX_VALUE), new Constant(Double.MAX_VALUE)), new SamplePoint(0, 0, 0)));
    }

    @Test void unrepresentableLatticeCornersAreRejectedWithoutWrapping() {
        DensityExpression boundary = new Interpolated(X, new CellGeometry(4, 3));
        assertThrows(ArithmeticException.class, () -> interpreter.evaluate(boundary, new SamplePoint(Integer.MAX_VALUE, 0, 0)));
        assertThrows(ArithmeticException.class, () -> interpreter.evaluate(boundary, new SamplePoint(0, Integer.MIN_VALUE, 0)));
        assertEquals((double) Integer.MAX_VALUE, interpreter.evaluate(X, new SamplePoint(Integer.MAX_VALUE, 0, 0)));
        assertEquals((double) Integer.MIN_VALUE, interpreter.evaluate(new Interpolated(X, new CellGeometry(4, 8)), new SamplePoint(Integer.MIN_VALUE, 0, 0)));
    }

    @Test void immutableNodesValidateNullsBoundsAndGeometry() {
        assertThrows(IllegalArgumentException.class, () -> new Constant(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Constant(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new RangeChoice(X, 2, 2, X, X));
        assertThrows(IllegalArgumentException.class, () -> new RangeChoice(X, Double.NEGATIVE_INFINITY, 2, X, X));
        assertThrows(NullPointerException.class, () -> new Coordinate(null));
        assertThrows(NullPointerException.class, () -> new Add(X, null));
        assertThrows(NullPointerException.class, () -> new Interpolated(null, new CellGeometry(4, 8)));
        assertThrows(IllegalArgumentException.class, () -> new CellGeometry(3, 8));
        assertThrows(IllegalArgumentException.class, () -> new CellGeometry(4, 0));
    }

    @Test void validationBoundsDeepAndExpandedProgramsAndHandlesSharedDag() {
        DensityExpression deep = X;
        for (int i = 0; i < ExpressionValidation.MAX_DEPTH; i++) deep = new Add(deep, new Constant(0));
        DensityExpression finalDeep = deep;
        assertThrows(IllegalArgumentException.class, () -> ExpressionValidation.validate(finalDeep));
        DensityExpression expanded = X;
        for (int i = 0; i < 6; i++) expanded = new Interpolated(expanded, new CellGeometry(4, 8));
        DensityExpression finalExpanded = expanded;
        assertThrows(IllegalArgumentException.class, () -> ExpressionValidation.validate(finalExpanded));
        DensityExpression dag = X;
        for (int i = 0; i < 90; i++) dag = new RangeChoice(X, -1, 1, dag, dag);
        DensityExpression finalDag = dag;
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> ExpressionValidation.validate(finalDag));
    }

    @Test void validationCannotHideDeepPathsBehindSharedNodesOrExceedDistinctNodeBudget() {
        DensityExpression shared = X;
        for (int i = 0; i < 90; i++) shared = new Add(shared, new Constant(0));
        DensityExpression laterPath = shared;
        for (int i = 0; i < 40; i++) laterPath = new Add(laterPath, new Constant(0));
        // Visit and memoize shared through the shallow left path before revisiting it deeply.
        DensityExpression deepDag = new Add(shared, laterPath);
        assertThrows(IllegalArgumentException.class, () -> ExpressionValidation.validate(deepDag));

        // These are shallow finite trees with distinct identities, not repeated references.
        DensityExpression withinBudget = balancedTree(11); // 4095 nodes.
        assertDoesNotThrow(() -> ExpressionValidation.validate(withinBudget));
        DensityExpression tooManyNodes = balancedTree(12); // 8191 nodes; cost stays below 65536.
        assertThrows(IllegalArgumentException.class, () -> ExpressionValidation.validate(tooManyNodes));
    }

    @Test void programHashIsStructuralOrderedAndPrecisionSensitive() {
        assertEquals(ExpressionIdentity.hash(new Add(X, new Constant(1))), ExpressionIdentity.hash(new Add(new Coordinate(Axis.X), new Constant(1))));
        assertNotEquals(ExpressionIdentity.hash(new Add(X, Y)), ExpressionIdentity.hash(new Add(Y, X)));
        assertNotEquals(ExpressionIdentity.hash(new Constant(-0.0)), ExpressionIdentity.hash(new Constant(0.0)));
        assertNotEquals(ExpressionIdentity.hash(new Interpolated(X, new CellGeometry(4, 8))), ExpressionIdentity.hash(new Interpolated(X, new CellGeometry(8, 4))));
        assertTrue(ExpressionIdentity.hash(X).matches("[0-9a-f]{64}"));
    }

    private long bits(DensityExpression expression, SamplePoint point) { return Double.doubleToRawLongBits(interpreter.evaluate(expression, point)); }

    private static DensityExpression balancedTree(int depth) {
        return depth == 0 ? new Constant(1) : new Add(balancedTree(depth - 1), balancedTree(depth - 1));
    }

    private static DensityExpression cornerLookup(double[] c) {
        DensityExpression x0z0 = new RangeChoice(Y, 0, 8, new Constant(c[0]), new Constant(c[1]));
        DensityExpression x0z1 = new RangeChoice(Y, 0, 8, new Constant(c[2]), new Constant(c[3]));
        DensityExpression x1z0 = new RangeChoice(Y, 0, 8, new Constant(c[4]), new Constant(c[5]));
        DensityExpression x1z1 = new RangeChoice(Y, 0, 8, new Constant(c[6]), new Constant(c[7]));
        return new RangeChoice(X, 0, 4, new RangeChoice(Z, 0, 4, x0z0, x0z1), new RangeChoice(Z, 0, 4, x1z0, x1z1));
    }
}
