// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan;

import dev.tellurium.compiler.vulkan.worldgen.*;
import dev.tellurium.semantic.program.*;
import dev.tellurium.semantic.snapshot.NoiseParameters;
import dev.tellurium.semantic.snapshot.BlendedNoiseParameters;
import dev.tellurium.semantic.snapshot.BeardifierSnapshot;
import dev.tellurium.semantic.snapshot.StructureBlendSnapshot;
import dev.tellurium.semantic.snapshot.NoiseRouterSnapshot;
import dev.tellurium.semantic.snapshot.WorldgenSnapshot;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class V02WorldgenShaderTest {
    @Test void emptyCapturedBeardifierEmitsExactPositiveZeroInBothCarrierTypes() {
        for (ValueType type : new ValueType[]{ValueType.FP32, ValueType.FP64}) {
            var root = new ProgramNode.Beardifier(type, EvaluationDomain.BLOCK);
            var shader = new WorldgenShaderCompiler().emit(
                    WorldgenProgram.builder().root("finalDensity", root).build(), NumericProfile.GPU_IEEE_BITS, 64);
            String function = shader.nodeFunctions().entrySet().stream()
                    .filter(entry -> entry.getValue() == root).findFirst().orElseThrow().getKey();
            assertTrue(shader.source().contains((type == ValueType.FP64 ? "uvec2 " : "uint ")
                    + function + "(ivec3 point) {\n" + (type == ValueType.FP64 ? "return uvec2(0u);" : "return 0u;")));
            assertFalse(shader.source().contains("wg_beard_kernel"));
            assertFalse(shader.source().contains("wg_beardifier(point)"));
            SpirvNumericContract.require(shader.source(), NumericProfile.GPU_IEEE_BITS);
        }
    }

    @Test void nonemptyEmbeddedBeardifierIsSnapshotSpecificAndDeduplicatesItsRealHelper() {
        var beardifier = new BeardifierSnapshot(java.util.List.of(new BeardifierSnapshot.Rigid(
                -2, -2, -2, 2, 2, 2, BeardifierSnapshot.Adjustment.BEARD_BOX, 2)),
                java.util.List.of(new BeardifierSnapshot.Junction(-3, 0, 4)));
        var blend = new StructureBlendSnapshot("captured-beardifier", java.util.Map.of(), java.util.Map.of(), beardifier);
        for (ValueType type : new ValueType[]{ValueType.FP32, ValueType.FP64}) {
            var root = new ProgramNode.Binary("add", type, EvaluationDomain.BLOCK,
                    new ProgramNode.Beardifier(type, EvaluationDomain.BLOCK),
                    new ProgramNode.Beardifier(type, EvaluationDomain.BLOCK));
            var shader = new WorldgenShaderCompiler().emit(
                    WorldgenProgram.builder().root("finalDensity", root).build(), NumericProfile.GPU_IEEE_BITS, 64, blend);
            assertEquals(1, shader.source().split("uvec2 wg_beardifier\\(ivec3 point\\)", -1).length - 1);
            assertTrue(shader.source().contains(type == ValueType.FP64 ? "return wg_beardifier(point);"
                    : "return wg_fp64_to_fp32(wg_beardifier(point));"));
            assertTrue(shader.source().contains("wg_beard_kernel[13824]"));
            assertTrue(shader.source().contains("wg_fp64_mul(wg_fp64_mul(halfValue, estimate), estimate)"));
            assertFalse(shader.source().contains("estimateSquared"));
            SpirvNumericContract.require(shader.source(), NumericProfile.GPU_IEEE_BITS);
        }
    }

    @Test void beardifierCombineCoordinatesUseTheSixWordRowRatherThanTheOrdinaryPointStride() {
        var shader = new WorldgenShaderCompiler().emitBeardifierStage(
                StructureBlendSnapshot.empty(), NumericProfile.GPU_IEEE_BITS, 64, true);
        String main = shader.source().substring(shader.source().indexOf("// bounds check is mandatory"));
        assertTrue(main.contains("uint inputBase = index * 6u;"));
        assertTrue(main.contains("wg_i32_from_bits(inputBits[inputBase + 1u])"));
        assertTrue(main.contains("wg_i32_from_bits(inputBits[inputBase + 2u])"));
        assertFalse(main.contains("wg_point(index)"));
        assertTrue(main.indexOf("uint inputBase") < main.indexOf("ivec3 point"));
    }

    @Test void oneKnotSplineEmitsLinearExtensionIncludingNestedValueAndSignedZeroDerivative() {
        for (float derivative : new float[]{-0.0f, 0.0f, -0.25f, 2.0f}) {
            var coordinate = new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.WORLD);
            var child = new ProgramNode.SplineMultipoint(coordinate, java.util.List.of(-3.0f),
                    java.util.List.of(new ProgramNode.SplineConstant(4.0f)), java.util.List.of(derivative));
            var parent = new ProgramNode.SplineMultipoint(coordinate, java.util.List.of(1.0f),
                    java.util.List.of(child), java.util.List.of(derivative));
            var shader = new WorldgenShaderCompiler().emit(
                    WorldgenProgram.builder().root("finalDensity",
                            new ProgramNode.Spline(parent, ValueType.FP64, EvaluationDomain.WORLD)).build(),
                    NumericProfile.GPU_IEEE_BITS, 64);
            assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid());
            assertEquals(3, shader.nodeFunctions().keySet().stream()
                    .filter(name -> name.startsWith("wg_spline_")).count());
            assertTrue(shader.source().contains("uint value = wg_spline_"));
            assertTrue(shader.source().contains("return wg_fp32_zero("));
            assertFalse(shader.source().contains("uint locations[1]"));
            assertFalse(shader.source().contains("segment < 0"));
        }
        String shared = SharedSplineStageEmitter.source();
        assertTrue(shared.contains("knots >= 1u && knots <= 64u"));
        assertTrue(shared.contains("knots < 1u || knots > 64u"));
        assertTrue(shared.contains("segment < knots - 1u"));
    }

    @Test void endIslandPreservesJavaSignedRemainderAndLeftAssociatedSimplexAttenuation() {
        var parameters = new dev.tellurium.semantic.snapshot.EndIslandParameters(0.0, 0.0,
                java.util.stream.IntStream.range(0, 256).boxed().toList());
        var shader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity",
                        new ProgramNode.EndIsland(parameters, ValueType.FP64, EvaluationDomain.WORLD)).build(),
                NumericProfile.GPU_IEEE_BITS, 64);
        assertTrue(shader.source().contains("remainderX = x - gridX * 2, remainderZ = z - gridZ * 2"));
        assertFalse(shader.source().contains("x % 2"));
        assertFalse(shader.source().contains("z % 2"));
        assertTrue(shader.source().contains("wg_fp64_sub(wg_fp64_sub(uvec2(0u, 0x3fe00000u),"));
        assertTrue(shader.source().contains("wg_fp64_mul(x, x)), wg_fp64_mul(y, y))"));
        assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid());
    }

    @Test void stagePlannerRequiresFinalDensityAndChecksStageRanges() {
        var planner = new StagePlanner();
        assertThrows(IllegalArgumentException.class,
                () -> planner.plan(WorldgenProgram.builder().build(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> planner.plan(WorldgenProgram.builder().root("finalDensity",
                        new ProgramNode.Constant(ValueType.BOOLEAN, true, EvaluationDomain.BLOCK)).build(), 1));

        var plan = planner.plan(WorldgenProgram.builder().root("finalDensity",
                new ProgramNode.Constant(ValueType.FP64, 0.0, EvaluationDomain.BLOCK)).build(), 3);
        assertEquals(java.util.List.of("density", "aquifer", "ore", "material", "metadata"),
                plan.stream().map(StageLayout::name).toList());
        for (int index = 0; index < plan.size(); index++) {
            StageLayout stage = plan.get(index);
            assertEquals(stage.outputOffset(), stage.inputOffset());
            assertEquals(stage.outputOffset() + stage.outputBytes(), stage.outputEndExclusive());
            if (index > 0) assertEquals(plan.get(index - 1).outputEndExclusive(), stage.outputOffset());
        }
        assertThrows(IllegalArgumentException.class,
                () -> new StageLayout("overflow", Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 2));
    }

    @Test void softwareProfileUsesRawIntegerCarriersAndBounds() {
        var program = WorldgenProgram.builder().root("finalDensity", new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.BLOCK)).build();
        var shader = new WorldgenShaderCompiler().emit(program, NumericProfile.GPU_IEEE_BITS, 64);
        var inspection = new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS);
        assertTrue(inspection.valid(), inspection.violations().toString());
        assertTrue(shader.source().contains("uint inputBits[]"));
        assertTrue(shader.source().contains("index>=dispatch.count"));
        assertTrue(shader.source().contains("0x3f800000u"));
        assertFalse(shader.source().contains("outputStateIds[index]=0u;"));
        assertTrue(shader.source().contains("wg_fp32_finite"));
        assertTrue(shader.source().contains("uint wg_material_decide"));
        assertTrue(shader.source().contains("if (oreCandidate) return oreState"));
        assertTrue(shader.source().contains("return defaultStateId;"));
        assertFalse(shader.source().contains("return wg_fp32_less(0u, density) ? defaultStateId : airStateId;"));
    }

    @Test void shaderRetainsStableSemanticNodeMetadata() {
        ProgramNode left = new ProgramNode.Constant(ValueType.FP64, 1.0, EvaluationDomain.BLOCK);
        ProgramNode right = new ProgramNode.Constant(ValueType.FP64, 2.0, EvaluationDomain.BLOCK);
        ProgramNode root = new ProgramNode.Binary("+", ValueType.FP64, EvaluationDomain.BLOCK, left, right);
        var shader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity", root).build(),
                NumericProfile.GPU_IEEE_BITS, 64);

        assertEquals(3, shader.nodeFunctions().size());
        assertEquals(root, shader.nodeFunctions().values().stream()
                .filter(node -> WorldgenProgram.nodeFingerprint(node).equals(WorldgenProgram.nodeFingerprint(root)))
                .findFirst().orElseThrow());
    }

    @Test void metadataOutputCarriesDeviceFluidUpdateMarkAlongsideState() {
        var options = new WorldgenShaderCompiler.MaterialOptions(
                dev.tellurium.semantic.material.OreVeinProgram.disabled(), null, java.util.List.of(),
                new AquiferEmitter.Options(true, 16, java.util.List.of(
                        new AquiferEmitter.Candidate(0, 0, 0, 10, 31)), 63, -54, 31, 32));
        var shader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder()
                        .root("finalDensity", new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.BLOCK))
                        .root("barrierNoise", new ProgramNode.Constant(ValueType.FP32, 0.0f, EvaluationDomain.WORLD))
                        .build(), NumericProfile.GPU_IEEE_BITS, 64, StructureBlendSnapshot.empty(), options,
                WorldgenShaderCompiler.MarkerPolicy.DIRECT_POINT_REPLAY,
                WorldgenShaderCompiler.OutputMode.STATE_AND_FLUID_MARK);

        assertTrue(shader.source().contains("outputStateIds[index * 2u] = materialState"));
        assertTrue(shader.source().contains("outputStateIds[index * 2u + 1u] = aquiferResult.w"));
        assertTrue(shader.source().contains("outputStateIds[index * 2u + 1u] = 0u"));
        assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid());
    }

    @Test void disabledAquiferEmitsTheGlobalDefaultFluidPicker() {
        var options = new WorldgenShaderCompiler.MaterialOptions(
                dev.tellurium.semantic.material.OreVeinProgram.disabled(), null, java.util.List.of(),
                AquiferEmitter.Options.defaultFluid(63, -54, 7, 8));
        var shader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder()
                        .root("finalDensity", new ProgramNode.Constant(ValueType.FP32, 0.0f, EvaluationDomain.BLOCK))
                        .build(), NumericProfile.GPU_IEEE_BITS, 64, StructureBlendSnapshot.empty(), options,
                WorldgenShaderCompiler.MarkerPolicy.DIRECT_POINT_REPLAY,
                WorldgenShaderCompiler.OutputMode.STATE_ONLY);

        assertTrue(shader.source().contains("wg_aquifer_default_fluid_"));
        assertTrue(shader.source().contains("if (point.y < -54)"));
        assertTrue(shader.source().contains("if (point.y < 63)"));
        assertFalse(shader.source().contains("barrierNoise"));
        assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid());
    }

    @Test void capturedOreStageEmitsRootsPositionSeedAndOrderedMaterialBranches() {
        var options = new WorldgenShaderCompiler.MaterialOptions(
                dev.tellurium.semantic.material.OreVeinProgram.vanilla(99L),
                dev.tellurium.semantic.snapshot.PositionalRandomFactorySnapshot.legacy(123L),
                java.util.List.of(10, 11, 12, 13, 14, 15));
        var program = WorldgenProgram.builder()
                .root("finalDensity", new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.BLOCK))
                .root("veinToggle", new ProgramNode.Constant(ValueType.FP64, 0.5d, EvaluationDomain.WORLD))
                .root("veinRidged", new ProgramNode.Constant(ValueType.FP64, -1.0d, EvaluationDomain.WORLD))
                .root("veinGap", new ProgramNode.Constant(ValueType.FP64, 0.0d, EvaluationDomain.WORLD))
                .build();
        var shader = new WorldgenShaderCompiler().emit(program, NumericProfile.GPU_IEEE_BITS, 64,
                StructureBlendSnapshot.empty(), options);

        assertTrue(shader.source().contains("wg_ore_position_"));
        assertTrue(shader.source().contains("wg_ore_random_"));
        assertTrue(shader.source().contains("wg_i64_mul_lo(state"));
        assertTrue(shader.source().contains("0xdeece66du"));
        assertTrue(shader.source().contains("copper ? 0xau : 0xdu"));
        assertTrue(shader.source().contains("wg_fp32_less(rareRandom, 0x3ca3d70au)"));
        assertTrue(shader.source().contains("wg_fp32_less(rareRandom, 0x3ca3d70au)) {"));
        assertFalse(shader.source().contains("wg_fp32_less(0x3ca3d70au, rareRandom)"));
        assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid());

        var xorShader = new WorldgenShaderCompiler().emit(program, NumericProfile.GPU_IEEE_BITS, 64,
                StructureBlendSnapshot.empty(), new WorldgenShaderCompiler.MaterialOptions(
                        dev.tellurium.semantic.material.OreVeinProgram.vanilla(99L),
                        dev.tellurium.semantic.snapshot.PositionalRandomFactorySnapshot.xoroshiro(123L, 456L),
                        java.util.List.of(10, 11, 12, 13, 14, 15)));
        assertTrue(xorShader.source().contains("wg_xoroshiro128pp_next"));
        assertTrue(new SpirvNumericContract().inspect(xorShader.source(), NumericProfile.GPU_IEEE_BITS).valid());
    }

    @Test void capturedAquiferStageEmitsNearestCandidateAndPrecedesOre() {
        var options = new WorldgenShaderCompiler.MaterialOptions(
                dev.tellurium.semantic.material.OreVeinProgram.disabled(), null, java.util.List.of(),
                new AquiferEmitter.Options(true, 16, java.util.List.of(
                        new AquiferEmitter.Candidate(0, 0, 0, 10, 31),
                        new AquiferEmitter.Candidate(32, 0, 0, 4, 32)),
                        63, -54, 31, 32));
        var shader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity",
                        new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.BLOCK))
                        .root("barrierNoise", new ProgramNode.Constant(ValueType.FP32, 0.0f, EvaluationDomain.WORLD))
                        .build(),
                NumericProfile.GPU_IEEE_BITS, 64, StructureBlendSnapshot.empty(), options);

        assertTrue(shader.source().contains("Captured aquifer statuses; cellSize=16"));
        assertTrue(shader.source().contains("aquiferGridX = wg_i32_from_bits(wg_i32_floor_div"));
        assertTrue(shader.source().contains("aquiferGridYPrevious"));
        assertTrue(shader.source().contains("aquiferGridZNext"));
        assertTrue(shader.source().contains("if ((aquiferGridX == candidate.x || aquiferGridXNext == candidate.x)"));
        assertTrue(shader.source().contains("&& (aquiferGridZ == candidate.z || aquiferGridZNext == candidate.z)"));
        assertTrue(shader.source().contains("wg_u64_less(candidateDistance, firstDistance)"));
        assertTrue(shader.source().contains("nearestThree=true, devicePressure=true"));
        assertTrue(shader.source().contains("wg_aquifer_pressure_"));
        assertTrue(shader.source().contains("if (!foundFirst || !foundSecond || !foundThird)"));
        assertTrue(shader.source().contains("wg_fp64_add(density, pressure12)"));
        assertTrue(shader.source().contains("if (wg_failed) {"));
        assertTrue(shader.source().contains("wg_fp64_from_fp32(wg_node_"));
        assertTrue(shader.source().contains("wg_material_decide(density, aquiferResult.x != 0u"));
        assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid());
    }

    @Test void exactAquiferDraftUsesIntegerRationalPressureComparison() {
        String property = "tellurium.gpuCandidate.exactAquiferStage";
        String previous = System.getProperty(property);
        System.setProperty(property, "true");
        try {
            var options = new WorldgenShaderCompiler.MaterialOptions(
                    dev.tellurium.semantic.material.OreVeinProgram.disabled(), null, java.util.List.of(),
                    new AquiferEmitter.Options(true, 16, java.util.List.of(
                            new AquiferEmitter.Candidate(0, 0, 0, 10, 31),
                            new AquiferEmitter.Candidate(32, 0, 0, 4, 32)),
                            63, -54, 31, 32));
            var shader = new WorldgenShaderCompiler().emit(
                    WorldgenProgram.builder().root("finalDensity",
                            new ProgramNode.Constant(ValueType.FP32, -0.25f, EvaluationDomain.BLOCK))
                            .root("barrierNoise", new ProgramNode.Constant(ValueType.FP32, 0.0f, EvaluationDomain.WORLD))
                            .build(), NumericProfile.GPU_IEEE_BITS, 64,
                    StructureBlendSnapshot.empty(), options);

            assertTrue(shader.source().contains("wg_aquifer_pressure_positive_"));
            assertTrue(shader.source().contains("wg_aquifer_mul_u64_u32_"));
            assertTrue(shader.source().contains("wg_aquifer_density_equal_rational_"));
            assertFalse(shader.source().contains("wg_aquifer_scaled_pressure_"));
            assertFalse(shader.source().contains("uvec2 wg_aquifer_similarity_"));
            assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid());
        } finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    @Test void exactAquiferCanConsumeAnExternalBarrierCarrier() {
        String exactProperty = "tellurium.gpuCandidate.exactAquiferStage";
        String barrierProperty = "tellurium.gpuCandidate.exactAquiferBarrierInput";
        String previousExact = System.getProperty(exactProperty);
        String previousBarrier = System.getProperty(barrierProperty);
        System.setProperty(exactProperty, "true");
        System.setProperty(barrierProperty, "true");
        try {
            var options = new WorldgenShaderCompiler.MaterialOptions(
                    dev.tellurium.semantic.material.OreVeinProgram.disabled(), null, java.util.List.of(),
                    new AquiferEmitter.Options(true, 16, java.util.List.of(
                            new AquiferEmitter.Candidate(0, 0, 0, 10, 31),
                            new AquiferEmitter.Candidate(32, 0, 0, 4, 32)),
                            63, -54, 31, 32));
            var shader = new WorldgenShaderCompiler().emit(
                    WorldgenProgram.builder().root("finalDensity",
                            new ProgramNode.Constant(ValueType.FP32, -0.25f, EvaluationDomain.BLOCK))
                            .root("barrierNoise", new ProgramNode.Constant(ValueType.FP32, 0.125f, EvaluationDomain.WORLD))
                            .build(), NumericProfile.GPU_IEEE_BITS, 64,
                    StructureBlendSnapshot.empty(), options);

            assertTrue(shader.source().contains("uvec2 barrier)"));
            assertTrue(shader.source().contains("wg_aquifer_scale_rational_"));
            assertTrue(shader.source().contains("similarityFirst * similaritySecond * 2u, 625u"));
            assertTrue(shader.source().contains("uvec2 pressureDensity = density;"));
            assertTrue(shader.source().contains("if (parts.z != 0) {"));
            assertTrue(shader.source().contains("pressureDensity = wg_aquifer_add_carriers_"));
            assertTrue(shader.source().contains("remainder <<= 1u;"));
            assertTrue(shader.source().contains("wg_fp64_from_fp32(wg_node_"));
            assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid());
        } finally {
            if (previousExact == null) System.clearProperty(exactProperty);
            else System.setProperty(exactProperty, previousExact);
            if (previousBarrier == null) System.clearProperty(barrierProperty);
            else System.setProperty(barrierProperty, previousBarrier);
        }
    }

    @Test void enabledAquiferRejectsMissingBarrierRoot() {
        var options = new WorldgenShaderCompiler.MaterialOptions(
                dev.tellurium.semantic.material.OreVeinProgram.disabled(), null, java.util.List.of(),
                new AquiferEmitter.Options(true, 16, java.util.List.of(
                        new AquiferEmitter.Candidate(0, 0, 0, 10, 31)), 63, -54, 31, 32));
        var error = assertThrows(IllegalArgumentException.class, () -> new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity",
                        new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.BLOCK)).build(),
                NumericProfile.GPU_IEEE_BITS, 64, StructureBlendSnapshot.empty(), options));
        assertTrue(error.getMessage().contains("barrierNoise"));
    }

    @Test void completeLoaderSnapshotIsTheTypedVulkanCompilerBoundary() {
        var roots = new java.util.LinkedHashMap<String, ProgramNode>();
        for (String name : WorldgenProgram.ROUTER_ROOTS) {
            roots.put(name, name.equals("finalDensity")
                    ? new ProgramNode.Constant(ValueType.FP32, 1.0f, EvaluationDomain.BLOCK)
                    : new ProgramNode.Constant(ValueType.FP64, 0.0d, EvaluationDomain.WORLD));
        }
        var snapshot = WorldgenSnapshot.builder(77L, "minecraft:overworld")
                .router(new NoiseRouterSnapshot(roots))
                .build();
        var shader = new WorldgenShaderCompiler().emitSnapshot(snapshot, NumericProfile.GPU_IEEE_BITS, 32);

        assertEquals(NumericProfile.GPU_IEEE_BITS, shader.profile());
        assertTrue(shader.source().contains("Tellurium typed worldgen ABI v2"));
        assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid());
        var partial = WorldgenSnapshot.builder(77L, "minecraft:overworld").build();
        var error = assertThrows(IllegalArgumentException.class,
                () -> new WorldgenShaderCompiler().emitSnapshot(partial, NumericProfile.GPU_IEEE_BITS, 32));
        assertTrue(error.getMessage().contains("all 15 router roots"));
    }

    @Test void shaderCarriesExactBlendedNoiseFunctionMetadata() {
        var permutation = java.util.stream.IntStream.range(0, 256).boxed().toList();
        var level = new NoiseParameters.ImprovedNoiseSnapshot(0.0, 0.0, 0.0, permutation);
        var perlin = new NoiseParameters.PerlinNoiseSnapshot(0, java.util.List.of(1.0), java.util.List.of(level));
        var parameters = new BlendedNoiseParameters(77L, perlin, perlin, perlin,
                1.0, 1.0, 1.0, 1.0, 1.0);
        var program = WorldgenProgram.builder().root("finalDensity",
                new ProgramNode.BlendedNoise(parameters, ValueType.FP64, EvaluationDomain.WORLD)).build();

        var shader = new WorldgenShaderCompiler().emit(program, NumericProfile.GPU_IEEE_BITS, 32);

        assertEquals(1, shader.blendedNoiseFunctions().size());
        assertEquals(parameters, shader.blendedNoiseFunctions().values().iterator().next());
        assertTrue(shader.blendedNoiseFunctions().keySet().stream()
                .allMatch(name -> name.startsWith("wg_noise_blended_")));
    }

    @Test void typedArithmeticAndLazyControlAreEmitted() {
        var x = new ProgramNode.Input("x", ValueType.FP32, EvaluationDomain.BLOCK);
        var sum = new ProgramNode.Binary("add", ValueType.FP32, EvaluationDomain.BLOCK,
                x, new ProgramNode.Constant(ValueType.FP32, 2.0f, EvaluationDomain.WORLD));
        var selector = new ProgramNode.Binary("and", ValueType.BOOLEAN, EvaluationDomain.BLOCK,
                new ProgramNode.Constant(ValueType.BOOLEAN, true, EvaluationDomain.WORLD),
                new ProgramNode.Constant(ValueType.BOOLEAN, false, EvaluationDomain.WORLD));
        var branched = new ProgramNode.Select("select", ValueType.FP32, EvaluationDomain.BLOCK,
                selector, sum, new ProgramNode.Constant(ValueType.FP32, -1.0f, EvaluationDomain.WORLD));
        var interpolated = new ProgramNode.Interpolated(branched, new InterpolationGeometry(4, 8), ValueType.FP32);
        var shader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity", interpolated).build(),
                NumericProfile.GPU_IEEE_BITS, 32);

        assertTrue(shader.source().contains("wg_fp32_add"));
        assertTrue(shader.source().contains("wg_fp32_lerp"));
        assertTrue(shader.source().contains("if (!left) return false"));
        assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid());
    }

    @Test void capturedInterpolationUsesMinecraftLerp3AxisOrder() {
        var child = new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.BLOCK);
        var interpolated = new ProgramNode.Interpolated(child,
                new InterpolationGeometry(4, 8), ValueType.FP64);
        var compiler = new WorldgenShaderCompiler();

        var captured = compiler.emit(WorldgenProgram.builder().root("finalDensity", interpolated).build(),
                NumericProfile.GPU_IEEE_BITS, 32, StructureBlendSnapshot.empty(),
                WorldgenShaderCompiler.MaterialOptions.disabled(),
                WorldgenShaderCompiler.MarkerPolicy.DIRECT_POINT_REPLAY);
        assertTrue(captured.source().contains("uvec2 x00=wg_fp64_lerp(v000, v100, tx)"));
        assertTrue(captured.source().contains("uvec2 interpY0=wg_fp64_lerp(x00, x10, ty)"));
        assertTrue(captured.source().contains("return wg_fp64_lerp(interpY0, interpY1, tz)"));

        var synthetic = compiler.emit(WorldgenProgram.builder().root("finalDensity", interpolated).build(),
                NumericProfile.GPU_IEEE_BITS, 32);
        assertTrue(synthetic.source().contains("uvec2 x00=wg_fp64_lerp(v000, v010, ty)"));
        assertTrue(synthetic.source().contains("return wg_fp64_lerp(wg_fp64_lerp(x00, x10, tx)"));
    }

    @Test void ap2EmitsLazyIntegerCarrierBranches() {
        var left = new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.BLOCK);
        var right = new ProgramNode.Constant(ValueType.FP64, 3.0d, EvaluationDomain.WORLD);
        var multiply = new ProgramNode.Ap2("multiply", ValueType.FP64, EvaluationDomain.BLOCK,
                left, right, 0.0, 3.0);
        var minimum = new ProgramNode.Ap2("min", ValueType.FP64, EvaluationDomain.BLOCK,
                left, right, -2.0, 3.0);
        var shader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity", multiply).build(),
                NumericProfile.GPU_IEEE_BITS, 32);
        assertTrue(shader.source().contains("wg_fp64_equal(left, uvec2(0u))"));
        assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid());

        var minimumShader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity", minimum).build(),
                NumericProfile.GPU_IEEE_BITS, 32);
        assertTrue(minimumShader.source().contains("wg_fp64_min(left"));
        assertTrue(minimumShader.source().contains("if (wg_fp64_less(left, uvec2(0x0u, 0xc0000000u))"));
    }

    @Test void directPointReplayPreservesEveryNoiseChunkMarkerBoundary() {
        var compiler = new WorldgenShaderCompiler();
        for (String mode : java.util.List.of("ONCE", "ALL_IN_CELL", "CACHE2D", "FLATCACHE")) {
            var marker = new ProgramNode.Marker("marker/" + mode, mode, ValueType.FP32,
                    EvaluationDomain.BLOCK,
                    new ProgramNode.Input("x", ValueType.FP32, EvaluationDomain.BLOCK),
                    java.util.Map.of("mode", mode));
            var shader = compiler.emit(WorldgenProgram.builder().root("finalDensity", marker).build(),
                    NumericProfile.GPU_IEEE_BITS, 32, StructureBlendSnapshot.empty(),
                    WorldgenShaderCompiler.MaterialOptions.disabled(),
                    WorldgenShaderCompiler.MarkerPolicy.DIRECT_POINT_REPLAY);
            assertTrue(shader.source().contains("Tellurium marker " + mode), mode);
            assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid(), mode);
            var rejected = assertThrows(UnsupportedOperationException.class,
                    () -> compiler.emit(WorldgenProgram.builder().root("finalDensity", marker).build(),
                            NumericProfile.GPU_IEEE_BITS, 32));
            assertTrue(rejected.getMessage().contains("staged cache emitter"), mode);
        }
    }

    @Test void unsupportedTypedNodesFailClosed() {
        var unsupported = new ProgramNode.Constant(ValueType.INT64, 1L, EvaluationDomain.WORLD);
        var error = assertThrows(UnsupportedOperationException.class, () -> new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity", unsupported).build(),
                NumericProfile.GPU_IEEE_BITS, 64));
        assertTrue(error.getMessage().contains("INT64"));
    }

    @Test void integerCarrierSquareRootIsEmittedForBothFloatingWidths() {
        var fp32 = new ProgramNode.Unary("sqrt", ValueType.FP32, EvaluationDomain.BLOCK,
                new ProgramNode.Constant(ValueType.FP32, 4.0f, EvaluationDomain.WORLD));
        var fp64 = new ProgramNode.Unary("sqrt", ValueType.FP64, EvaluationDomain.BLOCK,
                new ProgramNode.Constant(ValueType.FP64, 4.0d, EvaluationDomain.WORLD));
        var compiler = new WorldgenShaderCompiler();
        assertTrue(compiler.emit(WorldgenProgram.builder().root("finalDensity", fp32).build(), NumericProfile.GPU_IEEE_BITS, 64)
                .source().contains("wg_fp32_sqrt"));
        assertTrue(compiler.emit(WorldgenProgram.builder().root("finalDensity", fp64).build(), NumericProfile.GPU_IEEE_BITS, 64)
                .source().contains("wg_fp64_sqrt"));
    }

    @Test void integerToBinary64WideningDoesNotRouteThroughBinary32() {
        String source = IntegerIeeeEmitter.source();
        assertTrue(source.contains("uvec2 wg_fp64_from_int(int value) {"));
        assertTrue(source.contains("uint magnitude = value < 0 ? uint(-(value + 1)) + 1u : uint(value);"));
        assertTrue(source.contains("wg_u64_shl(uvec2(magnitude, 0u), uint(52 - highest))"));
        assertFalse(source.contains("uvec2 wg_fp64_from_int(int value) { return wg_fp64_from_fp32"));
    }

    @Test void capturedNormalNoiseEmitsDeviceEvaluatedTablesAndOctaves() {
        var permutation = java.util.stream.IntStream.range(0, 256).boxed().toList();
        var level = new NoiseParameters.ImprovedNoiseSnapshot(0.0, 0.0, 0.0, permutation);
        var perlin = new NoiseParameters.PerlinNoiseSnapshot(0, java.util.List.of(1.0), java.util.List.of(level));
        var captured = new NoiseParameters.CapturedNoise(1234L, 1.0, perlin, perlin);
        var parameters = new NoiseParameters("minecraft:test", 0, java.util.List.of(1.0), 0L, captured);
        var noise = new ProgramNode.Noise("minecraft:test", parameters, 0.5, 2.0,
                ValueType.FP64, EvaluationDomain.WORLD);
        var shader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity", noise).build(),
                NumericProfile.GPU_IEEE_BITS, 64);
        assertTrue(shader.source().contains("wg_noise_perm_"));
        assertTrue(shader.source().contains("wg_noise_normal_"));
        assertTrue(shader.source().contains("wg_noise_interpolate"));
        assertTrue(shader.source().contains("noise_sample"));
        assertTrue(shader.source().contains("in uint permutation[64]"));
        assertTrue(shader.source().contains("noise_table_value"));
        assertTrue(shader.source().contains("uvec2 period = uvec2(0u, 0x41800000u);"));
        assertFalse(shader.source().contains("0x41700000u"));
        assertTrue(shader.source().contains("wg_fp64_floor_to_i32"));
        assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid());
    }

    @Test void capturedSplineEmitsFloatCubicInterpolationAndEndpointExtension() {
        var spline = new ProgramNode.Spline(new ProgramNode.SplineMultipoint(
                new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.WORLD),
                java.util.List.of(-2.0f, 0.0f, 3.0f),
                java.util.List.of(new ProgramNode.SplineConstant(-1.0f),
                        new ProgramNode.SplineConstant(0.5f), new ProgramNode.SplineConstant(2.0f)),
                java.util.List.of(0.25f, -0.5f, 0.0f)),
                ValueType.FP64, EvaluationDomain.WORLD);
        var shader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity", spline).build(),
                NumericProfile.GPU_IEEE_BITS, 64);
        assertTrue(shader.source().contains("uint wg_spline_"));
        assertTrue(shader.source().contains("wg_fp64_to_fp32"));
        assertTrue(shader.source().contains("wg_fp32_lerp"));
        assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid());
        var splineFunctions = shader.nodeFunctions().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("wg_spline_"))
                .toList();
        assertEquals(4, splineFunctions.size());
        assertTrue(splineFunctions.stream().allMatch(entry ->
                entry.getValue() instanceof ProgramNode.Spline emitted
                        && emitted.type() == ValueType.FP32
                        && emitted.domain() == EvaluationDomain.WORLD));
        assertTrue(splineFunctions.stream().anyMatch(entry ->
                ((ProgramNode.Spline) entry.getValue()).spline().equals(spline.spline())));
        assertTrue(shader.nodeFunctions().containsKey("wg_spline_1"));
        assertTrue(shader.nodeFunctions().containsKey("wg_spline_3"));
        assertTrue(shader.nodeFunctions().containsKey("wg_spline_4"));
        assertTrue(shader.nodeFunctions().containsKey("wg_spline_5"));
    }

    @Test void capturedWeirdScaledSamplerEmitsBothRarityBranchesAndNoiseCoordinates() {
        var permutation = java.util.stream.IntStream.range(0, 256).boxed().toList();
        var level = new NoiseParameters.ImprovedNoiseSnapshot(0.0, 0.0, 0.0, permutation);
        var perlin = new NoiseParameters.PerlinNoiseSnapshot(0, java.util.List.of(1.0), java.util.List.of(level));
        var captured = new NoiseParameters.CapturedNoise(77L, 1.0, perlin, perlin);
        var parameters = new NoiseParameters("minecraft:test", 0, java.util.List.of(1.0), 0L, captured);
        var compiler = new WorldgenShaderCompiler();
        for (String mapper : java.util.List.of("TYPE1", "TYPE2")) {
            var sampler = new ProgramNode.WeirdScaledSampler(
                    new ProgramNode.Input("x", ValueType.FP32, EvaluationDomain.WORLD),
                    parameters, mapper, ValueType.FP32, EvaluationDomain.WORLD);
            var shader = compiler.emit(WorldgenProgram.builder().root("finalDensity", sampler).build(),
                    NumericProfile.GPU_IEEE_BITS, 64);
            assertTrue(shader.source().contains("wg_fp64_div(wg_fp64_from_int(point.x), rarity)"));
            assertTrue(shader.source().contains("wg_fp64_abs(wg_noise_normal_"));
            assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid());
        }
    }

    @Test void capturedBlendedNoiseEmitsLegacyOctavesAndSmearedY() {
        var permutation = java.util.stream.IntStream.range(0, 256).boxed().toList();
        var level = new NoiseParameters.ImprovedNoiseSnapshot(0.0, 0.0, 0.0, permutation);
        var amplitudes = java.util.Collections.nCopies(16, 1.0);
        var levels = java.util.Collections.nCopies(16, level);
        var perlin = new NoiseParameters.PerlinNoiseSnapshot(15, amplitudes, levels);
        var parameters = new BlendedNoiseParameters(123L, perlin, perlin, perlin,
                1.0, 1.0, 8.555150000000001, 4.277575000000001, 2.0);
        var shader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity",
                        new ProgramNode.BlendedNoise(parameters, ValueType.FP64, EvaluationDomain.WORLD)).build(),
                NumericProfile.GPU_IEEE_BITS, 64);
        assertTrue(shader.source().contains("wg_noise_legacy_perlin_"));
        assertTrue(shader.source().contains("wg_noise_smeared_"));
        assertTrue(shader.source().contains("uvec2 yScale, uvec2 yMax"));
        assertTrue(shader.source().contains(
                "wg_noise_interpolate(uvec2 fx, uvec2 gradientFy, uvec2 blendFy, uvec2 fz"));
        assertTrue(shader.source().contains("wg_noise_smoothstep(blendFy)"));
        assertTrue(shader.source().contains("wg_noise_interpolate(fx, adjustedFy, fy, fz"));
        assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid());
    }

    @Test void capturedBlendedNoiseSelectsOneCapturedLevelPerLegacyOctave() {
        var identity = java.util.stream.IntStream.range(0, 256).boxed().toList();
        var reversed = java.util.stream.IntStream.iterate(255, value -> value - 1).limit(256).boxed().toList();
        var low = new NoiseParameters.ImprovedNoiseSnapshot(0.0, 0.0, 0.0, identity);
        var high = new NoiseParameters.ImprovedNoiseSnapshot(0.0, 0.0, 0.0, reversed);
        var levels = new java.util.ArrayList<NoiseParameters.ImprovedNoiseSnapshot>();
        for (int index = 0; index < 16; index++) levels.add(index == 15 ? low : high);
        var perlin = new NoiseParameters.PerlinNoiseSnapshot(15,
                java.util.Collections.nCopies(16, 1.0), levels);
        var parameters = new BlendedNoiseParameters(123L, perlin, perlin, perlin,
                1.0, 1.0, 8.555150000000001, 4.277575000000001, 2.0);
        var shader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity",
                        new ProgramNode.BlendedNoise(parameters, ValueType.FP64, EvaluationDomain.WORLD)).build(),
                NumericProfile.GPU_IEEE_BITS, 64);
        var matcher = java.util.regex.Pattern.compile(
                "legacySample([01]) = (wg_noise_legacy_perlin_\\d+)\\(").matcher(shader.source());
        var names = new java.util.HashMap<String, String>();
        while (matcher.find()) names.put(matcher.group(1), matcher.group(2));
        assertEquals(2, names.size());
        assertNotEquals(names.get("0"), names.get("1"),
                "each outer octave must address its selected captured level");
    }

    @Test void fp64MaterialClassificationStaysInTheBinary64Carrier() {
        var shader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity",
                        new ProgramNode.Constant(ValueType.FP64, 1.0e-50d, EvaluationDomain.BLOCK)).build(),
                NumericProfile.GPU_IEEE_BITS, 64);
        assertTrue(shader.source().contains("uvec2 density = wg_node_"));
        assertTrue(shader.source().contains("wg_material_decide64(density"));
        assertTrue(shader.source().contains("!wg_fp64_finite(density)"));
        assertFalse(shader.source().contains("uint density = wg_fp64_to_fp32"));
    }

    @Test void capturedBlendDensityEmitsDirectLookupAndWeightedWindow() {
        var root = new ProgramNode.BlendDensity(
                new ProgramNode.Constant(ValueType.FP64, 5.0, EvaluationDomain.BLOCK),
                ValueType.FP64, EvaluationDomain.BLOCK);
        var blend = new StructureBlendSnapshot("captured-boundary", java.util.Map.of(), java.util.Map.of(),
                BeardifierSnapshot.empty(),
                java.util.List.of(new StructureBlendSnapshot.DensitySample(2, 0, 0, 3.0)),
                java.util.List.of(new StructureBlendSnapshot.DirectDensitySample(-1, 0, 4, 0, 4, 1.0)));
        var shader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity", root).build(),
                NumericProfile.GPU_IEEE_BITS, 64, blend);
        assertTrue(shader.source().contains("wg_blend_density_"));
        assertTrue(shader.source().contains("wg_i32_floor_mod"));
        assertTrue(shader.source().contains("wg_fp64_sqrt"));
        assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid());
    }

    @Test void integerFloorAndCellHelpersNeverUseUndefinedSignedRemainders() {
        var shader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity",
                        new ProgramNode.Constant(ValueType.FP64, -1.0, EvaluationDomain.BLOCK)).build(),
                NumericProfile.GPU_IEEE_BITS, 64);
        assertFalse(shader.source().contains("left % right"));
        assertFalse(shader.source().contains("coordinate % cellSize"));
        assertTrue(shader.source().contains("leftMagnitude / rightMagnitude"));
        assertTrue(shader.source().contains("remainderMagnitude != 0u && oppositeSigns"));
        assertTrue(shader.source().contains("leftBits - wg_i32_floor_div(leftBits, rightBits) * rightBits"));
        assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid());
    }

    @Test void capturedBlendHeightEmitsDynamicAlphaAndOffsetTable() {
        var blend = new StructureBlendSnapshot("captured-height", java.util.Map.of(), java.util.Map.of(),
                BeardifierSnapshot.empty(), java.util.List.of(), java.util.List.of(),
                java.util.List.of(new StructureBlendSnapshot.HeightSample(0, 0, 200.0)),
                java.util.List.of(new StructureBlendSnapshot.DirectHeightSample(0, 0, 0, 0, 120.0)),
                true);
        var shader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity",
                        new ProgramNode.BlendAlpha(ValueType.FP64, EvaluationDomain.BLOCK)).build(),
                NumericProfile.GPU_IEEE_BITS, 64, blend);
        assertTrue(shader.source().contains("wg_blend_height_"));
        assertTrue(shader.source().contains("wg_fp64_floor"));
        assertTrue(shader.source().contains("wg_fp64_less_equal"));
        assertTrue(new SpirvNumericContract().inspect(shader.source(), NumericProfile.GPU_IEEE_BITS).valid());
    }
}
