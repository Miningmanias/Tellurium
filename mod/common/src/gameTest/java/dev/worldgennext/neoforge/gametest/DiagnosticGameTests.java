// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.gametest;

import dev.worldgennext.frontend.mc1211.LoweringResult;
import dev.worldgennext.frontend.mc1211.DensityNodeLowerer;
import dev.worldgennext.frontend.mc1211.Minecraft1211Frontend;
import dev.worldgennext.compiler.jvm.worldgen.WorldgenInterpreter;
import dev.worldgennext.semantic.program.MarkerContext;
import dev.worldgennext.semantic.program.WorldgenProgram;
import dev.worldgennext.neoforge.DiagnosticSelfTest;
import dev.worldgennext.neoforge.snapshot.DensityNodeReader;
import dev.worldgennext.neoforge.snapshot.MinecraftSnapshotReader;
import dev.worldgennext.semantic.identity.DynamicInputIdentity;
import dev.worldgennext.semantic.snapshot.GeneratorSettingsSnapshot;
import dev.worldgennext.semantic.snapshot.RegistrySnapshot;
import dev.worldgennext.semantic.snapshot.StructureBlendSnapshot;
import dev.worldgennext.neoforge.legacy.StagedRoute;
import dev.worldgennext.neoforge.runtime.QualifiedHookEvidence;
import dev.worldgennext.semantic.WorldgenIdentity;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import dev.worldgennext.material.chunk.BlockStateTable;
import dev.worldgennext.material.chunk.ChunkNoiseResult;
import dev.worldgennext.material.chunk.ChunkResultHeader;
import dev.worldgennext.material.chunk.HeightmapPayload;
import dev.worldgennext.neoforge.runtime.ChunkMutationJournal;
import dev.worldgennext.neoforge.runtime.MinecraftChunkApplier;
import dev.worldgennext.neoforge.runtime.MinecraftChunkCommitter;
import dev.worldgennext.neoforge.runtime.MinecraftOwnershipToken;
import dev.worldgennext.engine.worldgen.CommitCoordinator;
import dev.worldgennext.engine.worldgen.CommitToken;
import dev.worldgennext.semantic.execution.ExecutionReceipt;
import dev.worldgennext.semantic.identity.ContextIdentity;
import dev.worldgennext.semantic.program.NumericProfile;
import dev.worldgennext.semantic.program.WorldgenProgram;
import dev.worldgennext.semantic.snapshot.WorldgenSnapshot;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;

