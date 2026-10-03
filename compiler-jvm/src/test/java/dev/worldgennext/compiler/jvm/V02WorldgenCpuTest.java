// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.jvm;

import dev.worldgennext.compiler.jvm.worldgen.*;
import dev.worldgennext.semantic.program.*;
import dev.worldgennext.semantic.snapshot.WorldgenSnapshot;
import dev.worldgennext.semantic.snapshot.NoiseParameters;
import dev.worldgennext.semantic.snapshot.NoiseRouterSnapshot;
import dev.worldgennext.semantic.snapshot.GeneratorSettingsSnapshot;
import dev.worldgennext.semantic.snapshot.BlockStateDescriptor;
import dev.worldgennext.semantic.snapshot.StructureBlendSnapshot;
import dev.worldgennext.semantic.snapshot.BlockStateTraits;
import dev.worldgennext.semantic.snapshot.RegistrySnapshot;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class V02WorldgenCpuTest {
    @Test
    void capturedEntryPointRejectsTheSyntheticFallbackWhenRouterRootsAreMissing() {
        assertThrows(IllegalArgumentException.class, () -> new DenseNoiseGenerator()
                .generateCaptured(WorldgenSnapshot.builder(0, "minecraft:overworld").build(), 0, 0));
    }

    @Test void typedInterpreterKeepsOrderedInterpolation() {
        var root = new ProgramNode.Interpolated(new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.LATTICE), new InterpolationGeometry(4, 4), ValueType.FP64);
        var program = WorldgenProgram.builder().root("finalDensity", root).build();
        assertEquals(2.0, new WorldgenCpuCompiler().compile(program, "finalDensity").evaluateAt(2, 2, 2, new MarkerContext(0)), 1e-12);
    }

    @Test void directPointBoundaryLeavesInterpolationWrappersTransparent() {
        var root = new ProgramNode.Interpolated(
                new ProgramNode.Unary("square", ValueType.FP64, EvaluationDomain.LATTICE,
                        new ProgramNode.Input("y", ValueType.FP64, EvaluationDomain.LATTICE)),
                new InterpolationGeometry(4, 4), ValueType.FP64);
        var program = WorldgenProgram.builder().root("auxiliary", root).build();
        var interpreter = new WorldgenInterpreter();
        assertEquals(49.0, interpreter.evaluateAtDirect(program, "auxiliary", 0L, 2, 7, 3, new MarkerContext(0)), 0.0);
        assertEquals(52.0, interpreter.evaluateAt(program, "auxiliary", 0L, 2, 7, 3, new MarkerContext(0)), 0.0);
    }

    @Test void capturedInterpolationPreservesPerCornerCacheOnceValues() {
        var marker = new ProgramNode.Marker("once", "CACHE_ONCE", ValueType.FP64,
                EvaluationDomain.BLOCK, new ProgramNode.Input("y", ValueType.FP64, EvaluationDomain.BLOCK), Map.of());
        var program = WorldgenProgram.builder().root("finalDensity",
                new ProgramNode.Interpolated(marker, new InterpolationGeometry(4, 4), ValueType.FP64)).build();
        // NoiseChunk's CacheOnce.fillArray delegates to the wrapped function
        // for each corner; it must not reuse the lower Y corner for the upper
        // Y corner of an explicit interpolation cell.
        assertEquals(2.0, new WorldgenInterpreter().evaluateAtCaptured(program, "finalDensity", 0L,
                2, 2, 2, new MarkerContext(0)), 0.0);
    }

    @Test void capturedInterpolationUsesMinecraftXThenYThenZLerpOrder() {
        double[] corners = {5e-7, -390000, 300000, 3.4e-8, -500, 20000000000d, -4.3e-8, 1.2000000000000002e-7};
        // This fixture uses a direct corner lookup so the raw interpolation
        // result is checked independently of noise evaluation.
        var yInput = new ProgramNode.Input("y", ValueType.FP64, EvaluationDomain.LATTICE);
        var zInput = new ProgramNode.Input("z", ValueType.FP64, EvaluationDomain.LATTICE);
        var xInput = new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.LATTICE);
        var x0z0 = new ProgramNode.RangeChoice(yInput, 0.0, 8.0,
                new ProgramNode.Constant(ValueType.FP64, corners[0], EvaluationDomain.LATTICE),
                new ProgramNode.Constant(ValueType.FP64, corners[1], EvaluationDomain.LATTICE),
                ValueType.FP64, EvaluationDomain.LATTICE);
        var x0z1 = new ProgramNode.RangeChoice(yInput, 0.0, 8.0,
                new ProgramNode.Constant(ValueType.FP64, corners[2], EvaluationDomain.LATTICE),
                new ProgramNode.Constant(ValueType.FP64, corners[3], EvaluationDomain.LATTICE),
                ValueType.FP64, EvaluationDomain.LATTICE);
        var x1z0 = new ProgramNode.RangeChoice(yInput, 0.0, 8.0,
                new ProgramNode.Constant(ValueType.FP64, corners[4], EvaluationDomain.LATTICE),
                new ProgramNode.Constant(ValueType.FP64, corners[5], EvaluationDomain.LATTICE),
                ValueType.FP64, EvaluationDomain.LATTICE);
        var x1z1 = new ProgramNode.RangeChoice(yInput, 0.0, 8.0,
                new ProgramNode.Constant(ValueType.FP64, corners[6], EvaluationDomain.LATTICE),
                new ProgramNode.Constant(ValueType.FP64, corners[7], EvaluationDomain.LATTICE),
                ValueType.FP64, EvaluationDomain.LATTICE);
        var lookup = new ProgramNode.RangeChoice(xInput, 0.0, 4.0,
                new ProgramNode.RangeChoice(zInput, 0.0, 4.0, x0z0, x0z1,
                        ValueType.FP64, EvaluationDomain.LATTICE),
                new ProgramNode.RangeChoice(zInput, 0.0, 4.0, x1z0, x1z1,
                        ValueType.FP64, EvaluationDomain.LATTICE),
                ValueType.FP64, EvaluationDomain.LATTICE);
        var program = WorldgenProgram.builder().root("finalDensity",
                new ProgramNode.Interpolated(lookup, new InterpolationGeometry(4, 8), ValueType.FP64)).build();
        // The captured route's order is exercised by the live Minecraft
        // preflight; this assertion keeps the boundary flag on the intended
        // implementation without changing the v0.1 synthetic contract.
        assertEquals(0x41cbf0acd2d80002L, Double.doubleToRawLongBits(
                new WorldgenInterpreter().evaluateAtCaptured(program, "finalDensity", 0L,
                        1, 3, 2, new MarkerContext(0))));
    }

    @Test void denseGeneratorProducesCompleteGeometryAndMetadata() {
        var generated = new DenseNoiseGenerator().generate(WorldgenSnapshot.builder(0, "minecraft:overworld").build(), 0, 0);
        assertEquals(384 * 256, generated.states().length);
        assertEquals(256, generated.heightmaps().length);
        assertEquals(generated.states().length, generated.fluidMarks().length);
    }

    @Test void denseGeneratorExposesEveryNoiseHeightmapFamily() {
        var settings = new GeneratorSettingsSnapshot(0, 16, 16, 0,
                BlockStateDescriptor.of("minecraft:stone"),
                BlockStateDescriptor.of("minecraft:water"), false, false);
        var snapshot = WorldgenSnapshot.builder(0, "minecraft:overworld")
                .generatorSettings(settings)
                .router(completeRouter(new ProgramNode.Constant(ValueType.FP64, 1.0, EvaluationDomain.WORLD)))
                .build();

        var maps = new DenseNoiseGenerator().generate(snapshot, 0, 0).heightmapSet();

        assertEquals(java.util.Set.of("OCEAN_FLOOR_WG", "WORLD_SURFACE_WG", "MOTION_BLOCKING",
                "MOTION_BLOCKING_NO_LEAVES", "OCEAN_FLOOR", "WORLD_SURFACE"), maps.keySet());
        for (int[] values : maps.values()) {
            assertEquals(256, values.length);
            assertEquals(16, values[0]);
        }
    }

    @Test void heightmapsUseCapturedBlockStateTraitsInsteadOfNonAirShortcuts() {
        var air = BlockStateDescriptor.of("minecraft:air");
        var stone = BlockStateDescriptor.of("minecraft:stone");
        var torch = BlockStateDescriptor.of("minecraft:torch");
        var leaves = BlockStateDescriptor.of("minecraft:oak_leaves");
        var water = BlockStateDescriptor.of("minecraft:water");
        var registry = new RegistrySnapshot(java.util.List.of(air, stone, torch, leaves, water), stone, water,
                Map.of(torch.canonical(), new BlockStateTraits(false, false, false, false),
                        leaves.canonical(), new BlockStateTraits(false, false, false, true),
                        water.canonical(), new BlockStateTraits(false, false, true, false)));
        var settings = new GeneratorSettingsSnapshot(0, 16, 16, 4, stone, water, false, false);
        var snapshot = WorldgenSnapshot.builder(0, "minecraft:overworld").registry(registry)
                .generatorSettings(settings)
                .router(completeRouter(new ProgramNode.Constant(ValueType.FP64, 1.0, EvaluationDomain.WORLD)))
                .build();

        var states = new String[16 * 16 * 16];
        java.util.Arrays.fill(states, air.canonical());
        states[0] = torch.canonical();
        states[256] = leaves.canonical();
        states[512] = water.canonical();
        var altered = new DenseNoiseGenerator.GeneratedChunk(0, 0, 0, 16, states,
                new int[256], new boolean[states.length], snapshot.fingerprint());
        var maps = altered.heightmapSet(registry);

        assertEquals(0, maps.get("OCEAN_FLOOR_WG")[0]);
        assertEquals(3, maps.get("WORLD_SURFACE_WG")[0]);
        assertEquals(3, maps.get("MOTION_BLOCKING")[0]);
        assertEquals(3, maps.get("MOTION_BLOCKING_NO_LEAVES")[0]);
    }

    @Test void denseGeneratorUsesCapturedFinalDensityWhenAllRouterRootsArePresent() {
        var positive = completeRouter(new ProgramNode.Constant(ValueType.FP64, 2.0, EvaluationDomain.WORLD));
        var negative = completeRouter(new ProgramNode.Constant(ValueType.FP64, -2.0, EvaluationDomain.WORLD));
        var settings = new GeneratorSettingsSnapshot(0, 16, 16, 0, BlockStateDescriptor.of("minecraft:stone"),
                BlockStateDescriptor.of("minecraft:water"), false, false);
        var positiveSnapshot = WorldgenSnapshot.builder(0, "minecraft:overworld").router(positive)
                .generatorSettings(settings).build();
        var negativeSnapshot = WorldgenSnapshot.builder(0, "minecraft:overworld").router(negative)
                .generatorSettings(settings).build();
        var generator = new DenseNoiseGenerator();
        assertEquals("minecraft:stone", generator.generate(positiveSnapshot, 0, 0).state(0, 0, 0));
        // A disabled aquifer returns the global FluidStatus.at(y), which is
        // an explicit AIR state at/above sea level. It therefore wins the
        // material-rule list instead of falling through to default stone.
        assertEquals("minecraft:air", generator.generate(negativeSnapshot, 0, 0).state(0, 0, 0));
    }

    @Test void disabledAquiferUsesTheGlobalDefaultFluidPicker() {
        var states = java.util.List.of(
                BlockStateDescriptor.of("minecraft:air"),
                BlockStateDescriptor.of("minecraft:stone"),
                BlockStateDescriptor.of("minecraft:water"),
                BlockStateDescriptor.of("minecraft:lava"));
        var registry = new dev.worldgennext.semantic.snapshot.RegistrySnapshot(states, states.get(1), states.get(2));
        var settings = new GeneratorSettingsSnapshot(0, 16, 16, 3,
                BlockStateDescriptor.of("minecraft:stone"),
                BlockStateDescriptor.of("minecraft:water"), false, false);
        var snapshot = WorldgenSnapshot.builder(0, "minecraft:overworld")
                .router(completeRouter(new ProgramNode.Constant(ValueType.FP64, -1.0, EvaluationDomain.WORLD)))
                .registry(registry)
                .generatorSettings(settings)
                .build();

        var generated = new DenseNoiseGenerator().generateCaptured(snapshot, 0, 0);

        assertEquals("minecraft:water", generated.state(0, 0, 0));
        assertEquals("minecraft:water", generated.state(0, 2, 0));
        assertEquals("minecraft:air", generated.state(0, 3, 0));
        assertFalse(generated.fluidMarks()[0]);
    }

    @Test void capturedEntryPointRejectsMissingMaterialIdentityOrEnabledRandomState() {
        var settings = new GeneratorSettingsSnapshot(0, 16, 16, 0,
                BlockStateDescriptor.of("minecraft:stone"), BlockStateDescriptor.of("minecraft:water"), true, false);
        var noLava = WorldgenSnapshot.builder(0, "minecraft:overworld")
                .router(completeRouter(new ProgramNode.Constant(ValueType.FP64, 1.0, EvaluationDomain.WORLD)))
                .generatorSettings(settings)
                .build();
        assertThrows(IllegalArgumentException.class, () -> new DenseNoiseGenerator().generateCaptured(noLava, 0, 0));

        var states = java.util.List.of(
                BlockStateDescriptor.of("minecraft:air"),
                BlockStateDescriptor.of("minecraft:stone"),
                BlockStateDescriptor.of("minecraft:water"),
                BlockStateDescriptor.of("minecraft:lava"));
        var registry = new dev.worldgennext.semantic.snapshot.RegistrySnapshot(states, states.get(1), states.get(2));
        var noAquiferRandom = WorldgenSnapshot.builder(0, "minecraft:overworld")
                .router(completeRouter(new ProgramNode.Constant(ValueType.FP64, 1.0, EvaluationDomain.WORLD)))
                .registry(registry).generatorSettings(settings).build();
        assertThrows(IllegalArgumentException.class,
                () -> new DenseNoiseGenerator().generateCaptured(noAquiferRandom, 0, 0));
    }

    @Test void capturedEntryPointRejectsParameterOnlyNoiseInsteadOfUsingFixtureSampling() {
        var states = java.util.List.of(
                BlockStateDescriptor.of("minecraft:air"),
                BlockStateDescriptor.of("minecraft:stone"),
                BlockStateDescriptor.of("minecraft:water"),
                BlockStateDescriptor.of("minecraft:lava"));
        var registry = new dev.worldgennext.semantic.snapshot.RegistrySnapshot(states, states.get(1), states.get(2));
        var settings = new GeneratorSettingsSnapshot(0, 16, 16, 0,
                states.get(1), states.get(2), false, false);
        var noise = new ProgramNode.Noise("fixture-noise",
                new NoiseParameters("fixture-noise", 0, java.util.List.of(1.0), 0L),
                1.0, 1.0, ValueType.FP64, EvaluationDomain.WORLD);
        var snapshot = WorldgenSnapshot.builder(0, "minecraft:overworld")
                .router(completeRouter(noise)).registry(registry).generatorSettings(settings).build();
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new DenseNoiseGenerator().generateCaptured(snapshot, 0, 0));
        assertTrue(failure.getMessage().contains("noise tables"));
    }

    @Test void snapshotRejectsRandomStateFromAnotherWorld() {
        assertThrows(IllegalArgumentException.class, () -> new WorldgenSnapshot(
                1L, "minecraft:overworld", "settings", NoiseRouterSnapshot.empty(),
                dev.worldgennext.semantic.snapshot.RandomStateSnapshot.forSeed(2L),
                dev.worldgennext.semantic.snapshot.RegistrySnapshot.minimal(),
                GeneratorSettingsSnapshot.overworld(), StructureBlendSnapshot.empty(),
                java.util.List.of(), dev.worldgennext.semantic.identity.DynamicInputIdentity.empty(), "test-stack"));
    }

    @Test void simpleDisabledAquiferUsesThePinnedGlobalLavaBand() {
        var program = new dev.worldgennext.semantic.material.AquiferProgram(false, 16,
                "minecraft:water", "minecraft:lava", 63.0,
                dev.worldgennext.semantic.material.AquiferProgram.VANILLA_GLOBAL_LAVA_LEVEL);
        var evaluator = new AquiferEvaluator();

        assertEquals("minecraft:lava", evaluator.evaluate(program, 0L, 0, -55, 0, -1.0).state());
        assertEquals("minecraft:water", evaluator.evaluate(program, 0L, 0, -54, 0, -1.0).state());
        assertEquals("minecraft:water", evaluator.evaluate(program, 0L, 0, 62, 0, -1.0).state());
        assertEquals("minecraft:air", evaluator.evaluate(program, 0L, 0, 63, 0, -1.0).state());
    }

    @Test void denseGeneratorKeepsStorageHeightSeparateFromLogicalGenerationHeight() {
        var settings = new GeneratorSettingsSnapshot(0, 256, 128, 0,
                BlockStateDescriptor.of("minecraft:stone"), BlockStateDescriptor.of("minecraft:water"), false, false);
        var snapshot = WorldgenSnapshot.builder(0, "minecraft:the_nether")
                .router(completeRouter(new ProgramNode.Constant(ValueType.FP64, 1.0, EvaluationDomain.WORLD)))
                .generatorSettings(settings)
                .build();
        var generated = new DenseNoiseGenerator().generate(snapshot, 0, 0);
        assertEquals(256 * 256, generated.states().length);
        assertEquals("minecraft:stone", generated.state(0, 127, 0));
        assertEquals("minecraft:air", generated.state(0, 128, 0));
        assertEquals("minecraft:air", generated.state(0, 255, 0));
    }

    @Test void denseGeneratorRejectsChunkCoordinateMultiplicationOverflow() {
        var settings = new GeneratorSettingsSnapshot(0, 16, 16, 0,
                BlockStateDescriptor.of("minecraft:stone"), BlockStateDescriptor.of("minecraft:water"), false, false);
        var snapshot = WorldgenSnapshot.builder(0, "minecraft:overworld")
                .generatorSettings(settings)
                .router(completeRouter(new ProgramNode.Constant(ValueType.FP64, 1.0, EvaluationDomain.WORLD)))
                .build();
        assertThrows(ArithmeticException.class, () -> new DenseNoiseGenerator().generate(snapshot, 134_217_728, 0));
    }

    @Test void denseGeneratorRejectsUncapturedHeightAndBiomeBlending() {
        var blend = new StructureBlendSnapshot("old-height", Map.of(), Map.of(),
                dev.worldgennext.semantic.snapshot.BeardifierSnapshot.empty(), java.util.List.of(), java.util.List.of(), true);
        var snapshot = WorldgenSnapshot.builder(0, "minecraft:overworld")
                .structureBlend(blend)
                .router(completeRouter(new ProgramNode.Constant(ValueType.FP64, 1.0, EvaluationDomain.WORLD)))
                .build();
        assertThrows(UnsupportedOperationException.class,
                () -> new DenseNoiseGenerator().generateCaptured(snapshot, 0, 0));
    }

    @Test void aquiferUsesMinecraftWayBelowMinYSentinelForFiniteDryColumns() {
        var roots = new LinkedHashMap<String, ProgramNode>();
        for (String name : WorldgenProgram.ROUTER_ROOTS) {
            double value = name.equals("initialDensityWithoutJaggedness") ? 1.0 : 0.0;
            roots.put(name, new ProgramNode.Constant(ValueType.FP64, value, EvaluationDomain.WORLD));
        }
        var router = WorldgenProgram.builder().roots(roots).build();
        var aquifer = new AquiferEvaluator().evaluate(
                new dev.worldgennext.semantic.material.AquiferProgram(true, 16,
                        "minecraft:water[level=0]", "minecraft:lava[level=0]", 63.0, -54.0),
                router, dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot.legacy(0L),
                GeneratorSettingsSnapshot.overworld(), 0L, 0, 0, 0, -1.0);
        assertEquals(-32512.0, aquifer.level());
        assertFalse(aquifer.fluid());
        assertTrue(aquifer.aquiferCandidate());
        assertEquals("minecraft:air", aquifer.state());
    }

    @Test void aquiferPreliminaryScanTerminatesAtIntegerMinimumWorldBounds() {
        var roots = new LinkedHashMap<String, ProgramNode>();
        for (String name : WorldgenProgram.ROUTER_ROOTS) {
            roots.put(name, new ProgramNode.Constant(ValueType.FP64,
                    name.equals("initialDensityWithoutJaggedness") ? 0.0 : 0.0,
                    EvaluationDomain.WORLD));
        }
        var settings = new GeneratorSettingsSnapshot(Integer.MIN_VALUE, 16, 16, 0,
                BlockStateDescriptor.of("minecraft:stone"), BlockStateDescriptor.of("minecraft:water"), true, false);
        var program = new dev.worldgennext.semantic.material.AquiferProgram(true, 16,
                "minecraft:water", "minecraft:lava", 0.0, -54.0);
        assertDoesNotThrow(() -> new AquiferEvaluator().evaluate(program,
                WorldgenProgram.builder().roots(roots).build(),
                dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot.legacy(0L),
                settings, 0L, 0, 0, 0, -1.0));
    }

    @Test void aquiferPreliminarySurfaceUsesLogicalNoiseHeightInsteadOfStoragePadding() {
        var roots = new LinkedHashMap<String, ProgramNode>();
        for (String name : WorldgenProgram.ROUTER_ROOTS) {
            ProgramNode value = switch (name) {
                case "initialDensityWithoutJaggedness" ->
                        new ProgramNode.Input("y", ValueType.FP64, EvaluationDomain.WORLD);
                case "fluidLevelFloodedness" ->
                        new ProgramNode.Constant(ValueType.FP64, 0.25D, EvaluationDomain.WORLD);
                default -> new ProgramNode.Constant(ValueType.FP64, 0.0D, EvaluationDomain.WORLD);
            };
            roots.put(name, value);
        }
        var router = WorldgenProgram.builder().roots(roots).build();
        var settings = new GeneratorSettingsSnapshot(0, 32, 16, 63,
                BlockStateDescriptor.of("minecraft:stone"),
                BlockStateDescriptor.of("minecraft:water"), true, false);
        var program = new dev.worldgennext.semantic.material.AquiferProgram(true, 16,
                "minecraft:water", "minecraft:lava", 63.0, -54.0);

        var result = new AquiferEvaluator().evaluate(program, router,
                dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot.legacy(0L),
                settings, 0L, 0, 0, 0, -1.0);

        // Scanning logical height 16 keeps the global level at 63. Scanning
        // storage padding up to 32 would instead select the randomized level 20.
        assertTrue(result.fluid());
        assertEquals(63.0, result.level());
    }

    @Test void aquiferLavaTypeUsesAbsoluteThreshold() {
        var negative = aquiferRouterWithLavaNoise(-1.0);
        var positive = aquiferRouterWithLavaNoise(1.0);
        var program = new dev.worldgennext.semantic.material.AquiferProgram(true, 16,
                "minecraft:water[level=0]", "minecraft:lava[level=0]", 63.0, -54.0);
        var factory = dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot.legacy(0L);
        var settings = GeneratorSettingsSnapshot.overworld();
        var evaluator = new AquiferEvaluator();
        var water = evaluator.evaluate(program, negative, factory, settings, 0L, 0, -30, 0, -1.0);
        var lava = evaluator.evaluate(program, positive, factory, settings, 0L, 0, -30, 0, -1.0);
        assertEquals("minecraft:lava[level=0]", water.state());
        assertEquals("minecraft:lava[level=0]", lava.state());
    }

    @Test void aquiferFluidLevelUsesMinecraftsStrictAtBoundary() {
        var program = new dev.worldgennext.semantic.material.AquiferProgram(true, 16,
                "minecraft:water", "minecraft:lava", 0.0, -54.0);

        var result = new AquiferEvaluator().evaluate(program, 0L, 0, 0, 0, -1.0);

        assertFalse(result.fluid());
        assertEquals("minecraft:air", result.state());
    }

    @Test void aquiferDeepDarkBranchUsesTheFloatPromotedThresholds() {
        var roots = new LinkedHashMap<String, ProgramNode>();
        for (String name : WorldgenProgram.ROUTER_ROOTS) {
            double value = switch (name) {
                case "initialDensityWithoutJaggedness" -> 1.0;
                case "erosion" -> -0.224999997D;
                case "depth" -> 0.89999999D;
                case "fluidLevelFloodedness" -> 1.0;
                default -> 0.0;
            };
            roots.put(name, new ProgramNode.Constant(ValueType.FP64, value, EvaluationDomain.WORLD));
        }
        var router = WorldgenProgram.builder().roots(roots).build();
        var program = new dev.worldgennext.semantic.material.AquiferProgram(true, 16,
                "minecraft:water[level=0]", "minecraft:lava[level=0]", 63.0, -54.0);
        var result = new AquiferEvaluator().evaluate(program, router,
                dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot.legacy(0L),
                GeneratorSettingsSnapshot.overworld(), 0L, 0, 0, 0, -1.0);
        assertFalse(result.fluid());
        assertEquals("minecraft:air", result.state());
        assertEquals(-32512.0, result.level());
    }

    @Test void aquiferDeviceCaptureContainsStatusesForEverySelectableCell() {
        var router = WorldgenProgram.builder().roots(completeRouter(
                new ProgramNode.Constant(ValueType.FP64, 1.0, EvaluationDomain.WORLD)).roots()).build();
        var settings = new GeneratorSettingsSnapshot(-64, 384, 384, 63,
                BlockStateDescriptor.of("minecraft:stone"),
                BlockStateDescriptor.of("minecraft:water[level=0]"), true, false);
        var program = new dev.worldgennext.semantic.material.AquiferProgram(true, 16,
                "minecraft:water[level=0]", "minecraft:lava[level=0]", 63.0, -54.0);
        var evaluator = new AquiferEvaluator();
        var first = evaluator.captureDeviceCandidates(program, router,
                dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot.legacy(77L),
                settings, 77L, 0, 15, -64, 319, 0, 15);
        var second = evaluator.captureDeviceCandidates(program, router,
                dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot.legacy(77L),
                settings, 77L, 0, 15, -64, 319, 0, 15);

        // x/z cover three aquifer cells because the vanilla -5 offset crosses
        // both chunk edges; y covers 35 cells after the +/-1 candidate halo.
        assertEquals(3 * 35 * 3, first.size());
        assertEquals(first, second);
        assertTrue(first.stream().allMatch(candidate -> candidate.state() != null
                && candidate.level() >= -32512));
        assertTrue(first.stream().anyMatch(AquiferEvaluator.CapturedCandidate::fluid));
    }

    @Test void oreVeinifierPreservesRngOrderBoundsAndMaterialSelection() {
        var router = WorldgenProgram.builder()
                .root("veinToggle", new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.WORLD))
                .root("veinRidged", new ProgramNode.Constant(ValueType.FP64, -1.0d, EvaluationDomain.WORLD))
                .root("veinGap", new ProgramNode.Constant(ValueType.FP64, 0.0d, EvaluationDomain.WORLD))
                .build();
        var program = dev.worldgennext.semantic.material.OreVeinProgram.vanilla(99L);
        var factory = dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot.legacy(123L);
        var evaluator = new OreVeinEvaluator();

        var ironFiller = evaluator.evaluate(program, router, factory, 0L, -5, -20, 5, new MarkerContext(0));
        assertTrue(ironFiller.ore());
        assertEquals("minecraft:tuff", ironFiller.state());
        assertEquals("filler", ironFiller.branch());

        var copperOutside = evaluator.evaluate(program, router, factory, 0L, 5, -100, 5, new MarkerContext(0));
        assertFalse(copperOutside.ore());
        assertEquals("minecraft:air", copperOutside.state());

        var ironOutside = evaluator.evaluate(program, router, factory, 0L, -5, -7, 5, new MarkerContext(0));
        assertFalse(ironOutside.ore());
        assertEquals("minecraft:air", ironOutside.state());
    }

    @Test void oreVeinifierUsesCapturedInterpolationForNoiseChunkRoots() {
        var router = WorldgenProgram.builder()
                .root("veinToggle", new ProgramNode.Constant(ValueType.FP64, -1.0d, EvaluationDomain.WORLD))
                .root("veinRidged", new ProgramNode.Interpolated(
                        new ProgramNode.RangeChoice(
                                new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.BLOCK),
                                -6.0, -4.0,
                                new ProgramNode.Constant(ValueType.FP64, -1.0d, EvaluationDomain.WORLD),
                                new ProgramNode.Constant(ValueType.FP64, 1.0d, EvaluationDomain.WORLD),
                                ValueType.FP64, EvaluationDomain.BLOCK),
                        new InterpolationGeometry(4, 8), ValueType.FP64))
                .root("veinGap", new ProgramNode.Constant(ValueType.FP64, 0.0d, EvaluationDomain.WORLD))
                .build();

        var result = new OreVeinEvaluator().evaluate(dev.worldgennext.semantic.material.OreVeinProgram.vanilla(99L),
                router, dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot.legacy(123L),
                0L, -5, -20, 5, new MarkerContext(0));

        // At x=-5 the direct selector is in range, but both NoiseChunk cell
        // corners (-8 and -4) are out of range; the interpolated ridged root
        // therefore rejects the vein.
        assertFalse(result.ore());
        assertEquals("minecraft:air", result.state());
    }

    @Test void oreVeinifierKeepsGapEvaluationLazyAfterRichnessMiss() {
        var router = WorldgenProgram.builder()
                .root("veinToggle", new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.WORLD))
                .root("veinRidged", new ProgramNode.Constant(ValueType.FP64, -1.0d, EvaluationDomain.WORLD))
                .root("veinGap", new ProgramNode.Input("missing", ValueType.FP64, EvaluationDomain.WORLD))
                .build();
        var result = new OreVeinEvaluator().evaluate(dev.worldgennext.semantic.material.OreVeinProgram.vanilla(99L),
                router, dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot.legacy(123L),
                0L, -5, -20, 5, new MarkerContext(0));
        assertTrue(result.ore());
        assertEquals("minecraft:tuff", result.state());
        assertEquals("filler", result.branch());
    }

    @Test void xoroshiroAllZeroStateUsesMinecraftCanonicalDefaults() {
        var factory = dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot.xoroshiro(0L, 0L);
        assertEquals(-7046029254386353131L, factory.seedLo());
        assertEquals(7640891576956012809L, factory.seedHi());
        var random = new XoroshiroRandom(0L, 0L);
        assertEquals(factory.seedLo(), random.stateLo());
        assertEquals(factory.seedHi(), random.stateHi());
    }

    @Test void typedArithmeticKeepsFloatRoundingAndIntegerWrapping() {
        var floatRoot = new ProgramNode.Binary("add", ValueType.FP32, EvaluationDomain.BLOCK,
                new ProgramNode.Constant(ValueType.FP32, Float.MAX_VALUE, EvaluationDomain.WORLD),
                new ProgramNode.Constant(ValueType.FP32, Float.MAX_VALUE, EvaluationDomain.WORLD));
        var intRoot = new ProgramNode.Binary("add", ValueType.INT32, EvaluationDomain.BLOCK,
                new ProgramNode.Constant(ValueType.INT32, Integer.MAX_VALUE, EvaluationDomain.WORLD),
                new ProgramNode.Constant(ValueType.INT32, 1, EvaluationDomain.WORLD));
        var program = WorldgenProgram.builder().root("float", floatRoot).root("int", intRoot).build();
        var interpreter = new WorldgenInterpreter();
        assertEquals(Float.POSITIVE_INFINITY, interpreter.evaluate(program, "float", Map.of(), new MarkerContext(0)));
        assertEquals(Integer.MIN_VALUE, interpreter.evaluate(program, "int", Map.of(), new MarkerContext(0)));
    }

    @Test void logicalBranchesAreLazyAndDoNotEvaluateTheUnselectedInput() {
        var exploding = new ProgramNode.Input("missing", ValueType.BOOLEAN, EvaluationDomain.BLOCK);
        var root = new ProgramNode.Binary("and", ValueType.BOOLEAN, EvaluationDomain.BLOCK,
                new ProgramNode.Constant(ValueType.BOOLEAN, false, EvaluationDomain.WORLD), exploding);
        var program = WorldgenProgram.builder().root("flag", root).build();
        assertEquals(false, new WorldgenInterpreter().evaluate(program, "flag", Map.of(), new MarkerContext(0)));
    }

    @Test void ap2PreservesMinecraftLazyZeroAndBoundedMinMaxSemantics() {
        var missing = new ProgramNode.Input("missing", ValueType.FP64, EvaluationDomain.BLOCK);
        var zero = new ProgramNode.Ap2("multiply", ValueType.FP64, EvaluationDomain.BLOCK,
                new ProgramNode.Constant(ValueType.FP64, -0.0d, EvaluationDomain.WORLD), missing,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        var belowMinimum = new ProgramNode.Ap2("min", ValueType.FP64, EvaluationDomain.BLOCK,
                new ProgramNode.Constant(ValueType.FP64, -4.0d, EvaluationDomain.WORLD), missing,
                -3.0, 10.0);
        var aboveMaximum = new ProgramNode.Ap2("max", ValueType.FP64, EvaluationDomain.BLOCK,
                new ProgramNode.Constant(ValueType.FP64, 11.0d, EvaluationDomain.WORLD), missing,
                -3.0, 10.0);
        var program = WorldgenProgram.builder().root("zero", zero).root("below", belowMinimum).root("above", aboveMaximum).build();
        var interpreter = new WorldgenInterpreter();
        Object zeroValue = interpreter.evaluate(program, "zero", Map.of(), new MarkerContext(0));
        assertEquals(Double.doubleToRawLongBits(0.0d), Double.doubleToRawLongBits((Double) zeroValue));
        assertEquals(-4.0, interpreter.evaluate(program, "below", Map.of(), new MarkerContext(0)));
        assertEquals(11.0, interpreter.evaluate(program, "above", Map.of(), new MarkerContext(0)));

        var required = new ProgramNode.Ap2("multiply", ValueType.FP64, EvaluationDomain.BLOCK,
                new ProgramNode.Constant(ValueType.FP64, 2.0d, EvaluationDomain.WORLD),
                new ProgramNode.Constant(ValueType.FP64, 3.0d, EvaluationDomain.WORLD), 0.0, 3.0);
        var requiredProgram = WorldgenProgram.builder().root("required", required).build();
        assertEquals(6.0, interpreter.evaluate(requiredProgram, "required", Map.of(), new MarkerContext(0)));
    }

    @Test void typedInterpolationReturnsFloatAndUsesNegativeFloorCells() {
        var root = new ProgramNode.Interpolated(new ProgramNode.Input("x", ValueType.FP32, EvaluationDomain.LATTICE), new InterpolationGeometry(4, 4), ValueType.FP32);
        var program = WorldgenProgram.builder().root("finalDensity", root).build();
        var value = new WorldgenInterpreter().evaluate(program, "finalDensity", Map.of("x", -1, "y", 0, "z", 0), new MarkerContext(0));
        assertEquals(Float.class, value.getClass());
        assertEquals(-1.0f, (Float) value, 0.0001f);
    }

    @Test void markerCacheOnceUsesOneValuePerInterpolationCell() {
        var child = new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.BLOCK);
        var marked = new ProgramNode.Marker("cached", "ONCE", ValueType.FP64, EvaluationDomain.BLOCK, child, Map.of());
        var root = new ProgramNode.Interpolated(marked, new InterpolationGeometry(4, 4), ValueType.FP64);
        var program = WorldgenProgram.builder().root("finalDensity", root).build();
        var markers = new MarkerContext(0);
        var interpreter = new WorldgenInterpreter();
        assertEquals(0.0, interpreter.evaluateAt(program, "finalDensity", 0L, 1, 0, 0, markers));
        assertEquals(4.0, interpreter.evaluateAt(program, "finalDensity", 0L, 5, 0, 0, markers));
    }

    @Test void nestedMarkersCanPopulateTheRequestCacheWithoutMapMutationFailure() {
        var inner = new ProgramNode.Marker("inner", "CACHE_2D", ValueType.FP64,
                EvaluationDomain.BLOCK, new ProgramNode.Constant(ValueType.FP64, 3.0, EvaluationDomain.BLOCK), Map.of());
        var outer = new ProgramNode.Marker("outer", "CACHE_2D", ValueType.FP64,
                EvaluationDomain.BLOCK, inner, Map.of());
        var program = WorldgenProgram.builder().root("finalDensity", outer).build();
        var markers = new MarkerContext(0);
        var interpreter = new WorldgenInterpreter();
        assertEquals(3.0, interpreter.evaluateAt(program, "finalDensity", 0L, 4, 12, -8, markers));
        assertEquals(3.0, interpreter.evaluateAt(program, "finalDensity", 0L, 4, 20, -8, markers));
    }

    @Test void rawMinecraftMarkerKindsRemainTransparentAtDirectPointBoundary() {
        var interpreter = new WorldgenInterpreter();
        for (String mode : java.util.List.of("ONCE", "ALL_IN_CELL", "CACHE2D", "FLATCACHE")) {
            var marker = new ProgramNode.Marker("marker/" + mode, mode, ValueType.FP64,
                    EvaluationDomain.BLOCK,
                    new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.BLOCK),
                    Map.of("mode", mode));
            var program = WorldgenProgram.builder().root("finalDensity", marker).build();
            assertEquals(13.0, interpreter.evaluateAt(program, "finalDensity", 17L, 13, -7, 4,
                    new MarkerContext(0)), 0.0, mode);
        }
    }

    @Test void capturedDirectBoundaryPreservesFlatCacheQuantization() {
        var marker = new ProgramNode.Marker("flat", "FLATCACHE", ValueType.FP64,
                EvaluationDomain.BLOCK,
                new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.BLOCK), Map.of());
        var program = WorldgenProgram.builder().root("auxiliary", marker).build();
        var interpreter = new WorldgenInterpreter();
        assertEquals(16.0, interpreter.evaluateAtCapturedDirect(program, "auxiliary", 7L,
                17, 13, 4, new MarkerContext(0)), 0.0);
        assertEquals(17.0, interpreter.evaluateAtDirect(program, "auxiliary", 7L,
                17, 13, 4, new MarkerContext(0)), 0.0);
    }

    @Test void capturedRawBoundaryLeavesEveryMinecraftMarkerTransparent() {
        var interpreter = new WorldgenInterpreter();
        for (String mode : java.util.List.of("ONCE", "ALL_IN_CELL", "CACHE2D", "FLATCACHE")) {
            var marker = new ProgramNode.Marker("raw/" + mode, mode, ValueType.FP64,
                    EvaluationDomain.BLOCK,
                    new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.BLOCK),
                    Map.of("mode", mode));
            var program = WorldgenProgram.builder().root("auxiliary", marker).build();
            assertEquals(13.0, interpreter.evaluateAtCapturedRaw(program, "auxiliary", 17L,
                    13, -7, 4, new MarkerContext(0)), 0.0, mode);
        }
    }

    @Test void capturedFlatCacheUsesTheWrappedFunctionOutsideItsNoiseChunkRange() {
        var marker = new ProgramNode.Marker("flat-range", "FLATCACHE", ValueType.FP64,
                EvaluationDomain.BLOCK,
                new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.BLOCK), Map.of());
        var program = WorldgenProgram.builder().root("auxiliary", marker).build();
        var interpreter = new WorldgenInterpreter();
        var bounds = new MarkerContext.FlatCacheBounds(0, 0, 4);
        var markers = new MarkerContext(0, StructureBlendSnapshot.empty(), bounds);

        assertEquals(16.0, interpreter.evaluateAtCapturedDirect(program, "auxiliary", 7L,
                17, 13, 4, markers), 0.0);
        assertEquals(20.0, interpreter.evaluateAtCapturedDirect(program, "auxiliary", 7L,
                20, 13, 4, markers), 0.0);
    }

    @Test void cache2dKeepsTheColumnDomainButNotTheVerticalCoordinate() {
        var marked = new ProgramNode.Marker("column", "CACHE_2D", ValueType.FP64,
                EvaluationDomain.BLOCK,
                new ProgramNode.Input("y", ValueType.FP64, EvaluationDomain.BLOCK),
                Map.of());
        var program = WorldgenProgram.builder().root("finalDensity", marked).build();
        var markers = new MarkerContext(0);
        var interpreter = new WorldgenInterpreter();
        assertEquals(10.0, interpreter.evaluateAt(program, "finalDensity", 7L, 3, 10, 5, markers));
        assertEquals(10.0, interpreter.evaluateAt(program, "finalDensity", 7L, 3, 20, 5, markers));
        assertEquals(30.0, interpreter.evaluateAt(program, "finalDensity", 7L, 4, 30, 5, markers));
    }

    @Test void cache2dRetainsOnlyTheMostRecentColumn() {
        var marked = new ProgramNode.Marker("column-last", "CACHE_2D", ValueType.FP64,
                EvaluationDomain.BLOCK, new ProgramNode.Input("y", ValueType.FP64, EvaluationDomain.BLOCK), Map.of());
        var program = WorldgenProgram.builder().root("finalDensity", marked).build();
        var markers = new MarkerContext(0);
        var interpreter = new WorldgenInterpreter();
        assertEquals(10.0, interpreter.evaluateAt(program, "finalDensity", 7L, 3, 10, 5, markers));
        assertEquals(20.0, interpreter.evaluateAt(program, "finalDensity", 4L, 4, 20, 5, markers));
        assertEquals(30.0, interpreter.evaluateAt(program, "finalDensity", 7L, 3, 30, 5, markers));
    }

    @Test void flatCacheSamplesAtTheQuantizedHorizontalPointAndYZero() {
        var marked = new ProgramNode.Marker("flat", "FLAT_CACHE", ValueType.FP64,
                EvaluationDomain.BLOCK,
                new ProgramNode.Input("y", ValueType.FP64, EvaluationDomain.BLOCK),
                Map.of());
        var program = WorldgenProgram.builder().root("finalDensity",
                new ProgramNode.Interpolated(marked, new InterpolationGeometry(4, 4), ValueType.FP64)).build();
        var markers = new MarkerContext(0);
        assertEquals(0.0, new WorldgenInterpreter().evaluateAt(program, "finalDensity", 7L, 3, 10, 5, markers));
        assertEquals(0.0, new WorldgenInterpreter().evaluateAt(program, "finalDensity", 7L, 4, 20, 5, markers));
    }

    @Test void unknownMarkerCacheModesFailClosedInsteadOfBecomingTransparent() {
        var marker = new ProgramNode.Marker("unknown", "future_cache_mode", ValueType.FP64,
                EvaluationDomain.BLOCK,
                new ProgramNode.Constant(ValueType.FP64, 1.0, EvaluationDomain.BLOCK), Map.of());
        var program = WorldgenProgram.builder().root("finalDensity", marker).build();
        var failure = assertThrows(UnsupportedOperationException.class, () -> new WorldgenInterpreter()
                .evaluateAt(program, "finalDensity", 0L, 0, 0, 0, new MarkerContext(0)));
        assertTrue(failure.getMessage().contains("future_cache_mode"));
    }

    @Test void capturedNoiseUsesWorldSeedAndPreservesScaleInputs() {
        var parameters = new NoiseParameters("test/noise", 0, java.util.List.of(1.0), 0L);
        var root = new ProgramNode.Noise("test/noise", parameters, 0.5f, 2.0f, ValueType.FP64, EvaluationDomain.WORLD);
        var program = WorldgenProgram.builder().root("finalDensity", root).build();
        double expected = new NoiseEvaluator().sample(parameters, 1234L, 2 * 0.5, 3 * 2.0, 4 * 0.5);
        assertEquals(expected, new WorldgenInterpreter().evaluateAt(program, "finalDensity", 1234L, 2, 3, 4, new MarkerContext(0)), 0.0);
    }

    @Test void capturedPerlinUsesMinecraftsSignedFirstOctaveConvention() {
        var permutation = java.util.stream.IntStream.range(0, 256).boxed().toList();
        var level = new NoiseParameters.ImprovedNoiseSnapshot(0.0, 0.0, 0.0, permutation);
        var perlin = new NoiseParameters.PerlinNoiseSnapshot(-1, java.util.List.of(1.0), java.util.List.of(level));
        var empty = new NoiseParameters.PerlinNoiseSnapshot(0, java.util.List.of(0.0),
                java.util.Collections.singletonList(null));
        var captured = new NoiseParameters.CapturedNoise(1234L, 1.0, perlin, empty);
        var parameters = new NoiseParameters("minecraft:test", -1, java.util.List.of(1.0), 0L, captured);
        double x = 0.25, y = 0.5, z = 0.75;
        double expected = expectedPerlin(perlin, x, y, z);
        double actual = new NoiseEvaluator().sample(parameters, 1234L, x, y, z);
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
    }

    @Test void capturedNoiseRejectsAWorldSeedMismatchInsteadOfFallingBack() {
        var permutation = java.util.stream.IntStream.range(0, 256).boxed().toList();
        var level = new NoiseParameters.ImprovedNoiseSnapshot(0.0, 0.0, 0.0, permutation);
        var perlin = new NoiseParameters.PerlinNoiseSnapshot(0, java.util.List.of(1.0), java.util.List.of(level));
        var captured = new NoiseParameters.CapturedNoise(1234L, 1.0, perlin, perlin);
        var parameters = new NoiseParameters("minecraft:test", 0, java.util.List.of(1.0), 0L, captured);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new NoiseEvaluator().sample(parameters, 1235L, 0.25, 0.5, 0.75));
        assertTrue(failure.getMessage().contains("seed"));
    }

    @Test void shiftBUsesMinecraftsZX0CoordinatePermutation() {
        var parameters = new NoiseParameters("test/shift", 0, java.util.List.of(1.0), 0L);
        var root = new ProgramNode.Shift("test/shift", parameters, "ZX0", 0.25f,
                ValueType.FP64, EvaluationDomain.WORLD);
        var program = WorldgenProgram.builder().root("finalDensity", root).build();
        double expected = new NoiseEvaluator().sample(parameters, 99L, 4 * 0.25, 2 * 0.25, 0.0) * 4.0;
        assertEquals(expected, new WorldgenInterpreter().evaluateAt(program, "finalDensity", 99L, 2, 3, 4,
                new MarkerContext(0)), 0.0);
    }

    @Test void capturedSplineAndWeirdSamplerUseTypedReplayNodes() {
        var coordinate = new ProgramNode.Input("x", ValueType.FP64, EvaluationDomain.WORLD);
        var spline = new ProgramNode.Spline(new ProgramNode.SplineMultipoint(coordinate,
                java.util.List.of(0.0f, 4.0f),
                java.util.List.of(new ProgramNode.SplineConstant(0.0f), new ProgramNode.SplineConstant(2.0f)),
                java.util.List.of(0.0f, 0.0f)), ValueType.FP64, EvaluationDomain.WORLD);
        var noiseParameters = new NoiseParameters("test/weird", 0, java.util.List.of(1.0), 0L);
        var weird = new ProgramNode.WeirdScaledSampler(new ProgramNode.Constant(ValueType.FP64, -1.0, EvaluationDomain.WORLD),
                noiseParameters, "TYPE1", ValueType.FP64, EvaluationDomain.WORLD);
        var program = WorldgenProgram.builder().root("spline", spline).root("weird", weird).build();
        var interpreter = new WorldgenInterpreter();
        assertEquals(1.0, interpreter.evaluateAt(program, "spline", 0L, 2, 0, 0, new MarkerContext(0)), 0.0);
        double rarity = 0.75;
        double expected = rarity * Math.abs(new NoiseEvaluator().sample(noiseParameters, 7L, 4 / rarity, 5 / rarity, 6 / rarity));
        assertEquals(expected, interpreter.evaluateAt(program, "weird", 7L, 4, 5, 6, new MarkerContext(0)), 0.0);
    }

    @Test void beardifierUsesMinecraftPrecomputedFloatKernel() {
        var rigid = new dev.worldgennext.semantic.snapshot.BeardifierSnapshot.Rigid(
                0, 0, 0, 10, 10, 10,
                dev.worldgennext.semantic.snapshot.BeardifierSnapshot.Adjustment.BEARD_BOX, 0);
        var blend = new StructureBlendSnapshot("beard", Map.of(), Map.of(),
                new dev.worldgennext.semantic.snapshot.BeardifierSnapshot(java.util.List.of(rigid), java.util.List.of()));
        double half = 2.5;
        double distanceSquared = half * half;
        long bits = Double.doubleToRawLongBits(distanceSquared / 2.0);
        bits = 6910469410427058090L - (bits >> 1);
        double inverse = Double.longBitsToDouble(bits);
        inverse *= 1.5 - 0.5 * (distanceSquared / 2.0) * inverse * inverse;
        double expected = -half * inverse / 2.0
                * (float) Math.pow(Math.E, -0.25 / 16.0) * 0.8;
        assertEquals(Double.doubleToRawLongBits(expected),
                Double.doubleToRawLongBits(new BeardifierEvaluator().compute(blend, 1, 2, 3)));
    }

    @Test void beardifierProgramNodeReadsTheRequestStructureSnapshot() {
        var rigid = new dev.worldgennext.semantic.snapshot.BeardifierSnapshot.Rigid(
                0, 0, 0, 10, 10, 10,
                dev.worldgennext.semantic.snapshot.BeardifierSnapshot.Adjustment.BEARD_BOX, 0);
        var blend = new StructureBlendSnapshot("beard-node", Map.of(), Map.of(),
                new dev.worldgennext.semantic.snapshot.BeardifierSnapshot(java.util.List.of(rigid), java.util.List.of()));
        var program = WorldgenProgram.builder().root("beard", new ProgramNode.Beardifier(
                ValueType.FP64, EvaluationDomain.BLOCK)).build();
        double expected = new BeardifierEvaluator().compute(blend, 1, 2, 3);
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(
                new WorldgenInterpreter().evaluateAt(program, "beard", 7L, 1, 2, 3, new MarkerContext(0, blend))));
    }

    @Test void blendDensityUsesDirectAndWeightedCapturedOldGenerationSamples() {
        var blend = new StructureBlendSnapshot("old-boundary",
                Map.of(), Map.of(), dev.worldgennext.semantic.snapshot.BeardifierSnapshot.empty(),
                java.util.List.of(
                        new StructureBlendSnapshot.DensitySample(0, 0, 0, 1.0),
                        new StructureBlendSnapshot.DensitySample(2, 0, 0, 3.0)));
        var root = new ProgramNode.BlendDensity(
                new ProgramNode.Constant(ValueType.FP64, 5.0, EvaluationDomain.BLOCK),
                ValueType.FP64, EvaluationDomain.BLOCK);
        var program = WorldgenProgram.builder().root("density", root).build();
        var interpreter = new WorldgenInterpreter();
        assertEquals(1.0, interpreter.evaluateAt(program, "density", 0L, 0, 0, 0,
                new MarkerContext(0, blend)), 0.0, "exact old-generation sample wins");
        assertEquals(3.0, interpreter.evaluateAt(program, "density", 0L, 4, 0, 0,
                new MarkerContext(0, blend)), 0.0, "weighted samples use Minecraft's lerp order");
        assertEquals(5.0, interpreter.evaluateAt(program, "density", 0L, 100, 0, 100,
                new MarkerContext(0, StructureBlendSnapshot.empty())), 0.0);
    }

    @Test void blendDensityUsesMinecraftDirectSectionFallbackAndExclusiveUpperYWindow() {
        var blend = new StructureBlendSnapshot("old-boundary", Map.of(), Map.of(),
                dev.worldgennext.semantic.snapshot.BeardifierSnapshot.empty(),
                java.util.List.of(
                        new StructureBlendSnapshot.DensitySample(0, 0, 0, 2.0),
                        new StructureBlendSnapshot.DensitySample(1, 1, 0, 100.0)),
                java.util.List.of(new StructureBlendSnapshot.DirectDensitySample(-1, 0, 4, 0, 0, 1.0)));
        var root = new ProgramNode.BlendDensity(
                new ProgramNode.Constant(ValueType.FP64, 5.0, EvaluationDomain.BLOCK),
                ValueType.FP64, EvaluationDomain.BLOCK);
        var program = WorldgenProgram.builder().root("density", root).build();
        var interpreter = new WorldgenInterpreter();
        assertEquals(1.0, interpreter.evaluateAt(program, "density", 0L, 0, 0, 0,
                new MarkerContext(0, blend)), 0.0);
        // At quart (1,0), only cellY 0 is in Blender's [cellY-1,cellY]
        // iterator window; the cellY 1 sample must not affect the result.
        assertEquals(3.0, interpreter.evaluateAt(program, "density", 0L, 4, 0, 0,
                new MarkerContext(0, blend)), 0.0);
    }

    @Test void blendAlphaAndOffsetUseCapturedHeightLookupBeforeWeightedSearch() {
        var blend = new StructureBlendSnapshot("old-height", Map.of(), Map.of(),
                dev.worldgennext.semantic.snapshot.BeardifierSnapshot.empty(),
                java.util.List.of(), java.util.List.of(),
                java.util.List.of(new StructureBlendSnapshot.HeightSample(0, 0, 200.0)),
                java.util.List.of(new StructureBlendSnapshot.DirectHeightSample(0, 0, 0, 0, 120.0)),
                true);
        var program = WorldgenProgram.builder()
                .root("alpha", new ProgramNode.BlendAlpha(ValueType.FP64, EvaluationDomain.BLOCK))
                .root("offset", new ProgramNode.BlendOffset(ValueType.FP64, EvaluationDomain.BLOCK))
                .build();
        var interpreter = new WorldgenInterpreter();
        var markers = new MarkerContext(0, blend);
        assertEquals(0.0, interpreter.evaluateAt(program, "alpha", 0L, 0, 0, 0, markers), 0.0);
        assertEquals(-240.0 / 3904.0,
                interpreter.evaluateAt(program, "offset", 0L, 0, 0, 0, markers), 0.0);
    }

    private static NoiseRouterSnapshot completeRouter(ProgramNode finalDensity) {
        var roots = new LinkedHashMap<String, ProgramNode>();
        for (String name : WorldgenProgram.ROUTER_ROOTS) roots.put(name,
                name.equals("finalDensity") ? finalDensity : new ProgramNode.Constant(ValueType.FP64, 0.0, EvaluationDomain.WORLD));
        return new NoiseRouterSnapshot(roots);
    }

    private static WorldgenProgram aquiferRouterWithLavaNoise(double lava) {
        var roots = new LinkedHashMap<String, ProgramNode>();
        for (String name : WorldgenProgram.ROUTER_ROOTS) {
            double value = switch (name) {
                case "initialDensityWithoutJaggedness" -> 1.0;
                case "fluidLevelFloodedness" -> 0.5;
                case "lava" -> lava;
                default -> 0.0;
            };
            roots.put(name, new ProgramNode.Constant(ValueType.FP64, value, EvaluationDomain.WORLD));
        }
        return WorldgenProgram.builder().roots(roots).build();
    }

    private static double expectedPerlin(NoiseParameters.PerlinNoiseSnapshot noise, double x, double y, double z) {
        double inputFactor = Math.scalb(1.0, noise.firstOctave());
        double valueFactor = Math.scalb(1.0, noise.levels().size() - 1)
                / (Math.scalb(1.0, noise.levels().size()) - 1.0);
        double result = 0.0;
        for (int index = 0; index < noise.levels().size(); index++) {
            var level = noise.levels().get(index);
            if (level != null) result += noise.amplitudes().get(index)
                    * expectedImproved(level, wrap(x * inputFactor), wrap(y * inputFactor), wrap(z * inputFactor))
                    * valueFactor;
            inputFactor *= 2.0;
            valueFactor /= 2.0;
        }
        return result;
    }

    private static double expectedImproved(NoiseParameters.ImprovedNoiseSnapshot noise, double x, double y, double z) {
        double sx = x + noise.xOffset(), sy = y + noise.yOffset(), sz = z + noise.zOffset();
        int x0 = (int) Math.floor(sx), y0 = (int) Math.floor(sy), z0 = (int) Math.floor(sz);
        double fx = sx - x0, fy = sy - y0, fz = sz - z0;
        var permutation = noise.permutation();
        int xHash = permutation.get(x0 & 255), xNext = permutation.get((x0 + 1) & 255);
        int xy00 = permutation.get((xHash + y0) & 255), xy01 = permutation.get((xHash + y0 + 1) & 255);
        int xy10 = permutation.get((xNext + y0) & 255), xy11 = permutation.get((xNext + y0 + 1) & 255);
        double v000 = gradient(permutation.get((xy00 + z0) & 255), fx, fy, fz);
        double v100 = gradient(permutation.get((xy10 + z0) & 255), fx - 1.0, fy, fz);
        double v010 = gradient(permutation.get((xy01 + z0) & 255), fx, fy - 1.0, fz);
        double v110 = gradient(permutation.get((xy11 + z0) & 255), fx - 1.0, fy - 1.0, fz);
        double v001 = gradient(permutation.get((xy00 + z0 + 1) & 255), fx, fy, fz - 1.0);
        double v101 = gradient(permutation.get((xy10 + z0 + 1) & 255), fx - 1.0, fy, fz - 1.0);
        double v011 = gradient(permutation.get((xy01 + z0 + 1) & 255), fx, fy - 1.0, fz - 1.0);
        double v111 = gradient(permutation.get((xy11 + z0 + 1) & 255), fx - 1.0, fy - 1.0, fz - 1.0);
        double bx = smooth(fx), by = smooth(fy), bz = smooth(fz);
        return lerp(bz, lerp(by, lerp(bx, v000, v100), lerp(bx, v010, v110)),
                lerp(by, lerp(bx, v001, v101), lerp(bx, v011, v111)));
    }

    private static double wrap(double value) { return value - Math.floor(value / 33554432.0 + 0.5) * 33554432.0; }
    private static double smooth(double value) { return value * value * value * (value * (value * 6.0 - 15.0) + 10.0); }
    private static double lerp(double t, double a, double b) { return a + t * (b - a); }
    private static double gradient(int hash, double x, double y, double z) {
        int[][] gradients = {{1,1,0},{-1,1,0},{1,-1,0},{-1,-1,0},{1,0,1},{-1,0,1},{1,0,-1},{-1,0,-1},
                {0,1,1},{0,-1,1},{0,1,-1},{0,-1,-1},{1,1,0},{0,-1,1},{-1,1,0},{0,-1,-1}};
        int[] value = gradients[hash & 15];
        return value[0] * x + value[1] * y + value[2] * z;
    }
}
