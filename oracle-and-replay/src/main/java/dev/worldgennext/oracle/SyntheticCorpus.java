// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle;

import dev.worldgennext.semantic.CellGeometry;
import dev.worldgennext.semantic.DensityExpression;
import dev.worldgennext.semantic.SamplePoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static dev.worldgennext.semantic.DensityExpression.*;

/** Fixed synthetic-v1 identity. Changing these inputs requires a corpus version change. */
public final class SyntheticCorpus {
    public static final String ID = "worldgennext-synthetic-v1";
    public static final String NORMAL_RANGE_ID = "worldgennext-normal-range-diagnostic-v1";
    public static final long POINT_SEED = 0x57474E455854L;
    private SyntheticCorpus() {}

    /** Separate, explicitly smaller corpus; never counted as the strict eight-fixture check. */
    public static List<ReplayFixture> normalRangeFixtures() {
        return fixtures().stream().filter(f -> !f.name().equals("subnormal-addition")).toList();
    }

    public static List<ReplayFixture> fixtures() {
        var points = points();
        var x = new Coordinate(Axis.X);
        var y = new Coordinate(Axis.Y);
        var z = new Coordinate(Axis.Z);
        DensityExpression affine = new Add(x, new Add(new Multiply(y, new Constant(0.125)), new Multiply(z, new Constant(0.03125))));
        DensityExpression nonlinear = new Add(new Multiply(x, x), new Add(new Multiply(y, new Constant(0.1)), new Multiply(z, new Constant(-0.7))));
        DensityExpression interpolated = new Interpolated(nonlinear, new CellGeometry(4, 8));
        return List.of(
                new ReplayFixture("affine", affine, points),
                new ReplayFixture("lazy-overflow", new RangeChoice(new Constant(0), -1, 1, affine,
                        new Multiply(new Constant(Double.MAX_VALUE), new Constant(2))), points),
                new ReplayFixture("range-boundaries", new RangeChoice(x, -4, 4, new Multiply(x, x), new Add(y, new Constant(0.25))), points),
                new ReplayFixture("interpolation-4x8", interpolated, points),
                new ReplayFixture("semantic-boundary", new Add(interpolated, new Multiply(x, y)), points),
                new ReplayFixture("interpolation-8x4", new Interpolated(nonlinear, new CellGeometry(8, 4)), points),
                new ReplayFixture("negative-zero", new Multiply(new Constant(-0.0), new Constant(3)), points),
                new ReplayFixture("subnormal-addition", new Add(new Constant(Double.MIN_VALUE), new Constant(Double.MIN_VALUE)), points));
    }

    private static List<SamplePoint> points() {
        var points = new ArrayList<SamplePoint>();
        int[] boundaries = {-17, -16, -9, -8, -5, -4, -1, 0, 1, 3, 4, 7, 8, 15, 16, 17};
        for (int v : boundaries) points.add(new SamplePoint(v, v - 1, -v));
        Random random = new Random(POINT_SEED);
        while (points.size() < 131) points.add(new SamplePoint(random.nextInt(2049) - 1024, random.nextInt(513) - 256, random.nextInt(2049) - 1024));
        return List.copyOf(points);
    }
}
