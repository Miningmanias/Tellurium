// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan;

import dev.worldgennext.semantic.*;
import org.junit.jupiter.api.Test;
import static dev.worldgennext.semantic.DensityExpression.*;
import static org.junit.jupiter.api.Assertions.*;

/** Source-contract tests. Shaderc compilation/native execution are runtime-vulkan gates. */
class GlslCompilerTest {
    private final GlslCompiler compiler = new GlslCompiler();
    private static final Coordinate X = new Coordinate(Axis.X);

    @Test void shaderHasPinnedNativeAbiAndGuardsTailBeforeInputRead() {
        String shader = compiler.emit(X);
        assertTrue(shader.startsWith("#version 450\n"));
        assertTrue(shader.contains("local_size_x = 64"));
        assertTrue(shader.contains("binding = 0) readonly buffer InputPoints { ivec4 points[]; }"));
        assertTrue(shader.contains("binding = 1) writeonly buffer OutputValues { double values[]; }"));
        assertTrue(shader.contains("layout(push_constant) uniform Dispatch { uint sampleCount; }"));
        assertTrue(shader.indexOf("if (index >= dispatch.sampleCount) return;") < shader.indexOf("points[index].xyz"));
    }

    @Test void branchCallsAreInsideControlRegionsAndNotPrecomputed() {
        DensityExpression overflow = new Multiply(new Constant(Double.MAX_VALUE), new Constant(2));
        String shader = compiler.emit(new RangeChoice(X, -2, 3, new Constant(7), overflow));
        int selector = shader.lastIndexOf("precise double selector");
        String region = shader.substring(selector, shader.indexOf("void main()", selector));
        assertTrue(region.contains("if (selector >= "));
        assertTrue(region.contains(" && selector < "));
        assertTrue(region.indexOf("if (wg_failed) return 0.0LF;") < region.indexOf("if (selector >= "));
        assertTrue(region.contains("} else {\n        return wg_node_"));
        assertFalse(region.contains("precise double left"));
        assertFalse(region.contains(" ? "));
    }

    @Test void constantsUseBitPatternsForSignedZeroAndSubnormalValues() {
        assertTrue(compiler.emit(new Constant(-0.0)).contains("packDouble2x32(uvec2(0x00000000u, 0x80000000u))"));
        assertTrue(compiler.emit(new Constant(Double.MIN_VALUE)).contains("packDouble2x32(uvec2(0x00000001u, 0x00000000u))"));
        assertTrue(compiler.emit(new Constant(Double.MAX_VALUE)).contains("packDouble2x32(uvec2(0xffffffffu, 0x7fefffffu))"));
    }

    @Test void arithmeticUsesExplicitCheckedPreciseStepsAndNaNFailureSentinel() {
        String shader = compiler.emit(new Interpolated(new Add(X, new Constant(1)), new CellGeometry(4, 8)));
        assertTrue(shader.contains("precise double difference = high - low;"));
        assertTrue(shader.contains("precise double scaled = fraction * difference;"));
        assertTrue(shader.contains("precise double result = low + scaled;"));
        assertTrue(shader.contains("isnan(value) || isinf(value)"));
        assertTrue(shader.contains("wg_failed ? packDouble2x32(uvec2(0u, 0x7ff80000u)) : result"));
        assertFalse(shader.contains("fma("));
        assertFalse(shader.contains("mix("));
    }

    @Test void latticeMathRejectsCornerOverflowAndInterpolatesYThenXThenZ() {
        String shader = compiler.emit(new Interpolated(X, new CellGeometry(4, 3)));
        assertTrue(shader.contains("coordinate - quotient * size"));
        assertTrue(shader.contains("if (remainder < 0) remainder += size;"));
        assertTrue(shader.contains("coordinate < (-2147483647 - 1) + remainder"));
        assertTrue(shader.contains("low > 2147483647 - size"));
        assertTrue(shader.contains("wg_cell_axis(point.y, 3, y0, y1, fy)"));
        assertTrue(shader.contains("y00 = wg_lerp(fy, c000, c010)"));
        assertTrue(shader.contains("xz0 = wg_lerp(fx, y00, y10)"));
        assertTrue(shader.contains("return wg_lerp(fz, xz0, xz1)"));
    }

    @Test void deterministicOutputAndSharedDagFunctionEmission() {
        DensityExpression shared = new Multiply(X, new Constant(2));
        DensityExpression expression = new Add(shared, shared);
        String shader = compiler.emit(expression);
        assertEquals(shader, compiler.emit(expression));
        assertTrue(shader.contains(ExpressionIdentity.hash(expression)));
        assertEquals(4, shader.lines().filter(line -> line.startsWith("double wg_node_")).count());
        assertEquals(1, shader.lines().filter(line -> line.contains("precise double result = left * right;")).count());
    }

    @Test void nullAndTooLargeProgramsFailBeforeEmission() {
        assertThrows(NullPointerException.class, () -> compiler.emit(null));
        DensityExpression expression = X;
        for (int i = 0; i < 6; i++) expression = new Interpolated(expression, new CellGeometry(4, 8));
        DensityExpression expanded = expression;
        assertThrows(IllegalArgumentException.class, () -> compiler.emit(expanded));
    }
}
