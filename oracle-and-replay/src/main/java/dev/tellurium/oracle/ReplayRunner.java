// SPDX-License-Identifier: MIT
package dev.tellurium.oracle;

import dev.tellurium.compiler.jvm.CpuCompiler;
import dev.tellurium.runtime.vulkan.GpuSmokeResult;
import dev.tellurium.runtime.vulkan.Fp64Profile;
import dev.tellurium.runtime.vulkan.VulkanSmokeRunner;
import dev.tellurium.semantic.CompiledDensity;
import dev.tellurium.semantic.DensityExpression;
import dev.tellurium.semantic.ReferenceInterpreter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Differential execution. The reference interpreter never calls either compiler. */
public final class ReplayRunner {
    private ReplayRunner() {}

    public static ReplayReport cpu(List<ReplayFixture> fixtures) {
        var compiler = new CpuCompiler();
        return cpu(fixtures, compiler::compile);
    }

    static ReplayReport cpu(List<ReplayFixture> fixtures, Function<DensityExpression, CompiledDensity> compile) {
        List<ReplayFixture> input = validate(fixtures);
        var results = new ArrayList<FixtureResult>();
        var reference = new ReferenceInterpreter();
        for (var fixture : input) {
            int compared = 0, mismatches = 0;
            var evidence = identity(fixture);
            String error = "";
            try {
                var compiled = compile.apply(fixture.expression());
                double[] expected = new double[fixture.points().size()], actual = new double[expected.length];
                for (int i = 0; i < expected.length; i++) {
                    expected[i] = reference.evaluate(fixture.expression(), fixture.points().get(i));
                    actual[i] = compiled.sample(fixture.points().get(i));
                    if (!Double.isFinite(expected[i]) || !Double.isFinite(actual[i])
                            || Double.doubleToRawLongBits(expected[i]) != Double.doubleToRawLongBits(actual[i])) mismatches++;
                    compared++;
                }
                evidence.put("expectedValuesSha256", ReplayFixture.valuesHash(expected));
                evidence.put("actualValuesSha256", ReplayFixture.valuesHash(actual));
            } catch (RuntimeException failure) { error = describe(failure); }
            results.add(new FixtureResult(fixture.name(), fixture.points().size(), compared, mismatches, error, evidence));
        }
        return report(ReplayReport.Backend.CPU, input, results);
    }

    public static ReplayReport gpu(List<ReplayFixture> fixtures) {
        return gpu(fixtures, (fixture, expected) -> new VulkanSmokeRunner().run(fixture.expression(), fixture.points(), expected));
    }

    public static ReplayReport gpuNormalRangeDiagnostic() {
        return gpu(SyntheticCorpus.normalRangeFixtures(), (fixture, expected) -> new VulkanSmokeRunner()
                .run(fixture.expression(), fixture.points(), expected, Fp64Profile.NORMAL_RANGE_DIAGNOSTIC),
                ReplayReport.Backend.VULKAN_NORMAL_RANGE_DIAGNOSTIC);
    }

    static ReplayReport gpu(List<ReplayFixture> fixtures, BiFunction<ReplayFixture, double[], GpuSmokeResult> dispatch) {
        return gpu(fixtures, dispatch, ReplayReport.Backend.VULKAN);
    }

    private static ReplayReport gpu(List<ReplayFixture> fixtures, BiFunction<ReplayFixture, double[], GpuSmokeResult> dispatch, ReplayReport.Backend backend) {
        List<ReplayFixture> input = validate(fixtures);
        var results = new ArrayList<FixtureResult>();
        var reference = new ReferenceInterpreter();
        for (var fixture : input) {
            var evidence = identity(fixture);
            int compared = 0, mismatches = 0;
            String error = "";
            try {
                double[] expected = new double[fixture.points().size()];
                for (int i = 0; i < expected.length; i++) expected[i] = reference.evaluate(fixture.expression(), fixture.points().get(i));
                evidence.put("expectedValuesSha256", ReplayFixture.valuesHash(expected));
                GpuSmokeResult result = dispatch.apply(fixture, expected);
                Fp64Profile requiredProfile = backend == ReplayReport.Backend.VULKAN
                        ? Fp64Profile.STRICT : Fp64Profile.NORMAL_RANGE_DIAGNOSTIC;
                if (result.profile() != requiredProfile) throw new IllegalStateException("Native profile differs from requested qualification");
                if (result.submittedSamples() != expected.length) throw new IllegalStateException("Native submission count differs from fixture");
                compared = result.comparedSamples();
                mismatches = result.mismatches();
                evidence.put("device", result.device().name());
                evidence.put("deviceType", Integer.toString(result.device().deviceType()));
                evidence.put("apiVersion", Integer.toUnsignedString(result.device().apiVersion()));
                evidence.put("driverVersion", Integer.toUnsignedString(result.device().driverVersion()));
                evidence.put("fp64Profile", result.profile().name());
                evidence.put("denormPreserve64Advertised", Boolean.toString(result.device().preserveDenorm64()));
                evidence.put("shaderSha256", result.shaderSha256());
                evidence.put("spirvSha256", result.spirvSha256());
                if (!result.passed()) error = "GPU-required result failed capability, coverage or raw-bit comparison";
            } catch (RuntimeException | LinkageError failure) { error = describe(failure); }
            results.add(new FixtureResult(fixture.name(), fixture.points().size(), compared, mismatches, error, evidence));
            // A native failure can leave device work quarantined; stop submitting and let the dedicated CLI exit.
            if (!error.isEmpty()) break;
        }
        return report(backend, input, results);
    }

    private static List<ReplayFixture> validate(List<ReplayFixture> fixtures) {
        var input = List.copyOf(fixtures);
        if (input.isEmpty()) throw new IllegalArgumentException("No fixtures to compare");
        if (input.stream().map(ReplayFixture::name).distinct().count() != input.size()) throw new IllegalArgumentException("Duplicate input fixture names");
        return input;
    }
    private static ReplayReport report(ReplayReport.Backend backend, List<ReplayFixture> input, List<FixtureResult> results) {
        return new ReplayReport(backend, input.size(), Math.toIntExact(input.stream().mapToLong(f -> f.points().size()).sum()), results);
    }
    private static LinkedHashMap<String, String> identity(ReplayFixture fixture) {
        var evidence = new LinkedHashMap<String, String>();
        evidence.put("expressionSha256", fixture.expressionHash());
        evidence.put("pointsSha256", fixture.pointHash());
        return evidence;
    }
    private static String describe(Throwable failure) { return failure.getClass().getSimpleName() + ": " + failure.getMessage(); }
}
