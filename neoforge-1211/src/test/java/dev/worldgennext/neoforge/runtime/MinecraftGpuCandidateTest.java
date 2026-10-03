// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import dev.worldgennext.material.chunk.BlockStateTable;
import dev.worldgennext.compiler.vulkan.worldgen.WorldgenShaderCompiler;
import dev.worldgennext.runtime.vulkan.production.VulkanWorldgenExecutor;
import dev.worldgennext.semantic.material.OreVeinProgram;
import dev.worldgennext.semantic.snapshot.BlockStateDescriptor;
import dev.worldgennext.semantic.snapshot.BeardifierSnapshot;
import dev.worldgennext.semantic.snapshot.StructureBlendSnapshot;
import dev.worldgennext.semantic.snapshot.GeneratorSettingsSnapshot;
import dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot;
import dev.worldgennext.semantic.snapshot.RandomStateSnapshot;
import dev.worldgennext.semantic.snapshot.RegistrySnapshot;
import dev.worldgennext.semantic.snapshot.WorldgenSnapshot;
import dev.worldgennext.semantic.program.EvaluationDomain;
import dev.worldgennext.semantic.program.NumericProfile;
import dev.worldgennext.semantic.program.ProgramNode;
import dev.worldgennext.semantic.program.ValueType;
import dev.worldgennext.semantic.program.WorldgenProgram;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftGpuCandidateTest {
    @Test void boundedOreFinishPacksOwnedQuotientsAndRejectsMalformedOrNonfiniteRows() {
        int[] inputs = new int[20];
        for (int i = 0; i < inputs.length; i++) inputs[i] = i;
        int[][] quotients = {{101, 102, 103, 104}, {201, 202, 203, 204}};
        int[] result = MinecraftGpuCandidate.packOreFinishInputs(inputs, quotients);
        assertEquals(28, result.length);
        assertEquals(101, result[10]); assertEquals(201, result[12]);
        assertEquals(10, result[14]); assertEquals(103, result[24]); assertEquals(203, result[26]);
        inputs[0] = 999; quotients[0][0] = 999;
        assertEquals(0, result[0]); assertEquals(101, result[10]);
        assertThrows(IllegalArgumentException.class, () -> MinecraftGpuCandidate.packOreFinishInputs(new int[0], quotients));
        assertThrows(IllegalArgumentException.class, () -> MinecraftGpuCandidate.packOreFinishInputs(new int[11], quotients));
        quotients[0] = new int[2];
        assertThrows(IllegalArgumentException.class, () -> MinecraftGpuCandidate.packOreFinishInputs(inputs, quotients));
        quotients[0] = new int[]{0, 0x7ff00000, 0, 0};
        assertThrows(IllegalStateException.class, () -> MinecraftGpuCandidate.packOreFinishInputs(inputs, quotients));
    }

    @Test void boundedOrePrepareUsesCapturedDivisionOperandsAndFinishContainsNoDivider() {
        var program = WorldgenProgram.builder()
                .root("veinToggle", new ProgramNode.Constant(ValueType.FP64, 0.5d, EvaluationDomain.WORLD))
                .root("veinRidged", new ProgramNode.Constant(ValueType.FP64, -0.1d, EvaluationDomain.WORLD))
                .root("veinGap", new ProgramNode.Constant(ValueType.FP64, 0d, EvaluationDomain.WORLD)).build();
        var material = new WorldgenShaderCompiler.MaterialOptions(OreVeinProgram.vanilla(0),
                PositionalRandomFactorySnapshot.xoroshiro(1, 2), List.of(10, 11, 12, 13, 14, 15));
        var ids = new java.util.concurrent.atomic.AtomicInteger(4);
        var emission = new dev.worldgennext.compiler.vulkan.worldgen.OreVeinEmitter().emit(program, material,
                (router, root) -> switch (root) {
                    case "veinToggle" -> "wg_node_1(point)";
                    case "veinRidged" -> "wg_node_2(point)";
                    case "veinGap" -> "wg_node_3(point)";
                    default -> throw new IllegalArgumentException(root);
                }, ids::getAndIncrement);
        String source = """
                #version 450
                layout(local_size_x = 64) in;
                layout(set = 0, binding = 0) readonly buffer Input { uint inputBits[]; };
                layout(set = 0, binding = 1) buffer Output { uint outputStateIds[]; };
                bool wg_failed = false;
                ivec3 wg_point(uint index) { uint base = index * 4u; return ivec3(inputBits[base], inputBits[base+1u], inputBits[base+2u]); }
                uvec2 wg_node_1(ivec3 point) { return uvec2(1u); }
                uvec2 wg_node_2(ivec3 point) { return uvec2(2u); }
                uvec2 wg_node_3(ivec3 point) { return uvec2(3u); }
                """ + dev.worldgennext.compiler.vulkan.worldgen.IntegerIeeeEmitter.source() + emission.source()
                + "\n// bounds check is mandatory\nvoid main() {}\n";
        String edge = MinecraftGpuCandidate.oreDivisionPrepareSource(source, emission.entryPoint(), "edgeFraction");
        String rich = MinecraftGpuCandidate.oreDivisionPrepareSource(source, emission.entryPoint(), "richnessFraction");
        assertTrue(edge.contains("uvec2 numerator = wg_fp64_sub(wg_fp64_from_int(distanceToEdge)"));
        assertTrue(rich.contains("uvec2 numerator = wg_fp64_sub(magnitude"));
        assertEquals(false, edge.contains("wg_fp64_div("));
        assertEquals(false, rich.contains("wg_fp64_div("));
        String finish = MinecraftGpuCandidate.oreFinishStageSource(source, emission.entryPoint());
        assertEquals(false, finish.contains("wg_fp64_div("));
        assertEquals(false, finish.contains("wg_node_"));
        assertTrue(finish.contains("uint base = index * 14u;"));
        assertTrue(finish.contains("gl_GlobalInvocationID.x * 14u + 13u"));
        assertTrue(finish.contains("uint richnessRandom"));
        assertThrows(IllegalArgumentException.class, () -> MinecraftGpuCandidate.oreDivisionPrepareSource(source, emission.entryPoint(), "unknown"));
    }

    @Test void externalOreInputsPreserveRowsAndCarrierOrderWithOwnedPacking() {
        int[] coordinates = {-1, 18, 4, 77, 2, -8, 6, 88};
        int[][] carriers = {{1, 2, 3, 4}, {5, 6, 7, 8}, {9, 10, 11, 12}};
        int[] packed = MinecraftGpuCandidate.packOreStageInputs(coordinates, carriers);
        assertArrayEquals(new int[]{-1, 18, 4, 77, 1, 2, 5, 6, 9, 10,
                2, -8, 6, 88, 3, 4, 7, 8, 11, 12}, packed);
        coordinates[0] = 999; carriers[0][0] = 999;
        assertEquals(-1, packed[0]); assertEquals(1, packed[4]);
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.packOreStageInputs(new int[0], carriers));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.packOreStageInputs(new int[5], carriers));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.packOreStageInputs(new int[8], new int[2][]));
        carriers[2] = new int[2];
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.packOreStageInputs(new int[8], carriers));
    }

    @Test void externalOreSourceKeepsDecisionAndRngButDropsNoiseCallsAndUsesTenWordStride() {
        String source = """
                #version 450
                layout(local_size_x = 64) in;
                layout(set = 0, binding = 0) readonly buffer Input { uint inputBits[]; };
                layout(set = 0, binding = 1) buffer Output { uint outputStateIds[]; };
                bool wg_failed = false;
                ivec3 wg_point(uint index) { uint base = index * 4u; return ivec3(inputBits[base], inputBits[base+1u], inputBits[base+2u]); }
                uvec2 wg_node_1(ivec3 point) { return uvec2(1u); }
                uvec2 wg_node_2(ivec3 point) { return uvec2(2u); }
                uvec2 wg_node_3(ivec3 point) { return uvec2(3u); }
                uint wg_rng(ivec3 point) { return uint(point.x); }
                uvec2 wg_ore_4(ivec3 point) {
                    uvec2 toggle = wg_node_1(point);
                    if (wg_rng(point) == 7u) return uvec2(0u);
                    uvec2 ridged = wg_node_2(point);
                    if (ridged.x == 0u) return uvec2(0u);
                    uvec2 gap = wg_node_3(point);
                    return toggle + gap;
                }
                // bounds check is mandatory
                void main() { }
                """;
        assertEquals(List.of("wg_node_1", "wg_node_2", "wg_node_3"),
                MinecraftGpuCandidate.oreStageInputRoots(source, "wg_ore_4"));
        String stage = MinecraftGpuCandidate.oreExternalStageSource(source, "wg_ore_4");
        assertTrue(stage.contains("uint base = index * 10u;"));
        assertTrue(stage.contains("inputBits[gl_GlobalInvocationID.x * 10u + 4u]"));
        assertTrue(stage.contains("inputBits[gl_GlobalInvocationID.x * 10u + 9u]"));
        assertTrue(stage.contains("if (wg_rng(point) == 7u)"));
        assertEquals(false, stage.contains("wg_node_"));
        assertThrows(UnsupportedOperationException.class, () -> MinecraftGpuCandidate.oreStageInputRoots(
                source.replace("uvec2 gap = wg_node_3(point);", "uvec2 gap = uvec2(0u);"), "wg_ore_4"));
        assertThrows(UnsupportedOperationException.class, () -> MinecraftGpuCandidate.oreStageInputRoots(
                source.replace("uvec2 gap = wg_node_3(point);", "uvec2 gap = wg_node_3(point); uvec2 gap = wg_node_3(point);"), "wg_ore_4"));
    }

    @Test void embeddedStructureStagesUseTheStandaloneInlinePolicyEvenWithExplicitOverrides() {
        var shader = new WorldgenShaderCompiler().emitBeardifierStage(
                StructureBlendSnapshot.empty(), NumericProfile.GPU_IEEE_BITS, 64, false);
        var request = new VulkanWorldgenExecutor.RawRequest(shader, new int[4], 4, 2, 1)
                .withDontInlineFunctions("wg_node_,wg_fp64_");
        var direct = MinecraftGpuCandidate.directDensityStageRequest(request);
        var ordinary = MinecraftGpuCandidate.densityStageRequest(request);
        assertEquals(false, direct.dontInlineFunctions());
        assertEquals(false, ordinary.dontInlineFunctions());
        assertEquals(4 + 13824, direct.inputWordCount());
        assertArrayEquals(direct.inputWords(), ordinary.inputWords());
        assertTrue(direct.shader().source().contains("dispatch.count * 4u + uint(index)"));
        assertEquals(false, direct.shader().source().contains("const uint wg_beard_kernel["));
        assertEquals(request.disablePipelineOptimization(), direct.disablePipelineOptimization());
        var suffixed = new VulkanWorldgenExecutor.RawRequest(shader, new int[8], 4, 2, 1);
        assertThrows(IllegalArgumentException.class, () -> MinecraftGpuCandidate.densityStageRequest(suffixed));
    }

    @Test void canonicalBlendedPackingReordersInterleavedGpuCarriersAndOwnsOutput() {
        var specs = new java.util.ArrayList<MinecraftGpuCandidate.SampleSpec>();
        for (int i = 0; i < 8; i++) specs.add(new MinecraftGpuCandidate.SampleSpec("main" + i, i, "main"));
        for (int i = 0; i < 16; i++) {
            specs.add(new MinecraftGpuCandidate.SampleSpec("min" + i, i, "min"));
            specs.add(new MinecraftGpuCandidate.SampleSpec("max" + i, i, "max"));
        }
        int[] coordinates = {-1, 2, 3, 0x7fc00001, 7, 8, 9, 0x7fc00002};
        int[][] words = new int[40][];
        for (int i = 0; i < 40; i++) words[i] = new int[]{i, -i, 0x7fc00001, 0x7fc00002, 100 + i, -100 - i, 77, 88};
        int[] result = MinecraftGpuCandidate.packCanonicalBlendedInput(specs, coordinates, words);
        assertEquals(168, result.length);
        assertArrayEquals(coordinates, new int[]{result[0], result[1], result[2], result[3],
                result[84], result[85], result[86], result[87]});
        for (int i = 0; i < 16; i++) {
            assertEquals(8 + 2 * i, result[4 + (8 + i) * 2]);
            assertEquals(9 + 2 * i, result[4 + (24 + i) * 2]);
            assertEquals(100 + 8 + 2 * i, result[84 + 4 + (8 + i) * 2]);
        }
        coordinates[0] = 999; words[0][0] = 999;
        assertEquals(-1, result[0]); assertEquals(0, result[4]);
        var malformed = new java.util.ArrayList<>(specs);
        malformed.set(1, malformed.get(0));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.packCanonicalBlendedInput(malformed, coordinates, words));
        malformed.set(1, new MinecraftGpuCandidate.SampleSpec("bad", 8, "main"));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.packCanonicalBlendedInput(malformed, coordinates, words));
        malformed.set(1, new MinecraftGpuCandidate.SampleSpec("bad", 0, "unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.packCanonicalBlendedInput(malformed, coordinates, words));
        words[0] = new int[4];
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.packCanonicalBlendedInput(specs, coordinates, words));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.packCanonicalBlendedInput(specs, new int[0], words));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.packCanonicalBlendedInput(List.of(), coordinates, words));
    }

    @Test void structureReplayAdmitsCapturedBeardifierButRejectsEveryOldWorldBlendChannel() {
        var beardifier = new BeardifierSnapshot(List.of(new BeardifierSnapshot.Rigid(
                -2, -2, -2, 2, 2, 2, BeardifierSnapshot.Adjustment.BEARD_BOX, 2)),
                List.of(new BeardifierSnapshot.Junction(-3, 0, 4)));
        MinecraftGpuCandidate.requireDenseMaterialScope(StructureBlendSnapshot.empty());
        MinecraftGpuCandidate.requireDenseMaterialScope(new StructureBlendSnapshot(
                "structure", java.util.Map.of(), java.util.Map.of(), beardifier));
        var density = List.of(new StructureBlendSnapshot.DensitySample(0, 0, 0, 0.25));
        var directDensity = List.of(new StructureBlendSnapshot.DirectDensitySample(0, 0, 0, 0, 0, 0.25));
        var height = List.of(new StructureBlendSnapshot.HeightSample(0, 0, 64));
        var directHeight = List.of(new StructureBlendSnapshot.DirectHeightSample(0, 0, 0, 0, 64));
        for (var blend : List.of(
                new StructureBlendSnapshot("density-map", java.util.Map.of("a", 0.25), java.util.Map.of()),
                new StructureBlendSnapshot("alpha-map", java.util.Map.of(), java.util.Map.of("a", 0.25)),
                new StructureBlendSnapshot("density", java.util.Map.of(), java.util.Map.of(), beardifier, density),
                new StructureBlendSnapshot("direct-density", java.util.Map.of(), java.util.Map.of(), beardifier, List.of(), directDensity),
                new StructureBlendSnapshot("height-flag", java.util.Map.of(), java.util.Map.of(), beardifier, List.of(), List.of(), true),
                new StructureBlendSnapshot("height", java.util.Map.of(), java.util.Map.of(), beardifier, List.of(), List.of(), height, List.of(), false),
                new StructureBlendSnapshot("direct-height", java.util.Map.of(), java.util.Map.of(), beardifier, List.of(), List.of(), List.of(), directHeight, false))) {
            var failure = assertThrows(UnsupportedOperationException.class,
                    () -> MinecraftGpuCandidate.requireDenseMaterialScope(blend));
            assertTrue(failure.getMessage().contains("old-world blending"));
        }
        assertThrows(NullPointerException.class, () -> MinecraftGpuCandidate.requireDenseMaterialScope(null));
    }

    @Test void stageCompactionRetainsBeardKernelOnlyForReachedStructureHelpers() {
        String source = """
                #version 450
                const uint wg_beard_kernel[13824] = uint[13824](
                    %s);
                uvec2 wg_beardifier(ivec3 point) { return uvec2(wg_beard_kernel[point.x]); }
                uvec2 wg_node_0(ivec3 point) { return wg_beardifier(point); }
                uvec2 wg_node_1(ivec3 point) { return uvec2(0u); }
                """.formatted("0u,".repeat(13823) + "0u");
        String reached = MinecraftGpuCandidate.compactSource(source, Set.of("wg_node_0"));
        assertTrue(reached.contains("const uint wg_beard_kernel[13824]"));
        assertTrue(reached.contains("uvec2 wg_beardifier"));
        String unrelated = MinecraftGpuCandidate.compactSource(source, Set.of("wg_node_1"));
        assertEquals(false, unrelated.contains("wg_beard_kernel"));
        assertEquals(false, unrelated.contains("wg_beardifier"));
        assertTrue(unrelated.contains("wg_node_1"));
    }

    @Test
    void noiseTableUsersScanExactIdentifiersWithoutPrefixAliasesAndReturnOwnedImmutableSets() {
        var functions = new java.util.HashMap<String, String>();
        functions.put("first", "wg_noise_perm_1[0] + wg_noise_perm_1[2] + wg_noise_perm_10[0]");
        functions.put("second", "xwg_noise_perm_1 + wg_noise_perm_1_extra + wg_noise_perm_10_lookup(0)");
        functions.put("third", "// wg_noise_perm_10\nreturn wg_noise_perm_1 [0];");
        var names = new java.util.HashSet<>(Set.of("wg_noise_perm_1", "wg_noise_perm_10", "unused"));
        var users = MinecraftGpuCandidate.noiseTableUsers(functions, names);
        assertEquals(Set.of("first", "third"), users.get("wg_noise_perm_1"));
        assertEquals(Set.of("first", "third"), users.get("wg_noise_perm_10"));
        assertEquals(Set.of(), users.get("unused"));
        functions.clear();
        names.clear();
        assertEquals(3, users.size());
        assertThrows(UnsupportedOperationException.class, () -> users.put("new", Set.of()));
        assertThrows(UnsupportedOperationException.class, () -> users.get("unused").add("second"));
        assertEquals(java.util.Map.of(), MinecraftGpuCandidate.noiseTableUsers(java.util.Map.of(), Set.of()));
        assertEquals(java.util.Map.of("unused", Set.of()),
                MinecraftGpuCandidate.noiseTableUsers(java.util.Map.of(), Set.of("unused")));
    }

    @Test
    void sharedEndPermutationIsOwnedAndUploadedOnceAfterTheCoordinateRows() {
        var stage = MinecraftGpuCandidate.sharedEndIslandStage(endSharedFixture(45, 0));
        int[] permutation = stage.permutationWords();
        assertArrayEquals(java.util.stream.IntStream.range(0, 256).toArray(), permutation);
        permutation[0] = 99;
        assertEquals(0, stage.permutationWords()[0]);
        int[] rows = {-256, 257, -12, 12, 1, -1, 0, 0};
        int[] input = stage.inputRows(rows);
        assertEquals(264, input.length);
        assertArrayEquals(rows, java.util.Arrays.copyOfRange(input, 0, 8));
        assertArrayEquals(stage.permutationWords(), java.util.Arrays.copyOfRange(input, 8, input.length));
        rows[0] = 20;
        assertEquals(-256, input[0]);
        assertThrows(IllegalArgumentException.class, () -> stage.inputRows(null));
        assertThrows(IllegalArgumentException.class, () -> stage.inputRows(new int[0]));
        assertThrows(IllegalArgumentException.class, () -> stage.inputRows(new int[5]));
    }

    @Test
    void sharedEndCanonicalSourcePreservesNestedTableReadsAndUnchangedMathAcrossSeeds() {
        var first = MinecraftGpuCandidate.sharedEndIslandStage(endSharedFixture(45, 0));
        var second = MinecraftGpuCandidate.sharedEndIslandStage(endSharedFixture(91, 17));
        assertEquals(first.source(), second.source());
        assertEquals(17, second.permutationWords()[0]);
        assertTrue(first.source().contains("wg_end_permutation_lookup((1u + wg_end_permutation_lookup(2u)) & 255u)"));
        assertTrue(first.source().contains("inputBits[dispatch.count * 4u + index]"));
        assertTrue(first.source().contains("inputBits.length() != dispatch.count * 4u + 256u"));
        assertTrue(first.source().contains("return wg_fp64_add(x, y);"));
        assertTrue(first.source().contains("wg_end_simplex_shared"));
        assertEquals(false, first.source().contains("wg_end_perm_45"));
        assertEquals(false, first.source().contains("uint[256]"));
    }

    @Test
    void sharedEndRejectsIncompleteAmbiguousOutOfRangeOrNonPermutationTables() {
        String source = endSharedFixture(45, 0);
        assertThrows(IllegalArgumentException.class, () -> MinecraftGpuCandidate.sharedEndIslandStage(null));
        assertThrows(UnsupportedOperationException.class,
                () -> MinecraftGpuCandidate.sharedEndIslandStage(source.replace("[256]", "[255]")));
        assertThrows(UnsupportedOperationException.class,
                () -> MinecraftGpuCandidate.sharedEndIslandStage(source + source));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.sharedEndIslandStage(source.replace("uint[256](0u,1u,", "uint[256](1u,1u,")));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.sharedEndIslandStage(source.replace("uint[256](0u,1u,", "uint[256](256u,1u,")));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.sharedEndIslandStage(source.replace("uint[256](0u,1u,", "uint[256](1u,")));
    }

    @Test
    void sharedEndFailsClosedOnUnknownHelperIdentityAndChangedMainAbi() {
        String source = endSharedFixture(45, 0);
        assertThrows(UnsupportedOperationException.class,
                () -> MinecraftGpuCandidate.sharedEndIslandStage(source.replace("wg_end_corner_45", "wg_end_corner_46")));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.sharedEndIslandStage(source.replace("index * 4u", "index * 5u")));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.sharedEndIslandStage(source.replace("wg_failed = false;", "wg_failed = false;\n    uint base = index * 4u;\n    wg_failed = false;")));
    }

    private static String endSharedFixture(int id, int rotation) {
        String table = java.util.stream.IntStream.range(0, 256)
                .mapToObj(value -> ((value + rotation) & 255) + "u")
                .collect(java.util.stream.Collectors.joining(","));
        return """
                bool wg_failed;
                const uint wg_end_perm_%1$d[256] = uint[256](%2$s);
                uvec2 wg_end_gradient_%1$d(uint hash, uvec2 x, uvec2 y) { return wg_fp64_add(x, y); }
                uvec2 wg_end_corner_%1$d(uint hash, uvec2 x, uvec2 y) { return wg_end_gradient_%1$d(hash, x, y); }
                uvec2 wg_end_simplex_%1$d(uvec2 x, uvec2 y) {
                    uint hash = wg_end_perm_%1$d[(1u + wg_end_perm_%1$d[2u]) & 255u];
                    return wg_end_corner_%1$d(hash, x, y);
                }
                void main() {
                    wg_failed = false;
                    uint base = index * 4u;
                    uvec2 value = wg_end_simplex_%1$d(uvec2(inputBits[base]), uvec2(inputBits[base + 1u]));
                }
                """.formatted(id, table);
    }

    @Test
    void residentNormalNoiseRowsPreserveOnlyCoordinatesAndChildCarriers() {
        int[] input = {1, -2, 3, 4, 0x1234, 0x5678, -7, 8, -9, 10, 11, 12};
        int[] rows = MinecraftGpuCandidate.residentNormalNoiseInput(input, 6);
        assertEquals(44, rows.length);
        assertArrayEquals(java.util.Arrays.copyOfRange(input, 0, 6), java.util.Arrays.copyOfRange(rows, 0, 6));
        assertArrayEquals(java.util.Arrays.copyOfRange(input, 6, 12), java.util.Arrays.copyOfRange(rows, 22, 28));
        assertArrayEquals(new int[16], java.util.Arrays.copyOfRange(rows, 6, 22));
        assertArrayEquals(new int[16], java.util.Arrays.copyOfRange(rows, 28, 44));
        input[0] = 99;
        assertEquals(1, rows[0]);
        assertThrows(IllegalArgumentException.class, () -> MinecraftGpuCandidate.residentNormalNoiseInput(null, 4));
        assertThrows(IllegalArgumentException.class, () -> MinecraftGpuCandidate.residentNormalNoiseInput(new int[5], 4));
        assertThrows(IllegalArgumentException.class, () -> MinecraftGpuCandidate.residentNormalNoiseInput(new int[3], 3));
    }

    @Test
    void residentSamplerKeepsMetadataOutsideRowsAndCopiesOnlyTheRow() {
        String source = """
                uint metadataBase = dispatch.count * 10u;
                uvec2 value = wg_shared_perlin_generic(index * 10u);
                uint outputBase = index * 2u;
                """;
        String resident = MinecraftGpuCandidate.residentNormalNoiseSampler(source, 22, 12, 20);
        assertTrue(resident.contains("metadataBase = dispatch.count * 22u;"));
        assertTrue(resident.contains("wg_shared_perlin_generic(index * 22u + 8u)"));
        assertTrue(resident.contains("outputBits[rowBase + 21u] = inputBits[rowBase + 21u];"));
        assertEquals(false, resident.contains("word <"));
        assertTrue(resident.contains("outputBase = rowBase + 20u;"));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.residentNormalNoiseSampler(source, 22, 17, 20));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.residentNormalNoiseOutput(source, 22, 2, 21));
    }

    @Test
    void residentSourceAdaptersFailClosedOnMissingOrAmbiguousAbiAnchors() {
        assertEquals("abc NEW xyz", MinecraftGpuCandidate.replaceStageAbiOnce("abc OLD xyz", "OLD", "NEW"));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.replaceStageAbiOnce("abc", "OLD", "NEW"));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.replaceStageAbiOnce("OLD OLD", "OLD", "NEW"));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.replaceStageAbiOnce("abc", "", "NEW"));
    }

    @Test
    void endIslandDomainTruncatesSignedCoordinatesIgnoresYAndRejectsIncompleteRows() {
        int[] rows = { -7, 0, -8, 99, 15, 64, 16, 0, 15, -200, 16, -1 };
        assertArrayEquals(new int[]{0, -1, 1, 2, 1, 2}, MinecraftGpuCandidate.endIslandScaledCoordinates(rows));
        int[] scaled = MinecraftGpuCandidate.endIslandScaledCoordinates(rows);
        rows[0] = 800;
        assertEquals(0, scaled[0]);
        assertThrows(IllegalArgumentException.class, () -> MinecraftGpuCandidate.endIslandScaledCoordinates(null));
        assertThrows(IllegalArgumentException.class, () -> MinecraftGpuCandidate.endIslandScaledCoordinates(new int[0]));
        assertThrows(IllegalArgumentException.class, () -> MinecraftGpuCandidate.endIslandScaledCoordinates(new int[3]));
    }

    @Test
    void islandLeafIsAnExplicitBundleBoundaryNotEveryAncestorOrCapturedTable() {
        String source = """
                const uint wg_end_perm_45[1] = uint[1](0u);
                uvec2 wg_node_44(ivec3 point) { return wg_end_island_45(point); }
                uvec2 wg_node_42(ivec3 point) { return wg_node_44(point); }
                uvec2 wg_node_27(ivec3 point) { return uvec2(0u); }
                """;
        assertEquals(true, MinecraftGpuCandidate.capturedEndIslandRoot(source, "wg_node_44"));
        assertEquals(false, MinecraftGpuCandidate.capturedEndIslandRoot(source, "wg_node_42"));
        assertEquals(false, MinecraftGpuCandidate.capturedEndIslandRoot(source, "wg_node_27"));
        assertEquals(false, MinecraftGpuCandidate.capturedEndIslandRoot(source, "wg_spline_423"));
        assertEquals(false, MinecraftGpuCandidate.capturedEndIslandRoot("", "wg_node_44"));
        assertThrows(UnsupportedOperationException.class,
                () -> MinecraftGpuCandidate.capturedEndIslandRoot(source, "wg_node_999"));
    }

    @Test
    void disabledAquiferDoesNotRequireAnExternalBarrierCarrier() {
        assertEquals(false, MinecraftGpuCandidate.useExternalAquiferBarrier(true, null));
        assertEquals(false, MinecraftGpuCandidate.useExternalAquiferBarrier(false, "wg_node_7"));
        assertEquals(true, MinecraftGpuCandidate.useExternalAquiferBarrier(true, "wg_node_7"));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.useExternalAquiferBarrier(true, " "));
    }

    @Test
    void deviceOnlyEntryRefusesCpuFallbackComparisonAndDraftOptions() {
        for (String option : List.of("nativeDraft", "debugDensityParity", "cpuDensityMaterialFallback",
                "debugDensityStageCpuOracle", "debugDensityStageRootSeedCpu", "debugDensityProbePoint")) {
            String key = "worldgennext.gpuCandidate." + option;
            String previous = System.getProperty(key);
            try {
                System.setProperty(key, option.equals("debugDensityProbePoint") ? "512,-49,512" : "true");
                assertThrows(IllegalArgumentException.class, MinecraftGpuCandidate::requireDeviceOnlyOptions);
            } finally {
                if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
            }
        }
    }

    @Test
    void gpuResultAssemblyRequiresNoCpuTerrainAndPreservesCapturedMetadata() {
        var registry = RegistrySnapshot.minimal();
        var settings = new GeneratorSettingsSnapshot(-16, 16, 8, 0,
                registry.defaultBlock(), registry.defaultFluid(), false, false);
        // The empty router cannot generate CPU terrain. Assembly must consume
        // only the GPU state/mark arrays and the captured prerequisite metadata.
        var snapshot = new WorldgenSnapshot(77L, "minecraft:overworld", "test-settings",
                dev.worldgennext.semantic.snapshot.NoiseRouterSnapshot.empty(),
                new RandomStateSnapshot(77L, 0L, List.of(), List.of()), registry, settings,
                dev.worldgennext.semantic.snapshot.StructureBlendSnapshot.empty(), List.of(),
                dev.worldgennext.semantic.identity.DynamicInputIdentity.empty(), "test-stack");
        var metadata = new dev.worldgennext.material.chunk.ChunkMetadataPayload(java.util.Map.of("biomes", "captured"));
        var inputs = new MinecraftCpuCandidate.Inputs(-2, 3, snapshot, metadata);
        String[] states = new String[4096];
        java.util.Arrays.fill(states, "minecraft:air");
        states[3 * 256] = "minecraft:stone";
        states[4 * 256] = registry.defaultFluid().canonical();
        boolean[] marks = new boolean[states.length];
        marks[4 * 256] = true;
        var result = MinecraftGpuCandidate.resultWithGpuStates(inputs, states, marks);
        assertEquals(-2, result.header().chunkX());
        assertEquals(3, result.header().chunkZ());
        assertEquals(metadata, result.metadata());
        assertEquals(-11, result.heightmaps().values("WORLD_SURFACE_WG")[0]);
        assertEquals(-12, result.heightmaps().values("OCEAN_FLOOR_WG")[0]);
        assertEquals(Set.of("WORLD_SURFACE_WG", "OCEAN_FLOOR_WG"), result.heightmaps().maps().keySet());
        assertEquals(true, result.postProcessing().fluidMarks()[4 * 256]);
        states[3 * 256] = "minecraft:air";
        marks[4 * 256] = false;
        assertEquals("minecraft:stone", result.denseStates()[3 * 256]);
        assertEquals(true, result.postProcessing().fluidMarks()[4 * 256]);
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.resultWithGpuStates(inputs, new String[1], new boolean[1]));
    }

    @Test
    void reusableSplineSelectionSurvivesTheEarlierBundlePlanner() {
        assertTrue(MinecraftGpuCandidate.sharedSplineBundleBoundary("wg_spline_148", List.of("wg_node_101"), true));
        assertTrue(!MinecraftGpuCandidate.sharedSplineBundleBoundary("wg_spline_148", List.of("wg_node_101"), false));
        assertTrue(!MinecraftGpuCandidate.sharedSplineBundleBoundary("wg_spline_149", List.of(), true));
        assertTrue(!MinecraftGpuCandidate.sharedSplineBundleBoundary("wg_node_101", List.of("wg_node_102"), true));
    }

    @Test
    void sharedBlendedMetadataPreservesOffsetsSmearAndPackedTableWithoutHostSampling() {
        StringBuilder source = new StringBuilder("const uint wg_noise_perm_2[256] = uint[256](");
        for (int i = 0; i < 256; i++) source.append(i == 0 ? "" : ",").append(i).append('u');
        source.append("""
                );
                uvec2 wg_noise_smeared_2(uvec2 x, uvec2 y, uvec2 z, uvec2 yScale, uvec2 yMax) {
                    return noise_sample(x, y, z, uvec2(1u,2u), uvec2(3u,4u), uvec2(5u,6u), yScale, yMax, true, wg_noise_perm_2);
                }
                uvec2 wg_noise_legacy_perlin_1(uvec2 x, uvec2 y, uvec2 z, uvec2 yScale, uvec2 yMax) {
                    return wg_noise_smeared_2(x, y, z, yScale, yMax);
                }
                """);
        int[] metadata = MinecraftGpuCandidate.sharedBlendedMetadata(source.toString(), "wg_noise_legacy_perlin_1");
        assertEquals(71, metadata.length);
        assertArrayEquals(new int[]{1,2,3,4,5,6,1}, java.util.Arrays.copyOf(metadata, 7));
        assertEquals(0x03020100, metadata[7]);
        assertEquals(0xfffefdfc, metadata[70]);
        assertThrows(UnsupportedOperationException.class, () -> MinecraftGpuCandidate.sharedBlendedMetadata(
                source.toString().replace("yScale, yMax, true,", "yMax, yScale, true,"), "wg_noise_legacy_perlin_1"));
    }

    @Test
    void sharedPerlinMetadataIsCopiedOnceAndRebasedForPartialBatches() {
        int[] rows = java.util.stream.IntStream.range(0, 30).toArray();
        int[] metadata = java.util.stream.IntStream.range(1000, 1000 + 1 + 16 * 82).toArray();
        int[] packed = MinecraftGpuCandidate.sharedPerlinSuffixInput(rows, metadata, 3);
        assertEquals(rows.length + metadata.length, packed.length);
        assertArrayEquals(rows, java.util.Arrays.copyOf(packed, rows.length));
        assertArrayEquals(metadata, java.util.Arrays.copyOfRange(packed, rows.length, packed.length));

        var shader = new WorldgenShaderCompiler.Shader("#version 450\nvoid main() {}",
                "shared-suffix", NumericProfile.GPU_IEEE_BITS, 64);
        var request = new VulkanWorldgenExecutor.RawRequest(shader, packed, 10, 2, 3);
        var first = request.slice(0, 2);
        var tail = request.slice(2, 1);
        assertArrayEquals(java.util.Arrays.copyOf(rows, 20),
                java.util.Arrays.copyOf(first.inputWords(), 20));
        assertArrayEquals(java.util.Arrays.copyOfRange(rows, 20, 30),
                java.util.Arrays.copyOf(tail.inputWords(), 10));
        assertArrayEquals(metadata, java.util.Arrays.copyOfRange(first.inputWords(), 20,
                first.inputWordCount()));
        assertArrayEquals(metadata, java.util.Arrays.copyOfRange(tail.inputWords(), 10,
                tail.inputWordCount()));
        rows[0] = -1;
        metadata[0] = -1;
        assertEquals(0, packed[0]);
        assertEquals(1000, packed[30]);
    }

    @Test
    void sharedPerlinSuffixRejectsMissingRowsOrIncompleteTables() {
        int[] metadata = new int[1 + 16 * 82];
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.sharedPerlinSuffixInput(new int[9], metadata, 1));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.sharedPerlinSuffixInput(new int[10], new int[82], 1));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.sharedPerlinSuffixInput(new int[0], metadata, 0));
    }

    @Test
    void materialOptionsBindAllCapturedOreStatesToResultLocalIds() {
        RegistrySnapshot registry = registryWithOreStates();
        GeneratorSettingsSnapshot settings = new GeneratorSettingsSnapshot(
                0, 16, 16, 0, registry.defaultBlock(), registry.defaultFluid(), false, true);
        WorldgenSnapshot snapshot = new WorldgenSnapshot(
                77L, "minecraft:overworld", "test-settings",
                dev.worldgennext.semantic.snapshot.NoiseRouterSnapshot.empty(),
                new RandomStateSnapshot(77L, 0L, List.of(), List.of(), null,
                        PositionalRandomFactorySnapshot.legacy(123L)),
                registry, settings, dev.worldgennext.semantic.snapshot.StructureBlendSnapshot.empty(),
                List.of(), dev.worldgennext.semantic.identity.DynamicInputIdentity.empty(), "test-stack");

        var options = MinecraftGpuCandidate.materialOptions(snapshot, BlockStateTable.fromRegistry(registry));

        assertEquals(OreVeinProgram.vanilla(77L), options.oreProgram());
        assertEquals(PositionalRandomFactorySnapshot.legacy(123L), options.oreRandom());
        assertEquals(OreVeinProgram.vanilla(77L).materials().stream()
                .map(registry::id).toList(), options.oreStateIds());
        assertEquals(false, options.aquifer().enabled());
        assertTrue(options.aquifer().defaultFluidFallback());
        assertEquals(registry.id(registry.defaultFluid()), options.aquifer().waterStateId());
        assertEquals(registry.id("minecraft:lava"), options.aquifer().lavaStateId());
    }

    @Test
    void materialOptionsRejectMissingCapturedOreStateInsteadOfUsingAPlaceholderId() {
        RegistrySnapshot registry = RegistrySnapshot.minimal();
        GeneratorSettingsSnapshot settings = new GeneratorSettingsSnapshot(
                0, 16, 16, 0, registry.defaultBlock(), registry.defaultFluid(), false, true);
        WorldgenSnapshot snapshot = new WorldgenSnapshot(
                77L, "minecraft:overworld", "test-settings",
                dev.worldgennext.semantic.snapshot.NoiseRouterSnapshot.empty(),
                new RandomStateSnapshot(77L, 0L, List.of(), List.of(), null,
                        PositionalRandomFactorySnapshot.legacy(123L)),
                registry, settings, dev.worldgennext.semantic.snapshot.StructureBlendSnapshot.empty(),
                List.of(), dev.worldgennext.semantic.identity.DynamicInputIdentity.empty(), "test-stack");

        assertThrows(IllegalStateException.class,
                () -> MinecraftGpuCandidate.materialOptions(snapshot, BlockStateTable.fromRegistry(registry)));
    }

    @Test
    void materialOptionsCaptureAquiferStatusesForTheRequestedChunk() {
        List<BlockStateDescriptor> states = List.of(
                BlockStateDescriptor.of("minecraft:air"),
                BlockStateDescriptor.of("minecraft:stone"),
                new BlockStateDescriptor("minecraft:water", java.util.Map.of("level", "0")),
                new BlockStateDescriptor("minecraft:lava", java.util.Map.of("level", "0")));
        RegistrySnapshot registry = new RegistrySnapshot(states, states.get(1), states.get(2));
        GeneratorSettingsSnapshot settings = new GeneratorSettingsSnapshot(
                0, 16, 16, 0, registry.defaultBlock(), registry.defaultFluid(), true, false);
        var roots = new java.util.LinkedHashMap<String, ProgramNode>();
        for (String name : WorldgenProgram.ROUTER_ROOTS) {
            roots.put(name, new ProgramNode.Constant(ValueType.FP64,
                    name.equals("initialDensityWithoutJaggedness") ? 1.0 : 0.0,
                    EvaluationDomain.WORLD));
        }
        WorldgenSnapshot snapshot = new WorldgenSnapshot(
                77L, "minecraft:overworld", "test-settings",
                new dev.worldgennext.semantic.snapshot.NoiseRouterSnapshot(roots),
                new RandomStateSnapshot(77L, 0L, List.of(), List.of(),
                        PositionalRandomFactorySnapshot.legacy(123L), null),
                registry, settings, dev.worldgennext.semantic.snapshot.StructureBlendSnapshot.empty(),
                List.of(), dev.worldgennext.semantic.identity.DynamicInputIdentity.empty(), "test-stack");

        var options = MinecraftGpuCandidate.materialOptions(snapshot,
                BlockStateTable.fromRegistry(registry), 0, 0);

        assertTrue(options.aquifer().enabled());
        assertEquals(2, options.aquifer().waterStateId());
        assertEquals(3, options.aquifer().lavaStateId());
        assertEquals(36, options.aquifer().candidates().size());
        assertTrue(options.aquifer().candidates().stream()
                .allMatch(candidate -> candidate.stateId() >= 0));
    }

    @Test
    void deviceAllowListIncludesEveryBoundMaterialStageState() {
        var material = new WorldgenShaderCompiler.MaterialOptions(
                OreVeinProgram.vanilla(77L),
                PositionalRandomFactorySnapshot.legacy(123L),
                List.of(10, 11, 12, 13, 14, 15));

        assertArrayEquals(new int[]{3, 1, 99, 10, 11, 12, 13, 14, 15},
                MinecraftGpuCandidate.allowedStateIds(3, 1, 99, material));
    }

    @Test
    void fluidMarkAbiRejectsWordsOtherThanZeroOrOne() {
        assertEquals(false, MinecraftGpuCandidate.decodeFluidMark(0, "test"));
        assertEquals(true, MinecraftGpuCandidate.decodeFluidMark(1, "test"));
        assertThrows(IllegalStateException.class,
                () -> MinecraftGpuCandidate.decodeFluidMark(2, "test"));
    }

    @Test
    void logicalFluidMarksExpandToStorageHeightWithAnAirTail() {
        RegistrySnapshot registry = RegistrySnapshot.minimal();
        GeneratorSettingsSnapshot settings = new GeneratorSettingsSnapshot(
                0, 32, 16, 0, registry.defaultBlock(), registry.defaultFluid(), false, false);
        boolean[] logicalMarks = new boolean[16 * 256];
        logicalMarks[0] = true;
        logicalMarks[logicalMarks.length - 1] = true;

        boolean[] storageMarks = MinecraftGpuCandidate.decodeFluidMarks(logicalMarks, settings);

        assertEquals(32 * 256, storageMarks.length);
        assertEquals(true, storageMarks[0]);
        assertEquals(true, storageMarks[logicalMarks.length - 1]);
        assertEquals(false, storageMarks[logicalMarks.length]);
        assertEquals(false, storageMarks[storageMarks.length - 1]);
    }

    @Test
    void logicalFluidMarkLengthMismatchFailsClosed() {
        RegistrySnapshot registry = RegistrySnapshot.minimal();
        GeneratorSettingsSnapshot settings = new GeneratorSettingsSnapshot(
                0, 32, 16, 0, registry.defaultBlock(), registry.defaultFluid(), false, false);

        assertThrows(IllegalStateException.class,
                () -> MinecraftGpuCandidate.decodeFluidMarks(new boolean[16 * 256 - 1], settings));
    }

    @Test
    void stagedTypedLeafUsesFp64CarrierAndParentRestoresFp32ReturnType() {
        String source = minimalStagedShader();
        String leafSource = MinecraftGpuCandidate.densityStageSource(source, "wg_spline_leaf");
        assertTrue(leafSource.contains("uvec2 value = wg_fp64_from_fp32(wg_spline_leaf(point));"));
        assertTrue(leafSource.contains("uint outputBase = index * 2u;"));

        String parentSource = MinecraftGpuCandidate.densityParentStageSource(
                source, "wg_node_root", List.of("wg_spline_leaf"));
        assertTrue(parentSource.contains("uint base = index * 6u;"));
        assertTrue(parentSource.contains(
                "uint wg_spline_leaf(ivec3 point) { return wg_fp64_to_fp32(wg_stage_density_value(0u, point)); }"));
        assertTrue(parentSource.contains("uint valueBase = index * 6U + 4U + slot * 2U;"));
    }

    @Test
    void aquiferEntryPointSelectorSkipsUvec4IntegerHelpers() {
        String source = """
                uvec4 wg_aquifer_mul_u64_u32_2284(uvec2 value, uint multiplier) { return uvec4(0u); }
                uvec4 wg_aquifer_2284(ivec3 point, uvec2 density) { return uvec4(1u); }
                """;

        assertEquals("wg_aquifer_2284", MinecraftGpuCandidate.aquiferFunctionName(source));
        String disabled = """
                uvec4 wg_aquifer_mul_u64_u32_47(uvec2 value, uint multiplier) { return uvec4(0u); }
                uvec4 wg_aquifer_default_fluid_47(ivec3 point, uvec2 density) { return uvec4(1u); }
                """;
        assertEquals("wg_aquifer_default_fluid_47", MinecraftGpuCandidate.aquiferFunctionName(disabled));
        assertThrows(UnsupportedOperationException.class, () -> MinecraftGpuCandidate.aquiferFunctionName(
                "uvec4 wg_aquifer_mul_u64_u32_47(uvec2 value, uint multiplier) { return uvec4(0u); }"));
    }

    @Test
    void independentDensityRootsShareOneMultiCarrierStageAbi() {
        String source = minimalStagedShader().replace(
                "// bounds check is mandatory",
                "uvec2 wg_node_other(ivec3 point) { return uvec2(uint(point.z), 0u); }\n"
                        + "// bounds check is mandatory");

        String bundled = MinecraftGpuCandidate.densityStageBundleSource(
                source, List.of("wg_spline_leaf", "wg_node_other"));

        assertTrue(bundled.contains("uvec2 value0 = wg_fp64_from_fp32(wg_spline_leaf(point));"));
        assertTrue(bundled.contains("uvec2 value1 = wg_node_other(point);"));
        assertTrue(bundled.contains("uint outputBase = index * 4u;"));
        assertTrue(bundled.contains("outputBits[outputBase + 2u] = value1.x;"));
        assertTrue(bundled.contains("outputBits[outputBase + 3u] = value1.y;"));
    }

    @Test
    void stagedDensityFinalMaterializesSelectorCornersInsteadOfRetainingSelectorGraph() {
        String source = minimalDensityStagedShader();

        String finalSource = MinecraftGpuCandidate.densityFinalStageSource(source);

        assertTrue(finalSource.contains(
                "uvec2 wg_node_3(ivec3 point) { return wg_stage_density_value(2u, point); }"));
        assertTrue(finalSource.contains("uint value = index * 53U + 3U + slot * 16U + corner * 2U;"));
        assertTrue(finalSource.contains("inputBits[index * 53u + 51u]"));
        assertTrue(!finalSource.contains("uvec2 wg_node_3(ivec3 point) { return uvec2(0u, 2u); }"));
    }

    @Test
    void stagedCornerLookupUsesCapturedInterpolationGeometry() {
        String source = minimalDensityStagedShader().replace(
                "uvec2 wg_node_4(ivec3 point) { return uvec2(0u); }", """
                        uvec2 wg_node_4(ivec3 point) {
                            int x0, x1, y0, y1, z0, z1; uvec2 fraction;
                            if (!wg_cell_axis64(point.x, 2, x0, x1, fraction)) return uvec2(0u);
                            if (!wg_cell_axis64(point.y, 8, y0, y1, fraction)) return uvec2(0u);
                            if (!wg_cell_axis64(point.z, 2, z0, z1, fraction)) return uvec2(0u);
                            ivec3 p000 = point; p000.x = x0; p000.y = y0; p000.z = z0;
                            return uvec2(uint(p000.x), 0u);
                        }
                        """);

        String finalSource = MinecraftGpuCandidate.densityFinalStageSource(source);

        assertTrue(finalSource.contains("wg_cell_axis64(original.x, 2"));
        assertTrue(finalSource.contains("wg_cell_axis64(original.y, 8"));
        assertTrue(finalSource.contains("wg_cell_axis64(original.z, 2"));
    }

    @Test
    void stagedCompactionPreservesForwardPrototypes() {
        String source = minimalStagedShader().replace(
                "uint wg_spline_leaf(ivec3 point) { return uint(point.x); }",
                "uvec2 wg_blend_height_offset_71(uvec2 height);\n"
                        + "uint wg_spline_leaf(ivec3 point) { return uint(point.x); }");

        String compacted = MinecraftGpuCandidate.densityStageSource(source, "wg_spline_leaf");

        assertTrue(compacted.contains("uvec2 wg_blend_height_offset_71(uvec2 height);"));
    }

    @Test
    void stagedCompactionDropsUnreferencedNoiseTables() {
        String source = """
                #version 450
                const uint wg_noise_perm_keep[2] = uint[2](1u, 2u);
                const uint wg_noise_perm_drop[2] = uint[2](3u, 4u);
                uvec2 wg_node_1(ivec3 point) { return uvec2(wg_noise_perm_keep[point.x & 1]); }
                """;

        String compacted = MinecraftGpuCandidate.compactSource(source, Set.of("wg_node_1"));

        assertTrue(compacted.contains("wg_noise_perm_keep"));
        assertTrue(!compacted.contains("wg_noise_perm_drop"));
    }

    @Test
    void stagedCompactionPacksCapturedNoiseTablesAndRewritesDeviceLookups() {
        StringBuilder source = new StringBuilder("""
                #version 450
                const uint wg_noise_perm_keep[256] = uint[256](
                """);
        for (int index = 0; index < 256; index++) {
            if (index > 0) source.append(',');
            source.append(index).append('u');
        }
        source.append("""
                );
                uvec2 wg_node_1(ivec3 point) { return uvec2(wg_noise_perm_keep[point.x & 255]); }
                """);

        String compacted = MinecraftGpuCandidate.compactSource(source.toString(), Set.of("wg_node_1"));

        assertTrue(compacted.contains("const uint wg_noise_perm_keep[64] = uint[64](0x03020100u"));
        assertTrue(compacted.contains("uint wg_noise_perm_keep_lookup(uint index)"));
        assertTrue(compacted.contains("wg_noise_perm_keep_lookup(point.x & 255)"));
    }

    @Test
    void stagedCapturedSamplerSpecializesPackedPermutationTables() {
        List<Integer> permutation = java.util.stream.IntStream.range(0, 256).boxed().toList();
        var level = new dev.worldgennext.semantic.snapshot.NoiseParameters.ImprovedNoiseSnapshot(
                0.0, 0.0, 0.0, permutation);
        var perlin = new dev.worldgennext.semantic.snapshot.NoiseParameters.PerlinNoiseSnapshot(
                0, List.of(1.0), List.of(level));
        var captured = new dev.worldgennext.semantic.snapshot.NoiseParameters.CapturedNoise(
                1234L, 1.0, perlin, perlin);
        var parameters = new dev.worldgennext.semantic.snapshot.NoiseParameters(
                "minecraft:test", 0, List.of(1.0), 0L, captured);
        var shader = new WorldgenShaderCompiler().emit(
                WorldgenProgram.builder().root("finalDensity", new ProgramNode.Noise(
                        "minecraft:test", parameters, 0.5, 2.0, ValueType.FP64,
                        EvaluationDomain.WORLD)).build(), NumericProfile.GPU_IEEE_BITS, 64);
        String source = shader.source();
        String sharedSource = MinecraftGpuCandidate.sharedPerlinGenericSource(source, 10);
        String blendedSource = MinecraftGpuCandidate.sharedBlendedSampleSource(source);
        assertTrue(blendedSource.contains("uint metadata = dispatch.count * 10u;"));
        assertTrue(blendedSource.contains("inputBits[base + 6u]"));
        assertTrue(blendedSource.contains("inputBits[base + 8u]"));
        assertTrue(blendedSource.contains("inputBits[metadata + 6u] != 0u, metadata + 7u"));
        assertTrue(blendedSource.contains("if (index >= dispatch.count) return;"));
        dev.worldgennext.compiler.vulkan.worldgen.SpirvNumericContract.require(blendedSource, NumericProfile.GPU_IEEE_BITS);
        assertTrue(sharedSource.contains("uint metadataBase = dispatch.count * 10u;"));
        assertTrue(sharedSource.contains("wg_shared_perlin_generic(index * 10u)"));
        assertTrue(sharedSource.contains("if (index >= dispatch.count) return;"));
        assertTrue(sharedSource.contains("inputBits[levelBase + 13u]"));
        assertTrue(sharedSource.contains("inputBits[levelBase + 15u]"));
        assertTrue(!sharedSource.contains("metadataBase = base + 10u"));
        assertThrows(IllegalArgumentException.class,
                () -> MinecraftGpuCandidate.sharedPerlinGenericSource(source, 1323));
        int marker = source.indexOf("uvec2 wg_noise_normal_");
        assertTrue(marker >= 0);
        int nameStart = marker + "uvec2 ".length();
        String root = source.substring(nameStart, source.indexOf('(', nameStart));

        String compacted = MinecraftGpuCandidate.compactSource(source, Set.of(root));

        assertTrue(compacted.contains("noise_sample_wg_noise_perm_"));
        assertTrue(compacted.contains("noise_table_value_wg_noise_perm_"));
        assertTrue(compacted.contains("uint wg_noise_perm_"));
        assertTrue(!compacted.contains("in uint permutation[64]"));
        assertTrue(!compacted.contains("uint tableId"));
        assertTrue(!compacted.contains("uvec2 noise_sample("));
        assertTrue(!compacted.contains("uint noise_table_value("));
    }

    @Test
    void staticBlendedNoiseGroupsWriteSuccessfulCarriersOutsideFailureBranch() {
        String source = """
                #version 450
                layout(local_size_x=64, local_size_y=1, local_size_z=1) in;
                layout(std430, binding=0) readonly buffer Inputs { uint inputBits[]; };
                layout(std430, binding=1) writeonly buffer Outputs { uint outputBits[]; };
                uvec2 wg_fp64_qnan() { return uvec2(0u); }
                uvec2 wg_fp64_from_int(int value) { return uvec2(uint(value), 0u); }
                uvec2 wg_fp64_mul(uvec2 left, uvec2 right) { return left; }
                uvec2 wg_fp64_div(uvec2 left, uvec2 right) { return left; }
                uvec2 wg_noise_wrap(uvec2 value) { return value; }
                uvec2 wg_noise_legacy_perlin_86(uvec2 a, uvec2 b, uvec2 c, uvec2 d, uvec2 e) { return a; }
                ivec3 wg_point(uint index) {
                    uint base = index * 4u;
                    return ivec3(int(inputBits[base]), int(inputBits[base + 1u]), int(inputBits[base + 2u]));
                }
                // bounds check is mandatory
                """;

        String emitted = MinecraftGpuCandidate.noiseSampleSourceForSpecs(source,
                List.of(new MinecraftGpuCandidate.SampleSpec("wg_noise_legacy_perlin_86", 0, "main")),
                "uvec2 d3=uvec2(0u), d4=uvec2(0u), d5=uvec2(0u), d7=uvec2(0u);");

        int failed = emitted.indexOf("if (wg_failed) {");
        int success = emitted.indexOf("outputBits[outputBase + 0u] = value0.x;");
        int elseBranch = emitted.indexOf("} else {", failed);
        assertTrue(failed >= 0);
        assertTrue(elseBranch > failed);
        assertTrue(success > elseBranch);
        assertTrue(emitted.contains("outputBits[outputBase + 0u] = value.x;"));
    }

    @Test
    void oversizedDensityAncestorsBecomeChildBeforeParentStages() {
        List<String> manifest = MinecraftGpuCandidate.densityStagePlanManifest(
                recursiveDensityShader(), List.of("wg_node_9999"));

        assertEquals("wg_node_199 children=", manifest.get(0));
        assertEquals("wg_node_200 children=wg_node_199", manifest.get(1));
        assertEquals("wg_node_9999 children=wg_node_205", manifest.get(manifest.size() - 1));
        assertTrue(manifest.indexOf("wg_node_205 children=wg_node_204")
                < manifest.indexOf("wg_node_9999 children=wg_node_205"));
    }

    @Test
    void alreadyMaterializedInterpolationIsNotReinlinedThroughSmallAncestors() {
        String source = minimalStagedShader().replace("// bounds check is mandatory", """
                uvec2 wg_node_10(ivec3 point) { return uvec2(uint(point.x), 0u); }
                uvec2 wg_node_11(ivec3 point) { return wg_node_10(point); }
                uvec2 wg_node_12(ivec3 point) { return wg_node_11(point); }
                // bounds check is mandatory
                """);
        assertEquals(List.of("wg_node_10 children=", "wg_node_11 children=wg_node_10",
                        "wg_node_12 children=wg_node_11"),
                MinecraftGpuCandidate.densityStagePlanManifest(source, List.of("wg_node_12"), Set.of("wg_node_10")));
        String parent = MinecraftGpuCandidate.densityParentStageSource(source, "wg_node_12", List.of("wg_node_11"));
        assertTrue(!parent.contains("uvec2 wg_node_10("));
        assertTrue(parent.contains("wg_stage_density_value(0u, point)"));
    }

    @Test
    void splineCoordinatesAreStagedEvenBelowTheSourceBudgetButConstantKnotsStayInline() {
        String source = minimalStagedShader().replace(
                "uint wg_spline_leaf(ivec3 point) { return uint(point.x); }",
                """
                uvec2 wg_node_coordinate(ivec3 point) { return uvec2(uint(point.x), 0u); }
                uint wg_spline_constant(ivec3 point) { return 0x3f800000u; }
                uint wg_spline_leaf(ivec3 point) {
                    return wg_fp64_to_fp32(wg_node_coordinate(point)) + wg_spline_constant(point);
                }
                """);
        List<String> manifest = MinecraftGpuCandidate.densityStagePlanManifest(
                source, List.of("wg_spline_leaf"));
        assertEquals(List.of("wg_node_coordinate children=",
                "wg_spline_leaf children=wg_node_coordinate"), manifest);
    }

    @Test
    void largeDirectDensityStageSplitsOnlyWhenItsParentGetsSmaller() {
        String padding = "/* large-density-child */" + "x".repeat(300_000) + "*/";
        String source = minimalStagedShader().replace(
                "uint wg_spline_leaf(ivec3 point) { return uint(point.x); }",
                "uint wg_spline_leaf(ivec3 point) { " + padding + " return uint(point.x); }");

        List<String> manifest = MinecraftGpuCandidate.densityStagePlanManifest(
                source, List.of("wg_node_root"));

        assertEquals("wg_spline_leaf children=", manifest.get(0));
        assertEquals("wg_node_root children=wg_spline_leaf", manifest.get(1));
    }

    private static String minimalStagedShader() {
        return """
                #version 450
                layout(local_size_x=64, local_size_y=1, local_size_z=1) in;
                layout(std430, binding=0) readonly buffer Inputs { uint inputBits[]; };
                layout(std430, binding=1) writeonly buffer Outputs { uint outputStateIds[]; };
                layout(push_constant) uniform Dispatch { uint count; uint defaultStateId; uint airStateId; uint invalidStateId; } dispatch;
                bool wg_failed;
                int wg_i32_from_bits(uint bits) { return int(bits); }
                ivec3 wg_point(uint index) {
                    uint base = index * 4u;
                    return ivec3(wg_i32_from_bits(inputBits[base]), wg_i32_from_bits(inputBits[base + 1u]), wg_i32_from_bits(inputBits[base + 2u]));
                }
                uvec2 wg_fp64_qnan() { return uvec2(0u); }
                bool wg_fp64_finite(uvec2 bits) { return true; }
                uvec2 wg_fp64_from_fp32(uint bits) { return uvec2(bits, 0u); }
                uint wg_fp64_to_fp32(uvec2 bits) { return bits.x; }
                bool wg_cell_axis64(int coordinate, int cell, out int lower, out int upper, out uvec2 fraction) {
                    lower = coordinate; upper = coordinate + cell; fraction = uvec2(0u); return true;
                }
                uint wg_spline_leaf(ivec3 point) { return uint(point.x); }
                uvec2 wg_node_root(ivec3 point) { return wg_fp64_from_fp32(wg_spline_leaf(point)); }
                // bounds check is mandatory
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    outputStateIds[index] = dispatch.defaultStateId;
                }
                """;
    }

    private static String minimalDensityStagedShader() {
        return """
                #version 450
                layout(local_size_x=64, local_size_y=1, local_size_z=1) in;
                layout(std430, binding=0) readonly buffer Inputs { uint inputBits[]; };
                layout(std430, binding=1) writeonly buffer Outputs { uint outputStateIds[]; };
                layout(push_constant) uniform Dispatch { uint count; uint defaultStateId; uint airStateId; uint invalidStateId; } dispatch;
                bool wg_failed;
                int wg_i32_from_bits(uint bits) { return int(bits); }
                ivec3 wg_point(uint index) {
                    uint base = index * 4u;
                    return ivec3(wg_i32_from_bits(inputBits[base]), wg_i32_from_bits(inputBits[base + 1u]), wg_i32_from_bits(inputBits[base + 2u]));
                }
                uvec2 wg_fp64_qnan() { return uvec2(0u); }
                bool wg_fp64_finite(uvec2 bits) { return true; }
                uvec2 wg_fp64_min(uvec2 left, uvec2 right) { return left; }
                uint wg_material_decide64(uvec2 density, bool aquiferCandidate, uint aquiferState,
                        bool oreCandidate, uint oreState, uint defaultStateId,
                        uint airStateId, bool noAquiferUsesDefault) { return defaultStateId; }
                uvec2 wg_node_4(ivec3 point) { return uvec2(0u); }
                uvec2 wg_node_5(ivec3 point) { return uvec2(0u, 1u); }
                uvec2 wg_node_3(ivec3 point) { return uvec2(0u, 2u); }
                uvec2 wg_node_1(ivec3 point) {
                    uvec2 selector = wg_node_3(point);
                    if (selector.x == 0u) return wg_node_4(point);
                    return wg_node_5(point);
                }
                uvec2 wg_node_2(ivec3 point) { return uvec2(0u, 3u); }
                uvec2 wg_node_0(ivec3 point) {
                    return wg_fp64_min(wg_node_1(point), wg_node_2(point));
                }
                // bounds check is mandatory
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 density = wg_node_0(point);
                    if (wg_failed || !wg_fp64_finite(density)) {
                        outputStateIds[index * 2u] = dispatch.invalidStateId;
                        outputStateIds[index * 2u + 1u] = 0u;
                    } else {
                        outputStateIds[index * 2u] = wg_material_decide64(density, false, 0u, false, 0u,
                                dispatch.defaultStateId, dispatch.airStateId, false);
                        outputStateIds[index * 2u + 1u] = 0u;
                    }
                }
                """;
    }

    private static String recursiveDensityShader() {
        StringBuilder source = new StringBuilder("""
                #version 450
                layout(local_size_x=64, local_size_y=1, local_size_z=1) in;
                layout(std430, binding=0) readonly buffer Inputs { uint inputBits[]; };
                layout(std430, binding=1) writeonly buffer Outputs { uint outputStateIds[]; };
                layout(push_constant) uniform Dispatch { uint count; uint defaultStateId; uint airStateId; uint invalidStateId; } dispatch;
                bool wg_failed;
                int wg_i32_from_bits(uint bits) { return int(bits); }
                ivec3 wg_point(uint index) {
                    uint base = index * 4u;
                    return ivec3(wg_i32_from_bits(inputBits[base]), wg_i32_from_bits(inputBits[base + 1u]), wg_i32_from_bits(inputBits[base + 2u]));
                }
                uvec2 wg_fp64_qnan() { return uvec2(0u); }
                bool wg_fp64_finite(uvec2 bits) { return true; }
                """);
        source.append("uvec2 wg_node_0(ivec3 point) { return uvec2(0u); }\n");
        for (int index = 1; index <= 205; index++) {
            source.append("uvec2 wg_node_").append(index).append("(ivec3 point) { return wg_node_")
                    .append(index - 1).append("(point); }\n");
        }
        source.append("uvec2 wg_node_9999(ivec3 point) { return wg_node_205(point); }\n")
                .append("// bounds check is mandatory\n")
                .append("void main() {\n")
                .append("    uint index = gl_GlobalInvocationID.x;\n")
                .append("    if (index >= dispatch.count) return;\n")
                .append("    outputStateIds[index] = dispatch.defaultStateId;\n")
                .append("}\n");
        return source.toString();
    }

    private static RegistrySnapshot registryWithOreStates() {
        List<BlockStateDescriptor> states = List.of(
                BlockStateDescriptor.of("minecraft:air"),
                BlockStateDescriptor.of("minecraft:stone"),
                BlockStateDescriptor.of("minecraft:water"),
                BlockStateDescriptor.of("minecraft:copper_ore"),
                BlockStateDescriptor.of("minecraft:raw_copper_block"),
                BlockStateDescriptor.of("minecraft:granite"),
                BlockStateDescriptor.of("minecraft:deepslate_iron_ore"),
                BlockStateDescriptor.of("minecraft:raw_iron_block"),
                BlockStateDescriptor.of("minecraft:tuff"),
                BlockStateDescriptor.of("minecraft:lava"));
        return new RegistrySnapshot(states, states.get(1), states.get(2));
    }
}
