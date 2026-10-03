// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan;

import dev.worldgennext.compiler.vulkan.worldgen.WorldgenShaderCompiler;
import dev.worldgennext.semantic.program.EvaluationDomain;
import dev.worldgennext.semantic.program.NumericProfile;
import dev.worldgennext.semantic.program.ProgramNode;
import dev.worldgennext.semantic.program.ValueType;
import dev.worldgennext.semantic.program.WorldgenProgram;
import dev.worldgennext.semantic.snapshot.BlendedNoiseParameters;
import dev.worldgennext.semantic.snapshot.NoiseParameters;
import dev.worldgennext.semantic.snapshot.EndIslandParameters;
import dev.worldgennext.semantic.snapshot.BeardifierSnapshot;
import dev.worldgennext.semantic.snapshot.StructureBlendSnapshot;

/**
 * Opt-in shaderc validation for the actual emitted integer-carrier worldgen
 * shaders. The ordinary CPU test task never loads shaderc or a Vulkan driver.
 */
public final class NativeIntegerIeeeCompile {
    private NativeIntegerIeeeCompile() {}

    public static void main(String[] args) {
        var compiler = new WorldgenShaderCompiler();
        var fp32Left = new ProgramNode.Input("x", ValueType.FP32, EvaluationDomain.BLOCK);
        var fp32 = new ProgramNode.Binary("add", ValueType.FP32, EvaluationDomain.BLOCK,
                fp32Left, new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.WORLD));
        var fp64 = new ProgramNode.Binary("multiply", ValueType.FP64, EvaluationDomain.BLOCK,
                new ProgramNode.Constant(ValueType.FP64, 1.5d, EvaluationDomain.WORLD),
                new ProgramNode.Constant(ValueType.FP64, -2.0d, EvaluationDomain.WORLD));
        var fp32Sqrt = new ProgramNode.Unary("sqrt", ValueType.FP32, EvaluationDomain.BLOCK,
                new ProgramNode.Constant(ValueType.FP32, 4.0f, EvaluationDomain.WORLD));
        var fp64Sqrt = new ProgramNode.Unary("sqrt", ValueType.FP64, EvaluationDomain.BLOCK,
                new ProgramNode.Constant(ValueType.FP64, 4.0d, EvaluationDomain.WORLD));
        var capturedNoise = capturedNoise();
        var noiseParameters = capturedNoiseParameters();
        var shift = new ProgramNode.Shift("minecraft:test", noiseParameters, "ZX0", 0.25,
                ValueType.FP64, EvaluationDomain.WORLD);
        var shiftedNoise = new ProgramNode.ShiftedNoise("minecraft:test", noiseParameters, 0.5, 2.0,
                new ProgramNode.Constant(ValueType.FP64, 0.25, EvaluationDomain.WORLD),
                new ProgramNode.Constant(ValueType.FP64, -0.5, EvaluationDomain.WORLD),
                new ProgramNode.Constant(ValueType.FP64, 0.75, EvaluationDomain.WORLD),
                ValueType.FP64, EvaluationDomain.WORLD);
        var interpolation = new ProgramNode.Interpolated(
                new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.LATTICE),
                new dev.worldgennext.semantic.program.InterpolationGeometry(4, 8), ValueType.FP64);
        var spline = new ProgramNode.Spline(new ProgramNode.SplineMultipoint(
                new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.WORLD),
                java.util.List.of(-2.0f, 0.0f, 3.0f),
                java.util.List.of(new ProgramNode.SplineConstant(-1.0f),
                        new ProgramNode.SplineConstant(0.5f), new ProgramNode.SplineConstant(2.0f)),
                java.util.List.of(0.25f, -0.5f, 0.0f)),
                ValueType.FP64, EvaluationDomain.WORLD);
        var endIsland = new ProgramNode.EndIsland(new EndIslandParameters(0.0, 0.0,
                java.util.stream.IntStream.range(0, 256).boxed().toList()),
                ValueType.FP64, EvaluationDomain.WORLD);
        var weirdType1 = new ProgramNode.WeirdScaledSampler(
                new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.WORLD),
                noiseParameters, "TYPE1", ValueType.FP64, EvaluationDomain.WORLD);
        var weirdType2 = new ProgramNode.WeirdScaledSampler(
                new ProgramNode.Input("x", ValueType.FP32, EvaluationDomain.WORLD),
                noiseParameters, "TYPE2", ValueType.FP32, EvaluationDomain.WORLD);
        var blended = new ProgramNode.BlendedNoise(capturedBlendedNoiseParameters(), ValueType.FP64,
                EvaluationDomain.WORLD);
        var blendDensity = new ProgramNode.BlendDensity(
                new ProgramNode.Constant(ValueType.FP64, 5.0, EvaluationDomain.BLOCK),
                ValueType.FP64, EvaluationDomain.BLOCK);
        var blendAlpha = new ProgramNode.BlendAlpha(ValueType.FP64, EvaluationDomain.BLOCK);
        var blendOffset = new ProgramNode.BlendOffset(ValueType.FP64, EvaluationDomain.BLOCK);
        var blendSnapshot = new StructureBlendSnapshot("compile-fixture", java.util.Map.of(), java.util.Map.of(),
                BeardifierSnapshot.empty(),
                java.util.List.of(new StructureBlendSnapshot.DensitySample(2, 0, 0, 3.0)),
                java.util.List.of(new StructureBlendSnapshot.DirectDensitySample(-1, 0, 4, 0, 4, 1.0)),
                java.util.List.of(new StructureBlendSnapshot.HeightSample(0, 0, 200.0)),
                java.util.List.of(new StructureBlendSnapshot.DirectHeightSample(0, 0, 0, 0, 120.0)),
                true);
        var shaderc = new ShadercCompiler();
        int totalBytes = 0;
        ProgramNode[] roots = {fp32, fp64, fp32Sqrt, fp64Sqrt, capturedNoise, shift, shiftedNoise,
                interpolation, spline, endIsland, weirdType1, weirdType2, blended, blendDensity, blendAlpha, blendOffset};
        for (ProgramNode root : roots) {
            var shader = root == blendDensity || root == blendAlpha || root == blendOffset
                    ? compiler.emit(WorldgenProgram.builder().root("finalDensity", root).build(),
                    NumericProfile.GPU_IEEE_BITS, 64, blendSnapshot)
                    : compiler.emit(WorldgenProgram.builder().root("finalDensity", root).build(),
                    NumericProfile.GPU_IEEE_BITS, 64);
            byte[] spirv = shaderc.compile(shader.source());
            if (spirv.length == 0 || spirv.length % 4 != 0) throw new IllegalStateException("Invalid SPIR-V result");
            var inspection = new dev.worldgennext.compiler.vulkan.worldgen.SpirvNumericContract()
                    .inspectSpirv(spirv, NumericProfile.GPU_IEEE_BITS);
            if (!inspection.valid()) throw new IllegalStateException("Compiled module violates integer profile: " + inspection.violations());
            totalBytes += spirv.length;
            System.out.println("compiled profile=" + shader.profile() + " root=" + root.type() + " bytes=" + spirv.length);
        }
        System.out.println("nativeIntegerIeee PASS shaders=" + roots.length + " spirvBytes=" + totalBytes);
    }

    private static ProgramNode capturedNoise() {
        return new ProgramNode.Noise("minecraft:test", capturedNoiseParameters(), 0.5, 2.0,
                ValueType.FP64, EvaluationDomain.WORLD);
    }

    private static NoiseParameters capturedNoiseParameters() {
        var permutation = java.util.stream.IntStream.range(0, 256).boxed().toList();
        var level = new NoiseParameters.ImprovedNoiseSnapshot(0.0, 0.0, 0.0, permutation);
        var perlin = new NoiseParameters.PerlinNoiseSnapshot(0, java.util.List.of(1.0), java.util.List.of(level));
        var captured = new NoiseParameters.CapturedNoise(1234L, 1.0, perlin, perlin);
        return new NoiseParameters("minecraft:test", 0, java.util.List.of(1.0), 0L, captured);
    }

    private static BlendedNoiseParameters capturedBlendedNoiseParameters() {
        var permutation = java.util.stream.IntStream.range(0, 256).boxed().toList();
        var level = new NoiseParameters.ImprovedNoiseSnapshot(0.0, 0.0, 0.0, permutation);
        var amplitudes = java.util.Collections.nCopies(16, 1.0);
        var levels = java.util.Collections.nCopies(16, level);
        var perlin = new NoiseParameters.PerlinNoiseSnapshot(15, amplitudes, levels);
        return new BlendedNoiseParameters(1234L, perlin, perlin, perlin,
                1.0, 1.0, 8.555150000000001, 4.277575000000001, 2.0);
    }
}
