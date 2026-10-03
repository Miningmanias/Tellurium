// SPDX-License-Identifier: MIT
package dev.worldgennext.oracle;

import dev.worldgennext.runtime.vulkan.DeviceCapabilities;
import dev.worldgennext.runtime.vulkan.GpuSmokeResult;
import dev.worldgennext.semantic.DensityExpression;
import dev.worldgennext.semantic.SamplePoint;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReplayRunnerTest {
    @Test void diagnosticCorpusIsSeparateAndCannotPromoteALimitedNativeProfile() {
        var normal = SyntheticCorpus.normalRangeFixtures();
        assertEquals(7, normal.size());
        assertEquals(917, normal.stream().mapToInt(f -> f.points().size()).sum());
        assertEquals(8, SyntheticCorpus.fixtures().size());
        assertNotEquals(SyntheticCorpus.ID, SyntheticCorpus.NORMAL_RANGE_ID);
        assertTrue(ReplayRunner.cpu(normal).passed());
        var limited = ReplayRunner.gpu(List.of(constant(1)), (f, expected) -> new GpuSmokeResult(gpu(), 2, 2, 0,
                "shader", "spirv", dev.worldgennext.runtime.vulkan.Fp64Profile.NORMAL_RANGE_DIAGNOSTIC));
        assertFalse(limited.passed());
        assertEquals(0, limited.comparedSamples());
        assertTrue(limited.fixtures().getFirst().error().contains("profile"));
    }
    private static ReplayFixture constant(double value) {
        return new ReplayFixture("fixture", new DensityExpression.Constant(value), List.of(new SamplePoint(-1, 0, 1), new SamplePoint(4, -8, 16)));
    }
    private static DeviceCapabilities gpu() { return new DeviceCapabilities("fixture-gpu", (1 << 22) | (2 << 12), 1, 2, 0, true, true, true, true); }

    @Test void seededCorpusHasCompleteIndependentCpuComparison() {
        var report = ReplayRunner.cpu(SyntheticCorpus.fixtures());
        assertTrue(report.passed());
        assertEquals(8, report.expectedFixtures());
        assertEquals(1048, report.comparedSamples());
        assertEquals(0, report.mismatches());
        assertEquals(ReplayReport.Backend.CPU, report.backend());
        for (var result : report.fixtures()) assertEquals(result.evidence().get("expectedValuesSha256"), result.evidence().get("actualValuesSha256"));
    }
    @Test void detectsOneBitSignDifferenceRatherThanNumericEquality() {
        var report = ReplayRunner.cpu(List.of(constant(-0.0)), ignored -> point -> 0.0);
        assertFalse(report.passed());
        assertEquals(2, report.comparedSamples());
        assertEquals(2, report.mismatches());
    }
    @Test void nonfiniteCandidateCannotPass() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            var report = ReplayRunner.cpu(List.of(constant(1)), ignored -> point -> bad);
            assertFalse(report.passed());
            assertEquals(2, report.mismatches());
        }
    }
    @Test void exceptionKeepsActualPartialCoverageAndFailure() {
        var count = new AtomicInteger();
        var report = ReplayRunner.cpu(List.of(constant(1)), ignored -> point -> {
            if (count.incrementAndGet() == 2) throw new IllegalStateException("injected evaluation failure");
            return 1;
        });
        assertFalse(report.passed());
        assertEquals(1, report.comparedSamples());
        assertEquals(0, report.mismatches());
        assertEquals(1, report.failedFixtures());
        assertTrue(report.fixtures().getFirst().error().contains("injected evaluation failure"));
    }
    @Test void missingOrDuplicateCoverageCannotPass() {
        assertThrows(IllegalArgumentException.class, () -> ReplayRunner.cpu(List.of()));
        assertThrows(IllegalArgumentException.class, () -> ReplayRunner.cpu(List.of(constant(1), constant(1))));
        assertThrows(IllegalArgumentException.class, () -> new ReplayReport(ReplayReport.Backend.CPU, 0, 0, List.of()));
        var full = new FixtureResult("one", 2, 2, 0, "", Map.of());
        assertFalse(new ReplayReport(ReplayReport.Backend.CPU, 2, 4, List.of(full)).passed());
        assertFalse(new ReplayReport(ReplayReport.Backend.CPU, 1, 3, List.of(full)).passed());
        assertThrows(IllegalArgumentException.class, () -> new ReplayReport(ReplayReport.Backend.CPU, 2, 4, List.of(full, full)));
        assertThrows(IllegalArgumentException.class, () -> new FixtureResult("bad", 1, 2, 0, "", Map.of()));
    }
    @Test void gpuFailureStopsSubmissionAndNeverFallsBackToCpu() {
        var calls = new AtomicInteger();
        var report = ReplayRunner.gpu(SyntheticCorpus.fixtures(), (fixture, expected) -> {
            calls.incrementAndGet();
            throw new UnsatisfiedLinkError("injected missing GPU runtime");
        });
        assertFalse(report.passed());
        assertEquals(1, calls.get());
        assertEquals(0, report.comparedSamples());
        assertEquals(ReplayReport.Backend.VULKAN, report.backend());
        assertEquals(1, report.fixtures().size());
        assertEquals(8, report.expectedFixtures());
    }
    @Test void gpuAdapterRejectsMismatchAndIncompleteNativeResult() {
        var fixture = constant(1);
        var mismatch = ReplayRunner.gpu(List.of(fixture), (f, expected) -> new GpuSmokeResult(gpu(), 2, 2, 1, "shader", "spirv"));
        assertFalse(mismatch.passed());
        assertEquals(1, mismatch.mismatches());
        var partial = ReplayRunner.gpu(List.of(fixture), (f, expected) -> new GpuSmokeResult(gpu(), 2, 1, 0, "shader", "spirv"));
        assertFalse(partial.passed());
        assertEquals(1, partial.comparedSamples());
        var wrongSubmission = ReplayRunner.gpu(List.of(fixture), (f, expected) -> new GpuSmokeResult(gpu(), 1, 1, 0, "shader", "spirv"));
        assertFalse(wrongSubmission.passed());
        assertEquals(0, wrongSubmission.comparedSamples());
    }
    @Test void fixtureOwnsInputAndHashesOrderAndSignedBits() {
        var points = new java.util.ArrayList<>(List.of(new SamplePoint(1, 2, 3)));
        var fixture = new ReplayFixture("fixture", new DensityExpression.Constant(1), points);
        points.clear();
        assertEquals(1, fixture.points().size());
        assertThrows(UnsupportedOperationException.class, () -> fixture.points().clear());
        assertNotEquals(ReplayFixture.valuesHash(new double[]{-0.0}), ReplayFixture.valuesHash(new double[]{0.0}));
        assertNotEquals(constant(1).expressionHash(), constant(2).expressionHash());
    }
}
