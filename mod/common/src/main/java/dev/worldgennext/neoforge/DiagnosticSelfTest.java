// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge;

import dev.worldgennext.compiler.jvm.CpuCompiler;
import dev.worldgennext.material.SectionCodec;
import dev.worldgennext.material.SectionData;
import dev.worldgennext.semantic.DensityExpression;
import dev.worldgennext.semantic.ReferenceInterpreter;
import dev.worldgennext.semantic.SamplePoint;

/** Pure diagnostic used by commands and real loader smoke; fixture IDs are not registry IDs. */
public final class DiagnosticSelfTest {
    private DiagnosticSelfTest() {}
    public record Result(int densityCompared, int materialCompared, int mismatches) {
        public boolean passed() { return densityCompared == 65 && materialCompared == SectionData.BLOCK_COUNT && mismatches == 0; }
    }
    public static Result run() {
        var expression = new DensityExpression.Add(new DensityExpression.Coordinate(DensityExpression.Axis.X), new DensityExpression.Constant(0.5));
        var compiled = new CpuCompiler().compile(expression);
        var reference = new ReferenceInterpreter();
        int mismatches = 0;
        for (int x = -32; x <= 32; x++) {
            var point = new SamplePoint(x, -1, 17);
            if (Double.doubleToRawLongBits(reference.evaluate(expression, point)) != Double.doubleToRawLongBits(compiled.sample(point))) mismatches++;
        }
        int[] states = new int[SectionData.BLOCK_COUNT];
        for (int i = 0; i < states.length; i++) states[i] = i % 7;
        int[] decoded = SectionCodec.decode(SectionCodec.encode(states));
        for (int i = 0; i < states.length; i++) if (states[i] != decoded[i]) mismatches++;
        return new Result(65, states.length, mismatches);
    }
}
