// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

import dev.tellurium.compiler.vulkan.worldgen.WorldgenShaderCompiler;
import dev.tellurium.runtime.vulkan.production.VulkanWorldgenExecutor;
import dev.tellurium.semantic.program.EvaluationDomain;
import dev.tellurium.semantic.program.NumericProfile;
import dev.tellurium.semantic.program.ProgramNode;
import dev.tellurium.semantic.program.ValueType;
import dev.tellurium.semantic.program.WorldgenProgram;
import dev.tellurium.semantic.snapshot.PositionalRandomFactorySnapshot;
import dev.tellurium.semantic.snapshot.StructureBlendSnapshot;
import dev.tellurium.semantic.snapshot.NoiseParameters;

/**
 * Opt-in device smoke for the persistent production executor. This is kept
 * separate from the CPU test task and validates a result produced by the
 * emitted worldgen shader, not a Java-side copy.
 */
public final class NativeWorldgenSmoke {
    private NativeWorldgenSmoke() { }

    public static void main(String[] args) {
        var shader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity",
                        new ProgramNode.Binary("add", ValueType.FP32, EvaluationDomain.BLOCK,
                                new ProgramNode.Input("x", ValueType.FP32, EvaluationDomain.BLOCK),
                                new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.WORLD)))
                        .build(),
                NumericProfile.GPU_IEEE_BITS, 64);
        int[] coordinates = {-2, 0, 4, 0, 7, 0, 19, 0, -3, 0, 0, 0};
        try (var executor = new VulkanWorldgenExecutor()) {
            int batchSize = 3;
            var result = executor.executeBatched(new VulkanWorldgenExecutor.Request(shader, coordinates, 17, 23, 99), batchSize);
            int[] expected = {23, 17, 17, 17};
            if (!java.util.Arrays.equals(expected, result.stateIds())) {
                throw new IllegalStateException("Device result mismatch: expected="
                        + java.util.Arrays.toString(expected) + " actual="
                        + java.util.Arrays.toString(result.stateIds()));
            }
            System.out.println("nativeWorldgen DEVICE_PASS device=" + result.device().name()
                    + " elements=" + result.elementCount()
                    + " sliceLimit=" + batchSize
                    + " shaderSha256=" + result.shaderHash()
                    + " spirvSha256=" + result.spirvHash());

            var permutation = java.util.stream.IntStream.range(0, 256).boxed().toList();
            var level = new NoiseParameters.ImprovedNoiseSnapshot(0.0, 0.0, 0.0, permutation);
            var perlin = new NoiseParameters.PerlinNoiseSnapshot(0, java.util.List.of(1.0), java.util.List.of(level));
            var emptyPerlin = new NoiseParameters.PerlinNoiseSnapshot(0, java.util.List.of(0.0),
                    java.util.Collections.singletonList(null));
            var captured = new NoiseParameters.CapturedNoise(1234L, 1.0, perlin, emptyPerlin);
            var noiseParameters = new NoiseParameters("tellurium:native-smoke", 0,
                    java.util.List.of(1.0), 0L, captured);
            var noiseShader = new WorldgenShaderCompiler().emit(
                    WorldgenProgram.builder().root("finalDensity", new ProgramNode.Noise(
                            "tellurium:native-smoke", noiseParameters, 0.5, 2.0,
                            ValueType.FP64, EvaluationDomain.WORLD)).build(),
                    NumericProfile.GPU_IEEE_BITS, 64);
            int[] noiseCoordinates = {1, 2, 3, -4, -5, -6, 0, 0, 0, 7, -2, 9, 16, 16, 16, -17, 13, -11};
            var noiseResult = executor.executeBatched(new VulkanWorldgenExecutor.Request(noiseShader,
                    noiseCoordinates, 17, 23, 99), 2);
            int[] expectedNoise = {17, 23, 23, 23, 23, 23};
            if (!java.util.Arrays.equals(expectedNoise, noiseResult.stateIds())) {
                throw new IllegalStateException("Device captured-noise result mismatch: expected="
                        + java.util.Arrays.toString(expectedNoise) + " actual="
                        + java.util.Arrays.toString(noiseResult.stateIds()));
            }
            System.out.println("nativeWorldgen NOISE_PASS device=" + noiseResult.device().name()
                    + " elements=" + noiseResult.elementCount()
                    + " sliceLimit=2"
                    + " shaderSha256=" + noiseResult.shaderHash()
                    + " spirvSha256=" + noiseResult.spirvHash());

            var aquiferShader = new WorldgenShaderCompiler().emit(
                    WorldgenProgram.builder()
                            // Aquifer.computeSubstance returns null for
                            // positive density.  Use a negative density here
                            // so this fixture actually exercises the captured
                            // status/material path rather than the ordinary
                            // default-block fallback.
                            .root("finalDensity", new ProgramNode.Constant(ValueType.FP32, -1.0f, EvaluationDomain.BLOCK))
                            .root("barrierNoise", new ProgramNode.Constant(ValueType.FP32, 0.0f, EvaluationDomain.WORLD))
                            .build(),
                    NumericProfile.GPU_IEEE_BITS, 64, StructureBlendSnapshot.empty(),
                    new WorldgenShaderCompiler.MaterialOptions(
                            dev.tellurium.semantic.material.OreVeinProgram.disabled(), null, java.util.List.of(),
                            new dev.tellurium.compiler.vulkan.worldgen.AquiferEmitter.Options(true, 16,
                                    java.util.List.of(
                                            new dev.tellurium.compiler.vulkan.worldgen.AquiferEmitter.Candidate(0, 0, 0, 10, 31),
                                            new dev.tellurium.compiler.vulkan.worldgen.AquiferEmitter.Candidate(0, -12, 0, 10, 31),
                                            new dev.tellurium.compiler.vulkan.worldgen.AquiferEmitter.Candidate(0, 12, 0, 10, 31),
                                            new dev.tellurium.compiler.vulkan.worldgen.AquiferEmitter.Candidate(32, 0, 0, 4, 32),
                                            new dev.tellurium.compiler.vulkan.worldgen.AquiferEmitter.Candidate(32, -12, 0, 4, 32),
                                            new dev.tellurium.compiler.vulkan.worldgen.AquiferEmitter.Candidate(32, 12, 0, 4, 32)),
                                    63, -54, 31, 32)));
            int[] aquiferCoordinates = {0, 0, 0, 2, 0, 0, 32, 4, 0, 32, 5, 0};
            var aquiferResult = executor.executeBatched(new VulkanWorldgenExecutor.Request(aquiferShader,
                    aquiferCoordinates, 17, 23, 99, new int[]{17, 23, 31, 32, 99}), 2);
            int[] expectedAquifer = {31, 31, 23, 23};
            if (!java.util.Arrays.equals(expectedAquifer, aquiferResult.stateIds())) {
                throw new IllegalStateException("Device aquifer result mismatch: expected="
                        + java.util.Arrays.toString(expectedAquifer) + " actual="
                        + java.util.Arrays.toString(aquiferResult.stateIds()));
            }
            System.out.println("nativeWorldgen AQUIFER_PASS device=" + aquiferResult.device().name()
                    + " elements=" + aquiferResult.elementCount()
                    + " sliceLimit=2"
                    + " shaderSha256=" + aquiferResult.shaderHash()
                    + " spirvSha256=" + aquiferResult.spirvHash());

            var aquiferMetadataShader = new WorldgenShaderCompiler().emit(
                    WorldgenProgram.builder()
                            .root("finalDensity", new ProgramNode.Constant(ValueType.FP32, -10.0f, EvaluationDomain.BLOCK))
                            .root("barrierNoise", new ProgramNode.Constant(ValueType.FP32, 0.0f, EvaluationDomain.WORLD))
                            .build(),
                    NumericProfile.GPU_IEEE_BITS, 64, StructureBlendSnapshot.empty(),
                    new WorldgenShaderCompiler.MaterialOptions(
                            dev.tellurium.semantic.material.OreVeinProgram.disabled(), null, java.util.List.of(),
                            new dev.tellurium.compiler.vulkan.worldgen.AquiferEmitter.Options(true, 16,
                                    java.util.List.of(
                                            new dev.tellurium.compiler.vulkan.worldgen.AquiferEmitter.Candidate(0, 0, 0, 10, 31),
                                            new dev.tellurium.compiler.vulkan.worldgen.AquiferEmitter.Candidate(2, 0, 0, 10, 31),
                                            new dev.tellurium.compiler.vulkan.worldgen.AquiferEmitter.Candidate(0, 12, 0, 10, 31)),
                                    63, -54, 31, 32)),
                    WorldgenShaderCompiler.MarkerPolicy.REJECT_UNSTAGED,
                    WorldgenShaderCompiler.OutputMode.STATE_AND_FLUID_MARK);
            var aquiferMetadata = executor.executeRaw(new VulkanWorldgenExecutor.RawRequest(
                    aquiferMetadataShader, new int[]{0, 0, 0, 0}, 4, 2, 1, 17, 23, 99));
            int[] aquiferMetadataWords = aquiferMetadata.outputWords();
            if (aquiferMetadataWords[0] != 31 || aquiferMetadataWords[1] != 1) {
                throw new IllegalStateException("Device aquifer metadata mismatch: expected=[31, 1] actual="
                        + java.util.Arrays.toString(aquiferMetadataWords));
            }
            System.out.println("nativeWorldgen AQUIFER_METADATA_PASS device=" + aquiferMetadata.device().name()
                    + " elements=" + aquiferMetadata.elementCount()
                    + " outputWordsPerElement=" + aquiferMetadata.outputWordsPerElement()
                    + " shaderSha256=" + aquiferMetadata.shaderHash()
                    + " spirvSha256=" + aquiferMetadata.spirvHash());

            var oreShader = new WorldgenShaderCompiler().emit(
                    WorldgenProgram.builder()
                            .root("finalDensity", new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.BLOCK))
                            .root("veinToggle", new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.WORLD))
                            .root("veinRidged", new ProgramNode.Constant(ValueType.FP64, -1.0d, EvaluationDomain.WORLD))
                            .root("veinGap", new ProgramNode.Constant(ValueType.FP64, 0.0d, EvaluationDomain.WORLD))
                            .build(),
                    NumericProfile.GPU_IEEE_BITS, 64, StructureBlendSnapshot.empty(),
                    new WorldgenShaderCompiler.MaterialOptions(
                            dev.tellurium.semantic.material.OreVeinProgram.vanilla(99L),
                            PositionalRandomFactorySnapshot.legacy(123L),
                            java.util.List.of(101, 102, 103, 104, 105, 106)));
            // Keep one valid non-copper filler, one valid copper filler, and two
            // out-of-range points so both random branches and the edge gate are
            // exercised by the legacy and xoroshiro paths.
            int[] oreCoordinates = {-5, -20, 5, 5, 20, -5, -5, 20, 5, 5, -100, 5};
            var oreResult = executor.executeBatched(new VulkanWorldgenExecutor.Request(oreShader, oreCoordinates,
                    17, 23, 99, new int[]{17, 23, 99, 101, 102, 103, 104, 105, 106}), 2);
            int[] expectedOre = {106, 17, 17, 17};
            if (!java.util.Arrays.equals(expectedOre, oreResult.stateIds())) {
                throw new IllegalStateException("Device ore result mismatch: expected="
                        + java.util.Arrays.toString(expectedOre) + " actual="
                        + java.util.Arrays.toString(oreResult.stateIds()));
            }
            System.out.println("nativeWorldgen ORE_PASS device=" + oreResult.device().name()
                    + " elements=" + oreResult.elementCount()
                    + " sliceLimit=2"
                    + " shaderSha256=" + oreResult.shaderHash()
                    + " spirvSha256=" + oreResult.spirvHash());

            var xorOreShader = new WorldgenShaderCompiler().emit(
                    WorldgenProgram.builder()
                            .root("finalDensity", new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.BLOCK))
                            .root("veinToggle", new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.WORLD))
                            .root("veinRidged", new ProgramNode.Constant(ValueType.FP64, -1.0d, EvaluationDomain.WORLD))
                            .root("veinGap", new ProgramNode.Constant(ValueType.FP64, 0.0d, EvaluationDomain.WORLD))
                            .build(),
                    NumericProfile.GPU_IEEE_BITS, 64, StructureBlendSnapshot.empty(),
                    new WorldgenShaderCompiler.MaterialOptions(
                            dev.tellurium.semantic.material.OreVeinProgram.vanilla(99L),
                            PositionalRandomFactorySnapshot.xoroshiro(123L, 456L),
                            java.util.List.of(101, 102, 103, 104, 105, 106)));
            var xorOreResult = executor.executeBatched(new VulkanWorldgenExecutor.Request(xorOreShader,
                    oreCoordinates, 17, 23, 99, new int[]{17, 23, 99, 101, 102, 103, 104, 105, 106}), 2);
            int[] expectedXorOre = {106, 103, 17, 17};
            if (!java.util.Arrays.equals(expectedXorOre, xorOreResult.stateIds())) {
                throw new IllegalStateException("Device xoroshiro ore result mismatch: expected="
                        + java.util.Arrays.toString(expectedXorOre) + " actual="
                        + java.util.Arrays.toString(xorOreResult.stateIds()));
            }
            System.out.println("nativeWorldgen XOR_ORE_PASS device=" + xorOreResult.device().name()
                    + " elements=" + xorOreResult.elementCount()
                    + " sliceLimit=2"
                    + " shaderSha256=" + xorOreResult.shaderHash()
                    + " spirvSha256=" + xorOreResult.spirvHash());
        }
    }
}