@Mod("worldgennext_test")
@GameTestHolder("worldgennext_test")
@PrefixGameTestTemplate(false)
public final class DiagnosticGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public void packagedCoreAvailable(GameTestHelper helper) {
        helper.assertTrue(ModList.get().isLoaded(dev.worldgennext.neoforge.WorldgenNextMod.MOD_ID), "Main diagnostics mod must be loaded");
        var result = DiagnosticSelfTest.run();
        helper.assertTrue(result.passed(), "Synthetic core selftest failed: " + result);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public void realProtoChunkApplicationAndRollback(GameTestHelper helper) {
        var level = helper.getLevel();
        var target = new ProtoChunk(ChunkPos.ZERO, UpgradeData.EMPTY,
                LevelHeightAccessor.create(0, 16),
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        target.setPersistedStatus(ChunkStatus.BIOMES);
        target.setUnsaved(false);

        var table = BlockStateTable.fromRegistry(RegistrySnapshot.minimal());
        String[] states = new String[4096];
        java.util.Arrays.fill(states, "minecraft:air");
        states[0] = "minecraft:stone";
        boolean[] marks = new boolean[4096];
        marks[0] = true;
        int[] surface = new int[256];
        int[] floor = new int[256];
        surface[0] = 1;
        floor[0] = 1;
        var maps = new java.util.LinkedHashMap<String, int[]>();
        maps.put("WORLD_SURFACE_WG", surface);
        maps.put("OCEAN_FLOOR_WG", floor);
        var result = ChunkNoiseResult.ofDense(
                new ChunkResultHeader(0, 0, 0, 16, "context", table.fingerprint(), "4.0"),
                table, states, new HeightmapPayload(maps), marks);
        var journal = new ChunkMutationJournal();
        var receipt = new MinecraftChunkApplier(MinecraftChunkApplier.fromBlocks(
                level.registryAccess().lookupOrThrow(Registries.BLOCK))).apply(target, result, journal);
        helper.assertTrue(receipt.blocksChanged() == 1, "Expected one changed block: " + receipt);
        helper.assertTrue(target.getBlockState(new BlockPos(0, 0, 0)).is(net.minecraft.world.level.block.Blocks.STONE), "Stone was not applied");
        helper.assertTrue(target.getPostProcessing()[0] != null && target.getPostProcessing()[0].size() == 1, "Fluid mark was not applied");
        journal.rollback();
        helper.assertTrue(target.getBlockState(new BlockPos(0, 0, 0)).isAir(), "Rollback did not restore air");
        helper.assertTrue(!target.isUnsaved(), "Rollback did not restore dirty state");
        helper.assertTrue(!target.hasPrimedHeightmap(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG),
                "Rollback retained a heightmap created by the transaction");
        helper.assertTrue(!target.hasPrimedHeightmap(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG),
                "Rollback retained an ocean-floor heightmap created by the transaction");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public void authoritativeCommitLinksOwnershipReceiptAndSingleUseToken(GameTestHelper helper) {
        var level = helper.getLevel();
        var target = new ProtoChunk(ChunkPos.ZERO, UpgradeData.EMPTY,
                LevelHeightAccessor.create(0, 16),
                level.registryAccess().registryOrThrow(Registries.BIOME), null);
        target.setPersistedStatus(ChunkStatus.BIOMES);
        target.getOrCreateHeightmapUnprimed(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG);
        target.setUnsaved(false);

        ContextIdentity context = ContextIdentity.of(WorldgenSnapshot.builder(91L, "minecraft:overworld").build(),
                WorldgenProgram.builder().build(), NumericProfile.JAVA_REFERENCE, "abi", "compiler", 0, 0);
        var table = BlockStateTable.fromRegistry(RegistrySnapshot.minimal());
        String[] states = new String[4096];
        java.util.Arrays.fill(states, "minecraft:air");
        states[0] = "minecraft:stone";
        int[] surface = new int[256];
        surface[0] = 1;
        var result = ChunkNoiseResult.ofDense(
                new ChunkResultHeader(0, 0, 0, 16, context.worldKey(), context.registryHash(), "abi"),
                table, states, new HeightmapPayload(java.util.Map.of("WORLD_SURFACE_WG", surface)),
                new boolean[4096]);
        var ownership = new MinecraftOwnershipToken(context, 0, 0, "biomes", "owner");
        var committer = MinecraftChunkCommitter.forChunk(target,
                level.registryAccess().lookupOrThrow(Registries.BLOCK), () -> ownership, "biomes");
        var execution = ExecutionReceipt.issued("cpu-commit", "cpu", context, NumericProfile.JAVA_REFERENCE,
                context.programHash(), "abi", 0, 1).completed(1).validated(1);
        var token = new CommitToken("owner", context, 0, 0);
        var receipt = new CommitCoordinator(committer).publish(token, result, execution);
        helper.assertTrue(receipt.committed(), "Commit was rejected: " + receipt.detail());
        helper.assertTrue(target.getBlockState(new BlockPos(0, 0, 0)).is(net.minecraft.world.level.block.Blocks.STONE), "Commit did not publish stone");
        var duplicate = new CommitCoordinator(committer).publish(token, result, execution);
        helper.assertTrue(!duplicate.committed() && duplicate.detail().contains("duplicate"), "Commit token was reusable");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public void injectedMutationFailuresRestoreEveryPublishedSeam(GameTestHelper helper) {
        var level = helper.getLevel();
        var resolver = MinecraftChunkApplier.fromBlocks(level.registryAccess().lookupOrThrow(Registries.BLOCK));
        var table = BlockStateTable.fromRegistry(RegistrySnapshot.minimal());
        var states = new String[4096];
        java.util.Arrays.fill(states, "minecraft:air");
        states[0] = "minecraft:stone";
        var heightmap = new int[256];
        heightmap[0] = 1;

        for (var failingPhase : MinecraftChunkApplier.MutationPhase.values()) {
            var target = new ProtoChunk(ChunkPos.ZERO, UpgradeData.EMPTY,
                    LevelHeightAccessor.create(0, 16),
                    level.registryAccess().registryOrThrow(Registries.BIOME), null);
            target.setPersistedStatus(ChunkStatus.BIOMES);
            var targetHeightmap = target.getOrCreateHeightmapUnprimed(
                    net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG);
            long[] originalHeightmap = targetHeightmap.getRawData().clone();
            target.setUnsaved(false);
            var result = ChunkNoiseResult.ofDense(
                    new ChunkResultHeader(0, 0, 0, 16, "context", table.fingerprint(), "4.0"),
                    table, states, new HeightmapPayload(java.util.Map.of("WORLD_SURFACE_WG", heightmap)),
                    markedCell());
            var applier = new MinecraftChunkApplier(resolver, phase -> {
                if (phase == failingPhase) throw new IllegalStateException("injected " + phase);
            });

            boolean failed = false;
            try {
                applier.apply(target, result, new ChunkMutationJournal());
            } catch (IllegalStateException expected) {
                failed = true;
            }
            helper.assertTrue(failed, "Failure seam did not fail at " + failingPhase);
            helper.assertTrue(target.getBlockState(new BlockPos(0, 0, 0)).isAir(),
                    "Block storage was not restored at " + failingPhase);
            helper.assertTrue(java.util.Arrays.equals(originalHeightmap,
                            target.getOrCreateHeightmapUnprimed(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG).getRawData()),
                    "Heightmap was not restored at " + failingPhase);
            int postProcessingEntries = 0;
            for (var section : target.getPostProcessing()) {
                if (section != null) postProcessingEntries += section.size();
            }
            helper.assertTrue(postProcessingEntries == 0,
                    "Postprocessing was not restored at " + failingPhase);
            helper.assertTrue(!target.isUnsaved(), "Dirty state was not restored at " + failingPhase);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public void generationRemainsExplicitlyUnsupported(GameTestHelper helper) {
        var frontend = new Minecraft1211Frontend();
        helper.assertTrue(!frontend.canInterceptGeneration(), "v0.1 must not intercept generation");
        var identity = new WorldgenIdentity(0, "minecraft:overworld", "synthetic-diagnostic", 0);
        var result = frontend.lower(identity, helper.getLevel().getChunkSource().getGenerator());
        helper.assertTrue(result instanceof LoweringResult.Unsupported, "Actual Minecraft generator must report unsupported");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public void versionPinnedNoiseHookBypassesWithoutQualification(GameTestHelper helper) {
        var result = StagedRoute.interceptNoise(null, null, null, null);
        helper.assertTrue(result.action() == StagedRoute.HookAction.BYPASS,
                "Unqualified generateNoise must remain vanilla: " + result.reason());
        helper.assertTrue(result.future() == null, "Vanilla bypass must not create a replacement future");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public void hookValidationCancellationPropagatesToProvider(GameTestHelper helper) {
        CompletableFuture<ChunkAccess> provider = new CompletableFuture<>();
        CompletableFuture<ChunkAccess> validated = StagedRoute.validatedProviderFuture(provider, null);
        helper.assertTrue(validated.cancel(false), "Hook validation future did not accept cancellation");
        helper.assertTrue(provider.isCancelled(), "Provider future kept running after hook cancellation");
        helper.assertTrue(validated.isCancelled(), "Hook validation future did not remain cancelled");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public void rejectedProviderAdmissionClosesOnlyTheUninstalledProvider(GameTestHelper helper) {
        StagedRoute.clearQualifiedNoiseProvider(null);
        var closed = new AtomicInteger();
        Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                StagedRoute.QualifiedNoiseProvider.class.getClassLoader(),
                new Class<?>[]{StagedRoute.QualifiedNoiseProvider.class, AutoCloseable.class},
                (ignored, method, arguments) -> switch (method.getName()) {
                    case "route" -> "CPU_OWNED";
                    case "close" -> { closed.incrementAndGet(); yield null; }
                    default -> null;
                });
        boolean rejected = false;
        try {
            StagedRoute.registerQualifiedNoiseProvider(
                    (StagedRoute.QualifiedNoiseProvider) proxy,
                    new QualifiedHookEvidence("unqualified", "CPU_OWNED", 0, 0, 0,
                            false, true,
                            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        } catch (IllegalArgumentException expected) {
            rejected = true;
        } finally {
            StagedRoute.clearQualifiedNoiseProvider(null);
        }
        helper.assertTrue(rejected, "Unqualified provider admission unexpectedly succeeded");
        helper.assertTrue(closed.get() == 1,
                "Rejected provider was not closed exactly once: " + closed.get());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public void distinctRouterMarkersNeverAliasTheirCapturedCacheKeys(GameTestHelper helper) {
        var reader = new DensityNodeReader();
        var leftSource = DensityFunctions.flatCache(DensityFunctions.constant(2.0));
        var rightSource = DensityFunctions.flatCache(DensityFunctions.constant(7.0));
        var leftCapture = reader.capture(leftSource);
        var rightCapture = reader.capture(rightSource);
        helper.assertTrue(reader.capture(leftSource) == leftCapture,
                "Shared source identity must reuse its descriptor");
        var left = new DensityNodeLowerer().lower(leftCapture);
        var right = new DensityNodeLowerer().lower(rightCapture);
        helper.assertTrue(left.supported() && right.supported(), "Marker capture failed");
        var leftMarker = (dev.worldgennext.semantic.program.ProgramNode.Marker) left.node();
        var rightMarker = (dev.worldgennext.semantic.program.ProgramNode.Marker) right.node();
        helper.assertTrue(!leftMarker.marker().equals(rightMarker.marker()),
                "Distinct root markers have the same value-cache identity");
        var program = WorldgenProgram.builder().root("left", leftMarker).root("right", rightMarker).build();
        var interpreter = new WorldgenInterpreter();
        var markers = new MarkerContext(0);
        helper.assertTrue(interpreter.evaluateAtCaptured(program, "left", 1L, -1, 64, -1, markers) == 2.0,
                "Left marker value was incorrect");
        helper.assertTrue(interpreter.evaluateAtCaptured(program, "right", 1L, -1, 64, -1, markers) == 7.0,
                "Right marker reused the left producer at the same quart point");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public void mappedDensityCaptureLowersWithoutOpaqueGameObjects(GameTestHelper helper) {
        var source = DensityFunctions.rangeChoice(
                DensityFunctions.constant(2.0), 0.0, 4.0,
                DensityFunctions.constant(7.0), DensityFunctions.constant(11.0));
        var captured = new DensityNodeReader().capture(source);
        var lowered = new DensityNodeLowerer().lower(captured);
        helper.assertTrue(lowered.supported(), lowered.diagnostics().summary());
        var program = WorldgenProgram.builder().root("finalDensity", lowered.node()).build();
        Object value = new WorldgenInterpreter().evaluate(program, "finalDensity", java.util.Map.of(), new MarkerContext(0));
        helper.assertTrue(value instanceof Double && ((Double) value) == 7.0, "Unexpected lowered range value: " + value);

        var zero = DensityFunctions.constant(0.0);
        var router = new net.minecraft.world.level.levelgen.NoiseRouter(
                zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero);
        var snapshot = new MinecraftSnapshotReader().capture(0L, "minecraft:overworld", "game-test", router,
                RegistrySnapshot.minimal(), GeneratorSettingsSnapshot.overworld(), DynamicInputIdentity.empty(),
                "game-test-stack", null, StructureBlendSnapshot.empty(), java.util.List.of());
        helper.assertTrue(snapshot.supported(), snapshot.diagnostics().summary());
        helper.assertTrue(snapshot.snapshot().router().complete(), "All 15 router roots must be captured");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public void actualRouterCaptureInventory(GameTestHelper helper) {
        long actualSeed = helper.getLevel().getSeed();
        NoiseGeneratorSettings settings = helper.getLevel().registryAccess().registryOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var runtimeRandomState = helper.getLevel().getChunkSource().randomState();
        NoiseRouter router = runtimeRandomState.router();
        var reader = new DensityNodeReader();
        var failures = new java.util.ArrayList<String>();
        int captured = 0;
        for (var entry : actualRoots(router).entrySet()) {
            try {
                reader.capture(entry.getValue());
                captured++;
            } catch (RuntimeException failure) {
                failures.add(entry.getKey() + "=" + failure.getMessage());
            }
        }
        System.out.println("WorldgenNext actual-router capture inventory: captured=" + captured
                + "/" + actualRoots(router).size() + ", failures=" + failures);
        helper.assertTrue(captured > 0, "No actual NoiseRouter roots were captured: " + failures);
        var registry = new dev.worldgennext.neoforge.snapshot.RegistrySnapshotReader().capture(settings.defaultBlock(), settings.defaultFluid());
        var noiseSettings = settings.noiseSettings();
        var generatorSettings = new GeneratorSettingsSnapshot(noiseSettings.minY(), noiseSettings.height(), noiseSettings.height(),
                settings.seaLevel(), registry.defaultBlock(), registry.defaultFluid(), settings.aquifersEnabled(), settings.oreVeinsEnabled());
        var snapshot = new MinecraftSnapshotReader().captureWithRuntimeRandomState(actualSeed, "minecraft:overworld", "game-test-actual-router",
                router, runtimeRandomState, registry,
                generatorSettings, DynamicInputIdentity.empty(), "game-test-stack", null,
                StructureBlendSnapshot.empty(), java.util.List.of());
        helper.assertTrue(snapshot.supported(), "Actual 15-root snapshot did not lower: " + snapshot.diagnostics().summary());
        var program = WorldgenProgram.builder().roots(snapshot.snapshot().router().roots()).build();
        helper.assertTrue(program.hasAllRouterRoots(), "Actual snapshot lost a router root");
        Object finalDensity = new WorldgenInterpreter().evaluateAt(program, "finalDensity", actualSeed, 0, 64, 0, new MarkerContext(0));
        helper.assertTrue(finalDensity instanceof Number && Double.isFinite(((Number) finalDensity).doubleValue()),
                "Actual lowered final density was not finite: " + finalDensity);
        var interpreter = new WorldgenInterpreter();
        for (int[] point : new int[][]{
                {0, 64, 0}, {13, -32, -17}, {31, 128, 47}, {-32, 0, 16}, {128, 200, -64},
                {-127, -63, 95}, {7, 7, 7}, {-1, 63, -1}, {1024, 80, -1024}
        }) {
            double expected = router.finalDensity().compute(new DensityFunction.SinglePointContext(point[0], point[1], point[2]));
            double actual = interpreter.evaluateAt(program, "finalDensity", actualSeed, point[0], point[1], point[2], new MarkerContext(0));
            helper.assertTrue(Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual),
                    "Captured finalDensity mismatch at " + java.util.Arrays.toString(point)
                            + ": expected=" + expected + ", actual=" + actual);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public void netherAndEndRouterCaptureInventory(GameTestHelper helper) {
        assertDimensionRouter(helper, Level.NETHER, NoiseGeneratorSettings.NETHER);
        assertDimensionRouter(helper, Level.END, NoiseGeneratorSettings.END);
        helper.succeed();
    }

    private static void assertDimensionRouter(GameTestHelper helper, net.minecraft.resources.ResourceKey<Level> dimension,
                                              net.minecraft.resources.ResourceKey<NoiseGeneratorSettings> settingsKey) {
        ServerLevel level = helper.getLevel().getServer().getLevel(dimension);
        helper.assertTrue(level != null, "Missing loaded dimension " + dimension.location());
        var randomState = level.getChunkSource().randomState();
        var roots = actualRoots(randomState.router());
        var reader = new DensityNodeReader(randomState, level.getSeed());
        var failures = new java.util.ArrayList<String>();
        int captured = 0;
        for (var entry : roots.entrySet()) {
            try {
                reader.capture(entry.getValue());
                captured++;
            } catch (RuntimeException failure) {
                failures.add(entry.getKey() + "=" + failure.getMessage());
            }
        }
        System.out.println("WorldgenNext dimension-router capture inventory " + dimension.location()
                + ": captured=" + captured + "/" + roots.size() + ", failures=" + failures);
        helper.assertTrue(captured == roots.size(), dimension.location() + " router capture incomplete: " + failures);
        var settings = level.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS).getOrThrow(settingsKey);
        helper.assertTrue(settings.noiseRouter() != null, "Missing noise settings router for " + dimension.location());
        var lowered = new DensityNodeLowerer().lower(reader.capture(randomState.router().finalDensity()));
        helper.assertTrue(lowered.supported(), dimension.location() + " finalDensity did not lower: " + lowered.diagnostics().summary());
        var program = WorldgenProgram.builder().root("finalDensity", lowered.node()).build();
        var interpreter = new WorldgenInterpreter();
        for (int[] point : new int[][]{{0, 64, 0}, {31, 128, 47}, {-127, -63, 95}}) {
            double expected = randomState.router().finalDensity().compute(new DensityFunction.SinglePointContext(point[0], point[1], point[2]));
            double actual = interpreter.evaluateAt(program, "finalDensity", level.getSeed(), point[0], point[1], point[2], new MarkerContext(0));
            helper.assertTrue(Double.doubleToRawLongBits(expected) == Double.doubleToRawLongBits(actual),
                    dimension.location() + " finalDensity mismatch at " + java.util.Arrays.toString(point)
                            + ": expected=" + expected + ", actual=" + actual);
        }
        System.out.println("WorldgenNext dimension finalDensity parity " + dimension.location() + ": 3/3 raw-bit points");
    }

    private static boolean[] markedCell() {
        boolean[] marks = new boolean[4096];
        marks[0] = true;
        return marks;
    }

    private static java.util.Map<String, DensityFunction> actualRoots(NoiseRouter router) {
        var roots = new java.util.LinkedHashMap<String, DensityFunction>();
        roots.put("barrierNoise", router.barrierNoise());
        roots.put("fluidLevelFloodedness", router.fluidLevelFloodednessNoise());
        roots.put("fluidLevelSpread", router.fluidLevelSpreadNoise());
        roots.put("lava", router.lavaNoise());
        roots.put("temperature", router.temperature());
        roots.put("vegetation", router.vegetation());
        roots.put("continents", router.continents());
        roots.put("erosion", router.erosion());
        roots.put("depth", router.depth());
        roots.put("ridges", router.ridges());
        roots.put("initialDensityWithoutJaggedness", router.initialDensityWithoutJaggedness());
        roots.put("finalDensity", router.finalDensity());
        roots.put("veinToggle", router.veinToggle());
        roots.put("veinRidged", router.veinRidged());
        roots.put("veinGap", router.veinGap());
        return roots;
    }

}
