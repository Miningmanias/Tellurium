// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

import dev.tellurium.semantic.CellGeometry;
import dev.tellurium.semantic.DensityExpression;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SmokeWorkBudgetTest {
    @Test void normalRangeDiagnosticRejectsSubnormalOracleBeforeNativeUse() {
        var expression = new DensityExpression.Constant(Double.MIN_VALUE);
        var points = java.util.List.of(new dev.tellurium.semantic.SamplePoint(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new VulkanSmokeRunner().run(expression, points,
                new double[]{Double.MIN_VALUE}, Fp64Profile.NORMAL_RANGE_DIAGNOSTIC));
    }
    @Test void partialWorkgroupsAndByteSizesAreExact() {
        var config = SmokeConfig.of(65);
        assertEquals(2, config.workgroups());
        assertEquals(1040, config.pointBytes());
        assertEquals(520, config.valueBytes());
    }
    @Test void rejectsUnboundedSizeAndWaits() {
        assertThrows(IllegalArgumentException.class, () -> SmokeConfig.of(0));
        assertThrows(IllegalArgumentException.class, () -> SmokeConfig.of(4097));
        assertThrows(IllegalArgumentException.class, () -> new SmokeConfig(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SmokeConfig(1, 30_000_000_001L));
    }
    @Test void countsExpandedInterpolationAndBothSidesOfSharedOperations() {
        var x = new DensityExpression.Coordinate(DensityExpression.Axis.X);
        var expression = new DensityExpression.Interpolated(new DensityExpression.Add(x, x), new CellGeometry(4, 8));
        assertEquals((32 + 8 * 3) * 65, SmokeWorkBudget.validate(expression, SmokeConfig.of(65)));
    }
    @Test void rejectsSmallGraphsWithLargeExpandedWorkBeforeNativeUse() {
        DensityExpression expression = new DensityExpression.Constant(1);
        for (int i = 0; i < 4; i++) expression = new DensityExpression.Interpolated(expression, new CellGeometry(4, 8));
        DensityExpression huge = expression;
        assertThrows(IllegalArgumentException.class, () -> SmokeWorkBudget.validate(huge, SmokeConfig.of(4096)));
        assertTrue(SmokeWorkBudget.validate(expression, SmokeConfig.of(65)) < SmokeWorkBudget.MAX_DISPATCH_OPERATIONS);
    }
}
