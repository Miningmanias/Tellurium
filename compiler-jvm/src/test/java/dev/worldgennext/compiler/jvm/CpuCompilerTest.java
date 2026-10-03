// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.jvm;

import dev.worldgennext.semantic.*;
import org.junit.jupiter.api.Test;
import java.util.Random;
import java.util.stream.IntStream;
import static dev.worldgennext.semantic.DensityExpression.*;
import static org.junit.jupiter.api.Assertions.*;

class CpuCompilerTest {
    private static final Coordinate X = new Coordinate(Axis.X);
    private static final Coordinate Y = new Coordinate(Axis.Y);
    private static final Coordinate Z = new Coordinate(Axis.Z);
    private final CpuCompiler compiler = new CpuCompiler();
    private final DensityEvaluator reference = new ReferenceInterpreter();

    @Test void compiledAffineProgramMatchesAnalyticOracleAcrossNegativeSpace() {
        DensityExpression affine = new Add(new Multiply(X, new Constant(2)), new Add(new Multiply(Y, new Constant(-3)), Z));
        CompiledDensity program = compiler.compile(new Interpolated(affine, new CellGeometry(4, 8)));
        for (int x = -17; x <= 17; x++) for (int y = -9; y <= 9; y++) {
            SamplePoint p = new SamplePoint(x, y, 5 - x);
            assertEquals(2.0 * x - 3.0 * y + 5 - x, program.sample(p));
        }
    }

    @Test void outerSquareAndInnerSquareRemainDistinctAcrossCellEdges() {
        DensityExpression boundary = new Interpolated(X, new CellGeometry(4, 8));
        CompiledDensity outer = compiler.compile(new Multiply(boundary, boundary));
        CompiledDensity inner = compiler.compile(new Interpolated(new Multiply(X, X), new CellGeometry(4, 8)));
        for (int x = -12; x <= 12; x++) {
            SamplePoint p = new SamplePoint(x, -3, 2);
            int base = Math.floorDiv(x, 4) * 4;
            // Independent straight-line equation for the chord of f(x)=x^2.
            assertEquals((double) x * x, outer.sample(p));
            assertEquals((double) base * base + (x - base) * (2.0 * base + 4), inner.sample(p));
        }
    }

    @Test void lazyBranchNeverExecutesUnselectedOverflowOrBadCorner() {
        DensityExpression overflow = new Multiply(new Constant(Double.MAX_VALUE), new Constant(2));
        CompiledDensity in = compiler.compile(new RangeChoice(X, -2, 3, new Constant(-0.0), overflow));
        assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(in.sample(new SamplePoint(-2, 0, 0))));
        assertThrows(ArithmeticException.class, () -> in.sample(new SamplePoint(3, 0, 0)));
        CompiledDensity out = compiler.compile(new RangeChoice(new Constant(5), 0, 1, new Interpolated(X, new CellGeometry(4, 8)), new Constant(9)));
        assertEquals(9, out.sample(new SamplePoint(Integer.MAX_VALUE, 0, 0)));
    }

    @Test void invalidSelectorCannotSelectAnApparentlyValidOutput() {
        DensityExpression overflow = new Add(new Constant(Double.MAX_VALUE), new Constant(Double.MAX_VALUE));
        CompiledDensity program = compiler.compile(new RangeChoice(overflow, -1, 1, new Constant(7), new Constant(11)));
        assertThrows(ArithmeticException.class, () -> program.sample(new SamplePoint(0, 0, 0)));
    }

    @Test void nestedBoundaryAndAdversarialGoldenMatchWithoutReassociation() {
        double[] values = {5e-7, -390000, 300000, 3.4e-8, -500, 20000000000d, -4.3e-8, 1.2000000000000002e-7};
        DensityExpression[] yz = new DensityExpression[4];
        for (int i = 0; i < 4; i++) yz[i] = new RangeChoice(Y, 0, 8, new Constant(values[2 * i]), new Constant(values[2 * i + 1]));
        DensityExpression lookup = new RangeChoice(X, 0, 4, new RangeChoice(Z, 0, 4, yz[0], yz[1]), new RangeChoice(Z, 0, 4, yz[2], yz[3]));
        assertEquals(0x41cbf0acd2d80001L, Double.doubleToRawLongBits(compiler.compile(new Interpolated(lookup, new CellGeometry(4, 8))).sample(new SamplePoint(1, 3, 2))));
        DensityExpression nested = new Interpolated(new Interpolated(new Multiply(X, X), new CellGeometry(4, 8)), new CellGeometry(8, 4));
        assertEquals(8, compiler.compile(nested).sample(new SamplePoint(-1, -2, -3)));
    }

    @Test void seededProgramsMatchIndependentInterpreterBitForBit() {
        Random random = new Random(0x6e657874L);
        for (int i = 0; i < 80; i++) {
            DensityExpression expression = randomExpression(random, 4);
            CompiledDensity program = compiler.compile(expression);
            for (int j = 0; j < 40; j++) {
                SamplePoint p = new SamplePoint(random.nextInt(129) - 64, random.nextInt(81) - 40, random.nextInt(129) - 64);
                assertEquals(Double.doubleToRawLongBits(reference.evaluate(expression, p)), Double.doubleToRawLongBits(program.sample(p)), "program=" + i + ", sample=" + j);
            }
        }
    }

    @Test void finiteSubsetFailuresAreConsistentAndDoNotWrapCoordinates() {
        DensityExpression expression = new Interpolated(Y, new CellGeometry(4, 3));
        CompiledDensity program = compiler.compile(expression);
        assertThrows(ArithmeticException.class, () -> program.sample(new SamplePoint(0, Integer.MIN_VALUE, 0)));
        assertThrows(ArithmeticException.class, () -> program.sample(new SamplePoint(Integer.MAX_VALUE, 0, 0)));
        DensityExpression overflow = new Add(new Constant(Double.MAX_VALUE), new Constant(Double.MAX_VALUE));
        assertThrows(ArithmeticException.class, () -> compiler.compile(overflow).sample(new SamplePoint(0, 0, 0)));
        assertEquals(1L, Double.doubleToRawLongBits(compiler.compile(new Constant(Double.MIN_VALUE)).sample(new SamplePoint(0, 0, 0))));
        assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(compiler.compile(new Multiply(new Constant(-0.0), new Constant(1))).sample(new SamplePoint(0, 0, 0))));
    }

    @Test void compiledProgramIsSafeForConcurrentIndependentSamples() {
        CompiledDensity program = compiler.compile(new Interpolated(new Add(X, new Multiply(Y, new Constant(2))), new CellGeometry(4, 8)));
        IntStream.range(-500, 500).parallel().forEach(i -> assertEquals(3.0 * i, program.sample(new SamplePoint(i, i, -i))));
    }

    @Test void nullAndExcessivelyDeepProgramsAreRejected() {
        assertThrows(NullPointerException.class, () -> compiler.compile(null));
        assertThrows(NullPointerException.class, () -> compiler.compile(X).sample(null));
        DensityExpression deep = X;
        for (int i = 0; i < 130; i++) deep = new Add(deep, new Constant(0));
        DensityExpression finalDeep = deep;
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(finalDeep));
    }

    private static DensityExpression randomExpression(Random random, int depth) {
        if (depth == 0) return switch (random.nextInt(5)) {
            case 0 -> X; case 1 -> Y; case 2 -> Z; case 3 -> new Constant(-0.0); default -> new Constant((random.nextInt(31) - 15) / 8.0);
        };
        return switch (random.nextInt(5)) {
            case 0 -> new Add(randomExpression(random, depth - 1), randomExpression(random, depth - 1));
            case 1 -> new Multiply(randomExpression(random, depth - 1), randomExpression(random, depth - 1));
            case 2 -> new RangeChoice(randomExpression(random, depth - 1), -3, 7, randomExpression(random, depth - 1), randomExpression(random, depth - 1));
            case 3 -> new Interpolated(randomExpression(random, depth - 1), random.nextBoolean() ? new CellGeometry(4, 8) : new CellGeometry(8, 4));
            default -> new Add(randomExpression(random, depth - 1), new Constant(0.125));
        };
    }
}
