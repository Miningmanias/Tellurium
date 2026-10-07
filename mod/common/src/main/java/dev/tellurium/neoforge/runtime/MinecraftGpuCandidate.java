// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract;
import dev.tellurium.compiler.vulkan.worldgen.NativeDraftMath;
import dev.tellurium.compiler.vulkan.worldgen.WorldgenShaderCompiler;
import dev.tellurium.compiler.vulkan.worldgen.SharedSplineStageEmitter;
import dev.tellurium.compiler.vulkan.worldgen.SharedFp64DivisionStageEmitter;
import dev.tellurium.compiler.vulkan.worldgen.SharedBlendedReductionStageEmitter;
import dev.tellurium.material.chunk.BlockStateTable;
import dev.tellurium.material.chunk.ChunkNoiseResult;
import dev.tellurium.material.chunk.ChunkResultHeader;
import dev.tellurium.material.chunk.HeightmapPayload;
import dev.tellurium.compiler.jvm.worldgen.AquiferEvaluator;
import dev.tellurium.compiler.jvm.worldgen.DenseNoiseGenerator;
import dev.tellurium.compiler.jvm.worldgen.NoiseEvaluator;
import dev.tellurium.compiler.vulkan.worldgen.AquiferEmitter;
import dev.tellurium.compiler.vulkan.worldgen.BeardifierEmitter;
import dev.tellurium.compiler.vulkan.worldgen.BeardifierKernelInputStage;
import dev.tellurium.runtime.vulkan.DeviceCapabilities;
import dev.tellurium.runtime.vulkan.Hashes;
import dev.tellurium.runtime.vulkan.production.VulkanWorldgenExecutor;
import dev.tellurium.semantic.program.NumericProfile;
import dev.tellurium.semantic.program.ProgramNode;
import dev.tellurium.semantic.program.ValueType;
import dev.tellurium.semantic.program.WorldgenProgram;
import dev.tellurium.semantic.material.AquiferProgram;
import dev.tellurium.semantic.material.OreVeinProgram;
import dev.tellurium.semantic.snapshot.GeneratorSettingsSnapshot;
import dev.tellurium.semantic.snapshot.BlendedNoiseParameters;
import dev.tellurium.semantic.snapshot.StructureBlendSnapshot;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in real-device replay of a captured Minecraft NOISE program.
 *
 * <p>This is deliberately an isolated diagnostic path.  It captures the
 * immutable inputs and CPU result through {@link MinecraftCpuCandidate},
 * lowers the same snapshot to the integer-carrier shader, dispatches every
 * logical block through one persistent executor, and compares the resulting
 * registry-local states before writing an artifact.  It does not install a
 * Minecraft generation hook or publish a result into a live chunk.</p>
 *
 * <p>The dense shader material boundary captures aquifer cell statuses and
 * evaluates nearest-three pressure on the device.  Structure/blending input
 * remains a separate admission boundary.  When the captured generator
 * enables ores, the existing six-material device stage is included after
 * strict registry/RNG validation.</p>
 */
public final class MinecraftGpuCandidate {
    private static final int DENSITY_STAGE_CORNER_COUNT = 8;
    private static final int DENSITY_STAGE_VALUE_WORDS = 2;
    private static final int DENSITY_STAGE_CORNER_VALUE_WORDS =
            DENSITY_STAGE_CORNER_COUNT * DENSITY_STAGE_VALUE_WORDS;
    private static final int DENSITY_STAGE_CORNER_FRACTION_WORDS = 3 * DENSITY_STAGE_VALUE_WORDS;
    private static final int DENSITY_STAGE_CORNER_VALUE_BASE_WORDS =
            4 + DENSITY_STAGE_CORNER_FRACTION_WORDS;
    private static final int BLENDED_NOISE_SAMPLE_COUNT = 40;
    // One literal octave per exact shader is slower, but it is the smallest
    // driver-visible unit that completed the captured Nether route reliably.
    // Native-draft uses a larger default only because the target-driver
    // embedded-root smoke reached the grouped path without a fault; it is not
    // an exact-parity or production qualification.
    private static final int BLENDED_NOISE_DEFAULT_STATIC_GROUP_SIZE = 1;
    private static final int BLENDED_NOISE_INPUT_WORDS = 4
            + BLENDED_NOISE_SAMPLE_COUNT * DENSITY_STAGE_VALUE_WORDS;
    private static final int DENSITY_SELECTOR_SLOT = 2;
    private static final int DENSITY_DIRECT_BRANCH_SLOT = 3;
    private static final int DENSITY_FINAL_BLOCK_WORDS =
            3 + DENSITY_STAGE_CORNER_VALUE_WORDS * 3 + DENSITY_STAGE_VALUE_WORDS;
    /** Material-input rows also carry the exact host-derived interpolation fractions. */
    private static final int DENSITY_MATERIAL_INPUT_FRACTION_WORDS = DENSITY_STAGE_CORNER_FRACTION_WORDS;
    private static final int DENSITY_MATERIAL_INPUT_VALUE_BASE_WORDS =
            3 + DENSITY_MATERIAL_INPUT_FRACTION_WORDS;
    private static final int DENSITY_MATERIAL_INPUT_WORDS =
            DENSITY_MATERIAL_INPUT_VALUE_BASE_WORDS
                    + DENSITY_STAGE_CORNER_VALUE_WORDS * 3 + DENSITY_STAGE_VALUE_WORDS;
    private static final int AQUIFER_STAGE_INPUT_WORDS = 5;
    private static final int AQUIFER_STAGE_EXTERNAL_INPUT_WORDS = 7;
    private static final int ORE_STAGE_INPUT_WORDS = 4;
    /** Coordinates/reserved4, then device-produced toggle/ridged/gap FP64 carriers. */
    private static final int ORE_STAGE_EXTERNAL_INPUT_WORDS = 10;
    private static final int ORE_STAGE_FINISH_INPUT_WORDS = 14;
    private static final int MATERIAL_STAGE_INPUT_WORDS = 11;
    /** Fixed row used by the device-resident post-density chain. */
    private static final int MATERIAL_RESIDENT_WORDS = 13;
    /** Resident row variant with the external aquifer barrier carrier. */
    private static final int MATERIAL_RESIDENT_EXTERNAL_WORDS = 15;
    private static final int MATERIAL_RESIDENT_DENSITY_WORD = 3;
    private static final int MATERIAL_RESIDENT_AQUIFER_WORD = 5;
    private static final int MATERIAL_RESIDENT_ORE_WORD = 9;
    private static final int MATERIAL_RESIDENT_STATE_WORD = 11;
    private static final int MATERIAL_RESIDENT_FLUID_WORD = 12;
    private static final int MATERIAL_RESIDENT_BARRIER_WORD = 11;
    private static final int MATERIAL_RESIDENT_EXTERNAL_STATE_WORD = 13;
    private static final int MATERIAL_RESIDENT_EXTERNAL_FLUID_WORD = 14;
    private static final int BEARDIFIER_STAGE_INPUT_WORDS = 6;
    private static final int NORMAL_NOISE_COORD_OUTPUT_WORDS = 6;
    private static final int NORMAL_NOISE_SAMPLE_INPUT_WORDS = 10;
    private static final int NORMAL_NOISE_COMBINE_INPUT_WORDS = 14;
    /**
     * Shared Perlin metadata keeps captured permutation tables and octave
     * factors in the raw carrier instead of baking them into one shader per
     * captured function.  The draft Overworld has at most sixteen levels;
     * rows stay bounded even when a later capture contains sparse levels.
     */
    private static final int SHARED_PERLIN_MAX_LEVELS = 16;
    // active + (input/value/amplitude/offset/y-scale/y-max/smear = 18)
    // + 64 packed table words. Keep the complete noise_sample ABI in the row;
    // dropping the y-axis controls changes captured Minecraft noise semantics.
    private static final int SHARED_PERLIN_LEVEL_WORDS = 82;
    private static final int SHARED_PERLIN_METADATA_WORDS = 1
            + SHARED_PERLIN_MAX_LEVELS * SHARED_PERLIN_LEVEL_WORDS;
    private static final int SHARED_PERLIN_INPUT_WORDS = NORMAL_NOISE_SAMPLE_INPUT_WORDS
            + SHARED_PERLIN_METADATA_WORDS;
    private static final int FP64_DIVISION_STATE_WORDS = SharedFp64DivisionStageEmitter.STATE_WORDS;
    private static final int FP64_DIVISION_CHUNK_COUNT = SharedFp64DivisionStageEmitter.CHUNK_COUNT;
    private static final int DENSITY_PIPELINE_RECLAIM_INTERVAL = 8;
    private static final int BLENDED_NOISE_DEFAULT_PIPELINE_RECLAIM_INTERVAL = 2;
    /**
     * A captured vanilla spline graph can contain several thousand functions.
     * Keep every leaf module below a conservative driver-visible reachability
     * bound; the complete graph is still recombined by GPU stages.
     */
    private static final int DENSITY_STAGE_MAX_REACHABLE_FUNCTIONS = 200;
    /** Split large stages only when replacing children actually reduces source. */
    private static final int DENSITY_STAGE_SOURCE_SPLIT_THRESHOLD = 75_000;
    /** Spline bodies can be small while their transitive child graph is large. */
    private static final int DENSITY_STAGE_SPLINE_SOURCE_SPLIT_THRESHOLD = 75_000;
    /**
     * The captured Overworld has a high-complexity node parent below the
     * general source limit. Split that node family earlier, but leave the
     * many ordinary spline parents on the bounded 75 KB policy.
     */
    private static final int DENSITY_STAGE_COMPLEX_NODE_SPLIT_THRESHOLD = 60_000;
    /** Split medium-sized FP64 arithmetic nodes before the driver sees a deep carrier subgraph. */
    private static final int DENSITY_STAGE_FP64_NODE_SPLIT_THRESHOLD = 16_000;
    private static final int DENSITY_STAGE_MAX_PLAN_COUNT = 2_048;
    /** Bounded fan-in for independent graph roots sharing one coordinate ABI. */
    private static final int DENSITY_STAGE_BUNDLE_MAX_ROOTS = 8;
    /** Bound the union of child carriers in a multi-root parent stage. */
    private static final int DENSITY_STAGE_BUNDLE_MAX_CHILD_ROOTS = 16;
    /** Keep bundled modules below the draft driver's known source envelope. */
    private static final int DENSITY_STAGE_BUNDLE_MAX_SOURCE_CHARS = 100_000;
    /** Fail closed before shaderc/driver compilation reaches the known large-source envelope. */
    private static final int GPU_SHADER_SOURCE_MAX_CHARS = 900_000;
    /** Maximum graph stages carried by one experimental resident density row. */
    private static final int DENSITY_RESIDENT_DEFAULT_MAX_STAGES = 16;
    /**
     * Keep graph, captured-noise wrappers, per-table samplers and integer IEEE
     * helper calls as compilation boundaries. The staged sampler has already
     * removed its array parameter and table-ID switch; keeping the specialized
     * ABI explicit prevents the driver from expanding every octave into its
     * caller.
     */
    private static final String DENSITY_DONT_INLINE_PREFIX =
            "noise_sample,noise_table_value,wg_node_,wg_spline_,wg_noise_blended_,wg_noise_normal_,wg_noise_perlin_,"
                    + "wg_noise_improved_,wg_noise_smeared_,wg_noise_legacy_perlin_,wg_noise_interpolate,"
                    + "wg_i32_,wg_u32_,wg_u64_,wg_u128_,wg_fp32_,wg_fp64_";
    /**
     * The direct second-branch replay owns an explicit normal-noise fan-out.
     * Keep only captured graph boundaries here: marking the captured noise
     * wrapper DontInline can return zero on the target driver, while the
     * isolated Perlin stages are intentionally compiled without this policy.
     */
    private static final String DIRECT_DENSITY_DONT_INLINE_PREFIX =
            "wg_node_,wg_spline_";
    /**
     * Native-draft graph bundles need arithmetic callable boundaries as well
     * as graph boundaries.  This is intentionally narrower than the exact
     * density policy: keeping captured normal-noise wrappers inline avoids the
     * target-driver zero-result behavior observed on the exact direct route.
     */
    private static final String NATIVE_DRAFT_DIRECT_DONT_INLINE_PREFIX =
            "wg_node_,wg_spline_,wg_fp64_,wg_i32_,wg_u32_,wg_u64_,wg_u128_,"
                    + "wg_noise_perlin_,wg_noise_improved_,wg_noise_smeared_,"
                    + "wg_noise_legacy_perlin_,wg_noise_interpolate";
    /**
     * Material stages are smaller than the captured density graph, but the
     * aquifer pressure path still contains enough integer IEEE helpers to
     * make unrestricted driver inlining exceed the target driver's compile
     * envelope. Keep the material ABI at explicit callable boundaries too.
     */
    private static final String MATERIAL_DONT_INLINE_PREFIX = "wg_,noise_";
    private static final String END_DONT_INLINE_PREFIX = "wg_";
    /** The captured shader is reparsed by several staged frontiers; retain its identity-keyed indexes. */
    private static final int LARGE_CAPTURE_SOURCE_CHARS = 64_000;
    private static final ThreadLocal<Map<String, Map<String, GlslFunction>>>
            LARGE_SOURCE_FUNCTION_CACHE = ThreadLocal.withInitial(() -> boundedSourceCache(16));
    private static final ThreadLocal<Map<String, Map<String, Set<String>>>>
            LARGE_SOURCE_CALL_CACHE = ThreadLocal.withInitial(() -> boundedSourceCache(16));
    /** Reuse call extraction for the unchanged function bodies in transformed large captures. */
    private static final ThreadLocal<Map<FunctionCallCacheKey, Set<String>>>
            LARGE_FUNCTION_CALL_CACHE = ThreadLocal.withInitial(() -> boundedSourceCache(8192));
    private static final ThreadLocal<Map<String, String>>
            LARGE_SOURCE_STAGE_PREFIX_CACHE = ThreadLocal.withInitial(() -> boundedSourceCache(16));
    private static final ThreadLocal<Map<String, Map<String, String>>>
            LARGE_SOURCE_COMPACT_CACHE = ThreadLocal.withInitial(() -> boundedSourceCache(16));

    private static <K, V> Map<K, V> boundedSourceCache(int limit) {
        return new LinkedHashMap<K, V>(limit, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > limit;
            }
        };
    }
    /**
     * Native-draft replay keeps the exact emitted shader beside the rewritten
     * shader so normal-noise leaf stages can use the proven integer-carrier
     * arithmetic without making the complete Overworld module compile again.
     * The candidate runs on one worker thread, so a thread-local is enough for
     * this deliberately provisional bridge.
     */
    private static final ThreadLocal<WorldgenShaderCompiler.Shader>
            EXACT_NORMAL_NOISE_SHADER = new ThreadLocal<>();
    /**
     * Explicit draft bridge for isolating GPU material consumers from the
     * unqualified native density carrier. It is populated only by the
     * opt-in CPU-density material experiment and is never a production route.
     */
    private static final ThreadLocal<double[]> MATERIAL_DENSITY_FALLBACK = new ThreadLocal<>();
    /** Bounded captured-source cache; long-lived workers must not retain every chunk graph. */
    private static final ThreadLocal<Map<String, WorldgenShaderCompiler.Shader>>
            SHARED_PERLIN_GENERIC_SHADER_CACHE = ThreadLocal.withInitial(() -> boundedSourceCache(16));
    /** Optional first-divergent-stage CPU oracle for one bounded GPU probe. */
    private static final ThreadLocal<StageCpuOracle> DENSITY_STAGE_CPU_ORACLE = new ThreadLocal<>();
    private static final ThreadLocal<IdentityHashMap<String, WorldgenShaderCompiler.Shader>>
            SHARED_SPLINE_SHADER_CACHE = ThreadLocal.withInitial(IdentityHashMap::new);

    private record StageCpuKey(String root, int x, int y, int z) { }

    private static final class StageCpuOracle {
        private final MinecraftCpuCandidate candidate;
        private final MinecraftCpuCandidate.Inputs inputs;
        private final Map<String, ProgramNode> nodes;
        private final Map<StageCpuKey, Double> values = new HashMap<>();

        private StageCpuOracle(MinecraftCpuCandidate candidate,
                               MinecraftCpuCandidate.Inputs inputs,
                               Map<String, ProgramNode> nodes) {
            this.candidate = Objects.requireNonNull(candidate, "candidate");
            this.inputs = Objects.requireNonNull(inputs, "inputs");
            this.nodes = Map.copyOf(nodes);
        }

        private Double value(String root, int x, int y, int z) {
            ProgramNode node = nodes.get(root);
            if (node == null) return null;
            StageCpuKey key = new StageCpuKey(root, x, y, z);
            if (!values.containsKey(key)) {
                values.put(key, candidate.capturedNodeValueAt(inputs, node, x, y, z));
            }
            return values.get(key);
        }
    }

    /**
     * The candidate has a small exact fraction table for the cell sizes that
     * occur in the currently admitted Minecraft captures.  Keep the default
     * here as a compatibility value for the older diagnostic-only helpers;
     * real density staging derives its geometry from the emitted program.
     */
    private static final DensityInterpolationGeometry DEFAULT_DENSITY_INTERPOLATION_GEOMETRY =
            new DensityInterpolationGeometry(4, 8, 4);

    public record Replay(MinecraftCpuCandidate.Capture cpuCapture,
                         ChunkNoiseResult gpuResult,
                         int comparedBlocks,
                         int mismatches,
                         dev.tellurium.runtime.vulkan.DeviceCapabilities device,
                         String shaderHash,
                         String spirvHash, NumericProfile numericProfile) {
        public Replay {
            Objects.requireNonNull(cpuCapture, "cpuCapture");
            Objects.requireNonNull(gpuResult, "gpuResult");
            Objects.requireNonNull(device, "device");
            if (comparedBlocks <= 0 || mismatches < 0 || mismatches > comparedBlocks
                    || shaderHash == null || shaderHash.isBlank()
                    || spirvHash == null || spirvHash.isBlank()) {
                throw new IllegalArgumentException("Invalid GPU replay receipt");
            }
        }
    }

    /** Device generation, not a CPU comparison or qualification receipt. */
    public record DeviceGeneration(ChunkNoiseResult gpuResult, int logicalGpuElements,
                                   DeviceCapabilities device, String shaderHash,
                                   String spirvHash, NumericProfile numericProfile) {
        public DeviceGeneration {
            Objects.requireNonNull(gpuResult, "gpuResult");
            Objects.requireNonNull(device, "device");
            Objects.requireNonNull(numericProfile, "numericProfile");
            if (logicalGpuElements <= 0 || logicalGpuElements > gpuResult.denseStates().length
                    || shaderHash == null || shaderHash.isBlank()
                    || spirvHash == null || spirvHash.isBlank()) {
                throw new IllegalArgumentException("Invalid GPU generation result");
            }
        }
    }

    /** Device-produced dense state plus the post-processing mark ABI. */
    private record DeviceMaterialResult(int[] stateIds, boolean[] fluidMarks,
                                       DeviceCapabilities device, String shaderHash,
                                       String spirvHash, int elementCount) {
        private DeviceMaterialResult {
            Objects.requireNonNull(stateIds, "stateIds");
            Objects.requireNonNull(fluidMarks, "fluidMarks");
            Objects.requireNonNull(device, "device");
            if (elementCount <= 0 || stateIds.length != elementCount
                    || fluidMarks.length != elementCount
                    || shaderHash == null || shaderHash.isBlank()
                    || spirvHash == null || spirvHash.isBlank()) {
                throw new IllegalArgumentException("Invalid device material result");
            }
            stateIds = stateIds.clone();
            fluidMarks = fluidMarks.clone();
        }

        @Override public int[] stateIds() { return stateIds.clone(); }
        @Override public boolean[] fluidMarks() { return fluidMarks.clone(); }
    }

    /** One CPU expectation for the opt-in repeated-point aquifer sweep. */
    private record AquiferThresholdCase(double density, AquiferEvaluator.Result expected) { }

    /**
     * Run a complete logical storage-height comparison for one chunk.  The
     * executor is persistent for the entire replay, and its bounded batched
     * path is used for the logical blocks rather than silently truncating to a
     * smoke-sized sample.
     */
    public Replay generate(ServerLevel level, int chunkX, int chunkZ) {
        Objects.requireNonNull(level, "level");
        MinecraftCpuCandidate cpuCandidate = new MinecraftCpuCandidate();
        return generate(level, cpuCandidate.capture(level, chunkX, chunkZ));
    }

    /**
     * Runs an isolated chunk replay on a caller-owned persistent executor.
     * Batch diagnostics use this overload so one server process does not
     * recreate the Vulkan device and queue for every captured chunk.
     */
    public Replay generate(ServerLevel level, int chunkX, int chunkZ, VulkanWorldgenExecutor executor) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(executor, "executor");
        MinecraftCpuCandidate cpuCandidate = new MinecraftCpuCandidate();
        return generate(cpuCandidate.capture(level, chunkX, chunkZ), executor);
    }

    /**
     * Generates from the exact immutable capture owned by a live NOISE task.
     * The overload is package-private so the eventual qualified provider can
     * reuse the caller's cache/step capture without scheduling another holder
     * lookup.  The isolated artifact entry point above remains convenient for
     * diagnostics that intentionally own their prerequisite lookup.
     */
    Replay generate(ServerLevel level, MinecraftCpuCandidate.Inputs inputs) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(inputs, "inputs");
        if (!level.dimension().location().toString().equals(inputs.snapshot().dimension())) {
            throw new IllegalArgumentException("GPU candidate level dimension does not match captured snapshot");
        }
        return generate(inputs);
    }

    /** Generates a device result from immutable captured inputs only. */
    Replay generate(MinecraftCpuCandidate.Inputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        try (VulkanWorldgenExecutor executor = new VulkanWorldgenExecutor()) {
            return generate(inputs, executor);
        }
    }

    /**
     * Uses a caller-owned persistent executor.  Live generation must use this
     * overload so device/queue/pipeline lifetime is not recreated for every
     * chunk request.
     */
    /**
     * Generates from immutable captured inputs on a caller-owned executor.
     * The public visibility is intentional: the loader-side mod captures on
     * its server thread, then hands only this loader-free record to its GPU
     * worker.  No Minecraft object is reachable from this overload.
     */
    public Replay generate(MinecraftCpuCandidate.Inputs inputs, VulkanWorldgenExecutor executor) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(executor, "executor");
        MinecraftCpuCandidate cpuCandidate = new MinecraftCpuCandidate();
        MinecraftCpuCandidate.Capture cpu = cpuCandidate.generate(inputs);
        DeviceGeneration generation = generateCaptured(inputs, executor, cpuCandidate, cpu);
        return new Replay(cpu, generation.gpuResult(), generation.gpuResult().denseStates().length, 0,
                generation.device(), generation.shaderHash(), generation.spirvHash(), generation.numericProfile());
    }

    /**
     * Executes the captured device program without regenerating the chunk on
     * the CPU. Independent qualification is the provider's admission duty;
     * isolated oracle campaigns must keep using generate(...), not this API.
     */
    public DeviceGeneration generateDevice(MinecraftCpuCandidate.Inputs inputs, VulkanWorldgenExecutor executor) {
        requireDeviceOnlyOptions();
        return generateCaptured(inputs, executor, new MinecraftCpuCandidate(), null);
    }

    static void requireDeviceOnlyOptions() {
        for (String flag : List.of("nativeDraft", "debugDensityParity", "cpuDensityMaterialFallback",
                "debugDensityStageCpuOracle", "debugDensityStageRootSeedCpu")) {
            if (Boolean.getBoolean("tellurium.gpuCandidate." + flag)) {
                throw new IllegalArgumentException("Device-only generation refuses comparison/draft option " + flag);
            }
        }
        for (String option : List.of("debugDensityProbePoint", "debugDensityStageRoot", "shaderOutput")) {
            if (!System.getProperty("tellurium.gpuCandidate." + option, "").isBlank()) {
                throw new IllegalArgumentException("Device-only generation refuses diagnostic option " + option);
            }
        }
    }

    private DeviceGeneration generateCaptured(MinecraftCpuCandidate.Inputs inputs, VulkanWorldgenExecutor executor,
                                              MinecraftCpuCandidate cpuCandidate, MinecraftCpuCandidate.Capture cpu) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(executor, "executor");
        EXACT_NORMAL_NOISE_SHADER.remove();
        MATERIAL_DENSITY_FALLBACK.remove();
        SHARED_SPLINE_SHADER_CACHE.remove();
        boolean stageDebugEnabled = Boolean.parseBoolean(
                System.getProperty("tellurium.gpuCandidate.debugStages", "false"));
        var snapshot = inputs.snapshot();
        String dimension = snapshot.dimension();
        int chunkX = inputs.chunkX();
        int chunkZ = inputs.chunkZ();
        stageDebug(stageDebugEnabled, "generate-start dimension=" + dimension
                + " chunk=" + chunkX + "," + chunkZ);
        stageDebug(stageDebugEnabled, "capture-complete");
        GeneratorSettingsSnapshot settings = snapshot.generatorSettings();
        requireDenseMaterialScope(snapshot.structureBlend());

        BlockStateTable table = BlockStateTable.fromRegistry(snapshot.registry());
        int defaultStateId = table.id(settings.defaultBlock().canonical());
        int airStateId = table.id("minecraft:air");
        int invalidStateId = table.states().size();
        if (defaultStateId < 0 || airStateId < 0) {
            throw new IllegalStateException("Captured registry lacks the GPU default or air state");
        }

        stageDebug(stageDebugEnabled, "emit-start");
        WorldgenShaderCompiler.MaterialOptions material = materialOptions(snapshot, table, chunkX, chunkZ);
        // Captured Minecraft material graphs can select any valid registry
        // state (the Nether path exposed netherrack/lava IDs that are not
        // present in the small default/ore/aquifer set). Keep the executor
        // ABI bounded to this captured registry, then let the independent
        // CPU result comparison reject semantic differences.
        int[] allowedStateIds = registryStateIds(table.states().size(), invalidStateId);
        WorldgenShaderCompiler.Shader exactShader = new WorldgenShaderCompiler().emitSnapshot(
                snapshot, NumericProfile.GPU_IEEE_BITS, 64,
                material,
                WorldgenShaderCompiler.MarkerPolicy.DIRECT_POINT_REPLAY,
                WorldgenShaderCompiler.OutputMode.STATE_AND_FLUID_MARK);
        EXACT_NORMAL_NOISE_SHADER.set(exactShader);
        String exactAquiferBarrierFunction = material.aquifer().enabled()
                ? emittedFunctionName(exactShader, snapshot.router().root("barrierNoise"),
                "captured barrier") : null;
        WorldgenShaderCompiler.Shader shader = exactShader;
        if (Boolean.getBoolean("tellurium.gpuCandidate.nativeDraft")) {
            String arithmeticMode = System.getProperty(
                    "tellurium.gpuCandidate.nativeDraftArithmetic", "both").trim().toLowerCase(Locale.ROOT);
            boolean rewriteFp32 = !arithmeticMode.equals("fp64-only");
            boolean rewriteFp64 = !arithmeticMode.equals("fp32-only");
            String disabledFp64Operation = arithmeticMode.startsWith("fp64-no-")
                    ? arithmeticMode.substring("fp64-no-".length()) : "";
            if (!arithmeticMode.equals("both") && !arithmeticMode.equals("fp32-only")
                    && !arithmeticMode.equals("fp64-only")
                    && !arithmeticMode.equals("fp64-no-add") && !arithmeticMode.equals("fp64-no-sub")
                    && !arithmeticMode.equals("fp64-no-mul") && !arithmeticMode.equals("fp64-no-div")) {
                throw new IllegalArgumentException(
                        "nativeDraftArithmetic must be both, fp32-only, fp64-only, or fp64-no-{add,sub,mul,div}");
            }
            shader = new WorldgenShaderCompiler.Shader(
                    NativeDraftMath.rewrite(shader.source(), rewriteFp32, rewriteFp64, disabledFp64Operation),
                    shader.programHash() + "/native-draft-v1-" + arithmeticMode,
                    NumericProfile.GPU_NATIVE_DRAFT,
                    shader.localSize(), shader.blendedNoiseFunctions(), shader.nodeFunctions());
        }
        boolean twoDirectDensityBranches = hasTwoDirectDensityBranches(shader.source());
        stageDebug(stageDebugEnabled, "emit-complete chars=" + shader.source().length());
        String shaderOutput = System.getProperty("tellurium.gpuCandidate.shaderOutput", "").trim();
        if (!shaderOutput.isEmpty()) {
            Path path = Path.of(shaderOutput).toAbsolutePath().normalize();
            Path parent = path.getParent();
            try {
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(path, shader.source());
            } catch (IOException failure) {
                throw new IllegalStateException("GPU candidate shader-only diagnostic could not write " + path, failure);
            }
            throw new IllegalStateException("GPU candidate shader-only diagnostic wrote " + path
                    + " (sourceChars=" + shader.source().length() + ")");
        }
        int logicalBlocks = Math.multiplyExact(settings.logicalHeight(), DenseNoiseGenerator.CHUNK_SIZE * DenseNoiseGenerator.CHUNK_SIZE);
        int[] coordinates = new int[Math.multiplyExact(logicalBlocks, 3)];
        fillCoordinates(coordinates, settings, chunkX, chunkZ);
        int[] fullCoordinates = coordinates;
        stageDebug(stageDebugEnabled, "coordinates-complete blocks=" + logicalBlocks);
        boolean densityParityDebug = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugDensityParity", "false"));
        boolean cpuDensityMaterialFallback = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.cpuDensityMaterialFallback", "false"));
        boolean cpuDensityMaterialProbe = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugMaterialProbeCpuDensity", "false"));
        if (cpuDensityMaterialProbe && !cpuDensityMaterialFallback) {
            throw new IllegalArgumentException(
                    "GPU candidate debugMaterialProbeCpuDensity requires cpuDensityMaterialFallback=true");
        }
        if (cpuDensityMaterialFallback && densityParityDebug && !cpuDensityMaterialProbe) {
            throw new IllegalArgumentException(
                    "CPU density material fallback cannot be combined with density parity diagnostics");
        }
        if (cpuDensityMaterialFallback
                && !(dimension.equals("minecraft:overworld") || dimension.equals("minecraft:the_nether"))) {
            throw new IllegalArgumentException(
                    "CPU density material fallback requires a captured Overworld or Nether density route");
        }
        if (cpuDensityMaterialFallback) {
            double[] fallback = cpuCandidate.capturedDensityValues(inputs);
            MATERIAL_DENSITY_FALLBACK.set(fallback);
            stageDebug(stageDebugEnabled, "cpu-density-material-fallback enabled values=" + fallback.length);
        }
        boolean densityBranchOnlyDebug = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugDensityBranchOnly", "false"));
        boolean densityFirstBranchOnlyDebug = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugDensityFirstBranchOnly", "false"));
        if ((densityBranchOnlyDebug || densityFirstBranchOnlyDebug) && !densityParityDebug) {
            throw new IllegalArgumentException(
                    "GPU candidate density branch diagnostics require debugDensityParity=true");
        }
        double[] expectedDensityValues = densityParityDebug
                && (dimension.equals("minecraft:overworld") || dimension.equals("minecraft:the_nether")
                || dimension.equals("minecraft:the_end"))
                ? cpuCandidate.capturedDensityValues(inputs) : null;
        double[] expectedDirectDensityValues = expectedDensityValues == null || !twoDirectDensityBranches ? null
                : cpuCandidate.capturedDirectDensityValues(inputs, 1);
        boolean densityComponentDebug = expectedDensityValues != null && twoDirectDensityBranches && Boolean.parseBoolean(
                System.getProperty("tellurium.gpuCandidate.debugDensityComponents", "false"));
        // The first-branch-only diagnostic is useful without the more verbose
        // material-component route.  Keep its CPU oracle independent so the
        // flag cannot silently fall through into the full 410-stage replay.
        double[] expectedFirstDensityValues = (densityComponentDebug || densityFirstBranchOnlyDebug)
                ? cpuCandidate.capturedDirectDensityValues(inputs, 0) : null;
        boolean densityMaterialInternalDebug = densityComponentDebug && Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugDensityMaterialInternals", "false"));
        double[] expectedFirstInterpolationValues = densityMaterialInternalDebug
                ? cpuCandidate.capturedDirectDensityPathValues(inputs, 0, 0, 1) : null;
        double[] expectedFirstNode5Values = densityMaterialInternalDebug
                ? cpuCandidate.capturedDirectDensityPathValues(inputs, 0, 0, 1, 0) : null;
        double[] expectedFirstNode44Values = densityMaterialInternalDebug
                ? cpuCandidate.capturedDirectDensityPathValues(inputs, 0, 0, 1, 0, 0, 1, 1, 1, 1, 1, 1) : null;
        double[] expectedFirstNode45Values = densityMaterialInternalDebug
                ? cpuCandidate.capturedDirectDensityPathValues(inputs, 0, 0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 0) : null;
        double[] expectedFirstNode2101Values = densityMaterialInternalDebug
                ? cpuCandidate.capturedDirectDensityPathValues(inputs, 0, 0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1) : null;
        double[] expectedFirstNode2150Values = densityMaterialInternalDebug
                ? cpuCandidate.capturedDirectDensityPathValues(inputs, 0, 0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 2) : null;
        double[] fullFirstNode5Values = expectedFirstNode5Values;
        int semanticValueIndex = 0;
        if (expectedDensityValues != null) {
            if (expectedDensityValues.length != logicalBlocks) {
                throw new IllegalStateException("CPU density diagnostic length does not match captured bounds");
            }
            stageDebug(stageDebugEnabled, "density-parity-diagnostic enabled");
        }
        debugDensityCpuCornerRows(fullCoordinates, expectedFirstNode2101Values,
                expectedFirstNode2150Values, expectedFirstNode45Values);
        DensityProbe densityProbe = parseDensityProbe();
        if (densityProbe != null) {
            if (expectedDensityValues == null) {
                throw new IllegalArgumentException(
                        "GPU candidate debugDensityProbePoint requires debugDensityParity on an Overworld or Nether capture");
            }
            int fullIndex = findCoordinateIndex(coordinates, densityProbe);
            semanticValueIndex = fullIndex;
            expectedDensityValues = new double[]{expectedDensityValues[fullIndex]};
            expectedDirectDensityValues = expectedDirectDensityValues == null ? null
                    : new double[]{expectedDirectDensityValues[fullIndex]};
            expectedFirstDensityValues = expectedFirstDensityValues == null ? null
                    : new double[]{expectedFirstDensityValues[fullIndex]};
            expectedFirstInterpolationValues = expectedFirstInterpolationValues == null ? null
                    : new double[]{expectedFirstInterpolationValues[fullIndex]};
            expectedFirstNode5Values = expectedFirstNode5Values == null ? null
                    : new double[]{expectedFirstNode5Values[fullIndex]};
            expectedFirstNode44Values = expectedFirstNode44Values == null ? null
                    : new double[]{expectedFirstNode44Values[fullIndex]};
            expectedFirstNode45Values = expectedFirstNode45Values == null ? null
                    : new double[]{expectedFirstNode45Values[fullIndex]};
            expectedFirstNode2101Values = expectedFirstNode2101Values == null ? null
                    : new double[]{expectedFirstNode2101Values[fullIndex]};
            expectedFirstNode2150Values = expectedFirstNode2150Values == null ? null
                    : new double[]{expectedFirstNode2150Values[fullIndex]};
            coordinates = new int[]{densityProbe.x(), densityProbe.y(), densityProbe.z()};
            stageDebug(stageDebugEnabled, "density-probe point=" + densityProbe.x() + ","
                    + densityProbe.y() + "," + densityProbe.z() + " fullIndex=" + fullIndex);
            if (Boolean.parseBoolean(System.getProperty(
                    "tellurium.gpuCandidate.debugDensityBranchComponents", "false"))
                    && twoDirectDensityBranches) {
                double[] directSelector = cpuCandidate.capturedDirectDensityPathValues(inputs, 1, 0);
                double[] directSelectorChild = cpuCandidate.capturedDirectDensityPathValues(inputs, 1, 0, 0);
                double[] directTrueBranch = cpuCandidate.capturedDirectDensityPathValues(inputs, 1, 1);
                double[] directFalseBranch = cpuCandidate.capturedDirectDensityPathValues(inputs, 1, 2);
                stageDebug(stageDebugEnabled, "density-direct-cpu-components point="
                        + densityProbe.x() + "," + densityProbe.y() + "," + densityProbe.z()
                        + " selector=" + directSelector[fullIndex]
                        + " selectorChild=" + directSelectorChild[fullIndex]
                        + " true=" + directTrueBranch[fullIndex]
                        + " false=" + directFalseBranch[fullIndex]);
            }
            if (stageDebugEnabled) {
                stageDebug(true, "cpu-material-probe=" + cpuCandidate.capturedMaterialDiagnostic(
                        inputs, densityProbe.x(), densityProbe.y(), densityProbe.z()));
            }
            String semanticProbeNodes = System.getProperty(
                    "tellurium.gpuCandidate.debugDensitySemanticNodes", "").trim();
            if (!semanticProbeNodes.isEmpty()) {
                for (String selector : semanticProbeNodes.split(",")) {
                    String nodeName = selector.trim();
                    ProgramNode node = shader.nodeFunctions().get(nodeName);
                    if (node == null) throw new IllegalArgumentException(
                            "Unknown GPU density semantic probe node " + nodeName);
                    stageDebug(true, "cpu-semantic-node=" + nodeName + " value="
                            + cpuCandidate.capturedNodeValueAt(inputs, node,
                            densityProbe.x(), densityProbe.y(), densityProbe.z()));
                }
            }
            if (Boolean.parseBoolean(System.getProperty(
                    "tellurium.gpuCandidate.debugAquiferThresholdSweep", "false"))) {
                WorldgenShaderCompiler.Shader aquiferTraceShader = Boolean.parseBoolean(System.getProperty(
                        "tellurium.gpuCandidate.exactAquiferStageNative", "false"))
                        && shader.profile() == NumericProfile.GPU_NATIVE_DRAFT
                        ? shader : EXACT_NORMAL_NOISE_SHADER.get();
                executeAquiferThresholdSweep(executor, aquiferTraceShader, densityProbe,
                        expectedDensityValues[0], defaultStateId, airStateId, invalidStateId,
                        1, exactShader, exactAquiferBarrierFunction, cpuCandidate, inputs, table);
            }
            if (Boolean.parseBoolean(System.getProperty(
                    "tellurium.gpuCandidate.debugAquiferCpuDensity", "false"))) {
                WorldgenShaderCompiler.Shader aquiferTraceShader = Boolean.parseBoolean(System.getProperty(
                        "tellurium.gpuCandidate.exactAquiferStageNative", "false"))
                        && shader.profile() == NumericProfile.GPU_NATIVE_DRAFT
                        ? shader : EXACT_NORMAL_NOISE_SHADER.get();
                executeAquiferScalarTrace(executor, aquiferTraceShader, densityProbe,
                        expectedDensityValues[0], defaultStateId, airStateId, invalidStateId,
                        1, exactShader, exactAquiferBarrierFunction);
            }
        }
        if (Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.cpuDensityOnly", "false"))) {
            int index = 0;
            StringBuilder diagnostic = new StringBuilder("CPU density diagnostic point=")
                    .append(coordinates[index * 3]).append(',')
                    .append(coordinates[index * 3 + 1]).append(',')
                    .append(coordinates[index * 3 + 2])
                    .append(" full=").append(expectedDensityValues == null ? "disabled" : expectedDensityValues[index]);
            if (expectedFirstDensityValues != null) diagnostic.append(" branch0=").append(expectedFirstDensityValues[index]);
            if (expectedDirectDensityValues != null) diagnostic.append(" branch1=").append(expectedDirectDensityValues[index]);
            if (expectedFirstInterpolationValues != null) diagnostic.append(" interp=").append(expectedFirstInterpolationValues[index]);
            if (expectedFirstNode5Values != null) diagnostic.append(" node5=").append(expectedFirstNode5Values[index]);
            if (expectedFirstNode44Values != null) diagnostic.append(" node44=").append(expectedFirstNode44Values[index]);
            if (expectedFirstNode45Values != null) diagnostic.append(" node45=").append(expectedFirstNode45Values[index]);
            if (expectedFirstNode2101Values != null) diagnostic.append(" node2101=").append(expectedFirstNode2101Values[index]);
            if (expectedFirstNode2150Values != null) diagnostic.append(" node2150=").append(expectedFirstNode2150Values[index]);
            if (densityMaterialInternalDebug) {
                double[] cpuNode2102 = cpuCandidate.capturedDirectDensityPathValues(inputs, 0,
                        0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1);
                double[] cpuNode2103 = cpuCandidate.capturedDirectDensityPathValues(inputs, 0,
                        0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 0);
                double[] cpuNode2104 = cpuCandidate.capturedDirectDensityPathValues(inputs, 0,
                        0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1);
                diagnostic.append(" node2102=").append(cpuNode2102[index])
                        .append(" node2103=").append(cpuNode2103[index])
                        .append(" node2104=").append(cpuNode2104[index]);
            }
            String semanticNodeDiagnostic = System.getProperty(
                    "tellurium.gpuCandidate.debugDensitySemanticNodes", "").trim();
            if (!semanticNodeDiagnostic.isEmpty()) {
                for (String encodedNode : semanticNodeDiagnostic.split(",", -1)) {
                    String nodeSelector = encodedNode.trim();
                    if (nodeSelector.isEmpty()) continue;
                    String nodeName = nodeSelector.startsWith("semantic:")
                            ? resolveDensitySemanticSelector(shader, nodeSelector,
                            "debugDensitySemanticNodes") : nodeSelector;
                    ProgramNode semanticNode = shader.nodeFunctions().get(nodeName);
                    if (semanticNode == null) {
                        diagnostic.append(" semanticNode=").append(nodeSelector).append("=MISSING");
                        continue;
                    }
                    double[] semanticValues = cpuCandidate.capturedNodeValues(inputs, semanticNode);
                    diagnostic.append(" semanticNode=").append(nodeSelector)
                            .append(" resolved=").append(nodeName).append('=')
                            .append(semanticValues[semanticValueIndex]);
                    diagnostic.append(" semanticFingerprint=")
                            .append(WorldgenProgram.nodeFingerprint(semanticNode))
                            .append(" semanticOperation=").append(semanticNode.operation());
                    if (semanticNode instanceof ProgramNode.Ap2 ap2) {
                        diagnostic.append(" semanticBounds=")
                                .append(Double.toHexString(ap2.rightMinValue())).append("..")
                                .append(Double.toHexString(ap2.rightMaxValue()))
                                .append(" semanticChildFingerprints=")
                                .append(WorldgenProgram.nodeFingerprint(ap2.left())).append(',')
                                .append(WorldgenProgram.nodeFingerprint(ap2.right()));
                    } else if (semanticNode instanceof ProgramNode.RangeChoice range) {
                        diagnostic.append(" semanticBounds=")
                                .append(Double.toHexString(range.minInclusive())).append("..")
                                .append(Double.toHexString(range.maxExclusive()));
                    }
                }
            }
            String cpuPathDiagnostic = System.getProperty(
                    "tellurium.gpuCandidate.debugDensityCpuPaths", "").trim();
            if (!cpuPathDiagnostic.isEmpty()) {
                for (String encodedPath : cpuPathDiagnostic.split(";", -1)) {
                    if (encodedPath.isBlank()) continue;
                    int[] path = Arrays.stream(encodedPath.split(",", -1))
                            .mapToInt(value -> Integer.parseInt(value.trim())).toArray();
                    try {
                        double[] pathValues = cpuCandidate.capturedDirectDensityPathValues(inputs, 0, path);
                        diagnostic.append(" cpuPath=").append(Arrays.toString(path))
                                .append("=").append(pathValues[index]);
                    } catch (RuntimeException failure) {
                        diagnostic.append(" cpuPath=").append(Arrays.toString(path))
                                .append("=INVALID(").append(failure.getMessage()).append(')');
                    }
                }
            }
            diagnostic.append(" blendBeardifierPieces=")
                    .append(snapshot.structureBlend().beardifier().pieces().size())
                    .append(" blendJunctions=")
                    .append(snapshot.structureBlend().beardifier().junctions().size());
            if (densityProbe != null && fullFirstNode5Values != null) {
                diagnostic.append(densityCornerDiagnostic(fullCoordinates, fullFirstNode5Values,
                        densityProbe, 4, 8, 4));
            }
            throw new IllegalStateException(diagnostic.toString());
        }

        enforceShaderSourceBudget(shader, stageDebugEnabled);
        int batchSize = integerProperty("tellurium.gpuCandidate.batchElements", 128);
        if (batchSize <= 0 || batchSize > VulkanWorldgenExecutor.MAX_DISPATCH_ELEMENTS) {
            throw new IllegalArgumentException("GPU candidate batchElements must be in [1, "
                    + VulkanWorldgenExecutor.MAX_DISPATCH_ELEMENTS + "]");
        }
        String debugDensityNode = System.getProperty(
                "tellurium.gpuCandidate.debugDensityNode", "").trim();
        if (!debugDensityNode.isEmpty()) {
            if (!debugDensityNode.matches("wg_node_[0-9]+")) {
                throw new IllegalArgumentException("GPU candidate debugDensityNode must be wg_node_<id>");
            }
            boolean exactDebugNode = shader.profile() == NumericProfile.GPU_NATIVE_DRAFT
                    && Boolean.parseBoolean(System.getProperty(
                            "tellurium.gpuCandidate.debugDensityNodeExact", "false"));
            WorldgenShaderCompiler.Shader nodeShaderBase = exactDebugNode
                    ? normalNoiseCompleteShader(shader) : shader;
            WorldgenShaderCompiler.Shader nodeShader = stageShader(nodeShaderBase,
                    densityStageSource(nodeShaderBase.source(), debugDensityNode),
                    "density-node-" + debugDensityNode);
            SpirvNumericContract.require(nodeShader.source(), nodeShader.profile());
            VulkanWorldgenExecutor.RawResult nodeResult = executor.executeRawBatched(
                    densityStageRequest(new VulkanWorldgenExecutor.RawRequest(
                            nodeShader, coordinateWords(coordinates), 4, DENSITY_STAGE_VALUE_WORDS,
                            coordinates.length / 3, defaultStateId, airStateId, invalidStateId))
                            .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
            String cpuNodeContext = expectedDensityValues == null ? "" : " cpuFull=" + expectedDensityValues[0]
                    + (expectedFirstDensityValues == null ? "" : " cpuBranch0=" + expectedFirstDensityValues[0])
                    + (expectedDirectDensityValues == null ? "" : " cpuBranch1=" + expectedDirectDensityValues[0]);
            if (debugDensityNode.equals("wg_node_2150") && expectedFirstNode2150Values != null) {
                cpuNodeContext += " cpuNode2150=" + expectedFirstNode2150Values[0];
            }
            throw new IllegalStateException("GPU density node diagnostic node=" + debugDensityNode
                    + " exact=" + exactDebugNode
                    + debugStageValues(nodeResult.outputWords(), coordinateWords(coordinates)) + cpuNodeContext);
        }
        String debugDensityStageRootSelector = System.getProperty(
                "tellurium.gpuCandidate.debugDensityStageRoot", "").trim();
        if (!debugDensityStageRootSelector.isEmpty()) {
            if (!debugDensityStageRootSelector.matches(
                    "wg_node_[0-9]+|wg_spline_[0-9]+|semantic:[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException(
                        "GPU candidate debugDensityStageRoot must be wg_node_<id>, wg_spline_<id>, "
                                + "or semantic:<64-hex-node-fingerprint>");
            }
            String debugDensityStageRoot = resolveDensitySemanticSelector(
                    shader, debugDensityStageRootSelector, "debugDensityStageRoot");
            if (densityProbe == null) {
                throw new IllegalArgumentException(
                        "GPU candidate debugDensityStageRoot requires debugDensityProbePoint");
            }
            int[] stageCoordinates = coordinateWords(coordinates);
            String seededChildValues = System.getProperty(
                    "tellurium.gpuCandidate.debugDensityStageRootChildValues", "").trim();
            boolean cpuSeeded = false;
            if (seededChildValues.isEmpty() && Boolean.parseBoolean(System.getProperty(
                    "tellurium.gpuCandidate.debugDensityStageRootSeedCpu", "false"))) {
                seededChildValues = cpuSeededDensityRootChildren(
                        cpuCandidate, inputs, shader, debugDensityStageRoot, fullCoordinates, densityProbe);
                cpuSeeded = true;
            }
            boolean stageCpuOracleEnabled = Boolean.parseBoolean(System.getProperty(
                    "tellurium.gpuCandidate.debugDensityStageCpuOracle", "false"));
            StageCpuOracle stageCpuOracle = stageCpuOracleEnabled
                    ? new StageCpuOracle(cpuCandidate, inputs, shader.nodeFunctions()) : null;
            if (stageCpuOracle != null) DENSITY_STAGE_CPU_ORACLE.set(stageCpuOracle);
            VulkanWorldgenExecutor.RawResult stageResult;
            try {
                stageResult = seededChildValues.isEmpty()
                        ? (Boolean.parseBoolean(System.getProperty(
                                "tellurium.gpuCandidate.debugDensityStageRootGpuChildren", "false"))
                                ? executeChunkedDensityRoot(executor, shader, debugDensityStageRoot, stageCoordinates,
                                defaultStateId, airStateId, invalidStateId, batchSize, new HashSet<>())
                                : executeStagedDensityRoot(executor, shader, debugDensityStageRoot, stageCoordinates,
                                defaultStateId, airStateId, invalidStateId, batchSize, new HashSet<>()))
                        : executeSeededDensityRoot(executor, shader, debugDensityStageRoot, stageCoordinates,
                        seededChildValues, defaultStateId, airStateId, invalidStateId, batchSize);
            } finally {
                if (stageCpuOracle != null) DENSITY_STAGE_CPU_ORACLE.remove();
            }
            String cpuStageContext = expectedDensityValues == null ? "" : " cpuFull=" + expectedDensityValues[0];
            if (debugDensityStageRoot.equals("wg_node_2150") && expectedFirstNode2150Values != null) {
                cpuStageContext += " cpuNode2150=" + expectedFirstNode2150Values[0];
            }
            ProgramNode semanticRoot = shader.nodeFunctions().get(debugDensityStageRoot);
            if (semanticRoot != null) {
                StringBuilder orderedSplineTrace = new StringBuilder();
                Map<String, ProgramNode> semanticTraceNodes = shader.nodeFunctions();
                double semanticRootValue = semanticRoot instanceof ProgramNode.Spline spline
                        ? dev.tellurium.compiler.jvm.worldgen.WorldgenInterpreter.withSplineTrace(
                        spline.spline(), (node, trace) -> {
                            String name = semanticTraceNodes.entrySet().stream()
                                    .filter(entry -> entry.getValue() instanceof ProgramNode.Spline mapped
                                            && mapped.spline() == node)
                                    .map(Map.Entry::getKey).findFirst().orElse("unmapped");
                            orderedSplineTrace.append('[').append(name).append(' ').append(trace).append(']');
                        },
                        () -> cpuCandidate.capturedNodeValueAt(inputs, semanticRoot,
                                densityProbe.x(), densityProbe.y(), densityProbe.z()))
                        : cpuCandidate.capturedNodeValueAt(inputs, semanticRoot,
                                densityProbe.x(), densityProbe.y(), densityProbe.z());
                cpuStageContext += " cpuSemanticRoot=" + semanticRootValue
                        + " semanticFingerprint=" + WorldgenProgram.nodeFingerprint(semanticRoot)
                        + " semanticOperation=" + semanticRoot.operation()
                        + (orderedSplineTrace.isEmpty() ? "" : " splineOrdered=" + orderedSplineTrace);
                if (semanticRoot instanceof ProgramNode.Spline spline
                        && spline.spline() instanceof ProgramNode.SplineMultipoint multipoint) {
                    float coordinate = (float) cpuCandidate.capturedNodeValueAt(inputs,
                            multipoint.coordinate(), densityProbe.x(), densityProbe.y(), densityProbe.z());
                    int interval = -1;
                    for (int knot = 0; knot < multipoint.locations().size(); knot++) {
                        if (coordinate < multipoint.locations().get(knot)) break;
                        interval = knot;
                    }
                    cpuStageContext += " splineCoordinate=" + coordinate + " splineInterval=" + interval
                            + " splineLocations=" + multipoint.locations()
                            + " splineDerivatives=" + multipoint.derivatives();
                    if (interval >= 0 && interval + 1 < multipoint.values().size()) {
                        float left = (float) cpuCandidate.capturedNodeValueAt(inputs,
                                new ProgramNode.Spline(multipoint.values().get(interval), ValueType.FP32, spline.domain()),
                                densityProbe.x(), densityProbe.y(), densityProbe.z());
                        float right = (float) cpuCandidate.capturedNodeValueAt(inputs,
                                new ProgramNode.Spline(multipoint.values().get(interval + 1), ValueType.FP32, spline.domain()),
                                densityProbe.x(), densityProbe.y(), densityProbe.z());
                        float width = multipoint.locations().get(interval + 1) - multipoint.locations().get(interval);
                        float t = (coordinate - multipoint.locations().get(interval)) / width;
                        float delta = right - left;
                        float a = multipoint.derivatives().get(interval) * width - delta;
                        float b = -multipoint.derivatives().get(interval + 1) * width + delta;
                        float reference = left + t * (right - left)
                                + t * (1.0f - t) * (a + t * (b - a));
                        cpuStageContext += " splineLeft=" + left + " splineRight=" + right
                                + " splineT=" + t + " splineIndependent=" + reference;
                    }
                }
            }
            throw new IllegalStateException("GPU staged density root diagnostic root="
                    + debugDensityStageRootSelector + " resolved=" + debugDensityStageRoot
                    + (seededChildValues.isEmpty() ? ""
                    : " seeded=" + (cpuSeeded ? "cpu" : "explicit"))
                    + (seededChildValues.isEmpty() && Boolean.parseBoolean(System.getProperty(
                            "tellurium.gpuCandidate.debugDensityStageRootGpuChildren", "false"))
                            ? " gpuChildren=true" : "")
                    + debugStageValues(stageResult.outputWords(), stageCoordinates)
                    + (cpuSeeded ? " cpuSeededChildren=" + seededChildValues : "")
                    + cpuStageContext);
        }
        String debugDensityInterpolationRoot = System.getProperty(
                "tellurium.gpuCandidate.debugDensityInterpolationRoot", "").trim();
        if (!debugDensityInterpolationRoot.isEmpty()) {
            if (!debugDensityInterpolationRoot.matches("wg_node_[0-9]+")) {
                throw new IllegalArgumentException(
                        "GPU candidate debugDensityInterpolationRoot must be wg_node_<id>");
            }
            if (densityProbe == null) {
                throw new IllegalArgumentException(
                        "GPU candidate debugDensityInterpolationRoot requires debugDensityProbePoint");
            }
            if (!isDensityInterpolationFunction(shader.source(), debugDensityInterpolationRoot)) {
                throw new IllegalArgumentException(
                        "GPU candidate debugDensityInterpolationRoot is not a staged density interpolation root: "
                                + debugDensityInterpolationRoot);
            }
            int[] interpolationCoordinates = coordinateWords(coordinates);
            VulkanWorldgenExecutor.RawResult interpolationResult = executeStagedDensityInterpolation(
                    executor, shader, debugDensityInterpolationRoot, interpolationCoordinates,
                    defaultStateId, airStateId, invalidStateId, batchSize, new HashSet<>());
            String cpuInterpolationContext = expectedFirstInterpolationValues == null ? ""
                    : " cpuExpected=" + expectedFirstInterpolationValues[0];
            throw new IllegalStateException("GPU density interpolation diagnostic root="
                    + debugDensityInterpolationRoot + debugStageValues(
                    interpolationResult.outputWords(), interpolationCoordinates) + cpuInterpolationContext);
        }
        String debugNormalNoiseRoot = System.getProperty(
                "tellurium.gpuCandidate.debugDensityNormalNoiseRoot", "").trim();
        if (!debugNormalNoiseRoot.isEmpty()) {
            if (!debugNormalNoiseRoot.matches("wg_node_[0-9]+")) {
                throw new IllegalArgumentException(
                        "GPU candidate debugDensityNormalNoiseRoot must be wg_node_<id>");
            }
            DirectNormalNoise directNormalNoise = detectDirectNormalNoise(shader.source(), debugNormalNoiseRoot);
            if (directNormalNoise == null) {
                throw new IllegalArgumentException("GPU candidate debugDensityNormalNoiseRoot is not a direct normal-noise root: "
                        + debugNormalNoiseRoot);
            }
            int[] normalNoiseCoordinates = directStageProbeCoordinates(coordinateWords(coordinates));
            VulkanWorldgenExecutor.RawResult normalNoiseResult = executeStagedNormalNoise(
                    executor, shader, directNormalNoise, normalNoiseCoordinates,
                    defaultStateId, airStateId, invalidStateId, batchSize);
            throw new IllegalStateException("GPU normal-noise diagnostic root=" + debugNormalNoiseRoot
                    + debugStageValues(normalNoiseResult.outputWords(), normalNoiseCoordinates));
        }
        String debugEmbeddedNormalNoiseRoot = System.getProperty(
                "tellurium.gpuCandidate.debugDensityEmbeddedNormalNoiseRoot", "").trim();
        if (!debugEmbeddedNormalNoiseRoot.isEmpty()) {
            if (!debugEmbeddedNormalNoiseRoot.matches("wg_node_[0-9]+")) {
                throw new IllegalArgumentException(
                        "GPU candidate debugDensityEmbeddedNormalNoiseRoot must be wg_node_<id>");
            }
            DirectNormalNoise embeddedNormalNoise = detectEmbeddedNormalNoise(
                    shader.source(), debugEmbeddedNormalNoiseRoot);
            if (embeddedNormalNoise == null) {
                throw new IllegalArgumentException(
                        "GPU candidate debugDensityEmbeddedNormalNoiseRoot is not an embedded normal-noise root: "
                                + debugEmbeddedNormalNoiseRoot);
            }
            List<String> childRoots = densityChildren(shader.source(), debugEmbeddedNormalNoiseRoot);
            if (childRoots.isEmpty()) {
                throw new IllegalArgumentException(
                        "GPU candidate debugDensityEmbeddedNormalNoiseRoot has no density children: "
                                + debugEmbeddedNormalNoiseRoot);
            }
            int[] embeddedCoordinates = directStageProbeCoordinates(coordinateWords(coordinates));
            int childWords = densityStageInputWords(childRoots);
            int[] childInput = new int[Math.multiplyExact(embeddedCoordinates.length / 4, childWords)];
            for (int index = 0; index < embeddedCoordinates.length / 4; index++) {
                System.arraycopy(embeddedCoordinates, index * 4, childInput, index * childWords, 4);
            }
            VulkanWorldgenExecutor.RawResult embeddedResult = executeStagedEmbeddedNormalNoise(
                    executor, shader, embeddedNormalNoise, childRoots, childInput,
                    embeddedCoordinates, defaultStateId, airStateId, invalidStateId, batchSize);
            throw new IllegalStateException("GPU embedded normal-noise diagnostic root="
                    + debugEmbeddedNormalNoiseRoot + " children=" + childRoots
                    + debugStageValues(embeddedResult.outputWords(), embeddedCoordinates));
        }
        if (cpuDensityMaterialProbe) {
            if (densityProbe == null || expectedDensityValues == null || expectedDensityValues.length != 1) {
                throw new IllegalArgumentException(
                        "GPU candidate debugMaterialProbeCpuDensity requires debugDensityProbePoint and debugDensityParity");
            }
            // The probe is intentionally downstream-only: use the independent
            // CPU density value as an explicit carrier, then execute the GPU
            // barrier/aquifer/ore/material consumers without traversing the
            // expensive full Overworld density planner.
            MATERIAL_DENSITY_FALLBACK.remove();
            DeviceMaterialResult materialProbe = executeMaterialStages(
                    executor, shader, coordinates, cpuDensityWords(expectedDensityValues),
                    defaultStateId, airStateId, invalidStateId, allowedStateIds, batchSize,
                    executor.device(), "CPU-density material probe", null,
                    snapshot.structureBlend(), exactShader, exactAquiferBarrierFunction);
            throw new IllegalStateException("GPU CPU-density material probe point="
                    + densityProbe.x() + "," + densityProbe.y() + "," + densityProbe.z()
                    + " density=" + expectedDensityValues[0]
                    + " states=" + Arrays.toString(materialProbe.stateIds())
                    + " fluid=" + Arrays.toString(materialProbe.fluidMarks())
                    + " shader=" + materialProbe.shaderHash());
        }
        DeviceMaterialResult deviceResult;
        stageDebug(stageDebugEnabled, "executor-start");
        stageDebug(stageDebugEnabled, "executor-ready");
        if (dimension.equals("minecraft:overworld") || dimension.equals("minecraft:the_nether")
                || dimension.equals("minecraft:the_end")) {
            // Both profiles must evaluate the captured root through the same
            // coordinate-aware planner. Reconstructing a two-branch min from
            // a guessed largest split bypassed semantic ancestor boundaries:
            // the generic exact root matched while the rebuilt material row
            // changed 133 stone states to air in the seed -1 witness.
            String root = firstFunctionName(shader.source(),
                    "uvec2 density = (wg_node_[0-9]+)\\(point\\);", "final density");
            deviceResult = executeStagedSingleBranchDensity(executor, shader, root, coordinates,
                    defaultStateId, airStateId, invalidStateId, allowedStateIds, batchSize,
                    expectedDensityValues, snapshot.structureBlend(), exactShader,
                    exactAquiferBarrierFunction);
        } else {
            deviceResult = executeDense(executor, shader, coordinates, defaultStateId,
                    airStateId, invalidStateId, allowedStateIds, batchSize);
        }
        if (densityProbe != null) {
            String expectedState = cpu.result().denseStates()[semanticValueIndex];
            String actualState = table.state(deviceResult.stateIds()[0]).canonical();
            boolean expectedFluid = cpu.result().postProcessing().fluidMarks()[semanticValueIndex];
            boolean actualFluid = deviceResult.fluidMarks()[0];
            if (!actualState.equals(expectedState) || actualFluid != expectedFluid) {
                throw new IllegalStateException("GPU density probe material mismatch point=" + densityProbe.x()
                        + "," + densityProbe.y() + "," + densityProbe.z()
                        + " expected=" + expectedState + ":fluid=" + expectedFluid
                        + " actual=" + actualState + ":fluid=" + actualFluid);
            }
            throw new IllegalStateException("GPU density probe completed for point=" + densityProbe.x()
                    + "," + densityProbe.y() + "," + densityProbe.z()
                    + " state=" + actualState + " fluid=" + actualFluid);
        }
        String[] gpuStates = decodeStates(deviceResult.stateIds(), table, settings, invalidStateId);
        boolean[] gpuFluidMarks = decodeFluidMarks(deviceResult.fluidMarks(), settings);
        if (cpu != null) {
            int mismatches = compare(cpu.result().denseStates(), gpuStates);
            if (mismatches != 0) {
                throw new IllegalStateException("Real GPU candidate differs from CPU candidate at "
                        + mismatches + " of " + Math.multiplyExact(settings.height(), 256) + " storage blocks\n"
                        + mismatchSummary(cpu.result().denseStates(), gpuStates, coordinates));
            }
            int fluidMismatches = compare(cpu.result().postProcessing().fluidMarks(), gpuFluidMarks);
            if (fluidMismatches != 0) {
                throw new IllegalStateException("Real GPU candidate differs from CPU candidate at "
                        + fluidMismatches + " fluid post-processing marks");
            }
        }
        ChunkNoiseResult gpuResult = resultWithGpuStates(inputs, gpuStates, gpuFluidMarks);
        EXACT_NORMAL_NOISE_SHADER.remove();
        MATERIAL_DENSITY_FALLBACK.remove();
        return new DeviceGeneration(gpuResult, deviceResult.elementCount(), deviceResult.device(),
                deviceResult.shaderHash(), deviceResult.spirvHash(), shader.profile());
    }

    /** Dispatches the regular dense shader with its explicit two-word output ABI. */
    private static DeviceMaterialResult executeDense(VulkanWorldgenExecutor executor,
                                                     WorldgenShaderCompiler.Shader shader,
                                                     int[] coordinates, int defaultStateId,
                                                     int airStateId, int invalidStateId,
                                                     int[] allowedStateIds, int batchSize) {
        if (coordinates.length == 0 || coordinates.length % 3 != 0) {
            throw new IllegalArgumentException("Dense GPU coordinates must contain XYZ triples");
        }
        int elementCount = coordinates.length / 3;
        int[] inputWords = new int[Math.multiplyExact(elementCount, 4)];
        for (int index = 0; index < elementCount; index++) {
            int source = index * 3;
            int target = index * 4;
            inputWords[target] = coordinates[source];
            inputWords[target + 1] = coordinates[source + 1];
            inputWords[target + 2] = coordinates[source + 2];
        }
        VulkanWorldgenExecutor.RawResult raw = executor.executeRawBatched(
                new VulkanWorldgenExecutor.RawRequest(shader, inputWords, 4, 2, elementCount,
                        defaultStateId, airStateId, invalidStateId), batchSize);
        int[] words = raw.outputWords();
        int[] stateIds = new int[elementCount];
        boolean[] fluidMarks = new boolean[elementCount];
        for (int index = 0; index < elementCount; index++) {
            int word = index * 2;
                stateIds[index] = words[word];
                fluidMarks[index] = decodeFluidMark(words[word + 1], "dense")
                        && stateIds[index] != airStateId;
            if (!contains(allowedStateIds, stateIds[index])) {
                throw new IllegalStateException("GPU material shader returned state ID outside the dispatch ABI: "
                        + stateIds[index]);
            }
        }
        return new DeviceMaterialResult(stateIds, fluidMarks, raw.device(), raw.shaderHash(),
                raw.spirvHash(), elementCount);
    }

    /**
     * Splits a captured FP64 density root at its two direct branches.  The
     * branch shaders only carry immutable density arithmetic; the final shader
     * consumes those device-produced carriers and owns all material logic.
     */
    private static DeviceMaterialResult executeStagedDensity(VulkanWorldgenExecutor executor,
                                                              WorldgenShaderCompiler.Shader completeShader,
                                                              int[] coordinates, int defaultStateId,
                                                              int airStateId, int invalidStateId,
                                                              int[] allowedStateIds, int batchSize,
                                                              double[] expectedDensityValues,
                                                              double[] expectedDirectDensityValues,
                                                              double[] expectedFirstDensityValues,
                                                              double[] expectedFirstInterpolationValues,
                                                              double[] expectedFirstNode5Values,
                                                              double[] expectedFirstNode44Values,
                                                              double[] expectedFirstNode45Values,
                                                               double[] expectedFirstNode2101Values,
                                                               double[] expectedFirstNode2150Values,
                                                               StructureBlendSnapshot structureBlend,
                                                               WorldgenShaderCompiler.Shader exactBarrierShader,
                                                               String exactBarrierFunction) {
        DensityStageNames names = findDensityStageNames(completeShader.source());
        if (names.branches().size() == 1) {
            return executeStagedSingleBranchDensity(executor, completeShader, names.root(),
                    coordinates, defaultStateId, airStateId, invalidStateId, allowedStateIds,
                    batchSize, expectedDensityValues, structureBlend, exactBarrierShader,
                    exactBarrierFunction);
        }
        if (expectedDirectDensityValues != null && Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugDensityBranchOnly", "false"))) {
            int[] branchWords = executeStagedDirectDensityBranch(executor, completeShader,
                    names.branches().get(1), coordinates, defaultStateId, airStateId,
                    invalidStateId, batchSize).outputWords();
            int branchCount = branchWords.length / DENSITY_STAGE_VALUE_WORDS;
            int[] componentWords = new int[Math.multiplyExact(branchCount, 6)];
            for (int index = 0; index < branchCount; index++) {
                componentWords[index * 6 + 2] = branchWords[index * 2];
                componentWords[index * 6 + 3] = branchWords[index * 2 + 1];
            }
            int[] diagnosticCoordinates = coordinates;
            double[] diagnosticExpected = expectedDirectDensityValues;
            if (branchCount != coordinates.length / 3) {
                diagnosticCoordinates = Arrays.copyOf(coordinates, branchCount * 3);
                diagnosticExpected = Arrays.copyOf(expectedDirectDensityValues, branchCount);
            }
            requireDirectBranchParity(diagnosticExpected, componentWords, diagnosticCoordinates);
            throw new IllegalStateException("GPU direct density branch diagnostic passed; stopping before staged density");
        }
        if (expectedFirstDensityValues != null && Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugDensityFirstBranchOnly", "false"))) {
            VulkanWorldgenExecutor.RawResult branchResult = executeStagedDensityRoot(executor,
                    completeShader, names.branches().get(0), coordinateWords(coordinates),
                    defaultStateId, airStateId, invalidStateId, batchSize, new HashSet<>());
            int branchCount = branchResult.elementCount();
            int[] componentWords = new int[Math.multiplyExact(branchCount, 6)];
            int[] branchWords = branchResult.outputWords();
            for (int index = 0; index < branchCount; index++) {
                int source = index * DENSITY_STAGE_VALUE_WORDS;
                int target = index * 6;
                componentWords[target] = branchWords[source];
                componentWords[target + 1] = branchWords[source + 1];
            }
            int[] diagnosticCoordinates = coordinates;
            double[] diagnosticExpected = expectedFirstDensityValues;
            if (branchCount != coordinates.length / 3) {
                diagnosticCoordinates = Arrays.copyOf(coordinates, branchCount * 3);
                diagnosticExpected = Arrays.copyOf(expectedFirstDensityValues, branchCount);
            }
            requireDensityComponentParity(diagnosticExpected, componentWords, 0,
                    diagnosticCoordinates, "recursive first density branch");
            throw new IllegalStateException(
                    "GPU recursive first density branch diagnostic passed; stopping before staged density");
        }
        if (expectedFirstNode45Values != null && Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugDensityNode45Monolithic", "false"))) {
            WorldgenShaderCompiler.Shader nodeShader = stageShader(completeShader,
                    densityStageSource(completeShader.source(), "wg_node_45"),
                    "density-node45-monolithic");
            SpirvNumericContract.require(nodeShader.source(), nodeShader.profile());
            VulkanWorldgenExecutor.RawResult nodeResult = executor.executeRawBatched(
                    densityStageRequest(new VulkanWorldgenExecutor.RawRequest(
                            nodeShader, coordinateWords(coordinates), 4, DENSITY_STAGE_VALUE_WORDS,
                            coordinates.length / 3, defaultStateId, airStateId, invalidStateId))
                            .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
            requireDensityComponentParity(expectedFirstNode45Values, nodeResult.outputWords(), 0, 2,
                    coordinates, "monolithic wg_node_45");
            throw new IllegalStateException(
                    "GPU monolithic wg_node_45 diagnostic passed; stopping before staged density");
        }
        if (Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugDensityBlendedNoiseOnly", "false"))) {
            String configuredRoot = System.getProperty(
                    "tellurium.gpuCandidate.debugDensityBlendedNoiseRoot", "").trim();
            String noiseRoot = configuredRoot.isEmpty()
                    ? firstFunctionName(completeShader.source(),
                    "(?m)^uvec2 (wg_noise_blended_[0-9]+)\\(", "captured blended-noise root")
                    : configuredRoot;
            requireDensityBlendedNoiseDeviceOracle(executor, completeShader, noiseRoot,
                    coordinates, defaultStateId, airStateId, invalidStateId, batchSize);
            throw new IllegalStateException(
                    "GPU blended-noise device oracle passed; stopping before staged density");
        }
        List<String> stageRoots = new ArrayList<>(names.splitBranches());
        stageRoots.add(names.selector());
        boolean aggressiveStaging = aggressiveDensityStaging(completeShader);
        List<DensityStagePlan> stagePlans = findDensityStagePlans(
                completeShader.source(), stageRoots, Set.of(), aggressiveStaging);
        DensityInterpolationGeometry geometry = densityInterpolationGeometryForBranch(
                completeShader.source(), names.branches().get(0));
        DensityStageInputs stageInputs = buildDensityStageInputs(coordinates, geometry);
        boolean stageDebugEnabled = Boolean.parseBoolean(
                System.getProperty("tellurium.gpuCandidate.debugStages", "false"));
        stageDebug(stageDebugEnabled, "density-stage-plan stages=" + stagePlans.size()
                + " uniqueCoordinates=" + stageInputs.uniqueCoordinates().length / 4
                + " blocks=" + coordinates.length / 3
                + " aggressive=" + aggressiveStaging);
        Set<String> finalRoots = Set.of(names.splitBranches().get(0), names.splitBranches().get(1), names.selector());
        Map<String, Integer> remainingConsumers = densityStageConsumerCounts(stagePlans);
        writeDensityStageDiagnostics(completeShader, stagePlans, names);
        int stopAfterStage = integerProperty("tellurium.gpuCandidate.stopAfterDensityStage", -1);
        if (stopAfterStage < -1) {
            throw new IllegalArgumentException("GPU candidate stopAfterDensityStage must be -1 or a zero-based stage index");
        }
        int deviceRecreateInterval = integerProperty(
                "tellurium.gpuCandidate.recreateDeviceInterval", -1);
        if (deviceRecreateInterval == 0 || deviceRecreateInterval < -1) {
            throw new IllegalArgumentException(
                    "GPU candidate recreateDeviceInterval must be -1 or a positive stage interval");
        }

        // Keep captured graph functions, captured-noise wrappers, the
        // per-table sampler helpers and the integer IEEE helper families out
        // of the inliner. The sampler has already been specialized, so no
        // permutation array or table-ID switch crosses this boundary.
        int elementCount = coordinates.length / 3;
        int[] stageCoordinates = stageInputs.uniqueCoordinates();
        int uniqueCount = stageCoordinates.length / 4;
        DeviceCapabilities device = null;
        Map<String, VulkanWorldgenExecutor.RawResult> stageResults = new LinkedHashMap<>();
        int stageProbeElements = integerProperty("tellurium.gpuCandidate.stageProbeElements", -1);
        if (stageProbeElements == 0 || stageProbeElements > uniqueCount) {
            throw new IllegalArgumentException("GPU candidate stageProbeElements must be -1 or in [1, "
                    + uniqueCount + "]");
        }
        if (stageProbeElements > 0) {
            if (stopAfterStage < 0) {
                throw new IllegalArgumentException(
                        "GPU candidate stageProbeElements requires stopAfterDensityStage");
            }
            if (stageProbeElements < uniqueCount) {
                stageCoordinates = Arrays.copyOf(stageCoordinates, stageProbeElements * 4);
                uniqueCount = stageProbeElements;
                stageDebug(stageDebugEnabled, "density-stage-probe-limit elements=" + uniqueCount);
            }
        }
        for (int stageIndex = 0; stageIndex < stagePlans.size();) {
            if (densityResidentScratchEnabled()) {
                int residentEnd = densityResidentGraphSegmentEnd(completeShader.source(), stagePlans,
                        stageIndex, stopAfterStage, deviceRecreateInterval);
                if (residentEnd > stageIndex) {
                    List<DensityStagePlan> residentPlans = stagePlans.subList(stageIndex, residentEnd);
                    Map<String, VulkanWorldgenExecutor.RawResult> resident =
                            executeResidentDensityGraphSegment(executor, completeShader, residentPlans,
                                    stageCoordinates, Map.of(), stageResults, defaultStateId,
                                    airStateId, invalidStateId, batchSize,
                                    "density-stage" + stageIndex);
                    for (int residentIndex = stageIndex; residentIndex < residentEnd; residentIndex++) {
                        DensityStagePlan plan = stagePlans.get(residentIndex);
                        VulkanWorldgenExecutor.RawResult result = requireStageResult(resident, plan.root());
                        if (device == null) device = result.device();
                        else requireSameDevice(device, result.device(), "density stages");
                        if (stopAfterStage >= 0) {
                            requireFiniteStageWords(result.outputWords(), stageCoordinates,
                                    residentIndex, plan.root());
                        }
                        if (remainingConsumers.containsKey(plan.root()) || finalRoots.contains(plan.root())) {
                            stageResults.put(plan.root(), result);
                        }
                        releaseDensityStageChildren(plan.childRoots(), remainingConsumers, stageResults, finalRoots);
                        stageDebug(stageDebugEnabled, "density-stage-complete index=" + residentIndex
                                + " root=" + plan.root() + " resident=true"
                                + debugStageValues(result.outputWords(), stageCoordinates));
                    }
                    int lastStage = residentEnd - 1;
                    if ((lastStage + 1) % DENSITY_PIPELINE_RECLAIM_INTERVAL == 0) {
                        executor.reclaimPipelines();
                        stageDebug(stageDebugEnabled, "density-pipeline-reclaim index=" + lastStage);
                    }
                    if (deviceRecreateInterval > 0 && (lastStage + 1) % deviceRecreateInterval == 0) {
                        executor.recreateDevice();
                        stageDebug(stageDebugEnabled, "density-device-recreate index=" + lastStage);
                    }
                    if (lastStage == stopAfterStage) {
                        throw new IllegalStateException("GPU candidate stopped after density stage " + lastStage
                                + " root=" + stagePlans.get(lastStage).root()
                                + " for compiler/driver diagnostics");
                    }
                    stageIndex = residentEnd;
                    continue;
                }
            }
            int bundleEnd = densityStageBundleEnd(completeShader.source(), stagePlans, stageIndex,
                    stopAfterStage, deviceRecreateInterval);
            if (bundleEnd - stageIndex > 1) {
                List<DensityStagePlan> bundlePlans = stagePlans.subList(stageIndex, bundleEnd);
                List<String> bundleChildren = densityStageBundleChildren(bundlePlans);
                String bundleSource = densityStageBundleSourceForPlans(completeShader.source(),
                        bundlePlans, bundleChildren);
                int bundleSourceLimit = densityStageBundleSourceLimit();
                if (bundleSource.length() <= bundleSourceLimit) {
                    WorldgenShaderCompiler.Shader bundleShader = stageShader(completeShader, bundleSource,
                            "density-bundle-" + stageIndex + "-" + (bundleEnd - 1));
                    SpirvNumericContract.require(bundleShader.source(), bundleShader.profile());
                    stageDebug(stageDebugEnabled, "density-stage-bundle start=" + stageIndex
                            + " end=" + (bundleEnd - 1) + " roots=" + bundlePlans.size()
                            + " chars=" + bundleSource.length());
                    Map<String, VulkanWorldgenExecutor.RawResult> bundled = executeDensityStageBundle(
                            executor, bundleShader, bundlePlans, bundleChildren, stageCoordinates,
                            stageResults, defaultStateId, airStateId, invalidStateId, batchSize);
                    for (int bundledIndex = stageIndex; bundledIndex < bundleEnd; bundledIndex++) {
                        DensityStagePlan plan = stagePlans.get(bundledIndex);
                        VulkanWorldgenExecutor.RawResult result = requireStageResult(bundled, plan.root());
                        if (device == null) device = result.device();
                        else requireSameDevice(device, result.device(), "density stages");
                        if (stopAfterStage >= 0) {
                            requireFiniteStageWords(result.outputWords(), stageCoordinates,
                                    bundledIndex, plan.root());
                        }
                        if (stageDebugEnabled && stageProbeElements > 0
                                && (plan.root().equals(names.splitBranches().get(0))
                                || plan.root().equals(names.splitBranches().get(1))
                                || plan.root().equals(names.selector()))) {
                            int[] probe = result.outputWords();
                            long bits = (Integer.toUnsignedLong(probe[1]) << 32)
                                    | Integer.toUnsignedLong(probe[0]);
                            stageDebug(true, "density-stage-probe root=" + plan.root()
                                    + " point=" + stageCoordinates[0] + "," + stageCoordinates[1]
                                    + "," + stageCoordinates[2] + " value="
                                    + Double.longBitsToDouble(bits) + " bits=0x"
                                    + String.format(Locale.ROOT, "%016x", bits));
                        }
                        if (remainingConsumers.containsKey(plan.root()) || finalRoots.contains(plan.root())) {
                            stageResults.put(plan.root(), result);
                        }
                        releaseDensityStageChildren(plan.childRoots(), remainingConsumers, stageResults, finalRoots);
                        stageDebug(stageDebugEnabled, "density-stage-complete index=" + bundledIndex
                                + " root=" + plan.root() + " bundled=true");
                    }
                    int lastStage = bundleEnd - 1;
                    if ((lastStage + 1) % DENSITY_PIPELINE_RECLAIM_INTERVAL == 0) {
                        executor.reclaimPipelines();
                        stageDebug(stageDebugEnabled, "density-pipeline-reclaim index=" + lastStage);
                    }
                    if (deviceRecreateInterval > 0 && (lastStage + 1) % deviceRecreateInterval == 0) {
                        executor.recreateDevice();
                        stageDebug(stageDebugEnabled, "density-device-recreate index=" + lastStage);
                    }
                    if (lastStage == stopAfterStage) {
                        throw new IllegalStateException("GPU candidate stopped after density stage " + lastStage
                                + " root=" + stagePlans.get(lastStage).root()
                                + " for compiler/driver diagnostics");
                    }
                    stageIndex = bundleEnd;
                    continue;
                }
                stageDebug(stageDebugEnabled, "density-stage-bundle-skip start=" + stageIndex
                        + " end=" + (bundleEnd - 1) + " chars=" + bundleSource.length()
                        + " limit=" + bundleSourceLimit);
            }
            DensityStagePlan plan = stagePlans.get(stageIndex);
            // The ordinary recursive planner reaches the same captured
            // normal-noise leaves as the direct-branch planner.  The
            // direct two-call fan-out is kept as an opt-in experiment; its
            // duplicated helper closure is not the default compiler policy.
            // The two-call normal-noise fan-out remains available as an
            // explicit driver experiment, but the default graph stage is the
            // smaller, previously proven compiler unit.  Splitting it here
            // duplicates the complete integer-IEEE helper closure and can
            // make shaderc spend minutes compiling two nearly identical
            // modules even for a one-point diagnostic.
            boolean sharedSpline = canExecuteSharedSplineStage(completeShader, plan);
            DirectNormalNoise directNormalNoise = !sharedSpline && plan.kind() == DensityStageKind.GRAPH
                    && plan.childRoots().isEmpty()
                    && splitNormalNoise(completeShader)
                    ? detectDirectNormalNoise(completeShader.source(), plan.root()) : null;
            DirectNormalNoise embeddedNormalNoise = !sharedSpline && plan.kind() == DensityStageKind.GRAPH
                    && !plan.childRoots().isEmpty()
                    && splitNormalNoise(completeShader)
                    ? detectEmbeddedNormalNoise(completeShader.source(), plan.root()) : null;
            boolean specialStage = sharedSpline || plan.kind() != DensityStageKind.GRAPH
                    || directNormalNoise != null || embeddedNormalNoise != null;
            WorldgenShaderCompiler.Shader stageShaderBase = sharedSpline ? completeShader : exactInterpolationStageShader(
                    completeShader, plan.root());
            String source = specialStage ? "" : densityStageSourceForPlan(stageShaderBase.source(), plan);
            WorldgenShaderCompiler.Shader stageShader = specialStage
                    ? null : stageShader(stageShaderBase, source, "density-stage-" + stageIndex);
            if (stageShader != null) SpirvNumericContract.require(stageShader.source(), stageShader.profile());
            stageDebug(stageDebugEnabled, "density-stage-plan index=" + stageIndex
                    + " root=" + plan.root() + " children=" + plan.childRoots().size()
                    + " kind=" + (directNormalNoise != null ? "DIRECT_NORMAL_NOISE"
                    : embeddedNormalNoise != null ? "EMBEDDED_NORMAL_NOISE" : plan.kind())
                    + " chars=" + source.length());
            int inputWordsPerElement = densityStageInputWords(plan.childRoots());
            int[] inputWords = sharedSpline ? null : densityStageInput(stageCoordinates, plan.childRoots(), stageResults);
            stageDebug(stageDebugEnabled, "density-stage-start index=" + stageIndex
                    + " root=" + plan.root() + " children=" + plan.childRoots().size());
            VulkanWorldgenExecutor.RawResult result;
            if (sharedSpline) {
                result = executeSharedSplineStage(executor, completeShader, plan,
                        stageCoordinates, stageResults, defaultStateId, airStateId, invalidStateId, batchSize);
            } else if (capturedEndIslandRoot(completeShader.source(), plan.root())) {
                result = executeStagedEndIsland(executor, completeShader, plan.root(), stageCoordinates, batchSize);
            } else if (plan.kind() == DensityStageKind.BLENDED_NOISE) {
                result = executeStagedBlendedNoise(executor, completeShader, plan.root(),
                        stageCoordinates, batchSize);
            } else if (directNormalNoise != null) {
                try {
                    result = executeStagedNormalNoise(executor, completeShader, directNormalNoise,
                            stageCoordinates, defaultStateId, airStateId, invalidStateId, batchSize);
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("GPU density stage failed index=" + stageIndex
                            + " root=" + plan.root() + " kind=DIRECT_NORMAL_NOISE"
                            + " uniqueCoordinates=" + uniqueCount, failure);
                }
            } else if (embeddedNormalNoise != null) {
                try {
                    result = executeStagedEmbeddedNormalNoise(executor, completeShader, embeddedNormalNoise,
                            plan.childRoots(), inputWords, stageCoordinates, defaultStateId,
                            airStateId, invalidStateId, batchSize);
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("GPU density stage failed index=" + stageIndex
                            + " root=" + plan.root() + " kind=EMBEDDED_NORMAL_NOISE"
                            + " uniqueCoordinates=" + uniqueCount, failure);
                }
            } else if (plan.kind() == DensityStageKind.FP64_DIVISION) {
                try {
                    result = executeStagedFp64Division(executor, completeShader, plan,
                            inputWords, uniqueCount, defaultStateId, airStateId, invalidStateId, batchSize);
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("GPU density stage failed index=" + stageIndex
                            + " root=" + plan.root() + " kind=" + plan.kind()
                            + " uniqueCoordinates=" + uniqueCount, failure);
                }
            } else {
                try {
                    VulkanWorldgenExecutor.RawRequest stageRequest = densityStageRequest(
                            new VulkanWorldgenExecutor.RawRequest(stageShader, inputWords,
                                    inputWordsPerElement, DENSITY_STAGE_VALUE_WORDS, uniqueCount,
                                    defaultStateId, airStateId, invalidStateId));
                    result = executor.executeRawBatched(
                            stageRequest.withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("GPU density stage failed index=" + stageIndex
                            + " root=" + plan.root() + " kind=" + plan.kind()
                            + " uniqueCoordinates=" + uniqueCount, failure);
                }
            }
            if (device == null) device = result.device();
            else requireSameDevice(device, result.device(), "density stages");
            if (stopAfterStage >= 0) {
                requireFiniteStageWords(result.outputWords(), stageCoordinates, stageIndex, plan.root());
            }
            if (stageDebugEnabled && stageProbeElements > 0
                    && (plan.root().equals(names.splitBranches().get(0))
                    || plan.root().equals(names.splitBranches().get(1))
                    || plan.root().equals(names.selector()))) {
                int[] probe = result.outputWords();
                long bits = (Integer.toUnsignedLong(probe[1]) << 32)
                        | Integer.toUnsignedLong(probe[0]);
                stageDebug(true, "density-stage-probe root=" + plan.root()
                        + " point=" + stageCoordinates[0] + "," + stageCoordinates[1]
                        + "," + stageCoordinates[2] + " value="
                        + Double.longBitsToDouble(bits) + " bits=0x"
                        + String.format(Locale.ROOT, "%016x", bits));
            }
            if (remainingConsumers.containsKey(plan.root()) || finalRoots.contains(plan.root())) {
                stageResults.put(plan.root(), result);
            }
            releaseDensityStageChildren(plan.childRoots(), remainingConsumers, stageResults, finalRoots);
            stageDebug(stageDebugEnabled, "density-stage-complete index=" + stageIndex
                    + " root=" + plan.root()
                    + debugStageValues(result.outputWords(), stageCoordinates));
            if ((stageIndex + 1) % DENSITY_PIPELINE_RECLAIM_INTERVAL == 0) {
                // A few drivers retain JIT/compiler working sets after a
                // pipeline is destroyed.  Reaching device-idle at a bounded
                // stage boundary keeps a large captured graph from growing a
                // process-wide native footprint while preserving one device
                // and queue for the whole candidate run.
                executor.reclaimPipelines();
                stageDebug(stageDebugEnabled, "density-pipeline-reclaim index=" + stageIndex);
            }
            if (deviceRecreateInterval > 0 && (stageIndex + 1) % deviceRecreateInterval == 0) {
                // Some development drivers retain shader compiler state for
                // the device lifetime.  This opt-in boundary is a draft-mode
                // containment measure for very large captured graphs; normal
                // callers retain the persistent device/queue contract.
                executor.recreateDevice();
                stageDebug(stageDebugEnabled, "density-device-recreate index=" + stageIndex);
            }
            if (stageIndex == stopAfterStage) {
                throw new IllegalStateException("GPU candidate stopped after density stage " + stageIndex
                        + " root=" + plan.root() + " for compiler/driver diagnostics");
            }
            stageIndex++;
        }
        VulkanWorldgenExecutor.RawResult splitFirst = requireStageResult(stageResults,
                names.splitBranches().get(0));
        VulkanWorldgenExecutor.RawResult splitSecond = requireStageResult(stageResults,
                names.splitBranches().get(1));
        VulkanWorldgenExecutor.RawResult selector = requireStageResult(stageResults, names.selector());
        VulkanWorldgenExecutor.RawResult secondBranch = executeStagedDirectDensityBranch(executor,
                completeShader, names.branches().get(1), coordinates, defaultStateId, airStateId,
                invalidStateId, batchSize);
        stageDebug(stageDebugEnabled, "density-branch-complete name=second-branch");
        int[] coordinateWords = coordinateWords(coordinates);
        if (device == null) device = splitFirst.device();
        requireSameDevice(device, splitFirst.device(), "density stages");
        requireSameDevice(device, splitSecond.device(), "density stages");
        requireSameDevice(device, selector.device(), "density stages");
        requireSameDevice(device, secondBranch.device(), "density branch stages");
        int[] splitFirstWords = splitFirst.outputWords();
        int[] splitSecondWords = splitSecond.outputWords();
        int[] selectorWords = selector.outputWords();
        int[] secondBranchWords = secondBranch.outputWords();
        debugDensityCornerRows(stageInputs.uniqueCoordinates(), splitFirstWords,
                splitSecondWords, selectorWords);
        int[] cornerIndices = stageInputs.cornerIndices();
        int[] interpolationFractions = densityInterpolationFractionWords(
                coordinateWords(coordinates), geometry);
        int materialInputWords = DENSITY_MATERIAL_INPUT_WORDS;
        int[] combinedInput = new int[Math.multiplyExact(elementCount, materialInputWords)];
        for (int index = 0; index < elementCount; index++) {
            int coordinate = index * 3;
                int target = index * materialInputWords;
                combinedInput[target] = coordinates[coordinate];
                combinedInput[target + 1] = coordinates[coordinate + 1];
                combinedInput[target + 2] = coordinates[coordinate + 2];
                System.arraycopy(interpolationFractions,
                        index * DENSITY_MATERIAL_INPUT_FRACTION_WORDS,
                        combinedInput, target + 3, DENSITY_MATERIAL_INPUT_FRACTION_WORDS);
                for (int corner = 0; corner < DENSITY_STAGE_CORNER_COUNT; corner++) {
                    int unique = cornerIndices[index * DENSITY_STAGE_CORNER_COUNT + corner];
                    int stageWord = unique * DENSITY_STAGE_VALUE_WORDS;
                    int firstTarget = target + DENSITY_MATERIAL_INPUT_VALUE_BASE_WORDS
                            + corner * DENSITY_STAGE_VALUE_WORDS;
                    combinedInput[firstTarget] = splitFirstWords[stageWord];
                    combinedInput[firstTarget + 1] = splitFirstWords[stageWord + 1];
                    int secondTarget = target + DENSITY_MATERIAL_INPUT_VALUE_BASE_WORDS
                            + DENSITY_STAGE_CORNER_VALUE_WORDS
                            + corner * DENSITY_STAGE_VALUE_WORDS;
                    combinedInput[secondTarget] = splitSecondWords[stageWord];
                    combinedInput[secondTarget + 1] = splitSecondWords[stageWord + 1];
                    int selectorTarget = target + DENSITY_MATERIAL_INPUT_VALUE_BASE_WORDS
                            + DENSITY_STAGE_CORNER_VALUE_WORDS * 2
                            + corner * DENSITY_STAGE_VALUE_WORDS;
                    combinedInput[selectorTarget] = selectorWords[stageWord];
                    combinedInput[selectorTarget + 1] = selectorWords[stageWord + 1];
                }
                int branchWord = index * DENSITY_STAGE_VALUE_WORDS;
                int branchTarget = target + DENSITY_MATERIAL_INPUT_VALUE_BASE_WORDS
                        + DENSITY_STAGE_CORNER_VALUE_WORDS * DENSITY_DIRECT_BRANCH_SLOT;
                combinedInput[branchTarget] = secondBranchWords[branchWord];
                combinedInput[branchTarget + 1] = secondBranchWords[branchWord + 1];
        }
        // Keep the density interpolation and the material helpers in separate
        // pipelines.  Aquifer and ore capture can each retain a large graph;
        // putting both behind the final material call creates a source module
        // that is valid GLSL but exceeds the target driver's compile envelope.
        WorldgenShaderCompiler.Shader densityMaterialShader = stageShader(completeShader,
                densityMaterialInputStageSource(completeShader.source(), expectedDensityValues != null),
                "density-material-input");
        SpirvNumericContract.require(densityMaterialShader.source(), densityMaterialShader.profile());
        VulkanWorldgenExecutor.RawResult densityResult;
        int densityOutputWords = expectedDensityValues == null ? DENSITY_STAGE_VALUE_WORDS
                : expectedFirstInterpolationValues != null ? 18 : 6;
        try {
            densityResult = executor.executeRawBatched(new VulkanWorldgenExecutor.RawRequest(
                    densityMaterialShader, combinedInput, DENSITY_MATERIAL_INPUT_WORDS,
                    densityOutputWords, elementCount, defaultStateId, airStateId, invalidStateId)
                    .withDontInlineFunctions(false, "")
                    .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("GPU density material-input stage failed for "
                    + elementCount + " blocks", failure);
        }
        requireSameDevice(device, densityResult.device(), "density material-input stage");
        stageDebug(stageDebugEnabled, "density-material-input-complete");

        int[] densityWords;
        if (expectedDensityValues == null) {
            densityWords = densityResult.outputWords();
        } else {
            int[] componentWords = densityResult.outputWords();
            boolean materialInternals = expectedFirstInterpolationValues != null;
            int componentWordsPerElement = materialInternals ? 18 : 6;
            if (componentWords.length != Math.multiplyExact(elementCount, componentWordsPerElement)) {
                throw new IllegalStateException("GPU density component output length is " + componentWords.length
                        + ", expected " + Math.multiplyExact(elementCount, componentWordsPerElement));
            }
            densityWords = new int[Math.multiplyExact(elementCount, DENSITY_STAGE_VALUE_WORDS)];
            for (int index = 0; index < elementCount; index++) {
                int source = index * componentWordsPerElement;
                int target = index * DENSITY_STAGE_VALUE_WORDS;
                int densityOffset = materialInternals ? 16 : 4;
                densityWords[target] = componentWords[source + densityOffset];
                densityWords[target + 1] = componentWords[source + densityOffset + 1];
                if (stageDebugEnabled && index < 12) {
                    stageDebug(true, "density-component-probe index=" + index + " point="
                            + coordinates[index * 3] + "," + coordinates[index * 3 + 1] + ","
                            + coordinates[index * 3 + 2] + " first="
                            + carrierValue(componentWords, source) + " second="
                            + carrierValue(componentWords, source + (materialInternals ? 14 : 2)) + " cpuSecond="
                            + Double.toString(expectedDirectDensityValues[index]) + " final="
                            + carrierValue(componentWords, source + densityOffset));
                }
            }
            requireDensityComponentParity(expectedDirectDensityValues, componentWords,
                    materialInternals ? 7 : 1, componentWordsPerElement, coordinates,
                    "direct density branch");
            if (materialInternals) {
                StringBuilder internalFailures = new StringBuilder();
                appendDensityComponentFailure(internalFailures, expectedFirstInterpolationValues,
                        componentWords, 1, componentWordsPerElement, coordinates,
                        "first density interpolation");
                appendDensityComponentFailure(internalFailures, expectedFirstNode5Values,
                        componentWords, 2, componentWordsPerElement, coordinates,
                        "first density interpolation child");
                appendDensityComponentFailure(internalFailures, expectedFirstNode44Values,
                        componentWords, 3, componentWordsPerElement, coordinates,
                        "first density interpolation split");
                appendDensityComponentFailure(internalFailures, expectedFirstNode45Values,
                        componentWords, 4, componentWordsPerElement, coordinates,
                        "first density interpolation selector");
                appendDensityComponentFailure(internalFailures, expectedFirstNode2101Values,
                        componentWords, 5, componentWordsPerElement, coordinates,
                        "first density interpolation true branch");
                appendDensityComponentFailure(internalFailures, expectedFirstNode2150Values,
                        componentWords, 6, componentWordsPerElement, coordinates,
                        "first density interpolation false branch");
                if (internalFailures.length() != 0) {
                    throw new IllegalStateException("GPU density material internals differ from CPU:\n"
                            + internalFailures);
                }
            }
            if (expectedFirstDensityValues != null) {
                requireDensityComponentParity(expectedFirstDensityValues, componentWords, 0,
                        componentWordsPerElement, coordinates, "first density branch");
            }
        }
        requireFiniteDensityWords(densityWords, coordinates);
        return executeMaterialStages(executor, completeShader, coordinates, densityWords,
                defaultStateId, airStateId, invalidStateId, allowedStateIds, batchSize, device,
                "staged density", expectedDensityValues, structureBlend, exactBarrierShader,
                exactBarrierFunction);
    }

    /**
     * Runs the complete captured density root through the same bounded
     * child/interpolation planner, then reuses the ordinary aquifer, ore and
     * material stages.  This keeps root semantics on the device without
     * asking the compiler to lower the whole captured material program as one
     * monolithic shader.
     */
    private static DeviceMaterialResult executeStagedSingleBranchDensity(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            String root, int[] coordinates, int defaultStateId, int airStateId,
            int invalidStateId, int[] allowedStateIds, int batchSize,
            double[] expectedDensityValues, StructureBlendSnapshot structureBlend,
            WorldgenShaderCompiler.Shader exactBarrierShader, String exactBarrierFunction) {
        int elementCount = coordinates.length / 3;
        VulkanWorldgenExecutor.RawResult densityResult = executeStagedDensityRoot(
                executor, completeShader, root, coordinateWords(coordinates), defaultStateId,
                airStateId, invalidStateId, batchSize, new HashSet<>());
        if (densityResult.elementCount() != elementCount
                || densityResult.outputWordsPerElement() != DENSITY_STAGE_VALUE_WORDS) {
            throw new IllegalStateException("Single-branch density returned incompatible geometry: " + root);
        }
        int[] densityWords = densityResult.outputWords();
        requireFiniteDensityWords(densityWords, coordinates);
        boolean stageDebugEnabled = Boolean.parseBoolean(
                System.getProperty("tellurium.gpuCandidate.debugStages", "false"));
        stageDebug(stageDebugEnabled, "density-single-branch-complete root=" + root
                + " elements=" + elementCount + debugStageValues(densityWords, coordinateWords(coordinates))
                + (expectedDensityValues == null ? "" : " cpuExpected=" + expectedDensityValues[0]));
        return executeMaterialStages(executor, completeShader, coordinates, densityWords,
                defaultStateId, airStateId, invalidStateId, allowedStateIds, batchSize,
                densityResult.device(), "single-branch density", expectedDensityValues, structureBlend,
                exactBarrierShader, exactBarrierFunction);
    }

    /** Runs the shared material consumers after a device-produced density row. */
    private static DeviceMaterialResult executeMaterialStages(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            int[] coordinates, int[] densityWords, int defaultStateId, int airStateId,
            int invalidStateId, int[] allowedStateIds, int batchSize,
            DeviceCapabilities device, String routeLabel, double[] expectedDensityValues,
            StructureBlendSnapshot structureBlend,
            WorldgenShaderCompiler.Shader exactBarrierShader, String exactBarrierFunction) {
        if (Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.deviceResidentMaterial", "false"))) {
            if (stagedOreInputsEnabled(completeShader)
                    && lastFunctionNameOrNull(completeShader.source(),
                    "(?m)^uvec2 (wg_ore_[A-Za-z0-9_]+)\\(") != null) {
                throw new UnsupportedOperationException(
                        "Staged ore inputs are not yet supported by the diagnostic resident material chain");
            }
            if (MATERIAL_DENSITY_FALLBACK.get() != null) {
                throw new IllegalArgumentException(
                        "CPU density material fallback is incompatible with device-resident material");
            }
            requireFiniteDensityWords(densityWords, coordinates);
            return executeMaterialStagesDeviceResident(executor, completeShader, coordinates, densityWords,
                    defaultStateId, airStateId, invalidStateId, allowedStateIds, batchSize, device,
                    routeLabel, structureBlend, expectedDensityValues, exactBarrierShader,
                    exactBarrierFunction);
        }
        int elementCount = coordinates.length / 3;
        boolean stageDebugEnabled = Boolean.parseBoolean(
                System.getProperty("tellurium.gpuCandidate.debugStages", "false"));
        densityWords = executeBeardifierCombineStage(executor, completeShader, structureBlend,
                coordinates, densityWords, batchSize, device);
        requireFiniteDensityWords(densityWords, coordinates);
        double[] materialFallback = MATERIAL_DENSITY_FALLBACK.get();
        if (materialFallback != null) {
            if (materialFallback.length != elementCount) {
                throw new IllegalStateException("CPU density material fallback length does not match GPU coordinates: cpu="
                        + materialFallback.length + " gpu=" + elementCount);
            }
            densityWords = cpuDensityWords(materialFallback);
            stageDebug(stageDebugEnabled, "cpu-density-material-fallback applied elements=" + elementCount);
        } else if (expectedDensityValues != null) {
            requireDensityParity(expectedDensityValues, densityWords, coordinates);
        }
        int[] coordinateInput = coordinateWords(coordinates);
        boolean externalBarrier = useExternalAquiferBarrier(exactAquiferBarrierInputEnabled(), exactBarrierFunction);
        int[] barrierWords = null;
        if (externalBarrier) {
            if (exactBarrierShader == null || exactBarrierFunction == null || exactBarrierFunction.isBlank()) {
                throw new IllegalStateException(
                        "External aquifer barrier input requires exact barrier shader metadata");
            }
            VulkanWorldgenExecutor.RawResult barrierResult = executeStagedDensityRoot(
                    executor, exactBarrierShader, exactBarrierFunction, coordinateInput,
                    defaultStateId, airStateId, invalidStateId, batchSize, new HashSet<>(), false, null);
            if (barrierResult.elementCount() != elementCount
                    || barrierResult.outputWordsPerElement() != DENSITY_STAGE_VALUE_WORDS) {
                throw new IllegalStateException("Aquifer barrier carrier geometry does not match material coordinates");
            }
            barrierWords = barrierResult.outputWords();
            requireFiniteDensityWords(barrierWords, coordinates);
            stageDebug(stageDebugEnabled, "density-aquifer-barrier-complete elements=" + elementCount
                    + " first=" + carrierValue(barrierWords, 0));
        }
        WorldgenShaderCompiler.Shader aquiferCompleteShader = completeShader;
        if (Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.exactAquiferStage", "false"))) {
            WorldgenShaderCompiler.Shader exactAquiferShader = EXACT_NORMAL_NOISE_SHADER.get();
            if (exactAquiferShader == null) {
                throw new IllegalStateException("Exact aquifer stage requested before exact shader capture");
            }
            if (!Boolean.parseBoolean(System.getProperty(
                    "tellurium.gpuCandidate.exactAquiferStageNative", "false"))
                    || completeShader.profile() != NumericProfile.GPU_NATIVE_DRAFT) {
                aquiferCompleteShader = exactAquiferShader;
            }
        }
        String aquiferFunction = aquiferFunctionName(aquiferCompleteShader.source());
        WorldgenShaderCompiler.Shader aquiferShader = stageShader(aquiferCompleteShader,
                aquiferStageSource(aquiferCompleteShader.source(), aquiferFunction, externalBarrier),
                aquiferCompleteShader == completeShader ? "density-aquifer" : "density-aquifer-exact");
        writeLiveStageDiagnostic("density-aquifer-live.comp", aquiferShader.source());
        SpirvNumericContract.require(aquiferShader.source(), aquiferShader.profile());
        int aquiferInputWords = externalBarrier ? AQUIFER_STAGE_EXTERNAL_INPUT_WORDS : AQUIFER_STAGE_INPUT_WORDS;
        int[] aquiferInput = new int[Math.multiplyExact(elementCount, aquiferInputWords)];
        for (int index = 0; index < elementCount; index++) {
            int coordinate = index * 3;
            int target = index * aquiferInputWords;
            aquiferInput[target] = coordinates[coordinate];
            aquiferInput[target + 1] = coordinates[coordinate + 1];
            aquiferInput[target + 2] = coordinates[coordinate + 2];
            int densityWord = index * DENSITY_STAGE_VALUE_WORDS;
            aquiferInput[target + 3] = densityWords[densityWord];
            aquiferInput[target + 4] = densityWords[densityWord + 1];
            if (externalBarrier) {
                int barrierWord = index * DENSITY_STAGE_VALUE_WORDS;
                aquiferInput[target + 5] = barrierWords[barrierWord];
                aquiferInput[target + 6] = barrierWords[barrierWord + 1];
            }
        }
        VulkanWorldgenExecutor.RawResult aquiferResult;
        try {
            aquiferResult = executor.executeRawBatched(new VulkanWorldgenExecutor.RawRequest(
                    aquiferShader, aquiferInput, aquiferInputWords, 4, elementCount,
                    defaultStateId, airStateId, invalidStateId)
                    .withDontInlineFunctions(aquiferStageDontInlineEnabled(), aquiferStageDontInlinePrefix())
                    .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("GPU aquifer material stage failed for "
                    + elementCount + " blocks", failure);
        }
        requireSameDevice(device, aquiferResult.device(), routeLabel + " aquifer material stage");
        stageDebug(stageDebugEnabled, "density-aquifer-complete");
        int[] aquiferWords = aquiferResult.outputWords();
        requireValidMaterialStageWords(aquiferWords, coordinates, 4, "aquifer");
        if (stageDebugEnabled && aquiferWords.length >= 4) {
            stageDebug(true, "density-aquifer-first="
                    + Arrays.toString(Arrays.copyOf(aquiferWords, 4)));
        }
        if (Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugAquiferTrace", "false"))) {
            throw new IllegalStateException("GPU aquifer scalar trace="
                    + Arrays.toString(Arrays.copyOf(aquiferWords, 4)));
        }

        String oreFunction = lastFunctionNameOrNull(completeShader.source(),
                "(?m)^uvec2 (wg_ore_[A-Za-z0-9_]+)\\(");
        int[] oreWords;
        if (oreFunction == null) {
            // Some dimension captures intentionally have no ore emitter. Keep
            // the ABI explicit and feed the GPU material combiner an empty
            // ore result instead of inventing a CPU-side block decision.
            oreWords = new int[Math.multiplyExact(elementCount, DENSITY_STAGE_VALUE_WORDS)];
            stageDebug(stageDebugEnabled, "density-ore-skipped-no-captured-emitter");
        } else {
            boolean externalOre = stagedOreInputsEnabled(completeShader);
            int[] oreInput = coordinateInput;
            int oreInputWords = ORE_STAGE_INPUT_WORDS;
            if (externalOre) {
                List<String> roots = oreStageInputRoots(completeShader.source(), oreFunction);
                int[][] carriers = new int[3][];
                for (int rootIndex = 0; rootIndex < roots.size(); rootIndex++) {
                    VulkanWorldgenExecutor.RawResult carrier = executeStagedDensityRoot(
                            executor, completeShader, roots.get(rootIndex), coordinateInput,
                            defaultStateId, airStateId, invalidStateId, batchSize, new HashSet<>(), false, null);
                    requireSameDevice(device, carrier.device(), "ore input " + roots.get(rootIndex));
                    if (carrier.elementCount() != elementCount
                            || carrier.outputWordsPerElement() != DENSITY_STAGE_VALUE_WORDS) {
                        throw new IllegalStateException("Ore input carrier geometry differs: " + roots.get(rootIndex));
                    }
                    carriers[rootIndex] = carrier.outputWords();
                    requireFiniteDensityWords(carriers[rootIndex], coordinates);
                    stageDebug(stageDebugEnabled, "density-ore-input root=" + roots.get(rootIndex)
                            + " elements=" + elementCount + " first=" + carrierValue(carriers[rootIndex], 0));
                }
                oreInput = packOreStageInputs(coordinateInput, carriers);
                oreInput = executeOreDivisionInputs(executor, completeShader, oreFunction, oreInput,
                        defaultStateId, airStateId, invalidStateId, batchSize, device);
                oreInputWords = ORE_STAGE_FINISH_INPUT_WORDS;
            }
            WorldgenShaderCompiler.Shader oreShader = stageShader(completeShader,
                    externalOre ? oreFinishStageSource(completeShader.source(), oreFunction)
                            : oreStageSource(completeShader.source(), oreFunction),
                    externalOre ? "density-ore-external-v1" : "density-ore");
            writeLiveStageDiagnostic("density-ore-live.comp", oreShader.source());
            SpirvNumericContract.require(oreShader.source(), oreShader.profile());
            VulkanWorldgenExecutor.RawResult oreResult;
            try {
                oreResult = executor.executeRawBatched(new VulkanWorldgenExecutor.RawRequest(
                        oreShader, oreInput, oreInputWords, DENSITY_STAGE_VALUE_WORDS, elementCount,
                        defaultStateId, airStateId, invalidStateId)
                        .withDontInlineFunctions(!externalOre && materialStageDontInlineEnabled(),
                                externalOre ? "" : materialStageDontInlinePrefix())
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
            } catch (RuntimeException failure) {
                throw new IllegalStateException("GPU ore material stage failed for "
                        + elementCount + " blocks", failure);
            }
            requireSameDevice(device, oreResult.device(), routeLabel + " ore material stage");
            stageDebug(stageDebugEnabled, "density-ore-complete");
            oreWords = oreResult.outputWords();
            requireValidMaterialStageWords(oreWords, coordinates, DENSITY_STAGE_VALUE_WORDS, "ore");
        }
        if (stageDebugEnabled && oreWords.length >= DENSITY_STAGE_VALUE_WORDS) {
            stageDebug(true, "density-ore-first="
                    + Arrays.toString(Arrays.copyOf(oreWords, DENSITY_STAGE_VALUE_WORDS)));
        }

        int[] materialInput = new int[Math.multiplyExact(elementCount, MATERIAL_STAGE_INPUT_WORDS)];
        for (int index = 0; index < elementCount; index++) {
            int coordinate = index * 3;
            int target = index * MATERIAL_STAGE_INPUT_WORDS;
            materialInput[target] = coordinates[coordinate];
            materialInput[target + 1] = coordinates[coordinate + 1];
            materialInput[target + 2] = coordinates[coordinate + 2];
            int densityWord = index * DENSITY_STAGE_VALUE_WORDS;
            materialInput[target + 3] = densityWords[densityWord];
            materialInput[target + 4] = densityWords[densityWord + 1];
            int aquiferWord = index * 4;
            System.arraycopy(aquiferWords, aquiferWord, materialInput, target + 5, 4);
            int oreWord = index * DENSITY_STAGE_VALUE_WORDS;
            materialInput[target + 9] = oreWords[oreWord];
            materialInput[target + 10] = oreWords[oreWord + 1];
        }
        WorldgenShaderCompiler.Shader materialShader = stageShader(completeShader,
                materialStageSource(completeShader.source()), "density-material");
        writeLiveStageDiagnostic("density-material-live.comp", materialShader.source());
        SpirvNumericContract.require(materialShader.source(), materialShader.profile());
        VulkanWorldgenExecutor.RawResult raw;
        try {
            raw = executor.executeRawBatched(new VulkanWorldgenExecutor.RawRequest(
                    materialShader, materialInput, MATERIAL_STAGE_INPUT_WORDS,
                    DENSITY_STAGE_VALUE_WORDS, elementCount, defaultStateId, airStateId, invalidStateId)
                    .withDontInlineFunctions(materialStageDontInlineEnabled(), materialStageDontInlinePrefix())
                    .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("GPU material combine stage failed for "
                    + elementCount + " blocks", failure);
        }
        stageDebug(stageDebugEnabled, "density-final-complete");
        requireSameDevice(device, raw.device(), routeLabel + " final stage");
        int[] words = raw.outputWords();
        if (stageDebugEnabled && words.length >= 2) {
            stageDebug(true, "density-material-first="
                    + Arrays.toString(Arrays.copyOf(words, 2)));
        }
        int[] stateIds = new int[elementCount];
        boolean[] fluidMarks = new boolean[elementCount];
        for (int index = 0; index < elementCount; index++) {
                int word = index * 2;
                stateIds[index] = words[word];
                fluidMarks[index] = decodeFluidMark(words[word + 1], "staged density")
                        && stateIds[index] != airStateId;
                if (!contains(allowedStateIds, stateIds[index])) {
                    throw new IllegalStateException("Staged density shader returned state ID outside the dispatch ABI: "
                            + stateIds[index]);
                }
        }
        return new DeviceMaterialResult(stateIds, fluidMarks, raw.device(), raw.shaderHash(),
                raw.spirvHash(), elementCount);
    }

    /** Runs only the exact aquifer consumer against the CPU density carrier. */
    private static void executeAquiferScalarTrace(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader exactShader,
            DensityProbe probe, double density, int defaultStateId, int airStateId,
            int invalidStateId, int batchSize,
            WorldgenShaderCompiler.Shader exactBarrierShader, String exactBarrierFunction) {
        if (exactShader == null) {
            throw new IllegalStateException("Aquifer scalar trace requires the captured exact shader");
        }
        String aquiferFunction = aquiferFunctionName(exactShader.source());
        boolean externalBarrier = useExternalAquiferBarrier(exactAquiferBarrierInputEnabled(), exactBarrierFunction);
        int[] barrierWords = null;
        if (externalBarrier) {
            if (exactBarrierShader == null || exactBarrierFunction == null || exactBarrierFunction.isBlank()) {
                throw new IllegalStateException(
                        "External aquifer barrier scalar trace requires exact barrier shader metadata");
            }
            VulkanWorldgenExecutor.RawResult barrierResult = executeStagedDensityRoot(
                    executor, exactBarrierShader, exactBarrierFunction,
                    coordinateWords(new int[]{probe.x(), probe.y(), probe.z()}),
                    defaultStateId, airStateId, invalidStateId, batchSize,
                    new HashSet<>(), false, null);
            if (barrierResult.elementCount() != 1
                    || barrierResult.outputWordsPerElement() != DENSITY_STAGE_VALUE_WORDS) {
                throw new IllegalStateException("GPU aquifer scalar barrier carrier has incompatible geometry");
            }
            barrierWords = barrierResult.outputWords();
        }
        WorldgenShaderCompiler.Shader aquiferShader = stageShader(exactShader,
                aquiferStageSource(exactShader.source(), aquiferFunction, externalBarrier),
                "density-aquifer-scalar-trace");
        writeLiveStageDiagnostic("density-aquifer-scalar-trace-live.comp", aquiferShader.source());
        SpirvNumericContract.require(aquiferShader.source(), aquiferShader.profile());
        int[] densityWords = cpuDensityWords(new double[]{density});
        int inputWords = externalBarrier ? AQUIFER_STAGE_EXTERNAL_INPUT_WORDS : AQUIFER_STAGE_INPUT_WORDS;
        int[] input = new int[inputWords];
        input[0] = probe.x();
        input[1] = probe.y();
        input[2] = probe.z();
        input[3] = densityWords[0];
        input[4] = densityWords[1];
        if (externalBarrier) {
            input[5] = barrierWords[0];
            input[6] = barrierWords[1];
        }
        VulkanWorldgenExecutor.RawResult result = executor.executeRawBatched(
                new VulkanWorldgenExecutor.RawRequest(aquiferShader, input,
                        inputWords, 4, 1, defaultStateId, airStateId, invalidStateId)
                        .withDontInlineFunctions(aquiferStageDontInlineEnabled(), aquiferStageDontInlinePrefix())
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        int[] words = result.outputWords();
        if (words.length != 4) {
            throw new IllegalStateException("GPU aquifer scalar trace returned " + words.length
                    + " words");
        }
        throw new IllegalStateException("GPU aquifer scalar trace density=" + density
                + " words=" + Arrays.toString(words));
    }

    /**
     * Sweeps the exact aquifer ABI around the CPU pressure thresholds at one
     * point.  This is deliberately a repeated-point diagnostic: it avoids the
     * full density planner while exercising density sign, exact-zero and each
     * finite pressure boundary through the same device-produced barrier row.
     */
    private static void executeAquiferThresholdSweep(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader exactShader,
            DensityProbe probe, double actualDensity, int defaultStateId, int airStateId,
            int invalidStateId, int batchSize,
            WorldgenShaderCompiler.Shader exactBarrierShader, String exactBarrierFunction,
            MinecraftCpuCandidate cpuCandidate, MinecraftCpuCandidate.Inputs inputs,
            BlockStateTable table) {
        if (!Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.exactAquiferStage", "false"))) {
            throw new IllegalArgumentException(
                    "GPU aquifer threshold sweep requires exactAquiferStage=true");
        }
        if (!exactAquiferBarrierInputEnabled()) {
            throw new IllegalArgumentException(
                    "GPU aquifer threshold sweep requires exactAquiferBarrierInput=true");
        }
        if (exactShader == null || exactBarrierShader == null
                || exactBarrierFunction == null || exactBarrierFunction.isBlank()) {
            throw new IllegalStateException("GPU aquifer threshold sweep requires captured exact barrier metadata");
        }
        AquiferEvaluator.DecisionTrace trace = cpuCandidate
                .capturedMaterialDiagnostic(inputs, probe.x(), probe.y(), probe.z())
                .aquiferTrace();
        if (trace == null) {
            throw new IllegalStateException("GPU aquifer threshold sweep requires an enabled captured aquifer trace");
        }

        LinkedHashMap<Long, Double> densityValues = new LinkedHashMap<>();
        addAquiferThresholdDensity(densityValues, actualDensity);
        addAquiferThresholdDensity(densityValues, 0.0D);
        double[] pressureThresholds = {
                trace.pressure12(), trace.pressure13(), trace.pressure23()
        };
        for (double pressure : pressureThresholds) {
            if (!Double.isFinite(pressure)) continue;
            double threshold = -pressure;
            addAquiferThresholdDensity(densityValues, Math.nextDown(threshold));
            addAquiferThresholdDensity(densityValues, threshold);
            addAquiferThresholdDensity(densityValues, Math.nextUp(threshold));
        }
        if (densityValues.size() < 3) {
            addAquiferThresholdDensity(densityValues, -1.0D);
            addAquiferThresholdDensity(densityValues, 1.0D);
        }

        int elementCount = densityValues.size();
        double[] densities = new double[elementCount];
        int[] coordinates = new int[Math.multiplyExact(elementCount, 3)];
        int cursor = 0;
        for (double value : densityValues.values()) {
            densities[cursor] = value;
            int coordinate = cursor * 3;
            coordinates[coordinate] = probe.x();
            coordinates[coordinate + 1] = probe.y();
            coordinates[coordinate + 2] = probe.z();
            cursor++;
        }

        VulkanWorldgenExecutor.RawResult barrierResult = executeStagedDensityRoot(
                executor, exactBarrierShader, exactBarrierFunction,
                coordinateWords(new int[]{probe.x(), probe.y(), probe.z()}),
                defaultStateId, airStateId, invalidStateId, 1,
                new HashSet<>(), false, null);
        if (barrierResult.elementCount() != 1
                || barrierResult.outputWordsPerElement() != DENSITY_STAGE_VALUE_WORDS) {
            throw new IllegalStateException("GPU aquifer threshold barrier carrier has incompatible geometry");
        }
        int[] barrierWords = barrierResult.outputWords();
        requireFiniteDensityWords(barrierWords, new int[]{probe.x(), probe.y(), probe.z()});

        String aquiferFunction = aquiferFunctionName(exactShader.source());
        WorldgenShaderCompiler.Shader aquiferShader = stageShader(exactShader,
                aquiferStageSource(exactShader.source(), aquiferFunction, true),
                "density-aquifer-threshold-sweep");
        writeLiveStageDiagnostic("density-aquifer-threshold-sweep-live.comp", aquiferShader.source());
        SpirvNumericContract.require(aquiferShader.source(), aquiferShader.profile());
        int[] densityWords = cpuDensityWords(densities);
        int[] input = new int[Math.multiplyExact(elementCount, AQUIFER_STAGE_EXTERNAL_INPUT_WORDS)];
        for (int index = 0; index < elementCount; index++) {
            int target = index * AQUIFER_STAGE_EXTERNAL_INPUT_WORDS;
            int coordinate = index * 3;
            input[target] = coordinates[coordinate];
            input[target + 1] = coordinates[coordinate + 1];
            input[target + 2] = coordinates[coordinate + 2];
            int density = index * DENSITY_STAGE_VALUE_WORDS;
            input[target + 3] = densityWords[density];
            input[target + 4] = densityWords[density + 1];
            input[target + 5] = barrierWords[0];
            input[target + 6] = barrierWords[1];
        }
        VulkanWorldgenExecutor.RawResult result = executor.executeRawBatched(
                new VulkanWorldgenExecutor.RawRequest(aquiferShader, input,
                        AQUIFER_STAGE_EXTERNAL_INPUT_WORDS, 4, elementCount,
                        defaultStateId, airStateId, invalidStateId)
                        .withDontInlineFunctions(aquiferStageDontInlineEnabled(), aquiferStageDontInlinePrefix())
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        int[] words = result.outputWords();
        if (Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugAquiferRationalComparator", "false"))) {
            StringBuilder diagnostic = new StringBuilder();
            for (int index = 0; index < elementCount; index++) {
                int output = index * 4;
                diagnostic.append(" density=").append(densities[index])
                        .append(" actual=")
                        .append(Arrays.toString(Arrays.copyOfRange(words, output, output + 4)));
            }
            throw new IllegalStateException("GPU aquifer rational comparator debug point="
                    + probe.x() + "," + probe.y() + "," + probe.z()
                    + " barrier=" + carrierValue(barrierWords, 0) + diagnostic);
        }
        int mismatches = 0;
        StringBuilder mismatch = new StringBuilder();
        for (int index = 0; index < elementCount; index++) {
            double density = densities[index];
            AquiferEvaluator.Result expected = cpuCandidate.capturedAquiferAtDensity(
                    inputs, probe.x(), probe.y(), probe.z(), density);
            int expectedCandidate = expected.aquiferCandidate() ? 1 : 0;
            int expectedState = expected.aquiferCandidate()
                    ? table.id(expected.state()) : airStateId;
            if (expectedState < 0) {
                throw new IllegalStateException("CPU aquifer threshold state is absent from the captured registry: "
                        + expected.state());
            }
            int expectedNoAquiferDefault = expected.noAquiferUsesDefault() ? 1 : 0;
            int expectedSchedule = expected.scheduleFluidUpdate() ? 1 : 0;
            int output = index * 4;
            boolean same = words[output] == expectedCandidate
                    && words[output + 1] == expectedState
                    && words[output + 2] == expectedNoAquiferDefault
                    && words[output + 3] == expectedSchedule;
            if (!same) {
                mismatches++;
                if (mismatch.length() < 4096) {
                    AquiferEvaluator.DecisionTrace customTrace = cpuCandidate.capturedAquiferTraceAtDensity(
                            inputs, probe.x(), probe.y(), probe.z(), density);
                    mismatch.append(" density=").append(density)
                            .append(" bits=0x")
                            .append(String.format(Locale.ROOT, "%016x", Double.doubleToRawLongBits(density)))
                            .append(" cpu=").append(expected)
                            .append(" trace=").append(customTrace)
                            .append(" expected=")
                            .append(Arrays.toString(new int[]{expectedCandidate, expectedState,
                                    expectedNoAquiferDefault, expectedSchedule}))
                            .append(" actual=")
                            .append(Arrays.toString(Arrays.copyOfRange(words, output, output + 4)));
                }
            }
        }
        if (mismatches != 0) {
            throw new IllegalStateException("GPU aquifer threshold sweep mismatched " + mismatches
                    + " of " + elementCount + " cases at point=" + probe.x() + ","
                    + probe.y() + "," + probe.z() + mismatch);
        }
        throw new IllegalStateException("GPU aquifer threshold sweep passed point="
                + probe.x() + "," + probe.y() + "," + probe.z()
                + " cases=" + elementCount + " barrier=" + carrierValue(barrierWords, 0)
                + " densities=" + Arrays.toString(densities));
    }

    private static void addAquiferThresholdDensity(LinkedHashMap<Long, Double> values, double density) {
        if (Double.isFinite(density)) {
            values.putIfAbsent(Double.doubleToRawLongBits(density), density);
        }
    }

    /**
     * Runs the post-density material pipeline as one device-resident row
     * chain.  The row deliberately keeps the ABI boring: coordinates,
     * density, aquifer result, ore result, and the final state/mark.  Every
     * stage copies the row before changing its owned fields, so the Java side
     * only supplies the initial density carriers and reads back the final
     * material row.
     */
    private static DeviceMaterialResult executeMaterialStagesDeviceResident(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            int[] coordinates, int[] densityWords, int defaultStateId, int airStateId,
            int invalidStateId, int[] allowedStateIds, int batchSize,
            DeviceCapabilities device, String routeLabel, StructureBlendSnapshot structureBlend,
            double[] expectedDensityValues,
            WorldgenShaderCompiler.Shader exactBarrierShader, String exactBarrierFunction) {
        int elementCount = coordinates.length / 3;
        boolean externalBarrier = useExternalAquiferBarrier(exactAquiferBarrierInputEnabled(), exactBarrierFunction);
        if (externalBarrier && (exactBarrierShader == null || exactBarrierFunction == null
                || exactBarrierFunction.isBlank())) {
            throw new IllegalStateException(
                    "External aquifer barrier input requires exact barrier shader metadata");
        }
        int rowWords = externalBarrier ? MATERIAL_RESIDENT_EXTERNAL_WORDS : MATERIAL_RESIDENT_WORDS;
        if (densityWords.length != Math.multiplyExact(elementCount, DENSITY_STAGE_VALUE_WORDS)) {
            throw new IllegalArgumentException("Device-resident material density geometry does not match coordinates");
        }
        int[] input = new int[Math.multiplyExact(elementCount, rowWords)];
        for (int index = 0; index < elementCount; index++) {
            int coordinate = index * 3;
            int target = index * rowWords;
            input[target] = coordinates[coordinate];
            input[target + 1] = coordinates[coordinate + 1];
            input[target + 2] = coordinates[coordinate + 2];
            int density = index * DENSITY_STAGE_VALUE_WORDS;
            input[target + MATERIAL_RESIDENT_DENSITY_WORD] = densityWords[density];
            input[target + MATERIAL_RESIDENT_DENSITY_WORD + 1] = densityWords[density + 1];
        }

        boolean pipelineOptimizationDisabled = densityPipelineOptimizationDisabled();
        boolean dontInline = materialStageDontInlineEnabled();
        String dontInlinePrefix = materialStageDontInlinePrefix();
        List<VulkanWorldgenExecutor.RawStage> stages = new ArrayList<>();
        boolean stageDebugEnabled = Boolean.parseBoolean(
                System.getProperty("tellurium.gpuCandidate.debugStages", "false"));

        if (externalBarrier) {
            WorldgenShaderCompiler.Shader barrierShader = stageShader(exactBarrierShader,
                    barrierResidentStageSource(exactBarrierShader.source(), exactBarrierFunction, rowWords),
                    "density-aquifer-barrier-resident");
            SpirvNumericContract.require(barrierShader.source(), barrierShader.profile());
            stages.add(new VulkanWorldgenExecutor.RawStage(barrierShader, rowWords, rowWords,
                    pipelineOptimizationDisabled, dontInline, dontInlinePrefix));
            writeLiveStageDiagnostic("density-aquifer-barrier-resident-live.comp", barrierShader.source());
        }

        if (BeardifierEmitter.hasData(structureBlend)) {
            WorldgenShaderCompiler.Shader emitted = new WorldgenShaderCompiler().emitBeardifierStage(
                    structureBlend, completeShader.profile(), completeShader.localSize(), true);
            var structureKernel = BeardifierKernelInputStage.bind(
                    beardifierResidentStageSource(emitted.source(), rowWords), rowWords);
            WorldgenShaderCompiler.Shader stage = stageShader(completeShader,
                    structureKernel.source(),
                    "density-beardifier-resident");
            SpirvNumericContract.require(stage.source(), stage.profile());
            stages.add(new VulkanWorldgenExecutor.RawStage(stage, rowWords,
                    rowWords, pipelineOptimizationDisabled, false, "", structureKernel.kernelWords()));
            writeLiveStageDiagnostic("density-beardifier-resident-live.comp", stage.source());
        }

        WorldgenShaderCompiler.Shader aquiferCompleteShader = completeShader;
        if (Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.exactAquiferStage", "false"))) {
            WorldgenShaderCompiler.Shader exactAquiferShader = EXACT_NORMAL_NOISE_SHADER.get();
            if (exactAquiferShader == null) {
                throw new IllegalStateException("Exact aquifer stage requested before exact shader capture");
            }
            if (!Boolean.parseBoolean(System.getProperty(
                    "tellurium.gpuCandidate.exactAquiferStageNative", "false"))
                    || completeShader.profile() != NumericProfile.GPU_NATIVE_DRAFT) {
                aquiferCompleteShader = exactAquiferShader;
            }
        }
        String aquiferFunction = aquiferFunctionName(aquiferCompleteShader.source());
        WorldgenShaderCompiler.Shader aquiferShader = stageShader(aquiferCompleteShader,
                aquiferResidentStageSource(aquiferCompleteShader.source(), aquiferFunction,
                        rowWords, externalBarrier),
                "density-aquifer-resident");
        SpirvNumericContract.require(aquiferShader.source(), aquiferShader.profile());
        stages.add(new VulkanWorldgenExecutor.RawStage(aquiferShader, rowWords,
                rowWords, pipelineOptimizationDisabled,
                aquiferStageDontInlineEnabled(), aquiferStageDontInlinePrefix()));
        writeLiveStageDiagnostic("density-aquifer-resident-live.comp", aquiferShader.source());

        String oreFunction = lastFunctionNameOrNull(completeShader.source(),
                "(?m)^uvec2 (wg_ore_[A-Za-z0-9_]+)\\(");
        if (oreFunction != null) {
            WorldgenShaderCompiler.Shader oreShader = stageShader(completeShader,
                    oreResidentStageSource(completeShader.source(), oreFunction, rowWords),
                    "density-ore-resident");
            SpirvNumericContract.require(oreShader.source(), oreShader.profile());
            stages.add(new VulkanWorldgenExecutor.RawStage(oreShader, rowWords,
                    rowWords, pipelineOptimizationDisabled, dontInline, dontInlinePrefix));
            writeLiveStageDiagnostic("density-ore-resident-live.comp", oreShader.source());
        }

        WorldgenShaderCompiler.Shader materialShader = stageShader(completeShader,
                materialResidentStageSource(completeShader.source(), rowWords),
                "density-material-resident");
        SpirvNumericContract.require(materialShader.source(), materialShader.profile());
        stages.add(new VulkanWorldgenExecutor.RawStage(materialShader, rowWords,
                rowWords, pipelineOptimizationDisabled, dontInline, dontInlinePrefix));
        writeLiveStageDiagnostic("density-material-resident-live.comp", materialShader.source());

        VulkanWorldgenExecutor.RawResult raw;
        try {
            raw = executor.executeRawChainBatched(input, rowWords, elementCount,
                    defaultStateId, airStateId, invalidStateId, stages, batchSize);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("GPU device-resident material chain failed for "
                    + elementCount + " blocks on " + routeLabel, failure);
        }
        requireSameDevice(device, raw.device(), routeLabel + " device-resident material chain");
        int[] words = raw.outputWords();
        int[] stateIds = new int[elementCount];
        boolean[] fluidMarks = new boolean[elementCount];
        for (int index = 0; index < elementCount; index++) {
            int row = index * rowWords;
            int stateWord = externalBarrier ? MATERIAL_RESIDENT_EXTERNAL_STATE_WORD
                    : MATERIAL_RESIDENT_STATE_WORD;
            int fluidWord = externalBarrier ? MATERIAL_RESIDENT_EXTERNAL_FLUID_WORD
                    : MATERIAL_RESIDENT_FLUID_WORD;
            stateIds[index] = words[row + stateWord];
            fluidMarks[index] = decodeFluidMark(words[row + fluidWord],
                    "device-resident material") && stateIds[index] != airStateId;
            if (!contains(allowedStateIds, stateIds[index])) {
                throw new IllegalStateException("Device-resident material chain returned state ID outside the dispatch ABI: "
                        + stateIds[index]);
            }
        }
        if (expectedDensityValues != null) {
            int[] finalDensityWords = new int[Math.multiplyExact(elementCount, DENSITY_STAGE_VALUE_WORDS)];
            for (int index = 0; index < elementCount; index++) {
                int row = index * rowWords;
                int density = index * DENSITY_STAGE_VALUE_WORDS;
                finalDensityWords[density] = words[row + MATERIAL_RESIDENT_DENSITY_WORD];
                finalDensityWords[density + 1] = words[row + MATERIAL_RESIDENT_DENSITY_WORD + 1];
            }
            requireDensityParity(expectedDensityValues, finalDensityWords, coordinates);
        }
        stageDebug(stageDebugEnabled, "density-material-device-resident-complete elements=" + elementCount
                + " stages=" + stages.size() + " shader=" + raw.shaderHash());
        return new DeviceMaterialResult(stateIds, fluidMarks, raw.device(), raw.shaderHash(),
                raw.spirvHash(), elementCount);
    }

    /** Adds the outer Beardifier on the device before aquifer/material consumers run. */
    private static int[] executeBeardifierCombineStage(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            StructureBlendSnapshot structureBlend, int[] coordinates, int[] densityWords,
            int batchSize, DeviceCapabilities device) {
        boolean stageDebugEnabled = Boolean.parseBoolean(
                System.getProperty("tellurium.gpuCandidate.debugStages", "false"));
        if (!BeardifierEmitter.hasData(structureBlend)) {
            stageDebug(stageDebugEnabled, "density-beardifier-skipped-empty");
            return densityWords;
        }
        int elementCount = coordinates.length / 3;
        WorldgenShaderCompiler.Shader beardifierShader = new WorldgenShaderCompiler()
                .emitBeardifierStage(structureBlend, completeShader.profile(), completeShader.localSize(), true);
        writeLiveStageDiagnostic("density-beardifier-live.comp", beardifierShader.source());
        SpirvNumericContract.require(beardifierShader.source(), beardifierShader.profile());
        int[] input = new int[Math.multiplyExact(elementCount, BEARDIFIER_STAGE_INPUT_WORDS)];
        for (int index = 0; index < elementCount; index++) {
            int coordinate = index * 3;
            int target = index * BEARDIFIER_STAGE_INPUT_WORDS;
            input[target] = coordinates[coordinate];
            input[target + 1] = coordinates[coordinate + 1];
            input[target + 2] = coordinates[coordinate + 2];
            int density = index * DENSITY_STAGE_VALUE_WORDS;
            input[target + 4] = densityWords[density];
            input[target + 5] = densityWords[density + 1];
        }
        VulkanWorldgenExecutor.RawResult result = executor.executeRawBatched(
                beardifierKernelStageRequest(new VulkanWorldgenExecutor.RawRequest(beardifierShader, input,
                        BEARDIFIER_STAGE_INPUT_WORDS, DENSITY_STAGE_VALUE_WORDS, elementCount)
                        .withDontInlineFunctions(false, "")
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled())), batchSize);
        requireSameDevice(device, result.device(), "Beardifier density-combine stage");
        stageDebug(stageDebugEnabled, "density-beardifier-complete elements=" + elementCount
                + " first=" + carrierValue(result.outputWords(), 0)
                + " shader=" + result.shaderHash());
        return result.outputWords();
    }

    /**
     * Executes the direct second final-density branch on block coordinates.
     * It has its own stage frontier because the interpolation split uses
     * lattice-corner coordinates while this branch is consumed at each block
     * coordinate.  In particular, direct FP64 divisions must use the same
     * multi-dispatch divider as the corner stages; the old one-module path
     * silently produced zero for this branch on the target driver.
     */
    private static VulkanWorldgenExecutor.RawResult executeStagedDirectDensityBranch(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            String root, int[] coordinates, int defaultStateId, int airStateId,
            int invalidStateId, int batchSize) {
        int probeElements = integerProperty("tellurium.gpuCandidate.directBranchProbeElements", -1);
        int elementCount = coordinates.length / 3;
        if (probeElements == 0 || probeElements > elementCount) {
            throw new IllegalArgumentException("GPU candidate directBranchProbeElements must be -1 or in [1, "
                    + elementCount + "]");
        }
            int[] diagnosticCoordinates = probeElements > 0 && probeElements < elementCount
                ? Arrays.copyOf(coordinates, probeElements * 3) : coordinates;
        String source = completeShader.source();
        List<String> directDivisions = reachableFunctions(Set.of(root),
                        functionCalls(source, parseFunctions(source)), Set.of()).stream()
                .filter(name -> isDensityFunction(name)
                        && isDirectFp64Division(source, name, densityChildren(source, name)))
                .sorted()
                .toList();
        if (directDivisions.size() == 1) {
            String divisionRoot = directDivisions.get(0);
            DirectIntegerRamp ramp = detectDirectIntegerRamp(source, divisionRoot);
            stageDebug(Boolean.parseBoolean(System.getProperty(
                    "tellurium.gpuCandidate.debugStages", "false")),
                    "density-direct-ramp-detect division=" + divisionRoot + " ramp=" + ramp);
            if (ramp != null) {
                return executeDirectIntegerRampBranch(executor, completeShader, root, ramp,
                        diagnosticCoordinates, defaultStateId, airStateId, invalidStateId, batchSize);
            }
            return executeDirectDivisionLookupBranch(executor, completeShader, root,
                    divisionRoot, diagnosticCoordinates, defaultStateId,
                    airStateId, invalidStateId, batchSize);
        }
        return executeStagedDensityRoot(executor, completeShader, root, coordinateWords(diagnosticCoordinates),
                defaultStateId, airStateId, invalidStateId, batchSize, new HashSet<>());
    }

    /**
     * Draft fast path for a branch with one direct FP64 division.  The
     * divider is evaluated once at the unique lattice corners, then the
     * captured branch graph runs unchanged at block coordinates while its
     * divider node reads those device-produced corner carriers.  This avoids
     * recursively replanning every interpolation ancestor.
     */
    private static VulkanWorldgenExecutor.RawResult executeDirectDivisionLookupBranch(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            String root, String divisionRoot, int[] coordinates, int defaultStateId,
            int airStateId, int invalidStateId, int batchSize) {
        DensityInterpolationGeometry geometry = densityInterpolationGeometryForBranch(
                completeShader.source(), root);
        DensityStageInputs stageInputs = buildDensityStageInputs(coordinates, geometry);
        int[] cornerCoordinates = stageInputs.uniqueCoordinates();
        List<DensityStagePlan> divisionPlans = findDensityStagePlans(
                completeShader.source(), List.of(divisionRoot));
        Map<String, VulkanWorldgenExecutor.RawResult> divisionResults = executeDensityPlanSet(
                executor, completeShader, divisionPlans, cornerCoordinates, Set.of(divisionRoot),
                Map.of(), defaultStateId, airStateId, invalidStateId, batchSize,
                "density-direct-divider root=" + divisionRoot);
        VulkanWorldgenExecutor.RawResult divisionResult = requireStageResult(divisionResults, divisionRoot);
        int blockCount = coordinates.length / 3;
        int inputWordsPerElement = Math.addExact(4, DENSITY_STAGE_CORNER_VALUE_WORDS);
        int[] blockInput = new int[Math.multiplyExact(blockCount, inputWordsPerElement)];
        int[] cornerIndices = stageInputs.cornerIndices();
        int[] divisionWords = divisionResult.outputWords();
        int cornerCount = cornerCoordinates.length / 4;
        if (divisionResult.elementCount() != cornerCount
                || divisionResult.outputWordsPerElement() != DENSITY_STAGE_VALUE_WORDS) {
            throw new IllegalStateException("Direct FP64 divider returned incompatible corner geometry");
        }
        stageDebug(Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugStages", "false")),
                "density-direct-divider-corners count=" + cornerCount
                        + " first=" + carrierValue(divisionWords, 0));
        for (int block = 0; block < blockCount; block++) {
            int target = block * inputWordsPerElement;
            int coordinate = block * 3;
            blockInput[target] = coordinates[coordinate];
            blockInput[target + 1] = coordinates[coordinate + 1];
            blockInput[target + 2] = coordinates[coordinate + 2];
            for (int corner = 0; corner < DENSITY_STAGE_CORNER_COUNT; corner++) {
                int source = cornerIndices[block * DENSITY_STAGE_CORNER_COUNT + corner]
                        * DENSITY_STAGE_VALUE_WORDS;
                int destination = target + 4 + corner * DENSITY_STAGE_VALUE_WORDS;
                blockInput[destination] = divisionWords[source];
                blockInput[destination + 1] = divisionWords[source + 1];
            }
        }
        int inputWords = inputWordsPerElement;
        String stageSource = directDivisionLookupStageSource(completeShader.source(), root,
                divisionRoot, inputWords, List.of(), geometry);
        boolean componentDiagnostic = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugDensityBranchComponents", "false"));
        List<String> debugFunctions = componentDiagnostic
                ? directBranchDiagnosticFunctions(completeShader.source(), root, divisionRoot) : List.of();
        if (componentDiagnostic) {
            stageSource = directDivisionLookupStageSource(completeShader.source(), root,
                    divisionRoot, inputWords, debugFunctions, geometry);
        }
        WorldgenShaderCompiler.Shader stageShader = stageShader(completeShader, stageSource,
                "density-direct-division-lookup-" + root);
        SpirvNumericContract.require(stageShader.source(), stageShader.profile());
        int outputWordsPerElement = componentDiagnostic
                ? Math.multiplyExact(debugFunctions.size(), DENSITY_STAGE_VALUE_WORDS)
                : DENSITY_STAGE_VALUE_WORDS;
        VulkanWorldgenExecutor.RawRequest branchRequest = new VulkanWorldgenExecutor.RawRequest(
                stageShader, blockInput, inputWords, outputWordsPerElement,
                blockCount, defaultStateId, airStateId, invalidStateId);
        branchRequest = directDensityStageRequest(branchRequest);
        VulkanWorldgenExecutor.RawResult result = executor.executeRawBatched(
                branchRequest.withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        if (result.elementCount() != blockCount
                || result.outputWordsPerElement() != outputWordsPerElement) {
            throw new IllegalStateException("Direct density lookup branch returned incompatible geometry: " + root);
        }
        if (componentDiagnostic) {
            int[] diagnosticWords = result.outputWords();
            StringBuilder values = new StringBuilder("density-direct-branch-components root=")
                    .append(root).append(" point=")
                    .append(coordinates[0]).append(",").append(coordinates[1]).append(",").append(coordinates[2]);
            for (int index = 0; index < debugFunctions.size(); index++) {
                values.append(' ').append(debugFunctions.get(index)).append('=')
                        .append(carrierValue(diagnosticWords, index * DENSITY_STAGE_VALUE_WORDS));
            }
            stageDebug(true, values.toString());
            throw new IllegalStateException("GPU direct density branch component diagnostic complete");
        }
        stageDebug(Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugStages", "false")),
                "density-direct-branch-result root=" + root
                        + " first=" + carrierValue(result.outputWords(), 0));
        requireSameDevice(divisionResult.device(), result.device(), "direct density divider branch");
        return result;
    }

    /**
     * Executes the captured vanilla depth selector after reducing its exact
     * integer ramp to an integer clamp.  The source graph is still evaluated
     * by Vulkan; only the selector's mathematically redundant divide/multiply
     * sequence is replaced.  This avoids a known NVIDIA compiler crash in the
     * compact direct-branch module while preserving the general divider path
     * for captures that do not match this shape.
     */
    private static VulkanWorldgenExecutor.RawResult executeDirectIntegerRampBranch(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            String root, DirectIntegerRamp ramp, int[] coordinates, int defaultStateId,
            int airStateId, int invalidStateId, int batchSize) {
        WorldgenShaderCompiler.Shader rampShader = directIntegerRampShader(completeShader, ramp);
        // The exact Overworld ramp branch commonly selects a captured normal-noise
        // leaf.  Keeping that leaf as one graph-stage shader makes the NVIDIA
        // driver spend minutes in vkCreateComputePipelines.  Split only this
        // branch: enabling the global property for the whole route also expands
        // the large fallback branch into dozens of extra normal-noise stages.
        String splitProperty = "tellurium.gpuCandidate.splitNormalNoise";
        String previousSplit = System.getProperty(splitProperty);
        if (previousSplit == null || previousSplit.isBlank()) {
            System.setProperty(splitProperty, "true");
        }
        VulkanWorldgenExecutor.RawResult result;
        try {
            result = executeStagedDensityRoot(executor, rampShader, root,
                    coordinateWords(coordinates), defaultStateId, airStateId, invalidStateId,
                    batchSize, new HashSet<>());
        } finally {
            if (previousSplit == null) System.clearProperty(splitProperty);
            else System.setProperty(splitProperty, previousSplit);
        }
        int elementCount = coordinates.length / 3;
        stageDebug(Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugStages", "false")),
                "density-direct-integer-ramp root=" + root + " selector=" + ramp.selector()
                        + " bounds=" + ramp.lower() + "," + ramp.upper()
                        + " elements=" + elementCount + " first="
                        + carrierValue(result.outputWords(), 0));
        return result;
    }

    private static WorldgenShaderCompiler.Shader directIntegerRampShader(
            WorldgenShaderCompiler.Shader completeShader, DirectIntegerRamp ramp) {
        Map<String, GlslFunction> functions = parseFunctions(completeShader.source());
        GlslFunction selector = functions.get(ramp.selector());
        if (selector == null) throw new IllegalArgumentException("Direct integer-ramp selector is missing");
        String source = replaceFunction(completeShader.source(), ramp.selector(),
                directIntegerRampSelectorFunction(ramp, selector.returnType()));
        return new WorldgenShaderCompiler.Shader(source, completeShader.programHash(),
                completeShader.profile(), completeShader.localSize(), completeShader.blendedNoiseFunctions(),
                completeShader.nodeFunctions());
    }

    private static String directIntegerRampSelectorFunction(DirectIntegerRamp ramp, String returnType) {
        return returnType + " " + ramp.selector() + "(ivec3 point) {\n"
                + "    if (point.y <= " + ramp.lower() + ") return "
                + carrierLiteral(ramp.lowerBits()) + ";\n"
                + "    if (point.y >= " + ramp.upper() + ") return "
                + carrierLiteral(ramp.upperBits()) + ";\n"
                + "    return wg_fp64_from_int(point.y);\n"
                + "}\n";
    }

    private static String directIntegerRampStageSource(String completeSource, String root,
                                                       DirectIntegerRamp ramp) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        GlslFunction rootFunction = functions.get(root);
        GlslFunction selectorFunction = functions.get(ramp.selector());
        if (rootFunction == null || selectorFunction == null) {
            throw new IllegalArgumentException("Direct integer-ramp source lost root or selector");
        }
        String source = sourcePrefix(completeSource)
                .replace("uint outputStateIds[]", "uint outputBits[]")
                .replace("uint base = index * 4u;", "uint base = index * 4u;");
        source = replaceCandidateCellFraction(source);
        source = replaceFunction(source, ramp.selector(),
                directIntegerRampSelectorFunction(ramp, selectorFunction.returnType()));
        String value = densityValueExpression(rootFunction, root);
        source = compactSource(source, Set.of("wg_point", root, "wg_fp64_qnan", "wg_fp64_finite"));
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 value = %s;
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
                """.formatted(value);
    }

    /** Finds the vanilla depth pattern: add(low, mul(clamp(div(y-low, range)), range)). */
    private static DirectIntegerRamp detectDirectIntegerRamp(String source, String divisionRoot) {
        Map<String, GlslFunction> functions = parseFunctions(source);
        Map<String, Set<String>> calls = functionCalls(source, functions);
        Map<String, String> bodies = new HashMap<>();
        for (GlslFunction function : functions.values()) {
            if (isDensityFunction(function.name())) {
                String text = functionText(source, function);
                bodies.put(function.name(), text.substring(text.indexOf('{') + 1, text.length() - 1));
            }
        }
        GlslFunction division = functions.get(divisionRoot);
        if (division == null || !bodies.getOrDefault(divisionRoot, "").contains("wg_fp64_div(")) return null;
        String numerator = uniqueDensityChild(calls, divisionRoot, name ->
                bodies.getOrDefault(name, "").contains("wg_fp64_sub("));
        String denominator = uniqueDensityChild(calls, divisionRoot, name ->
                !name.equals(numerator) && constantCarrierBits(bodies, name) != null);
        stageDebug(Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugStages", "false")),
                "density-direct-ramp-shape divisionCalls=" + calls.getOrDefault(divisionRoot, Set.of())
                        + " numerator=" + numerator + " denominator=" + denominator);
        if (numerator == null || denominator == null) return null;
        Set<String> numeratorCalls = calls.getOrDefault(numerator, Set.of());
        String lowerFunction = uniqueDensityChild(numeratorCalls, name ->
                !name.equals(divisionRoot) && constantCarrierBits(bodies, name) != null);
        Long lowerBits = lowerFunction == null ? null : constantCarrierBits(bodies, lowerFunction);
        Long denominatorBits = constantCarrierBits(bodies, denominator);
        stageDebug(Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugStages", "false")),
                "density-direct-ramp-shape numeratorCalls=" + numeratorCalls
                        + " lowerFunction=" + lowerFunction + " lowerBits=" + lowerBits
                        + " denominatorBits=" + denominatorBits);
        if (lowerBits == null || denominatorBits == null) return null;
        double lowerValue = Double.longBitsToDouble(lowerBits);
        double rangeValue = Double.longBitsToDouble(denominatorBits);
        if (!isExactInt(lowerValue) || !isExactInt(rangeValue) || rangeValue <= 0.0) return null;

        String max = uniqueFunction(functions, name ->
                bodies.getOrDefault(name, "").contains("wg_fp64_max(")
                        && calls.getOrDefault(name, Set.of()).contains(divisionRoot));
        stageDebug(Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugStages", "false")),
                "density-direct-ramp-shape max=" + max + " lower=" + lowerValue + " range=" + rangeValue);
        if (max == null || !hasConstantChild(bodies, calls, max, 0.0)) return null;
        String min = uniqueFunction(functions, name ->
                bodies.getOrDefault(name, "").contains("wg_fp64_min(")
                        && calls.getOrDefault(name, Set.of()).contains(max));
        stageDebug(Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugStages", "false")),
                "density-direct-ramp-shape min=" + min);
        if (min == null || !hasConstantChild(bodies, calls, min, 1.0)) return null;
        String multiply = uniqueFunction(functions, name ->
                bodies.getOrDefault(name, "").contains("wg_fp64_mul(")
                        && calls.getOrDefault(name, Set.of()).contains(min));
        stageDebug(Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugStages", "false")),
                "density-direct-ramp-shape multiply=" + multiply);
        if (multiply == null || !hasConstantChild(bodies, calls, multiply, rangeValue)) return null;
        String selector = uniqueFunction(functions, name ->
                bodies.getOrDefault(name, "").contains("wg_fp64_add(")
                        && calls.getOrDefault(name, Set.of()).contains(multiply));
        stageDebug(Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugStages", "false")),
                "density-direct-ramp-shape selector=" + selector);
        if (selector == null || !hasConstantChild(bodies, calls, selector, lowerValue)) return null;

        double upperValue = lowerValue + rangeValue;
        if (!isExactInt(upperValue) || lowerValue < Integer.MIN_VALUE || lowerValue > Integer.MAX_VALUE
                || upperValue < Integer.MIN_VALUE || upperValue > Integer.MAX_VALUE) return null;
        long upperBits = Double.doubleToRawLongBits(upperValue);
        return new DirectIntegerRamp(selector, (int) lowerValue, (int) upperValue,
                lowerBits, upperBits);
    }

    private static String uniqueDensityChild(Map<String, Set<String>> calls, String parent,
                                             java.util.function.Predicate<String> predicate) {
        return uniqueDensityChild(calls.getOrDefault(parent, Set.of()), predicate);
    }

    private static String uniqueDensityChild(Set<String> candidates,
                                             java.util.function.Predicate<String> predicate) {
        String found = null;
        for (String candidate : candidates) {
            if (!isDensityFunction(candidate) || !predicate.test(candidate)) continue;
            if (found != null) return null;
            found = candidate;
        }
        return found;
    }

    private static String uniqueFunction(Map<String, GlslFunction> functions,
                                        java.util.function.Predicate<String> predicate) {
        String found = null;
        for (String name : functions.keySet()) {
            if (!isDensityFunction(name) || !predicate.test(name)) continue;
            if (found != null) return null;
            found = name;
        }
        return found;
    }

    private static boolean hasConstantChild(Map<String, String> bodies, Map<String, Set<String>> calls,
                                            String parent, double expected) {
        int matches = 0;
        for (String child : calls.getOrDefault(parent, Set.of())) {
            if (!isDensityFunction(child)) continue;
            Long bits = constantCarrierBits(bodies, child);
            if (bits != null && Double.doubleToRawLongBits(Double.longBitsToDouble(bits))
                    == Double.doubleToRawLongBits(expected)) matches++;
        }
        return matches == 1;
    }

    private static Long constantCarrierBits(String source, String function) {
        Map<String, GlslFunction> functions = parseFunctions(source);
        GlslFunction parsed = functions.get(function);
        if (parsed == null) return null;
        String text = functionText(source, parsed);
        return constantCarrierBits(Map.of(function,
                text.substring(text.indexOf('{') + 1, text.length() - 1)), function);
    }

    private static Long constantCarrierBits(Map<String, String> bodies, String function) {
        return constantCarrierBits(bodies, function, new HashSet<>());
    }

    private static Long constantCarrierBits(Map<String, String> bodies, String function,
                                            Set<String> active) {
        if (!active.add(function)) return null;
        try {
            String body = bodies.getOrDefault(function, "");
            Long literal = constantCarrierLiteralBits(body);
            if (literal != null) return literal;
            Matcher operation = Pattern.compile(
                    "(?s)\\breturn\\s+wg_fp64_(add|sub|mul|min|max)\\(\\s*"
                            + "(wg_(?:node|spline)_[0-9]+)\\(point\\)\\s*,\\s*"
                            + "(wg_(?:node|spline)_[0-9]+)\\(point\\)\\s*\\)\\s*;")
                    .matcher(body);
            if (!operation.find()) return null;
            Long leftBits = constantCarrierBits(bodies, operation.group(2), active);
            Long rightBits = constantCarrierBits(bodies, operation.group(3), active);
            if (leftBits == null || rightBits == null) return null;
            double left = Double.longBitsToDouble(leftBits);
            double right = Double.longBitsToDouble(rightBits);
            double result = switch (operation.group(1)) {
                case "add" -> left + right;
                case "sub" -> left - right;
                case "mul" -> left * right;
                case "min" -> Math.min(left, right);
                case "max" -> Math.max(left, right);
                default -> throw new IllegalStateException("Unexpected constant FP64 operation");
            };
            return Double.doubleToRawLongBits(result);
        } finally {
            active.remove(function);
        }
    }

    private static Long constantCarrierLiteralBits(String body) {
        Matcher matcher = Pattern.compile(
                "(?s)\\breturn\\s+uvec2\\(\\s*(0x[0-9a-fA-F]+|[0-9]+)u\\s*,\\s*"
                        + "(0x[0-9a-fA-F]+|[0-9]+)u\\s*\\)\\s*;")
                .matcher(body);
        if (!matcher.find()) return null;
        long low = parseUnsignedWord(matcher.group(1));
        long high = parseUnsignedWord(matcher.group(2));
        return (high << 32) | low;
    }

    private static long parseUnsignedWord(String text) {
        String digits = text.startsWith("0x") || text.startsWith("0X") ? text.substring(2) : text;
        return Long.parseUnsignedLong(digits, text.startsWith("0x") || text.startsWith("0X") ? 16 : 10)
                & 0xffff_ffffL;
    }

    private static boolean isExactInt(double value) {
        return Double.isFinite(value) && value == Math.rint(value)
                && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
    }

    private static String carrierLiteral(long bits) {
        return "uvec2(0x" + Long.toHexString(bits & 0xffff_ffffL) + "u, 0x"
                + Long.toHexString((bits >>> 32) & 0xffff_ffffL) + "u)";
    }

    static VulkanWorldgenExecutor.RawRequest directDensityStageRequest(
            VulkanWorldgenExecutor.RawRequest request) {
        if (hasCapturedBeardifierHelper(request.shader().source())) {
            return beardifierKernelStageRequest(request);
        }
        if (request.shader().source().contains("uvec2 wg_end_island_")) {
            return densityStageRequest(request);
        }
        String configured = System.getProperty("tellurium.gpuCandidate.directBranchDontInlinePrefix", "").trim();
        String prefix = configured.isEmpty()
                ? (request.shader().profile() == NumericProfile.GPU_NATIVE_DRAFT
                        ? NATIVE_DRAFT_DIRECT_DONT_INLINE_PREFIX : DIRECT_DENSITY_DONT_INLINE_PREFIX)
                : configured;
        if (prefix.equalsIgnoreCase("NONE")) return request.withDontInlineFunctions(false, "");
        for (String part : prefix.split(",", -1)) {
            if (part.isBlank()) {
                throw new IllegalArgumentException(
                        "GPU candidate directBranchDontInlinePrefix contains an empty function prefix");
            }
        }
        return request.withDontInlineFunctions(prefix);
    }

    private static List<String> directBranchDiagnosticFunctions(String completeSource, String root,
                                                                  String divisionRoot) {
        Map<String, GlslFunction> parsed = parseFunctions(completeSource);
        Set<String> reachable = reachableFunctions(Set.of(root), functionCalls(completeSource, parsed), Set.of());
        List<String> functions = reachable.stream()
                .filter(name -> isDensityFunction(name))
                .sorted()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (!functions.contains(root)) functions.add(0, root);
        if (!functions.contains(divisionRoot)) functions.add(divisionRoot);
        return List.copyOf(functions);
    }

    private static String directDivisionLookupStageSource(String completeSource, String root,
                                                           String divisionRoot, int inputWords) {
        return directDivisionLookupStageSource(completeSource, root, divisionRoot, inputWords, List.of(),
                DEFAULT_DENSITY_INTERPOLATION_GEOMETRY);
    }

    private static String directDivisionLookupStageSource(String completeSource, String root,
                                                           String divisionRoot, int inputWords,
                                                           List<String> diagnosticFunctions) {
        return directDivisionLookupStageSource(completeSource, root, divisionRoot, inputWords,
                diagnosticFunctions, DEFAULT_DENSITY_INTERPOLATION_GEOMETRY);
    }

    private static String directDivisionLookupStageSource(String completeSource, String root,
                                                           String divisionRoot, int inputWords,
                                                           List<String> diagnosticFunctions,
                                                           DensityInterpolationGeometry geometry) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        GlslFunction rootFunction = functions.get(root);
        GlslFunction divisionFunction = functions.get(divisionRoot);
        if (rootFunction == null || divisionFunction == null) {
            throw new IllegalArgumentException("Direct density lookup source lost root or divider");
        }
        String source = sourcePrefix(completeSource)
                .replace("uint outputStateIds[]", "uint outputBits[]")
                .replace("uint base = index * 4u;", "uint base = index * " + inputWords + "u;");
        source = replaceCandidateCellFraction(source);
        source = replaceFunction(source, divisionRoot,
                divisionFunction.returnType() + " " + divisionRoot
                        + "(ivec3 point) { return wg_stage_density_value(0u, point); }\n");
        int firstNode = source.indexOf("uvec2 wg_node_");
        if (firstNode < 0) throw new IllegalStateException("Direct density lookup source has no node functions");
        source = source.substring(0, firstNode)
                + densityStageCornerLookupFunction(inputWords, 4, geometry)
                + source.substring(firstNode);
        String value = densityValueExpression(rootFunction, root);
        Set<String> roots = new HashSet<>(Set.of("wg_point", root, "wg_fp64_qnan", "wg_fp64_finite"));
        roots.addAll(diagnosticFunctions);
        if (rootFunction.returnType().equals("uint")) roots.add("wg_fp64_from_fp32");
        source = compactSource(source, roots);
        if (!diagnosticFunctions.isEmpty()) {
            StringBuilder main = new StringBuilder("""
                    void main() {
                        uint index = gl_GlobalInvocationID.x;
                        if (index >= dispatch.count) return;
                        wg_failed = false;
                        ivec3 point = wg_point(index);
                        uint outputBase = index * %du;
                    """.formatted(Math.multiplyExact(diagnosticFunctions.size(), DENSITY_STAGE_VALUE_WORDS)));
            for (int index = 0; index < diagnosticFunctions.size(); index++) {
                String function = diagnosticFunctions.get(index);
                String expression = densityValueExpression(functions.get(function), function);
                int output = index * DENSITY_STAGE_VALUE_WORDS;
                main.append("    uvec2 debugValue").append(index).append(" = ").append(expression).append(";\n")
                        .append("    outputBits[outputBase + ").append(output).append("u] = debugValue")
                        .append(index).append(".x;\n")
                        .append("    outputBits[outputBase + ").append(output + 1).append("u] = debugValue")
                        .append(index).append(".y;\n");
            }
            main.append("}\n");
            return source + main;
        }
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 value = %s;
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
                """.formatted(inputWords, value);
    }

    /** Builds explicit child carriers from compiler-owned semantic nodes for a diagnostic only. */
    private static String cpuSeededDensityRootChildren(
            MinecraftCpuCandidate cpuCandidate, MinecraftCpuCandidate.Inputs inputs,
            WorldgenShaderCompiler.Shader shader, String root, int[] fullCoordinates,
            DensityProbe probe) {
        if (probe == null) {
            throw new IllegalArgumentException(
                    "GPU candidate debugDensityStageRootSeedCpu requires debugDensityProbePoint");
        }
        List<String> children = densityChildren(shader.source(), root);
        findCoordinateIndex(fullCoordinates, probe);
        StringBuilder encoded = new StringBuilder();
        for (String child : children) {
            ProgramNode node = shader.nodeFunctions().get(child);
            if (node == null) {
                throw new IllegalArgumentException("CPU semantic seed is unavailable for generated child "
                        + child + " of " + root);
            }
            double value = cpuCandidate.capturedNodeValueAt(inputs, node,
                    probe.x(), probe.y(), probe.z());
            if (encoded.length() > 0) encoded.append(';');
            encoded.append(child).append('=').append(Double.toString(value));
        }
        if (encoded.length() == 0) {
            throw new IllegalArgumentException("CPU semantic seed has no direct density children: " + root);
        }
        stageDebug(Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugStages", "false")),
                "density-stage-root-cpu-seed root=" + root + " values=" + encoded);
        return encoded.toString();
    }

    /** Resolve a compiler-owned semantic selector to this capture's transient GLSL name. */
    private static String resolveDensitySemanticSelector(
            WorldgenShaderCompiler.Shader shader, String selector, String propertyName) {
        if (!selector.startsWith("semantic:")) return selector;
        String fingerprint = selector.substring("semantic:".length()).toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (Map.Entry<String, ProgramNode> entry : shader.nodeFunctions().entrySet()) {
            if (WorldgenProgram.nodeFingerprint(entry.getValue()).equals(fingerprint)) {
                matches.add(entry.getKey());
            }
        }
        if (matches.size() != 1) {
            throw new IllegalArgumentException(propertyName + " semantic selector matched "
                    + matches.size() + " emitted nodes: " + selector + " matches=" + matches);
        }
        return matches.get(0);
    }

    /**
     * Executes a density root one direct child at a time, retaining only the
     * child result row needed by its parent.  The ordinary planner builds one
     * transitive plan and is intentionally capped; this diagnostic route keeps
     * the same GPU stage ABI while lowering the planner/compiler peak for a
     * very large semantic subtree.
     */
    private static VulkanWorldgenExecutor.RawResult executeChunkedDensityRoot(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            String root, int[] coordinateWords, int defaultStateId, int airStateId,
            int invalidStateId, int batchSize, Set<String> activeRoots) {
        return executeChunkedDensityRoot(executor, completeShader, root, coordinateWords,
                defaultStateId, airStateId, invalidStateId, batchSize, activeRoots,
                new ChunkedDensityExecution());
    }

    private static VulkanWorldgenExecutor.RawResult executeChunkedDensityRoot(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            String root, int[] coordinateWords, int defaultStateId, int airStateId,
            int invalidStateId, int batchSize, Set<String> activeRoots,
            ChunkedDensityExecution execution) {
        if (isDensityInterpolationFunction(completeShader.source(), root)) {
            return executeStagedDensityInterpolation(executor, completeShader, root, coordinateWords,
                    defaultStateId, airStateId, invalidStateId, batchSize, activeRoots, null, execution);
        }
        if (!activeRoots.add(root)) {
            throw new IllegalArgumentException("Chunked density graph is recursive at " + root);
        }
        execution.begin(root);
        try {
            if (Boolean.parseBoolean(System.getProperty(
                    "tellurium.gpuCandidate.debugDensityStageRootGpuChildrenSemanticLeaves", "true"))) {
                VulkanWorldgenExecutor.RawResult semanticLeaf = executeSemanticLeafStage(
                        executor, completeShader, root, coordinateWords, defaultStateId,
                        airStateId, invalidStateId, batchSize);
                if (semanticLeaf != null) {
                    execution.complete(executor, root);
                    return semanticLeaf;
                }
            }
            String source = completeShader.source();
            List<String> children = densityChildren(source, root);
            List<String> semanticChildren = semanticDirectChildFunctions(completeShader, root);
            if (semanticChildren != null && !semanticChildren.isEmpty()) {
                children = semanticChildren;
            }
            DirectNormalNoise directNoise = splitNormalNoise(completeShader) && children.isEmpty()
                    ? detectDirectNormalNoise(source, root) : null;
            if (directNoise != null) {
                VulkanWorldgenExecutor.RawResult result = executeStagedNormalNoise(
                        executor, completeShader, directNoise, coordinateWords,
                        defaultStateId, airStateId, invalidStateId, batchSize);
                    execution.complete(executor, root);
                    return result;
                }
            Map<String, VulkanWorldgenExecutor.RawResult> childResults = new LinkedHashMap<>();
            for (String child : children) {
                VulkanWorldgenExecutor.RawResult childResult = executeChunkedDensityRoot(
                        executor, completeShader, child, coordinateWords, defaultStateId,
                        airStateId, invalidStateId, batchSize, activeRoots, execution);
                childResults.put(child, childResult);
            }
            DirectNormalNoise embeddedNormalNoise = splitNormalNoise(completeShader) && !children.isEmpty()
                    ? detectEmbeddedNormalNoise(source, root) : null;
            if (embeddedNormalNoise != null) {
                int[] childInput = densityStageInput(coordinateWords, children, childResults);
                VulkanWorldgenExecutor.RawResult result = executeStagedEmbeddedNormalNoise(
                        executor, completeShader, embeddedNormalNoise, children, childInput,
                        coordinateWords, defaultStateId, airStateId, invalidStateId, batchSize);
                execution.complete(executor, root);
                return result;
            }
            DensityStageKind kind = isStageableBlendedNoiseFunction(root)
                    ? DensityStageKind.BLENDED_NOISE
                    : isDirectFp64Division(source, root, children) && children.size() == 2
                    ? DensityStageKind.FP64_DIVISION : DensityStageKind.GRAPH;
            if (kind == DensityStageKind.GRAPH && !children.isEmpty()) {
                VulkanWorldgenExecutor.RawResult carrierStage = executeSemanticCarrierStage(
                        executor, completeShader, root, children, coordinateWords, childResults,
                        defaultStateId, airStateId, invalidStateId, batchSize);
                if (carrierStage != null) {
                    execution.complete(executor, root);
                    return carrierStage;
                }
            }
            DensityStagePlan plan = new DensityStagePlan(root, children, kind);
            Map<String, VulkanWorldgenExecutor.RawResult> results = executeDensityPlanSet(
                    executor, completeShader, List.of(plan), coordinateWords, Set.of(root), childResults,
                    defaultStateId, airStateId, invalidStateId, batchSize,
                    "density-chunked-root root=" + root);
            VulkanWorldgenExecutor.RawResult result = requireStageResult(results, root);
            execution.complete(executor, root);
            return result;
        } finally {
            activeRoots.remove(root);
        }
    }

    /**
     * Emits one captured Shift leaf as a standalone semantic shader. Captured
     * normal-noise leaves use the existing coordinate/Perlin/combine route
     * because the target driver returned an incorrect zero for the generic
     * standalone Noise wrapper. This route remains behind the chunked
     * diagnostic switch.
     */
    private static VulkanWorldgenExecutor.RawResult executeSemanticLeafStage(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            String root, int[] coordinateWords, int defaultStateId, int airStateId,
            int invalidStateId, int batchSize) {
        ProgramNode node = completeShader.nodeFunctions().get(root);
        if (!(node instanceof ProgramNode.Noise) && !(node instanceof ProgramNode.Shift)) return null;
        if (node.type() != ValueType.FP32 && node.type() != ValueType.FP64) return null;
        if (node instanceof ProgramNode.Noise && splitNormalNoise(completeShader)) {
            // Captured normal noise already has a bounded two-half exact
            // containment path. The target driver produced an incorrect zero
            // from the generic standalone Noise wrapper, so keep Noise on
            // the known Perlin route and reserve this compact path for Shift.
            DirectNormalNoise direct = detectDirectNormalNoise(completeShader.source(), root);
            if (direct != null) {
                return executeStagedNormalNoise(executor, completeShader, direct, coordinateWords,
                        defaultStateId, airStateId, invalidStateId, batchSize);
            }
        }
        WorldgenProgram stageProgram = WorldgenProgram.builder()
                .version("tellurium-density-leaf-v2")
                .numericProfile(NumericProfile.GPU_IEEE_BITS)
                .root("finalDensity", node)
                .build();
        WorldgenShaderCompiler.Shader emitted;
        try {
            emitted = new WorldgenShaderCompiler().emit(
                    stageProgram, NumericProfile.GPU_IEEE_BITS, completeShader.localSize(),
                    StructureBlendSnapshot.empty());
        } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
            return null;
        }
        int main = emitted.source().indexOf("void main()");
        if (main < 0) return null;
        String source = emitted.source().substring(0, main)
                .replace("uint outputStateIds[]", "uint outputBits[]");
        String rootFunction = semanticFunctionForNode(emitted, node);
        if (rootFunction == null) return null;
        String value = node.type() == ValueType.FP64
                ? rootFunction + "(point)"
                : "wg_fp64_from_fp32(" + rootFunction + "(point))";
        Set<String> compactRoots = new LinkedHashSet<>(Set.of(
                "wg_point", rootFunction, "wg_fp64_qnan", "wg_fp64_finite"));
        if (node.type() == ValueType.FP32) compactRoots.add("wg_fp64_from_fp32");
        source = compactSource(source, compactRoots);
        boolean nativeCarrier = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugDensityStageRootGpuChildrenNativeCarrier", "false"));
        NumericProfile stageProfile = nativeCarrier
                ? NumericProfile.GPU_NATIVE_DRAFT : NumericProfile.GPU_IEEE_BITS;
        if (nativeCarrier) source = NativeDraftMath.rewrite(source, true, true);
        source += """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 value = %s;
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
                """.formatted(value);
        WorldgenShaderCompiler.Shader stageShader = new WorldgenShaderCompiler.Shader(
                source, completeShader.programHash() + "/semantic-leaf-" + root,
                stageProfile, emitted.localSize(),
                emitted.blendedNoiseFunctions(), emitted.nodeFunctions());
        SpirvNumericContract.require(stageShader.source(), stageShader.profile());
        writeLiveStageDiagnostic("density-semantic-leaf-" + root + ".comp", stageShader.source());
        VulkanWorldgenExecutor.RawResult result = executor.executeRawBatched(
                densityStageRequest(new VulkanWorldgenExecutor.RawRequest(
                        stageShader, coordinateWords, 4, DENSITY_STAGE_VALUE_WORDS,
                        coordinateWords.length / 4, defaultStateId, airStateId, invalidStateId))
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        executor.reclaimPipelines();
        return result;
    }

    /**
     * Emits the current semantic parent with its already-computed children
     * replaced by raw carrier inputs.  This is the important memory-bound
     * path for the large Overworld capture: the compiler sees one operator
     * and its direct child types, not the entire transitive graph.
     *
     * <p>The result row is always an FP64 carrier, even when the original
     * child node is FP32.  The generated carrier input therefore converts
     * back to the child's declared type before the parent operator runs.
     * Nodes whose semantics depend on the captured structure snapshot or on
     * a corner-domain ABI deliberately return {@code null} and use the older
     * source-rewrite route.</p>
     */
    private static VulkanWorldgenExecutor.RawResult executeSemanticCarrierStage(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            String root, List<String> childRoots, int[] coordinateWords,
            Map<String, VulkanWorldgenExecutor.RawResult> childResults,
            int defaultStateId, int airStateId, int invalidStateId, int batchSize) {
        ProgramNode rootNode = completeShader.nodeFunctions().get(root);
        if (rootNode == null || rootNode.type() != ValueType.FP32 && rootNode.type() != ValueType.FP64) {
            return null;
        }
        if (rootNode instanceof ProgramNode.BlendDensity
                || rootNode instanceof ProgramNode.Interpolated) {
            return null;
        }
        List<ProgramNode> originalChildren = rootNode.children();
        if (originalChildren.isEmpty() || originalChildren.size() != childRoots.size()) return null;

        List<ProgramNode> carrierChildren = new ArrayList<>(originalChildren.size());
        List<String> carrierRoots = new ArrayList<>(originalChildren.size());
        for (int index = 0; index < originalChildren.size(); index++) {
            ProgramNode child = originalChildren.get(index);
            if (child.type() != ValueType.FP32 && child.type() != ValueType.FP64) return null;
            String childFunction = semanticFunctionForNode(completeShader, child);
            if (childFunction == null || !childRoots.contains(childFunction)) return null;
            carrierRoots.add(childFunction);
            carrierChildren.add(new ProgramNode.Input("carrier" + index, child.type(), child.domain()));
        }
        if (!childRoots.containsAll(new LinkedHashSet<>(carrierRoots))) return null;

        ProgramNode stagedRoot = replaceSemanticChildren(rootNode, carrierChildren);
        if (stagedRoot == null) return null;
        WorldgenProgram stageProgram = WorldgenProgram.builder()
                .version("tellurium-density-carrier-v2")
                .numericProfile(NumericProfile.GPU_IEEE_BITS)
                .root("finalDensity", stagedRoot)
                .build();
        WorldgenShaderCompiler.Shader emitted;
        try {
            emitted = new WorldgenShaderCompiler().emit(
                    stageProgram, NumericProfile.GPU_IEEE_BITS, completeShader.localSize(),
                    StructureBlendSnapshot.empty());
        } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
            // The source-level route remains the fail-closed fallback for a
            // node kind that the small semantic stage cannot currently admit.
            return null;
        }

        int main = emitted.source().indexOf("void main()");
        if (main < 0) return null;
        String source = emitted.source()
                .substring(0, main)
                .replace("uint outputStateIds[]", "uint outputBits[]");
        int inputWords = densityStageInputWords(carrierRoots);
        for (Map.Entry<String, ProgramNode> entry : emitted.nodeFunctions().entrySet()) {
            if (!(entry.getValue() instanceof ProgramNode.Input input)
                    || !input.name().startsWith("carrier")) continue;
            int carrierIndex = parseSemanticCarrierIndex(input.name());
            if (carrierIndex < 0 || carrierIndex >= carrierRoots.size()) return null;
            source = replaceFunction(source, entry.getKey(), semanticCarrierFunction(
                    entry.getKey(), input.type(), inputWords, carrierIndex));
        }
        String rootFunction = semanticFunctionForNode(emitted, stagedRoot);
        if (rootFunction == null) return null;
        String value = stagedRoot.type() == ValueType.FP64
                ? rootFunction + "(point)"
                : "wg_fp64_from_fp32(" + rootFunction + "(point))";
        Set<String> compactRoots = new LinkedHashSet<>(Set.of(
                "wg_point", rootFunction, "wg_fp64_qnan", "wg_fp64_finite"));
        if (stagedRoot.type() == ValueType.FP32) compactRoots.add("wg_fp64_from_fp32");
        if (carrierChildren.stream().anyMatch(child -> child.type() == ValueType.FP32)) {
            compactRoots.add("wg_fp64_to_fp32");
        }
        for (Map.Entry<String, ProgramNode> entry : emitted.nodeFunctions().entrySet()) {
            if (entry.getValue() instanceof ProgramNode.Input input
                    && input.name().startsWith("carrier")) {
                compactRoots.add(entry.getKey());
            }
        }
        source = compactSource(source, compactRoots);
        boolean nativeCarrier = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugDensityStageRootGpuChildrenNativeCarrier", "false"));
        NumericProfile stageProfile = nativeCarrier
                ? NumericProfile.GPU_NATIVE_DRAFT : NumericProfile.GPU_IEEE_BITS;
        if (nativeCarrier) source = NativeDraftMath.rewrite(source, true, true);
        source += """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 value = %s;
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
                """.formatted(value);
        WorldgenShaderCompiler.Shader stageShader = new WorldgenShaderCompiler.Shader(
                source, completeShader.programHash() + "/semantic-carrier-" + root,
                stageProfile, emitted.localSize(),
                emitted.blendedNoiseFunctions(), emitted.nodeFunctions());
        SpirvNumericContract.require(stageShader.source(), stageShader.profile());
        writeLiveStageDiagnostic("density-semantic-carrier-" + root + ".comp", stageShader.source());
        int[] inputWordsBuffer = densityStageInput(coordinateWords, carrierRoots, childResults);
        return executor.executeRawBatched(
                densityStageRequest(new VulkanWorldgenExecutor.RawRequest(
                        stageShader, inputWordsBuffer, inputWords, DENSITY_STAGE_VALUE_WORDS,
                        coordinateWords.length / 4, defaultStateId, airStateId, invalidStateId))
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
    }

    /** Returns compiler-owned direct children, excluding generated spline helpers. */
    private static List<String> semanticDirectChildFunctions(
            WorldgenShaderCompiler.Shader shader, String root) {
        ProgramNode node = shader.nodeFunctions().get(root);
        if (node == null || node.children().isEmpty()) return null;
        List<String> children = new ArrayList<>(node.children().size());
        for (ProgramNode child : node.children()) {
            String function = semanticFunctionForNode(shader, child);
            if (function == null) return null;
            children.add(function);
        }
        return List.copyOf(children);
    }

    private static String semanticFunctionForNode(WorldgenShaderCompiler.Shader shader, ProgramNode node) {
        String equalMatch = null;
        for (Map.Entry<String, ProgramNode> entry : shader.nodeFunctions().entrySet()) {
            if (entry.getValue() == node) return entry.getKey();
            if (equalMatch == null && entry.getValue().equals(node)) equalMatch = entry.getKey();
        }
        return equalMatch;
    }

    private static int parseSemanticCarrierIndex(String name) {
        if (!name.startsWith("carrier") || name.length() == "carrier".length()) return -1;
        try {
            return Integer.parseInt(name.substring("carrier".length()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String semanticCarrierInputBody(ValueType type, int inputWords, int childIndex) {
        int offset = Math.addExact(4, Math.multiplyExact(childIndex, DENSITY_STAGE_VALUE_WORDS));
        String base = "gl_GlobalInvocationID.x * " + inputWords + "u + " + offset + "u";
        String carrier = "uvec2(inputBits[" + base + "], inputBits[" + base + " + 1u])";
        return switch (type) {
            case FP64 -> "return " + carrier + ";";
            case FP32 -> "return wg_fp64_to_fp32(" + carrier + ");";
            default -> throw new IllegalArgumentException("Unsupported semantic carrier type: " + type);
        };
    }

    private static String semanticCarrierFunction(String name, ValueType type,
                                                  int inputWords, int childIndex) {
        String returnType = switch (type) {
            case FP64 -> "uvec2";
            case FP32, INT32 -> "uint";
            case BOOLEAN -> "bool";
            default -> throw new IllegalArgumentException("Unsupported semantic carrier type: " + type);
        };
        return returnType + " " + name + "(ivec3 point) {\n"
                + semanticCarrierInputBody(type, inputWords, childIndex) + "\n}";
    }

    private static ProgramNode replaceSemanticChildren(ProgramNode root, List<ProgramNode> children) {
        if (root.children().size() != children.size()) return null;
        if (root instanceof ProgramNode.Unary node) {
            return new ProgramNode.Unary(node.operation(), node.type(), node.domain(), children.get(0));
        }
        if (root instanceof ProgramNode.Binary node) {
            return new ProgramNode.Binary(node.operation(), node.type(), node.domain(),
                    children.get(0), children.get(1));
        }
        if (root instanceof ProgramNode.Ap2 node) {
            return new ProgramNode.Ap2(node.operation(), node.type(), node.domain(),
                    children.get(0), children.get(1), node.rightMinValue(), node.rightMaxValue());
        }
        if (root instanceof ProgramNode.Select node) {
            return new ProgramNode.Select(node.operation(), node.type(), node.domain(),
                    children.get(0), children.get(1), children.get(2));
        }
        if (root instanceof ProgramNode.RangeChoice node) {
            return new ProgramNode.RangeChoice(children.get(0), node.minInclusive(), node.maxExclusive(),
                    children.get(1), children.get(2), node.type(), node.domain());
        }
        if (root instanceof ProgramNode.Marker node) {
            return new ProgramNode.Marker(node.marker(), node.cacheMode(), node.type(), node.domain(),
                    children.get(0), node.effects());
        }
        if (root instanceof ProgramNode.ShiftedNoise node) {
            return new ProgramNode.ShiftedNoise(node.name(), node.parameters(), node.xzScale(), node.yScale(),
                    children.get(0), children.get(1), children.get(2), node.type(), node.domain());
        }
        if (root instanceof ProgramNode.WeirdScaledSampler node) {
            return new ProgramNode.WeirdScaledSampler(children.get(0), node.parameters(),
                    node.rarityMapper(), node.type(), node.domain());
        }
        if (root instanceof ProgramNode.Spline node) {
            int[] cursor = {0};
            ProgramNode.SplineNode spline = replaceSemanticSplineNode(node.spline(), children, cursor);
            if (spline == null || cursor[0] != children.size()) return null;
            return new ProgramNode.Spline(spline, node.type(), node.domain());
        }
        return null;
    }

    private static ProgramNode.SplineNode replaceSemanticSplineNode(
            ProgramNode.SplineNode node, List<ProgramNode> children, int[] cursor) {
        if (node instanceof ProgramNode.SplineConstant) return node;
        ProgramNode.SplineMultipoint multipoint = (ProgramNode.SplineMultipoint) node;
        if (cursor[0] >= children.size()) return null;
        ProgramNode coordinate = children.get(cursor[0]++);
        List<ProgramNode.SplineNode> values = new ArrayList<>(multipoint.values().size());
        for (ProgramNode.SplineNode value : multipoint.values()) {
            ProgramNode.SplineNode replacement = replaceSemanticSplineNode(value, children, cursor);
            if (replacement == null) return null;
            values.add(replacement);
        }
        return new ProgramNode.SplineMultipoint(coordinate, multipoint.locations(), values,
                multipoint.derivatives());
    }

    /** Per-diagnostic guard for recursive GPU child execution. */
    private static final class ChunkedDensityExecution {
        private final int maxStages = integerProperty(
                "tellurium.gpuCandidate.debugDensityStageRootGpuChildrenMaxStages", 4096);
        private final int reclaimEvery = integerProperty(
                "tellurium.gpuCandidate.debugDensityStageRootGpuChildrenReclaimEvery", 1);
        private final int recreateDeviceEvery = integerProperty(
                "tellurium.gpuCandidate.debugDensityStageRootGpuChildrenRecreateDeviceEvery", 0);
        private final long maxElapsedNanos;
        private final long startedNanos = System.nanoTime();
        private int startedStages;
        private int completedStages;

        private ChunkedDensityExecution() {
            if (maxStages <= 0) {
                throw new IllegalArgumentException(
                        "GPU candidate debugDensityStageRootGpuChildrenMaxStages must be positive");
            }
            if (reclaimEvery <= 0) {
                throw new IllegalArgumentException(
                        "GPU candidate debugDensityStageRootGpuChildrenReclaimEvery must be positive");
            }
            if (recreateDeviceEvery < 0) {
                throw new IllegalArgumentException(
                        "GPU candidate debugDensityStageRootGpuChildrenRecreateDeviceEvery must be zero or positive");
            }
            int maxElapsedMillis = integerProperty(
                    "tellurium.gpuCandidate.debugDensityStageRootGpuChildrenMaxMillis", 600_000);
            if (maxElapsedMillis <= 0) {
                throw new IllegalArgumentException(
                        "GPU candidate debugDensityStageRootGpuChildrenMaxMillis must be positive");
            }
            maxElapsedNanos = Math.multiplyExact((long) maxElapsedMillis, 1_000_000L);
        }

        private void begin(String root) {
            checkElapsed(root);
            if (startedStages >= maxStages) {
                throw new IllegalStateException("GPU candidate chunked density child route exceeded "
                        + maxStages + " stages before " + root);
            }
            startedStages++;
        }

        private void complete(VulkanWorldgenExecutor executor, String root) {
            checkElapsed(root);
            completedStages++;
            if (completedStages % reclaimEvery == 0) {
                executor.reclaimPipelines();
                stageDebug(Boolean.parseBoolean(System.getProperty(
                        "tellurium.gpuCandidate.debugStages", "false")),
                        "density-chunked-reclaim completed=" + completedStages + " root=" + root);
            }
            if (recreateDeviceEvery > 0 && completedStages % recreateDeviceEvery == 0) {
                executor.recreateDevice();
                stageDebug(Boolean.parseBoolean(System.getProperty(
                        "tellurium.gpuCandidate.debugStages", "false")),
                        "density-chunked-device-recreate completed=" + completedStages + " root=" + root);
            }
        }

        private void checkElapsed(String root) {
            if (System.nanoTime() - startedNanos >= maxElapsedNanos) {
                throw new IllegalStateException("GPU candidate chunked density child route exceeded "
                        + (maxElapsedNanos / 1_000_000L) + " ms before " + root);
            }
        }
    }

    /**
     * Evaluates one direct density parent from caller-supplied child carriers.
     * This is a bounded isolation aid for a large captured fallback subtree:
     * the parent operator still executes on Vulkan, while the child values are
     * explicit diagnostic inputs rather than CPU-generated production output.
     * The format is {@code child=value;child=value}, using decimal FP64 values
     * whose Java {@link Double#toString(double)} form round-trips to the raw
     * carrier bits.  It intentionally rejects interpolation roots because
     * those have a different corner-domain ABI.
     */
    private static VulkanWorldgenExecutor.RawResult executeSeededDensityRoot(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            String root, int[] coordinateWords, String encodedChildValues,
            int defaultStateId, int airStateId, int invalidStateId, int batchSize) {
        if (isDensityInterpolationFunction(completeShader.source(), root)) {
            throw new IllegalArgumentException(
                    "Seeded density root diagnostic does not support interpolation root " + root);
        }
        List<String> children = densityChildren(completeShader.source(), root);
        if (children.isEmpty()) {
            throw new IllegalArgumentException("Seeded density root has no direct density children: " + root);
        }
        Map<String, Double> values = new LinkedHashMap<>();
        for (String entry : encodedChildValues.split(";", -1)) {
            if (entry.isBlank()) continue;
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1
                    || entry.indexOf('=', separator + 1) >= 0) {
                throw new IllegalArgumentException(
                        "GPU candidate debugDensityStageRootChildValues expects child=value;child=value");
            }
            String child = entry.substring(0, separator).trim();
            if (!children.contains(child)) {
                throw new IllegalArgumentException("Seeded density root value is not a direct child of "
                        + root + ": " + child + " expected=" + children);
            }
            double value;
            try {
                value = Double.parseDouble(entry.substring(separator + 1).trim());
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("Invalid seeded density carrier for " + child, failure);
            }
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Seeded density carrier must be finite: " + child);
            }
            if (values.put(child, value) != null) {
                throw new IllegalArgumentException("Duplicate seeded density child: " + child);
            }
        }
        if (values.size() != children.size()) {
            throw new IllegalArgumentException("Seeded density root values must cover direct children of "
                    + root + ": expected=" + children + " actual=" + values.keySet());
        }
        if (coordinateWords.length == 0 || coordinateWords.length % 4 != 0) {
            throw new IllegalArgumentException("Seeded density root coordinates must contain four words per element");
        }
        int elementCount = coordinateWords.length / 4;
        int inputWordsPerElement = densityStageInputWords(children);
        int[] inputWords = new int[Math.multiplyExact(elementCount, inputWordsPerElement)];
        for (int element = 0; element < elementCount; element++) {
            int target = element * inputWordsPerElement;
            System.arraycopy(coordinateWords, element * 4, inputWords, target, 4);
            for (int childIndex = 0; childIndex < children.size(); childIndex++) {
                long bits = Double.doubleToRawLongBits(values.get(children.get(childIndex)));
                int childTarget = target + 4 + childIndex * DENSITY_STAGE_VALUE_WORDS;
                inputWords[childTarget] = (int) bits;
                inputWords[childTarget + 1] = (int) (bits >>> 32);
            }
        }
        WorldgenShaderCompiler.Shader stageBase = exactInterpolationStageShader(completeShader, root);
        WorldgenShaderCompiler.Shader stageShader = stageShader(stageBase,
                densityParentStageSource(stageBase.source(), root, children),
                "density-seeded-root-" + root);
        SpirvNumericContract.require(stageShader.source(), stageShader.profile());
        writeLiveStageDiagnostic("density-seeded-root-" + root + ".comp", stageShader.source());
        return executor.executeRawBatched(
                densityStageRequest(new VulkanWorldgenExecutor.RawRequest(
                        stageShader, inputWords, inputWordsPerElement, DENSITY_STAGE_VALUE_WORDS,
                        elementCount, defaultStateId, airStateId, invalidStateId))
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
    }

    /**
     * Evaluates one density root on a coordinate domain.  Interpolation roots
     * are explicit domain boundaries: their children run on the unique cell
     * corners and the interpolation itself runs back on the caller's points.
     * Non-interpolation portions retain the ordinary source-level stage plan.
     */
    private static VulkanWorldgenExecutor.RawResult executeStagedDensityRoot(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            String root, int[] coordinateWords, int defaultStateId, int airStateId,
            int invalidStateId, int batchSize, Set<String> activeRoots) {
        return executeStagedDensityRoot(executor, completeShader, root, coordinateWords,
                defaultStateId, airStateId, invalidStateId, batchSize, activeRoots, true, null);
    }

    private static VulkanWorldgenExecutor.RawResult executeStagedDensityRoot(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            String root, int[] coordinateWords, int defaultStateId, int airStateId,
            int invalidStateId, int batchSize, Set<String> activeRoots,
            boolean applyDirectStageProbeLimit) {
        return executeStagedDensityRoot(executor, completeShader, root, coordinateWords,
                defaultStateId, airStateId, invalidStateId, batchSize, activeRoots,
                applyDirectStageProbeLimit, null);
    }

    private static VulkanWorldgenExecutor.RawResult executeStagedDensityRoot(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            String root, int[] coordinateWords, int defaultStateId, int airStateId,
            int invalidStateId, int batchSize, Set<String> activeRoots,
            boolean applyDirectStageProbeLimit, Boolean aggressiveStagingOverride) {
        if (applyDirectStageProbeLimit) {
            coordinateWords = directStageProbeCoordinates(coordinateWords);
        }
        if (isDensityInterpolationFunction(completeShader.source(), root)) {
            return executeStagedDensityInterpolation(executor, completeShader, root,
                    coordinateWords, defaultStateId, airStateId, invalidStateId,
                    batchSize, activeRoots, aggressiveStagingOverride);
        }
        if (!activeRoots.add(root)) {
            throw new IllegalArgumentException("Staged direct density graph is recursive at " + root);
        }
        try {
            String source = completeShader.source();
            DirectNormalNoise directNoise = splitNormalNoise(completeShader)
                    ? detectDirectNormalNoise(source, root) : null;
            if (directNoise != null) {
                return executeStagedNormalNoise(executor, completeShader, directNoise,
                        coordinateWords, defaultStateId, airStateId, invalidStateId, batchSize);
            }
            Map<String, GlslFunction> functions = parseFunctions(source);
            Map<String, Set<String>> calls = functionCalls(source, functions);
            Set<String> interpolationRoots = reachableFunctions(Set.of(root), calls, Set.of()).stream()
                    .filter(name -> isDensityFunction(name)
                            && isDensityInterpolationFunction(source, name, functions))
                    .sorted()
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Map<String, VulkanWorldgenExecutor.RawResult> seeded = new LinkedHashMap<>();
            for (String interpolationRoot : interpolationRoots) {
                seeded.put(interpolationRoot, executeStagedDensityInterpolation(executor, completeShader,
                        interpolationRoot, coordinateWords, defaultStateId, airStateId,
                        invalidStateId, batchSize, activeRoots, aggressiveStagingOverride));
            }
            boolean aggressiveStaging = aggressiveStagingOverride == null
                    ? aggressiveDensityStaging(completeShader) : aggressiveStagingOverride;
            List<DensityStagePlan> plans = findDensityStagePlans(
                    source, List.of(root), interpolationRoots, aggressiveStaging);
            boolean stageDebugEnabled = Boolean.parseBoolean(
                    System.getProperty("tellurium.gpuCandidate.debugStages", "false"));
            stageDebug(stageDebugEnabled, "density-direct-branch-plan root=" + root
                    + " stages=" + plans.size() + " elements=" + coordinateWords.length / 4
                    + " interpolationBoundaries=" + interpolationRoots.size()
                    + " aggressive=" + aggressiveStaging);
            Map<String, VulkanWorldgenExecutor.RawResult> results = executeDensityPlanSet(
                    executor, completeShader, plans, coordinateWords, Set.of(root), seeded,
                    defaultStateId, airStateId, invalidStateId, batchSize,
                    "density-direct-branch root=" + root);
            return requireStageResult(results, root);
        } finally {
            activeRoots.remove(root);
        }
    }

    /** Executes an interpolated root after its direct children have been sampled at corners. */
    private static VulkanWorldgenExecutor.RawResult executeStagedDensityInterpolation(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            String root, int[] coordinateWords, int defaultStateId, int airStateId,
            int invalidStateId, int batchSize, Set<String> activeRoots) {
        return executeStagedDensityInterpolation(executor, completeShader, root, coordinateWords,
                defaultStateId, airStateId, invalidStateId, batchSize, activeRoots, null);
    }

    private static VulkanWorldgenExecutor.RawResult executeStagedDensityInterpolation(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            String root, int[] coordinateWords, int defaultStateId, int airStateId,
            int invalidStateId, int batchSize, Set<String> activeRoots,
            Boolean aggressiveStagingOverride) {
        return executeStagedDensityInterpolation(executor, completeShader, root, coordinateWords,
                defaultStateId, airStateId, invalidStateId, batchSize, activeRoots,
                aggressiveStagingOverride, null);
    }

    private static VulkanWorldgenExecutor.RawResult executeStagedDensityInterpolation(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            String root, int[] coordinateWords, int defaultStateId, int airStateId,
            int invalidStateId, int batchSize, Set<String> activeRoots,
            Boolean aggressiveStagingOverride, ChunkedDensityExecution chunkedExecution) {
        if (!activeRoots.add(root)) {
            throw new IllegalArgumentException("Staged density interpolation graph is recursive at " + root);
        }
        if (chunkedExecution != null) chunkedExecution.begin(root);
        try {
            String interpolationSource = completeShader.source();
            List<String> children = densityChildren(interpolationSource, root);
            if (children.isEmpty()) {
                throw new UnsupportedOperationException("Density interpolation has no staged child: " + root);
            }
            DensityInterpolationGeometry geometry = densityInterpolationGeometryForRoot(
                    interpolationSource, root);
            DensityStageInputs stageInputs = buildDensityStageInputs(
                    tripleCoordinates(coordinateWords), geometry);
            int[] cornerCoordinates = stageInputs.uniqueCoordinates();
            Map<String, VulkanWorldgenExecutor.RawResult> childResults = new LinkedHashMap<>();
            boolean childAggressiveStaging = aggressiveStagingOverride == null
                    ? interpolationAggressiveDensityStaging() : aggressiveStagingOverride;
            boolean chunkGpuChildren = Boolean.parseBoolean(System.getProperty(
                    "tellurium.gpuCandidate.debugDensityStageRootGpuChildren", "false"));
            ChunkedDensityExecution childExecution = chunkGpuChildren
                    ? chunkedExecution == null ? new ChunkedDensityExecution() : chunkedExecution : null;
            for (String child : children) {
                childResults.put(child, chunkGpuChildren
                        ? executeChunkedDensityRoot(executor, completeShader, child, cornerCoordinates,
                        defaultStateId, airStateId, invalidStateId, batchSize, activeRoots, childExecution)
                        : executeStagedDensityRoot(executor, completeShader, child,
                        cornerCoordinates, defaultStateId, airStateId, invalidStateId,
                        batchSize, activeRoots, false, childAggressiveStaging));
            }
            int blockCount = coordinateWords.length / 4;
            debugDensityInterpolationChildren(root, children, childResults, stageInputs,
                    coordinateWords);
            int inputWordsPerElement = densityCornerStageInputWords(children);
            int[] fractionWords = densityInterpolationFractionWords(coordinateWords, geometry);
            int[] inputWords = new int[Math.multiplyExact(blockCount, inputWordsPerElement)];
            int[] cornerIndices = stageInputs.cornerIndices();
            int[][] childWords = new int[children.size()][];
            for (int childIndex = 0; childIndex < children.size(); childIndex++) {
                VulkanWorldgenExecutor.RawResult childResult = childResults.get(children.get(childIndex));
                if (childResult.elementCount() != cornerCoordinates.length / 4
                        || childResult.outputWordsPerElement() != DENSITY_STAGE_VALUE_WORDS) {
                    throw new IllegalStateException("Density interpolation child has incompatible geometry: "
                            + children.get(childIndex));
                }
                // RawResult accessors preserve ownership by cloning. Take one
                // local copy, never a complete array copy for every block/corner.
                childWords[childIndex] = childResult.outputWords();
            }
            for (int block = 0; block < blockCount; block++) {
                int target = block * inputWordsPerElement;
                System.arraycopy(coordinateWords, block * 4, inputWords, target, 4);
                System.arraycopy(fractionWords, block * DENSITY_STAGE_CORNER_FRACTION_WORDS,
                        inputWords, target + 4, DENSITY_STAGE_CORNER_FRACTION_WORDS);
                for (int childIndex = 0; childIndex < children.size(); childIndex++) {
                    int childTarget = target + DENSITY_STAGE_CORNER_VALUE_BASE_WORDS
                            + childIndex * DENSITY_STAGE_CORNER_VALUE_WORDS;
                    for (int corner = 0; corner < DENSITY_STAGE_CORNER_COUNT; corner++) {
                        int source = cornerIndices[block * DENSITY_STAGE_CORNER_COUNT + corner]
                                * DENSITY_STAGE_VALUE_WORDS;
                        inputWords[childTarget + corner * DENSITY_STAGE_VALUE_WORDS] =
                                childWords[childIndex][source];
                        inputWords[childTarget + corner * DENSITY_STAGE_VALUE_WORDS + 1] =
                                childWords[childIndex][source + 1];
                    }
                }
            }
            WorldgenShaderCompiler.Shader interpolationShaderBase = completeShader;
            if (completeShader.profile() == NumericProfile.GPU_NATIVE_DRAFT
                    && Boolean.parseBoolean(System.getProperty(
                            "tellurium.gpuCandidate.nativeDraftExactInterpolation", "false"))) {
                WorldgenShaderCompiler.Shader exactShader = EXACT_NORMAL_NOISE_SHADER.get();
                if (exactShader != null) interpolationShaderBase = exactShader;
            }
            String stageSource = densityCornerParentStageSource(
                    interpolationShaderBase.source(), root, children);
            WorldgenShaderCompiler.Shader stageShader = stageShader(interpolationShaderBase, stageSource,
                    "density-interpolation-" + root);
            SpirvNumericContract.require(stageShader.source(), stageShader.profile());
            VulkanWorldgenExecutor.RawResult result = executor.executeRawBatched(
                    directDensityStageRequest(new VulkanWorldgenExecutor.RawRequest(
                            stageShader, inputWords, inputWordsPerElement, DENSITY_STAGE_VALUE_WORDS,
                            blockCount, defaultStateId, airStateId, invalidStateId))
                            .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
            if (result.elementCount() != blockCount
                    || result.outputWordsPerElement() != DENSITY_STAGE_VALUE_WORDS) {
                throw new IllegalStateException("Density interpolation returned incompatible geometry: " + root);
            }
            debugDensityStageCpuOracle(root, result, coordinateWords);
            stageDebug(Boolean.parseBoolean(System.getProperty(
                    "tellurium.gpuCandidate.debugStages", "false")),
                    "density-interpolation-complete root=" + root + " corners="
                            + cornerCoordinates.length / 4 + " blocks=" + blockCount
                            + debugStageValues(result.outputWords(), coordinateWords));
            if (chunkedExecution != null) chunkedExecution.complete(executor, root);
            return result;
        } finally {
            activeRoots.remove(root);
        }
    }

    /**
     * Emits one bounded child-corner snapshot for a selected interpolation
     * probe. This is deliberately opt-in: it is an isolation aid for the
     * native draft and must not add a per-block diagnostic cost to ordinary
     * staged execution.
     */
    private static void debugDensityInterpolationChildren(
            String root, List<String> children,
            Map<String, VulkanWorldgenExecutor.RawResult> childResults,
            DensityStageInputs stageInputs, int[] coordinateWords) {
        if (!Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugDensityInterpolationComponents", "false"))) return;
        DensityProbe probe = parseDensityProbe();
        if (probe == null || coordinateWords.length % 4 != 0) return;
        int probeBlock = findCoordinateIndex(tripleCoordinates(coordinateWords), probe);
        int[] cornerIndices = stageInputs.cornerIndices();
        StringBuilder corners = new StringBuilder();
        for (String child : children) {
            VulkanWorldgenExecutor.RawResult result = childResults.get(child);
            int[] words = result.outputWords();
            corners.setLength(0);
            corners.append("density-interpolation-child-values root=").append(root)
                    .append(" child=").append(child).append(" probe=").append(probeText())
                    .append(" corners=[");
            for (int corner = 0; corner < DENSITY_STAGE_CORNER_COUNT; corner++) {
                if (corner != 0) corners.append(", ");
                int source = cornerIndices[probeBlock * DENSITY_STAGE_CORNER_COUNT + corner]
                        * DENSITY_STAGE_VALUE_WORDS;
                corners.append(carrierValue(words, source));
            }
            corners.append(']');
            stageDebug(true, corners.toString());
        }
    }

    /** Executes one child-before-parent plan on one coordinate domain. */
    private static Map<String, VulkanWorldgenExecutor.RawResult> executeDensityPlanSet(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            List<DensityStagePlan> plans, int[] stageCoordinates, Set<String> finalRoots,
            Map<String, VulkanWorldgenExecutor.RawResult> seeded, int defaultStateId,
            int airStateId, int invalidStateId, int batchSize, String debugLabel) {
        int uniqueCount = stageCoordinates.length / 4;
        int stopAfterStage = integerProperty(
                "tellurium.gpuCandidate.stopAfterDirectDensityStage", -1);
        if (stopAfterStage < -1) {
            throw new IllegalArgumentException("GPU candidate stopAfterDirectDensityStage must be -1 or a zero-based stage index");
        }
        int deviceRecreateInterval = integerProperty(
                "tellurium.gpuCandidate.recreateDeviceInterval", -1);
        if (deviceRecreateInterval == 0 || deviceRecreateInterval < -1) {
            throw new IllegalArgumentException(
                    "GPU candidate recreateDeviceInterval must be -1 or a positive stage interval");
        }
        int directStageProbeElements = integerProperty(
                "tellurium.gpuCandidate.directStageProbeElements", -1);
        if (directStageProbeElements == 0 || directStageProbeElements > uniqueCount) {
            throw new IllegalArgumentException("GPU candidate directStageProbeElements must be -1 or in [1, "
                    + uniqueCount + "]");
        }
        // A direct root can be preceded by a corner-domain interpolation child.
        // Only trim the plan that actually contains the requested stop stage;
        // the child must retain its full corner geometry for the parent ABI.
        if (stopAfterStage >= 0 && stopAfterStage < plans.size()
                && directStageProbeElements > 0 && directStageProbeElements < uniqueCount) {
            stageCoordinates = Arrays.copyOf(stageCoordinates, directStageProbeElements * 4);
            uniqueCount = directStageProbeElements;
            stageDebug(Boolean.parseBoolean(System.getProperty(
                    "tellurium.gpuCandidate.debugStages", "false")),
                    debugLabel + "-probe-limit elements=" + uniqueCount);
        }
        Map<String, Integer> remainingConsumers = densityStageConsumerCounts(plans);
        Map<String, VulkanWorldgenExecutor.RawResult> stageResults = new LinkedHashMap<>(seeded);
        boolean stageDebugEnabled = Boolean.parseBoolean(
                System.getProperty("tellurium.gpuCandidate.debugStages", "false"));
        DeviceCapabilities device = seeded.values().stream().findFirst()
                .map(VulkanWorldgenExecutor.RawResult::device).orElse(null);
        for (int stageIndex = 0; stageIndex < plans.size();) {
            DensityStagePlan plan = plans.get(stageIndex);
            if (seeded.containsKey(plan.root())) {
                stageDebug(stageDebugEnabled, debugLabel + "-seeded index=" + stageIndex
                        + " root=" + plan.root());
                stageIndex++;
                continue;
            }
            if (densityResidentScratchEnabled()) {
                int residentEnd = densityResidentGraphSegmentEnd(completeShader.source(), plans,
                        stageIndex, stopAfterStage, deviceRecreateInterval);
                if (residentEnd > stageIndex) {
                    List<DensityStagePlan> residentPlans = plans.subList(stageIndex, residentEnd);
                    Map<String, VulkanWorldgenExecutor.RawResult> resident =
                            executeResidentDensityGraphSegment(executor, completeShader, residentPlans,
                                    stageCoordinates, seeded, stageResults, defaultStateId,
                                    airStateId, invalidStateId, batchSize, debugLabel);
                    for (int residentIndex = stageIndex; residentIndex < residentEnd; residentIndex++) {
                        DensityStagePlan residentPlan = plans.get(residentIndex);
                        VulkanWorldgenExecutor.RawResult result = requireStageResult(resident,
                                residentPlan.root());
                        if (device == null) device = result.device();
                        else requireSameDevice(device, result.device(), debugLabel + " stages");
                        if (remainingConsumers.containsKey(residentPlan.root())
                                || finalRoots.contains(residentPlan.root())) {
                            stageResults.put(residentPlan.root(), result);
                        }
                        releaseDensityStageChildren(residentPlan.childRoots(), remainingConsumers,
                                stageResults, finalRoots);
                        debugDensityStageCpuOracle(residentPlan.root(), result, stageCoordinates);
                        stageDebug(stageDebugEnabled, debugLabel + "-stage-complete index="
                                + residentIndex + " root=" + residentPlan.root() + " resident=true"
                                + debugStageValues(result.outputWords(), stageCoordinates));
                    }
                    int lastStage = residentEnd - 1;
                    if ((lastStage + 1) % DENSITY_PIPELINE_RECLAIM_INTERVAL == 0) {
                        executor.reclaimPipelines();
                    }
                    if (deviceRecreateInterval > 0 && (lastStage + 1) % deviceRecreateInterval == 0) {
                        executor.recreateDevice();
                        stageDebug(stageDebugEnabled, debugLabel + "-device-recreate index=" + lastStage);
                    }
                    if (lastStage == stopAfterStage) {
                        throw new IllegalStateException("GPU candidate stopped after direct density stage "
                                + lastStage + " for compiler/driver diagnostics");
                    }
                    stageIndex = residentEnd;
                    continue;
                }
            }
            int bundleEnd = densityStageBundleEnd(completeShader.source(), plans, stageIndex, -1, -1);
            if (bundleEnd > stageIndex + 1) {
                List<DensityStagePlan> bundlePlans = plans.subList(stageIndex, bundleEnd);
                List<String> bundleChildren = densityStageBundleChildren(bundlePlans);
                String bundleSource = densityStageBundleSourceForPlans(
                        completeShader.source(), bundlePlans, bundleChildren);
                int bundleSourceLimit = densityStageBundleSourceLimit();
                if (bundleSource.length() > bundleSourceLimit) {
                    stageDebug(stageDebugEnabled, debugLabel + "-bundle-skip start=" + stageIndex
                            + " end=" + (bundleEnd - 1) + " chars=" + bundleSource.length()
                            + " limit=" + bundleSourceLimit);
                    bundleEnd = stageIndex + 1;
                }
            }
            if (bundleEnd > stageIndex + 1) {
                List<DensityStagePlan> bundlePlans = plans.subList(stageIndex, bundleEnd);
                List<String> bundleChildren = densityStageBundleChildren(bundlePlans);
                String bundleSource = densityStageBundleSourceForPlans(
                        completeShader.source(), bundlePlans, bundleChildren);
                WorldgenShaderCompiler.Shader bundleShader = stageShader(completeShader, bundleSource,
                        "density-direct-stage-bundle-" + stageIndex);
                SpirvNumericContract.require(bundleShader.source(), bundleShader.profile());
                writeLiveStageDiagnostic("density-direct-bundle-" + stageIndex + ".comp", bundleShader.source());
                stageDebug(stageDebugEnabled, debugLabel + "-bundle-plan start=" + stageIndex
                        + " end=" + (bundleEnd - 1) + " roots=" + bundlePlans.size()
                        + " children=" + bundleChildren.size() + " chars=" + bundleSource.length());
                Map<String, VulkanWorldgenExecutor.RawResult> bundled = executeDensityStageBundle(
                        executor, bundleShader, bundlePlans, bundleChildren, stageCoordinates,
                        stageResults, defaultStateId, airStateId, invalidStateId, batchSize, true);
                for (int bundledIndex = stageIndex; bundledIndex < bundleEnd; bundledIndex++) {
                    DensityStagePlan bundledPlan = plans.get(bundledIndex);
                    VulkanWorldgenExecutor.RawResult result = requireStageResult(bundled, bundledPlan.root());
                    if (device == null) device = result.device();
                    else requireSameDevice(device, result.device(), debugLabel + " bundled stages");
                    if (remainingConsumers.containsKey(bundledPlan.root())
                            || finalRoots.contains(bundledPlan.root())) {
                        stageResults.put(bundledPlan.root(), result);
                    }
                    releaseDensityStageChildren(bundledPlan.childRoots(), remainingConsumers,
                            stageResults, finalRoots);
                    debugDensityStageCpuOracle(bundledPlan.root(), result, stageCoordinates);
                    stageDebug(stageDebugEnabled, debugLabel + "-stage-complete index=" + bundledIndex
                            + " root=" + bundledPlan.root() + " bundled=true"
                            + debugStageValues(result.outputWords(), stageCoordinates));
                }
                if ((bundleEnd % DENSITY_PIPELINE_RECLAIM_INTERVAL) == 0) {
                    executor.reclaimPipelines();
                }
                if (deviceRecreateInterval > 0 && bundleEnd % deviceRecreateInterval == 0) {
                    executor.recreateDevice();
                    stageDebug(stageDebugEnabled, debugLabel + "-device-recreate index=" + (bundleEnd - 1));
                }
                if (stopAfterStage >= stageIndex && stopAfterStage < bundleEnd) {
                    throw new IllegalStateException("GPU candidate stopped after direct density stage "
                            + stopAfterStage + " for compiler/driver diagnostics");
                }
                stageIndex = bundleEnd;
                continue;
            }
            boolean sharedSpline = canExecuteSharedSplineStage(completeShader, plan);
            DirectNormalNoise directNormalNoise = !sharedSpline && plan.kind() == DensityStageKind.GRAPH
                    && plan.childRoots().isEmpty()
                    && splitNormalNoise(completeShader)
                    ? detectDirectNormalNoise(completeShader.source(), plan.root()) : null;
            DirectNormalNoise embeddedNormalNoise = !sharedSpline && plan.kind() == DensityStageKind.GRAPH
                    && !plan.childRoots().isEmpty()
                    && splitNormalNoise(completeShader)
                    ? detectEmbeddedNormalNoise(completeShader.source(), plan.root()) : null;
            boolean specialStage = sharedSpline || plan.kind() != DensityStageKind.GRAPH
                    || directNormalNoise != null || embeddedNormalNoise != null;
            WorldgenShaderCompiler.Shader stageShaderBase = sharedSpline ? completeShader : exactInterpolationStageShader(
                    completeShader, plan.root());
            String source = specialStage ? "" : densityStageSourceForPlan(stageShaderBase.source(), plan);
            WorldgenShaderCompiler.Shader stageShader = specialStage
                    ? null : stageShader(stageShaderBase, source, "density-direct-stage-" + stageIndex
                    + "-" + plan.root());
            if (stageShader != null) SpirvNumericContract.require(stageShader.source(), stageShader.profile());
            if (stageShader != null) writeLiveStageDiagnostic("density-direct-" + plan.root() + ".comp", stageShader.source());
            int inputWordsPerElement = densityStageInputWords(plan.childRoots());
            int[] inputWords = sharedSpline ? null : densityStageInput(stageCoordinates, plan.childRoots(), stageResults);
            stageDebug(stageDebugEnabled, debugLabel + "-stage-plan index=" + stageIndex
                    + " root=" + plan.root() + " children=" + plan.childRoots().size()
                    + " kind=" + (directNormalNoise != null ? "DIRECT_NORMAL_NOISE"
                    : embeddedNormalNoise != null ? "EMBEDDED_NORMAL_NOISE" : plan.kind())
                    + " chars=" + source.length());
            VulkanWorldgenExecutor.RawResult result;
            if (sharedSpline) {
                result = executeSharedSplineStage(executor, completeShader, plan,
                        stageCoordinates, stageResults, defaultStateId, airStateId, invalidStateId, batchSize);
            } else if (plan.kind() == DensityStageKind.BLENDED_NOISE) {
                result = executeStagedBlendedNoise(executor, completeShader, plan.root(),
                        stageCoordinates, batchSize);
            } else if (capturedEndIslandRoot(completeShader.source(), plan.root())) {
                result = executeStagedEndIsland(executor, completeShader, plan.root(), stageCoordinates, batchSize);
            } else if (directNormalNoise != null) {
                result = executeStagedNormalNoise(executor, completeShader, directNormalNoise,
                        stageCoordinates, defaultStateId, airStateId, invalidStateId, batchSize);
            } else if (embeddedNormalNoise != null) {
                result = executeStagedEmbeddedNormalNoise(executor, completeShader, embeddedNormalNoise,
                        plan.childRoots(), inputWords, stageCoordinates, defaultStateId,
                        airStateId, invalidStateId, batchSize);
            } else if (plan.kind() == DensityStageKind.FP64_DIVISION) {
                result = executeStagedFp64Division(executor, completeShader, plan, inputWords,
                        uniqueCount, defaultStateId, airStateId, invalidStateId, batchSize);
            } else {
                result = executor.executeRawBatched(
                        directDensityStageRequest(new VulkanWorldgenExecutor.RawRequest(stageShader, inputWords,
                                inputWordsPerElement, DENSITY_STAGE_VALUE_WORDS, uniqueCount,
                                defaultStateId, airStateId, invalidStateId))
                                .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
            }
            if (device == null) device = result.device();
            else requireSameDevice(device, result.device(), debugLabel + " stages");
            if (remainingConsumers.containsKey(plan.root()) || finalRoots.contains(plan.root())) {
                stageResults.put(plan.root(), result);
            }
            releaseDensityStageChildren(plan.childRoots(), remainingConsumers, stageResults, finalRoots);
            debugDensityStageCpuOracle(plan.root(), result, stageCoordinates);
            stageDebug(stageDebugEnabled, debugLabel + "-stage-complete index=" + stageIndex
                    + " root=" + plan.root() + debugStageValues(result.outputWords(), stageCoordinates));
            if ((stageIndex + 1) % DENSITY_PIPELINE_RECLAIM_INTERVAL == 0) {
                executor.reclaimPipelines();
            }
            if (deviceRecreateInterval > 0 && (stageIndex + 1) % deviceRecreateInterval == 0) {
                executor.recreateDevice();
                stageDebug(stageDebugEnabled, debugLabel + "-device-recreate index=" + stageIndex);
            }
            if (stageIndex == stopAfterStage) {
                throw new IllegalStateException("GPU candidate stopped after direct density stage "
                        + stopAfterStage + " for compiler/driver diagnostics");
            }
            stageIndex++;
        }
        for (String finalRoot : finalRoots) requireStageResult(stageResults, finalRoot);
        return stageResults;
    }

    /**
     * Compare the first element of an opt-in staged GPU result with the exact
     * captured semantic node at the same coordinate.  This is intentionally a
     * point diagnostic: it identifies the first bad stage without turning the
     * already-expensive full chunk route into a second full CPU replay.
     */
    private static void debugDensityStageCpuOracle(
            String root, VulkanWorldgenExecutor.RawResult result, int[] stageCoordinates) {
        StageCpuOracle oracle = DENSITY_STAGE_CPU_ORACLE.get();
        if (oracle == null || result == null || result.elementCount() <= 0
                || stageCoordinates == null || stageCoordinates.length < 4) return;
        if (stageCoordinates.length != Math.multiplyExact(result.elementCount(), 4)) {
            throw new IllegalStateException("Density stage CPU oracle coordinate count differs for " + root);
        }
        int[] output = result.outputWords();
        double tolerance = doubleProperty(
                "tellurium.gpuCandidate.debugDensityStageCpuOracleTolerance", 0.0);
        int exactCount = 0;
        int mismatchCount = 0;
        String firstMismatch = null;
        for (int index = 0; index < result.elementCount(); index++) {
            int x = stageCoordinates[index * 4];
            int y = stageCoordinates[index * 4 + 1];
            int z = stageCoordinates[index * 4 + 2];
            Double cpu = oracle.value(root, x, y, z);
            if (cpu == null) {
                stageDebug(true, "density-stage-cpu-oracle-skip root=" + root
                        + " point=" + x + "," + y + "," + z + " reason=node-missing");
                return;
            }
            double gpu = carrierDouble(output, index * DENSITY_STAGE_VALUE_WORDS);
            boolean exact = Double.doubleToRawLongBits(gpu) == Double.doubleToRawLongBits(cpu);
            if (exact) exactCount++;
            boolean withinTolerance = exact || (tolerance > 0.0 && Double.isFinite(gpu)
                    && Double.isFinite(cpu) && Math.abs(gpu - cpu) <= tolerance);
            if (!withinTolerance) {
                mismatchCount++;
                if (firstMismatch == null) {
                    double delta = Double.isFinite(gpu) && Double.isFinite(cpu) ? gpu - cpu : Double.NaN;
                    firstMismatch = " point=" + x + "," + y + "," + z
                            + " gpu=" + gpu + " cpu=" + cpu + " delta=" + delta;
                }
            }
        }
        String diagnostic = "density-stage-cpu-oracle root=" + root
                + " compared=" + result.elementCount() + " exact=" + exactCount
                + " mismatches=" + mismatchCount + " tolerance=" + tolerance
                + (firstMismatch == null ? "" : firstMismatch);
        stageDebug(true, diagnostic);
        if (mismatchCount > 0 && Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugDensityStageCpuOracleStop", "true"))) {
            throw new IllegalStateException("GPU density stage CPU oracle mismatch: " + diagnostic);
        }
    }

    private static String emittedNodeName(WorldgenShaderCompiler.Shader shader, ProgramNode node) {
        return shader.nodeFunctions().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("wg_node_") && entry.getValue() == node)
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private static String emittedSplineName(WorldgenShaderCompiler.Shader shader, ProgramNode.SplineNode node) {
        return shader.nodeFunctions().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("wg_spline_")
                        && entry.getValue() instanceof ProgramNode.Spline spline && spline.spline() == node)
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private static boolean canExecuteSharedSplineStage(WorldgenShaderCompiler.Shader shader,
                                                       DensityStagePlan plan) {
        if (!Boolean.getBoolean("tellurium.gpuCandidate.sharedSplineShader")
                || plan.kind() != DensityStageKind.GRAPH
                || !(shader.nodeFunctions().get(plan.root()) instanceof ProgramNode.Spline spline)
                || !(spline.spline() instanceof ProgramNode.SplineMultipoint multipoint)
                || multipoint.locations().size() < 1 || multipoint.locations().size() > 64) return false;
        String coordinate = emittedNodeName(shader, multipoint.coordinate());
        if (coordinate == null || !plan.childRoots().contains(coordinate)) return false;
        for (ProgramNode.SplineNode value : multipoint.values()) {
            if (value instanceof ProgramNode.SplineConstant) continue;
            String child = emittedSplineName(shader, value);
            if (child == null || !plan.childRoots().contains(child)) return false;
        }
        return true;
    }

    /** One cached kernel consumes GPU coordinate/knot carriers and immutable spline coefficients. */
    private static VulkanWorldgenExecutor.RawResult executeSharedSplineStage(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            DensityStagePlan plan, int[] stageCoordinates,
            Map<String, VulkanWorldgenExecutor.RawResult> stageResults,
            int defaultStateId, int airStateId, int invalidStateId, int batchSize) {
        var spline = (ProgramNode.Spline) completeShader.nodeFunctions().get(plan.root());
        var multipoint = (ProgramNode.SplineMultipoint) spline.spline();
        int count = multipoint.locations().size();
        int elements = stageCoordinates.length / 4;
        int rowWords = Math.addExact(3, Math.multiplyExact(count, 4));
        int[] coordinate = requireStageResult(stageResults,
                emittedNodeName(completeShader, multipoint.coordinate())).outputWords();
        int[][] values = new int[count][];
        long[] constants = new long[count];
        for (int knot = 0; knot < count; knot++) {
            ProgramNode.SplineNode value = multipoint.values().get(knot);
            if (value instanceof ProgramNode.SplineConstant constant) {
                // Captured immutable coefficients, not CPU-evaluated density.
                constants[knot] = Double.doubleToRawLongBits((double) constant.value());
            } else {
                values[knot] = requireStageResult(stageResults,
                        emittedSplineName(completeShader, value)).outputWords();
            }
            if (values[knot] != null && values[knot].length != Math.multiplyExact(elements, 2)) {
                throw new IllegalStateException("Shared spline knot carrier count differs for " + plan.root());
            }
        }
        if (coordinate.length != Math.multiplyExact(elements, 2)) {
            throw new IllegalStateException("Shared spline coordinate carrier count differs for " + plan.root());
        }
        int[] input = new int[Math.multiplyExact(elements, rowWords)];
        for (int element = 0; element < elements; element++) {
            int base = element * rowWords;
            input[base] = count;
            input[base + 1] = coordinate[element * 2];
            input[base + 2] = coordinate[element * 2 + 1];
            for (int knot = 0; knot < count; knot++) {
                int offset = base + 3 + knot * 4;
                input[offset] = Float.floatToRawIntBits(multipoint.locations().get(knot));
                input[offset + 1] = Float.floatToRawIntBits(multipoint.derivatives().get(knot));
                input[offset + 2] = values[knot] == null ? (int) constants[knot] : values[knot][element * 2];
                input[offset + 3] = values[knot] == null ? (int) (constants[knot] >>> 32)
                        : values[knot][element * 2 + 1];
            }
        }
        WorldgenShaderCompiler.Shader shader = SHARED_SPLINE_SHADER_CACHE.get().get(completeShader.source());
        if (shader == null) {
            String prefix = compactSource(stageSourcePrefix(completeShader.source()),
                    SharedSplineStageEmitter.helperRoots());
            shader = stageShader(completeShader, prefix + SharedSplineStageEmitter.source(), "density-shared-spline");
            SpirvNumericContract.require(shader.source(), shader.profile());
            SHARED_SPLINE_SHADER_CACHE.get().put(completeShader.source(), shader);
            writeLiveStageDiagnostic("density-shared-spline.comp", shader.source());
        }
        // This already-bounded generic arithmetic kernel has no captured graph
        // closure to retain. Match the shared noise kernels' ordinary inlining
        // policy instead of marking every software IEEE helper DontInline.
        var request = new VulkanWorldgenExecutor.RawRequest(
                shader, input, rowWords, 2, elements, defaultStateId, airStateId, invalidStateId)
                .withDontInlineFunctions(false, "");
        VulkanWorldgenExecutor.RawResult result = executor.executeRawBatched(
                request.withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        stageDebug(Boolean.getBoolean("tellurium.gpuCandidate.debugStages"),
                "density-shared-spline root=" + plan.root() + " knots=" + count + " elements=" + elements);
        if (Boolean.getBoolean("tellurium.gpuCandidate.debugStages")) {
            StringBuilder row = new StringBuilder("density-shared-spline-input root=").append(plan.root())
                    .append(" coordinate=").append(carrierDouble(input, 1));
            for (int knot = 0; knot < count; knot++) {
                int offset = 3 + knot * 4;
                row.append(" knot=").append(knot).append(':')
                        .append(Float.intBitsToFloat(input[offset])).append(',')
                        .append(Float.intBitsToFloat(input[offset + 1])).append(',')
                        .append(carrierDouble(input, offset + 2));
            }
            stageDebug(true, row.toString());
        }
        return result;
    }

    /**
     * Evaluates one captured blended-noise function as a device-only
     * fan-out/fan-in stage. Each octave remains the exact captured noise
     * function and the final blend only consumes its raw FP64 carriers. The
     * ordinary draft deliberately gives each small static group fixed literal
     * calls and a separate dispatch: the target NVIDIA driver miscompiles a
     * selector-driven module containing multiple captured-noise callers, so
     * the candidate-local sampler now specializes each retained table. The
     * default group size is one call for the exact profile and four calls for
     * the native-draft profile. The latter is a compiler-envelope experiment
     * only; larger groups remain an explicit property override until an
     * independent oracle qualifies them.
     */
    private static VulkanWorldgenExecutor.RawResult executeStagedBlendedNoise(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            String noiseFunction, int[] uniqueCoordinates, int batchSize) {
        return executeStagedBlendedNoise(executor, completeShader, noiseFunction,
                uniqueCoordinates, batchSize, null);
    }

    private static VulkanWorldgenExecutor.RawResult executeStagedBlendedNoise(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            String noiseFunction, int[] uniqueCoordinates, int batchSize,
            BlendedNoiseParameters sampleOracleParameters) {
        List<SampleSpec> sampleSpecs = blendedNoiseSampleSpecs(completeShader.source(), noiseFunction);
        String setup = blendedNoiseSetup(completeShader.source(), noiseFunction);
        int sampleLimit = integerProperty("tellurium.gpuCandidate.debugDensityBlendedNoiseSampleLimit", -1);
        if (sampleLimit == 0 || sampleLimit < -1 || sampleLimit > sampleSpecs.size()) {
            throw new IllegalArgumentException("GPU candidate debugDensityBlendedNoiseSampleLimit must be -1 or in [1, "
                    + sampleSpecs.size() + "]");
        }
        int groupLimit = integerProperty("tellurium.gpuCandidate.debugDensityBlendedNoiseGroupLimit", -1);
        if (groupLimit == 0 || groupLimit < -1) {
            throw new IllegalArgumentException(
                    "GPU candidate debugDensityBlendedNoiseGroupLimit must be -1 or positive");
        }
        boolean sampleDiagnostic = sampleLimit > 0;
        List<SampleSpec> selectedSamples = sampleDiagnostic
                ? List.copyOf(sampleSpecs.subList(0, sampleLimit)) : sampleSpecs;
        List<List<SampleSpec>> sampleGroups = sampleDiagnostic
                ? selectedSamples.stream().map(List::of).toList()
                : blendedNoiseStaticGroups(sampleSpecs, completeShader.profile());
        if (groupLimit > 0 && groupLimit > sampleGroups.size()) {
            throw new IllegalArgumentException("GPU candidate debugDensityBlendedNoiseGroupLimit must be no greater than "
                    + sampleGroups.size());
        }
        if (groupLimit > 0 && sampleDiagnostic) {
            throw new IllegalArgumentException(
                    "GPU candidate blended-noise sample and group diagnostics cannot be combined");
        }
        // Compile and dispatch each static group just in time.  Compiling all
        // groups before the first dispatch retains twenty large source trees
        // and makes a bounded diagnostic pay for work it will intentionally
        // discard.  Keeping the shaders already run is only for optional
        // source diagnostics after a complete fan-out.
        int groupsToRun = groupLimit > 0 ? groupLimit : sampleGroups.size();
        List<WorldgenShaderCompiler.Shader> sampleShaders = new ArrayList<>(groupsToRun);
        int pipelineReclaimInterval = integerProperty(
                "tellurium.gpuCandidate.blendedNoisePipelineReclaimInterval",
                BLENDED_NOISE_DEFAULT_PIPELINE_RECLAIM_INTERVAL);
        if (pipelineReclaimInterval == 0 || pipelineReclaimInterval < -1) {
            throw new IllegalArgumentException(
                    "GPU candidate blendedNoisePipelineReclaimInterval must be -1 or positive");
        }
        int deviceRecreateInterval = integerProperty(
                "tellurium.gpuCandidate.blendedNoiseDeviceRecreateInterval", -1);
        if (deviceRecreateInterval == 0 || deviceRecreateInterval < -1) {
            throw new IllegalArgumentException(
                    "GPU candidate blendedNoiseDeviceRecreateInterval must be -1 or positive");
        }
        boolean stageDebugEnabled = Boolean.parseBoolean(
                System.getProperty("tellurium.gpuCandidate.debugStages", "false"));
        stageDebug(stageDebugEnabled, "density-blended-start root=" + noiseFunction
                + " samples=" + sampleSpecs.size() + " groups=" + sampleGroups.size()
                + " uniqueCoordinates=" + uniqueCoordinates.length / 4);

        if (uniqueCoordinates.length == 0 || uniqueCoordinates.length % 4 != 0) {
            throw new IllegalArgumentException("Blended-noise stage coordinates must contain four words per element");
        }
        int uniqueCount = uniqueCoordinates.length / 4;
        DeviceCapabilities device = null;
        int[][] sampleWords = new int[sampleSpecs.size()][];
        boolean sharedBlended = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.blendedNoiseSharedShader", "false"));
        Map<String, WorldgenShaderCompiler.Shader> coordinateShaders = new HashMap<>();
        WorldgenShaderCompiler.Shader sharedSampleShader = sharedBlended
                ? stageShader(completeShader, sharedBlendedSampleSource(completeShader.source()),
                "density-blended-shared-sample") : null;
        String sampleDontInline = blendedNoiseSampleDontInlinePrefix();
        for (int group = 0; group < groupsToRun; group++) {
            List<SampleSpec> specs = sampleGroups.get(group);
            String label = sampleDiagnostic
                    ? "density-blended-sample-" + sampleSpecs.indexOf(specs.get(0))
                    + "-" + specs.get(0).function()
                    : "density-blended-samples-" + group + "-" + specs.get(0).group();
            WorldgenShaderCompiler.Shader selectedShader = sharedBlended ? sharedSampleShader : stageShader(completeShader,
                    noiseSampleSourceForSpecs(completeShader.source(), specs, setup), label);
            SpirvNumericContract.require(selectedShader.source(), selectedShader.profile());
            sampleShaders.add(selectedShader);
            int groupOutputWords = Math.multiplyExact(specs.size(), 4);
            int[] sampleCoordinates = uniqueCoordinates.clone();
            if (sampleDiagnostic) {
                SampleSpec spec = specs.get(0);
                for (int unique = 0; unique < uniqueCount; unique++) {
                    sampleCoordinates[unique * 4 + 3] = spec.scaleExponent();
                }
                stageDebug(stageDebugEnabled, "density-blended-sample-start slot="
                        + sampleSpecs.indexOf(spec) + " group=" + spec.group()
                        + " function=" + spec.function() + " elements=" + uniqueCount);
            } else {
                stageDebug(stageDebugEnabled, "density-blended-group-start group="
                        + specs.get(0).group() + " samples=" + specs.size()
                        + " elements=" + uniqueCount);
            }
            // The normal path emits a small statically selected set of calls
            // per group shader. There is no input-dependent selector, and the
            // bounded groups reduce the forty separate source compilations
            // without pushing all captured octaves through one compiler unit.
            VulkanWorldgenExecutor.RawRequest sampleRequest = new VulkanWorldgenExecutor.RawRequest(
                    selectedShader, sampleCoordinates, 4, groupOutputWords, uniqueCount)
                    .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled());
            sampleRequest = sampleDontInline.equalsIgnoreCase("NONE")
                    ? sampleRequest.withDontInlineFunctions(false, "")
                    : sampleRequest.withDontInlineFunctions(sampleDontInline);
            VulkanWorldgenExecutor.RawResult sampleResult;
            if (sharedBlended) {
                String coordinateGroup = specs.get(0).group().equals("main") ? "main" : "limit";
                WorldgenShaderCompiler.Shader coordinateShader = coordinateShaders.computeIfAbsent(
                        coordinateGroup, key -> stageShader(completeShader,
                                sharedBlendedCoordinateSource(completeShader.source(), setup, key),
                                "density-blended-shared-coordinates-" + key));
                sampleResult = executeSharedBlendedSamples(executor, completeShader, coordinateShader,
                        sharedSampleShader, specs, uniqueCoordinates, batchSize);
            } else {
                sampleResult = executor.executeRawBatched(sampleRequest, batchSize);
            }
            if (sampleResult.elementCount() != uniqueCount
                    || sampleResult.outputWordsPerElement() != groupOutputWords) {
                throw new IllegalStateException("Blended-noise sample group returned incompatible geometry: "
                        + specs.get(0).group());
            }
            if (device == null) device = sampleResult.device();
            else requireSameDevice(device, sampleResult.device(), "blended-noise sample stages");
            int[] groupWords = sampleResult.outputWords();
            if (sampleDiagnostic) {
                int sample = sampleSpecs.indexOf(specs.get(0));
                sampleWords[sample] = groupWords;
                stageDebug(stageDebugEnabled, "density-blended-sample-complete slot=" + sample
                        + " function=" + specs.get(0).function() + debugRawCarrier(groupWords));
            } else {
                for (int localSample = 0; localSample < specs.size(); localSample++) {
                    int sample = sampleSpecs.indexOf(specs.get(localSample));
                    int[] words = new int[Math.multiplyExact(uniqueCount, 4)];
                    for (int unique = 0; unique < uniqueCount; unique++) {
                        int source = unique * groupOutputWords + localSample * 4;
                        int target = unique * 4;
                        words[target] = groupWords[source];
                        words[target + 1] = groupWords[source + 1];
                    }
                    sampleWords[sample] = words;
                }
            }
            if (sampleOracleParameters != null) {
                verifyBlendedNoiseSampleGroupCpuOracle(groupWords, specs, groupOutputWords,
                        uniqueCoordinates, sampleOracleParameters, noiseFunction);
            }
            stageDebug(stageDebugEnabled, "density-blended-group-complete group="
                + specs.get(0).group() + debugRawCarrier(groupWords));
            if (pipelineReclaimInterval > 0 && (group + 1) % pipelineReclaimInterval == 0) {
                executor.reclaimPipelines();
                stageDebug(stageDebugEnabled, "density-blended-pipeline-reclaim group=" + group);
            }
            if (deviceRecreateInterval > 0 && (group + 1) % deviceRecreateInterval == 0
                    && groupLimit < 0) {
                // This is intentionally opt-in.  Some development drivers
                // retain compiler state beyond pipeline destruction; a
                // device-generation boundary is the only bounded escape
                // hatch while the sample carriers are already host-owned.
                executor.recreateDevice();
                stageDebug(stageDebugEnabled, "density-blended-device-recreate group=" + group);
            }
        }
        if (groupLimit > 0 && groupLimit < sampleGroups.size()) {
            throw new IllegalStateException("GPU blended-noise group diagnostic stopped after " + groupLimit
                    + " of " + sampleGroups.size() + " groups");
        }
        if (sampleLimit > 0 && sampleLimit < sampleSpecs.size()) {
            throw new IllegalStateException("GPU blended-noise sample diagnostic stopped after " + sampleLimit
                    + " of " + sampleSpecs.size() + " samples");
        }

        if (completeShader.profile() == NumericProfile.GPU_IEEE_BITS
                && Boolean.parseBoolean(System.getProperty(
                        "tellurium.gpuCandidate.sharedBlendedReduction", "true"))) {
            VulkanWorldgenExecutor.RawResult reduced = executeSharedBlendedReduction(executor,
                    completeShader, noiseFunction, sampleSpecs, uniqueCoordinates, sampleWords, batchSize);
            requireSameDevice(device, reduced.device(), "bounded blended-noise reduction");
            stageDebug(stageDebugEnabled, "density-blended-complete root=" + noiseFunction + " reduction=shared-staged");
            return reduced;
        }

        WorldgenShaderCompiler.Shader finalShader = stageShader(completeShader,
                blendedNoiseFinalSource(completeShader.source(), noiseFunction),
                "density-blended-final-" + noiseFunction);
        SpirvNumericContract.require(finalShader.source(), finalShader.profile());
        writeBlendedNoiseDiagnostics(noiseFunction, sampleShaders, finalShader);

        int[] finalInput = new int[Math.multiplyExact(uniqueCount, BLENDED_NOISE_INPUT_WORDS)];
        for (int unique = 0; unique < uniqueCount; unique++) {
            int coordinateWord = unique * 4;
            int target = unique * BLENDED_NOISE_INPUT_WORDS;
            System.arraycopy(uniqueCoordinates, coordinateWord, finalInput, target, 4);
            for (int sample = 0; sample < sampleWords.length; sample++) {
                int source = unique * 4;
                int value = target + 4 + sample * DENSITY_STAGE_VALUE_WORDS;
                finalInput[value] = sampleWords[sample][source];
                finalInput[value + 1] = sampleWords[sample][source + 1];
            }
        }
        VulkanWorldgenExecutor.RawResult result = executor.executeRawBatched(
                new VulkanWorldgenExecutor.RawRequest(
                        finalShader, finalInput, BLENDED_NOISE_INPUT_WORDS,
                        DENSITY_STAGE_VALUE_WORDS, uniqueCount)
                        .withDontInlineFunctions(false, "")
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        if (result.elementCount() != uniqueCount
                || result.outputWordsPerElement() != DENSITY_STAGE_VALUE_WORDS) {
            throw new IllegalStateException("Blended-noise final stage returned incompatible geometry");
        }
        if (device == null) device = result.device();
        else requireSameDevice(device, result.device(), "blended-noise final stage");
        stageDebug(stageDebugEnabled, "density-blended-complete root=" + noiseFunction);
        return result;
    }

    /** Reduces GPU octave carriers without expanding forty restoring dividers into one pipeline. */
    private static VulkanWorldgenExecutor.RawResult executeSharedBlendedReduction(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader complete,
            String root, List<SampleSpec> specs, int[] coordinates, int[][] samples, int batchSize) {
        int count = coordinates.length / 4;
        int[] input = packCanonicalBlendedInput(specs, coordinates, samples);
        var prepare = stageShader(complete,
                SharedBlendedReductionStageEmitter.prepareSource(complete.localSize()), "blended-reduce-prepare");
        SpirvNumericContract.require(prepare.source(), prepare.profile());
        writeLiveStageDiagnostic("density-blended-reduce-prepare.comp", prepare.source());
        var prepared = executor.executeRawBatched(new VulkanWorldgenExecutor.RawRequest(prepare, input,
                SharedBlendedReductionStageEmitter.SAMPLE_WORDS,
                SharedBlendedReductionStageEmitter.DIVISION_INPUT_WORDS, count)
                .withDontInlineFunctions(false, "")
                .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        var init = stageShader(complete, SharedFp64DivisionStageEmitter.initSource(complete.localSize(), 4, 6),
                "blended-reduce-div-init");
        SpirvNumericContract.require(init.source(), init.profile());
        var quotient = executeFp64DivisionHostStages(executor, complete, init, "blended-" + root,
                prepared.outputWords(), SharedBlendedReductionStageEmitter.DIVISION_INPUT_WORDS,
                count, 0, 0, 0, batchSize);
        requireSameDevice(prepared.device(), quotient.device(), "blended-noise main division");
        int[] quotientWords = quotient.outputWords();
        int[] finishInput = new int[Math.multiplyExact(count, SharedBlendedReductionStageEmitter.FINISH_WORDS)];
        for (int index = 0; index < count; index++) {
            int target = index * SharedBlendedReductionStageEmitter.FINISH_WORDS;
            System.arraycopy(input, index * SharedBlendedReductionStageEmitter.SAMPLE_WORDS,
                    finishInput, target, SharedBlendedReductionStageEmitter.SAMPLE_WORDS);
            finishInput[target + SharedBlendedReductionStageEmitter.SAMPLE_WORDS] = quotientWords[index * 2];
            finishInput[target + SharedBlendedReductionStageEmitter.SAMPLE_WORDS + 1] = quotientWords[index * 2 + 1];
        }
        var finish = stageShader(complete,
                SharedBlendedReductionStageEmitter.finishSource(complete.localSize()), "blended-reduce-finish");
        SpirvNumericContract.require(finish.source(), finish.profile());
        writeLiveStageDiagnostic("density-blended-reduce-finish.comp", finish.source());
        var result = executor.executeRawBatched(new VulkanWorldgenExecutor.RawRequest(finish, finishInput,
                SharedBlendedReductionStageEmitter.FINISH_WORDS, 2, count)
                .withDontInlineFunctions(false, "")
                .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        requireSameDevice(prepared.device(), result.device(), "blended-noise finish reduction");
        return result;
    }

    /** Reorders immutable GPU carriers only; never evaluates octave values on the host. */
    static int[] packCanonicalBlendedInput(List<SampleSpec> specs, int[] coordinates, int[][] samples) {
        Objects.requireNonNull(specs, "specs");
        Objects.requireNonNull(coordinates, "coordinates");
        Objects.requireNonNull(samples, "samples");
        if (specs.size() != BLENDED_NOISE_SAMPLE_COUNT || samples.length != BLENDED_NOISE_SAMPLE_COUNT
                || coordinates.length == 0 || coordinates.length % 4 != 0) {
            throw new IllegalArgumentException("Canonical blended reduction requires forty samples and four-word coordinate rows");
        }
        int count = coordinates.length / 4;
        int[] result = new int[Math.multiplyExact(count, SharedBlendedReductionStageEmitter.SAMPLE_WORDS)];
        for (int row = 0; row < count; row++) {
            System.arraycopy(coordinates, row * 4, result,
                    row * SharedBlendedReductionStageEmitter.SAMPLE_WORDS, 4);
        }
        boolean[] seen = new boolean[BLENDED_NOISE_SAMPLE_COUNT];
        for (int slot = 0; slot < specs.size(); slot++) {
            SampleSpec spec = Objects.requireNonNull(specs.get(slot), "spec");
            int start = switch (spec.group()) {
                case "main" -> 0;
                case "min" -> 8;
                case "max" -> 24;
                default -> throw new IllegalArgumentException("Unknown blended octave group: " + spec.group());
            };
            int maximum = start == 0 ? 8 : 16;
            int octave = spec.scaleExponent();
            if (octave < 0 || octave >= maximum || seen[start + octave]) {
                throw new IllegalArgumentException("Duplicate or out-of-range blended octave: " + spec);
            }
            seen[start + octave] = true;
            int[] words = Objects.requireNonNull(samples[slot], "sample");
            if (words.length != Math.multiplyExact(count, 4)) {
                throw new IllegalArgumentException("Blended sample geometry differs at slot " + slot);
            }
            for (int row = 0; row < count; row++) {
                int target = row * SharedBlendedReductionStageEmitter.SAMPLE_WORDS + 4 + (start + octave) * 2;
                result[target] = words[row * 4];
                result[target + 1] = words[row * 4 + 1];
            }
        }
        return result;
    }

    /** Splits the island/simplex loop into bounded neighbor work and a cheap device reduction. */
    private static VulkanWorldgenExecutor.RawResult executeStagedEndIsland(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader complete,
            String root, int[] points, int batchSize) {
        String island = firstFunctionName(functionBody(complete.source(), root),
                "\\b(wg_end_island_[0-9]+)\\(", "captured End island");
        String simplex = "wg_end_simplex_" + island.substring("wg_end_island_".length());
        record IslandPoint(int x, int z) { }
        Map<IslandPoint, Integer> ids = new LinkedHashMap<>();
        int[] pointIds = new int[points.length / 4];
        int[] scaledCoordinates = endIslandScaledCoordinates(points);
        for (int i = 0; i < pointIds.length; i++) {
            // This is coordinate preparation, not host noise/material evaluation.
            var coordinate = new IslandPoint(scaledCoordinates[i * 2], scaledCoordinates[i * 2 + 1]);
            pointIds[i] = ids.computeIfAbsent(coordinate, ignored -> ids.size());
        }
        int groups = ids.size();
        int[] neighbors = new int[Math.multiplyExact(groups, 625 * 4)];
        for (var entry : ids.entrySet()) {
            int cursor = entry.getValue() * 625 * 4;
            for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) {
                neighbors[cursor++] = entry.getKey().x();
                neighbors[cursor++] = entry.getKey().z();
                neighbors[cursor++] = dx;
                neighbors[cursor++] = dz;
            }
        }
        String neighborSource = compactSource(sourcePrefix(complete.source())
                .replace("uint outputStateIds[]", "uint outputBits[]"), Set.of(simplex,
                "wg_fp64_from_int", "wg_fp64_add", "wg_fp64_mul", "wg_fp64_less", "wg_fp64_finite",
                "wg_fp32_from_int", "wg_fp32_abs", "wg_fp32_add", "wg_fp32_sub", "wg_fp32_mul",
                "wg_fp32_less", "wg_fp32_sqrt", "wg_fp32_min", "wg_fp32_max", "wg_fp32_finite"));
        neighborSource += """
                // Exact nonnegative finite FP32 remainder by integer divisor 13.
                // Integer mantissa modular reduction avoids rounded quotient subtraction.
                uint wg_end_remainder13(uint bits) {
                    if (wg_fp32_less(bits, 0x41500000u)) return bits;
                    int exponent = int((bits >> 23u) & 255u) - 127;
                    uint mantissa = (bits & 0x007fffffu) | 0x00800000u;
                    if (exponent >= 23) {
                        uint remainder = mantissa %% 13u;
                        for (int shift = 0; shift < exponent - 23; shift++) remainder = (remainder * 2u) %% 13u;
                        return wg_fp32_from_int(int(remainder));
                    }
                    uint remainder = mantissa %% (13u << uint(23 - exponent));
                    uint scale = uint(exponent - 23 + 127) << 23u;
                    return wg_fp32_mul(wg_fp32_from_int(int(remainder)), scale);
                }
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uint base = index * 4u;
                    int x = int(inputBits[base]), z = int(inputBits[base + 1u]);
                    int dx = int(inputBits[base + 2u]), dz = int(inputBits[base + 3u]);
                    int gridX = x / 2, gridZ = z / 2;
                    int islandX = gridX + dx, islandZ = gridZ + dz;
                    uvec2 ix = wg_fp64_from_int(islandX), iz = wg_fp64_from_int(islandZ);
                    uvec2 radius = wg_fp64_add(wg_fp64_mul(ix, ix), wg_fp64_mul(iz, iz));
                    uint height = 0xc2c80000u;
                    if (wg_fp64_less(uvec2(0u, 0x40b00000u), radius)) {
                        uvec2 noise = %s(ix, iz);
                        if (!wg_fp64_finite(noise)) wg_failed = true;
                        if (wg_fp64_less(noise, uvec2(0xc0000000u, 0xbfecccccu))) {
                            uint scaleProduct = wg_fp32_add(
                                    wg_fp32_mul(wg_fp32_abs(wg_fp32_from_int(islandX)), 0x4556f000u),
                                    wg_fp32_mul(wg_fp32_abs(wg_fp32_from_int(islandZ)), 0x43130000u));
                            uint scale = wg_fp32_add(wg_end_remainder13(scaleProduct), 0x41100000u);
                            // GLSL signed %% is undefined for negative operands.
                            // Subtract the truncating quotient to preserve Java's signed remainder.
                            uint localX = wg_fp32_from_int(x - gridX * 2 - dx * 2);
                            uint localZ = wg_fp32_from_int(z - gridZ * 2 - dz * 2);
                            uint distance = wg_fp32_sqrt(wg_fp32_add(
                                    wg_fp32_mul(localX, localX), wg_fp32_mul(localZ, localZ)));
                            height = wg_fp32_sub(0x42c80000u, wg_fp32_mul(distance, scale));
                            height = wg_fp32_max(0xc2c80000u, wg_fp32_min(0x42a00000u, height));
                        }
                    }
                    outputBits[index * 2u] = height;
                    outputBits[index * 2u + 1u] = (wg_failed || !wg_fp32_finite(height)) ? 2u : 0u;
                }
                """.formatted(simplex);
        EndIslandSharedStage sharedStage = Boolean.getBoolean("tellurium.gpuCandidate.endIslandSharedShader")
                ? sharedEndIslandStage(neighborSource) : null;
        if (sharedStage != null) neighborSource = sharedStage.source();
        var neighborShader = stageShader(complete, neighborSource, "end-island-neighbors");
        writeLiveStageDiagnostic("end-island-neighbors.comp", neighborShader.source());
        var sampled = executor.executeRawBatched(new VulkanWorldgenExecutor.RawRequest(
                neighborShader, sharedStage == null ? neighbors : sharedStage.inputRows(neighbors), 4, 2, groups * 625)
                .withDontInlineFunctions("wg_end_")
                .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        int[] sampledWords = sampled.outputWords();
        int[] combineInput = new int[groups * 4 + sampledWords.length];
        for (var entry : ids.entrySet()) {
            int base = entry.getValue() * 4;
            combineInput[base] = entry.getKey().x();
            combineInput[base + 1] = entry.getKey().z();
            combineInput[base + 2] = entry.getValue();
        }
        System.arraycopy(sampledWords, 0, combineInput, groups * 4, sampledWords.length);
        String combineSource = compactSource(sourcePrefix(complete.source())
                .replace("uint outputStateIds[]", "uint outputBits[]"), Set.of(
                "wg_i32_add", "wg_i32_mul", "wg_fp32_from_int", "wg_fp32_sqrt", "wg_fp32_mul",
                "wg_fp32_sub", "wg_fp32_min", "wg_fp32_max", "wg_fp64_from_fp32",
                "wg_fp64_sub", "wg_fp64_mul", "wg_fp64_qnan", "wg_fp64_finite"));
        combineSource += """
                // wg_shared_end_island_reduction: bounded reusable island carrier kernel
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uint base = index * 4u;
                    uint x = inputBits[base], z = inputBits[base + 1u];
                    uint distance = wg_i32_add(wg_i32_mul(x, x), wg_i32_mul(z, z));
                    uint height = wg_fp32_sub(0x42c80000u, wg_fp32_mul(
                            wg_fp32_sqrt(wg_fp32_from_int(int(distance))), 0x41000000u));
                    height = wg_fp32_max(0xc2c80000u, wg_fp32_min(0x42a00000u, height));
                    uint sampleBase = dispatch.count * 4u + inputBits[base + 2u] * 1250u;
                    for (uint neighbor = 0u; neighbor < 625u; neighbor++) {
                        uint offset = sampleBase + neighbor * 2u;
                        height = wg_fp32_max(height, inputBits[offset]);
                        if (inputBits[offset + 1u] != 0u) wg_failed = true;
                    }
                    // Division by 128 is exact power-of-two scaling in this bounded range.
                    uvec2 value = wg_fp64_mul(wg_fp64_sub(wg_fp64_from_fp32(height),
                            uvec2(0u, 0x40200000u)), uvec2(0u, 0x3f800000u));
                    if ((distance & 0x80000000u) != 0u || wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    outputBits[index * 2u] = value.x;
                    outputBits[index * 2u + 1u] = value.y;
                }
                """;
        var combineShader = stageShader(complete, combineSource, "end-island-reduction");
        writeLiveStageDiagnostic("end-island-reduction.comp", combineShader.source());
        var combined = executor.executeRawBatched(new VulkanWorldgenExecutor.RawRequest(
                combineShader, combineInput, 4, 2, groups)
                .withDontInlineFunctions(false, "")
                .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        requireSameDevice(sampled.device(), combined.device(), "End island neighbor/reduction");
        int[] values = combined.outputWords();
        int[] output = new int[pointIds.length * 2];
        for (int i = 0; i < pointIds.length; i++) {
            output[i * 2] = values[pointIds[i] * 2];
            output[i * 2 + 1] = values[pointIds[i] * 2 + 1];
        }
        stageDebug(Boolean.getBoolean("tellurium.gpuCandidate.debugStages"),
                "density-end-island-staged root=" + root + " points=" + pointIds.length
                        + " uniqueXZ=" + groups + " neighbors=" + groups * 625);
        if (sharedStage != null) {
            stageDebug(Boolean.getBoolean("tellurium.gpuCandidate.debugStages"),
                    "density-end-island-shared root=" + root + " metadataWords=256 metadataLayout=batch-suffix"
                            + " samplerShader=" + sampled.shaderHash() + " samplerSpirv=" + sampled.spirvHash()
                            + " permutation=" + Hashes.sha256(Arrays.toString(sharedStage.permutationWords)
                                    .getBytes(StandardCharsets.UTF_8)));
        }
        return new VulkanWorldgenExecutor.RawResult(output, combined.device(), combined.shaderHash(),
                combined.spirvHash(), pointIds.length, 2);
    }

    /** Immutable captured permutation metadata; no host-evaluated island/noise values. */
    record EndIslandSharedStage(String source, int[] permutationWords) {
        EndIslandSharedStage {
            if (source == null || source.isBlank() || permutationWords == null || permutationWords.length != 256) {
                throw new IllegalArgumentException("Invalid shared End island source or permutation metadata");
            }
            boolean[] seen = new boolean[256];
            for (int word : permutationWords) {
                if (word < 0 || word > 255 || seen[word]) {
                    throw new IllegalArgumentException("End island metadata must be a complete byte permutation");
                }
                seen[word] = true;
            }
            permutationWords = permutationWords.clone();
        }

        @Override
        public int[] permutationWords() { return permutationWords.clone(); }

        int[] inputRows(int[] rows) {
            if (rows == null || rows.length == 0 || rows.length % 4 != 0) {
                throw new IllegalArgumentException("Shared End island inputs require nonempty four-word rows");
            }
            int[] input = Arrays.copyOf(rows, Math.addExact(rows.length, permutationWords.length));
            System.arraycopy(permutationWords, 0, input, rows.length, permutationWords.length);
            return input;
        }
    }

    /** Preserves the emitted math verbatim, canonicalizing only names and table-load ABI. */
    static EndIslandSharedStage sharedEndIslandStage(String source) {
        if (source == null) throw new IllegalArgumentException("Missing captured End island stage source");
        Matcher declaration = Pattern.compile("(?s)\\bconst\\s+uint\\s+(wg_end_perm_([0-9]+))\\s*"
                + "\\[\\s*256\\s*\\]\\s*=\\s*uint\\s*\\[\\s*256\\s*\\]\\s*\\((.*?)\\)\\s*;")
                .matcher(source);
        if (!declaration.find()) throw new UnsupportedOperationException("Missing captured 256-byte End permutation");
        String name = declaration.group(1), id = declaration.group(2);
        int start = declaration.start(), end = declaration.end();
        String[] literals = declaration.group(3).split(",", -1);
        if (literals.length != 256) throw new IllegalArgumentException("End permutation must contain 256 byte literals");
        int[] words = new int[256];
        for (int index = 0; index < words.length; index++) words[index] = parseNoiseTableByte(literals[index], name, index);
        if (declaration.find()) throw new UnsupportedOperationException("Ambiguous captured End permutation declarations");
        Map<String, GlslFunction> functions = parseFunctions(source);
        for (String kind : List.of("gradient", "corner", "simplex")) {
            if (!functions.containsKey("wg_end_" + kind + "_" + id)) {
                throw new UnsupportedOperationException("Missing captured End " + kind + " function for " + name);
            }
        }
        String lookup = """
                // wg_shared_end_permutation: immutable metadata follows four-word neighbor rows.
                uint wg_end_permutation_lookup(uint index) {
                    if (index >= 256u) { wg_failed = true; return 0u; }
                    return inputBits[dispatch.count * 4u + index];
                }
                """;
        String shared = replaceNoiseTableLookups(source.substring(0, start) + lookup + source.substring(end), name);
        shared = shared.replaceAll("\\b" + Pattern.quote(name + "_lookup") + "\\b", "wg_end_permutation_lookup")
                .replaceAll("\\bwg_end_(gradient|corner|simplex)_" + id + "\\b", "wg_end_$1_shared");
        if (Pattern.compile("\\bwg_end_(?:perm|gradient|corner|simplex)_[0-9]+\\b").matcher(shared).find()) {
            throw new UnsupportedOperationException("Unmapped captured End table/function in shared stage");
        }
        shared = replaceStageAbiOnce(shared, "wg_failed = false;\n    uint base = index * 4u;", """
                wg_failed = false;
                    if (inputBits.length() != dispatch.count * 4u + 256u) {
                        outputBits[index * 2u] = 0xc2c80000u;
                        outputBits[index * 2u + 1u] = 2u;
                        return;
                    }
                    uint base = index * 4u;""");
        return new EndIslandSharedStage(shared, words);
    }

    static int[] endIslandScaledCoordinates(int[] points) {
        if (points == null || points.length == 0 || points.length % 4 != 0) {
            throw new IllegalArgumentException("End island coordinates require nonempty four-word rows");
        }
        int[] scaled = new int[points.length / 2];
        for (int i = 0; i < points.length / 4; i++) {
            // End uses Java truncation, not the floor-div lattice convention.
            scaled[i * 2] = points[i * 4] / 8;
            scaled[i * 2 + 1] = points[i * 4 + 2] / 8;
        }
        return scaled;
    }

    /** Reuse two coordinate kernels and one ImprovedNoise kernel for all forty captured octaves. */
    private static VulkanWorldgenExecutor.RawResult executeSharedBlendedSamples(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader complete,
            WorldgenShaderCompiler.Shader coordinates, WorldgenShaderCompiler.Shader sampler,
            List<SampleSpec> specs, int[] points, int batchSize) {
        int count = points.length / 4;
        int stride = Math.multiplyExact(specs.size(), 4);
        int[] output = new int[Math.multiplyExact(count, stride)];
        VulkanWorldgenExecutor.RawResult last = null;
        for (int slot = 0; slot < specs.size(); slot++) {
            SampleSpec spec = specs.get(slot);
            long scale = Double.doubleToRawLongBits(Math.scalb(1.0, -spec.scaleExponent()));
            int[] coordinateInput = new int[Math.multiplyExact(count, 6)];
            for (int i = 0; i < count; i++) {
                System.arraycopy(points, i * 4, coordinateInput, i * 6, 4);
                coordinateInput[i * 6 + 4] = (int) scale;
                coordinateInput[i * 6 + 5] = (int) (scale >>> 32);
            }
            var prepared = executor.executeRawBatched(new VulkanWorldgenExecutor.RawRequest(
                    coordinates, coordinateInput, 6, 10, count)
                    .withDontInlineFunctions(false, "")
                    .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
            int[] rows = prepared.outputWords();
            int[] metadata = sharedBlendedMetadata(complete.source(), spec.function());
            int[] input = java.util.Arrays.copyOf(rows, Math.addExact(rows.length, metadata.length));
            System.arraycopy(metadata, 0, input, rows.length, metadata.length);
            var sampled = executor.executeRawBatched(new VulkanWorldgenExecutor.RawRequest(
                    sampler, input, 10, 4, count)
                    .withDontInlineFunctions(false, "")
                    .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
            requireSameDevice(prepared, sampled, "shared blended coordinate/sample");
            if (last != null) requireSameDevice(last, sampled, "shared blended octaves");
            int[] words = sampled.outputWords();
            for (int i = 0; i < count; i++) System.arraycopy(words, i * 4, output, i * stride + slot * 4, 4);
            stageDebug(Boolean.parseBoolean(System.getProperty("tellurium.gpuCandidate.debugStages", "false")),
                    "density-blended-shared-sample-complete function=" + spec.function()
                            + " group=" + spec.group() + " octave=" + spec.scaleExponent()
                            + " elements=" + count + " metadataLayout=batch-suffix");
            last = sampled;
        }
        return new VulkanWorldgenExecutor.RawResult(output, last.device(),
                last.shaderHash(), last.spirvHash(), count, stride);
    }

    static String sharedBlendedCoordinateSource(String source, String setup, String group) {
        if (!group.equals("main") && !group.equals("limit")) {
            throw new IllegalArgumentException("Unknown blended coordinate group: " + group);
        }
        String prefix = compactSource(sourcePrefix(source)
                .replace("uint outputStateIds[]", "uint outputBits[]")
                .replace("uint base = index * 4u;", "uint base = index * 6u;"),
                Set.of("wg_point", "wg_fp64_from_int", "wg_fp64_mul", "wg_fp64_div",
                        "wg_noise_wrap", "wg_fp64_qnan", "wg_fp64_finite"));
        List<String> arguments = splitTopLevelArguments(noiseSampleArguments(
                group.equals("main") ? "main" : "min", "scale"));
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < arguments.size(); i++) {
            output.append("uvec2 arg").append(i).append(" = ").append(arguments.get(i)).append(";\n")
                    .append("if (wg_failed || !wg_fp64_finite(arg").append(i)
                    .append(")) arg").append(i).append(" = wg_fp64_qnan();\n")
                    .append("outputBits[index * 10u + ").append(i * 2).append("u] = arg").append(i).append(".x;\n")
                    .append("outputBits[index * 10u + ").append(i * 2 + 1).append("u] = arg").append(i).append(".y;\n");
        }
        return prefix + """
                // wg_shared_blended_coordinates: retained across ordinary stage reclamation.
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    %s
                    uvec2 scale = uvec2(inputBits[index * 6u + 4u], inputBits[index * 6u + 5u]);
                    %s
                }
                """.formatted(setup, output);
    }

    static String sharedBlendedSampleSource(String source) {
        String kernel = """
                uvec2 wg_shared_blended_value(uint base) {
                    uint metadata = dispatch.count * 10u;
                    return wg_shared_noise_sample(
                            uvec2(inputBits[base], inputBits[base + 1u]),
                            uvec2(inputBits[base + 2u], inputBits[base + 3u]),
                            uvec2(inputBits[base + 4u], inputBits[base + 5u]),
                            uvec2(inputBits[metadata], inputBits[metadata + 1u]),
                            uvec2(inputBits[metadata + 2u], inputBits[metadata + 3u]),
                            uvec2(inputBits[metadata + 4u], inputBits[metadata + 5u]),
                            uvec2(inputBits[base + 6u], inputBits[base + 7u]),
                            uvec2(inputBits[base + 8u], inputBits[base + 9u]),
                            inputBits[metadata + 6u] != 0u, metadata + 7u);
                }
                """;
        String prefix = compactSource(stageSourcePrefix(source) + sharedPerlinSamplerSource(source) + kernel,
                Set.of("wg_shared_blended_value", "wg_fp64_finite", "wg_fp64_qnan"));
        return prefix + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uvec2 value = wg_shared_blended_value(index * 10u);
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    outputBits[index * 4u] = value.x;
                    outputBits[index * 4u + 1u] = value.y;
                    outputBits[index * 4u + 2u] = 0u;
                    outputBits[index * 4u + 3u] = 0u;
                }
                """;
    }

    /** Extract constants only; GPU-produced sampler controls are never evaluated on the host. */
    static int[] sharedBlendedMetadata(String source, String function) {
        Map<String, GlslFunction> functions = parseFunctions(source);
        GlslFunction legacy = functions.get(function);
        if (legacy == null) throw new UnsupportedOperationException("Missing legacy octave: " + function);
        String body = functionBody(source, legacy);
        Matcher wrapper = Pattern.compile("^\\s*return\\s+(wg_noise_smeared_[0-9]+)\\(x, y, z, yScale, yMax\\);\\s*$").matcher(body);
        if (!wrapper.matches()) throw new UnsupportedOperationException("Unknown legacy octave wrapper: " + function);
        GlslFunction smeared = functions.get(wrapper.group(1));
        if (smeared == null) throw new UnsupportedOperationException("Missing smeared octave: " + function);
        body = functionBody(source, smeared);
        Matcher sample = Pattern.compile("^\\s*return\\s+noise_sample\\s*\\(").matcher(body);
        if (!sample.find()) throw new UnsupportedOperationException("Unknown smeared sampler: " + function);
        int open = body.indexOf('(', sample.start());
        int close = matchingParenthesis(body, open);
        List<String> args = splitTopLevelArguments(body.substring(open + 1, close));
        if (args.size() != 10 || !args.get(0).equals("x") || !args.get(1).equals("y")
                || !args.get(2).equals("z") || !args.get(6).equals("yScale") || !args.get(7).equals("yMax")
                || !body.substring(close + 1).strip().equals(";")) {
            throw new UnsupportedOperationException("Legacy ImprovedNoise ABI changed: " + function);
        }
        int[] metadata = new int[71];
        for (int i = 0; i < 3; i++) {
            int[] offset = parseUvec2Literal(args.get(i + 3), function + " offset " + i);
            System.arraycopy(offset, 0, metadata, i * 2, 2);
        }
        metadata[6] = parseBooleanLiteral(args.get(8), function + " smear") ? 1 : 0;
        String name = args.get(9).strip();
        Matcher declaration = Pattern.compile("(?s)\\bconst\\s+uint\\s+" + Pattern.quote(name)
                + "\\[(64|256)\\]\\s*=\\s*uint\\[\\1\\]\\((.*?)\\)\\s*;").matcher(source);
        if (!declaration.find()) throw new UnsupportedOperationException("Missing blended permutation: " + name);
        int[] table;
        if (declaration.group(1).equals("64")) {
            table = parsePermutationTable(declaration.group(), name, function);
        } else {
            String[] bytes = declaration.group(2).split(",", -1);
            if (bytes.length != 256) throw new IllegalArgumentException("Blended permutation must have 256 bytes");
            table = new int[64];
            for (int i = 0; i < bytes.length; i++) {
                table[i / 4] |= parseNoiseTableByte(bytes[i], name, i) << ((i % 4) * 8);
            }
        }
        System.arraycopy(table, 0, metadata, 7, table.length);
        return metadata;
    }

    /**
     * Compares the fan-out/fan-in blended-noise stage with the same captured
     * function executed as one compact GPU module.  This is a bounded draft
     * diagnostic: it never feeds a result into generation and exists only to
     * distinguish a sample ABI bug from a larger graph-stage bug.
     */
    private static void requireDensityBlendedNoiseDeviceOracle(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            String noiseFunction, int[] coordinates, int defaultStateId, int airStateId,
            int invalidStateId, int batchSize) {
        int[] coordinateInput = coordinateWords(coordinates);
        BlendedNoiseParameters blendedNoiseParameters = completeShader.blendedNoiseFunctions().get(noiseFunction);
        if (blendedNoiseParameters == null) {
            throw new IllegalStateException("Shader metadata has no captured parameters for blended-noise function: "
                    + noiseFunction);
        }
        VulkanWorldgenExecutor.RawResult staged = executeStagedBlendedNoise(
                executor, completeShader, noiseFunction, coordinateInput, batchSize,
                blendedNoiseParameters);
        verifyBlendedNoiseCpuOracle(staged, coordinates, blendedNoiseParameters, noiseFunction);
        WorldgenShaderCompiler.Shader monolithicShader = stageShader(completeShader,
                densityStageSource(completeShader.source(), noiseFunction),
                "density-blended-noise-monolithic-" + noiseFunction);
        SpirvNumericContract.require(monolithicShader.source(), monolithicShader.profile());
        VulkanWorldgenExecutor.RawResult monolithic = executor.executeRawBatched(
                densityStageRequest(new VulkanWorldgenExecutor.RawRequest(
                        monolithicShader, coordinateInput, 4, DENSITY_STAGE_VALUE_WORDS,
                        coordinateInput.length / 4, defaultStateId, airStateId, invalidStateId))
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        if (staged.elementCount() != monolithic.elementCount()
                || staged.outputWordsPerElement() != DENSITY_STAGE_VALUE_WORDS
                || monolithic.outputWordsPerElement() != DENSITY_STAGE_VALUE_WORDS) {
            throw new IllegalStateException("Blended-noise device oracle returned incompatible geometry: "
                    + noiseFunction);
        }
        int[] stagedWords = staged.outputWords();
        int[] monolithicWords = monolithic.outputWords();
        for (int index = 0; index < staged.elementCount(); index++) {
            int word = index * DENSITY_STAGE_VALUE_WORDS;
            if (stagedWords[word] != monolithicWords[word]
                    || stagedWords[word + 1] != monolithicWords[word + 1]) {
                long stagedBits = (Integer.toUnsignedLong(stagedWords[word + 1]) << 32)
                        | Integer.toUnsignedLong(stagedWords[word]);
                long monolithicBits = (Integer.toUnsignedLong(monolithicWords[word + 1]) << 32)
                        | Integer.toUnsignedLong(monolithicWords[word]);
                throw new IllegalStateException("Blended-noise device oracle differs for " + noiseFunction
                        + " at index " + index + ": staged=" + Double.longBitsToDouble(stagedBits)
                        + " monolithic=" + Double.longBitsToDouble(monolithicBits));
            }
        }
        stageDebug(true, "density-blended-device-oracle root=" + noiseFunction
                + " elements=" + staged.elementCount() + " exact=true");
    }

    private static void verifyBlendedNoiseSampleGroupCpuOracle(
            int[] groupWords, List<SampleSpec> specs, int groupOutputWords,
            int[] uniqueCoordinates, BlendedNoiseParameters parameters, String noiseFunction) {
        int uniqueCount = uniqueCoordinates.length / 4;
        NoiseEvaluator evaluator = new NoiseEvaluator();
        for (int sample = 0; sample < specs.size(); sample++) {
            SampleSpec spec = specs.get(sample);
            for (int index = 0; index < uniqueCount; index++) {
                int source = index * groupOutputWords + sample * 4;
                long bits = (Integer.toUnsignedLong(groupWords[source + 1]) << 32)
                        | Integer.toUnsignedLong(groupWords[source]);
                double actual = Double.longBitsToDouble(bits);
                int coordinate = index * 4;
                double expected = evaluator.sampleBlendedSample(parameters, spec.group(),
                        spec.scaleExponent(), uniqueCoordinates[coordinate],
                        uniqueCoordinates[coordinate + 1], uniqueCoordinates[coordinate + 2]);
                double delta = Math.abs(actual - expected);
                double tolerance = 1.0e-10 * Math.max(1.0, Math.max(Math.abs(actual), Math.abs(expected)));
                stageDebug(true, "density-blended-sample-cpu-oracle root=" + noiseFunction
                        + " group=" + spec.group() + " octave=" + spec.scaleExponent()
                        + " index=" + index + " expected=" + expected + " actual=" + actual
                        + " delta=" + delta + " exact=" + (delta <= tolerance));
                if (!Double.isFinite(actual) || !Double.isFinite(expected) || delta > tolerance) {
                    throw new IllegalStateException("Blended-noise sample differs from CPU for " + noiseFunction
                            + " group=" + spec.group() + " octave=" + spec.scaleExponent()
                            + " at index " + index + ": gpu=" + actual + " cpu=" + expected
                            + " delta=" + delta);
                }
            }
        }
    }

    private static void verifyBlendedNoiseCpuOracle(VulkanWorldgenExecutor.RawResult staged,
                                                    int[] coordinates,
                                                    BlendedNoiseParameters parameters,
                                                    String noiseFunction) {
        if (coordinates.length != staged.elementCount() * 3) {
            throw new IllegalStateException("Blended-noise CPU oracle coordinate count does not match staged output: "
                    + noiseFunction);
        }
        NoiseEvaluator evaluator = new NoiseEvaluator();
        int[] words = staged.outputWords();
        for (int index = 0; index < staged.elementCount(); index++) {
            int coordinate = index * 3;
            int word = index * DENSITY_STAGE_VALUE_WORDS;
            long bits = (Integer.toUnsignedLong(words[word + 1]) << 32)
                    | Integer.toUnsignedLong(words[word]);
            double actual = Double.longBitsToDouble(bits);
            double expected = evaluator.sampleBlended(parameters,
                    coordinates[coordinate], coordinates[coordinate + 1], coordinates[coordinate + 2]);
            double delta = Math.abs(actual - expected);
            double tolerance = 1.0e-10 * Math.max(1.0, Math.max(Math.abs(actual), Math.abs(expected)));
            stageDebug(true, "density-blended-cpu-oracle root=" + noiseFunction
                    + " index=" + index + " expected=" + expected + " staged=" + actual
                    + " delta=" + delta + " exact=" + (delta <= tolerance));
            if (!Double.isFinite(actual) || !Double.isFinite(expected) || delta > tolerance) {
                throw new IllegalStateException("Blended-noise staged result differs from CPU for " + noiseFunction
                        + " at index " + index + ": staged=" + actual + " cpu=" + expected
                        + " delta=" + delta);
            }
        }
    }

    private static void requireSameDevice(VulkanWorldgenExecutor.RawResult first,
                                          VulkanWorldgenExecutor.RawResult second,
                                          String stages) {
        requireSameDevice(first.device(), second.device(), stages);
    }

    private static void requireSameDevice(DeviceCapabilities first, DeviceCapabilities second,
                                          String stages) {
        if (!first.equals(second)) {
            throw new IllegalStateException("Vulkan device provenance changed between " + stages);
        }
    }

    private static int[] coordinateWords(int[] coordinates) {
        if (coordinates.length == 0 || coordinates.length % 3 != 0) {
            throw new IllegalArgumentException("Staged density coordinates must contain XYZ triples");
        }
        int elementCount = coordinates.length / 3;
        int[] words = new int[Math.multiplyExact(elementCount, 4)];
        for (int index = 0; index < elementCount; index++) {
            int source = index * 3;
            int target = index * 4;
            words[target] = coordinates[source];
            words[target + 1] = coordinates[source + 1];
            words[target + 2] = coordinates[source + 2];
        }
        return words;
    }

    private static int[] tripleCoordinates(int[] coordinateWords) {
        if (coordinateWords.length == 0 || coordinateWords.length % 4 != 0) {
            throw new IllegalArgumentException("Density coordinate words must contain four words per element");
        }
        int elementCount = coordinateWords.length / 4;
        int[] coordinates = new int[Math.multiplyExact(elementCount, 3)];
        for (int index = 0; index < elementCount; index++) {
            int source = index * 4;
            int target = index * 3;
            coordinates[target] = coordinateWords[source];
            coordinates[target + 1] = coordinateWords[source + 1];
            coordinates[target + 2] = coordinateWords[source + 2];
        }
        return coordinates;
    }

    private static int[] densityInterpolationFractionWords(int[] coordinateWords) {
        return densityInterpolationFractionWords(coordinateWords,
                DEFAULT_DENSITY_INTERPOLATION_GEOMETRY);
    }

    private static int[] densityInterpolationFractionWords(
            int[] coordinateWords, DensityInterpolationGeometry geometry) {
        if (coordinateWords.length == 0 || coordinateWords.length % 4 != 0) {
            throw new IllegalArgumentException("Density interpolation coordinates must contain four words per element");
        }
        int elementCount = coordinateWords.length / 4;
        int[] fractions = new int[Math.multiplyExact(elementCount, DENSITY_STAGE_CORNER_FRACTION_WORDS)];
        for (int index = 0; index < elementCount; index++) {
            int coordinate = index * 4;
            int target = index * DENSITY_STAGE_CORNER_FRACTION_WORDS;
            putCarrier(fractions, target,
                    cellFractionBits(coordinateWords[coordinate], geometry.xCellSize()));
            putCarrier(fractions, target + DENSITY_STAGE_VALUE_WORDS,
                    cellFractionBits(coordinateWords[coordinate + 1], geometry.yCellSize()));
            putCarrier(fractions, target + 2 * DENSITY_STAGE_VALUE_WORDS,
                    cellFractionBits(coordinateWords[coordinate + 2], geometry.zCellSize()));
        }
        return fractions;
    }

    private static long cellFractionBits(int coordinate, int cellSize) {
        int low = cellLow(coordinate, cellSize);
        return Double.doubleToRawLongBits((double) (coordinate - low) / cellSize);
    }

    private static void putCarrier(int[] words, int offset, long bits) {
        words[offset] = (int) bits;
        words[offset + 1] = (int) (bits >>> 32);
    }

    private static int densityCornerStageInputWords(List<String> children) {
        return Math.addExact(DENSITY_STAGE_CORNER_VALUE_BASE_WORDS,
                Math.multiplyExact(children.size(), DENSITY_STAGE_CORNER_VALUE_WORDS));
    }

    /**
     * Candidate-only interpolation helper.  Minecraft's captured density
     * lattice uses cell sizes four and eight, so these fractions are exact
     * binary64 constants.  Avoiding a full restoring divide for the three
     * coordinate fractions keeps the small interpolation parent inside the
     * target driver's reliable instruction envelope.
     */
    private static String replaceCandidateCellFraction(String source) {
        String marker = "fraction = wg_fp64_div(wg_fp64_from_int(remainder), wg_fp64_from_int(cellSize));";
        if (!source.contains(marker)) return source;
        String helper = """
                uvec2 wg_stage_cell_fraction(int remainder, int cellSize) {
                    if (remainder == 0) return uvec2(0u);
                    if (cellSize == 2 && remainder == 1) return uvec2(0u, 0x3fe00000u);
                    if (cellSize == 4) {
                        if (remainder == 1) return uvec2(0u, 0x3fd00000u);
                        if (remainder == 2) return uvec2(0u, 0x3fe00000u);
                        if (remainder == 3) return uvec2(0u, 0x3fe80000u);
                    }
                    if (cellSize == 8) {
                        if (remainder == 1) return uvec2(0u, 0x3fc00000u);
                        if (remainder == 2) return uvec2(0u, 0x3fd00000u);
                        if (remainder == 3) return uvec2(0u, 0x3fd80000u);
                        if (remainder == 4) return uvec2(0u, 0x3fe00000u);
                        if (remainder == 5) return uvec2(0u, 0x3fe40000u);
                        if (remainder == 6) return uvec2(0u, 0x3fe80000u);
                        if (remainder == 7) return uvec2(0u, 0x3fec0000u);
                    }
                    return uvec2(0u, 0x7ff80000u);
                }

                """;
        String transformed = source.replace(marker,
                "fraction = wg_stage_cell_fraction(remainder, cellSize);");
        int firstFunction = firstGeneratedFunctionStart(transformed);
        return transformed.substring(0, firstFunction) + helper + transformed.substring(firstFunction);
    }

    /** Finds the first generated wg_* function without reparsing the whole capture. */
    private static int firstGeneratedFunctionStart(String source) {
        List<String> returnTypes = List.of("void", "bool", "uint", "uvec2", "uvec4", "ivec3", "float", "int");
        int lineStart = 0;
        while (lineStart < source.length()) {
            int lineEnd = source.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = source.length();
            int cursor = lineStart;
            while (cursor < lineEnd && (source.charAt(cursor) == ' ' || source.charAt(cursor) == '\t')) cursor++;
            for (String returnType : returnTypes) {
                int nameStart = cursor + returnType.length();
                if (source.startsWith(returnType, cursor)
                        && nameStart < lineEnd
                        && (source.charAt(nameStart) == ' ' || source.charAt(nameStart) == '\t')) {
                    while (nameStart < lineEnd
                            && (source.charAt(nameStart) == ' ' || source.charAt(nameStart) == '\t')) nameStart++;
                    if (source.startsWith("wg_", nameStart)
                            && source.indexOf('(', nameStart + 3) >= 0
                            && source.indexOf('(', nameStart + 3) < lineEnd) {
                        return cursor;
                    }
                }
            }
            lineStart = lineEnd + 1;
        }
        throw new IllegalArgumentException("Generated shader contains no wg_* function");
    }

    private static List<String> densityChildren(String source, String root) {
        Map<String, GlslFunction> functions = parseFunctions(source);
        return functionCalls(source, functions).getOrDefault(root, Set.of()).stream()
                .filter(name -> isDensityFunction(name) || isStageableBlendedNoiseFunction(name))
                .sorted()
                .toList();
    }

    private static boolean isDensityInterpolationFunction(String source, String root) {
        return isDensityInterpolationFunction(source, root, parseFunctions(source));
    }

    private static boolean isDensityInterpolationFunction(String source, String root,
                                                           Map<String, GlslFunction> functions) {
        if (!isDensityFunction(root)) return false;
        GlslFunction function = functions.get(root);
        // Spline nodes are density functions too, but their captured ABI is
        // usually uint (the FP32 carrier), not the uvec2 FP64 carrier used by
        // the material-input interpolation boundary.  Do not ask the
        // uvec2-only helper to parse those functions as if they were FP64.
        if (function == null || !"uvec2".equals(function.returnType())) return false;
        String body = functionBody(source, function);
        return body.contains("wg_cell_axis64(") && body.contains("p000");
    }

    static VulkanWorldgenExecutor.RawRequest densityStageRequest(
            VulkanWorldgenExecutor.RawRequest request) {
        if (hasCapturedBeardifierHelper(request.shader().source())) {
            return beardifierKernelStageRequest(request);
        }
        if (request.shader().source().contains("uvec2 wg_end_island_")) {
            // The 25x25 island/simplex loop must retain helper call boundaries
            // on this driver. Its isolated kernel uses the bounded policy
            // already used by the original End-island stage; do not fold it
            // into a multi-output graph or duplicate it in ancestor shaders.
            return request.withDontInlineFunctions("wg_node_,wg_end_")
                    .withPipelineOptimizationDisabled(true);
        }
        if (request.shader().profile() == NumericProfile.GPU_IEEE_BITS
                && Boolean.parseBoolean(System.getProperty(
                        "tellurium.gpuCandidate.nativeDraftExactInterpolation", "false"))
                && Boolean.parseBoolean(System.getProperty(
                        "tellurium.gpuCandidate.nativeDraftExactInterpolationDontInline", "false"))) {
            // The selective exact-island experiment already splits captured
            // children into carrier rows. Keeping every exact helper and
            // graph wrapper DontInline multiplies the target driver's
            // compiler state across hundreds of tiny modules. This opt-in
            // escape hatch lets the exact stage use ordinary GLSL inlining;
            // it is never enabled for the normal qualified profile.
            return request.withDontInlineFunctions(false, "");
        }
        String prefix = densityDontInlinePrefix();
        if (prefix.equalsIgnoreCase("NONE")) return request.withDontInlineFunctions(false, "");
        return request.withDontInlineFunctions(prefix);
    }

    private static boolean hasCapturedBeardifierHelper(String source) {
        return source.contains("uvec2 wg_beardifier(ivec3 point)");
    }

    private static VulkanWorldgenExecutor.RawRequest beardifierKernelStageRequest(
            VulkanWorldgenExecutor.RawRequest request) {
        if (request.inputWordCount() != Math.multiplyExact(request.elementCount(), request.inputWordsPerElement())) {
            throw new IllegalArgumentException("Structure kernel binding refuses an existing input suffix");
        }
        var kernel = BeardifierKernelInputStage.bind(request.shader().source(), request.inputWordsPerElement());
        var shader = stageShader(request.shader(), kernel.source(), "beardifier-kernel-input-v1");
        SpirvNumericContract.require(shader.source(), shader.profile());
        writeLiveStageDiagnostic("density-beardifier-kernel-input-" + request.inputWordsPerElement() + ".comp", shader.source());
        return new VulkanWorldgenExecutor.RawRequest(shader, kernel.inputRows(request.inputWords()),
                request.inputWordsPerElement(), request.outputWordsPerElement(), request.elementCount(),
                request.defaultStateId(), request.airStateId(), request.invalidStateId(),
                request.disablePipelineOptimization(), false, "");
    }

    private static String densityDontInlinePrefix() {
        String configured = System.getProperty("tellurium.gpuCandidate.densityDontInlinePrefix", "").trim();
        String prefix = configured.isEmpty() ? DENSITY_DONT_INLINE_PREFIX : configured;
        if (prefix.equalsIgnoreCase("NONE")) return "NONE";
        for (String part : prefix.split(",", -1)) {
            if (part.isBlank()) {
                throw new IllegalArgumentException(
                        "GPU candidate densityDontInlinePrefix contains an empty function prefix");
            }
        }
        return prefix;
    }

    /**
     * Resident rows have already split graph dependencies into prior dispatches.
     * Keep only graph boundaries out-of-line by default; the target driver can
     * miscompile the integer IEEE helper family when those helpers are also
     * marked DontInline in a ping-pong chain.
     */
    private static String residentDensityDontInlinePrefix() {
        String configured = System.getProperty(
                "tellurium.gpuCandidate.residentDensityDontInlinePrefix", "").trim();
        String prefix = configured.isEmpty() ? DIRECT_DENSITY_DONT_INLINE_PREFIX : configured;
        if (prefix.equalsIgnoreCase("NONE")) return "NONE";
        for (String part : prefix.split(",", -1)) {
            if (part.isBlank()) {
                throw new IllegalArgumentException(
                        "GPU candidate residentDensityDontInlinePrefix contains an empty function prefix");
            }
        }
        return prefix;
    }

    private static boolean densityPipelineOptimizationDisabled() {
        return !Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.enablePipelineOptimization", "false"));
    }

    /**
     * Experimental dependency-row path.  The ordinary route remains the
     * default until the scratch ABI has a complete driver matrix and a
     * measured allocator policy.
     */
    private static boolean densityResidentScratchEnabled() {
        return Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.deviceResidentDensityScratch", "false"));
    }

    private static int densityResidentScratchMaxStages() {
        int value = integerProperty("tellurium.gpuCandidate.deviceResidentDensityMaxStages",
                DENSITY_RESIDENT_DEFAULT_MAX_STAGES);
        if (value <= 0 || value > 64) {
            throw new IllegalArgumentException(
                    "GPU candidate deviceResidentDensityMaxStages must be in [1, 64]");
        }
        return value;
    }

    private static boolean materialStageDontInlineEnabled() {
        return !materialStageDontInlinePrefix().equalsIgnoreCase("NONE");
    }

    private static boolean aquiferStageDontInlineEnabled() {
        return !aquiferStageDontInlinePrefix().equalsIgnoreCase("NONE");
    }

    private static String aquiferStageDontInlinePrefix() {
        String configured = System.getProperty(
                "tellurium.gpuCandidate.aquiferDontInlinePrefix", "").trim();
        if (!configured.isEmpty()) return configured;
        // The compact integer-carrier consumer miscompiled dynamic pressure
        // addition when every wg_ helper was forced DontInline on this driver.
        if (exactAquiferBarrierInputEnabled() && Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.exactAquiferStage", "false"))) return "NONE";
        return materialStageDontInlinePrefix();
    }

    private static boolean exactAquiferBarrierInputEnabled() {
        return Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.exactAquiferBarrierInput", "false"));
    }

    static boolean useExternalAquiferBarrier(boolean requested, String capturedBarrierFunction) {
        // Null explicitly means aquifers are disabled in the captured material
        // options (e.g. vanilla Nether). There is no pressure/barrier consumer
        // to feed, even when the operator requests exact shared stages globally.
        if (!requested || capturedBarrierFunction == null) return false;
        if (capturedBarrierFunction.isBlank()) {
            throw new IllegalArgumentException("Captured aquifer barrier function cannot be blank");
        }
        return true;
    }

    private static String materialStageDontInlinePrefix() {
        String configured = System.getProperty(
                "tellurium.gpuCandidate.materialDontInlinePrefix", "").trim();
        String prefix = configured.isEmpty() ? MATERIAL_DONT_INLINE_PREFIX : configured;
        if (prefix.equalsIgnoreCase("NONE")) return "NONE";
        for (String part : prefix.split(",", -1)) {
            if (part.isBlank()) {
                throw new IllegalArgumentException(
                        "GPU candidate materialDontInlinePrefix contains an empty function prefix");
            }
        }
        return prefix;
    }

    private static VulkanWorldgenExecutor.RawResult requireStageResult(
            Map<String, VulkanWorldgenExecutor.RawResult> results, String root) {
        VulkanWorldgenExecutor.RawResult result = results.get(root);
        if (result == null) throw new IllegalStateException("Density stage did not produce root " + root);
        return result;
    }

    private static Map<String, Integer> densityStageConsumerCounts(List<DensityStagePlan> plans) {
        Map<String, Integer> counts = new HashMap<>();
        for (DensityStagePlan plan : plans) {
            for (String child : plan.childRoots()) {
                counts.merge(child, 1, Math::addExact);
            }
        }
        return counts;
    }

    /**
     * Finds a bounded run of independent graph stages that can share one
     * coordinate domain and one output row with multiple FP64 carriers.
     * Specialized stages and interpolation roots remain single-stage because
     * their input domains or ABI semantics differ from this bundle.
     */
    private static int densityStageBundleEnd(String source, List<DensityStagePlan> plans,
                                              int start, int stopAfterStage,
                                              int deviceRecreateInterval) {
        DensityStagePlan first = plans.get(start);
        if (!isDensityStageBundleCandidate(source, first)) return start + 1;
        int limit = Math.min(plans.size(), start + DENSITY_STAGE_BUNDLE_MAX_ROOTS);
        if (stopAfterStage >= 0) limit = Math.min(limit, stopAfterStage + 1);
        int reclaimBoundary = ((start / DENSITY_PIPELINE_RECLAIM_INTERVAL) + 1)
                * DENSITY_PIPELINE_RECLAIM_INTERVAL;
        limit = Math.min(limit, reclaimBoundary);
        if (deviceRecreateInterval > 0) {
            int recreateBoundary = ((start / deviceRecreateInterval) + 1) * deviceRecreateInterval;
            limit = Math.min(limit, recreateBoundary);
        }
        List<String> selectedRoots = new ArrayList<>();
        Set<String> selectedChildren = new HashSet<>();
        int end = start;
        while (end < limit) {
            DensityStagePlan candidate = plans.get(end);
            if (!isDensityStageBundleCandidate(source, candidate)) break;
            // A root cannot be bundled with a stage that consumes that root:
            // the latter needs the former's output, which is only available
            // after the bundle dispatch has completed.
            if (selectedRoots.contains(candidate.root())
                    || selectedChildren.contains(candidate.root())
                    || candidate.childRoots().stream().anyMatch(selectedRoots::contains)) break;
            if (selectedChildren.size() + candidate.childRoots().stream()
                    .filter(child -> !selectedChildren.contains(child)).count()
                    > DENSITY_STAGE_BUNDLE_MAX_CHILD_ROOTS) break;
            selectedRoots.add(candidate.root());
            selectedChildren.addAll(candidate.childRoots());
            end++;
        }
        return end;
    }

    /** Returns a bounded consecutive GRAPH segment for the resident-row experiment. */
    private static int densityResidentGraphSegmentEnd(String source,
                                                       List<DensityStagePlan> plans,
                                                       int start, int stopAfterStage,
                                                       int deviceRecreateInterval) {
        if (start >= plans.size()
                || !isDensityStageBundleCandidate(source, plans.get(start))) return start;
        int limit = Math.min(plans.size(), start + densityResidentScratchMaxStages());
        if (stopAfterStage >= 0) limit = Math.min(limit, stopAfterStage + 1);
        int reclaimBoundary = ((start / DENSITY_PIPELINE_RECLAIM_INTERVAL) + 1)
                * DENSITY_PIPELINE_RECLAIM_INTERVAL;
        limit = Math.min(limit, reclaimBoundary);
        if (deviceRecreateInterval > 0) {
            int recreateBoundary = ((start / deviceRecreateInterval) + 1) * deviceRecreateInterval;
            limit = Math.min(limit, recreateBoundary);
        }
        int end = start;
        while (end < limit && isDensityStageBundleCandidate(source, plans.get(end))) end++;
        return end;
    }

    private static boolean isDensityStageBundleCandidate(String source, DensityStagePlan plan) {
        return plan.kind() == DensityStageKind.GRAPH
                && !capturedEndIslandRoot(source, plan.root())
                && !sharedSplineBundleBoundary(plan.root(), plan.childRoots(),
                        Boolean.getBoolean("tellurium.gpuCandidate.sharedSplineShader"))
                && !isDensityInterpolationFunction(source, plan.root())
                && !nativeDraftExactInterpolationStage(source, plan.root())
                // The native draft uses the exact profile for these leaves;
                // exclude them from bundles so the hybrid boundary remains
                // explicit. The exact route retains the old bundled default.
                && (!splitNormalNoise(source)
                || (detectDirectNormalNoise(source, plan.root()) == null
                && detectEmbeddedNormalNoise(source, plan.root()) == null));
    }

    /** A requested reusable spline must not be swallowed by an earlier generic graph bundle. */
    static boolean sharedSplineBundleBoundary(String root, List<String> children, boolean sharedRequested) {
        return sharedRequested && root.startsWith("wg_spline_") && !children.isEmpty();
    }

    static boolean capturedEndIslandRoot(String source, String root) {
        // Spline stages can be virtual roots synthesized from captured tables,
        // with no function body in the original module. Only captured density
        // nodes can own the End island call; do not parse unrelated roots.
        if (!root.startsWith("wg_node_") || !source.contains("wg_end_island_")) return false;
        return functionBody(source, root).contains("wg_end_island_");
    }

    /**
     * The native interpolation experiment keeps the large FP64 graph native,
     * but gives captured FP32 carrier stages their Java-rounding helper set.
     * Those stages cannot share a bundle with hardware-native roots because
     * the bundle has one numeric profile and one helper family.
     */
    private static boolean nativeDraftExactInterpolationStage(String source, String root) {
        if (!root.startsWith("wg_")) return false;
        if (!Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.nativeDraftExactInterpolation", "false"))) return false;
        String configuredRoots = System.getProperty(
                "tellurium.gpuCandidate.nativeDraftExactInterpolationRoots", "").trim();
        if (!configuredRoots.isEmpty()) {
            for (String selector : configuredRoots.split(",", -1)) {
                String candidate = selector.trim();
                if (candidate.isEmpty()) continue;
                if (candidate.endsWith("*")
                        ? root.startsWith(candidate.substring(0, candidate.length() - 1))
                        : root.equals(candidate)) {
                    return true;
                }
            }
            return false;
        }
        Map<String, GlslFunction> functions = parseFunctions(source);
        GlslFunction function = functions.get(root);
        return function != null && "uint".equals(function.returnType());
    }

    private static WorldgenShaderCompiler.Shader exactInterpolationStageShader(
            WorldgenShaderCompiler.Shader completeShader, String root) {
        if (completeShader.profile() != NumericProfile.GPU_NATIVE_DRAFT
                || !nativeDraftExactInterpolationStage(completeShader.source(), root)) {
            return completeShader;
        }
        WorldgenShaderCompiler.Shader exactShader = EXACT_NORMAL_NOISE_SHADER.get();
        return exactShader == null ? completeShader : exactShader;
    }

    private static List<String> densityStageBundleChildren(List<DensityStagePlan> plans) {
        Set<String> children = new LinkedHashSet<>();
        for (DensityStagePlan plan : plans) children.addAll(plan.childRoots());
        return List.copyOf(children);
    }

    /**
     * Emits one coordinate-only shader for several independent density roots.
     * Each root gets its own failure reset and two-word carrier row, matching
     * the single-root stage semantics while reducing source-compaction and
     * native pipeline creation count.
     */
    static String densityStageBundleSource(String completeSource, List<String> roots) {
        if (roots == null || roots.size() < 2) {
            throw new IllegalArgumentException("Density stage bundle requires at least two roots");
        }
        List<DensityStagePlan> plans = roots.stream()
                .map(root -> new DensityStagePlan(root, List.of(), DensityStageKind.GRAPH))
                .toList();
        return densityStageBundleSourceForPlans(completeSource, plans, List.of());
    }

    private static String densityStageBundleSourceForPlans(String completeSource,
                                                            List<DensityStagePlan> plans,
                                                            List<String> childRoots) {
        if (plans == null || plans.size() < 2 || childRoots == null) {
            throw new IllegalArgumentException("Density stage bundle requires at least two plans");
        }
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        Set<String> compactRoots = new LinkedHashSet<>(Set.of(
                "wg_point", "wg_fp64_qnan", "wg_fp64_finite"));
        List<GlslFunction> rootFunctions = new ArrayList<>(plans.size());
        Set<String> planRoots = new HashSet<>();
        for (DensityStagePlan plan : plans) {
            String root = plan.root();
            GlslFunction function = functions.get(root);
            if (function == null) {
                throw new IllegalArgumentException("Generated shader is missing density bundle root: " + root);
            }
            if (!isDensityFunction(root)) {
                throw new IllegalArgumentException("Density stage bundle root is not a captured density function: " + root);
            }
            if (!"uvec2".equals(function.returnType()) && !"uint".equals(function.returnType())) {
                throw new UnsupportedOperationException("Density stage bundle cannot carry "
                        + function.returnType() + " root " + root);
            }
            rootFunctions.add(function);
            planRoots.add(root);
            compactRoots.add(root);
            if ("uint".equals(function.returnType())) compactRoots.add("wg_fp64_from_fp32");
        }
        for (String child : childRoots) {
            if (planRoots.contains(child)) {
                throw new IllegalArgumentException("Density stage bundle has an intra-bundle dependency: " + child);
            }
            GlslFunction function = functions.get(child);
            if (function == null) {
                throw new IllegalArgumentException("Generated shader is missing density bundle child: " + child);
            }
            compactRoots.add(child);
            sourceChildTypeCheck(function, child);
        }
        String prefix = stageSourcePrefix(completeSource);
        int inputWords = densityStageInputWords(childRoots);
        if (!childRoots.isEmpty()) {
            prefix = prefix.replace("uint base = index * 4u;", "uint base = index * " + inputWords + "u;");
            for (int childIndex = 0; childIndex < childRoots.size(); childIndex++) {
                String child = childRoots.get(childIndex);
                GlslFunction function = functions.get(child);
                prefix = replaceFunction(prefix, child,
                        stagedDensityFunction(child, function.returnType(), childIndex));
            }
            int firstStagedChild = Integer.MAX_VALUE;
            for (String child : childRoots) {
                firstStagedChild = Math.min(firstStagedChild,
                        generatedFunctionStart(prefix, child));
            }
            if (firstStagedChild == Integer.MAX_VALUE) {
                throw new IllegalStateException("Density stage bundle lost child functions");
            }
            // wg_stage_density_value calls wg_point and wg_fp64_qnan.  GLSL
            // requires those helpers to be declared before the lookup helper,
            // while the staged child wrappers must appear after it.  Anchoring
            // the insertion to the first replaced child preserves both
            // declaration-order constraints; anchoring it to the first
            // function in the whole prefix puts the lookup before wg_point.
            prefix = prefix.substring(0, firstStagedChild)
                    + densityStageDirectLookupFunction(inputWords, 4)
                    + prefix.substring(firstStagedChild);
            if (childRoots.stream().map(functions::get).anyMatch(function -> "uint".equals(function.returnType()))) {
                compactRoots.add("wg_fp64_to_fp32");
            }
        }
        prefix = compactSource(prefix, compactRoots);
        int outputWords = Math.multiplyExact(plans.size(), DENSITY_STAGE_VALUE_WORDS);
        StringBuilder main = new StringBuilder("""
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    ivec3 point = wg_point(index);
                """);
        for (int index = 0; index < plans.size(); index++) {
            main.append("    wg_failed = false;\n")
                    .append("    uvec2 value").append(index).append(" = ")
                    .append(densityValueExpression(rootFunctions.get(index), plans.get(index).root()))
                    .append(";\n")
                    .append("    if (wg_failed || !wg_fp64_finite(value").append(index)
                    .append(")) value").append(index).append(" = wg_fp64_qnan();\n");
        }
        main.append("    uint outputBase = index * ").append(outputWords).append("u;\n");
        for (int index = 0; index < plans.size(); index++) {
            int offset = index * DENSITY_STAGE_VALUE_WORDS;
            main.append("    outputBits[outputBase + ").append(offset).append("u] = value")
                    .append(index).append(".x;\n")
                    .append("    outputBits[outputBase + ").append(offset + 1).append("u] = value")
                    .append(index).append(".y;\n");
        }
        main.append("}\n");
        return prefix + main;
    }

    private static void sourceChildTypeCheck(GlslFunction function, String child) {
        if (!"uvec2".equals(function.returnType()) && !"uint".equals(function.returnType())) {
            throw new UnsupportedOperationException("Density stage bundle cannot carry "
                    + function.returnType() + " child " + child);
        }
    }

    private static Map<String, VulkanWorldgenExecutor.RawResult> executeDensityStageBundle(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader shader,
            List<DensityStagePlan> plans, List<String> childRoots, int[] stageCoordinates,
            Map<String, VulkanWorldgenExecutor.RawResult> stageResults,
            int defaultStateId, int airStateId, int invalidStateId, int batchSize) {
        return executeDensityStageBundle(executor, shader, plans, childRoots, stageCoordinates,
                stageResults, defaultStateId, airStateId, invalidStateId, batchSize, false);
    }

    private static Map<String, VulkanWorldgenExecutor.RawResult> executeDensityStageBundle(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader shader,
            List<DensityStagePlan> plans, List<String> childRoots, int[] stageCoordinates,
            Map<String, VulkanWorldgenExecutor.RawResult> stageResults,
            int defaultStateId, int airStateId, int invalidStateId, int batchSize,
            boolean direct) {
        if (stageCoordinates.length == 0 || stageCoordinates.length % 4 != 0) {
            throw new IllegalArgumentException("Density stage bundle coordinates must contain four words per element");
        }
        int elementCount = stageCoordinates.length / 4;
        int outputWordsPerElement = Math.multiplyExact(plans.size(), DENSITY_STAGE_VALUE_WORDS);
        int inputWordsPerElement = densityStageInputWords(childRoots);
        int[] inputWords = densityStageInput(stageCoordinates, childRoots, stageResults);
        VulkanWorldgenExecutor.RawRequest request = new VulkanWorldgenExecutor.RawRequest(
                shader, inputWords, inputWordsPerElement, outputWordsPerElement, elementCount,
                defaultStateId, airStateId, invalidStateId);
        request = direct ? directDensityStageRequest(request) : densityStageRequest(request);
        VulkanWorldgenExecutor.RawResult packed = executor.executeRawBatched(
                request.withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        int[] packedWords = packed.outputWords();
        Map<String, VulkanWorldgenExecutor.RawResult> results = new LinkedHashMap<>();
        for (int rootIndex = 0; rootIndex < plans.size(); rootIndex++) {
            int[] words = new int[Math.multiplyExact(elementCount, DENSITY_STAGE_VALUE_WORDS)];
            for (int element = 0; element < elementCount; element++) {
                int source = element * outputWordsPerElement + rootIndex * DENSITY_STAGE_VALUE_WORDS;
                int target = element * DENSITY_STAGE_VALUE_WORDS;
                words[target] = packedWords[source];
                words[target + 1] = packedWords[source + 1];
            }
            DensityStagePlan plan = plans.get(rootIndex);
            results.put(plan.root(), new VulkanWorldgenExecutor.RawResult(
                    words, packed.device(), packed.shaderHash(), packed.spirvHash(),
                    elementCount, DENSITY_STAGE_VALUE_WORDS));
        }
        return Map.copyOf(results);
    }

    private static void releaseDensityStageChildren(List<String> children,
                                                    Map<String, Integer> remainingConsumers,
                                                    Map<String, VulkanWorldgenExecutor.RawResult> results,
                                                    Set<String> retainedRoots) {
        for (String child : children) {
            Integer remaining = remainingConsumers.computeIfPresent(child, (ignored, count) -> count - 1);
            if (remaining != null && remaining <= 0 && !retainedRoots.contains(child)) {
                results.remove(child);
            }
        }
    }

    private static int densityStageInputWords(List<String> childRoots) {
        return Math.addExact(4, Math.multiplyExact(childRoots.size(), DENSITY_STAGE_VALUE_WORDS));
    }

    private static int[] densityStageInput(int[] stageCoordinates, List<String> childRoots,
                                           Map<String, VulkanWorldgenExecutor.RawResult> results) {
        if (stageCoordinates.length == 0 || stageCoordinates.length % 4 != 0) {
            throw new IllegalArgumentException("Density stage coordinates must contain four words per element");
        }
        int elementCount = stageCoordinates.length / 4;
        int inputWordsPerElement = densityStageInputWords(childRoots);
        int[] input = new int[Math.multiplyExact(elementCount, inputWordsPerElement)];
        for (int element = 0; element < elementCount; element++) {
            System.arraycopy(stageCoordinates, element * 4, input,
                    element * inputWordsPerElement, 4);
        }
        for (int childIndex = 0; childIndex < childRoots.size(); childIndex++) {
            String childRoot = childRoots.get(childIndex);
            VulkanWorldgenExecutor.RawResult result = requireStageResult(results, childRoot);
            if (result.elementCount() != elementCount
                    || result.outputWordsPerElement() != DENSITY_STAGE_VALUE_WORDS) {
                throw new IllegalStateException("Density child result has incompatible geometry: " + childRoot);
            }
            int[] words = result.outputWords();
            for (int element = 0; element < elementCount; element++) {
                int source = element * DENSITY_STAGE_VALUE_WORDS;
                int target = element * inputWordsPerElement + 4
                        + childIndex * DENSITY_STAGE_VALUE_WORDS;
                input[target] = words[source];
                input[target + 1] = words[source + 1];
            }
        }
        return input;
    }

    /** Executes a bounded ordinary-graph segment without host repacking. */
    private static Map<String, VulkanWorldgenExecutor.RawResult> executeResidentDensityGraphSegment(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            List<DensityStagePlan> plans, int[] stageCoordinates,
            Map<String, VulkanWorldgenExecutor.RawResult> seeded,
            Map<String, VulkanWorldgenExecutor.RawResult> stageResults,
            int defaultStateId, int airStateId, int invalidStateId, int batchSize,
            String debugLabel) {
        if (plans.isEmpty()) return Map.of();
        if (stageCoordinates.length == 0 || stageCoordinates.length % 4 != 0) {
            throw new IllegalArgumentException("Resident density coordinates must contain four words per element");
        }
        if (plans.stream().anyMatch(plan -> plan.kind() != DensityStageKind.GRAPH)) {
            throw new IllegalArgumentException("Resident density segment contains a specialized stage");
        }
        int elementCount = stageCoordinates.length / 4;
        LinkedHashSet<String> slotRoots = new LinkedHashSet<>();
        for (DensityStagePlan plan : plans) {
            slotRoots.add(plan.root());
            slotRoots.addAll(plan.childRoots());
        }
        Map<String, Integer> slots = new LinkedHashMap<>();
        int slot = 0;
        for (String root : slotRoots) slots.put(root, slot++);
        int rowWords = Math.addExact(4, Math.multiplyExact(slotRoots.size(), DENSITY_STAGE_VALUE_WORDS));
        int[] input = new int[Math.multiplyExact(elementCount, rowWords)];
        for (int element = 0; element < elementCount; element++) {
            System.arraycopy(stageCoordinates, element * 4, input, element * rowWords, 4);
        }
        Map<String, VulkanWorldgenExecutor.RawResult> available = new HashMap<>();
        available.putAll(seeded);
        available.putAll(stageResults);
        for (String root : slotRoots) {
            VulkanWorldgenExecutor.RawResult result = available.get(root);
            if (result == null) continue;
            if (result.elementCount() != elementCount
                    || result.outputWordsPerElement() != DENSITY_STAGE_VALUE_WORDS) {
                throw new IllegalStateException("Resident density seed has incompatible geometry: " + root);
            }
            int[] words = result.outputWords();
            int targetOffset = Math.addExact(4,
                    Math.multiplyExact(slots.get(root), DENSITY_STAGE_VALUE_WORDS));
            for (int element = 0; element < elementCount; element++) {
                int source = element * DENSITY_STAGE_VALUE_WORDS;
                int target = element * rowWords + targetOffset;
                input[target] = words[source];
                input[target + 1] = words[source + 1];
            }
        }
        if (Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugStages", "false"))) {
            StringBuilder row = new StringBuilder(debugLabel).append("-resident-input");
            for (Map.Entry<String, Integer> entry : slots.entrySet()) {
                int valueOffset = Math.addExact(4,
                        Math.multiplyExact(entry.getValue(), DENSITY_STAGE_VALUE_WORDS));
                int low = input[valueOffset];
                int high = input[valueOffset + 1];
                long bits = (Integer.toUnsignedLong(high) << 32) | Integer.toUnsignedLong(low);
                row.append(' ').append(entry.getKey()).append('=').append(Double.longBitsToDouble(bits));
            }
            stageDebug(true, row.toString());
        }

        String dontInlinePrefix = residentDensityDontInlinePrefix();
        boolean dontInline = !dontInlinePrefix.equalsIgnoreCase("NONE");
        List<VulkanWorldgenExecutor.RawStage> chain = new ArrayList<>(plans.size());
        for (DensityStagePlan plan : plans) {
            String source = densityResidentGraphStageSource(
                    completeShader.source(), plan, slots, rowWords);
            WorldgenShaderCompiler.Shader stage = stageShader(completeShader, source,
                    "density-resident-" + plan.root());
            SpirvNumericContract.require(stage.source(), stage.profile());
            writeLiveStageDiagnostic("density-resident-" + plan.root() + ".comp", stage.source());
            chain.add(new VulkanWorldgenExecutor.RawStage(stage, rowWords, rowWords,
                    densityPipelineOptimizationDisabled(), dontInline, dontInlinePrefix));
        }
        VulkanWorldgenExecutor.RawResult raw = executor.executeRawChainBatched(
                input, rowWords, elementCount, defaultStateId, airStateId, invalidStateId,
                chain, batchSize);
        int[] words = raw.outputWords();
        Map<String, VulkanWorldgenExecutor.RawResult> results = new LinkedHashMap<>();
        for (DensityStagePlan plan : plans) {
            int[] output = new int[Math.multiplyExact(elementCount, DENSITY_STAGE_VALUE_WORDS)];
            int sourceOffset = Math.addExact(4,
                    Math.multiplyExact(slots.get(plan.root()), DENSITY_STAGE_VALUE_WORDS));
            for (int element = 0; element < elementCount; element++) {
                int source = element * rowWords + sourceOffset;
                int target = element * DENSITY_STAGE_VALUE_WORDS;
                output[target] = words[source];
                output[target + 1] = words[source + 1];
            }
            results.put(plan.root(), new VulkanWorldgenExecutor.RawResult(
                    output, raw.device(), raw.shaderHash(), raw.spirvHash(),
                    elementCount, DENSITY_STAGE_VALUE_WORDS));
        }
        stageDebug(Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugStages", "false")),
                debugLabel + "-resident-chain-complete stages=" + plans.size()
                        + " rowWords=" + rowWords + " elements=" + elementCount);
        return Map.copyOf(results);
    }

    private record DensityStageNames(String root, List<String> branches,
                                     List<String> splitBranches, String selector) {
        private DensityStageNames {
            boolean validBranchCount = branches != null && (branches.size() == 1 || branches.size() == 2);
            boolean validSplit = branches != null && branches.size() == 1
                    ? splitBranches != null && splitBranches.isEmpty()
                    : splitBranches != null && splitBranches.size() == 2;
            boolean validSelector = branches != null && branches.size() == 1
                    ? selector == null || selector.isBlank() : selector != null && !selector.isBlank();
            if (root == null || root.isBlank() || !validBranchCount || !validSplit || !validSelector) {
                throw new IllegalArgumentException("Invalid density stage names");
            }
            branches = List.copyOf(branches);
            splitBranches = List.copyOf(splitBranches);
        }
    }

    /** Returns whether the captured final-density root has the staged two-branch shape. */
    private static boolean hasTwoDirectDensityBranches(String source) {
        Matcher main = Pattern.compile("uvec2 density = (wg_node_[0-9]+)\\(point\\);").matcher(source);
        if (!main.find()) return false;
        String root = main.group(1);
        String body = functionBody(source, root);
        Matcher calls = Pattern.compile("\\b(wg_node_[0-9]+)\\(point\\)").matcher(body);
        Set<String> branches = new HashSet<>();
        while (calls.find()) {
            if (!calls.group(1).equals(root)) branches.add(calls.group(1));
        }
        return branches.size() == 2;
    }

    private static DensityStageNames findDensityStageNames(String source) {
        Matcher main = Pattern.compile("uvec2 density = (wg_node_[0-9]+)\\(point\\);").matcher(source);
        if (!main.find()) {
            throw new UnsupportedOperationException("Staged density replay requires an FP64 finalDensity root");
        }
        String root = main.group(1);
        String body = functionBody(source, root);
        Matcher calls = Pattern.compile("\\b(wg_node_[0-9]+)\\(point\\)").matcher(body);
        List<String> branches = new ArrayList<>();
        while (calls.find()) {
            String branch = calls.group(1);
            if (!branch.equals(root) && !branches.contains(branch)) branches.add(branch);
        }
        if (branches.size() == 1) {
            return new DensityStageNames(root, branches, List.of(), null);
        }
        if (branches.size() != 2) {
            throw new UnsupportedOperationException("Staged density replay requires exactly two direct density branches; got "
                    + branches.size());
        }
        DensitySplit split = findLargestDensitySplit(source, branches.get(0));
        return new DensityStageNames(root, branches, split.branches(), split.selector());
    }

    private record DensitySplit(String selector, List<String> branches) {
        private DensitySplit {
            if (selector == null || selector.isBlank() || branches == null || branches.size() != 2) {
                throw new IllegalArgumentException("Invalid density split");
            }
            branches = List.copyOf(branches);
        }
    }

    /**
     * One executable density stage. A graph stage with children reads the
     * device-produced value for the same lattice point; the final material
     * stage is the separate consumer of eight-point interpolation rows. Plans
     * are stored in child-before-parent order so the executor never needs to
     * materialize an intermediate result on the host before its producer has
     * completed.
     */
    private enum DensityStageKind {
        GRAPH,
        BLENDED_NOISE,
        FP64_DIVISION
    }

    private record DensityStagePlan(String root, List<String> childRoots, DensityStageKind kind) {
        private DensityStagePlan {
            if (root == null || root.isBlank() || childRoots == null || kind == null) {
                throw new IllegalArgumentException("Invalid density stage plan");
            }
            if (kind == DensityStageKind.BLENDED_NOISE && !childRoots.isEmpty()) {
                throw new IllegalArgumentException("A blended-noise stage cannot have density children");
            }
            childRoots = List.copyOf(childRoots);
        }
    }

    private static DensitySplit findLargestDensitySplit(String source, String root) {
        Map<String, GlslFunction> functions = parseFunctions(source);
        Map<String, Set<String>> calls = functionCalls(source, functions);
        Set<String> reachable = reachableFunctions(root, calls);
        DensitySplit best = null;
        int bestScore = -1;
        for (String candidate : reachable.stream().sorted().toList()) {
            if (!candidate.startsWith("wg_node_")) continue;
            String body = functionBody(source, candidate);
            if (!body.contains("if")) continue;
            Matcher matcher = Pattern.compile("return (wg_node_[0-9]+)\\(point\\)").matcher(body);
            List<String> branches = new ArrayList<>();
            while (matcher.find()) {
                if (!branches.contains(matcher.group(1))) branches.add(matcher.group(1));
            }
            if (branches.size() != 2) continue;
            Matcher selectorMatcher = Pattern.compile(
                    "\\b(?:uvec2|uint)\\s+selector\\s*=\\s*(wg_(?:node|spline)_[0-9]+)\\s*\\(point\\)")
                    .matcher(body);
            if (!selectorMatcher.find()) continue;
            int score = reachableFunctions(branches.get(0), calls).size()
                    + reachableFunctions(branches.get(1), calls).size();
            if (score > bestScore) {
                bestScore = score;
                best = new DensitySplit(selectorMatcher.group(1), branches);
            }
        }
        if (best == null) {
            throw new UnsupportedOperationException(
                    "Staged density replay requires a large selector split with a captured selector value");
        }
        return best;
    }

    /**
     * Builds a deterministic child-before-parent plan for the captured density
     * DAG.  The old frontier-only split replaced deep leaves but left the
     * heavy ancestors in the parent module.  This recursive form stages every
     * oversized node at its direct density children, which bounds the graph
     * retained by each pipeline while keeping every arithmetic operation on
     * the device.
     */
    private static List<DensityStagePlan> findDensityStagePlans(String source, List<String> roots) {
        return findDensityStagePlans(source, roots, Set.of());
    }

    /**
     * Builds a plan while treating interpolation functions as already
     * materialized values.  Interpolation changes coordinate domains: its
     * children are evaluated at lattice corners while the interpolation and
     * its caller are evaluated at block coordinates.  The direct-branch
     * executor seeds these opaque roots with a separate corner-domain pass.
     */
    private static List<DensityStagePlan> findDensityStagePlans(String source, List<String> roots,
                                                                  Set<String> opaqueRoots) {
        return findDensityStagePlans(source, roots, opaqueRoots, false);
    }

    /** Direct branch planning uses a smaller compiler envelope than ordinary material stages. */
    private static List<DensityStagePlan> findDensityStagePlans(String source, List<String> roots,
                                                                  Set<String> opaqueRoots, boolean aggressive) {
        Map<String, GlslFunction> functions = parseFunctions(source);
        Map<String, Set<String>> calls = functionCalls(source, functions);
        // Plan estimation visits the same function graph hundreds of times.
        // Cache the immutable source slices once; repeatedly calling
        // substring(function.start(), function.end()) here was the main
        // source of multi-gigabyte transient heaps on large Overworld captures.
        String prefix = sourcePrefix(source);
        Map<String, String> functionTexts = new HashMap<>();
        int allFunctionChars = 0;
        for (GlslFunction function : functions.values()) {
            if (function.end() >= prefix.length()) continue;
            String text = functionText(source, function);
            functionTexts.put(function.name(), text);
            allFunctionChars = Math.addExact(allFunctionChars, text.length());
        }
        Map<String, Integer> noiseTableChars = noiseTableCharacterCounts(prefix);
        Map<String, Set<String>> noiseTableUsers = noiseTableUsers(functionTexts, noiseTableChars.keySet());
        Map<String, DensityStagePlan> plans = new LinkedHashMap<>();
        Set<String> visiting = new HashSet<>();
        for (String root : roots) {
            buildDensityStagePlan(source, root, functions, calls, plans, visiting, opaqueRoots, aggressive,
                    prefix, functionTexts, allFunctionChars, noiseTableChars, noiseTableUsers);
        }
        if (plans.isEmpty()) {
            throw new UnsupportedOperationException("Staged density replay found no GPU stage frontier");
        }
        return List.copyOf(plans.values());
    }

    private static void buildDensityStagePlan(String source, String root,
                                               Map<String, GlslFunction> functions,
                                               Map<String, Set<String>> calls,
                                               Map<String, DensityStagePlan> plans,
                                               Set<String> visiting,
                                               Set<String> opaqueRoots,
                                               boolean aggressive,
                                               String prefix,
                                               Map<String, String> functionTexts,
                                               int allFunctionChars,
                                               Map<String, Integer> noiseTableChars,
                                               Map<String, Set<String>> noiseTableUsers) {
        if (plans.containsKey(root)) return;
        if (!functions.containsKey(root)) {
            throw new IllegalArgumentException("Generated shader is missing density function: " + root);
        }
        if (!visiting.add(root)) {
            throw new IllegalArgumentException("Generated shader has a recursive density graph at " + root);
        }
        if (plans.size() >= densityStagePlanLimit()) {
            visiting.remove(root);
            throw new IllegalStateException("Density stage plan exceeded the draft limit of "
                    + densityStagePlanLimit() + " stages at " + root);
        }
        if (opaqueRoots.contains(root)) {
            visiting.remove(root);
            plans.put(root, new DensityStagePlan(root, List.of(), DensityStageKind.GRAPH));
            return;
        }
        if (isStageableBlendedNoiseFunction(root)) {
            GlslFunction function = functions.get(root);
            if (!"uvec2".equals(function.returnType())) {
                visiting.remove(root);
                throw new UnsupportedOperationException(
                        "Staged blended noise must return uvec2: " + root);
            }
            visiting.remove(root);
            plans.put(root, new DensityStagePlan(root, List.of(), DensityStageKind.BLENDED_NOISE));
            return;
        }
        List<String> children = calls.getOrDefault(root, Set.of()).stream()
                .filter(name -> isDensityFunction(name) || isStageableBlendedNoiseFunction(name))
                .sorted()
                .toList();
        Set<String> reachableSet = reachableFunctions(Set.of(root), calls, opaqueRoots);
        int reachable = reachableSet.size();
        int maxReachableFunctions = aggressive ? 80 : DENSITY_STAGE_MAX_REACHABLE_FUNCTIONS;
        List<String> childRoots;
        boolean directFp64Division = isDirectFp64Division(source, root, children);
        // A small spline can still hide a captured noise sampler in its
        // coordinate. The target driver returned the wrong endpoint for
        // that combined module although the independently staged coordinate
        // and spline agreed. Always materialize this semantic boundary.
        boolean splineCoordinateBoundary = root.startsWith("wg_spline_")
                && children.stream().anyMatch(child -> child.startsWith("wg_node_"));
        boolean splitAtChildren = directFp64Division || splineCoordinateBoundary
                // An opaque interpolation already has a corner-domain result.
                // Split every ancestor path to that carrier, not just its immediate
                // caller. Otherwise the estimator omits its closure but the emitted
                // ancestor silently inlines and reevaluates the complete noise graph.
                || reachableSet.stream().anyMatch(opaqueRoots::contains)
                || (!children.isEmpty() && reachable > maxReachableFunctions);
        if (!splitAtChildren && !children.isEmpty()) {
            int directSourceLength = estimateDensityStageSourceLength(
                    source, functions, calls, root, List.of(), prefix, functionTexts, allFunctionChars,
                    noiseTableChars, noiseTableUsers);
            int sourceSplitThreshold = aggressive ? 28_000 : DENSITY_STAGE_SOURCE_SPLIT_THRESHOLD;
            int complexNodeThreshold = aggressive ? 24_000 : DENSITY_STAGE_COMPLEX_NODE_SPLIT_THRESHOLD;
            int fp64NodeThreshold = aggressive ? 8_000 : DENSITY_STAGE_FP64_NODE_SPLIT_THRESHOLD;
            boolean complexNode = root.startsWith("wg_node_")
                    && directSourceLength > complexNodeThreshold;
            boolean fp64ArithmeticNode = root.startsWith("wg_node_")
                    && directSourceLength > fp64NodeThreshold
                    && reachableFunctions(Set.of(root), calls, opaqueRoots).contains("wg_fp64_div");
            boolean oversizedSpline = root.startsWith("wg_spline_")
                    && directSourceLength > DENSITY_STAGE_SPLINE_SOURCE_SPLIT_THRESHOLD;
            if (directSourceLength > sourceSplitThreshold
                    || oversizedSpline
                    || complexNode || fp64ArithmeticNode) {
                int parentSourceLength = estimateDensityStageSourceLength(
                        source, functions, calls, root, children, prefix, functionTexts, allFunctionChars,
                        noiseTableChars, noiseTableUsers);
                splitAtChildren = parentSourceLength < directSourceLength;
            }
        }
        if (!splitAtChildren) {
            childRoots = List.of();
        } else {
            childRoots = splineCoordinateBoundary ? children.stream()
                    .filter(child -> !child.startsWith("wg_spline_")
                            || !functionBody(source, functions.get(child)).matches(
                            "\\s*return\\s+(?:0x[0-9a-fA-F]+|[0-9]+)u;\\s*"))
                    .toList() : children;
            for (String child : childRoots) {
                buildDensityStagePlan(source, child, functions, calls, plans, visiting, opaqueRoots, aggressive,
                        prefix, functionTexts, allFunctionChars, noiseTableChars, noiseTableUsers);
            }
        }
        visiting.remove(root);
        DensityStageKind kind = directFp64Division && childRoots.size() == 2
                ? DensityStageKind.FP64_DIVISION : DensityStageKind.GRAPH;
        plans.put(root, new DensityStagePlan(root, childRoots, kind));
    }

    /**
     * Estimate a compact stage without building it.  Planning used to invoke
     * the full source compactor for every reachable node, repeatedly parsing
     * the complete captured shader and allocating discarded source strings.
     * That made the planner itself consume gigabytes for a large Overworld
     * graph before the first Vulkan pipeline was requested.  This estimator
     * follows the same function reachability and table-retention rules, while
     * counting only the retained function bodies and the fixed non-function
     * prefix.  The final stage source is still generated and validated before
     * dispatch, so this is only a planning optimization.
     */
    private static int estimateDensityStageSourceLength(String source,
                                                         Map<String, GlslFunction> functions,
                                                         Map<String, Set<String>> calls,
                                                         String root,
                                                         List<String> replacedChildren,
                                                         String prefix,
                                                         Map<String, String> functionTexts,
                                                         int allFunctionChars,
                                                         Map<String, Integer> noiseTableChars,
                                                         Map<String, Set<String>> noiseTableUsers) {
        GlslFunction rootFunction = functions.get(root);
        if (rootFunction == null) throw new IllegalArgumentException("Generated shader is missing density function: " + root);
        Set<String> roots = new HashSet<>(Set.of("wg_point", root, "wg_fp64_qnan", "wg_fp64_finite"));
        if (rootFunction.returnType().equals("uint")) roots.add("wg_fp64_from_fp32");
        if (replacedChildren.stream().anyMatch(child -> {
            GlslFunction function = functions.get(child);
            return function != null && function.returnType().equals("uint");
        })) roots.add("wg_fp64_to_fp32");

        Set<String> replaced = Set.copyOf(replacedChildren);
        Set<String> reachable = reachableFunctions(roots, calls, replaced);
        int retainedFunctionChars = 0;
        for (GlslFunction function : functions.values()) {
            if (function.end() >= prefix.length()) continue;
            if (!reachable.contains(function.name())) continue;
            if (replaced.contains(function.name())) {
                retainedFunctionChars = Math.addExact(retainedFunctionChars,
                        stagedDensityFunction(function.name(), function.returnType(),
                                replacedChildren.indexOf(function.name())).length());
            } else {
                retainedFunctionChars = Math.addExact(retainedFunctionChars,
                        functionTexts.get(function.name()).length());
            }
        }

        int estimated = prefix.length() - allFunctionChars + retainedFunctionChars;
        for (Map.Entry<String, Integer> table : noiseTableChars.entrySet()) {
            if (Collections.disjoint(reachable, noiseTableUsers.getOrDefault(table.getKey(), Set.of()))) {
                estimated -= table.getValue();
            }
        }
        if (!replaced.isEmpty()) {
            estimated += densityStageDirectLookupFunction(
                    densityStageInputWords(replacedChildren), 4).length();
        }
        String value = densityValueExpression(rootFunction, root);
        // The exact entry point is emitted below after compaction.  Its size
        // is intentionally a small conservative constant plus the root call.
        return Math.addExact(estimated, 512 + value.length());
    }

    private static Set<String> reachableFunctions(Set<String> roots,
                                                  Map<String, Set<String>> calls,
                                                  Set<String> replaced) {
        Set<String> reachable = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        for (String root : roots) {
            if (!calls.containsKey(root)) {
                throw new IllegalArgumentException("Generated shader is missing stage root: " + root);
            }
            if (reachable.add(root)) pending.addLast(root);
        }
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (replaced.contains(current)) continue;
            for (String callee : calls.getOrDefault(current, Set.of())) {
                if (reachable.add(callee)) pending.addLast(callee);
            }
        }
        return reachable;
    }

    private static int unusedNoiseTableChars(String source, CharSequence retainedFunctionSource) {
        Matcher declarations = Pattern.compile(
                "(?m)^[ \\t]*const[ \\t]+[A-Za-z_][A-Za-z0-9_]*[ \\t]+"
                        + "(wg_noise_[A-Za-z0-9_]+)[ \\t]*\\[[^\\]\\r\\n]*\\][ \\t]*="
                        + "[^;\\r\\n]*;[ \\t]*(?:\\r?\\n|$)")
                .matcher(source);
        int removed = 0;
        while (declarations.find()) {
            if (!Pattern.compile("\\b" + Pattern.quote(declarations.group(1)) + "\\b")
                    .matcher(retainedFunctionSource).find()) {
                removed += declarations.end() - declarations.start();
            }
        }
        return removed;
    }

    private static Map<String, Integer> noiseTableCharacterCounts(String source) {
        Matcher declarations = Pattern.compile(
                "(?m)^[ \\t]*const[ \\t]+[A-Za-z_][A-Za-z0-9_]*[ \\t]+"
                        + "(wg_noise_[A-Za-z0-9_]+)[ \\t]*\\[[^\\]\\r\\n]*\\][ \\t]*="
                        + "[^;\\r\\n]*;[ \\t]*(?:\\r?\\n|$)")
                .matcher(source);
        Map<String, Integer> result = new LinkedHashMap<>();
        while (declarations.find()) {
            result.put(declarations.group(1), declarations.end() - declarations.start());
        }
        return Map.copyOf(result);
    }

    static Map<String, Set<String>> noiseTableUsers(Map<String, String> functionTexts,
                                                   Set<String> tableNames) {
        Objects.requireNonNull(functionTexts, "functionTexts");
        Objects.requireNonNull(tableNames, "tableNames");
        if (tableNames.isEmpty()) return Map.of();
        Map<String, Set<String>> users = new HashMap<>();
        for (String table : tableNames) users.put(table, new HashSet<>());
        // Scan each function once, not once per captured permutation table.
        // This retains exact identifier boundaries, including references in
        // source comments just like the former per-table regex search.
        Pattern identifiers = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]*\\b");
        for (var function : functionTexts.entrySet()) {
            Matcher tokens = identifiers.matcher(function.getValue());
            while (tokens.find()) {
                Set<String> tableUsers = users.get(tokens.group());
                if (tableUsers != null) tableUsers.add(function.getKey());
            }
        }
        Map<String, Set<String>> result = new HashMap<>();
        users.forEach((table, names) -> result.put(table, Set.copyOf(names)));
        return Map.copyOf(result);
    }

    private static boolean isDensityFunction(String name) {
        return name.startsWith("wg_node_") || name.startsWith("wg_spline_");
    }

    private static boolean isStageableBlendedNoiseFunction(String name) {
        return name.startsWith("wg_noise_blended_");
    }

    private static boolean isDirectFp64Division(String source, String root, List<String> children) {
        if (!root.startsWith("wg_node_") || children.size() != 2) return false;
        Matcher matcher = Pattern.compile(
                "(?s)\\breturn\\s+wg_fp64_div\\(\\s*(wg_(?:node|spline)_[0-9]+)\\(point\\)\\s*,"
                        + "\\s*(wg_(?:node|spline)_[0-9]+)\\(point\\)\\s*\\)\\s*;")
                .matcher(functionBody(source, root));
        return matcher.find() && children.contains(matcher.group(1)) && children.contains(matcher.group(2));
    }

    private static Map<String, Set<String>> functionCalls(String source,
                                                           Map<String, GlslFunction> functions) {
        if (source.length() >= LARGE_CAPTURE_SOURCE_CHARS) {
            Map<String, Set<String>> cached = LARGE_SOURCE_CALL_CACHE.get().get(source);
            if (cached != null) return cached;
        }
        Map<String, Set<String>> calls = new HashMap<>();
        Pattern callPattern = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)[ \\t]*\\(");
        Map<FunctionCallCacheKey, Set<String>> perFunction = source.length() >= LARGE_CAPTURE_SOURCE_CHARS
                ? LARGE_FUNCTION_CALL_CACHE.get() : Map.of();
        for (GlslFunction function : functions.values()) {
            String text = functionText(source, function);
            FunctionCallCacheKey cacheKey = source.length() >= LARGE_CAPTURE_SOURCE_CHARS
                    ? new FunctionCallCacheKey(function.name(), text) : null;
            Set<String> names = cacheKey == null ? null : perFunction.get(cacheKey);
            if (names == null) {
                Set<String> extracted = new HashSet<>();
                Matcher matcher = callPattern.matcher(text);
                while (matcher.find()) {
                    String callee = matcher.group(1);
                    if (!callee.equals(function.name())) {
                        extracted.add(callee);
                    }
                }
                names = Set.copyOf(extracted);
                if (cacheKey != null) perFunction.put(cacheKey, names);
            }
            // Cache raw references, not a prior source's set of definitions:
            // identical bodies may appear in different compacted modules.
            calls.put(function.name(), names.stream().filter(functions::containsKey)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }
        if (source.length() >= LARGE_CAPTURE_SOURCE_CHARS) {
            LARGE_SOURCE_CALL_CACHE.get().put(source, calls);
        }
        return calls;
    }

    private record FunctionCallCacheKey(String function, String source) {
    }

    private static Map<String, GlslFunction> parseFunctions(String source) {
        if (source.length() >= LARGE_CAPTURE_SOURCE_CHARS) {
            Map<String, GlslFunction> cached = LARGE_SOURCE_FUNCTION_CACHE.get().get(source);
            if (cached != null) return cached;
        }
        Map<String, GlslFunction> functions = source.length() >= LARGE_CAPTURE_SOURCE_CHARS
                ? parseLargeGeneratedFunctions(source) : parseSmallGeneratedFunctions(source);
        if (functions.isEmpty()) throw new IllegalArgumentException("Generated shader contains no functions");
        if (source.length() >= LARGE_CAPTURE_SOURCE_CHARS) {
            LARGE_SOURCE_FUNCTION_CACHE.get().put(source, functions);
        }
        return functions;
    }

    private static Map<String, GlslFunction> parseSmallGeneratedFunctions(String source) {
        Matcher headers = Pattern.compile(
                "(?m)^[ \\t]*(void|bool|uint|uvec2|uvec4|ivec3|float|int)[ \\t]+"
                        + "([A-Za-z_][A-Za-z0-9_]*)[ \\t]*\\(").matcher(source);
        Map<String, GlslFunction> functions = new LinkedHashMap<>();
        while (headers.find()) {
            int open = source.indexOf('{', headers.end());
            int semicolon = source.indexOf(';', headers.end());
            if (open < 0 || (semicolon >= 0 && semicolon < open)) continue;
            addGeneratedFunction(functions, new GlslFunction(headers.group(1), headers.group(2),
                    headers.start(), matchingBrace(source, open)));
        }
        return functions;
    }

    /** Linear header scan for the generated large capture; avoids regex over each 1.5 MB stage source. */
    private static Map<String, GlslFunction> parseLargeGeneratedFunctions(String source) {
        List<String> returnTypes = List.of("void", "bool", "uint", "uvec2", "uvec4", "ivec3", "float", "int");
        Map<String, GlslFunction> functions = new LinkedHashMap<>();
        int lineStart = 0;
        while (lineStart < source.length()) {
            int lineEnd = source.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = source.length();
            int cursor = lineStart;
            while (cursor < lineEnd && (source.charAt(cursor) == ' ' || source.charAt(cursor) == '\t')) cursor++;
            for (String returnType : returnTypes) {
                if (!source.startsWith(returnType, cursor)) continue;
                int afterType = cursor + returnType.length();
                if (afterType >= lineEnd
                        || (source.charAt(afterType) != ' ' && source.charAt(afterType) != '\t')) continue;
                int nameStart = afterType;
                while (nameStart < lineEnd
                        && (source.charAt(nameStart) == ' ' || source.charAt(nameStart) == '\t')) nameStart++;
                int nameEnd = nameStart;
                while (nameEnd < lineEnd && isIdentifierPart(source.charAt(nameEnd))) nameEnd++;
                if (nameEnd == nameStart) continue;
                int openParenthesis = nameEnd;
                while (openParenthesis < lineEnd
                        && (source.charAt(openParenthesis) == ' ' || source.charAt(openParenthesis) == '\t')) {
                    openParenthesis++;
                }
                if (openParenthesis >= lineEnd || source.charAt(openParenthesis) != '(') continue;
                int signatureEnd = matchingParenthesis(source, openParenthesis);
                int openBrace = source.indexOf('{', signatureEnd + 1);
                int semicolon = source.indexOf(';', signatureEnd + 1);
                if (openBrace < 0 || (semicolon >= 0 && semicolon < openBrace)) continue;
                addGeneratedFunction(functions, new GlslFunction(returnType,
                        source.substring(nameStart, nameEnd), lineStart, matchingBrace(source, openBrace)));
                break;
            }
            lineStart = lineEnd + 1;
        }
        return functions;
    }

    private static void addGeneratedFunction(Map<String, GlslFunction> functions, GlslFunction function) {
        if (functions.put(function.name(), function) != null) {
            throw new IllegalArgumentException("Generated shader has duplicate function: " + function.name());
        }
    }

    private static String functionText(String source, GlslFunction function) {
        return source.substring(function.start(), function.end() + 1);
    }

    private static Set<String> reachableFunctions(String root, Map<String, Set<String>> calls) {
        if (!calls.containsKey(root)) throw new IllegalArgumentException("Generated shader is missing function: " + root);
        Set<String> reachable = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        reachable.add(root);
        pending.add(root);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            for (String callee : calls.getOrDefault(current, Set.of())) {
                if (reachable.add(callee)) pending.addLast(callee);
            }
        }
        return reachable;
    }

    static String densityStageSource(String completeSource, String root) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        GlslFunction function = functions.get(root);
        if (function == null) {
            throw new IllegalArgumentException("Generated shader is missing density function: " + root);
        }
        String value = densityValueExpression(function, root);
        Set<String> roots = new HashSet<>(Set.of("wg_point", root, "wg_fp64_qnan", "wg_fp64_finite"));
        if (function.returnType().equals("uint")) roots.add("wg_fp64_from_fp32");
        String prefix = stageSourcePrefix(completeSource);
        prefix = compactSource(prefix,
                roots);
        return prefix + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 value = %s;
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
        """.formatted(value);
    }

    private static String densityStageSourceForPlan(String completeSource, DensityStagePlan plan) {
        return switch (plan.kind()) {
            case BLENDED_NOISE -> blendedNoiseFinalSource(completeSource, plan.root());
            case FP64_DIVISION -> fp64DivisionDiagnosticSource(completeSource, plan);
            case GRAPH -> plan.childRoots().isEmpty()
                    ? densityStageSource(completeSource, plan.root())
                    : densityParentStageSource(completeSource, plan.root(), plan.childRoots());
        };
    }

    private static String fp64DivisionDiagnosticSource(String completeSource, DensityStagePlan plan) {
        List<String> operands = directFp64DivisionOperands(completeSource, plan.root());
        int leftIndex = plan.childRoots().indexOf(operands.get(0));
        int rightIndex = plan.childRoots().indexOf(operands.get(1));
        if (leftIndex < 0 || rightIndex < 0) {
            throw new IllegalStateException("FP64 division stage lost direct operands for " + plan.root());
        }
        return fp64DivisionInitSource(completeSource, 4 + leftIndex * DENSITY_STAGE_VALUE_WORDS,
                4 + rightIndex * DENSITY_STAGE_VALUE_WORDS);
    }

    /**
     * Emits a graph stage that preserves a complete dependency row.  Unlike
     * the ordinary stage source, child carriers are addressed by stable row
     * slots and the stage writes only its own slot.  This is intentionally
     * limited to ordinary GRAPH plans; interpolation, blended-noise fan-out,
     * and the divider state machine keep their existing explicit ABIs.
     */
    private static String densityResidentGraphStageSource(
            String completeSource, DensityStagePlan plan, Map<String, Integer> slots,
            int rowWords) {
        if (plan.kind() != DensityStageKind.GRAPH) {
            throw new IllegalArgumentException("Resident density row only supports GRAPH stages: " + plan.root());
        }
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        GlslFunction rootFunction = functions.get(plan.root());
        if (rootFunction == null) {
            throw new IllegalArgumentException("Generated shader is missing resident density root: " + plan.root());
        }
        Integer rootSlot = slots.get(plan.root());
        if (rootSlot == null) {
            throw new IllegalArgumentException("Resident density row is missing root slot: " + plan.root());
        }
        String source = stageSourcePrefix(completeSource)
                .replace("uint base = index * 4u;", "uint base = index * " + rowWords + "u;");
        for (String child : plan.childRoots()) {
            GlslFunction function = functions.get(child);
            Integer childSlot = slots.get(child);
            if (function == null || childSlot == null) {
                throw new IllegalArgumentException("Resident density row is missing child slot: " + child);
            }
            source = replaceFunction(source, child,
                    residentStagedDensityFunction(function, child, rowWords, childSlot));
        }
        if (!plan.childRoots().isEmpty()) {
            int firstStagedChild = Integer.MAX_VALUE;
            for (String child : plan.childRoots()) {
                firstStagedChild = Math.min(firstStagedChild, generatedFunctionStart(source, child));
            }
            if (firstStagedChild == Integer.MAX_VALUE) {
                throw new IllegalStateException("Resident density stage lost child functions: " + plan.root());
            }
            source = source.substring(0, firstStagedChild)
                    + densityStageDirectLookupFunction(rowWords, 4)
                    + source.substring(firstStagedChild);
        }
        String value = densityValueExpression(rootFunction, plan.root());
        Set<String> roots = new HashSet<>(Set.of(
                "wg_point", plan.root(), "wg_fp64_qnan", "wg_fp64_finite"));
        if (rootFunction.returnType().equals("uint")) roots.add("wg_fp64_from_fp32");
        if (plan.childRoots().stream().map(functions::get)
                .anyMatch(function -> "uint".equals(function.returnType()))) {
            roots.add("wg_fp64_to_fp32");
        }
        source = compactSource(source, roots);
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    uint inputBase = index * %du;
                    uint outputBase = inputBase;
                    for (uint word = 0u; word < %du; word++) {
                        outputBits[outputBase + word] = inputBits[inputBase + word];
                    }
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 value = %s;
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    uint valueBase = outputBase + 4u + %du;
                    outputBits[valueBase] = value.x;
                    outputBits[valueBase + 1u] = value.y;
                }
                """.formatted(rowWords, rowWords, value,
                Math.multiplyExact(rootSlot, DENSITY_STAGE_VALUE_WORDS));
    }

    static List<String> densityStagePlanManifest(String completeSource, List<String> roots) {
        return densityStagePlanManifest(completeSource, roots, Set.of());
    }

    static List<String> densityStagePlanManifest(String completeSource, List<String> roots,
                                                Set<String> opaqueRoots) {
        return findDensityStagePlans(completeSource, roots, opaqueRoots).stream()
                .map(plan -> plan.root() + " children=" + String.join(",", plan.childRoots()))
                .toList();
    }

    static String densityParentStageSource(String completeSource, String root,
                                           List<String> leafRoots) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        GlslFunction rootFunction = functions.get(root);
        if (rootFunction == null) {
            throw new IllegalArgumentException("Generated shader is missing density parent: " + root);
        }
        int inputWords = densityStageInputWords(leafRoots);
        String source = stageSourcePrefix(completeSource)
                .replace("uint base = index * 4u;", "uint base = index * " + inputWords + "u;");
        for (int index = 0; index < leafRoots.size(); index++) {
            String leaf = leafRoots.get(index);
            GlslFunction function = functions.get(leaf);
            if (function == null) {
                throw new IllegalArgumentException("Generated shader is missing density leaf: " + leaf);
            }
            source = replaceFunction(source, leaf,
                    stagedDensityFunction(leaf, function.returnType(), index));
        }
        int firstStagedLeaf = Integer.MAX_VALUE;
        for (String leaf : leafRoots) {
            firstStagedLeaf = Math.min(firstStagedLeaf,
                    generatedFunctionStart(source, leaf));
        }
        if (firstStagedLeaf == Integer.MAX_VALUE) {
            throw new IllegalStateException("Staged density parent has no density children");
        }
        source = source.substring(0, firstStagedLeaf)
                + densityStageDirectLookupFunction(inputWords, 4)
                + source.substring(firstStagedLeaf);
        String value = densityValueExpression(rootFunction, root);
        Set<String> roots = new HashSet<>(Set.of("wg_point", root, "wg_fp64_qnan", "wg_fp64_finite"));
        if (rootFunction.returnType().equals("uint")) roots.add("wg_fp64_from_fp32");
        if (leafRoots.stream().anyMatch(leaf -> functions.get(leaf).returnType().equals("uint"))) {
            roots.add("wg_fp64_to_fp32");
        }
        source = compactSource(source, roots);
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 value = %s;
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
                """.formatted(value);
    }

    /**
     * Builds a parent stage whose child carriers are eight lattice-corner
     * values per child.  The caller supplies block coordinates in the first
     * four words of each row; the lookup maps the interpolation's p000..p111
     * points back to the corresponding device-produced corner columns.
     */
    private static String densityCornerParentStageSource(String completeSource, String root,
                                                          List<String> leafRoots) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        GlslFunction rootFunction = functions.get(root);
        if (rootFunction == null) {
            throw new IllegalArgumentException("Generated shader is missing density interpolation parent: " + root);
        }
        if (leafRoots.size() != 1) {
            throw new UnsupportedOperationException(
                    "Draft density interpolation supports one captured child: " + root);
        }
        int inputWords = densityCornerStageInputWords(leafRoots);
        String source = stageSourcePrefix(completeSource)
                .replace("uint base = index * 4u;", "uint base = index * " + inputWords + "u;");
        source = replaceFunction(source, root,
                densityCornerInterpolationFunction(root, rootFunction.returnType(), inputWords));
        String value = densityValueExpression(rootFunction, root);
        Set<String> roots = new HashSet<>(Set.of("wg_point", root, "wg_fp64_qnan", "wg_fp64_finite"));
        if (rootFunction.returnType().equals("uint")) roots.add("wg_fp64_from_fp32");
        source = compactSource(source, roots);
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 value = %s;
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
        """.formatted(value);
    }

    private static String densityCornerInterpolationFunction(String root, String returnType,
                                                              int inputWords) {
        StringBuilder source = new StringBuilder(returnType).append(' ').append(root)
                .append("(ivec3 point) {\n")
                .append("    uint base = gl_GlobalInvocationID.x * ").append(inputWords).append("u;\n")
                .append("    uvec2 tx = uvec2(inputBits[base + 4u], inputBits[base + 5u]);\n")
                .append("    uvec2 ty = uvec2(inputBits[base + 6u], inputBits[base + 7u]);\n")
                .append("    uvec2 tz = uvec2(inputBits[base + 8u], inputBits[base + 9u]);\n");
        String[] names = {"v000", "v001", "v010", "v011", "v100", "v101", "v110", "v111"};
        for (int corner = 0; corner < names.length; corner++) {
            int offset = DENSITY_STAGE_CORNER_VALUE_BASE_WORDS
                    + corner * DENSITY_STAGE_VALUE_WORDS;
            source.append("    uvec2 ").append(names[corner]).append(" = uvec2(inputBits[base + ")
                    .append(offset).append("u], inputBits[base + ").append(offset + 1)
                    .append("u]);\n");
        }
        source.append("    uvec2 x00 = wg_fp64_lerp(v000, v100, tx);\n")
                .append("    uvec2 x01 = wg_fp64_lerp(v001, v101, tx);\n")
                .append("    uvec2 x10 = wg_fp64_lerp(v010, v110, tx);\n")
                .append("    uvec2 x11 = wg_fp64_lerp(v011, v111, tx);\n")
                .append("    uvec2 interpY0 = wg_fp64_lerp(x00, x10, ty);\n")
                .append("    uvec2 interpY1 = wg_fp64_lerp(x01, x11, ty);\n")
                .append("    return wg_fp64_lerp(interpY0, interpY1, tz);\n")
                .append("}\n");
        return source.toString();
    }

    private static String densityValueExpression(GlslFunction function, String name) {
        return switch (function.returnType()) {
            case "uvec2" -> name + "(point)";
            case "uint" -> "wg_fp64_from_fp32(" + name + "(point))";
            default -> throw new UnsupportedOperationException("Staged density replay cannot carry "
                    + function.returnType() + " function " + name);
        };
    }

    /**
     * Returns the two direct operands of a captured arithmetic division node.
     * The planner keeps their sorted stage order, so the executor maps these
     * source-order operands back to their raw carrier columns before the
     * division pipeline begins.
     */
    private static List<String> directFp64DivisionOperands(String source, String root) {
        Matcher matcher = Pattern.compile(
                "(?s)\\breturn\\s+wg_fp64_div\\(\\s*(wg_(?:node|spline)_[0-9]+)\\(point\\)\\s*,"
                        + "\\s*(wg_(?:node|spline)_[0-9]+)\\(point\\)\\s*\\)\\s*;")
                .matcher(functionBody(source, root));
        if (!matcher.find()) {
            throw new IllegalArgumentException("Generated shader is not a direct FP64 division node: " + root);
        }
        return List.of(matcher.group(1), matcher.group(2));
    }

    /**
     * Executes a direct FP64 division as small raw-carrier GPU stages.  This
     * is the draft escape hatch for drivers that lose the device when a
     * captured graph node contains the complete restoring divider in one
     * invocation. No arithmetic is performed by Java: it only carries the
     * integer state buffer from one device dispatch to the next.
     */
    private static VulkanWorldgenExecutor.RawResult executeStagedFp64Division(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            DensityStagePlan plan, int[] inputWords, int uniqueCount,
            int defaultStateId, int airStateId, int invalidStateId, int batchSize) {
        if (plan.childRoots().size() != 2) {
            throw new IllegalArgumentException("FP64 division stage requires exactly two children: " + plan.root());
        }
        int inputWordsPerElement = densityStageInputWords(plan.childRoots());
        List<String> operands = directFp64DivisionOperands(completeShader.source(), plan.root());
        int leftIndex = plan.childRoots().indexOf(operands.get(0));
        int rightIndex = plan.childRoots().indexOf(operands.get(1));
        if (leftIndex < 0 || rightIndex < 0 || leftIndex == rightIndex) {
            throw new IllegalStateException("FP64 division stage operands are not distinct children: " + plan.root());
        }

        // Keep a device-only single-dispatch escape hatch while the bounded
        // multi-dispatch divider is being validated against captured graphs.
        // The parent source is already compacted to the two staged operands,
        // so this does not reintroduce the full captured graph into a driver
        // module.  It also gives the real-device harness a direct A/B oracle
        // for the integer divider below.
        if (Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.useInlineFp64Division", "false"))) {
            String source = densityParentStageSource(completeShader.source(), plan.root(), plan.childRoots());
            WorldgenShaderCompiler.Shader shader = stageShader(completeShader, source,
                    "density-fp64-div-inline-" + plan.root());
            SpirvNumericContract.require(shader.source(), shader.profile());
            VulkanWorldgenExecutor.RawResult result = executor.executeRawBatched(
                    densityStageRequest(new VulkanWorldgenExecutor.RawRequest(
                            shader, inputWords, inputWordsPerElement, DENSITY_STAGE_VALUE_WORDS,
                            uniqueCount, defaultStateId, airStateId, invalidStateId))
                            .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
            if (result.elementCount() != uniqueCount
                    || result.outputWordsPerElement() != DENSITY_STAGE_VALUE_WORDS) {
                throw new IllegalStateException("Inline FP64 division returned incompatible geometry: " + plan.root());
            }
            return result;
        }

        WorldgenShaderCompiler.Shader initShader = stageShader(completeShader,
                fp64DivisionInitSource(completeShader.source(),
                        4 + leftIndex * DENSITY_STAGE_VALUE_WORDS,
                        4 + rightIndex * DENSITY_STAGE_VALUE_WORDS),
                "density-fp64-div-init-" + plan.root());
        SpirvNumericContract.require(initShader.source(), initShader.profile());
        writeLiveStageDiagnostic("density-fp64-div-init-" + plan.root() + ".comp", initShader.source());
        // The long chained divider is retained as an explicit experiment,
        // but the target NVIDIA driver has crashed at queue submission for a
        // 29-dispatch carrier chain. Keep the proven bounded host-mediated
        // divider as the default until that driver topology is qualified.
        if (!Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.deviceResidentFp64DivisionChain", "false"))) {
            return executeFp64DivisionHostStages(executor, completeShader, initShader,
                    plan.root(), inputWords, inputWordsPerElement, uniqueCount,
                    defaultStateId, airStateId, invalidStateId, batchSize);
        }
        String densityPrefix = densityDontInlinePrefix();
        boolean densityDontInline = !densityPrefix.equalsIgnoreCase("NONE");
        List<VulkanWorldgenExecutor.RawStage> chain = new ArrayList<>();
        chain.add(new VulkanWorldgenExecutor.RawStage(initShader, inputWordsPerElement,
                FP64_DIVISION_STATE_WORDS, densityPipelineOptimizationDisabled(),
                densityDontInline, densityPrefix));

        for (int chunk = 0; chunk < FP64_DIVISION_CHUNK_COUNT; chunk++) {
            WorldgenShaderCompiler.Shader chunkShader = stageShader(completeShader,
                    fp64DivisionChunkSource(completeShader.source(), chunk),
                    "density-fp64-div-chunk-" + plan.root() + "-" + chunk);
            SpirvNumericContract.require(chunkShader.source(), chunkShader.profile());
            writeLiveStageDiagnostic("density-fp64-div-chunk-" + plan.root() + "-" + chunk + ".comp",
                    chunkShader.source());
            chain.add(new VulkanWorldgenExecutor.RawStage(chunkShader, FP64_DIVISION_STATE_WORDS,
                    FP64_DIVISION_STATE_WORDS, densityPipelineOptimizationDisabled(),
                    densityDontInline, densityPrefix));
        }

        WorldgenShaderCompiler.Shader finishShader = stageShader(completeShader,
                fp64DivisionFinishSource(completeShader.source()),
                "density-fp64-div-finish-" + plan.root());
        SpirvNumericContract.require(finishShader.source(), finishShader.profile());
        writeLiveStageDiagnostic("density-fp64-div-finish-" + plan.root() + ".comp", finishShader.source());
        chain.add(new VulkanWorldgenExecutor.RawStage(finishShader, FP64_DIVISION_STATE_WORDS,
                DENSITY_STAGE_VALUE_WORDS, densityPipelineOptimizationDisabled(),
                densityDontInline, densityPrefix));
        VulkanWorldgenExecutor.RawResult result = executor.executeRawChainBatched(
                inputWords, inputWordsPerElement, uniqueCount, defaultStateId, airStateId,
                invalidStateId, chain, batchSize);
        if (result.elementCount() != uniqueCount
                || result.outputWordsPerElement() != DENSITY_STAGE_VALUE_WORDS) {
            throw new IllegalStateException("FP64 division finish returned incompatible geometry");
        }
        return result;
    }

    /** Proven fallback for long divider routes while raw-chain queue limits are investigated. */
    private static VulkanWorldgenExecutor.RawResult executeFp64DivisionHostStages(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            WorldgenShaderCompiler.Shader initShader, String divisionLabel, int[] inputWords,
            int inputWordsPerElement, int uniqueCount, int defaultStateId, int airStateId,
            int invalidStateId, int batchSize) {
        VulkanWorldgenExecutor.RawResult current = executor.executeRawBatched(
                densityStageRequest(new VulkanWorldgenExecutor.RawRequest(
                        initShader, inputWords, inputWordsPerElement, FP64_DIVISION_STATE_WORDS,
                        uniqueCount, defaultStateId, airStateId, invalidStateId))
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        requireFp64DivisionState(current, uniqueCount, "init");
        for (int chunk = 0; chunk < FP64_DIVISION_CHUNK_COUNT; chunk++) {
            WorldgenShaderCompiler.Shader chunkShader = stageShader(completeShader,
                    fp64DivisionChunkSource(completeShader.source(), chunk),
                    "density-fp64-div-chunk-host-" + divisionLabel + "-" + chunk);
            SpirvNumericContract.require(chunkShader.source(), chunkShader.profile());
            writeLiveStageDiagnostic("density-fp64-div-chunk-" + divisionLabel + "-" + chunk + ".comp",
                    chunkShader.source());
            current = executor.executeRawBatched(
                    densityStageRequest(new VulkanWorldgenExecutor.RawRequest(
                            chunkShader, current.outputWords(), FP64_DIVISION_STATE_WORDS,
                            FP64_DIVISION_STATE_WORDS, uniqueCount,
                            defaultStateId, airStateId, invalidStateId))
                            .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
            requireFp64DivisionState(current, uniqueCount, "chunk-" + chunk);
        }
        WorldgenShaderCompiler.Shader finishShader = stageShader(completeShader,
                fp64DivisionFinishSource(completeShader.source()),
                "density-fp64-div-finish-host-" + divisionLabel);
        SpirvNumericContract.require(finishShader.source(), finishShader.profile());
        writeLiveStageDiagnostic("density-fp64-div-finish-" + divisionLabel + ".comp", finishShader.source());
        current = executor.executeRawBatched(
                densityStageRequest(new VulkanWorldgenExecutor.RawRequest(
                        finishShader, current.outputWords(), FP64_DIVISION_STATE_WORDS,
                        DENSITY_STAGE_VALUE_WORDS, uniqueCount,
                        defaultStateId, airStateId, invalidStateId))
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        if (current.elementCount() != uniqueCount
                || current.outputWordsPerElement() != DENSITY_STAGE_VALUE_WORDS) {
            throw new IllegalStateException("FP64 division finish returned incompatible geometry");
        }
        return current;
    }

    private static void requireFp64DivisionState(VulkanWorldgenExecutor.RawResult result,
                                                 int elementCount, String phase) {
        if (result.elementCount() != elementCount
                || result.outputWordsPerElement() != FP64_DIVISION_STATE_WORDS) {
            throw new IllegalStateException("FP64 division " + phase + " returned incompatible geometry");
        }
    }

    private static int fp64DivisionLocalSize(String completeSource) {
        // Inspect only the compiler-owned ABI header, never the captured graph.
        var match = Pattern.compile("layout\\s*\\(local_size_x\\s*=\\s*([0-9]+),\\s*local_size_y=1,\\s*local_size_z=1\\)\\s*in;")
                .matcher(completeSource.substring(0, Math.min(completeSource.length(), 1024)));
        if (!match.find()) throw new IllegalStateException("FP64 division has an unknown compute ABI header");
        return Integer.parseInt(match.group(1));
    }

    private static String fp64DivisionInitSource(String completeSource, int leftOffset, int rightOffset) {
        return SharedFp64DivisionStageEmitter.initSource(fp64DivisionLocalSize(completeSource), leftOffset, rightOffset);
    }

    private static String fp64DivisionChunkSource(String completeSource, int chunk) {
        return SharedFp64DivisionStageEmitter.chunkSource(fp64DivisionLocalSize(completeSource), chunk);
    }

    private static String fp64DivisionFinishSource(String completeSource) {
        return SharedFp64DivisionStageEmitter.finishSource(fp64DivisionLocalSize(completeSource));
    }

    /**
     * Produces only the device density interpolation needed by the later
     * material stages.  The previous final shader also retained aquifer and
     * ore graphs, making a perfectly valid source module unnecessarily large.
     */
    private static String densityMaterialInputStageSource(String completeSource) {
        return densityMaterialInputStageSource(completeSource, false);
    }

    private static String densityMaterialInputStageSource(String completeSource, boolean emitComponents) {
        DensityStageNames names = findDensityStageNames(completeSource);
        String branch = names.branches().get(0);
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        GlslFunction selector = functions.get(names.selector());
        if (selector == null) {
            throw new IllegalArgumentException("Generated shader is missing density selector: " + names.selector());
        }
        String source = sourcePrefix(completeSource)
                .replace("uint outputStateIds[]", "uint outputBits[]")
                .replace("uint base = index * 4u;", "uint base = index * "
                        + DENSITY_MATERIAL_INPUT_WORDS + "u;");
        source = replaceCandidateCellFraction(source);
        // The staged values are lattice-corner samples, not arbitrary point
        // samples. Keep the original direct branch and its interpolation
        // graph so it can request those corner values at an interior block
        // coordinate; only replace the two split children and their selector.
        source = replacePointFunction(source, names.splitBranches().get(0),
                stagedDensityFunction(names.splitBranches().get(0), 0));
        source = replacePointFunction(source, names.splitBranches().get(1),
                stagedDensityFunction(names.splitBranches().get(1), 1));
        source = replacePointFunction(source, names.selector(),
                stagedDensityFunction(names.selector(), selector.returnType(), DENSITY_SELECTOR_SLOT));
        String interpolationRoot = findDensityMaterialInputInterpolationRoot(completeSource, branch);
        DensityInterpolationGeometry geometry = densityInterpolationGeometryForRoot(
                completeSource, interpolationRoot);
        source = replaceFunction(source, interpolationRoot,
                densityMaterialInputInterpolationFunction(completeSource, interpolationRoot,
                        DENSITY_MATERIAL_INPUT_WORDS));
        boolean emitInternals = emitComponents && Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugDensityMaterialInternals", "false"));
        String node5 = null;
        String node44 = null;
        String node45 = null;
        String node2101 = null;
        String node2150 = null;
        if (emitInternals) {
            List<String> interpolationChildren = densityChildren(completeSource, interpolationRoot);
            if (interpolationChildren.size() != 1) {
                throw new UnsupportedOperationException(
                        "Draft density material internals require one interpolation child: " + interpolationRoot);
            }
            node5 = interpolationChildren.get(0);
            DensitySplit split = findLargestDensitySplit(completeSource, node5);
            node44 = densitySplitParent(completeSource, node5, split);
            node45 = split.selector();
            node2101 = split.branches().get(0);
            node2150 = split.branches().get(1);
        }
        int firstNode = source.indexOf("uvec2 wg_node_");
        if (firstNode < 0) throw new IllegalStateException("Density material-input source has no node functions");
        source = source.substring(0, firstNode)
                + densityStageCornerLookupFunction(DENSITY_MATERIAL_INPUT_WORDS,
                        DENSITY_MATERIAL_INPUT_VALUE_BASE_WORDS, geometry)
                + source.substring(firstNode);
        Set<String> roots = new HashSet<>(Set.of("wg_point", branch, "wg_fp64_min",
                "wg_fp64_qnan", "wg_fp64_finite"));
        if (selector.returnType().equals("uint")) roots.add("wg_fp64_to_fp32");
        source = compactSource(source, roots);
        String main = source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uint inputBase = index * %du;
                    uvec2 first = %s(point);
                    uvec2 second = uvec2(inputBits[inputBase + %du], inputBits[inputBase + %du]);
                    uvec2 density = wg_fp64_min(first, second);
                    if (wg_failed || !wg_fp64_finite(density)) density = wg_fp64_qnan();
                    uint outputBase = index * %du;
                    %s
                }
                """.formatted(DENSITY_MATERIAL_INPUT_WORDS, branch,
                DENSITY_MATERIAL_INPUT_VALUE_BASE_WORDS
                        + DENSITY_STAGE_CORNER_VALUE_WORDS * DENSITY_DIRECT_BRANCH_SLOT,
                DENSITY_MATERIAL_INPUT_VALUE_BASE_WORDS
                        + 1 + DENSITY_STAGE_CORNER_VALUE_WORDS * DENSITY_DIRECT_BRANCH_SLOT,
                emitInternals ? 18 : emitComponents ? 6 : 2,
                emitInternals
                        ? ("uvec2 interpolation = " + interpolationRoot + "(point);\n"
                        + "uvec2 node5 = " + node5 + "(point);\n"
                        + "uvec2 node44 = " + node44 + "(point);\n"
                        + "uvec2 node45 = " + node45 + "(point);\n"
                        + "uvec2 node2101 = " + node2101 + "(point);\n"
                        + "uvec2 node2150 = " + node2150 + "(point);\n"
                        + "outputBits[outputBase] = first.x; outputBits[outputBase + 1u] = first.y;\n"
                        + "outputBits[outputBase + 2u] = interpolation.x; outputBits[outputBase + 3u] = interpolation.y;\n"
                        + "outputBits[outputBase + 4u] = node5.x; outputBits[outputBase + 5u] = node5.y;\n"
                        + "outputBits[outputBase + 6u] = node44.x; outputBits[outputBase + 7u] = node44.y;\n"
                        + "outputBits[outputBase + 8u] = node45.x; outputBits[outputBase + 9u] = node45.y;\n"
                        + "outputBits[outputBase + 10u] = node2101.x; outputBits[outputBase + 11u] = node2101.y;\n"
                        + "outputBits[outputBase + 12u] = node2150.x; outputBits[outputBase + 13u] = node2150.y;\n"
                        + "outputBits[outputBase + 14u] = second.x; outputBits[outputBase + 15u] = second.y;\n"
                        + "outputBits[outputBase + 16u] = density.x; outputBits[outputBase + 17u] = density.y;")
                        : emitComponents
                        ? "outputBits[outputBase] = first.x; outputBits[outputBase + 1u] = first.y;\n"
                        + "outputBits[outputBase + 2u] = second.x; outputBits[outputBase + 3u] = second.y;\n"
                        + "outputBits[outputBase + 4u] = density.x; outputBits[outputBase + 5u] = density.y;"
                        : "outputBits[outputBase] = density.x; outputBits[outputBase + 1u] = density.y;");
        return main;
    }

    private static String densitySplitParent(String completeSource, String root, DensitySplit split) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        Map<String, Set<String>> calls = functionCalls(completeSource, functions);
        for (String candidate : reachableFunctions(root, calls).stream().sorted().toList()) {
            if (!candidate.startsWith("wg_node_")) continue;
            String body = functionBody(completeSource, candidate);
            Matcher matcher = Pattern.compile("return (wg_node_[0-9]+)\\(point\\)").matcher(body);
            List<String> branches = new ArrayList<>();
            while (matcher.find()) {
                if (!branches.contains(matcher.group(1))) branches.add(matcher.group(1));
            }
            Matcher selectorMatcher = Pattern.compile(
                    "\\b(?:uvec2|uint)\\s+selector\\s*=\\s*(wg_(?:node|spline)_[0-9]+)\\s*\\(point\\)")
                    .matcher(body);
            if (branches.equals(split.branches()) && selectorMatcher.find()
                    && selectorMatcher.group(1).equals(split.selector())) return candidate;
        }
        throw new UnsupportedOperationException("Draft density internals cannot locate split parent for " + root);
    }

    private static String findDensityMaterialInputInterpolationRoot(String completeSource, String branch) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        Map<String, Set<String>> calls = functionCalls(completeSource, functions);
        List<String> roots = reachableFunctions(Set.of(branch), calls, Set.of()).stream()
                .filter(name -> isDensityFunction(name)
                        && isDensityInterpolationFunction(completeSource, name))
                .sorted()
                .toList();
        if (roots.size() != 1) {
            throw new UnsupportedOperationException(
                    "Draft density material input requires exactly one FP64 interpolation boundary in "
                            + branch + "; found " + roots.size());
        }
        return roots.get(0);
    }

    private record DensityInterpolationGeometry(int xCellSize, int yCellSize, int zCellSize) {
        private DensityInterpolationGeometry {
            requireCandidateCellSize(xCellSize, "x");
            requireCandidateCellSize(yCellSize, "y");
            requireCandidateCellSize(zCellSize, "z");
        }
    }

    /**
     * Reads the interpolation geometry from the captured function body once
     * and reuses it for both host corner packing and device corner lookup.
     * The old candidate silently assumed vanilla 4/8/4 geometry, which could
     * pair correct staged values with the wrong corner row for another
     * admitted capture.
     */
    private static DensityInterpolationGeometry densityInterpolationGeometryForRoot(
            String completeSource, String root) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        GlslFunction function = functions.get(root);
        if (function == null || !"uvec2".equals(function.returnType())) {
            throw new UnsupportedOperationException(
                    "GPU density interpolation geometry requires an FP64 function: " + root);
        }
        String body = functionBody(completeSource, function);
        return new DensityInterpolationGeometry(
                interpolationCellSize(body, "x"),
                interpolationCellSize(body, "y"),
                interpolationCellSize(body, "z"));
    }

    /** Returns the one geometry used by a branch, or the legacy default for non-interpolated branches. */
    private static DensityInterpolationGeometry densityInterpolationGeometryForBranch(
            String completeSource, String branch) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        Map<String, Set<String>> calls = functionCalls(completeSource, functions);
        List<String> roots = reachableFunctions(Set.of(branch), calls, Set.of()).stream()
                .filter(name -> isDensityFunction(name)
                        && isDensityInterpolationFunction(completeSource, name))
                .sorted()
                .toList();
        if (roots.isEmpty()) return DEFAULT_DENSITY_INTERPOLATION_GEOMETRY;
        if (roots.size() != 1) {
            throw new UnsupportedOperationException(
                    "GPU density staging requires one interpolation geometry for " + branch
                            + "; found " + roots.size());
        }
        return densityInterpolationGeometryForRoot(completeSource, roots.get(0));
    }

    private static void requireCandidateCellSize(int cellSize, String axis) {
        if (cellSize != 2 && cellSize != 4 && cellSize != 8) {
            throw new UnsupportedOperationException(
                    "GPU candidate interpolation " + axis + " cell size " + cellSize
                            + " is outside the exact staged fraction table {2,4,8}");
        }
    }

    /** Replays the captured branch interpolation with fractions supplied as exact row inputs. */
    private static String densityMaterialInputInterpolationFunction(String completeSource, String root,
                                                                      int inputWords) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        GlslFunction function = functions.get(root);
        List<String> children = densityChildren(completeSource, root);
        if (function == null || !"uvec2".equals(function.returnType()) || children.size() != 1) {
            throw new UnsupportedOperationException(
                    "Draft density material input requires one FP64 interpolation child: " + root);
        }
        DensityInterpolationGeometry geometry = densityInterpolationGeometryForRoot(completeSource, root);
        String child = children.get(0);
        StringBuilder source = new StringBuilder(1800)
                .append("uvec2 ").append(root).append("(ivec3 point) {\n")
                .append("    int x0, x1, y0, y1, z0, z1; uvec2 ignored;\n")
                .append("    if (!wg_cell_axis64(point.x, ").append(geometry.xCellSize())
                .append(", x0, x1, ignored)) return uvec2(0u);\n")
                .append("    if (!wg_cell_axis64(point.y, ").append(geometry.yCellSize())
                .append(", y0, y1, ignored)) return uvec2(0u);\n")
                .append("    if (!wg_cell_axis64(point.z, ").append(geometry.zCellSize())
                .append(", z0, z1, ignored)) return uvec2(0u);\n")
                .append("    uint base = gl_GlobalInvocationID.x * ").append(inputWords).append("u;\n")
                .append("    uvec2 tx = uvec2(inputBits[base + 3u], inputBits[base + 4u]);\n")
                .append("    uvec2 ty = uvec2(inputBits[base + 5u], inputBits[base + 6u]);\n")
                .append("    uvec2 tz = uvec2(inputBits[base + 7u], inputBits[base + 8u]);\n")
                .append("    ivec3 p000 = point, p001 = point, p010 = point, p011 = point, p100 = point, p101 = point, p110 = point, p111 = point;\n")
                .append("    p000.x=x0; p000.y=y0; p000.z=z0; p001.x=x0; p001.y=y0; p001.z=z1;\n")
                .append("    p010.x=x0; p010.y=y1; p010.z=z0; p011.x=x0; p011.y=y1; p011.z=z1;\n")
                .append("    p100.x=x1; p100.y=y0; p100.z=z0; p101.x=x1; p101.y=y0; p101.z=z1;\n")
                .append("    p110.x=x1; p110.y=y1; p110.z=z0; p111.x=x1; p111.y=y1; p111.z=z1;\n")
                .append("    uvec2 v000=").append(child).append("(p000), v001=").append(child).append("(p001), v010=")
                .append(child).append("(p010), v011=").append(child).append("(p011),\n")
                .append("        v100=").append(child).append("(p100), v101=").append(child).append("(p101), v110=")
                .append(child).append("(p110), v111=").append(child).append("(p111);\n")
                .append("    uvec2 x00=wg_fp64_lerp(v000, v100, tx), x01=wg_fp64_lerp(v001, v101, tx), ")
                .append("x10=wg_fp64_lerp(v010, v110, tx), x11=wg_fp64_lerp(v011, v111, tx);\n")
                .append("    uvec2 interpY0=wg_fp64_lerp(x00, x10, ty), interpY1=wg_fp64_lerp(x01, x11, ty);\n")
                .append("    return wg_fp64_lerp(interpY0, interpY1, tz);\n")
                .append("}\n");
        return source.toString();
    }

    private static int interpolationCellSize(String body, String axis) {
        Matcher matcher = Pattern.compile("wg_cell_axis64\\(point\\." + axis
                + ",\\s*(\\d+),").matcher(body);
        if (!matcher.find()) {
            throw new UnsupportedOperationException(
                    "Captured FP64 interpolation is missing its " + axis + " cell size");
        }
        return Integer.parseInt(matcher.group(1));
    }

    /** Returns a compact helper prefix whose point ABI addresses a resident row. */
    private static String residentStagePrefix(String completeSource, int rowWords,
                                              Set<String> roots) {
        String source = sourcePrefix(completeSource)
                .replace("uint outputStateIds[]", "uint outputBits[]")
                .replace("uint base = index * 4u;", "uint base = index * " + rowWords + "u;");
        return compactSource(source, roots);
    }

    /** Adds a separately compiled barrier carrier to an external resident row. */
    private static String barrierResidentStageSource(String completeSource, String barrierFunction,
                                                     int rowWords) {
        String source = residentStagePrefix(completeSource, rowWords,
                Set.of("wg_point", barrierFunction, "wg_fp64_finite", "wg_fp64_qnan"));
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uint inputBase = index * %du;
                    uint outputBase = index * %du;
                    for (uint word = 0u; word < %du; word++) {
                        outputBits[outputBase + word] = inputBits[inputBase + word];
                    }
                    ivec3 point = wg_point(index);
                    uvec2 value = %s(point);
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    outputBits[outputBase + %du] = value.x;
                    outputBits[outputBase + %du] = value.y;
                }
                """.formatted(rowWords, rowWords, rowWords, barrierFunction,
                MATERIAL_RESIDENT_BARRIER_WORD, MATERIAL_RESIDENT_BARRIER_WORD + 1);
    }

    /** Rewrites the standalone Beardifier entry point to preserve the resident row. */
    private static String beardifierResidentStageSource(String emittedSource, int rowWords) {
        int marker = emittedSource.indexOf("// bounds check is mandatory");
        if (marker < 0) throw new IllegalArgumentException("Beardifier shader has no entry-point marker");
        String source = emittedSource.substring(0, marker)
                .replace("uint outputStateIds[]", "uint outputBits[]")
                .replace("uint base = index * 4u;", "uint base = index * " + rowWords + "u;");
        return source + """
                // Resident material ABI: copy the row, then replace density.
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uint inputBase = index * %du;
                    uint outputBase = index * %du;
                    for (uint word = 0u; word < %du; word++) {
                        outputBits[outputBase + word] = inputBits[inputBase + word];
                    }
                    ivec3 point = wg_point(index);
                    uvec2 density = uvec2(inputBits[inputBase + %du],
                            inputBits[inputBase + %du]);
                    uvec2 value = wg_fp64_add(density, wg_beardifier(point));
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    outputBits[outputBase + %du] = value.x;
                    outputBits[outputBase + %du] = value.y;
                }
                """.formatted(rowWords, rowWords, rowWords,
                MATERIAL_RESIDENT_DENSITY_WORD, MATERIAL_RESIDENT_DENSITY_WORD + 1,
                MATERIAL_RESIDENT_DENSITY_WORD, MATERIAL_RESIDENT_DENSITY_WORD + 1);
    }

    /** Compiles the captured aquifer decision against a device-produced density carrier. */
    private static String aquiferStageSource(String completeSource, String aquiferFunction) {
        return aquiferStageSource(completeSource, aquiferFunction, false);
    }

    /** Compiles the aquifer decision with an optional external barrier carrier. */
    private static String aquiferStageSource(String completeSource, String aquiferFunction,
                                             boolean externalBarrier) {
        int inputWords = externalBarrier ? AQUIFER_STAGE_EXTERNAL_INPUT_WORDS : AQUIFER_STAGE_INPUT_WORDS;
        String barrier = externalBarrier
                ? "uvec2 barrier = uvec2(inputBits[inputBase + 5u], inputBits[inputBase + 6u]);\n"
                : "uvec2 barrier = uvec2(0u);\n";
        String call = externalBarrier
                ? aquiferFunction + "(point, density, barrier)"
                : aquiferFunction + "(point, density)";
        String source = sourcePrefix(completeSource)
                .replace("uint outputStateIds[]", "uint outputBits[]")
                .replace("uint base = index * 4u;", "uint base = index * "
                        + inputWords + "u;");
        Set<String> roots = new LinkedHashSet<>(Set.of("wg_point", aquiferFunction, "wg_fp64_finite"));
        if (externalBarrier && aquiferFunction.startsWith("wg_aquifer_")) {
            roots.add("wg_aquifer_scale_rational_"
                    + aquiferFunction.substring("wg_aquifer_".length()));
        }
        source = compactSource(source, roots);
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uint inputBase = index * %du;
                    uvec2 density = uvec2(inputBits[inputBase + 3u], inputBits[inputBase + 4u]);
                    %s
                    uvec4 value;
                    if (!wg_fp64_finite(density)) {
                        value = uvec4(2u, 0u, 0u, 0u);
                    } else {
                        value = %s;
                        if (wg_failed) value = uvec4(2u, 0u, 0u, 0u);
                    }
                uvec4 outputValue = value;
                    uint outputBase = index * 4u;
                    outputBits[outputBase] = outputValue.x;
                    outputBits[outputBase + 1u] = outputValue.y;
                    outputBits[outputBase + 2u] = outputValue.z;
                    outputBits[outputBase + 3u] = outputValue.w;
                }
                """.formatted(inputWords, barrier, call);
    }

    /** Aquifer stage that updates only its resident row fields. */
    private static String aquiferResidentStageSource(String completeSource, String aquiferFunction,
                                                     int rowWords) {
        return aquiferResidentStageSource(completeSource, aquiferFunction, rowWords, false);
    }

    /** Aquifer resident stage with an optional external barrier carrier. */
    private static String aquiferResidentStageSource(String completeSource, String aquiferFunction,
                                                     int rowWords, boolean externalBarrier) {
        Set<String> roots = new LinkedHashSet<>(Set.of("wg_point", aquiferFunction, "wg_fp64_finite"));
        if (externalBarrier && aquiferFunction.startsWith("wg_aquifer_")) {
            roots.add("wg_aquifer_scale_rational_"
                    + aquiferFunction.substring("wg_aquifer_".length()));
        }
        String source = residentStagePrefix(completeSource, rowWords, roots);
        String barrier = externalBarrier
                ? "                    uvec2 barrier = uvec2(inputBits[inputBase + "
                        + MATERIAL_RESIDENT_BARRIER_WORD + "u], inputBits[inputBase + "
                        + (MATERIAL_RESIDENT_BARRIER_WORD + 1) + "u]);\n"
                : "";
        String call = externalBarrier
                ? aquiferFunction + "(point, density, barrier)"
                : aquiferFunction + "(point, density)";
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uint inputBase = index * %du;
                    uint outputBase = index * %du;
                    for (uint word = 0u; word < %du; word++) {
                        outputBits[outputBase + word] = inputBits[inputBase + word];
                    }
                    ivec3 point = wg_point(index);
                    uvec2 density = uvec2(inputBits[inputBase + %du],
                            inputBits[inputBase + %du]);
                %s
                    uvec4 value;
                    if (!wg_fp64_finite(density)) {
                        value = uvec4(2u, 0u, 0u, 0u);
                    } else {
                        value = %s;
                        if (wg_failed) value = uvec4(2u, 0u, 0u, 0u);
                    }
                    outputBits[outputBase + %du] = value.x;
                    outputBits[outputBase + %du] = value.y;
                    outputBits[outputBase + %du] = value.z;
                    outputBits[outputBase + %du] = value.w;
                }
                """.formatted(rowWords, rowWords, rowWords,
                MATERIAL_RESIDENT_DENSITY_WORD, MATERIAL_RESIDENT_DENSITY_WORD + 1,
                barrier, call, MATERIAL_RESIDENT_AQUIFER_WORD,
                MATERIAL_RESIDENT_AQUIFER_WORD + 1, MATERIAL_RESIDENT_AQUIFER_WORD + 2,
                MATERIAL_RESIDENT_AQUIFER_WORD + 3);
    }

    private static boolean stagedOreInputsEnabled(WorldgenShaderCompiler.Shader shader) {
        return Boolean.parseBoolean(System.getProperty("tellurium.gpuCandidate.stagedOreInputs",
                Boolean.toString(shader.profile() == NumericProfile.GPU_IEEE_BITS)));
    }

    /** Produces both ore fractions on the same bounded divider as ordinary density stages. */
    private static int[] executeOreDivisionInputs(VulkanWorldgenExecutor executor,
            WorldgenShaderCompiler.Shader complete, String oreFunction, int[] inputs,
            int defaultStateId, int airStateId, int invalidStateId, int batchSize, DeviceCapabilities device) {
        int count = inputs.length / ORE_STAGE_EXTERNAL_INPUT_WORDS;
        int[][] quotients = new int[2][];
        List<String> fields = List.of("edgeFraction", "richnessFraction");
        for (int field = 0; field < fields.size(); field++) {
            String label = fields.get(field);
            var prepare = stageShader(complete, oreDivisionPrepareSource(complete.source(), oreFunction, label),
                    "ore-division-prepare-" + label);
            SpirvNumericContract.require(prepare.source(), prepare.profile());
            writeLiveStageDiagnostic("density-ore-division-prepare-" + label + ".comp", prepare.source());
            var prepared = executor.executeRawBatched(new VulkanWorldgenExecutor.RawRequest(prepare, inputs,
                    ORE_STAGE_EXTERNAL_INPUT_WORDS, 8, count, defaultStateId, airStateId, invalidStateId)
                    .withDontInlineFunctions(false, "")
                    .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
            var init = stageShader(complete, SharedFp64DivisionStageEmitter.initSource(complete.localSize(), 4, 6),
                    "ore-division-init-" + label);
            SpirvNumericContract.require(init.source(), init.profile());
            var quotient = executeFp64DivisionHostStages(executor, complete, init, "ore-" + label,
                    prepared.outputWords(), 8, count, defaultStateId, airStateId, invalidStateId, batchSize);
            requireSameDevice(device, prepared.device(), "ore division prepare " + label);
            requireSameDevice(device, quotient.device(), "ore division " + label);
            quotients[field] = quotient.outputWords();
        }
        return packOreFinishInputs(inputs, quotients);
    }

    static int[] packOreFinishInputs(int[] inputs, int[][] quotients) {
        if (inputs == null || inputs.length == 0 || inputs.length % ORE_STAGE_EXTERNAL_INPUT_WORDS != 0
                || quotients == null || quotients.length != 2) {
            throw new IllegalArgumentException("Complete ore input rows and two GPU quotients required");
        }
        int count = inputs.length / ORE_STAGE_EXTERNAL_INPUT_WORDS;
        for (int[] quotient : quotients) {
            if (quotient == null || quotient.length != Math.multiplyExact(count, 2)) {
                throw new IllegalArgumentException("Ore quotient carrier geometry differs");
            }
            for (int word = 1; word < quotient.length; word += 2) {
                if ((quotient[word] & 0x7ff0_0000) == 0x7ff0_0000) {
                    throw new IllegalStateException("GPU ore division returned a nonfinite carrier");
                }
            }
        }
        int[] result = new int[Math.multiplyExact(count, ORE_STAGE_FINISH_INPUT_WORDS)];
        for (int row = 0; row < count; row++) {
            int target = row * ORE_STAGE_FINISH_INPUT_WORDS;
            System.arraycopy(inputs, row * ORE_STAGE_EXTERNAL_INPUT_WORDS, result, target, ORE_STAGE_EXTERNAL_INPUT_WORDS);
            for (int field = 0; field < 2; field++) {
                System.arraycopy(quotients[field], row * 2, result, target + 10 + field * 2, 2);
            }
        }
        return result;
    }

    private static Pattern oreDivisionAssignment(String field) {
        if (!field.equals("edgeFraction") && !field.equals("richnessFraction")) {
            throw new IllegalArgumentException("Unknown ore division field: " + field);
        }
        return Pattern.compile("\\buvec2\\s+" + field + "\\s*=\\s*(wg_fp64_div\\([^;]*\\))\\s*;");
    }

    static String oreDivisionPrepareSource(String completeSource, String oreFunction, String field) {
        Matcher assignment = oreDivisionAssignment(field).matcher(functionBody(completeSource, oreFunction));
        if (!assignment.find()) throw new UnsupportedOperationException("Unsupported ore division: " + field);
        String expression = assignment.group(1);
        if (assignment.find()) throw new UnsupportedOperationException("Duplicate ore division: " + field);
        int open = expression.indexOf('(');
        int close = matchingParenthesis(expression, open);
        if (close != expression.length() - 1) throw new UnsupportedOperationException("Malformed ore division");
        List<String> operands = splitTopLevelArguments(expression.substring(open + 1, close));
        if (operands.size() != 2) throw new UnsupportedOperationException("Ore division needs two operands");
        String source = sourcePrefix(completeSource)
                .replace("uint outputStateIds[]", "uint outputBits[]")
                .replace("uint base = index * 4u;", "uint base = index * 10u;");
        source = compactSource(source, Set.of("wg_point", "wg_fp64_abs", "wg_fp64_less", "wg_fp64_sub",
                "wg_fp64_from_int", "wg_fp64_finite", "wg_fp64_qnan"));
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uint base = index * 10u;
                    uvec2 toggle = uvec2(inputBits[base + 4u], inputBits[base + 5u]);
                    bool copper = wg_fp64_less(uvec2(0u), toggle);
                    uvec2 magnitude = wg_fp64_abs(toggle);
                    int minY = copper ? 0 : -60, maxY = copper ? 50 : -8;
                    int distanceToEdge = min(maxY - point.y, point.y - minY);
                    uvec2 numerator = %s;
                    uvec2 denominator = %s;
                    if (wg_failed || !wg_fp64_finite(numerator) || !wg_fp64_finite(denominator)) {
                        numerator = wg_fp64_qnan(); denominator = wg_fp64_qnan();
                    }
                    uint outputBase = index * 8u;
                    for (uint word = 0u; word < 4u; word++) outputBits[outputBase + word] = inputBits[base + word];
                    outputBits[outputBase + 4u] = numerator.x;
                    outputBits[outputBase + 5u] = numerator.y;
                    outputBits[outputBase + 6u] = denominator.x;
                    outputBits[outputBase + 7u] = denominator.y;
                }
                """.formatted(operands.get(0), operands.get(1));
    }

    static String oreFinishStageSource(String completeSource, String oreFunction) {
        String source = oreExternalStageSource(completeSource, oreFunction);
        List<String> fields = List.of("edgeFraction", "richnessFraction");
        for (int field = 0; field < fields.size(); field++) {
            Matcher assignment = oreDivisionAssignment(fields.get(field)).matcher(source);
            if (!assignment.find()) throw new UnsupportedOperationException("Unsupported ore finish division");
            int offset = 10 + field * 2;
            source = assignment.replaceFirst("uvec2 " + fields.get(field)
                    + " = uvec2(inputBits[gl_GlobalInvocationID.x * 14u + " + offset
                    + "u], inputBits[gl_GlobalInvocationID.x * 14u + " + (offset + 1) + "u]);");
        }
        source = source.replace("index * 10u", "index * 14u")
                .replace("gl_GlobalInvocationID.x * 10u", "gl_GlobalInvocationID.x * 14u");
        return compactSource(source.substring(0, source.indexOf("void main()")), Set.of("wg_point", oreFunction))
                + source.substring(source.indexOf("void main()"));
    }

    /** The controlled emitter binds exactly one captured expression to each of these fields. */
    static List<String> oreStageInputRoots(String completeSource, String oreFunction) {
        String body = functionBody(completeSource, oreFunction);
        List<String> roots = new ArrayList<>(3);
        for (String field : List.of("toggle", "ridged", "gap")) {
            Matcher matcher = oreInputAssignment(field).matcher(body);
            if (!matcher.find()) {
                throw new UnsupportedOperationException("Unsupported captured ore " + field + " expression");
            }
            String root = matcher.group(1);
            if (matcher.find()) throw new UnsupportedOperationException("Duplicate captured ore " + field);
            // Require an admitted generated function before any native work.
            functionBody(completeSource, root);
            roots.add(root);
        }
        return List.copyOf(roots);
    }

    private static Pattern oreInputAssignment(String field) {
        return Pattern.compile("\\buvec2\\s+" + field
                + "\\s*=\\s*(wg_(?:node|spline)_[0-9]+)\\s*\\(\\s*point\\s*\\)\\s*;");
    }

    static int[] packOreStageInputs(int[] coordinates, int[][] carriers) {
        if (coordinates == null || coordinates.length == 0 || coordinates.length % 4 != 0
                || carriers == null || carriers.length != 3) {
            throw new IllegalArgumentException("Ore inputs require complete coordinates and three GPU carriers");
        }
        int count = coordinates.length / 4;
        for (int[] carrier : carriers) {
            if (carrier == null || carrier.length != Math.multiplyExact(count, 2)) {
                throw new IllegalArgumentException("Ore input carrier geometry differs");
            }
        }
        int[] input = new int[Math.multiplyExact(count, ORE_STAGE_EXTERNAL_INPUT_WORDS)];
        for (int index = 0; index < count; index++) {
            int target = index * ORE_STAGE_EXTERNAL_INPUT_WORDS;
            System.arraycopy(coordinates, index * 4, input, target, 4);
            for (int field = 0; field < carriers.length; field++) {
                System.arraycopy(carriers[field], index * 2, input, target + 4 + field * 2, 2);
            }
        }
        return input;
    }

    /** Retains the captured ore/RNG rule, but removes its monolithic noise graph. */
    static String oreExternalStageSource(String completeSource, String oreFunction) {
        oreStageInputRoots(completeSource, oreFunction);
        String body = functionBody(completeSource, oreFunction);
        List<String> fields = List.of("toggle", "ridged", "gap");
        for (int field = 0; field < fields.size(); field++) {
            int offset = 4 + field * 2;
            body = oreInputAssignment(fields.get(field)).matcher(body).replaceFirst(
                    "uvec2 " + fields.get(field) + " = uvec2(inputBits[gl_GlobalInvocationID.x * 10u + "
                            + offset + "u], inputBits[gl_GlobalInvocationID.x * 10u + " + (offset + 1) + "u]);");
        }
        String source = replaceFunction(completeSource, oreFunction,
                "uvec2 " + oreFunction + "(ivec3 point) {" + body + "}\n");
        source = oreStageSource(source, oreFunction)
                .replace("uint base = index * 4u;", "uint base = index * 10u;");
        return source;
    }

    /** Compiles the captured ore decision independently of the aquifer graph. */
    private static String oreStageSource(String completeSource, String oreFunction) {
        String source = sourcePrefix(completeSource)
                .replace("uint outputStateIds[]", "uint outputBits[]")
                .replace("uint base = index * 4u;", "uint base = index * "
                        + ORE_STAGE_INPUT_WORDS + "u;");
        source = compactSource(source, Set.of("wg_point", oreFunction));
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 value = %s(point);
                    if (wg_failed) value = uvec2(2u, 0u);
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
                """.formatted(oreFunction);
    }

    /** Ore stage that updates only its resident row fields. */
    private static String oreResidentStageSource(String completeSource, String oreFunction,
                                                 int rowWords) {
        String source = residentStagePrefix(completeSource, rowWords,
                Set.of("wg_point", oreFunction));
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uint inputBase = index * %du;
                    uint outputBase = index * %du;
                    for (uint word = 0u; word < %du; word++) {
                        outputBits[outputBase + word] = inputBits[inputBase + word];
                    }
                    ivec3 point = wg_point(index);
                    uvec2 value = %s(point);
                    if (wg_failed) value = uvec2(2u, 0u);
                    outputBits[outputBase + %du] = value.x;
                    outputBits[outputBase + %du] = value.y;
                }
                """.formatted(rowWords, rowWords, rowWords, oreFunction,
                MATERIAL_RESIDENT_ORE_WORD, MATERIAL_RESIDENT_ORE_WORD + 1);
    }

    /** The final shader now only selects already-computed material carriers. */
    private static String materialStageSource(String completeSource) {
        String source = sourcePrefix(completeSource)
                .replace("uint outputStateIds[]", "uint outputBits[]")
                .replace("uint base = index * 4u;", "uint base = index * "
                        + MATERIAL_STAGE_INPUT_WORDS + "u;");
        source = compactSource(source, Set.of("wg_point", "wg_material_decide64",
                "wg_fp64_finite"));
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uint inputBase = index * %du;
                    uvec2 density = uvec2(inputBits[inputBase + 3u], inputBits[inputBase + 4u]);
                    uvec4 aquifer = uvec4(inputBits[inputBase + 5u], inputBits[inputBase + 6u],
                            inputBits[inputBase + 7u], inputBits[inputBase + 8u]);
                    uvec2 ore = uvec2(inputBits[inputBase + 9u], inputBits[inputBase + 10u]);
                    uint outputBase = index * 2u;
                    if (!wg_fp64_finite(density) || aquifer.x > 1u || ore.x > 1u) {
                        outputBits[outputBase] = dispatch.invalidStateId;
                        outputBits[outputBase + 1u] = 0u;
                    } else {
                        uint materialState = wg_material_decide64(density,
                                aquifer.x != 0u, aquifer.y, ore.x != 0u, ore.y,
                                dispatch.defaultStateId, dispatch.airStateId,
                                aquifer.z != 0u);
                        outputBits[outputBase] = materialState;
                        outputBits[outputBase + 1u] = aquifer.w;
                    }
                }
                """.formatted(MATERIAL_STAGE_INPUT_WORDS);
    }

    /** Final resident material stage; state and fluid mark occupy row tail words. */
    private static String materialResidentStageSource(String completeSource, int rowWords) {
        int stateWord = rowWords == MATERIAL_RESIDENT_EXTERNAL_WORDS
                ? MATERIAL_RESIDENT_EXTERNAL_STATE_WORD : MATERIAL_RESIDENT_STATE_WORD;
        int fluidWord = rowWords == MATERIAL_RESIDENT_EXTERNAL_WORDS
                ? MATERIAL_RESIDENT_EXTERNAL_FLUID_WORD : MATERIAL_RESIDENT_FLUID_WORD;
        String source = residentStagePrefix(completeSource, rowWords,
                Set.of("wg_point", "wg_material_decide64", "wg_fp64_finite"));
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uint inputBase = index * %du;
                    uint outputBase = index * %du;
                    for (uint word = 0u; word < %du; word++) {
                        outputBits[outputBase + word] = inputBits[inputBase + word];
                    }
                    uvec2 density = uvec2(inputBits[inputBase + %du],
                            inputBits[inputBase + %du]);
                    uvec4 aquifer = uvec4(inputBits[inputBase + %du],
                            inputBits[inputBase + %du], inputBits[inputBase + %du],
                            inputBits[inputBase + %du]);
                    uvec2 ore = uvec2(inputBits[inputBase + %du],
                            inputBits[inputBase + %du]);
                    if (!wg_fp64_finite(density) || aquifer.x > 1u || ore.x > 1u) {
                        outputBits[outputBase + %du] = dispatch.invalidStateId;
                        outputBits[outputBase + %du] = 0u;
                    } else {
                        uint materialState = wg_material_decide64(density,
                                aquifer.x != 0u, aquifer.y, ore.x != 0u, ore.y,
                                dispatch.defaultStateId, dispatch.airStateId,
                                aquifer.z != 0u);
                        outputBits[outputBase + %du] = materialState;
                        outputBits[outputBase + %du] = aquifer.w;
                    }
                }
                """.formatted(rowWords, rowWords, rowWords,
                MATERIAL_RESIDENT_DENSITY_WORD, MATERIAL_RESIDENT_DENSITY_WORD + 1,
                MATERIAL_RESIDENT_AQUIFER_WORD, MATERIAL_RESIDENT_AQUIFER_WORD + 1,
                MATERIAL_RESIDENT_AQUIFER_WORD + 2, MATERIAL_RESIDENT_AQUIFER_WORD + 3,
                MATERIAL_RESIDENT_ORE_WORD, MATERIAL_RESIDENT_ORE_WORD + 1,
                stateWord, fluidWord, stateWord, fluidWord);
    }

    static String densityFinalStageSource(String completeSource) {
        return finalDensityStageSource(completeSource, findDensityStageNames(completeSource));
    }

    private static String finalDensityStageSource(String completeSource, DensityStageNames names) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        GlslFunction selector = functions.get(names.selector());
        if (selector == null) {
            throw new IllegalArgumentException("Generated shader is missing density selector: " + names.selector());
        }
        DensityInterpolationGeometry geometry = densityInterpolationGeometryForBranch(
                completeSource, names.branches().get(0));
        String source = sourcePrefix(completeSource)
                .replace("uint base = index * 4u;", "uint base = index * " + DENSITY_FINAL_BLOCK_WORDS + "u;");
        source = replaceCandidateCellFraction(source);
        source = replacePointFunction(source, names.splitBranches().get(0),
                stagedDensityFunction(names.splitBranches().get(0), 0));
        source = replacePointFunction(source, names.splitBranches().get(1),
                stagedDensityFunction(names.splitBranches().get(1), 1));
        source = replacePointFunction(source, names.selector(),
                stagedDensityFunction(names.selector(), selector.returnType(), DENSITY_SELECTOR_SLOT));
        int firstNode = source.indexOf("uvec2 wg_node_");
        if (firstNode < 0) throw new IllegalStateException("Staged density source has no node functions");
        source = source.substring(0, firstNode)
                + densityStageCornerLookupFunction(DENSITY_FINAL_BLOCK_WORDS, 3, geometry)
                + source.substring(firstNode);
        String originalDensity = "uvec2 density = " + names.root() + "(point);";
        String stagedDensity = "uvec2 density = wg_fp64_min(" + names.branches().get(0) + "(point), "
                + "uvec2(inputBits[index * " + DENSITY_FINAL_BLOCK_WORDS + "u + "
                + (3 + DENSITY_STAGE_CORNER_VALUE_WORDS * DENSITY_DIRECT_BRANCH_SLOT) + "u], inputBits[index * "
                + DENSITY_FINAL_BLOCK_WORDS + "u + "
                + (4 + DENSITY_STAGE_CORNER_VALUE_WORDS * DENSITY_DIRECT_BRANCH_SLOT) + "u]));";
        String main = completeSource.substring(completeSource.indexOf("// bounds check is mandatory"))
                .replace(originalDensity, stagedDensity);
        Set<String> roots = new HashSet<>(Set.of("wg_point", "wg_fp64_finite", "wg_fp64_min",
                "wg_material_decide64", names.branches().get(0)));
        if (selector.returnType().equals("uint")) roots.add("wg_fp64_to_fp32");
        Matcher aquifer = Pattern.compile("uvec4 (wg_aquifer_[A-Za-z0-9_]+)\\(ivec3 point, uvec2 density\\)").matcher(completeSource);
        if (aquifer.find()) roots.add(aquifer.group(1));
        String ore = lastFunctionNameOrNull(completeSource,
                "uvec2 (wg_ore_[A-Za-z0-9_]+)\\(ivec3 point\\)");
        if (ore != null) roots.add(ore);
        source = compactSource(source, roots);
        return source + main;
    }

    private static String stagedDensityFunction(String functionName, int slot) {
        return stagedDensityFunction(functionName, "uvec2", slot);
    }

    private static String stagedDensityFunction(String functionName, String returnType, int slot) {
        String value = "wg_stage_density_value(" + slot + "u, point)";
        if (returnType.equals("uint")) value = "wg_fp64_to_fp32(" + value + ")";
        if (!returnType.equals("uint") && !returnType.equals("uvec2")) {
            throw new UnsupportedOperationException("Staged density replay cannot wrap " + returnType
                    + " function " + functionName);
        }
        return returnType + " " + functionName + "(ivec3 point) { return " + value + "; }\n";
    }

    /** Emits a resident-row child wrapper with a fixed carrier offset. */
    private static String residentStagedDensityFunction(GlslFunction function,
                                                        String functionName, int rowWords, int slot) {
        // Reuse the validated row lookup instead of duplicating SSBO
        // indexing in every child wrapper.  Besides keeping the ABI in one
        // place, this rejects an accidental point/row mismatch instead of
        // silently reading a neighboring carrier.
        String value = "wg_stage_density_value(" + slot + "u, point)";
        if (function.returnType().equals("uint")) value = "wg_fp64_to_fp32(" + value + ")";
        if (!function.returnType().equals("uint") && !function.returnType().equals("uvec2")) {
            throw new UnsupportedOperationException("Resident density replay cannot wrap "
                    + function.returnType() + " function " + functionName);
        }
        return function.returnType() + " " + functionName + "(ivec3 point) { return " + value + "; }\n";
    }

    private static String densityStageDirectLookupFunction(int rowWords, int valueBaseWords) {
        return """
                uvec2 wg_stage_density_value(uint slot, ivec3 point) {
                    uint index = gl_GlobalInvocationID.x;
                    ivec3 original = wg_point(index);
                    if (point.x != original.x || point.y != original.y || point.z != original.z) {
                        wg_failed = true;
                        return wg_fp64_qnan();
                    }
                    uint valueBase = index * %dU + %dU + slot * %dU;
                    uvec2 value = uvec2(inputBits[valueBase], inputBits[valueBase + 1u]);
                    if (!wg_fp64_finite(value)) {
                        wg_failed = true;
                        return wg_fp64_qnan();
                    }
                    return value;
                }

                """.formatted(rowWords, valueBaseWords, DENSITY_STAGE_VALUE_WORDS);
    }

    private static String densityStageCornerLookupFunction(int rowWords, int valueBaseWords) {
        return densityStageCornerLookupFunction(rowWords, valueBaseWords,
                DEFAULT_DENSITY_INTERPOLATION_GEOMETRY);
    }

    private static String densityStageCornerLookupFunction(
            int rowWords, int valueBaseWords, DensityInterpolationGeometry geometry) {
        return """
                uvec2 wg_stage_density_value(uint slot, ivec3 point) {
                    uint index = gl_GlobalInvocationID.x;
                    ivec3 original = wg_point(index);
                    int x0, x1, y0, y1, z0, z1;
                    uvec2 fraction;
                    if (!wg_cell_axis64(original.x, %d, x0, x1, fraction)
                            || !wg_cell_axis64(original.y, %d, y0, y1, fraction)
                            || !wg_cell_axis64(original.z, %d, z0, z1, fraction)) {
                        return wg_fp64_qnan();
                    }
                    uint corner = 0xffffffffu;
                    if (point.x == x0 && point.y == y0 && point.z == z0) corner = 0u;
                    else if (point.x == x0 && point.y == y0 && point.z == z1) corner = 1u;
                    else if (point.x == x0 && point.y == y1 && point.z == z0) corner = 2u;
                    else if (point.x == x0 && point.y == y1 && point.z == z1) corner = 3u;
                    else if (point.x == x1 && point.y == y0 && point.z == z0) corner = 4u;
                    else if (point.x == x1 && point.y == y0 && point.z == z1) corner = 5u;
                    else if (point.x == x1 && point.y == y1 && point.z == z0) corner = 6u;
                    else if (point.x == x1 && point.y == y1 && point.z == z1) corner = 7u;
                    if (corner == 0xffffffffu) {
                        wg_failed = true;
                        return wg_fp64_qnan();
                    }
                    uint value = index * %dU + %dU + slot * %dU + corner * %dU;
                    return uvec2(inputBits[value], inputBits[value + 1u]);
                }

                """.formatted(geometry.xCellSize(), geometry.yCellSize(), geometry.zCellSize(),
                        rowWords, valueBaseWords, DENSITY_STAGE_CORNER_VALUE_WORDS,
                        DENSITY_STAGE_VALUE_WORDS);
    }

    private record DensityStageInputs(int[] uniqueCoordinates, int[] cornerIndices) {
        private DensityStageInputs {
            uniqueCoordinates = uniqueCoordinates.clone();
            cornerIndices = cornerIndices.clone();
        }

        @Override public int[] uniqueCoordinates() { return uniqueCoordinates.clone(); }
        @Override public int[] cornerIndices() { return cornerIndices.clone(); }
    }

    private static DensityStageInputs buildDensityStageInputs(int[] coordinates) {
        return buildDensityStageInputs(coordinates, DEFAULT_DENSITY_INTERPOLATION_GEOMETRY);
    }

    private static DensityStageInputs buildDensityStageInputs(
            int[] coordinates, DensityInterpolationGeometry geometry) {
        if (coordinates.length == 0 || coordinates.length % 3 != 0) {
            throw new IllegalArgumentException("Density staged coordinates must contain XYZ triples");
        }
        Map<Point, Integer> unique = new LinkedHashMap<>();
        int blockCount = coordinates.length / 3;
        int[] cornerIndices = new int[Math.multiplyExact(blockCount, DENSITY_STAGE_CORNER_COUNT)];
        for (int block = 0; block < blockCount; block++) {
            int coordinate = block * 3;
            int x = coordinates[coordinate], y = coordinates[coordinate + 1], z = coordinates[coordinate + 2];
            int x0 = cellLow(x, geometry.xCellSize()), x1 = Math.addExact(x0, geometry.xCellSize());
            int y0 = cellLow(y, geometry.yCellSize()), y1 = Math.addExact(y0, geometry.yCellSize());
            int z0 = cellLow(z, geometry.zCellSize()), z1 = Math.addExact(z0, geometry.zCellSize());
            Point[] corners = {
                    new Point(x0, y0, z0), new Point(x0, y0, z1), new Point(x0, y1, z0), new Point(x0, y1, z1),
                    new Point(x1, y0, z0), new Point(x1, y0, z1), new Point(x1, y1, z0), new Point(x1, y1, z1)
            };
            for (int corner = 0; corner < corners.length; corner++) {
                cornerIndices[block * DENSITY_STAGE_CORNER_COUNT + corner] =
                        unique.computeIfAbsent(corners[corner], ignored -> unique.size());
            }
        }
        int[] uniqueCoordinates = new int[Math.multiplyExact(unique.size(), 4)];
        for (var entry : unique.entrySet()) {
            int word = entry.getValue() * 4;
            uniqueCoordinates[word] = entry.getKey().x();
            uniqueCoordinates[word + 1] = entry.getKey().y();
            uniqueCoordinates[word + 2] = entry.getKey().z();
        }
        return new DensityStageInputs(uniqueCoordinates, cornerIndices);
    }

    private static void writeDensityStageDiagnostics(WorldgenShaderCompiler.Shader completeShader,
                                                      List<DensityStagePlan> stagePlans,
                                                      DensityStageNames names) {
        String directory = System.getProperty("tellurium.gpuCandidate.stagedShaderDir", "").trim();
        if (directory.isEmpty()) return;
        Path root = Path.of(directory).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            StringBuilder stageManifest = new StringBuilder();
            for (int index = 0; index < stagePlans.size(); index++) {
                DensityStagePlan plan = stagePlans.get(index);
                String source = densityStageSourceForPlan(completeShader.source(), plan);
                Files.writeString(root.resolve("density-stage-" + index + "-" + plan.root() + ".comp"),
                        source);
                stageManifest.append(index).append(' ').append(plan.root()).append(" children=")
                        .append(String.join(",", plan.childRoots())).append(' ')
                        .append(source.length()).append(System.lineSeparator());
            }
            Files.writeString(root.resolve("density-stages.txt"), stageManifest.toString());
            if (names.branches().size() == 1) {
                Files.writeString(root.resolve("density-single-root.comp"),
                        densityStageSourceForPlan(completeShader.source(),
                                requireDensityStagePlan(stagePlans, names.root())));
                if (Boolean.parseBoolean(System.getProperty(
                        "tellurium.gpuCandidate.stopAfterShaderDiagnostics", "false"))) {
                    throw new IllegalStateException("GPU candidate staged density diagnostics wrote " + root);
                }
                return;
            }
            Files.writeString(root.resolve("density-split-first.comp"),
                    densityStageSourceForPlan(completeShader.source(),
                            requireDensityStagePlan(stagePlans, names.splitBranches().get(0))));
            Files.writeString(root.resolve("density-split-second.comp"),
                    densityStageSourceForPlan(completeShader.source(),
                            requireDensityStagePlan(stagePlans, names.splitBranches().get(1))));
            Files.writeString(root.resolve("density-selector.comp"),
                    densityStageSourceForPlan(completeShader.source(),
                            requireDensityStagePlan(stagePlans, names.selector())));
            Files.writeString(root.resolve("density-second-branch.comp"),
                    densityStageSource(completeShader.source(), names.branches().get(1)));
            Files.writeString(root.resolve("density-final.comp"),
                    densityFinalStageSource(completeShader.source()));
            String aquiferFunction = aquiferFunctionName(completeShader.source());
            String oreFunction = lastFunctionNameOrNull(completeShader.source(),
                    "(?m)^uvec2 (wg_ore_[A-Za-z0-9_]+)\\(");
            Files.writeString(root.resolve("density-material-input.comp"),
                    densityMaterialInputStageSource(completeShader.source()));
            Files.writeString(root.resolve("density-aquifer.comp"),
                    aquiferStageSource(completeShader.source(), aquiferFunction));
            if (oreFunction != null) {
                Files.writeString(root.resolve("density-ore.comp"),
                        oreStageSource(completeShader.source(), oreFunction));
            }
            Files.writeString(root.resolve("density-material.comp"),
                    materialStageSource(completeShader.source()));
            Map<String, GlslFunction> functions = parseFunctions(completeShader.source());
            Map<String, Set<String>> calls = functionCalls(completeShader.source(), functions);
            Set<String> directBranchInterpolation = reachableFunctions(
                    Set.of(names.branches().get(1)), calls, Set.of()).stream()
                    .filter(name -> isDensityFunction(name)
                            && isDensityInterpolationFunction(completeShader.source(), name))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            for (String interpolation : directBranchInterpolation) {
                Files.writeString(root.resolve("density-direct-interpolation-" + interpolation + ".comp"),
                        densityCornerParentStageSource(completeShader.source(), interpolation,
                                densityChildren(completeShader.source(), interpolation)));
            }
            if (Boolean.parseBoolean(System.getProperty("tellurium.gpuCandidate.stopAfterShaderDiagnostics", "false"))) {
                throw new IllegalStateException("GPU candidate staged density diagnostics wrote " + root);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("GPU candidate staged density diagnostics could not write " + root, failure);
        }
    }

    private static DensityStagePlan requireDensityStagePlan(List<DensityStagePlan> plans, String root) {
        return plans.stream().filter(plan -> plan.root().equals(root)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Density stage plan omitted root " + root));
    }

    /**
     * The End router contains a large interpolated graph whose End-island and
     * blended-noise branches make a single driver-visible function call graph
     * exceed the target driver's reliable compilation/dispatch envelope.  The
     * stage boundary is explicit: both branches run on Vulkan into raw FP64
     * carriers, then a final Vulkan shader consumes those carriers while it
     * performs interpolation and material selection.  The host only packs
     * coordinates, stage indices and device-produced words.
     */
    private static DeviceMaterialResult executeStagedEnd(VulkanWorldgenExecutor executor,
                                                         WorldgenShaderCompiler.Shader completeShader,
                                                         int[] coordinates, int defaultStateId,
                                                         int airStateId, int invalidStateId,
                                                         int[] allowedStateIds,
                                                         int batchSize) {
        EndStageNames names = findEndStageNames(completeShader.source());
        EndStageInputs inputs = buildEndStageInputs(coordinates);
        WorldgenShaderCompiler.Shader endShader = stageShader(completeShader,
                endStageSource(completeShader.source(), names.endNode()), "end-island");
        List<SampleSpec> sampleSpecs = names.sampleSpecs();
        // The forty samples are split into the main, min-limit and max-limit
        // octave groups. Each group gets one selector-driven pipeline, but the
        // selector dispatches to the distinct captured Perlin function for
        // every octave. Reusing one function for a whole group would silently
        // reuse one permutation table and change End terrain.
        List<WorldgenShaderCompiler.Shader> sampleShaders = List.of(
                stageShader(completeShader, noiseSampleSource(completeShader.source(),
                        sampleSpecsFor(sampleSpecs, "main")), "blended-samples-main"),
                stageShader(completeShader, noiseSampleSource(completeShader.source(),
                        sampleSpecsFor(sampleSpecs, "min")), "blended-samples-min"),
                stageShader(completeShader, noiseSampleSource(completeShader.source(),
                        sampleSpecsFor(sampleSpecs, "max")), "blended-samples-max"));
        WorldgenShaderCompiler.Shader finalShader = stageShader(completeShader,
                finalStageSource(completeShader.source(), names), "final");
        SpirvNumericContract.require(endShader.source(), endShader.profile());
        for (WorldgenShaderCompiler.Shader sampleShader : sampleShaders) {
            SpirvNumericContract.require(sampleShader.source(), sampleShader.profile());
        }
        SpirvNumericContract.require(finalShader.source(), finalShader.profile());
        writeStageDiagnostics(endShader, sampleShaders, finalShader);

        // The staged path is deliberately self-contained.  Keeping the
        // driver function policy in each request avoids making a global
        // default claim for unrelated shaders in the same JVM.
        int[] uniqueCoordinates = inputs.uniqueCoordinates();
        int[] cornerIndices = inputs.cornerIndices();
        int uniqueCount = uniqueCoordinates.length / 4;
        VulkanWorldgenExecutor.RawResult end = executor.executeRawBatched(
                    new VulkanWorldgenExecutor.RawRequest(endShader, uniqueCoordinates, 4, 4,
                            uniqueCount)
                            .withDontInlineFunctions(END_DONT_INLINE_PREFIX)
                            .withPipelineOptimizationDisabled(true), batchSize);
        DeviceCapabilities device = end.device();
        List<VulkanWorldgenExecutor.RawResult> samples = new ArrayList<>(sampleSpecs.size());
        int[] sampleCoordinates = uniqueCoordinates.clone();
        for (SampleSpec spec : sampleSpecs) {
                for (int unique = 0; unique < uniqueCount; unique++) {
                    sampleCoordinates[unique * 4 + 3] = spec.scaleExponent();
                }
                WorldgenShaderCompiler.Shader selectedShader = sampleShaders.get(sampleGroupIndex(spec.group()));
                VulkanWorldgenExecutor.RawResult sample = executor.executeRawBatched(
                        new VulkanWorldgenExecutor.RawRequest(selectedShader, sampleCoordinates, 4, 4,
                                uniqueCount)
                                .withDontInlineFunctions(END_DONT_INLINE_PREFIX)
                                .withPipelineOptimizationDisabled(true), batchSize);
                requireSameDevice(device, sample.device(), "End sample stages");
                samples.add(sample);
        }
        // The branch stages need explicit call boundaries to stay within the
        // driver's FP64 register envelope.  The final stage is only the
        // interpolation/material combine and benefits from ordinary driver
        // optimization; retaining DontInline here needlessly inflates it.
        int blockCount = coordinates.length / 3;
        int[] finalStateIds = new int[blockCount];
        boolean[] finalFluidMarks = new boolean[blockCount];
        String shaderHash = null;
        String spirvHash = null;
        int stageRecordWords = Math.addExact(2, Math.multiplyExact(samples.size(), 2));
        int blockRecordWords = 11;
        int[] endWords = end.outputWords();
        int[][] sampleWords = new int[samples.size()][];
        for (int sample = 0; sample < samples.size(); sample++) {
                sampleWords[sample] = samples.get(sample).outputWords();
        }
        for (int offset = 0; offset < blockCount; offset += batchSize) {
                int elements = Math.min(batchSize, blockCount - offset);
                int stageOffset = Math.multiplyExact(elements, blockRecordWords);
                int[] batchInput = new int[Math.addExact(stageOffset,
                        Math.multiplyExact(uniqueCount, stageRecordWords))];
                for (int local = 0; local < elements; local++) {
                    int block = offset + local;
                    int sourceCoordinate = block * 3;
                    int targetWord = local * blockRecordWords;
                    batchInput[targetWord] = coordinates[sourceCoordinate];
                    batchInput[targetWord + 1] = coordinates[sourceCoordinate + 1];
                    batchInput[targetWord + 2] = coordinates[sourceCoordinate + 2];
                    for (int corner = 0; corner < 8; corner++) {
                        int unique = cornerIndices[block * 8 + corner];
                        batchInput[targetWord + 3 + corner] = stageOffset + unique * stageRecordWords;
                    }
                }
                for (int unique = 0; unique < uniqueCount; unique++) {
                    int stageWord = stageOffset + unique * stageRecordWords;
                    int valueWord = unique * 4;
                    batchInput[stageWord] = endWords[valueWord];
                    batchInput[stageWord + 1] = endWords[valueWord + 1];
                    for (int sample = 0; sample < sampleWords.length; sample++) {
                        int sampleWord = unique * 4;
                        int target = stageWord + 2 + sample * 2;
                        batchInput[target] = sampleWords[sample][sampleWord];
                        batchInput[target + 1] = sampleWords[sample][sampleWord + 1];
                    }
                }
                VulkanWorldgenExecutor.RawResult finalResult = executor.executeRaw(
                        new VulkanWorldgenExecutor.RawRequest(finalShader, batchInput, blockRecordWords, 2,
                                elements, defaultStateId, airStateId, invalidStateId)
                                .withDontInlineFunctions(false, "")
                                .withPipelineOptimizationDisabled(true));
                int[] batchWords = finalResult.outputWords();
                for (int local = 0; local < elements; local++) {
                    int outputWord = local * 2;
                    finalStateIds[offset + local] = batchWords[outputWord];
                    finalFluidMarks[offset + local] = decodeFluidMark(batchWords[outputWord + 1], "staged End");
                }
                requireSameDevice(device, finalResult.device(), "staged End final stage");
                if (shaderHash == null) {
                    shaderHash = finalResult.shaderHash();
                    spirvHash = finalResult.spirvHash();
                } else if (!shaderHash.equals(finalResult.shaderHash())
                        || !spirvHash.equals(finalResult.spirvHash())) {
                    throw new IllegalStateException("Staged End result provenance changed between slices");
                }
        }
        for (int stateId : finalStateIds) {
                if (!contains(allowedStateIds, stateId)) {
                    throw new IllegalStateException("Staged End shader returned state ID outside the dispatch ABI: " + stateId);
                }
        }
        return new DeviceMaterialResult(finalStateIds, finalFluidMarks, device, shaderHash, spirvHash,
                blockCount);
    }

    private static boolean contains(int[] values, int wanted) {
        for (int value : values) if (value == wanted) return true;
        return false;
    }

    private record EndStageNames(String root, String endNode, String noiseNode, String noiseFunction,
                                 String boundaryNode, List<SampleSpec> sampleSpecs) {
        private EndStageNames {
            sampleSpecs = List.copyOf(sampleSpecs);
        }
    }

    record SampleSpec(String function, int scaleExponent, String group) {
        SampleSpec {
            if (function == null || function.isBlank() || scaleExponent < 0
                    || group == null || group.isBlank()) {
                throw new IllegalArgumentException("Invalid staged End sample");
            }
        }
    }

    /** A single captured Perlin call inside a normal-noise wrapper. */
    private record NoiseCall(String function, List<String> arguments, int start, int end) {
        private NoiseCall {
            if (function == null || function.isBlank() || arguments == null
                    || arguments.isEmpty() || start < 0 || end <= start) {
                throw new IllegalArgumentException("Invalid captured normal-noise call");
            }
            arguments = List.copyOf(arguments);
        }
    }

    /**
     * The captured normal-noise wrapper used by the Overworld is small in
     * source but pulls a complete Perlin implementation into every density
     * parent.  Keep its two exact Perlin calls as independent GPU stages.
     */
    private record DirectNormalNoise(String root, String noiseFunction, List<String> rootArguments,
                                     NoiseCall first, NoiseCall second) {
        private DirectNormalNoise {
            if (root == null || root.isBlank() || noiseFunction == null || noiseFunction.isBlank()
                    || rootArguments == null || rootArguments.size() != 3
                    || first == null || second == null) {
                throw new IllegalArgumentException("Invalid direct normal-noise stage");
            }
            rootArguments = List.copyOf(rootArguments);
        }
    }

    private static DirectNormalNoise detectDirectNormalNoise(String source, String root) {
        if (!isDensityFunction(root)) return null;
        Map<String, GlslFunction> functions = parseFunctions(source);
        GlslFunction rootFunction = functions.get(root);
        if (rootFunction == null) return null;
        String body = functionBody(source, rootFunction);
        // Normal-noise calls are often multiplied by a captured amplitude or
        // have captured coordinate transforms around their arguments.  The
        // old detector only recognized a bare `return noise(point);`, which
        // left the common `return mul(noise(...), scale);` leaves inside a
        // monolithic graph stage.  Restrict this fast path to one call in a
        // return expression; roots with density children are kept on the
        // ordinary parent ABI until their child-fed variant is implemented.
        Matcher returned = Pattern.compile("(?s)^\\s*return\\s+.*;\\s*$").matcher(body);
        Matcher noiseCall = Pattern.compile("\\b(wg_noise_normal_[0-9]+)\\s*\\(").matcher(body);
        if (!returned.matches() || !noiseCall.find()
                || countMatches(body, "\\bwg_noise_normal_[0-9]+\\s*\\(") != 1) return null;
        int noiseStart = noiseCall.start(1);
        int open = body.indexOf('(', noiseStart);
        int close = matchingParenthesis(body, open);
        List<String> rootArguments = splitTopLevelArguments(body.substring(open + 1, close));
        if (rootArguments.size() != 3) return null;
        String noiseFunction = noiseCall.group(1);
        GlslFunction noise = functions.get(noiseFunction);
        if (noise == null) return null;
        String noiseBody = functionBody(source, noise);
        NoiseCall first = assignedNoiseCall(noiseBody, "first");
        NoiseCall second = assignedNoiseCall(noiseBody, "second");
        if (first == null || second == null) return null;
        return new DirectNormalNoise(root, noiseFunction, rootArguments, first, second);
    }

    /**
     * Finds the common parent form used by the captured Overworld graph:
     * local selector/rarity setup followed by one normal-noise call in the
     * return expression.  The old direct detector intentionally rejects this
     * form because its coordinate arguments refer to locals declared in the
     * parent.  Leaving it monolithic, however, brings the complete Perlin
     * closure back into a 40 KB parent stage and makes the target driver stall
     * in pipeline compilation.  The embedded route evaluates those arguments
     * in a small GPU coordinate stage, evaluates the two Perlin halves through
     * the existing bounded GPU path, and then lets a second GPU stage replay
     * the parent arithmetic.
     */
    private static DirectNormalNoise detectEmbeddedNormalNoise(String source, String root) {
        if (!isDensityFunction(root)) return null;
        Map<String, GlslFunction> functions = parseFunctions(source);
        GlslFunction rootFunction = functions.get(root);
        if (rootFunction == null) return null;
        String body = functionBody(source, rootFunction);
        Matcher noiseCall = Pattern.compile("\\b(wg_noise_normal_[0-9]+)\\s*\\(").matcher(body);
        if (!noiseCall.find()
                || countMatches(body, "\\bwg_noise_normal_[0-9]+\\s*\\(") != 1) return null;
        int noiseStart = noiseCall.start(1);
        int open = body.indexOf('(', noiseStart);
        int close = matchingParenthesis(body, open);
        List<String> rootArguments = splitTopLevelArguments(body.substring(open + 1, close));
        if (rootArguments.size() != 3) return null;
        int returnStart = body.lastIndexOf("return", noiseStart);
        if (returnStart < 0 || body.substring(returnStart, noiseStart).isBlank()) return null;
        String noiseFunction = noiseCall.group(1);
        GlslFunction noise = functions.get(noiseFunction);
        if (noise == null) return null;
        String noiseBody = functionBody(source, noise);
        NoiseCall first = assignedNoiseCall(noiseBody, "first");
        NoiseCall second = assignedNoiseCall(noiseBody, "second");
        if (first == null || second == null) return null;
        return new DirectNormalNoise(root, noiseFunction, rootArguments, first, second);
    }

    private static int countMatches(String source, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(source);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static NoiseCall assignedNoiseCall(String body, String variable) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(variable)
                + "\\s*=\\s*(wg_noise_perlin_[0-9]+)\\s*\\(").matcher(body);
        if (!matcher.find()) return null;
        int open = body.indexOf('(', matcher.start(1));
        int close = matchingParenthesis(body, open);
        List<String> arguments = splitTopLevelArguments(body.substring(open + 1, close));
        if (arguments.size() != 3) return null;
        return new NoiseCall(matcher.group(1), arguments, matcher.start(1), close + 1);
    }

    private static String functionBody(String source, GlslFunction function) {
        int open = source.indexOf('{', function.start());
        if (open < 0 || open >= function.end()) {
            throw new IllegalArgumentException("Malformed generated function: " + function.name());
        }
        return source.substring(open + 1, function.end());
    }

    private static List<String> splitTopLevelArguments(String arguments) {
        List<String> result = new ArrayList<>();
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        int start = 0;
        for (int index = 0; index < arguments.length(); index++) {
            char value = arguments.charAt(index);
            if (value == '(') parentheses++;
            else if (value == ')') parentheses--;
            else if (value == '[') brackets++;
            else if (value == ']') brackets--;
            else if (value == '{') braces++;
            else if (value == '}') braces--;
            else if (value == ',' && parentheses == 0 && brackets == 0 && braces == 0) {
                result.add(arguments.substring(start, index).trim());
                start = index + 1;
            }
            if (parentheses < 0 || brackets < 0 || braces < 0) {
                throw new IllegalArgumentException("Malformed captured normal-noise argument list");
            }
        }
        if (parentheses != 0 || brackets != 0 || braces != 0) {
            throw new IllegalArgumentException("Malformed captured normal-noise argument list");
        }
        String last = arguments.substring(start).trim();
        if (!last.isEmpty()) result.add(last);
        return List.copyOf(result);
    }

    private static VulkanWorldgenExecutor.RawResult executeStagedNormalNoise(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            DirectNormalNoise stage, int[] coordinateWords, int defaultStateId,
            int airStateId, int invalidStateId, int batchSize) {
        int elementCount = coordinateWords.length / 4;
        if (coordinateWords.length == 0 || coordinateWords.length % 4 != 0) {
            throw new IllegalArgumentException("Direct normal-noise coordinates must contain four words per element");
        }
        if (sharedNormalNoiseResidentEnabled()) {
            return executeResidentSharedNormalNoise(executor, completeShader, stage, List.of(),
                    coordinateWords, 4, defaultStateId, airStateId, invalidStateId, batchSize);
        }
        // Native-draft arithmetic is intentionally contained away from the
        // captured normal-noise leaf.  The Perlin sample shaders already use
        // the retained exact carrier shader; use that same shader for the
        // coordinate transforms and final combine as well.  Otherwise the
        // native FP32/FP64 rewrite changes the transformed coordinates before
        // the exact Perlin sample ever sees them.
        WorldgenShaderCompiler.Shader normalNoiseShader = normalNoiseCompleteShader(completeShader);
        WorldgenShaderCompiler.Shader coordinateShader = stageShader(normalNoiseShader,
                directNormalNoiseCoordinateSource(normalNoiseShader.source(), stage),
                "density-normal-noise-coordinates-" + stage.root());
        SpirvNumericContract.require(coordinateShader.source(), coordinateShader.profile());
        VulkanWorldgenExecutor.RawResult preparedCoordinates = executor.executeRawBatched(
                directDensityStageRequest(new VulkanWorldgenExecutor.RawRequest(
                        coordinateShader, coordinateWords, 4, NORMAL_NOISE_COORD_OUTPUT_WORDS,
                        elementCount, defaultStateId, airStateId, invalidStateId))
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        boolean stageDebugEnabled = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugStages", "false"));
        stageDebug(stageDebugEnabled, "density-direct-normal-noise-coordinates root=" + stage.root()
                + debugNoiseCoordinateValues(preparedCoordinates.outputWords(), coordinateWords));
        int[] preparedWords = preparedCoordinates.outputWords();
        WorldgenShaderCompiler.Shader secondCoordinateShader = stageShader(normalNoiseShader,
                directNormalNoiseTransformedCoordinateSource(normalNoiseShader.source(), stage),
                "density-normal-noise-second-coordinates-" + stage.root());
        SpirvNumericContract.require(secondCoordinateShader.source(), secondCoordinateShader.profile());
        VulkanWorldgenExecutor.RawResult secondPreparedCoordinates = executor.executeRawBatched(
                directDensityStageRequest(new VulkanWorldgenExecutor.RawRequest(
                        secondCoordinateShader, coordinateWords, 4, NORMAL_NOISE_COORD_OUTPUT_WORDS,
                        elementCount, defaultStateId, airStateId, invalidStateId))
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        requireSameDevice(preparedCoordinates.device(), secondPreparedCoordinates.device(),
                "direct normal-noise second coordinate stage");
        stageDebug(stageDebugEnabled, "density-direct-normal-noise-second-coordinates root=" + stage.root()
                + debugNoiseCoordinateValues(secondPreparedCoordinates.outputWords(), coordinateWords));
        int[] secondPreparedWords = secondPreparedCoordinates.outputWords();
        int[] sampleInput = new int[Math.multiplyExact(elementCount, NORMAL_NOISE_SAMPLE_INPUT_WORDS)];
        int[] secondSampleInput = new int[Math.multiplyExact(elementCount, NORMAL_NOISE_SAMPLE_INPUT_WORDS)];
        for (int index = 0; index < elementCount; index++) {
            int coordinate = index * 4;
            int target = index * NORMAL_NOISE_SAMPLE_INPUT_WORDS;
            System.arraycopy(coordinateWords, coordinate, sampleInput, target, 4);
            System.arraycopy(preparedWords, index * NORMAL_NOISE_COORD_OUTPUT_WORDS,
                    sampleInput, target + 4, NORMAL_NOISE_COORD_OUTPUT_WORDS);
            System.arraycopy(coordinateWords, coordinate, secondSampleInput, target, 4);
            System.arraycopy(secondPreparedWords, index * NORMAL_NOISE_COORD_OUTPUT_WORDS,
                    secondSampleInput, target + 4, NORMAL_NOISE_COORD_OUTPUT_WORDS);
        }
        VulkanWorldgenExecutor.RawResult first;
        VulkanWorldgenExecutor.RawResult second;
        if (normalNoisePairShaderEnabled()) {
            VulkanWorldgenExecutor.RawResult paired = executeStagedPairedPerlinNoise(
                    executor, normalNoiseShader, stage.first().function(), stage.second().function(),
                    sampleInput, secondSampleInput, elementCount, defaultStateId, airStateId,
                    invalidStateId, batchSize, "direct-" + stage.root());
            VulkanWorldgenExecutor.RawResult[] split = splitPairedPerlinResult(paired);
            first = split[0];
            second = split[1];
        } else {
            first = executeStagedPerlinNoise(
                    executor, normalNoiseShader, stage.first().function(), sampleInput, elementCount,
                    defaultStateId, airStateId, invalidStateId, batchSize, "first-" + stage.root());
            maybeRecreateBetweenNormalNoiseHalves(executor, stageDebugEnabled,
                    "direct-" + stage.root());
            second = executeStagedPerlinNoise(
                    executor, normalNoiseShader, stage.second().function(), secondSampleInput, elementCount,
                    defaultStateId, airStateId, invalidStateId, batchSize, "second-" + stage.root());
        }
        stageDebug(stageDebugEnabled, "density-direct-normal-noise-perlin root=" + stage.root()
                + " first=" + debugSingleStageValues(first.outputWords(), coordinateWords)
                + " second=" + debugSingleStageValues(second.outputWords(), coordinateWords));
        requireSameDevice(preparedCoordinates.device(), first.device(),
                "direct normal-noise coordinate/sample stage");
        requireSameDevice(first.device(), second.device(), "direct normal-noise samples");
        int[] firstWords = first.outputWords();
        int[] secondWords = second.outputWords();
        int inputWords = NORMAL_NOISE_COMBINE_INPUT_WORDS;
        int[] combineInput = new int[Math.multiplyExact(elementCount, inputWords)];
        for (int index = 0; index < elementCount; index++) {
            int coordinate = index * 4;
            int target = index * inputWords;
            System.arraycopy(coordinateWords, coordinate, combineInput, target, 4);
            System.arraycopy(preparedWords, index * NORMAL_NOISE_COORD_OUTPUT_WORDS,
                    combineInput, target + 4, NORMAL_NOISE_COORD_OUTPUT_WORDS);
            int firstWord = index * DENSITY_STAGE_VALUE_WORDS;
            combineInput[target + 10] = firstWords[firstWord];
            combineInput[target + 11] = firstWords[firstWord + 1];
            combineInput[target + 12] = secondWords[firstWord];
            combineInput[target + 13] = secondWords[firstWord + 1];
        }
        WorldgenShaderCompiler.Shader combineShader = stageShader(normalNoiseShader,
                directNormalNoiseCombineSource(normalNoiseShader.source(), stage, inputWords,
                        NORMAL_NOISE_COMBINE_INPUT_WORDS - 2 * DENSITY_STAGE_VALUE_WORDS),
                "density-normal-noise-combine-" + stage.root());
        SpirvNumericContract.require(combineShader.source(), combineShader.profile());
        VulkanWorldgenExecutor.RawResult result = executor.executeRawBatched(
                directDensityStageRequest(new VulkanWorldgenExecutor.RawRequest(
                        combineShader, combineInput, inputWords, DENSITY_STAGE_VALUE_WORDS,
                        elementCount, defaultStateId, airStateId, invalidStateId))
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        requireSameDevice(first.device(), result.device(), "direct normal-noise combine");
        // The draft route creates several perlin pipelines for each captured
        // normal-noise leaf.  Reclaim them at the leaf boundary so NVIDIA's
        // native compiler does not retain every prior leaf until the whole
        // captured graph has finished.  This is a containment measure, not a
        // release resource policy.
        executor.reclaimPipelines();
        stageDebug(stageDebugEnabled,
                "density-direct-normal-noise root=" + stage.root() + " samples=2 elements="
                        + elementCount + debugStageValues(result.outputWords(), coordinateWords));
        return result;
    }

    /**
     * Executes a selector/rarity parent whose return expression embeds one
     * captured normal-noise call.  Child carriers are supplied in the first
     * row ABI, the parent computes the transformed noise coordinates on the
     * device, and only the two Perlin carrier values cross the host-visible
     * draft boundary before the final parent replay.  No density value is
     * evaluated on the CPU here.
     */
    private static VulkanWorldgenExecutor.RawResult executeStagedEmbeddedNormalNoise(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader completeShader,
            DirectNormalNoise stage, List<String> childRoots, int[] childInput,
            int[] coordinateWords, int defaultStateId, int airStateId, int invalidStateId,
            int batchSize) {
        int elementCount = coordinateWords.length / 4;
        int childInputWords = densityStageInputWords(childRoots);
        if (childInput.length != Math.multiplyExact(elementCount, childInputWords)) {
            throw new IllegalArgumentException("Embedded normal-noise child row has incompatible geometry");
        }
        if (sharedNormalNoiseResidentEnabled()) {
            return executeResidentSharedNormalNoise(executor, completeShader, stage, childRoots,
                    childInput, childInputWords, defaultStateId, airStateId, invalidStateId, batchSize);
        }
        // Keep the complete embedded normal-noise leaf on the exact carrier
        // shader for the same reason as the direct leaf above: native draft
        // arithmetic in either coordinate transform changes the Perlin input.
        WorldgenShaderCompiler.Shader normalNoiseShader = normalNoiseCompleteShader(completeShader);
        WorldgenShaderCompiler.Shader coordinateShader = stageShader(normalNoiseShader,
                embeddedNormalNoiseCoordinateSource(normalNoiseShader.source(), stage, childRoots,
                        childInputWords),
                "density-embedded-normal-noise-coordinates-" + stage.root());
        SpirvNumericContract.require(coordinateShader.source(), coordinateShader.profile());
        VulkanWorldgenExecutor.RawResult preparedCoordinates = executor.executeRawBatched(
                directDensityStageRequest(new VulkanWorldgenExecutor.RawRequest(
                        coordinateShader, childInput, childInputWords, NORMAL_NOISE_COORD_OUTPUT_WORDS,
                        elementCount, defaultStateId, airStateId, invalidStateId))
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        boolean stageDebugEnabled = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugStages", "false"));
        stageDebug(stageDebugEnabled, "density-embedded-normal-noise-coordinates root=" + stage.root()
                + debugNoiseCoordinateValues(preparedCoordinates.outputWords(), coordinateWords));
        int[] preparedWords = preparedCoordinates.outputWords();

        int transformedInputWords = NORMAL_NOISE_SAMPLE_INPUT_WORDS;
        int[] transformedInput = new int[Math.multiplyExact(elementCount, transformedInputWords)];
        for (int index = 0; index < elementCount; index++) {
            int coordinate = index * 4;
            int target = index * transformedInputWords;
            System.arraycopy(coordinateWords, coordinate, transformedInput, target, 4);
            System.arraycopy(preparedWords, index * NORMAL_NOISE_COORD_OUTPUT_WORDS,
                    transformedInput, target + 4, NORMAL_NOISE_COORD_OUTPUT_WORDS);
        }
        WorldgenShaderCompiler.Shader secondCoordinateShader = stageShader(normalNoiseShader,
                embeddedNormalNoiseTransformedCoordinateSource(normalNoiseShader.source(), stage,
                        transformedInputWords),
                "density-embedded-normal-noise-second-coordinates-" + stage.root());
        SpirvNumericContract.require(secondCoordinateShader.source(), secondCoordinateShader.profile());
        VulkanWorldgenExecutor.RawResult secondPreparedCoordinates = executor.executeRawBatched(
                directDensityStageRequest(new VulkanWorldgenExecutor.RawRequest(
                        secondCoordinateShader, transformedInput, transformedInputWords,
                        NORMAL_NOISE_COORD_OUTPUT_WORDS, elementCount,
                        defaultStateId, airStateId, invalidStateId))
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        requireSameDevice(preparedCoordinates.device(), secondPreparedCoordinates.device(),
                "embedded normal-noise second coordinate stage");
        int[] secondPreparedWords = secondPreparedCoordinates.outputWords();

        int[] firstSampleInput = transformedInput;
        int[] secondSampleInput = new int[Math.multiplyExact(elementCount, transformedInputWords)];
        for (int index = 0; index < elementCount; index++) {
            int coordinate = index * 4;
            int target = index * transformedInputWords;
            System.arraycopy(coordinateWords, coordinate, secondSampleInput, target, 4);
            System.arraycopy(secondPreparedWords, index * NORMAL_NOISE_COORD_OUTPUT_WORDS,
                    secondSampleInput, target + 4, NORMAL_NOISE_COORD_OUTPUT_WORDS);
        }
        VulkanWorldgenExecutor.RawResult first;
        VulkanWorldgenExecutor.RawResult second;
        if (normalNoisePairShaderEnabled()) {
            VulkanWorldgenExecutor.RawResult paired = executeStagedPairedPerlinNoise(
                    executor, normalNoiseShader, stage.first().function(), stage.second().function(),
                    firstSampleInput, secondSampleInput, elementCount, defaultStateId, airStateId,
                    invalidStateId, batchSize, "embedded-" + stage.root());
            VulkanWorldgenExecutor.RawResult[] split = splitPairedPerlinResult(paired);
            first = split[0];
            second = split[1];
        } else {
            first = executeStagedPerlinNoise(
                    executor, normalNoiseShader, stage.first().function(), firstSampleInput, elementCount,
                    defaultStateId, airStateId, invalidStateId, batchSize, "first-" + stage.root());
            maybeRecreateBetweenNormalNoiseHalves(executor, stageDebugEnabled,
                    "embedded-" + stage.root());
            second = executeStagedPerlinNoise(
                    executor, normalNoiseShader, stage.second().function(), secondSampleInput, elementCount,
                    defaultStateId, airStateId, invalidStateId, batchSize, "second-" + stage.root());
        }
        requireSameDevice(preparedCoordinates.device(), first.device(),
                "embedded normal-noise coordinate/sample stage");
        requireSameDevice(first.device(), second.device(), "embedded normal-noise samples");
        int[] firstWords = first.outputWords();
        int[] secondWords = second.outputWords();

        int combineInputWords = Math.addExact(childInputWords, 2 * DENSITY_STAGE_VALUE_WORDS);
        int[] combineInput = new int[Math.multiplyExact(elementCount, combineInputWords)];
        for (int index = 0; index < elementCount; index++) {
            int childBase = index * childInputWords;
            int target = index * combineInputWords;
            System.arraycopy(childInput, childBase, combineInput, target, childInputWords);
            int firstWord = index * DENSITY_STAGE_VALUE_WORDS;
            int noiseBase = target + childInputWords;
            combineInput[noiseBase] = firstWords[firstWord];
            combineInput[noiseBase + 1] = firstWords[firstWord + 1];
            combineInput[noiseBase + 2] = secondWords[firstWord];
            combineInput[noiseBase + 3] = secondWords[firstWord + 1];
        }
        WorldgenShaderCompiler.Shader combineShader = stageShader(normalNoiseShader,
                embeddedNormalNoiseCombineSource(normalNoiseShader.source(), stage, childRoots,
                        combineInputWords),
                "density-embedded-normal-noise-combine-" + stage.root());
        SpirvNumericContract.require(combineShader.source(), combineShader.profile());
        VulkanWorldgenExecutor.RawResult result = executor.executeRawBatched(
                directDensityStageRequest(new VulkanWorldgenExecutor.RawRequest(
                        combineShader, combineInput, combineInputWords, DENSITY_STAGE_VALUE_WORDS,
                        elementCount, defaultStateId, airStateId, invalidStateId))
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        requireSameDevice(first.device(), result.device(), "embedded normal-noise combine");
        executor.reclaimPipelines();
        stageDebug(stageDebugEnabled, "density-embedded-normal-noise root=" + stage.root()
                + " elements=" + elementCount + debugStageValues(result.outputWords(), coordinateWords));
        return result;
    }

    private static boolean sharedNormalNoiseResidentEnabled() {
        if (!Boolean.getBoolean("tellurium.gpuCandidate.sharedNormalNoiseResidentChain")) return false;
        if (!Boolean.getBoolean("tellurium.gpuCandidate.normalNoiseSharedShader")
                || !Boolean.parseBoolean(System.getProperty(
                        "tellurium.gpuCandidate.normalNoiseSharedGenericShader", "true"))
                || Boolean.getBoolean("tellurium.gpuCandidate.normalNoiseDeviceResidentChain")
                || normalNoisePairShaderEnabled()) {
            throw new IllegalArgumentException("Resident shared normal-noise requires generic shared sampling without legacy/pair chains");
        }
        return true;
    }

    /** Coordinates, both metadata-backed samples and parent combine stay on-device. */
    private static VulkanWorldgenExecutor.RawResult executeResidentSharedNormalNoise(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader complete,
            DirectNormalNoise stage, List<String> children, int[] input, int childWords,
            int defaultStateId, int airStateId, int invalidStateId, int batchSize) {
        WorldgenShaderCompiler.Shader exact = normalNoiseCompleteShader(complete);
        if (exact.profile() != NumericProfile.GPU_IEEE_BITS) {
            throw new IllegalArgumentException("Resident shared normal-noise requires integer-carrier stages");
        }
        String mode = System.getProperty("tellurium.gpuCandidate.residentNormalNoiseMode", "halves").trim();
        if (mode.equals("halves")) {
            return executeResidentNormalNoiseHalves(executor, exact, stage, children, input, childWords,
                    defaultStateId, airStateId, invalidStateId, batchSize);
        }
        if (!mode.equals("row")) throw new IllegalArgumentException("Resident normal-noise mode must be halves or row");
        int elements = input.length / childWords;
        int rowWords = Math.addExact(childWords, 16);
        int[] rows = residentNormalNoiseInput(input, childWords);
        boolean embedded = !children.isEmpty();
        String firstCoordinates = embedded
                ? embeddedNormalNoiseCoordinateSource(exact.source(), stage, children, rowWords)
                : directNormalNoiseCoordinateSource(exact.source(), stage);
        if (!embedded) firstCoordinates = residentPointStride(firstCoordinates, rowWords);
        firstCoordinates = residentNormalNoiseOutput(firstCoordinates, rowWords, 6, childWords);

        String secondCoordinates = embedded
                ? embeddedNormalNoiseTransformedCoordinateSource(exact.source(), stage, rowWords)
                : residentPointStride(directNormalNoiseTransformedCoordinateSource(exact.source(), stage), rowWords);
        if (embedded) {
            secondCoordinates = replaceStageAbiOnce(secondCoordinates,
                    "uint base = gl_GlobalInvocationID.x * " + rowWords + "u + 4u + slot * 2u;",
                    "uint base = gl_GlobalInvocationID.x * " + rowWords + "u + " + childWords + "u + slot * 2u;");
        }
        secondCoordinates = residentNormalNoiseOutput(secondCoordinates, rowWords, 6, childWords + 6);

        String generic = sharedPerlinGenericShader(exact, NORMAL_NOISE_SAMPLE_INPUT_WORDS).source();
        String firstSample = residentNormalNoiseSampler(generic, rowWords, childWords, childWords + 12);
        String secondSample = residentNormalNoiseSampler(generic, rowWords, childWords + 6, childWords + 14);
        int[] firstMetadata = sharedPerlinMetadata(exact.source(), stage.first().function(),
                perlinSampleCalls(exact.source(), stage.first().function()));
        int[] secondMetadata = sharedPerlinMetadata(exact.source(), stage.second().function(),
                perlinSampleCalls(exact.source(), stage.second().function()));

        String combined = embedded
                ? embeddedNormalNoiseCombineSource(exact.source(), stage, children, rowWords)
                : residentPointStride(directNormalNoiseCombineSource(exact.source(), stage, rowWords,
                        childWords + 12), rowWords);
        if (embedded) {
            combined = replaceStageAbiOnce(combined,
                    "uint base = gl_GlobalInvocationID.x * " + rowWords + "u + " + childWords + "u + slot * 2u;",
                    "uint base = gl_GlobalInvocationID.x * " + rowWords + "u + " + (childWords + 12) + "u + slot * 2u;");
        }

        List<VulkanWorldgenExecutor.RawStage> chain = new ArrayList<>(5);
        chain.add(residentNormalNoiseStage(exact, firstCoordinates, rowWords, rowWords,
                "first-coordinates-" + stage.root(), false, null));
        chain.add(residentNormalNoiseStage(exact, firstSample, rowWords, rowWords,
                "first-sample", true, firstMetadata));
        chain.add(residentNormalNoiseStage(exact, secondCoordinates, rowWords, rowWords,
                "second-coordinates-" + stage.root(), false, null));
        chain.add(residentNormalNoiseStage(exact, secondSample, rowWords, rowWords,
                "second-sample", true, secondMetadata));
        chain.add(residentNormalNoiseStage(exact, combined, rowWords, DENSITY_STAGE_VALUE_WORDS,
                "combine-" + stage.root(), false, null));
        VulkanWorldgenExecutor.RawResult result = executor.executeRawChainBatched(rows, rowWords, elements,
                defaultStateId, airStateId, invalidStateId, chain, batchSize);
        executor.reclaimPipelines();
        stageDebug(Boolean.getBoolean("tellurium.gpuCandidate.debugStages"),
                "density-normal-noise-resident root=" + stage.root() + " embedded=" + embedded
                        + " elements=" + elements + " rowWords=" + rowWords + " stages=5"
                        + debugStageValues(result.outputWords(), Arrays.copyOf(input, 4)));
        return result;
    }

    private static VulkanWorldgenExecutor.RawResult executeResidentNormalNoiseHalves(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader exact, DirectNormalNoise stage,
            List<String> children, int[] input, int childWords, int defaultStateId,
            int airStateId, int invalidStateId, int batchSize) {
        int elements = input.length / childWords;
        boolean embedded = !children.isEmpty();
        String firstCoordinates = embedded
                ? embeddedNormalNoiseCoordinateSource(exact.source(), stage, children, childWords)
                : directNormalNoiseCoordinateSource(exact.source(), stage);
        VulkanWorldgenExecutor.RawResult first = executeResidentNoiseHalf(executor, exact,
                firstCoordinates, stage.first().function(), input, childWords, true,
                defaultStateId, airStateId, invalidStateId, batchSize, "first-" + stage.root());
        int[] firstWords = first.outputWords(); // six coordinate words, then two Perlin words
        int[] secondInput = input;
        int secondInputWords = childWords;
        String secondCoordinates;
        if (embedded) {
            secondInputWords = NORMAL_NOISE_SAMPLE_INPUT_WORDS;
            secondInput = new int[Math.multiplyExact(elements, secondInputWords)];
            for (int index = 0; index < elements; index++) {
                System.arraycopy(input, index * childWords, secondInput, index * secondInputWords, 4);
                System.arraycopy(firstWords, index * 8, secondInput, index * secondInputWords + 4, 6);
            }
            secondCoordinates = embeddedNormalNoiseTransformedCoordinateSource(exact.source(), stage, secondInputWords);
        } else {
            secondCoordinates = directNormalNoiseTransformedCoordinateSource(exact.source(), stage);
        }
        VulkanWorldgenExecutor.RawResult second = executeResidentNoiseHalf(executor, exact,
                secondCoordinates, stage.second().function(), secondInput, secondInputWords, false,
                defaultStateId, airStateId, invalidStateId, batchSize, "second-" + stage.root());
        requireSameDevice(first.device(), second.device(), "resident normal-noise halves");
        int[] secondWords = second.outputWords();
        int combineWords = embedded ? childWords + 4 : NORMAL_NOISE_COMBINE_INPUT_WORDS;
        int[] combined = new int[Math.multiplyExact(elements, combineWords)];
        for (int index = 0; index < elements; index++) {
            int target = index * combineWords;
            System.arraycopy(input, index * childWords, combined, target, childWords);
            int sampleOffset;
            if (embedded) {
                sampleOffset = childWords;
            } else {
                System.arraycopy(firstWords, index * 8, combined, target + 4, 6);
                sampleOffset = 10;
            }
            System.arraycopy(firstWords, index * 8 + 6, combined, target + sampleOffset, 2);
            System.arraycopy(secondWords, index * 2, combined, target + sampleOffset + 2, 2);
        }
        String source = embedded
                ? embeddedNormalNoiseCombineSource(exact.source(), stage, children, combineWords)
                : directNormalNoiseCombineSource(exact.source(), stage, combineWords, 10);
        var shader = stageShader(exact, source, "normal-noise-resident-half-combine-" + stage.root());
        SpirvNumericContract.require(shader.source(), shader.profile());
        var result = executor.executeRawBatched(directDensityStageRequest(new VulkanWorldgenExecutor.RawRequest(
                shader, combined, combineWords, 2, elements, defaultStateId, airStateId, invalidStateId))
                .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        requireSameDevice(first.device(), result.device(), "resident normal-noise final combine");
        executor.reclaimPipelines();
        stageDebug(Boolean.getBoolean("tellurium.gpuCandidate.debugStages"),
                "density-normal-noise-resident root=" + stage.root() + " mode=halves embedded=" + embedded
                        + " elements=" + elements + " stages=5");
        return result;
    }

    private static VulkanWorldgenExecutor.RawResult executeResidentNoiseHalf(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader exact,
            String coordinateSource, String perlinFunction, int[] input, int inputWords,
            boolean exportCoordinates, int defaultStateId, int airStateId, int invalidStateId,
            int batchSize, String label) {
        StringBuilder prefix = new StringBuilder("uint outputBase = index * 10u;\n");
        for (int word = 0; word < 4; word++) {
            prefix.append("outputBits[outputBase + ").append(word).append("u] = inputBits[index * ")
                    .append(inputWords).append("u + ").append(word).append("u];\n");
        }
        prefix.append("outputBase += 4u;");
        coordinateSource = replaceStageAbiOnce(coordinateSource, "uint outputBase = index * 6u;", prefix.toString());
        var coordinateShader = stageShader(exact, coordinateSource, "normal-noise-resident-half-coordinates-" + label);
        SpirvNumericContract.require(coordinateShader.source(), coordinateShader.profile());
        String coordinatePrefix = densityDontInlinePrefix();
        boolean dontInline = !coordinatePrefix.isEmpty() && !coordinatePrefix.equalsIgnoreCase("NONE");
        var coordinateStage = new VulkanWorldgenExecutor.RawStage(coordinateShader, inputWords, 10,
                densityPipelineOptimizationDisabled(), dontInline, dontInline ? coordinatePrefix : "");
        if (exportCoordinates) coordinateStage = coordinateStage.withExportedOutput(4, 6);
        var sampleShader = sharedPerlinGenericShader(exact, NORMAL_NOISE_SAMPLE_INPUT_WORDS);
        int[] metadata = sharedPerlinMetadata(exact.source(), perlinFunction,
                perlinSampleCalls(exact.source(), perlinFunction));
        String samplePrefix = normalNoiseDontInlinePrefix(sampleShader);
        var sampleStage = new VulkanWorldgenExecutor.RawStage(sampleShader, 10, 2,
                densityPipelineOptimizationDisabled(), !samplePrefix.equalsIgnoreCase("NONE"),
                samplePrefix.equalsIgnoreCase("NONE") ? "" : samplePrefix).withUniformInputWords(metadata);
        return executor.executeRawChainBatched(input, inputWords, input.length / inputWords,
                defaultStateId, airStateId, invalidStateId, List.of(coordinateStage, sampleStage), batchSize);
    }

    private static VulkanWorldgenExecutor.RawStage residentNormalNoiseStage(
            WorldgenShaderCompiler.Shader exact, String source, int inputWords, int outputWords,
            String label, boolean sampler, int[] metadata) {
        WorldgenShaderCompiler.Shader shader = stageShader(exact, source, "normal-noise-resident-" + label);
        SpirvNumericContract.require(shader.source(), shader.profile());
        writeLiveStageDiagnostic("normal-noise-resident-" + label + ".comp", source);
        String prefix = sampler ? System.getProperty(
                "tellurium.gpuCandidate.residentNormalNoiseSamplerDontInlinePrefix", "wg_shared_").trim()
                : densityDontInlinePrefix();
        if (sampler && prefix.isEmpty()) {
            throw new IllegalArgumentException("Resident sampler requires a prefix or NONE");
        }
        boolean dontInline = !prefix.isEmpty() && !prefix.equalsIgnoreCase("NONE");
        VulkanWorldgenExecutor.RawStage raw = new VulkanWorldgenExecutor.RawStage(shader,
                inputWords, outputWords, densityPipelineOptimizationDisabled(), dontInline,
                dontInline ? prefix : "");
        return metadata == null ? raw : raw.withUniformInputWords(metadata);
    }

    static int[] residentNormalNoiseInput(int[] input, int childWords) {
        if (childWords < 4 || input == null || input.length == 0 || input.length % childWords != 0) {
            throw new IllegalArgumentException("Resident normal-noise requires complete coordinate/child rows");
        }
        int rowWords = Math.addExact(childWords, 16);
        int elements = input.length / childWords;
        int[] rows = new int[Math.multiplyExact(elements, rowWords)];
        for (int index = 0; index < elements; index++) {
            System.arraycopy(input, index * childWords, rows, index * rowWords, childWords);
        }
        return rows;
    }

    static String replaceStageAbiOnce(String source, String from, String to) {
        int first = source.indexOf(from);
        if (first < 0 || source.indexOf(from, first + from.length()) >= 0 || from.isEmpty()) {
            throw new IllegalArgumentException("Resident stage ABI anchor is absent or ambiguous: " + from);
        }
        return source.substring(0, first) + to + source.substring(first + from.length());
    }

    private static String residentPointStride(String source, int rowWords) {
        return replaceStageAbiOnce(source, "uint base = index * 4u;",
                "uint base = index * " + rowWords + "u;");
    }

    static String residentNormalNoiseOutput(String source, int rowWords, int valueWords, int valueOffset) {
        if (rowWords < 20 || valueWords <= 0 || valueOffset < 4 || valueOffset > rowWords - valueWords) {
            throw new IllegalArgumentException("Invalid resident normal-noise output slice");
        }
        StringBuilder copied = new StringBuilder("uint rowBase = index * ")
                .append(rowWords).append("u;\n");
        // Keep the preserve-row accesses explicit: a second dynamic SSBO loop
        // around software IEEE sampling exceeds the target driver's JIT envelope.
        for (int word = 0; word < rowWords; word++) {
            copied.append("outputBits[rowBase + ").append(word).append("u] = inputBits[rowBase + ")
                    .append(word).append("u];\n");
        }
        copied.append("uint outputBase = rowBase + ").append(valueOffset).append("u;");
        return replaceStageAbiOnce(source, "uint outputBase = index * " + valueWords + "u;", copied.toString());
    }

    static String residentNormalNoiseSampler(String source, int rowWords, int coordinateOffset, int valueOffset) {
        if (coordinateOffset < 4 || coordinateOffset > rowWords - 6) {
            throw new IllegalArgumentException("Invalid resident normal-noise coordinate slice");
        }
        source = replaceStageAbiOnce(source, "uint metadataBase = dispatch.count * 10u;",
                "uint metadataBase = dispatch.count * " + rowWords + "u;");
        source = replaceStageAbiOnce(source, "wg_shared_perlin_generic(index * 10u)",
                "wg_shared_perlin_generic(index * " + rowWords + "u + " + (coordinateOffset - 4) + "u)");
        return "// wg_shared_normal_resident_sampler\n"
                + residentNormalNoiseOutput(source, rowWords, 2, valueOffset);
    }

    private static WorldgenShaderCompiler.Shader normalNoiseCompleteShader(
            WorldgenShaderCompiler.Shader fallback) {
        if (fallback.profile() == NumericProfile.GPU_NATIVE_DRAFT
                && Boolean.parseBoolean(System.getProperty(
                        "tellurium.gpuCandidate.debugDensityStageRootGpuChildrenNativeNoise", "false"))) {
            // The recursive child route is an explicit compiler-envelope
            // diagnostic.  Let it keep captured normal-noise leaves in the
            // native profile when requested instead of expanding the full
            // integer IEEE closure for every leaf.  This never changes the
            // ordinary route and never qualifies the resulting values.
            return fallback;
        }
        WorldgenShaderCompiler.Shader exact = EXACT_NORMAL_NOISE_SHADER.get();
        return exact == null ? fallback : exact;
    }

    /**
     * Native-draft normal-noise leaves are a pair of Perlin calls over two
     * transformed coordinates. Compile that pair as one bounded module so
     * the prototype does not pay four separate Perlin/sample pipeline builds
     * for every captured leaf. The final density combine remains a separate
     * carrier stage, preserving the existing device-side arithmetic boundary.
     */
    private static boolean normalNoisePairShaderEnabled() {
        String configured = System.getProperty(
                "tellurium.gpuCandidate.normalNoisePairShader", "").trim();
        if (!configured.isEmpty()) return Boolean.parseBoolean(configured);
        // Keep the larger paired module opt-in until the target driver's
        // compile envelope is measured; the split host-staged path remains
        // the draft default.
        return false;
    }

    private static void maybeRecreateBetweenNormalNoiseHalves(
            VulkanWorldgenExecutor executor, boolean stageDebugEnabled, String label) {
        maybeRecreateNormalNoiseDevice(executor, stageDebugEnabled,
                "between-halves label=" + label);
    }

    private static void maybeRecreateBetweenNormalNoiseGroups(
            VulkanWorldgenExecutor executor, boolean stageDebugEnabled, String label) {
        maybeRecreateNormalNoiseDevice(executor, stageDebugEnabled,
                "between-groups label=" + label);
    }

    private static void maybeRecreateNormalNoiseDevice(
            VulkanWorldgenExecutor executor, boolean stageDebugEnabled, String label) {
        if (!Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.recreateDeviceBetweenNormalNoiseHalves", "false"))) {
            return;
        }
        // The full native draft can exhaust the driver compiler while moving
        // from the first captured Perlin half to the second, before a density
        // stage boundary is reached. This opt-in boundary intentionally
        // trades persistent-device reuse for a clean compiler state; all
        // carriers are host words at this point.
        executor.recreateDevice();
        stageDebug(stageDebugEnabled,
                "density-normal-noise-device-recreate-" + label);
    }

    private static VulkanWorldgenExecutor.RawResult executeStagedPairedPerlinNoise(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader shader,
            String firstFunction, String secondFunction, int[] firstSampleInput,
            int[] secondSampleInput, int elementCount, int defaultStateId, int airStateId,
            int invalidStateId, int batchSize, String label) {
        int inputWords = Math.multiplyExact(2, NORMAL_NOISE_SAMPLE_INPUT_WORDS);
        int[] pairInput = new int[Math.multiplyExact(elementCount, inputWords)];
        for (int index = 0; index < elementCount; index++) {
            int source = index * NORMAL_NOISE_SAMPLE_INPUT_WORDS;
            int target = index * inputWords;
            System.arraycopy(firstSampleInput, source, pairInput, target,
                    NORMAL_NOISE_SAMPLE_INPUT_WORDS);
            System.arraycopy(secondSampleInput, source, pairInput,
                    target + NORMAL_NOISE_SAMPLE_INPUT_WORDS, NORMAL_NOISE_SAMPLE_INPUT_WORDS);
        }
        WorldgenShaderCompiler.Shader pairShader = stageShader(shader,
                directPerlinPairSource(shader.source(), firstFunction, secondFunction, inputWords),
                "density-normal-perlin-pair-" + label);
        SpirvNumericContract.require(pairShader.source(), pairShader.profile());
        VulkanWorldgenExecutor.RawRequest request = normalNoiseSampleRequest(
                new VulkanWorldgenExecutor.RawRequest(pairShader, pairInput, inputWords, 4,
                        elementCount, defaultStateId, airStateId, invalidStateId)
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), pairShader);
        VulkanWorldgenExecutor.RawResult result = executor.executeRawBatched(request, batchSize);
        stageDebug(Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugStages", "false")),
                "density-normal-perlin-pair-complete first=" + firstFunction
                        + " second=" + secondFunction + " elements=" + elementCount
                        + " label=" + label);
        return result;
    }

    private static VulkanWorldgenExecutor.RawResult[] splitPairedPerlinResult(
            VulkanWorldgenExecutor.RawResult paired) {
        if (paired.outputWordsPerElement() != 4) {
            throw new IllegalStateException("Paired Perlin result must contain four words per element");
        }
        int elementCount = paired.elementCount();
        int[] first = new int[Math.multiplyExact(elementCount, DENSITY_STAGE_VALUE_WORDS)];
        int[] second = new int[Math.multiplyExact(elementCount, DENSITY_STAGE_VALUE_WORDS)];
        int[] pairedWords = paired.outputWords();
        for (int index = 0; index < elementCount; index++) {
            int source = index * 4;
            int target = index * DENSITY_STAGE_VALUE_WORDS;
            first[target] = pairedWords[source];
            first[target + 1] = pairedWords[source + 1];
            second[target] = pairedWords[source + 2];
            second[target + 1] = pairedWords[source + 3];
        }
        return new VulkanWorldgenExecutor.RawResult[] {
                new VulkanWorldgenExecutor.RawResult(first, paired.device(), paired.shaderHash(),
                        paired.spirvHash(), elementCount, DENSITY_STAGE_VALUE_WORDS),
                new VulkanWorldgenExecutor.RawResult(second, paired.device(), paired.shaderHash(),
                        paired.spirvHash(), elementCount, DENSITY_STAGE_VALUE_WORDS)
        };
    }

    /**
     * Native draft normal-noise samples are deliberately kept behind callable
     * boundaries.  The exact IEEE route historically ran these two tiny
     * shaders with unrestricted inlining; the native FP64 helpers are much
     * cheaper to execute than to repeatedly expand through every octave.
     * Keep the old exact behavior as the default for the qualified profile and
     * make the draft policy overrideable while the driver envelope is being
     * characterized.
     */
    private static VulkanWorldgenExecutor.RawRequest normalNoiseSampleRequest(
            VulkanWorldgenExecutor.RawRequest request, WorldgenShaderCompiler.Shader shader) {
        String prefix = normalNoiseDontInlinePrefix(shader);
        if (prefix.equalsIgnoreCase("NONE")) return request.withDontInlineFunctions(false, "");
        return request.withDontInlineFunctions(prefix);
    }

    private static String normalNoiseDontInlinePrefix(WorldgenShaderCompiler.Shader shader) {
        String configured = System.getProperty(
                "tellurium.gpuCandidate.normalNoiseDontInlinePrefix", "").trim();
        String prefix = configured.isEmpty()
                ? (shader.profile() == NumericProfile.GPU_NATIVE_DRAFT ? "wg_noise_,wg_fp64_" : "NONE")
                : configured;
        if (prefix.equalsIgnoreCase("NONE")) return "NONE";
        for (String part : prefix.split(",", -1)) {
            if (part.isBlank()) {
                throw new IllegalArgumentException(
                        "GPU candidate normalNoiseDontInlinePrefix contains an empty function prefix");
            }
        }
        return prefix;
    }

    /** Executes one captured multi-octave Perlin call through bounded GPU stages. */
    private static VulkanWorldgenExecutor.RawResult executeStagedPerlinNoise(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader shader,
            String perlinFunction, int[] sampleInput, int elementCount,
            int defaultStateId, int airStateId, int invalidStateId, int batchSize,
            String label) {
        List<NoiseCall> samples = perlinSampleCalls(shader.source(), perlinFunction);
        // normalNoiseCompleteShader intentionally retains the exact carrier
        // shader for the hybrid native draft, so its profile is not the route
        // profile. Select draft grouping from the explicit route flag.
        // Native-draft normal-noise modules retain the exact carrier shader
        // for the captured leaf, so the target driver sees a larger helper
        // closure than the route profile suggests. Keep native modules at one
        // octave by default to bound that compile envelope; exact modules can
        // use two octaves, and every profile still has an explicit override.
        int defaultPerlinGroupSize = shader.profile() == NumericProfile.GPU_NATIVE_DRAFT ? 1 : 2;
        int perlinGroupSize = integerProperty(
                "tellurium.gpuCandidate.normalNoisePerlinGroupSize", defaultPerlinGroupSize);
        if (perlinGroupSize <= 0 || perlinGroupSize > 4) {
            throw new IllegalArgumentException(
                "GPU candidate normalNoisePerlinGroupSize must be in [1, 4]");
        }
        boolean deviceResidentChain = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.normalNoiseDeviceResidentChain", "false"));
        if (!deviceResidentChain && Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.normalNoiseSharedShader", "false"))) {
            return executeSharedPerlinNoise(executor, shader, perlinFunction, samples,
                    sampleInput, elementCount, defaultStateId, airStateId, invalidStateId,
                    batchSize, label);
        }
        int aggregateWords = NORMAL_NOISE_SAMPLE_INPUT_WORDS
                + Math.multiplyExact(samples.size(), DENSITY_STAGE_VALUE_WORDS);
        int[] chainInput = new int[Math.multiplyExact(elementCount, aggregateWords)];
        for (int element = 0; element < elementCount; element++) {
            System.arraycopy(sampleInput, element * NORMAL_NOISE_SAMPLE_INPUT_WORDS,
                    chainInput, element * aggregateWords, NORMAL_NOISE_SAMPLE_INPUT_WORDS);
        }
        if (!deviceResidentChain) {
            return executeHostStagedPerlinNoise(executor, shader, perlinFunction, samples,
                    perlinGroupSize, chainInput, aggregateWords, elementCount,
                    defaultStateId, airStateId, invalidStateId, batchSize, label);
        }
        List<VulkanWorldgenExecutor.RawStage> chain = new ArrayList<>();
        String dontInlinePrefix = normalNoiseDontInlinePrefix(shader);
        boolean dontInline = !dontInlinePrefix.equalsIgnoreCase("NONE");
        for (int start = 0; start < samples.size(); start += perlinGroupSize) {
            List<NoiseCall> group = samples.subList(start,
                    Math.min(start + perlinGroupSize, samples.size()));
            WorldgenShaderCompiler.Shader sampleShader = stageShader(shader,
                    directPerlinSampleGroupSource(shader.source(), perlinFunction, group,
                            start, aggregateWords),
                    "density-normal-perlin-sample-" + label + "-" + start);
            SpirvNumericContract.require(sampleShader.source(), sampleShader.profile());
            chain.add(new VulkanWorldgenExecutor.RawStage(sampleShader, aggregateWords, aggregateWords,
                    densityPipelineOptimizationDisabled(), dontInline, dontInlinePrefix));
        }
        WorldgenShaderCompiler.Shader combineShader = stageShader(shader,
                directPerlinCombineSource(shader.source(), perlinFunction, samples.size(), aggregateWords),
                "density-normal-perlin-combine-" + label);
        SpirvNumericContract.require(combineShader.source(), combineShader.profile());
        chain.add(new VulkanWorldgenExecutor.RawStage(combineShader, aggregateWords,
                DENSITY_STAGE_VALUE_WORDS, densityPipelineOptimizationDisabled(), false, ""));
        VulkanWorldgenExecutor.RawResult combined = executor.executeRawChainBatched(
                chainInput, aggregateWords, elementCount, defaultStateId, airStateId, invalidStateId,
                chain, batchSize);
        stageDebug(Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugStages", "false")),
                "density-normal-perlin-complete function=" + perlinFunction
                        + " samples=" + samples.size() + " elements=" + elementCount);
        return combined;
    }

    /**
     * Single-dispatch metadata-driven Perlin path for the large captured
     * Overworld graph. The arithmetic remains on the device while one generic
     * sampler reuses the pipeline across captured leaves. The generic shader
     * reads one immutable metadata suffix per batch, rather than duplicating
     * factors, sampler controls, offsets and permutation tables in every row.
     * This is still an opt-in driver experiment until the widened row ABI has
     * focused multi-context parity evidence.
     */
    private static VulkanWorldgenExecutor.RawResult executeSharedPerlinNoise(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader shader,
            String perlinFunction, List<NoiseCall> samples, int[] sampleInput,
            int elementCount, int defaultStateId, int airStateId, int invalidStateId,
            int batchSize, String label) {
        // Do not compile the captured Perlin function itself for this opt-in
        // route.  Its static closure includes every captured permutation table
        // and is the main source of the driver/compiler peak.  The metadata
        // ABI below keeps the exact captured factors, offsets and permutation
        // bytes in the row while the shader reuses one bounded sampler.
        int[] metadata = sharedPerlinMetadata(shader.source(), perlinFunction, samples);
        boolean genericSharedShader = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.normalNoiseSharedGenericShader", "true"));
        int inputWords = genericSharedShader ? NORMAL_NOISE_SAMPLE_INPUT_WORDS : SHARED_PERLIN_INPUT_WORDS;
        int[] input;
        if (genericSharedShader) {
            input = sharedPerlinSuffixInput(sampleInput, metadata, elementCount);
        } else {
            input = new int[Math.multiplyExact(elementCount, inputWords)];
            for (int element = 0; element < elementCount; element++) {
                int sourceBase = element * NORMAL_NOISE_SAMPLE_INPUT_WORDS;
                int targetBase = element * inputWords;
                System.arraycopy(sampleInput, sourceBase, input, targetBase,
                        NORMAL_NOISE_SAMPLE_INPUT_WORDS);
                System.arraycopy(metadata, 0, input,
                        targetBase + NORMAL_NOISE_SAMPLE_INPUT_WORDS, metadata.length);
            }
        }
        WorldgenShaderCompiler.Shader sharedShader;
        if (genericSharedShader) {
            sharedShader = sharedPerlinGenericShader(shader, inputWords);
        } else {
            // Keep a bounded fallback for drivers that cannot compile the
            // dynamic level loop/table indexing. This shader is still one
            // unrolled metadata function per captured Perlin leaf, but it
            // avoids rebuilding the original captured helper closure.
            sharedShader = stageShader(shader,
                    sharedPerlinSource(shader.source(), perlinFunction, inputWords, samples, metadata),
                    "density-normal-perlin-shared-unrolled-" + label);
        }
        SpirvNumericContract.require(sharedShader.source(), sharedShader.profile());
        VulkanWorldgenExecutor.RawRequest request = new VulkanWorldgenExecutor.RawRequest(
                sharedShader, input, inputWords, DENSITY_STAGE_VALUE_WORDS,
                elementCount, defaultStateId, airStateId, invalidStateId)
                .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled());
        String dontInlinePrefix = normalNoiseDontInlinePrefix(shader);
        if (!dontInlinePrefix.equalsIgnoreCase("NONE")) {
            request = request.withDontInlineFunctions(dontInlinePrefix);
        }
        VulkanWorldgenExecutor.RawResult result = executor.executeRawBatched(request, batchSize);
        stageDebug(Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugStages", "false")),
                 "density-normal-perlin-complete function=" + perlinFunction
                         + " samples=" + samples.size() + " mode=shared-metadata levels="
                         + samples.size() + " rowWords=" + inputWords
                         + " generic=" + genericSharedShader
                         + " metadataLayout=" + (genericSharedShader ? "batch-suffix" : "per-row")
                         + " label=" + label + " elements=" + elementCount);
        return result;
    }

    /** RawRequest.slice preserves this suffix and rebases it after each batch's rows. */
    static int[] sharedPerlinSuffixInput(int[] sampleInput, int[] metadata, int elementCount) {
        if (elementCount <= 0 || sampleInput.length != Math.multiplyExact(elementCount,
                NORMAL_NOISE_SAMPLE_INPUT_WORDS) || metadata.length != SHARED_PERLIN_METADATA_WORDS) {
            throw new IllegalArgumentException("Shared Perlin suffix needs complete sample rows and metadata");
        }
        int[] input = new int[Math.addExact(sampleInput.length, metadata.length)];
        System.arraycopy(sampleInput, 0, input, 0, sampleInput.length);
        System.arraycopy(metadata, 0, input, sampleInput.length, metadata.length);
        return input;
    }

    /**
     * Builds one exact shader whose captured octave/permutation metadata is
     * supplied once after the current batch's sample rows. Keeping the body independent of a
     * particular captured Perlin function lets the executor reuse one compiled
     * pipeline across all normal-noise leaves in the recursive diagnostic.
     */
    private static WorldgenShaderCompiler.Shader sharedPerlinGenericShader(
            WorldgenShaderCompiler.Shader complete, int inputWords) {
        Map<String, WorldgenShaderCompiler.Shader> cache =
                SHARED_PERLIN_GENERIC_SHADER_CACHE.get();
        WorldgenShaderCompiler.Shader cached = cache.get(complete.source());
        if (cached != null) return cached;
        String source = sharedPerlinGenericSource(complete.source(), inputWords);
        WorldgenShaderCompiler.Shader shader = stageShader(
                complete, source, "density-normal-perlin-shared-generic");
        SpirvNumericContract.require(shader.source(), shader.profile());
        cache.put(complete.source(), shader);
        return shader;
    }

    static String sharedPerlinGenericSource(String completeSource, int inputWords) {
        if (inputWords != NORMAL_NOISE_SAMPLE_INPUT_WORDS) {
            throw new IllegalArgumentException("Shared Perlin generic rows require ten sample words");
        }
        String sharedSampler = sharedPerlinSamplerSource(completeSource);
        String genericFunction = """
                uvec2 wg_shared_perlin_generic(uint base) {
                    uvec2 x = uvec2(inputBits[base + 4u], inputBits[base + 5u]);
                    uvec2 y = uvec2(inputBits[base + 6u], inputBits[base + 7u]);
                    uvec2 z = uvec2(inputBits[base + 8u], inputBits[base + 9u]);
                    uint metadataBase = dispatch.count * %du;
                    uint count = inputBits[metadataBase];
                    if (count > %du) {
                        wg_failed = true;
                        return wg_fp64_qnan();
                    }
                    uvec2 result = uvec2(0u);
                    for (uint level = 0u; level < count; level++) {
                        uint levelBase = metadataBase + 1u + level * %du;
                        if (inputBits[levelBase] == 0u) continue;
                        uvec2 inputFactor = uvec2(inputBits[levelBase + 1u], inputBits[levelBase + 2u]);
                        uvec2 valueFactor = uvec2(inputBits[levelBase + 3u], inputBits[levelBase + 4u]);
                        uvec2 amplitude = uvec2(inputBits[levelBase + 5u], inputBits[levelBase + 6u]);
                        uvec2 sampled = wg_shared_noise_sample(
                                wg_noise_wrap(wg_fp64_mul(x, inputFactor)),
                                wg_noise_wrap(wg_fp64_mul(y, inputFactor)),
                                wg_noise_wrap(wg_fp64_mul(z, inputFactor)),
                                uvec2(inputBits[levelBase + 7u], inputBits[levelBase + 8u]),
                                uvec2(inputBits[levelBase + 9u], inputBits[levelBase + 10u]),
                                uvec2(inputBits[levelBase + 11u], inputBits[levelBase + 12u]),
                                uvec2(inputBits[levelBase + 13u], inputBits[levelBase + 14u]),
                                uvec2(inputBits[levelBase + 15u], inputBits[levelBase + 16u]),
                                inputBits[levelBase + 17u] != 0u, levelBase + 18u);
                        uvec2 weighted = wg_fp64_mul(
                                wg_fp64_mul(amplitude, sampled), valueFactor);
                        result = wg_fp64_add(result, weighted);
                    }
                    return result;
                }
                """.formatted(inputWords, SHARED_PERLIN_MAX_LEVELS, SHARED_PERLIN_LEVEL_WORDS);
        String source = compactSource(stageSourcePrefix(completeSource) + sharedSampler + genericFunction,
                Set.of("wg_shared_perlin_generic", "wg_fp64_qnan", "wg_fp64_finite"));
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uvec2 value = wg_shared_perlin_generic(index * %du);
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
                """.formatted(inputWords);
    }

    /**
     * Emits one complete captured Perlin function with its static improved-
     * noise tables. This deliberately reuses the compiler-emitted function
     * instead of reconstructing its octave metadata in a second shader ABI.
     */
    private static String directPerlinWholeSource(String completeSource, String perlinFunction,
                                                  int inputWords) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        if (!functions.containsKey(perlinFunction)) {
            throw new IllegalArgumentException("Generated shader is missing Perlin function: " + perlinFunction);
        }
        String source = compactSource(stageSourcePrefix(completeSource),
                Set.of(perlinFunction, "wg_fp64_qnan", "wg_fp64_finite"));
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uint base = index * %du;
                    uvec2 x = uvec2(inputBits[base + 4u], inputBits[base + 5u]);
                    uvec2 y = uvec2(inputBits[base + 6u], inputBits[base + 7u]);
                    uvec2 z = uvec2(inputBits[base + 8u], inputBits[base + 9u]);
                    uvec2 value = %s(x, y, z);
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
                """.formatted(inputWords, perlinFunction);
    }

    /** Emits one bounded shader that evaluates both normal-noise Perlin roots. */
    private static String directPerlinPairSource(String completeSource, String firstFunction,
                                                 String secondFunction, int inputWords) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        if (!functions.containsKey(firstFunction) || !functions.containsKey(secondFunction)) {
            throw new IllegalArgumentException("Generated shader is missing paired Perlin functions: "
                    + firstFunction + ", " + secondFunction);
        }
        String source = compactSource(stageSourcePrefix(completeSource),
                Set.of(firstFunction, secondFunction, "wg_fp64_qnan", "wg_fp64_finite"));
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    uint base = index * %du;
                    wg_failed = false;
                    uvec2 first = %s(
                            uvec2(inputBits[base + 4u], inputBits[base + 5u]),
                            uvec2(inputBits[base + 6u], inputBits[base + 7u]),
                            uvec2(inputBits[base + 8u], inputBits[base + 9u]));
                    if (wg_failed || !wg_fp64_finite(first)) first = wg_fp64_qnan();
                    wg_failed = false;
                    uint secondBase = base + %du;
                    uvec2 second = %s(
                            uvec2(inputBits[secondBase + 4u], inputBits[secondBase + 5u]),
                            uvec2(inputBits[secondBase + 6u], inputBits[secondBase + 7u]),
                            uvec2(inputBits[secondBase + 8u], inputBits[secondBase + 9u]));
                    if (wg_failed || !wg_fp64_finite(second)) second = wg_fp64_qnan();
                    uint outputBase = index * 4u;
                    outputBits[outputBase] = first.x;
                    outputBits[outputBase + 1u] = first.y;
                    outputBits[outputBase + 2u] = second.x;
                    outputBits[outputBase + 3u] = second.y;
                }
                """.formatted(inputWords, firstFunction, NORMAL_NOISE_SAMPLE_INPUT_WORDS,
                        secondFunction);
    }

    private static int[] sharedPerlinMetadata(String source, String perlinFunction,
                                               List<NoiseCall> calls) {
        if (calls.isEmpty() || calls.size() > SHARED_PERLIN_MAX_LEVELS) {
            throw new IllegalArgumentException("Captured Perlin level count is outside shared-shader bound: "
                    + calls.size());
        }
        Map<String, GlslFunction> functions = parseFunctions(source);
        GlslFunction perlin = functions.get(perlinFunction);
        if (perlin == null) {
            throw new IllegalArgumentException("Generated shader is missing shared Perlin function: "
                    + perlinFunction);
        }
        String body = functionBody(source, perlin);
        int[] inputFactor = parseNamedUvec2(body, "inputFactor", perlinFunction);
        int[] valueFactor = parseNamedUvec2(body, "valueFactor", perlinFunction);
        int[] metadata = new int[SHARED_PERLIN_METADATA_WORDS];
        // Store the highest occupied level, not the number of calls: captured
        // octaves can be sparse, and the shader may stop at this bound.
        for (NoiseCall call : calls) {
            int level = perlinCallLevel(body, call);
            if (level < 0 || level >= SHARED_PERLIN_MAX_LEVELS) {
                throw new IllegalArgumentException("Captured Perlin level is outside shared-shader bound: "
                        + perlinFunction + " level=" + level);
            }
            metadata[0] = Math.max(metadata[0], level + 1);
            int levelBase = 1 + level * SHARED_PERLIN_LEVEL_WORDS;
            if (metadata[levelBase] != 0) {
                throw new IllegalArgumentException("Captured Perlin contains duplicate level " + level
                        + " in " + perlinFunction);
            }
            metadata[levelBase] = 1;
            putScaledCarrier(metadata, levelBase + 1, inputFactor, level, true);
            putScaledCarrier(metadata, levelBase + 3, valueFactor, level, false);
            int[] amplitude = parsePerlinAmplitude(body, level, perlinFunction);
            metadata[levelBase + 5] = amplitude[0];
            metadata[levelBase + 6] = amplitude[1];

            GlslFunction improved = functions.get(call.function());
            if (improved == null) {
                throw new IllegalArgumentException("Generated shader is missing improved noise function: "
                        + call.function());
            }
            String improvedBody = functionBody(source, improved);
            Matcher sampleMatcher = Pattern.compile("\\bnoise_sample\\s*\\(").matcher(improvedBody);
            if (!sampleMatcher.find()) {
                throw new IllegalArgumentException("Improved noise function has no noise_sample call: "
                        + call.function());
            }
            int open = improvedBody.indexOf('(', sampleMatcher.start());
            int close = matchingParenthesis(improvedBody, open);
            List<String> arguments = splitTopLevelArguments(improvedBody.substring(open + 1, close));
            if (arguments.size() != 10) {
                throw new IllegalArgumentException("Improved noise call ABI changed for " + call.function()
                        + ": expected 10 arguments, found " + arguments.size());
            }
            int[] xOffset = parseUvec2Literal(arguments.get(3), "xOffset " + call.function());
            int[] yOffset = parseUvec2Literal(arguments.get(4), "yOffset " + call.function());
            int[] zOffset = parseUvec2Literal(arguments.get(5), "zOffset " + call.function());
            metadata[levelBase + 7] = xOffset[0];
            metadata[levelBase + 8] = xOffset[1];
            metadata[levelBase + 9] = yOffset[0];
            metadata[levelBase + 10] = yOffset[1];
            metadata[levelBase + 11] = zOffset[0];
            metadata[levelBase + 12] = zOffset[1];
            int[] yScale = parseUvec2Literal(arguments.get(6), "yScale " + call.function());
            int[] yMax = parseUvec2Literal(arguments.get(7), "yMax " + call.function());
            metadata[levelBase + 13] = yScale[0];
            metadata[levelBase + 14] = yScale[1];
            metadata[levelBase + 15] = yMax[0];
            metadata[levelBase + 16] = yMax[1];
            metadata[levelBase + 17] = parseBooleanLiteral(arguments.get(8),
                    "smear " + call.function()) ? 1 : 0;
            int[] permutation = parsePermutationTable(source, arguments.get(9).trim(), call.function());
            System.arraycopy(permutation, 0, metadata,
                    levelBase + 18, permutation.length);
        }
        return metadata;
    }

    private static int perlinCallLevel(String body, NoiseCall call) {
        Matcher assignments = Pattern.compile("\\bsampled(\\d+)\\s*=\\s*$")
                .matcher(body.substring(0, call.start()));
        int level = -1;
        while (assignments.find()) level = Integer.parseInt(assignments.group(1));
        return level;
    }

    private static int[] parseNamedUvec2(String body, String name, String perlinFunction) {
        Matcher matcher = Pattern.compile("\\buvec2\\s+" + Pattern.quote(name)
                + "\\s*=\\s*(uvec2\\s*\\([^;]+?\\));").matcher(body);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Captured Perlin has no " + name + " setup: "
                    + perlinFunction);
        }
        return parseUvec2Literal(matcher.group(1), name + " " + perlinFunction);
    }

    private static int[] parsePerlinAmplitude(String body, int level, String perlinFunction) {
        Matcher matcher = Pattern.compile("\\bweighted" + level
                + "\\s*=\\s*wg_fp64_mul\\(\\s*wg_fp64_mul\\(\\s*(uvec2\\s*\\([^)]*\\))"
                + "\\s*,\\s*sampled" + level + "\\s*\\)\\s*,\\s*valueFactor\\s*\\);")
                .matcher(body);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Captured Perlin has no amplitude for level " + level
                    + ": " + perlinFunction);
        }
        return parseUvec2Literal(matcher.group(1), "amplitude " + perlinFunction + " level=" + level);
    }

    private static void putScaledCarrier(int[] target, int offset, int[] initial,
                                         int level, boolean increase) {
        long bits = (Integer.toUnsignedLong(initial[1]) << 32)
                | Integer.toUnsignedLong(initial[0]);
        double value = Double.longBitsToDouble(bits);
        double scaled = Math.scalb(value, increase ? level : -level);
        long scaledBits = Double.doubleToRawLongBits(scaled);
        target[offset] = (int) scaledBits;
        target[offset + 1] = (int) (scaledBits >>> 32);
    }

    private static int[] parseUvec2Literal(String expression, String label) {
        Matcher pair = Pattern.compile("uvec2\\s*\\(\\s*([^,]+?)\\s*,\\s*([^)]*?)\\s*\\)")
                .matcher(expression);
        if (pair.find()) {
            return new int[]{parseUnsignedWord(pair.group(1), label),
                    parseUnsignedWord(pair.group(2), label)};
        }
        // GLSL scalar constructors replicate the scalar across both lanes;
        // Minecraft emits uvec2(0u) for the usual zero yScale/yMax controls.
        Matcher scalar = Pattern.compile("uvec2\\s*\\(\\s*([^)]*?)\\s*\\)")
                .matcher(expression);
        if (scalar.find()) {
            int word = parseUnsignedWord(scalar.group(1), label);
            return new int[]{word, word};
        }
        throw new IllegalArgumentException("Expected uvec2 literal for " + label + ": " + expression);
    }

    private static int parseUnsignedWord(String token, String label) {
        String value = token.trim();
        if (value.endsWith("u") || value.endsWith("U")) value = value.substring(0, value.length() - 1);
        try {
            if (value.startsWith("0x") || value.startsWith("0X")) {
                return (int) Long.parseUnsignedLong(value.substring(2), 16);
            }
            return (int) Long.parseUnsignedLong(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid unsigned carrier word for " + label + ": " + token,
                    failure);
        }
    }

    private static boolean parseBooleanLiteral(String expression, String label) {
        String value = expression.trim();
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw new IllegalArgumentException("Expected boolean literal for " + label + ": " + expression);
    }

    private static int[] parsePermutationTable(String source, String name, String function) {
        Matcher matcher = Pattern.compile("(?s)\\bconst\\s+uint\\s+" + Pattern.quote(name)
                + "\\s*\\[64\\]\\s*=\\s*uint\\[64\\]\\s*\\((.*?)\\)\\s*;")
                .matcher(source);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing packed permutation table " + name
                    + " for " + function);
        }
        String[] words = matcher.group(1).split(",");
        if (words.length != 64) {
            throw new IllegalArgumentException("Packed permutation table " + name
                    + " has " + words.length + " words, expected 64");
        }
        int[] result = new int[64];
        for (int index = 0; index < words.length; index++) {
            result[index] = parseUnsignedWord(words[index], name);
        }
        return result;
    }

    private static String sharedPerlinSource(String completeSource, String perlinFunction, int inputWords,
                                             List<NoiseCall> calls, int[] metadata) {
        String sharedSampler = sharedPerlinSamplerSource(completeSource);
        if (calls == null || calls.isEmpty() || metadata == null
                || metadata.length != SHARED_PERLIN_METADATA_WORDS) {
            throw new IllegalArgumentException("Shared Perlin source needs a complete metadata row");
        }
        StringBuilder generic = new StringBuilder("uvec2 wg_shared_perlin_value(uint base) {\n")
                .append("    uvec2 x = uvec2(inputBits[base + 4u], inputBits[base + 5u]);\n")
                .append("    uvec2 y = uvec2(inputBits[base + 6u], inputBits[base + 7u]);\n")
                .append("    uvec2 z = uvec2(inputBits[base + 8u], inputBits[base + 9u]);\n")
                .append("    uvec2 result = uvec2(0u);\n");
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        GlslFunction perlin = functions.get(perlinFunction);
        if (perlin == null) {
            throw new IllegalArgumentException("Shared Perlin source is missing captured Perlin function: "
                    + perlinFunction);
        }
        String body = functionBody(completeSource, perlin);
        for (NoiseCall call : calls) {
            int level = perlinCallLevel(body, call);
            int levelBase = 1 + level * SHARED_PERLIN_LEVEL_WORDS;
            int tableOffset = 10 + levelBase + 18;
            generic.append("    uvec2 sampled").append(level).append(" = wg_shared_noise_sample(\n")
                    .append("            wg_noise_wrap(wg_fp64_mul(x, ")
                    .append(carrierLiteral(metadata, levelBase + 1)).append(")),\n")
                    .append("            wg_noise_wrap(wg_fp64_mul(y, ")
                    .append(carrierLiteral(metadata, levelBase + 1)).append(")),\n")
                    .append("            wg_noise_wrap(wg_fp64_mul(z, ")
                    .append(carrierLiteral(metadata, levelBase + 1)).append(")),\n")
                    .append("            ").append(carrierLiteral(metadata, levelBase + 7)).append(", ")
                    .append(carrierLiteral(metadata, levelBase + 9)).append(", ")
                    .append(carrierLiteral(metadata, levelBase + 11)).append(", ")
                    .append(carrierLiteral(metadata, levelBase + 13)).append(", ")
                    .append(carrierLiteral(metadata, levelBase + 15)).append(", ")
                    .append(metadata[levelBase + 17] == 0 ? "false" : "true")
                    .append(", base + ")
                    .append(tableOffset).append("u);\n")
                    .append("    uvec2 weighted").append(level).append(" = wg_fp64_mul(wg_fp64_mul(")
                    .append(carrierLiteral(metadata, levelBase + 5)).append(", sampled").append(level)
                    .append("), ").append(carrierLiteral(metadata, levelBase + 3)).append(");\n")
                    .append("    result = wg_fp64_add(result, weighted").append(level).append(");\n");
        }
        generic.append("    return result;\n}\n");
        String source = compactSource(stageSourcePrefix(completeSource) + sharedSampler + generic,
                Set.of("wg_shared_perlin_value", "wg_fp64_qnan", "wg_fp64_finite"));
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uvec2 value = wg_shared_perlin_value(index * %du);
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
                """.formatted(inputWords);
    }

    private static String carrierLiteral(int[] words, int offset) {
        long bits = (Integer.toUnsignedLong(words[offset + 1]) << 32)
                | Integer.toUnsignedLong(words[offset]);
        return carrierLiteral(bits);
    }

    /**
     * Reuses the canonical sampler arithmetic but replaces its array-table
     * ABI with a row-relative lookup. NVIDIA's draft compiler has produced
     * unreliable results for dynamically passed GLSL arrays; reading the
     * packed table directly from the carrier keeps the table dynamic without
     * crossing that driver boundary.
     */
    private static String sharedPerlinSamplerSource(String completeSource) {
        GlslFunction sampler = parseFunctions(completeSource).get("noise_sample");
        if (sampler == null) throw new IllegalArgumentException("Captured shader is missing noise_sample");
        String text = functionText(completeSource, sampler);
        int open = text.indexOf('{');
        if (open < 0 || !text.endsWith("}")) {
            throw new IllegalArgumentException("Malformed captured noise_sample function");
        }
        String body = text.substring(open + 1, text.length() - 1)
                .replace("noise_table_value(permutation,", "wg_shared_noise_table_value(tableBase,");
        if (body.contains("noise_table_value(permutation,")) {
            throw new IllegalArgumentException("Captured noise_sample table ABI did not rewrite");
        }
        return """
                uint wg_shared_noise_table_value(uint tableBase, uint index) {
                    uint packed = inputBits[tableBase + (index >> 2u)];
                    return (packed >> ((index & 3u) * 8u)) & 255u;
                }

                uvec2 wg_shared_noise_sample(uvec2 x, uvec2 y, uvec2 z,
                        uvec2 xOffset, uvec2 yOffset, uvec2 zOffset,
                        uvec2 yScale, uvec2 yMax, bool smear, uint tableBase) {%s
                }
                """.formatted(body);
    }

    /**
     * Conservative GPU fallback for drivers that cannot reliably update a
     * large raw-chain descriptor set. Every octave group still executes in a
     * Vulkan compute dispatch; only the intermediate carrier is read back and
     * uploaded between groups. Keep the resident chain available as an opt-in
     * experiment while this path is the correctness-first default.
     */
    private static VulkanWorldgenExecutor.RawResult executeHostStagedPerlinNoise(
            VulkanWorldgenExecutor executor, WorldgenShaderCompiler.Shader shader,
            String perlinFunction, List<NoiseCall> samples, int perlinGroupSize,
            int[] initialInput, int aggregateWords, int elementCount,
            int defaultStateId, int airStateId, int invalidStateId, int batchSize,
            String label) {
        int[] currentInput = initialInput;
        VulkanWorldgenExecutor.RawResult firstResult = null;
        String dontInlinePrefix = normalNoiseDontInlinePrefix(shader);
        boolean stageDebugEnabled = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugStages", "false"));
        for (int start = 0; start < samples.size(); start += perlinGroupSize) {
            List<NoiseCall> group = samples.subList(start,
                    Math.min(start + perlinGroupSize, samples.size()));
            WorldgenShaderCompiler.Shader sampleShader = stageShader(shader,
                    directPerlinSampleGroupSource(shader.source(), perlinFunction, group,
                            start, aggregateWords),
                    "density-normal-perlin-sample-host-" + label + "-" + start);
            SpirvNumericContract.require(sampleShader.source(), sampleShader.profile());
            VulkanWorldgenExecutor.RawRequest sampleRequest = new VulkanWorldgenExecutor.RawRequest(
                    sampleShader, currentInput, aggregateWords, aggregateWords, elementCount,
                    defaultStateId, airStateId, invalidStateId)
                    .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled());
            if (!dontInlinePrefix.equalsIgnoreCase("NONE")) {
                sampleRequest = sampleRequest.withDontInlineFunctions(dontInlinePrefix);
            }
            VulkanWorldgenExecutor.RawResult groupResult = executor.executeRawBatched(sampleRequest, batchSize);
            if (firstResult == null) firstResult = groupResult;
            else requireSameDevice(firstResult.device(), groupResult.device(),
                    "host-staged normal-noise samples");
            currentInput = groupResult.outputWords();
            stageDebug(stageDebugEnabled, "density-normal-perlin-group-complete function="
                    + perlinFunction + " start=" + start + " count=" + group.size()
                    + " mode=host-staged elements=" + elementCount);
            if (start + group.size() < samples.size()) {
                maybeRecreateBetweenNormalNoiseGroups(executor, stageDebugEnabled,
                        perlinFunction + "-" + start);
            }
        }
        WorldgenShaderCompiler.Shader combineShader = stageShader(shader,
                directPerlinCombineSource(shader.source(), perlinFunction, samples.size(), aggregateWords),
                "density-normal-perlin-combine-host-" + label);
        SpirvNumericContract.require(combineShader.source(), combineShader.profile());
        VulkanWorldgenExecutor.RawResult combined = executor.executeRawBatched(
                new VulkanWorldgenExecutor.RawRequest(combineShader, currentInput, aggregateWords,
                        DENSITY_STAGE_VALUE_WORDS, elementCount, defaultStateId, airStateId, invalidStateId)
                        .withPipelineOptimizationDisabled(densityPipelineOptimizationDisabled()), batchSize);
        if (firstResult != null) requireSameDevice(firstResult.device(), combined.device(),
                "host-staged normal-noise combine");
        stageDebug(stageDebugEnabled, "density-normal-perlin-complete function=" + perlinFunction
                + " samples=" + samples.size() + " mode=host-staged elements=" + elementCount);
        return combined;
    }

    private static List<NoiseCall> perlinSampleCalls(String source, String perlinFunction) {
        String body = functionBody(source, parseFunctions(source).get(perlinFunction));
        Matcher matcher = Pattern.compile(
                "\\bsampled[0-9]+\\s*=\\s*(wg_noise_[A-Za-z0-9_]+)\\s*\\(").matcher(body);
        List<NoiseCall> calls = new ArrayList<>();
        while (matcher.find()) {
            int open = body.indexOf('(', matcher.start(1));
            int close = matchingParenthesis(body, open);
            calls.add(new NoiseCall(matcher.group(1),
                    splitTopLevelArguments(body.substring(open + 1, close)),
                    matcher.start(1), close + 1));
        }
        if (calls.isEmpty()) {
            throw new IllegalArgumentException("Captured Perlin function has no sampled octaves: " + perlinFunction);
        }
        return List.copyOf(calls);
    }

    private static String perlinInputFactorSetup(String source, String perlinFunction,
                                                  int sampleIndex, int callStart) {
        String body = functionBody(source, parseFunctions(source).get(perlinFunction));
        Matcher declaration = Pattern.compile("\\buvec2\\s+inputFactor\\s*=\\s*([^;]+);").matcher(body);
        if (!declaration.find() || declaration.start() >= callStart) {
            throw new IllegalArgumentException("Captured Perlin function has no inputFactor setup: "
                    + perlinFunction);
        }
        List<String> updates = new ArrayList<>();
        Matcher update = Pattern.compile("(?m)^\\s*inputFactor\\s*=\\s*([^;]+);").matcher(body);
        while (update.find() && update.start() < callStart && update.start() >= declaration.end()) {
            updates.add("inputFactor = " + update.group(1).trim() + ";");
        }
        if (updates.size() < sampleIndex) {
            throw new IllegalArgumentException("Captured Perlin inputFactor setup is incomplete at octave "
                    + sampleIndex + " for " + perlinFunction);
        }
        StringBuilder setup = new StringBuilder("uvec2 inputFactor = ")
                .append(declaration.group(1).trim()).append(";\n");
        for (int index = 0; index < sampleIndex; index++) {
            setup.append(updates.get(index)).append('\n');
        }
        return setup.toString();
    }

    private static String directPerlinSampleGroupSource(String completeSource, String perlinFunction,
                                                         List<NoiseCall> calls, int firstSample,
                                                         int inputWords) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        Set<String> roots = new HashSet<>(Set.of("wg_fp64_qnan", "wg_fp64_finite"));
        for (NoiseCall call : calls) {
            roots.add(call.function());
            for (String expression : call.arguments()) {
                Matcher matcher = Pattern.compile("\\b(wg_[A-Za-z0-9_]*)\\s*\\(").matcher(expression);
                while (matcher.find()) {
                    if (functions.containsKey(matcher.group(1))) roots.add(matcher.group(1));
                }
            }
        }
        String source = compactSource(stageSourcePrefix(completeSource), roots);
        StringBuilder outputs = new StringBuilder();
        for (int index = 0; index < calls.size(); index++) {
            NoiseCall call = calls.get(index);
            int sampleIndex = firstSample + index;
            String arguments = String.join(", ", call.arguments());
            outputs.append("                    {\n")
                    .append("                        wg_failed = false;\n")
                    .append("                        ").append(perlinInputFactorSetup(
                            completeSource, perlinFunction, sampleIndex, call.start()))
                    .append("                        uvec2 value").append(index).append(" = ")
                    .append(call.function()).append('(').append(arguments).append(");\n")
                    .append("                        if (wg_failed || !wg_fp64_finite(value")
                    .append(index).append(") ) value").append(index).append(" = wg_fp64_qnan();\n")
                    .append("                        outputBits[outputBase + ")
                    .append(10 + sampleIndex * DENSITY_STAGE_VALUE_WORDS).append("u] = value")
                    .append(index).append(".x;\n")
                    .append("                        outputBits[outputBase + ")
                    .append(10 + sampleIndex * DENSITY_STAGE_VALUE_WORDS + 1).append("u] = value")
                    .append(index).append(".y;\n")
                    .append("                    }\n");
        }
        return source + """
                uvec2 wg_normal_perlin_input_value(uint slot) {
                    if (slot >= 3u) {
                        wg_failed = true;
                        return uvec2(0u);
                    }
                    uint base = gl_GlobalInvocationID.x * %du + 4u + slot * 2u;
                    return uvec2(inputBits[base], inputBits[base + 1u]);
                }

                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uvec2 x = wg_normal_perlin_input_value(0u);
                    uvec2 y = wg_normal_perlin_input_value(1u);
                    uvec2 z = wg_normal_perlin_input_value(2u);
                    uint outputBase = index * %du;
                    for (uint word = 0u; word < %du; word++) {
                        outputBits[outputBase + word] = inputBits[index * %du + word];
                    }
                %s
                }
                """.formatted(inputWords, inputWords, inputWords, inputWords, outputs);
    }

    private static String directPerlinSampleSource(String completeSource, String perlinFunction,
                                                    NoiseCall call, int sampleIndex, int inputWords) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        Set<String> roots = new HashSet<>(Set.of(call.function(), "wg_fp64_qnan", "wg_fp64_finite"));
        for (String expression : call.arguments()) {
            Matcher matcher = Pattern.compile("\\b(wg_[A-Za-z0-9_]*)\\s*\\(").matcher(expression);
            while (matcher.find()) {
                if (functions.containsKey(matcher.group(1))) roots.add(matcher.group(1));
            }
        }
        String source = compactSource(stageSourcePrefix(completeSource), roots);
        String arguments = String.join(", ", call.arguments());
        return source + """
                uvec2 wg_normal_perlin_input_value(uint slot) {
                    if (slot >= 3u) {
                        wg_failed = true;
                        return uvec2(0u);
                    }
                    uint base = gl_GlobalInvocationID.x * %du + 4u + slot * 2u;
                    return uvec2(inputBits[base], inputBits[base + 1u]);
                }

                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uvec2 x = wg_normal_perlin_input_value(0u);
                    uvec2 y = wg_normal_perlin_input_value(1u);
                    uvec2 z = wg_normal_perlin_input_value(2u);
                    %s
                    uvec2 value = %s(%s);
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
                """.formatted(inputWords, perlinInputFactorSetup(completeSource, perlinFunction,
                sampleIndex, call.start()), call.function(), arguments);
    }

    private static String directPerlinCombineSource(String completeSource, String perlinFunction,
                                                      int sampleCount, int inputWords) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        GlslFunction perlin = functions.get(perlinFunction);
        if (perlin == null) throw new IllegalArgumentException("Generated shader is missing Perlin function: "
                + perlinFunction);
        String functionText = functionText(completeSource, perlin);
        int open = functionText.indexOf('{');
        String body = functionBody(completeSource, perlin);
        List<NoiseCall> calls = perlinSampleCalls(completeSource, perlinFunction);
        calls = new ArrayList<>(calls);
        calls.sort(java.util.Comparator.comparingInt(NoiseCall::start).reversed());
        StringBuilder replacedBody = new StringBuilder(body);
        for (int index = 0; index < calls.size(); index++) {
            NoiseCall call = calls.get(index);
            int slot = calls.size() - 1 - index;
            replacedBody.replace(call.start(), call.end(),
                    "wg_direct_perlin_value(" + slot + "u)");
        }
        String transformed = functionText.substring(0, open + 1) + replacedBody + "\n}";
        String source = stageSourcePrefix(completeSource);
        source = replaceFunction(source, perlinFunction, transformed);
        int firstFunction = firstGeneratedFunctionStart(source);
        source = source.substring(0, firstFunction)
                + directPerlinLookupFunction(inputWords, sampleCount)
                + source.substring(firstFunction);
        source = compactSource(source, Set.of("wg_point", perlinFunction,
                "wg_direct_perlin_value", "wg_fp64_qnan", "wg_fp64_finite"));
        return source + """
                uvec2 wg_direct_perlin_coordinate(uint slot) {
                    if (slot >= 3u) {
                        wg_failed = true;
                        return uvec2(0u);
                    }
                    uint base = gl_GlobalInvocationID.x * %du + 4u + slot * 2u;
                    return uvec2(inputBits[base], inputBits[base + 1u]);
                }

                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uvec2 x = wg_direct_perlin_coordinate(0u);
                    uvec2 y = wg_direct_perlin_coordinate(1u);
                    uvec2 z = wg_direct_perlin_coordinate(2u);
                    uvec2 value = %s(x, y, z);
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
                """.formatted(inputWords, perlinFunction);
    }

    private static String directPerlinLookupFunction(int inputWords, int sampleCount) {
        return """
                uvec2 wg_direct_perlin_value(uint slot) {
                    if (slot >= %du) {
                        wg_failed = true;
                        return uvec2(0u);
                    }
                    uint base = gl_GlobalInvocationID.x * %du + 10u + slot * 2u;
                    return uvec2(inputBits[base], inputBits[base + 1u]);
                }

                """.formatted(sampleCount, inputWords);
    }

    private static String directNormalNoiseCoordinateSource(String completeSource,
                                                              DirectNormalNoise stage) {
        return directNormalNoiseCoordinateSource(completeSource, stage.rootArguments());
    }

    /** Emits the first transformed-coordinate stage for an embedded parent. */
    private static String embeddedNormalNoiseCoordinateSource(
            String completeSource, DirectNormalNoise stage, List<String> childRoots,
            int inputWords) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        String setup = embeddedNormalNoiseSetup(completeSource, stage);
        String source = stageSourcePrefix(completeSource)
                .replace("uint base = index * 4u;", "uint base = index * " + inputWords + "u;");
        for (int index = 0; index < childRoots.size(); index++) {
            String child = childRoots.get(index);
            GlslFunction function = functions.get(child);
            if (function == null) {
                throw new IllegalArgumentException("Generated shader is missing embedded normal-noise child: " + child);
            }
            source = replaceFunction(source, child,
                    stagedDensityFunction(child, function.returnType(), index));
        }
        int firstStagedChild = Integer.MAX_VALUE;
        for (String child : childRoots) {
            firstStagedChild = Math.min(firstStagedChild, generatedFunctionStart(source, child));
        }
        if (firstStagedChild == Integer.MAX_VALUE) {
            throw new IllegalStateException("Embedded normal-noise coordinate stage lost its child functions");
        }
        source = source.substring(0, firstStagedChild)
                + densityStageDirectLookupFunction(inputWords, 4)
                + source.substring(firstStagedChild);
        Set<String> roots = new HashSet<>(Set.of("wg_point", "wg_fp64_qnan", "wg_fp64_finite"));
        roots.addAll(childRoots);
        List<String> expressions = new ArrayList<>(stage.rootArguments());
        expressions.add(setup);
        addGeneratedFunctionRoots(completeSource, functions, roots, expressions);
        source = compactSource(source, roots);
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    %s
                    uvec2 x = %s;
                    uvec2 y = %s;
                    uvec2 z = %s;
                    if (wg_failed || !wg_fp64_finite(x)) x = wg_fp64_qnan();
                    if (wg_failed || !wg_fp64_finite(y)) y = wg_fp64_qnan();
                    if (wg_failed || !wg_fp64_finite(z)) z = wg_fp64_qnan();
                    uint outputBase = index * 6u;
                    outputBits[outputBase] = x.x;
                    outputBits[outputBase + 1u] = x.y;
                    outputBits[outputBase + 2u] = y.x;
                    outputBits[outputBase + 3u] = y.y;
                    outputBits[outputBase + 4u] = z.x;
                    outputBits[outputBase + 5u] = z.y;
                }
                """.formatted(setup, stage.rootArguments().get(0), stage.rootArguments().get(1),
                stage.rootArguments().get(2));
    }

    /** Emits the second transformed-coordinate stage from the first GPU row. */
    private static String embeddedNormalNoiseTransformedCoordinateSource(
            String completeSource, DirectNormalNoise stage, int inputWords) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        Set<String> roots = new HashSet<>(Set.of("wg_point", "wg_fp64_qnan", "wg_fp64_finite"));
        addGeneratedFunctionRoots(completeSource, functions, roots, stage.second().arguments());
        String source = stageSourcePrefix(completeSource)
                .replace("uint base = index * 4u;", "uint base = index * " + inputWords + "u;");
        source = compactSource(source, roots);
        return source + """
                uvec2 wg_embedded_normal_noise_input(uint slot) {
                    if (slot >= 3u) {
                        wg_failed = true;
                        return uvec2(0u);
                    }
                    uint base = gl_GlobalInvocationID.x * %du + 4u + slot * 2u;
                    return uvec2(inputBits[base], inputBits[base + 1u]);
                }

                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 x = wg_embedded_normal_noise_input(0u);
                    uvec2 y = wg_embedded_normal_noise_input(1u);
                    uvec2 z = wg_embedded_normal_noise_input(2u);
                    uvec2 transformedX = %s;
                    uvec2 transformedY = %s;
                    uvec2 transformedZ = %s;
                    if (wg_failed || !wg_fp64_finite(transformedX)) transformedX = wg_fp64_qnan();
                    if (wg_failed || !wg_fp64_finite(transformedY)) transformedY = wg_fp64_qnan();
                    if (wg_failed || !wg_fp64_finite(transformedZ)) transformedZ = wg_fp64_qnan();
                    uint outputBase = index * 6u;
                    outputBits[outputBase] = transformedX.x;
                    outputBits[outputBase + 1u] = transformedX.y;
                    outputBits[outputBase + 2u] = transformedY.x;
                    outputBits[outputBase + 3u] = transformedY.y;
                    outputBits[outputBase + 4u] = transformedZ.x;
                    outputBits[outputBase + 5u] = transformedZ.y;
                }
                """.formatted(inputWords, stage.second().arguments().get(0),
                stage.second().arguments().get(1), stage.second().arguments().get(2));
    }

    /** Replays the embedded parent after the two Perlin carriers are ready. */
    private static String embeddedNormalNoiseCombineSource(
            String completeSource, DirectNormalNoise stage, List<String> childRoots,
            int inputWords) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        GlslFunction rootFunction = functions.get(stage.root());
        GlslFunction noiseFunction = functions.get(stage.noiseFunction());
        if (rootFunction == null || noiseFunction == null) {
            throw new IllegalArgumentException("Generated shader is missing embedded normal-noise parent: "
                    + stage.root());
        }
        String source = stageSourcePrefix(completeSource)
                .replace("uint base = index * 4u;", "uint base = index * " + inputWords + "u;");
        for (int index = 0; index < childRoots.size(); index++) {
            String child = childRoots.get(index);
            GlslFunction function = functions.get(child);
            if (function == null) {
                throw new IllegalArgumentException("Generated shader is missing embedded normal-noise child: " + child);
            }
            source = replaceFunction(source, child,
                    stagedDensityFunction(child, function.returnType(), index));
        }
        String noiseText = functionText(completeSource, noiseFunction);
        int open = noiseText.indexOf('{');
        String body = functionBody(completeSource, noiseFunction);
        List<NoiseCall> calls = new ArrayList<>(List.of(stage.first(), stage.second()));
        calls.sort(java.util.Comparator.comparingInt(NoiseCall::start).reversed());
        StringBuilder replacedBody = new StringBuilder(body);
        for (NoiseCall call : calls) {
            int slot = call.function().equals(stage.first().function()) ? 0 : 1;
            replacedBody.replace(call.start(), call.end(),
                    "wg_direct_normal_noise_value(" + slot + "u)");
        }
        String transformedNoise = noiseText.substring(0, open + 1) + replacedBody + "\n}";
        source = replaceFunction(source, stage.noiseFunction(), transformedNoise);
        int firstStagedChild = Integer.MAX_VALUE;
        for (String child : childRoots) {
            firstStagedChild = Math.min(firstStagedChild, generatedFunctionStart(source, child));
        }
        GlslFunction transformedNoiseFunction = parseFunctions(source).get(stage.noiseFunction());
        if (transformedNoiseFunction == null) {
            throw new IllegalStateException("Embedded normal-noise combine stage lost its noise wrapper");
        }
        firstStagedChild = Math.min(firstStagedChild, transformedNoiseFunction.start());
        if (firstStagedChild == Integer.MAX_VALUE) {
            throw new IllegalStateException("Embedded normal-noise combine stage lost its child functions");
        }
        source = source.substring(0, firstStagedChild)
                + densityStageDirectLookupFunction(inputWords, 4)
                + directNormalNoiseLookupFunction(inputWords,
                        4 + childRoots.size() * DENSITY_STAGE_VALUE_WORDS)
                + source.substring(firstStagedChild);
        Set<String> roots = new HashSet<>(Set.of("wg_point", stage.root(), stage.noiseFunction(),
                "wg_direct_normal_noise_value", "wg_fp64_qnan", "wg_fp64_finite"));
        roots.addAll(childRoots);
        source = compactSource(source, roots);
        String value = densityValueExpression(rootFunction, stage.root());
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 value = %s;
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
                """.formatted(value);
    }

    private static String embeddedNormalNoiseSetup(String completeSource, DirectNormalNoise stage) {
        GlslFunction root = parseFunctions(completeSource).get(stage.root());
        if (root == null) throw new IllegalArgumentException("Generated shader is missing embedded normal-noise root");
        String body = functionBody(completeSource, root);
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(stage.noiseFunction()) + "\\s*\\(").matcher(body);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Embedded normal-noise root lost its noise call: " + stage.root());
        }
        int returnStart = body.lastIndexOf("return", matcher.start());
        if (returnStart < 0) {
            throw new IllegalArgumentException("Embedded normal-noise root has no return expression: " + stage.root());
        }
        return body.substring(0, returnStart).trim();
    }

    private static void addGeneratedFunctionRoots(String source, Map<String, GlslFunction> functions,
                                                  Set<String> roots, Iterable<String> expressions) {
        Pattern calls = Pattern.compile("\\b(wg_[A-Za-z0-9_]*)\\s*\\(");
        for (String expression : expressions) {
            Matcher matcher = calls.matcher(expression);
            while (matcher.find()) {
                if (functions.containsKey(matcher.group(1))) roots.add(matcher.group(1));
            }
        }
    }

    /** Emits the captured normal-noise wrapper arguments as device coordinates. */
    private static String directNormalNoiseTransformedCoordinateSource(String completeSource,
                                                                        DirectNormalNoise stage) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        List<String> baseArguments = stage.rootArguments();
        List<String> transformedArguments = stage.second().arguments();
        Set<String> roots = new HashSet<>(Set.of("wg_point", "wg_fp64_qnan", "wg_fp64_finite"));
        for (String expression : baseArguments) {
            Matcher matcher = Pattern.compile("\\b(wg_[A-Za-z0-9_]*)\\s*\\(").matcher(expression);
            while (matcher.find()) {
                if (functions.containsKey(matcher.group(1))) roots.add(matcher.group(1));
            }
        }
        for (String expression : transformedArguments) {
            Matcher matcher = Pattern.compile("\\b(wg_[A-Za-z0-9_]*)\\s*\\(").matcher(expression);
            while (matcher.find()) {
                if (functions.containsKey(matcher.group(1))) roots.add(matcher.group(1));
            }
        }
        String source = compactSource(stageSourcePrefix(completeSource), roots);
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 x = %s;
                    uvec2 y = %s;
                    uvec2 z = %s;
                    uvec2 transformedX = %s;
                    uvec2 transformedY = %s;
                    uvec2 transformedZ = %s;
                    if (wg_failed || !wg_fp64_finite(transformedX)) transformedX = wg_fp64_qnan();
                    if (wg_failed || !wg_fp64_finite(transformedY)) transformedY = wg_fp64_qnan();
                    if (wg_failed || !wg_fp64_finite(transformedZ)) transformedZ = wg_fp64_qnan();
                    uint outputBase = index * 6u;
                    outputBits[outputBase] = transformedX.x;
                    outputBits[outputBase + 1u] = transformedX.y;
                    outputBits[outputBase + 2u] = transformedY.x;
                    outputBits[outputBase + 3u] = transformedY.y;
                    outputBits[outputBase + 4u] = transformedZ.x;
                    outputBits[outputBase + 5u] = transformedZ.y;
                }
                """.formatted(baseArguments.get(0), baseArguments.get(1), baseArguments.get(2),
                transformedArguments.get(0), transformedArguments.get(1), transformedArguments.get(2));
    }

    private static String directNormalNoiseCoordinateSource(String completeSource,
                                                              List<String> arguments) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        Set<String> roots = new HashSet<>(Set.of("wg_point", "wg_fp64_qnan", "wg_fp64_finite"));
        for (String expression : arguments) {
            Matcher matcher = Pattern.compile("\\b(wg_[A-Za-z0-9_]*)\\s*\\(").matcher(expression);
            while (matcher.find()) {
                if (functions.containsKey(matcher.group(1))) roots.add(matcher.group(1));
            }
        }
        String source = compactSource(stageSourcePrefix(completeSource), roots);
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 x = %s;
                    uvec2 y = %s;
                    uvec2 z = %s;
                    if (wg_failed || !wg_fp64_finite(x)) x = wg_fp64_qnan();
                    if (wg_failed || !wg_fp64_finite(y)) y = wg_fp64_qnan();
                    if (wg_failed || !wg_fp64_finite(z)) z = wg_fp64_qnan();
                    uint outputBase = index * 6u;
                    outputBits[outputBase] = x.x;
                    outputBits[outputBase + 1u] = x.y;
                    outputBits[outputBase + 2u] = y.x;
                    outputBits[outputBase + 3u] = y.y;
                    outputBits[outputBase + 4u] = z.x;
                    outputBits[outputBase + 5u] = z.y;
                }
                """.formatted(arguments.get(0), arguments.get(1), arguments.get(2));
    }

    private static String directNormalNoiseSampleSource(String completeSource, DirectNormalNoise stage,
                                                         NoiseCall call, int inputWords) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        Set<String> roots = new HashSet<>(Set.of("wg_point", call.function(),
                "wg_fp64_qnan", "wg_fp64_finite"));
        for (String expression : call.arguments()) {
            Matcher matcher = Pattern.compile("\\b(wg_[A-Za-z0-9_]*)\\s*\\(").matcher(expression);
            while (matcher.find()) {
                if (functions.containsKey(matcher.group(1))) roots.add(matcher.group(1));
            }
        }
        String source = compactSource(stageSourcePrefix(completeSource), roots);
        String arguments = String.join(", ", call.arguments());
        return source + """
                uvec2 wg_normal_noise_input_value(uint slot) {
                    if (slot >= 3u) {
                        wg_failed = true;
                        return uvec2(0u);
                    }
                    uint base = gl_GlobalInvocationID.x * %du + 4u + slot * 2u;
                    return uvec2(inputBits[base], inputBits[base + 1u]);
                }

                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 x = wg_normal_noise_input_value(0u);
                    uvec2 y = wg_normal_noise_input_value(1u);
                    uvec2 z = wg_normal_noise_input_value(2u);
                    uvec2 value = %s(%s);
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
                """.formatted(inputWords, call.function(), arguments);
    }

    private static String directNormalNoiseCombineSource(String completeSource,
                                                           DirectNormalNoise stage, int inputWords,
                                                           int sampleBase) {
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        GlslFunction noise = functions.get(stage.noiseFunction());
        if (noise == null) {
            throw new IllegalArgumentException("Direct normal-noise combine lost wrapper");
        }
        String noiseText = functionText(completeSource, noise);
        int open = noiseText.indexOf('{');
        String body = functionBody(completeSource, noise);
        List<NoiseCall> calls = new ArrayList<>(List.of(stage.first(), stage.second()));
        calls.sort(java.util.Comparator.comparingInt(NoiseCall::start).reversed());
        StringBuilder replacedBody = new StringBuilder(body);
        for (NoiseCall call : calls) {
            int slot = call.function().equals(stage.first().function()) ? 0 : 1;
            replacedBody.replace(call.start(), call.end(),
                    "wg_direct_normal_noise_value(" + slot + "u)");
        }
        String transformedNoise = noiseText.substring(0, open + 1) + replacedBody + "\n}";
        String source = stageSourcePrefix(completeSource);
        source = replaceFunction(source, stage.noiseFunction(), transformedNoise);
        int firstFunction = firstGeneratedFunctionStart(source);
        source = source.substring(0, firstFunction)
                + directNormalNoiseLookupFunction(inputWords, sampleBase)
                + source.substring(firstFunction);
        Set<String> roots = new HashSet<>(Set.of("wg_point", stage.root(), stage.noiseFunction(),
                "wg_direct_normal_noise_value", "wg_fp64_qnan", "wg_fp64_finite"));
        source = compactSource(source, roots);
        String value = densityValueExpression(functions.get(stage.root()), stage.root());
        return source + """
                uvec2 wg_direct_normal_noise_coordinate(uint slot) {
                    if (slot >= 3u) {
                        wg_failed = true;
                        return uvec2(0u);
                    }
                    uint base = gl_GlobalInvocationID.x * %du + 4u + slot * 2u;
                    return uvec2(inputBits[base], inputBits[base + 1u]);
                }

                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 x = wg_direct_normal_noise_coordinate(0u);
                    uvec2 y = wg_direct_normal_noise_coordinate(1u);
                    uvec2 z = wg_direct_normal_noise_coordinate(2u);
                    uvec2 value = %s;
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
                """.formatted(inputWords, value);
    }

    private static String directNormalNoiseLookupFunction(int inputWords, int sampleBase) {
        return """
                uvec2 wg_direct_normal_noise_value(uint slot) {
                    if (slot >= 2u) {
                        wg_failed = true;
                        return uvec2(0u);
                    }
                    uint base = gl_GlobalInvocationID.x * %du + %du + slot * 2u;
                    return uvec2(inputBits[base], inputBits[base + 1u]);
                }

                """.formatted(inputWords, sampleBase);
    }

    private record EndStageInputs(int[] uniqueCoordinates, int[] cornerIndices) {
        private EndStageInputs {
            uniqueCoordinates = uniqueCoordinates.clone();
            cornerIndices = cornerIndices.clone();
        }

        @Override public int[] uniqueCoordinates() { return uniqueCoordinates.clone(); }
        @Override public int[] cornerIndices() { return cornerIndices.clone(); }
    }

    private record Point(int x, int y, int z) {}

    private static EndStageNames findEndStageNames(String source) {
        Matcher root = Pattern.compile("uvec2 density = (wg_node_[0-9]+)\\(point\\);").matcher(source);
        if (!root.find()) {
            throw new UnsupportedOperationException("Staged End replay is missing the final density node");
        }
        String endNode = findNodeReturning(source, "wg_end_island_");
        Matcher noise = Pattern.compile(
                "uvec2 (wg_node_[0-9]+)\\(ivec3 point\\) \\{\\s*return (wg_noise_blended_[0-9]+)\\(point\\);\\s*\\}",
                Pattern.DOTALL).matcher(source);
        if (!noise.find()) {
            throw new UnsupportedOperationException("Staged End replay is missing the blended-noise node");
        }
        String noiseNode = noise.group(1);
        String noiseFunction = noise.group(2);
        Matcher boundary = Pattern.compile(
                "uvec2 (wg_node_[0-9]+)\\(ivec3 point\\) \\{\\s*uvec2 current = wg_node_[0-9]+\\(point\\);\\s*return current;\\s*\\}",
                Pattern.DOTALL).matcher(source);
        if (!boundary.find()) {
            throw new UnsupportedOperationException("Staged End replay requires the captured interpolation boundary");
        }
        List<SampleSpec> samples = blendedNoiseSampleSpecs(source, noiseFunction);
        return new EndStageNames(root.group(1), endNode, noiseNode, noiseFunction, boundary.group(1), samples);
    }

    private static String functionBody(String source, String function) {
        String signature = "uvec2 " + function + "(ivec3 point) {";
        int start = source.indexOf(signature);
        if (start < 0) throw new UnsupportedOperationException("Generated shader is missing stage function: " + function);
        int open = source.indexOf('{', start);
        int end = matchingBrace(source, open);
        return source.substring(open + 1, end);
    }

    private static String firstFunctionName(String source, String expression, String description) {
        Matcher matcher = Pattern.compile(expression).matcher(source);
        if (!matcher.find()) {
            throw new UnsupportedOperationException("Generated shader is missing " + description + " function");
        }
        return matcher.group(1);
    }

    /** Select the captured aquifer entry point, not its uvec4 integer helpers. */
    static String aquiferFunctionName(String source) {
        return firstFunctionName(source, "(?m)^uvec4 (wg_aquifer_(?:default_fluid_)?[0-9]+)\\(", "captured aquifer/global-fluid picker");
    }

    private static String emittedFunctionName(WorldgenShaderCompiler.Shader shader, ProgramNode node,
                                              String description) {
        for (Map.Entry<String, ProgramNode> entry : shader.nodeFunctions().entrySet()) {
            if (entry.getValue() == node) return entry.getKey();
        }
        throw new IllegalStateException("Generated shader is missing " + description + " function metadata");
    }

    /** Captured ore fragments emit helper functions before their entry point. */
    private static String lastFunctionNameOrNull(String source, String expression) {
        Matcher matcher = Pattern.compile(expression).matcher(source);
        String result = null;
        while (matcher.find()) result = matcher.group(1);
        return result;
    }

    private static List<String> calledFunctions(String source, String expression) {
        Matcher matcher = Pattern.compile(expression, Pattern.DOTALL).matcher(source);
        List<String> result = new ArrayList<>();
        while (matcher.find()) result.add(matcher.group(1));
        return List.copyOf(result);
    }

    private static List<SampleSpec> sampleSpecsFor(List<SampleSpec> specs, String group) {
        List<SampleSpec> selected = new ArrayList<>();
        for (SampleSpec spec : specs) {
            if (spec.group().equals(group)) selected.add(spec);
        }
        if (selected.isEmpty()) throw new IllegalArgumentException("Missing staged samples for " + group);
        return List.copyOf(selected);
    }

    private static int sampleGroupIndex(String group) {
        return switch (group) {
            case "main" -> 0;
            case "min" -> 1;
            case "max" -> 2;
            default -> throw new IllegalArgumentException("Unknown staged sample group: " + group);
        };
    }

    private static String findNodeReturning(String source, String calleePrefix) {
        Matcher match = Pattern.compile(
                "uvec2 (wg_node_[0-9]+)\\(ivec3 point\\) \\{\\s*return ("
                        + Pattern.quote(calleePrefix) + "[0-9]+)\\(point\\);\\s*\\}",
                Pattern.DOTALL).matcher(source);
        if (!match.find()) {
            throw new UnsupportedOperationException("Staged End replay is missing a " + calleePrefix + " node");
        }
        return match.group(1);
    }

    private static WorldgenShaderCompiler.Shader stageShader(WorldgenShaderCompiler.Shader complete,
                                                               String source, String suffix) {
        return new WorldgenShaderCompiler.Shader(source, complete.programHash() + "/staged-" + suffix,
                complete.profile(), complete.localSize(), complete.blendedNoiseFunctions(),
                complete.nodeFunctions());
    }

    private static String sourcePrefix(String completeSource) {
        int marker = completeSource.indexOf("// bounds check is mandatory");
        if (marker < 0) throw new IllegalStateException("Generated End shader has no main marker");
        return completeSource.substring(0, marker);
    }

    /** Returns the stable candidate-stage prefix once per captured shader identity. */
    private static String stageSourcePrefix(String completeSource) {
        if (completeSource.length() < LARGE_CAPTURE_SOURCE_CHARS) {
            return replaceCandidateCellFraction(sourcePrefix(completeSource)
                    .replace("uint outputStateIds[]", "uint outputBits[]"));
        }
        Map<String, String> cache = LARGE_SOURCE_STAGE_PREFIX_CACHE.get();
        String cached = cache.get(completeSource);
        if (cached != null) return cached;
        String prefix = replaceCandidateCellFraction(sourcePrefix(completeSource)
                .replace("uint outputStateIds[]", "uint outputBits[]"));
        cache.put(completeSource, prefix);
        return prefix;
    }

    private static String endStageSource(String completeSource, String node) {
        String prefix = compactSource(sourcePrefix(completeSource).replace("uint outputStateIds[]", "uint outputBits[]"),
                Set.of("wg_point", node, "wg_fp64_qnan"));
        return prefix + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 value = %s(point);
                    if (wg_failed) value = wg_fp64_qnan();
                    uint outputBase = index * 4u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                    outputBits[outputBase + 2u] = 0u;
                    outputBits[outputBase + 3u] = 0u;
                }
                """.formatted(node);
    }

    private static String noiseSampleSource(String completeSource, List<SampleSpec> specs) {
        return noiseSampleSourceWithSetup(completeSource, specs, """
                uvec2 xzMultiplier = uvec2(0xc6a7ef9eu, 0x4065634bu);
                uvec2 yMultiplier = uvec2(0xc6a7ef9eu, 0x4065634bu);
                uvec2 d0 = wg_fp64_mul(wg_fp64_from_int(point.x), xzMultiplier);
                uvec2 d1 = wg_fp64_mul(wg_fp64_from_int(point.y), yMultiplier);
                uvec2 d2 = wg_fp64_mul(wg_fp64_from_int(point.z), xzMultiplier);
                uvec2 d3 = wg_fp64_div(d0, uvec2(0u, 0x40540000u));
                uvec2 d4 = wg_fp64_div(d1, uvec2(0u, 0x40640000u));
                uvec2 d5 = wg_fp64_div(d2, uvec2(0u, 0x40540000u));
                uvec2 d6 = wg_fp64_mul(yMultiplier, uvec2(0u, 0x40100000u));
                uvec2 d7 = wg_fp64_div(d6, uvec2(0u, 0x40640000u));
                """);
    }

    private static String noiseSampleSourceWithSetup(String completeSource, List<SampleSpec> specs,
                                                      String setup) {
        if (specs.isEmpty()) throw new IllegalArgumentException("Staged sample group is empty");
        String group = specs.get(0).group();
        if (specs.stream().anyMatch(spec -> !spec.group().equals(group))) {
            throw new IllegalArgumentException("Staged sample selector mixes function groups: " + group);
        }
        Set<String> roots = new HashSet<>(Set.of("wg_point", "wg_fp64_from_int", "wg_fp64_mul",
                "wg_fp64_div", "wg_noise_wrap", "wg_fp64_qnan"));
        specs.forEach(spec -> roots.add(spec.function()));
        String prefix = compactSource(sourcePrefix(completeSource).replace("uint outputStateIds[]", "uint outputBits[]"), roots);
        String scaleSelector = sampleScaleSelector(specs);
        String arguments = group.equals("main")
                ? "wg_noise_wrap(wg_fp64_mul(d3, scale)), "
                + "wg_noise_wrap(wg_fp64_mul(d4, scale)), "
                + "wg_noise_wrap(wg_fp64_mul(d5, scale)), "
                + "wg_fp64_mul(d7, scale), wg_fp64_mul(d4, scale)"
                : "wg_noise_wrap(wg_fp64_mul(d0, scale)), "
                + "wg_noise_wrap(wg_fp64_mul(d1, scale)), "
                + "wg_noise_wrap(wg_fp64_mul(d2, scale)), "
                + "wg_fp64_mul(d6, scale), wg_fp64_mul(d1, scale)";
        StringBuilder selected = new StringBuilder("uvec2 value;\n                    switch (sampleIndex) {\n");
        for (SampleSpec spec : specs) {
            selected.append("                        case ").append(spec.scaleExponent()).append("u: value = ")
                    .append(spec.function()).append('(').append(arguments).append("); break;\n");
        }
        selected.append("                        default: wg_failed = true; value = uvec2(0u); break;\n                    }\n");
        return prefix + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    %s
                    uint sampleIndex = inputBits[index * 4u + 3u];
                    %s
                    %s
                    if (wg_failed) value = wg_fp64_qnan();
                    uint outputBase = index * 4u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                    outputBits[outputBase + 2u] = 0u;
                    outputBits[outputBase + 3u] = 0u;
                }
                """.formatted(setup.strip(), scaleSelector, selected);
    }

    /**
     * Emit one fixed captured octave.  A one-spec selector looks equivalent
     * on paper, but it still makes the driver lower an input-dependent switch
     * and keeps the reserved carrier word live through the sampler.  The
     * ordinary draft uses this literal-call form so the only input-dependent
     * values are the captured point coordinates.
     */
    private static String noiseSampleSourceForSpec(String completeSource, SampleSpec spec,
                                                   String setup) {
        return noiseSampleSourceForSpecs(completeSource, List.of(spec), setup);
    }

    /**
     * Emit a static group of captured octave calls.  The output retains four
     * words per sample so it can be unpacked into the common carrier layout;
     * the two trailing words are reserved and remain zero.  A group has no
     * selector or table-ID input, which is important on drivers that compile
     * dynamic function selection differently from direct calls.
     */
    static String noiseSampleSourceForSpecs(String completeSource, List<SampleSpec> specs,
                                             String setup) {
        if (specs.isEmpty()) throw new IllegalArgumentException("Staged sample group is empty");
        String group = specs.get(0).group();
        if (specs.stream().anyMatch(spec -> !spec.group().equals(group))) {
            throw new IllegalArgumentException("Staged sample selector mixes function groups: " + group);
        }
        Set<String> roots = new HashSet<>(Set.of("wg_point", "wg_fp64_from_int", "wg_fp64_mul",
                "wg_fp64_div", "wg_noise_wrap", "wg_fp64_qnan"));
        specs.forEach(spec -> roots.add(spec.function()));
        String prefix = compactSource(sourcePrefix(completeSource)
                .replace("uint outputStateIds[]", "uint outputBits[]"), roots);
        StringBuilder calls = new StringBuilder(specs.size() * 260);
        for (int index = 0; index < specs.size(); index++) {
            SampleSpec spec = specs.get(index);
            String scale = "scale" + index;
            calls.append("                    uvec2 ").append(scale).append(" = ")
                    .append(fp64Literal(Math.scalb(1.0, -spec.scaleExponent()))).append(";\n")
                    .append("                    uvec2 value").append(index).append(" = ")
                    .append(spec.function()).append('(')
                    .append(noiseSampleArguments(spec.group(), scale)).append(");\n");
        }
        StringBuilder outputs = new StringBuilder(specs.size() * 150);
        for (int index = 0; index < specs.size(); index++) {
            int base = index * 4;
            outputs.append("                    outputBits[outputBase + ").append(base)
                    .append("u] = value").append(index).append(".x;\n")
                    .append("                    outputBits[outputBase + ").append(base + 1)
                    .append("u] = value").append(index).append(".y;\n")
                    .append("                    outputBits[outputBase + ").append(base + 2)
                    .append("u] = 0u;\n")
                    .append("                    outputBits[outputBase + ").append(base + 3)
                    .append("u] = 0u;\n");
        }
        return prefix + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    %s
                    %s
                    uint outputBase = index * %du;
                    if (wg_failed) {
                        uvec2 value = wg_fp64_qnan();
                        %s
                    } else {
                        %s
                    }
                }
                """.formatted(setup.strip(), calls, Math.multiplyExact(specs.size(), 4),
                        outputsForFailure(specs.size()), outputs);
    }

    private static String noiseSampleArguments(String group, String scale) {
        return group.equals("main")
                ? "wg_noise_wrap(wg_fp64_mul(d3, " + scale + ")), "
                + "wg_noise_wrap(wg_fp64_mul(d4, " + scale + ")), "
                + "wg_noise_wrap(wg_fp64_mul(d5, " + scale + ")), "
                + "wg_fp64_mul(d7, " + scale + "), wg_fp64_mul(d4, " + scale + ")"
                : "wg_noise_wrap(wg_fp64_mul(d0, " + scale + ")), "
                + "wg_noise_wrap(wg_fp64_mul(d1, " + scale + ")), "
                + "wg_noise_wrap(wg_fp64_mul(d2, " + scale + ")), "
                + "wg_fp64_mul(d6, " + scale + "), wg_fp64_mul(d1, " + scale + ")";
    }

    private static List<List<SampleSpec>> blendedNoiseStaticGroups(
            List<SampleSpec> specs, NumericProfile profile) {
        int defaultGroupSize = profile == NumericProfile.GPU_NATIVE_DRAFT ? 4
                : BLENDED_NOISE_DEFAULT_STATIC_GROUP_SIZE;
        int groupSize = integerProperty("tellurium.gpuCandidate.blendedNoiseStaticGroupSize",
                defaultGroupSize);
        if (groupSize <= 0 || groupSize > 8) {
            throw new IllegalArgumentException("GPU candidate blendedNoiseStaticGroupSize must be in [1, 8]");
        }
        List<List<SampleSpec>> groups = new ArrayList<>();
        for (String group : List.of("main", "min", "max")) {
            List<SampleSpec> members = sampleSpecsFor(specs, group);
            for (int start = 0; start < members.size(); start += groupSize) {
                groups.add(List.copyOf(members.subList(start,
                        Math.min(start + groupSize, members.size()))));
            }
        }
        return List.copyOf(groups);
    }

    private static String outputsForFailure(int sampleCount) {
        StringBuilder outputs = new StringBuilder(sampleCount * 150);
        for (int index = 0; index < sampleCount; index++) {
            int base = index * 4;
            outputs.append("                        outputBits[outputBase + ").append(base)
                    .append("u] = value.x;\n")
                    .append("                        outputBits[outputBase + ").append(base + 1)
                    .append("u] = value.y;\n")
                    .append("                        outputBits[outputBase + ").append(base + 2)
                    .append("u] = 0u;\n")
                    .append("                        outputBits[outputBase + ").append(base + 3)
                    .append("u] = 0u;\n");
        }
        return outputs.toString();
    }

    private static String blendedNoiseSampleDontInlinePrefix() {
        String configured = System.getProperty(
                "tellurium.gpuCandidate.blendedNoiseDontInlinePrefix", "NONE").trim();
        if (configured.isEmpty() || configured.equalsIgnoreCase("NONE")) return "NONE";
        for (String part : configured.split(",", -1)) {
            if (part.isBlank()) {
                throw new IllegalArgumentException(
                        "GPU candidate blendedNoiseDontInlinePrefix contains an empty function prefix");
            }
        }
        return configured;
    }

    private static String blendedNoiseSetup(String completeSource, String noiseFunction) {
        String body = functionBody(completeSource, noiseFunction);
        int end = body.indexOf("uvec2 d8");
        if (end < 0) {
            throw new UnsupportedOperationException(
                    "Staged blended noise has no captured octave accumulator setup: " + noiseFunction);
        }
        String setup = body.substring(0, end).strip();
        if (!setup.contains("uvec2 d0") || !setup.contains("d7 =")) {
            throw new UnsupportedOperationException(
                    "Staged blended noise has an incomplete coordinate setup: " + noiseFunction);
        }
        return setup;
    }

    private static List<SampleSpec> blendedNoiseSampleSpecs(String source, String noiseFunction) {
        String body = functionBody(source, noiseFunction);
        List<String> main = calledFunctions(body,
                "legacySample\\d+\\s*=\\s*(wg_noise_legacy_perlin_[0-9]+)\\s*\\(");
        List<String> min = calledFunctions(body,
                "if\\s*\\(\\s*!skipMin\\s*\\).*?(wg_noise_legacy_perlin_[0-9]+)\\s*\\(");
        List<String> max = calledFunctions(body,
                "if\\s*\\(\\s*!skipMax\\s*\\).*?(wg_noise_legacy_perlin_[0-9]+)\\s*\\(");
        if (main.size() != 8 || min.size() != 16 || max.size() != 16) {
            throw new UnsupportedOperationException("Staged blended noise requires 8 main, 16 min-limit and 16 max-limit captured octaves; got "
                    + main.size() + "/" + min.size() + "/" + max.size() + " for " + noiseFunction);
        }
        List<SampleSpec> samples = new ArrayList<>(BLENDED_NOISE_SAMPLE_COUNT);
        Map<String, SampleSpec> byFunction = new HashMap<>();
        for (int index = 0; index < main.size(); index++) {
            SampleSpec spec = new SampleSpec(main.get(index), index, "main");
            if (byFunction.put(spec.function(), spec) != null) {
                throw new UnsupportedOperationException("Captured blended-noise function is used by multiple sample groups: "
                        + spec.function());
            }
        }
        for (int index = 0; index < min.size(); index++) {
            SampleSpec spec = new SampleSpec(min.get(index), index, "min");
            if (byFunction.put(spec.function(), spec) != null) {
                throw new UnsupportedOperationException("Captured blended-noise function is used by multiple sample groups: "
                        + spec.function());
            }
        }
        for (int index = 0; index < max.size(); index++) {
            SampleSpec spec = new SampleSpec(max.get(index), index, "max");
            if (byFunction.put(spec.function(), spec) != null) {
                throw new UnsupportedOperationException("Captured blended-noise function is used by multiple sample groups: "
                        + spec.function());
            }
        }
        Matcher calls = Pattern.compile("\\b(wg_noise_legacy_perlin_[0-9]+)\\s*\\(").matcher(body);
        Set<String> seen = new HashSet<>();
        while (calls.find()) {
            String function = calls.group(1);
            SampleSpec spec = byFunction.get(function);
            if (spec == null) {
                throw new UnsupportedOperationException("Captured blended-noise call is not part of the admitted octave groups: "
                        + function);
            }
            if (!seen.add(function)) {
                throw new UnsupportedOperationException("Captured blended-noise octave is called more than once: "
                        + function);
            }
            samples.add(spec);
        }
        if (samples.size() != BLENDED_NOISE_SAMPLE_COUNT) {
            throw new UnsupportedOperationException("Captured blended-noise call order has " + samples.size()
                    + " octave calls; expected " + BLENDED_NOISE_SAMPLE_COUNT);
        }
        return List.copyOf(samples);
    }

    private static String replaceBlendedNoiseCalls(String body, List<SampleSpec> specs) {
        String token = "wg_noise_legacy_perlin_";
        StringBuilder replaced = new StringBuilder(body.length());
        int cursor = 0;
        int sample = 0;
        while (true) {
            int start = body.indexOf(token, cursor);
            if (start < 0) break;
            if (start > 0 && isIdentifierPart(body.charAt(start - 1))) {
                cursor = start + token.length();
                continue;
            }
            int end = start + token.length();
            while (end < body.length() && isIdentifierPart(body.charAt(end))) end++;
            String function = body.substring(start, end);
            int open = end;
            while (open < body.length() && Character.isWhitespace(body.charAt(open))) open++;
            if (open >= body.length() || body.charAt(open) != '(') {
                throw new IllegalArgumentException("Malformed captured blended-noise call: " + function);
            }
            int close = matchingParenthesis(body, open);
            if (sample >= specs.size() || !function.equals(specs.get(sample).function())) {
                throw new IllegalArgumentException("Captured blended-noise sample order changed at slot "
                        + sample + ": expected "
                        + (sample < specs.size() ? specs.get(sample).function() : "no more samples")
                        + ", found " + function);
            }
            replaced.append(body, cursor, start)
                    .append("wg_blended_stage_value(").append(sample).append("u)");
            cursor = close + 1;
            sample++;
        }
        replaced.append(body, cursor, body.length());
        if (sample != specs.size()) {
            throw new IllegalArgumentException("Captured blended-noise sample count changed: expected "
                    + specs.size() + ", found " + sample);
        }
        return replaced.toString();
    }

    private static String blendedNoiseStageLookupFunction() {
        return """
                uvec2 wg_blended_stage_value(uint slot) {
                    if (slot >= %dU) {
                        wg_failed = true;
                        return uvec2(0u);
                    }
                    uint index = gl_GlobalInvocationID.x;
                    uint value = index * %dU + 4U + slot * %dU;
                    return uvec2(inputBits[value], inputBits[value + 1U]);
                }

                """.formatted(BLENDED_NOISE_SAMPLE_COUNT, BLENDED_NOISE_INPUT_WORDS,
                DENSITY_STAGE_VALUE_WORDS);
    }

    private static String blendedNoiseFinalSource(String completeSource, String noiseFunction) {
        List<SampleSpec> specs = blendedNoiseSampleSpecs(completeSource, noiseFunction);
        Map<String, GlslFunction> functions = parseFunctions(completeSource);
        GlslFunction function = functions.get(noiseFunction);
        if (function == null || !"uvec2".equals(function.returnType())) {
            throw new IllegalArgumentException("Generated shader is missing uvec2 blended-noise function: "
                    + noiseFunction);
        }
        String functionText = functionText(completeSource, function);
        int open = functionText.indexOf('{');
        if (open < 0) throw new IllegalArgumentException("Malformed blended-noise function: " + noiseFunction);
        String transformedFunction = functionText.substring(0, open + 1)
                + replaceBlendedNoiseCalls(functionBody(completeSource, noiseFunction), specs)
                + "\n}";
        String source = sourcePrefix(completeSource)
                .replace("uint outputStateIds[]", "uint outputBits[]")
                .replace("uint base = index * 4u;", "uint base = index * " + BLENDED_NOISE_INPUT_WORDS + "u;");
        source = replaceFunction(source, noiseFunction, transformedFunction);
        Map<String, GlslFunction> transformedFunctions = parseFunctions(source);
        int firstFunction = transformedFunctions.values().iterator().next().start();
        source = source.substring(0, firstFunction) + blendedNoiseStageLookupFunction()
                + source.substring(firstFunction);
        source = compactSource(source, Set.of("wg_point", noiseFunction,
                "wg_blended_stage_value", "wg_fp64_qnan", "wg_fp64_finite"));
        return source + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    uvec2 value = %s(point);
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
                """.formatted(noiseFunction);
    }

    private static String sampleScaleSelector(List<SampleSpec> specs) {
        StringBuilder selector = new StringBuilder("uvec2 scale;\n");
        for (int index = 0; index < specs.size(); index++) {
            SampleSpec spec = specs.get(index);
            if (index == 0) selector.append("if ");
            else selector.append("else if ");
            selector.append("(sampleIndex == ").append(spec.scaleExponent()).append("u) scale = ")
                    .append(fp64Literal(Math.scalb(1.0, -spec.scaleExponent()))).append(";\n");
        }
        selector.append("else { wg_failed = true; scale = uvec2(0u); }\n");
        return selector.toString();
    }

    private static String fp64Literal(double value) {
        long bits = Double.doubleToRawLongBits(value);
        return String.format(Locale.ROOT, "uvec2(0x%08xu, 0x%08xu)", bits & 0xffffffffL, bits >>> 32);
    }

    private static void writeStageDiagnostics(WorldgenShaderCompiler.Shader endShader,
                                              List<WorldgenShaderCompiler.Shader> sampleShaders,
                                              WorldgenShaderCompiler.Shader finalShader) {
        String directory = System.getProperty("tellurium.gpuCandidate.stagedShaderDir", "").trim();
        if (directory.isEmpty()) return;
        Path root = Path.of(directory).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            Files.writeString(root.resolve("end-island.comp"), endShader.source());
            String[] names = {"34", "0", "17"};
            for (int index = 0; index < sampleShaders.size(); index++) {
                Files.writeString(root.resolve("blended-samples" + names[index] + ".comp"),
                        sampleShaders.get(index).source());
            }
            Files.writeString(root.resolve("final.comp"), finalShader.source());
            if (Boolean.parseBoolean(System.getProperty("tellurium.gpuCandidate.stopAfterShaderDiagnostics", "false"))) {
                throw new IllegalStateException("GPU candidate staged shader diagnostics wrote " + root);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("GPU candidate staged shader diagnostics could not write " + root, failure);
        }
    }

    private static void writeBlendedNoiseDiagnostics(String noiseFunction,
                                                     List<WorldgenShaderCompiler.Shader> sampleShaders,
                                                     WorldgenShaderCompiler.Shader finalShader) {
        String directory = System.getProperty("tellurium.gpuCandidate.stagedShaderDir", "").trim();
        if (directory.isEmpty()) return;
        Path root = Path.of(directory).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            if (sampleShaders.size() == 3) {
                String[] groups = {"main", "min", "max"};
                for (int index = 0; index < sampleShaders.size(); index++) {
                    Files.writeString(root.resolve("density-blended-samples-" + groups[index]
                            + "-" + noiseFunction + ".comp"), sampleShaders.get(index).source());
                }
            } else {
                for (int index = 0; index < sampleShaders.size(); index++) {
                    Files.writeString(root.resolve("density-blended-sample-"
                            + String.format(Locale.ROOT, "%02d", index) + "-" + noiseFunction + ".comp"),
                            sampleShaders.get(index).source());
                }
            }
            Files.writeString(root.resolve("density-blended-final-" + noiseFunction + ".comp"),
                    finalShader.source());
        } catch (IOException failure) {
            throw new IllegalStateException("GPU candidate blended-noise diagnostics could not write " + root,
                    failure);
        }
    }

    private static String finalStageSource(String completeSource, EndStageNames names) {
        String source = sourcePrefix(completeSource)
                .replace("uint base = index * 4u;", "uint base = index * 11u;")
                .replace("bool wg_failed;\n", "bool wg_failed;\n"
                        + "uint wg_stageIndexBase;\nuint wg_stageValueIndex;\nuint wg_stageCursor;\n");
        source = replacePointFunction(source, names.endNode(),
                "uvec2 " + names.endNode() + "(ivec3 point) {\n"
                        + "return uvec2(inputBits[wg_stageValueIndex], inputBits[wg_stageValueIndex + 1u]);\n}");
        source = replacePointFunction(source, names.noiseFunction(), stagedNoiseFunction(names.noiseFunction()));
        String boundarySignature = "uvec2 " + names.boundaryNode() + "(ivec3 point) {";
        int boundaryStart = source.indexOf(boundarySignature);
        if (boundaryStart < 0) throw new IllegalStateException("Staged End boundary function disappeared");
        int boundaryEnd = matchingBrace(source, source.indexOf('{', boundaryStart));
        String boundary = source.substring(boundaryStart, boundaryEnd + 1);
        String marker = "uvec2 current = wg_node_";
        int current = boundary.indexOf(marker);
        if (current < 0) throw new IllegalStateException("Staged End boundary has no point evaluation");
        boundary = boundary.substring(0, current)
                + "if (wg_stageCursor >= 8u) { wg_failed = true; return uvec2(0u); }\n"
                + "wg_stageValueIndex = inputBits[wg_stageIndexBase + wg_stageCursor];\n"
                + "wg_stageCursor++;\n"
                + boundary.substring(current);
        source = source.substring(0, boundaryStart) + boundary + source.substring(boundaryEnd + 1);
        String main = completeSource.substring(completeSource.indexOf("// bounds check is mandatory"));
        main = main.replace("wg_failed = false;\n", "wg_failed = false;\n"
                + "    wg_stageIndexBase = index * 11u + 3u;\n"
                + "    wg_stageCursor = 0u;\n");
        Set<String> roots = new HashSet<>(Set.of("wg_point", names.root(), names.boundaryNode(),
                "wg_fp64_to_fp32", "wg_fp64_finite", "wg_fp32_finite", "wg_material_decide",
                "wg_material_decide64"));
        Matcher aquifer = Pattern.compile("uvec4 (wg_aquifer_[A-Za-z0-9_]+)\\(ivec3 point, uvec2 density\\)")
                .matcher(completeSource);
        if (aquifer.find()) roots.add(aquifer.group(1));
        String ore = lastFunctionNameOrNull(completeSource,
                "uvec2 (wg_ore_[A-Za-z0-9_]+)\\(ivec3 point\\)");
        if (ore != null) roots.add(ore);
        return compactSource(source, roots) + main;
    }

    private static String stagedNoiseFunction(String functionName) {
        StringBuilder source = new StringBuilder();
        source.append("uvec2 ").append(functionName).append("(ivec3 point) {\n")
                .append("    uvec2 d8 = uvec2(0u), d9 = uvec2(0u), d10 = uvec2(0u);\n")
                .append("    uvec2 d11 = uvec2(0u, 0x3ff00000u);\n");
        for (int sample = 0; sample < 8; sample++) {
            source.append("    d10 = wg_fp64_add(d10, wg_fp64_div(")
                    .append(stageValue(sample)).append(", d11));\n")
                    .append("    d11 = wg_fp64_mul(d11, uvec2(0u, 0x3fe00000u));\n");
        }
        source.append("    uvec2 d16 = wg_fp64_mul(wg_fp64_add(")
                .append("wg_fp64_div(d10, uvec2(0u, 0x40240000u)), uvec2(0u, 0x3ff00000u)), ")
                .append("uvec2(0u, 0x3fe00000u));\n")
                .append("    bool skipMin = wg_fp64_less_equal(uvec2(0u, 0x3ff00000u), d16);\n")
                .append("    bool skipMax = wg_fp64_less_equal(d16, uvec2(0u));\n")
                .append("    d11 = uvec2(0u, 0x3ff00000u);\n");
        for (int level = 0; level < 16; level++) {
            int lowerSample = 8 + level * 2;
            int upperSample = lowerSample + 1;
            source.append("    if (!skipMin) d8 = wg_fp64_add(d8, wg_fp64_div(")
                    .append(stageValue(lowerSample)).append(", d11));\n")
                    .append("    if (!skipMax) d9 = wg_fp64_add(d9, wg_fp64_div(")
                    .append(stageValue(upperSample)).append(", d11));\n")
                    .append("    d11 = wg_fp64_mul(d11, uvec2(0u, 0x3fe00000u));\n");
        }
        source.append("    uvec2 lower = wg_fp64_div(d8, uvec2(0u, 0x40800000u)), ")
                .append("upper = wg_fp64_div(d9, uvec2(0u, 0x40800000u));\n")
                .append("    uvec2 blend = wg_fp64_max(uvec2(0u), ")
                .append("wg_fp64_min(uvec2(0u, 0x3ff00000u), d16));\n")
                .append("    return wg_fp64_div(wg_fp64_lerp(lower, upper, blend), ")
                .append("uvec2(0u, 0x40600000u));\n")
                .append("}\n");
        return source.toString();
    }

    private static String stageValue(int sample) {
        int word = Math.addExact(2, Math.multiplyExact(sample, 2));
        return "uvec2(inputBits[wg_stageValueIndex + " + word + "u], "
                + "inputBits[wg_stageValueIndex + " + (word + 1) + "u])";
    }

    private static String replacePointFunction(String source, String name, String replacement) {
        return replaceFunction(source, name, replacement);
    }

    /** Finds a generated density function header without reparsing every function body. */
    private static int generatedFunctionStart(String source, String name) {
        int signature = source.indexOf(name + "(ivec3 point)");
        if (signature < 0) {
            throw new IllegalStateException("Staged function is missing: " + name);
        }
        int lineStart = source.lastIndexOf('\n', signature) + 1;
        while (lineStart < signature
                && (source.charAt(lineStart) == ' ' || source.charAt(lineStart) == '\t')) {
            lineStart++;
        }
        return lineStart;
    }

    private static String replaceFunction(String source, String name, String replacement) {
        if (source.length() >= LARGE_CAPTURE_SOURCE_CHARS) {
            int signature = source.indexOf(name + "(ivec3 point)");
            if (signature >= 0) {
                int start = source.lastIndexOf('\n', signature) + 1;
                while (start < signature
                        && (source.charAt(start) == ' ' || source.charAt(start) == '\t')) start++;
                int signatureEnd = source.indexOf(')', signature);
                int open = source.indexOf('{', signatureEnd);
                int end = matchingBrace(source, open);
                return source.substring(0, start) + replacement + source.substring(end + 1);
            }
        }
        GlslFunction function = parseFunctions(source).get(name);
        if (function == null) throw new IllegalStateException("Staged function is missing: " + name);
        return source.substring(0, function.start()) + replacement + source.substring(function.end() + 1);
    }

    private static int matchingBrace(String source, int open) {
        if (open < 0) throw new IllegalArgumentException("Missing opening brace");
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') depth++;
            else if (value == '}' && --depth == 0) return index;
        }
        throw new IllegalArgumentException("Unterminated GLSL function");
    }

    private static int matchingParenthesis(String source, int open) {
        if (open < 0 || open >= source.length() || source.charAt(open) != '(') {
            throw new IllegalArgumentException("Missing opening parenthesis");
        }
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '(') depth++;
            else if (value == ')' && --depth == 0) return index;
        }
        throw new IllegalArgumentException("Unterminated GLSL call");
    }

    /**
     * Keep the generated globals but remove function bodies that cannot be
     * reached from the stage entry points.  A driver still parses the whole
     * module, and retaining every captured noise octave in each specialized
     * sample module needlessly multiplies register allocation and pipeline
     * memory.  The generated source uses ordinary non-nested GLSL functions,
     * so a small source-level reachability pass is sufficient and preserves
     * the original function order.
     */
    static String compactSource(String source, Set<String> roots) {
        String cacheKey = null;
        Map<String, String> cachedCompactions = null;
        boolean chunkedGpuChildren = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugDensityStageRootGpuChildren", "false"));
        if (source.length() >= LARGE_CAPTURE_SOURCE_CHARS && !chunkedGpuChildren) {
            cacheKey = roots.stream().sorted().collect(java.util.stream.Collectors.joining("\u0000"))
                    + "\nrewriteSampler="
                    + System.getProperty("tellurium.gpuCandidate.rewriteCapturedNoiseSampler", "true")
                    + "\nrewriteDivision="
                    + System.getProperty("tellurium.gpuCandidate.rewriteFp64DivisionLoop", "true");
            cachedCompactions = LARGE_SOURCE_COMPACT_CACHE.get().computeIfAbsent(
                    source, ignored -> boundedSourceCache(512));
            String cached = cachedCompactions.get(cacheKey);
            if (cached != null) return cached;
        }
        boolean debug = Boolean.parseBoolean(System.getProperty("tellurium.gpuCandidate.debugStages", "false"));
        stageDebug(debug, "compact-start chars=" + source.length() + " roots=" + roots);
        if (debug) System.out.println("Tellurium stage compact input chars=" + source.length() + " roots=" + roots);
        List<GlslFunction> functions = new ArrayList<>(parseFunctions(source).values());
        if (functions.isEmpty()) throw new IllegalArgumentException("Generated shader contains no functions");
        stageDebug(debug, "compact-headers functions=" + functions.size());
        Map<String, GlslFunction> byName = new HashMap<>();
        for (GlslFunction function : functions) {
            if (byName.put(function.name(), function) != null) {
                throw new IllegalArgumentException("Generated shader has duplicate function: " + function.name());
            }
        }
        // Large captures repeatedly replace one or two function bodies while
        // retaining thousands of unchanged helpers.  Reuse the same
        // per-function call extraction used by the planner instead of
        // running a regex over every helper for every staged parent.
        Map<String, Set<String>> calls = functionCalls(source, byName);
        Set<String> reachable = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        for (String root : roots) {
            if (!byName.containsKey(root)) throw new IllegalArgumentException("Generated shader is missing stage root: " + root);
            if (reachable.add(root)) pending.addLast(root);
        }
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            for (String callee : calls.getOrDefault(current, Set.of())) {
                if (byName.containsKey(callee) && reachable.add(callee)) pending.addLast(callee);
            }
        }
        if (debug) System.out.println("Tellurium stage compact functions=" + functions.size()
                + " reachable=" + reachable.size());
        stageDebug(debug, "compact-reachable functions=" + functions.size() + " reachable=" + reachable.size());
        StringBuilder compact = new StringBuilder(source.length());
        StringBuilder retainedFunctionSource = new StringBuilder();
        int cursor = 0;
        for (GlslFunction function : functions) {
            compact.append(source, cursor, function.start());
            if (reachable.contains(function.name())) {
                String functionSource = source.substring(function.start(), function.end() + 1);
                compact.append(functionSource);
                retainedFunctionSource.append(functionSource);
            }
            cursor = function.end() + 1;
        }
        compact.append(source, cursor, source.length());
        GlobalTableCompaction globals = compactUnusedNoiseTables(compact.toString(), retainedFunctionSource.toString());
        // CapturedNoiseEmitter emits the shared sampler with a packed [64]
        // permutation ABI. The candidate-local rewrite specializes that
        // shared sampler per retained immutable table; older/custom sources
        // with a legacy [256] array ABI remain untouched.
        boolean rewriteSampler = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.rewriteCapturedNoiseSampler", "true"));
        String packedSource = globals.source().contains("noise_sample(")
                ? rewriteSampler ? rewriteCapturedNoiseSampler(globals.source()) : globals.source()
                : packNoiseTables(globals.source());
        String boundedSource = rewriteFp64DivisionLoop(packedSource);
        stageDebug(debug, "compact-globals removed=" + globals.removed() + " chars=" + globals.source().length());
        stageDebug(debug, "compact-end chars=" + boundedSource.length());
        if (debug) System.out.println("Tellurium stage compact output chars=" + boundedSource.length()
                + " removedNoiseTables=" + globals.removed());
        if (cachedCompactions != null) cachedCompactions.put(cacheKey, boundedSource);
        return boundedSource;
    }

    /**
     * The integer-only IEEE divider is deliberately kept as a no-inline
     * helper, but its 108-step restoring-division loop is a poor fit for the
     * NVIDIA draft compiler: the generated Overworld stage can lose the
     * device even for one invocation.  Replace it with four statically bounded
     * limb loops, preserving the same high-to-low bit order without the
     * dynamic signed countdown and branch ladder.  This is a candidate-local
     * driver workaround; the canonical compiler source and its conformance
     * path remain unchanged.
     */
    private static String rewriteFp64DivisionLoop(String source) {
        if (!Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.rewriteFp64DivisionLoop", "true"))) return source;
        GlslFunction division = parseFunctions(source).get("wg_fp64_div");
        if (division == null) return source;
        String function = source.substring(division.start(), division.end() + 1);
        String marker = "for (int bit = 107; bit >= 0; bit--)";
        int loopStart = function.indexOf(marker);
        if (loopStart < 0) return source;
        int open = function.indexOf('{', loopStart + marker.length());
        int close = matchingBrace(function, open);
        StringBuilder bounded = new StringBuilder(5_000)
                .append("                    uvec4 state = uvec4(remainder, quotient);\n");
        for (int chunk = 0; chunk < 27; chunk++) {
            int highestBit = 107 - chunk * 4;
            String word;
            if (highestBit >= 96) word = "w";
            else if (highestBit >= 64) word = "z";
            else if (highestBit >= 32) word = "y";
            else word = "x";
            int wordOffset = switch (word) {
                case "w" -> 3;
                case "z" -> 2;
                case "y" -> 1;
                default -> 0;
            };
            bounded.append("                    state = wg_fp64_div_chunk(state, rightMantissa, (numerator.")
                    .append(word).append(" >> ").append(highestBit - wordOffset * 32 - 3)
                    .append("u) & 15u);\n");
        }
        bounded.append("                    remainder = state.xy;\n")
                .append("                    quotient = state.zw;\n");
        String helper = """
                uvec4 wg_fp64_div_chunk(uvec4 state, uvec2 divisor, uint incoming) {
                    uvec2 remainder = state.xy;
                    uvec2 quotient = state.zw;
                    {
                        uint bit = (incoming >> 3u) & 1u;
                        remainder = uvec2((remainder.x << 1) | bit,
                                (remainder.y << 1) | (remainder.x >> 31));
                        uint selected = wg_u64_less(divisor, remainder) ||
                                wg_u64_equal(divisor, remainder) ? 1u : 0u;
                        if (selected != 0u) remainder = wg_u64_sub(remainder, divisor);
                        quotient = uvec2((quotient.x << 1) | selected,
                                (quotient.y << 1) | (quotient.x >> 31));
                    }
                    {
                        uint bit = (incoming >> 2u) & 1u;
                        remainder = uvec2((remainder.x << 1) | bit,
                                (remainder.y << 1) | (remainder.x >> 31));
                        uint selected = wg_u64_less(divisor, remainder) ||
                                wg_u64_equal(divisor, remainder) ? 1u : 0u;
                        if (selected != 0u) remainder = wg_u64_sub(remainder, divisor);
                        quotient = uvec2((quotient.x << 1) | selected,
                                (quotient.y << 1) | (quotient.x >> 31));
                    }
                    {
                        uint bit = (incoming >> 1u) & 1u;
                        remainder = uvec2((remainder.x << 1) | bit,
                                (remainder.y << 1) | (remainder.x >> 31));
                        uint selected = wg_u64_less(divisor, remainder) ||
                                wg_u64_equal(divisor, remainder) ? 1u : 0u;
                        if (selected != 0u) remainder = wg_u64_sub(remainder, divisor);
                        quotient = uvec2((quotient.x << 1) | selected,
                                (quotient.y << 1) | (quotient.x >> 31));
                    }
                    {
                        uint bit = incoming & 1u;
                        remainder = uvec2((remainder.x << 1) | bit,
                                (remainder.y << 1) | (remainder.x >> 31));
                        uint selected = wg_u64_less(divisor, remainder) ||
                                wg_u64_equal(divisor, remainder) ? 1u : 0u;
                        if (selected != 0u) remainder = wg_u64_sub(remainder, divisor);
                        quotient = uvec2((quotient.x << 1) | selected,
                                (quotient.y << 1) | (quotient.x >> 31));
                    }
                    return uvec4(remainder, quotient);
                }
                """;
        String withHelper = function.substring(0, loopStart) + bounded + function.substring(close + 1);
        return source.substring(0, division.start()) + helper + withHelper
                + source.substring(division.end() + 1);
    }

    /**
     * Staged captured-noise modules use one shared sampler body in the
     * canonical compiler output.  Passing a GLSL array into that body makes
     * shaderc expand the array ABI at every octave call, while a scalar table
     * ID leaves a dynamic switch in the hottest lookup path.  Both forms have
     * produced unreliable NVIDIA draft behavior for the large Overworld
     * sampler.  Specialize the sampler and lookup helper once per retained
     * immutable table instead: the device still performs every lookup, but no
     * array parameter or table-ID branch crosses the driver boundary.
     */
    private static String rewriteCapturedNoiseSampler(String source) {
        Matcher declarations = Pattern.compile(
                "(?m)^[ \\t]*const[ \\t]+uint[ \\t]+(wg_noise_[A-Za-z0-9_]+)[ \\t]*\\[64\\][ \\t]*="
                        + "[ \\t]*uint\\[64\\]\\([^;]*\\);[ \\t]*(?:\\r?\\n|$)")
                .matcher(source);
        List<String> tableNames = new ArrayList<>();
        while (declarations.find()) tableNames.add(declarations.group(1));
        if (tableNames.isEmpty()) return source;
        if (!source.contains("in uint permutation[64]")) return source;

        GlslFunction sampler = parseFunctions(source).get("noise_sample");
        if (sampler == null) {
            throw new IllegalStateException("Captured sampler is missing noise_sample");
        }
        String samplerText = functionText(source, sampler);
        int open = samplerText.indexOf('{');
        String samplerBody = samplerText.substring(open + 1, samplerText.length() - 1);

        String transformed = replaceCapturedNoiseSampleCalls(source, tableNames);
        String withoutSampler = replaceFunction(transformed, "noise_sample", "");
        String withoutHelpers = replaceFunction(withoutSampler, "noise_table_value", "");

        Matcher retainedDeclarations = Pattern.compile(
                "(?m)^[ \\t]*const[ \\t]+uint[ \\t]+(wg_noise_[A-Za-z0-9_]+)[ \\t]*\\[64\\][ \\t]*="
                        + "[ \\t]*uint\\[64\\]\\([^;]*\\);[ \\t]*(?:\\r?\\n|$)")
                .matcher(withoutHelpers);
        StringBuffer withoutDeclarations = new StringBuffer(withoutHelpers.length());
        StringBuilder tableSource = new StringBuilder(tableNames.size() * 560);
        while (retainedDeclarations.find()) {
            tableSource.append(retainedDeclarations.group()).append('\n');
            retainedDeclarations.appendReplacement(withoutDeclarations, "");
        }
        retainedDeclarations.appendTail(withoutDeclarations);

        GlslFunction interpolation = parseFunctions(withoutDeclarations.toString()).get("wg_noise_interpolate");
        if (interpolation == null) {
            throw new IllegalStateException("Captured sampler is missing wg_noise_interpolate");
        }

        StringBuilder specialized = new StringBuilder(tableSource.length() + tableNames.size() * 2_400);
        specialized.append(tableSource);
        for (String table : tableNames) {
            String lookup = "noise_table_value_" + table;
            String sample = "noise_sample_" + table;
            specialized.append("uint ").append(lookup).append("(uint index) {\n")
                    .append("    uint packed = ").append(table).append("[index >> 2u];\n")
                    .append("    return (packed >> ((index & 3u) * 8u)) & 255u;\n")
                    .append("}\n")
                    .append("uvec2 ").append(sample).append("(uvec2 x, uvec2 y, uvec2 z,\n")
                    .append("        uvec2 xOffset, uvec2 yOffset, uvec2 zOffset,\n")
                    .append("        uvec2 yScale, uvec2 yMax, bool smear) {")
                    .append(samplerBody.replace("noise_table_value(permutation,", lookup + "("))
                    .append("\n}\n");
        }

        String compacted = withoutDeclarations.toString();
        int insertion = interpolation.end() + 1;
        return compacted.substring(0, insertion) + "\n" + specialized
                + compacted.substring(insertion);
    }

    private static String replaceCapturedNoiseSampleCalls(String source, List<String> tableNames) {
        StringBuilder result = new StringBuilder(source.length());
        int cursor = 0;
        String token = "noise_sample";
        while (true) {
            int start = source.indexOf(token + "(", cursor);
            if (start < 0) {
                result.append(source, cursor, source.length());
                return result.toString();
            }
            int open = start + token.length();
            int close = matchingParenthesis(source, open);
            String arguments = source.substring(open + 1, close);
            int comma = lastTopLevelComma(arguments);
            String table = comma < 0 ? "" : arguments.substring(comma + 1).trim();
            result.append(source, cursor, start);
            int tableIndex = tableNames.indexOf(table);
            if (tableIndex < 0) {
                result.append(source, start, close + 1);
            } else {
                result.append("noise_sample_").append(table).append('(')
                        .append(arguments, 0, comma).append(')');
            }
            cursor = close + 1;
        }
    }

    private static int lastTopLevelComma(String source) {
        int parentheses = 0;
        int brackets = 0;
        int last = -1;
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '(') parentheses++;
            else if (value == ')') parentheses--;
            else if (value == '[') brackets++;
            else if (value == ']') brackets--;
            else if (value == ',' && parentheses == 0 && brackets == 0) last = index;
        }
        if (parentheses != 0 || brackets != 0) {
            throw new IllegalArgumentException("Malformed captured sampler argument list");
        }
        return last;
    }

    private static GlobalTableCompaction compactUnusedNoiseTables(String source, String retainedFunctionSource) {
        Matcher declarations = Pattern.compile(
                "(?m)^[ \\t]*const[ \\t]+[A-Za-z_][A-Za-z0-9_]*[ \\t]+"
                        + "(wg_noise_[A-Za-z0-9_]+)[ \\t]*\\[[^\\]\\r\\n]*\\][ \\t]*="
                        + "[^;\\r\\n]*;[ \\t]*(?:\\r?\\n|$)")
                .matcher(source);
        Set<String> referencedTables = referencedNoiseTableNames(retainedFunctionSource);
        StringBuffer compact = new StringBuffer(source.length());
        int removed = 0;
        while (declarations.find()) {
            String name = declarations.group(1);
            boolean referenced = referencedTables.contains(name);
            if (referenced) {
                declarations.appendReplacement(compact, Matcher.quoteReplacement(declarations.group()));
            } else {
                declarations.appendReplacement(compact, "");
                removed++;
            }
        }
        declarations.appendTail(compact);
        // Beardifier's immutable FP32 kernel is multiline and not a noise
        // permutation. Keep it only in stages whose reached helpers read it.
        // Its direct snapshot node must not force every unrelated graph stage
        // to parse 13,824 unused constants.
        String compacted = compact.toString();
        if (!Pattern.compile("\\bwg_beard_kernel\\b").matcher(retainedFunctionSource).find()) {
            Matcher beardKernel = Pattern.compile(
                    "(?m)^[ \\t]*const[ \\t]+uint[ \\t]+wg_beard_kernel[ \\t]*\\[13824\\][ \\t]*="
                            + "[ \\t]*uint\\[13824\\]\\([^;]*\\);[ \\t]*(?:\\r?\\n|$)")
                    .matcher(compacted);
            StringBuffer withoutBeardKernel = new StringBuffer(compacted.length());
            while (beardKernel.find()) {
                beardKernel.appendReplacement(withoutBeardKernel, "");
                removed++;
            }
            beardKernel.appendTail(withoutBeardKernel);
            compacted = withoutBeardKernel.toString();
        }
        return new GlobalTableCompaction(compacted, removed);
    }

    /** Collects captured noise identifiers once instead of compiling one regex per table. */
    private static Set<String> referencedNoiseTableNames(String source) {
        Set<String> names = new HashSet<>();
        String prefix = "wg_noise_";
        int cursor = 0;
        while ((cursor = source.indexOf(prefix, cursor)) >= 0) {
            int end = cursor + prefix.length();
            while (end < source.length() && isIdentifierPart(source.charAt(end))) end++;
            names.add(source.substring(cursor, end));
            cursor = end;
        }
        return names;
    }

    /**
     * Captured permutation tables contain byte values, but spelling all 256
     * entries as individual GLSL constants makes shaderc build a very large
     * constant-expression tree.  Pack four bytes into one word while keeping
     * the table immutable and the lookup on the device.  This changes only
     * the specialized staged source; the complete shader remains the
     * canonical source used for provenance and independent comparison.
     */
    private static String packNoiseTables(String source) {
        Matcher declarations = Pattern.compile(
                "(?m)^[ \\t]*const[ \\t]+uint[ \\t]+(wg_noise_[A-Za-z0-9_]+)[ \\t]*\\[256\\][ \\t]*="
                        + "[ \\t]*uint\\[256\\]\\(([^;]*)\\);[ \\t]*(?:\\r?\\n|$)")
                .matcher(source);
        StringBuffer packed = new StringBuffer(source.length());
        List<String> names = new ArrayList<>();
        while (declarations.find()) {
            String name = declarations.group(1);
            String[] literals = declarations.group(2).split(",", -1);
            if (literals.length != 256) {
                throw new IllegalArgumentException("Noise table " + name + " has " + literals.length
                        + " entries; expected 256");
            }
            StringBuilder replacement = new StringBuilder("const uint ").append(name)
                    .append("[64] = uint[64](");
            for (int index = 0; index < 256; index += 4) {
                if (index > 0) replacement.append(',');
                long word = 0;
                for (int byteIndex = 0; byteIndex < 4; byteIndex++) {
                    int value = parseNoiseTableByte(literals[index + byteIndex], name, index + byteIndex);
                    word |= (long) value << (byteIndex * 8);
                }
                replacement.append(String.format(Locale.ROOT, "0x%08xu", word));
            }
            replacement.append(");\n");
            declarations.appendReplacement(packed, Matcher.quoteReplacement(replacement.toString()));
            names.add(name);
        }
        declarations.appendTail(packed);
        if (names.isEmpty()) return source;

        String transformed = packed.toString();
        Map<String, GlslFunction> functions = parseFunctions(transformed);
        StringBuilder withLookups = new StringBuilder(transformed.length() + names.size() * 150);
        int cursor = 0;
        for (GlslFunction function : functions.values()) {
            withLookups.append(transformed, cursor, function.start());
            withLookups.append(replaceNoiseTableLookups(
                    transformed.substring(function.start(), function.end() + 1), names));
            cursor = function.end() + 1;
        }
        withLookups.append(transformed, cursor, transformed.length());

        Map<String, GlslFunction> transformedFunctions = parseFunctions(withLookups.toString());
        int firstFunction = transformedFunctions.values().iterator().next().start();
        StringBuilder lookupPrototypes = new StringBuilder(names.size() * 45);
        StringBuilder lookupFunctions = new StringBuilder(names.size() * 150);
        for (String name : names) {
            lookupPrototypes.append("uint ").append(name).append("_lookup(uint index);\n");
            lookupFunctions.append("uint ").append(name).append("_lookup(uint index) {\n")
                    .append("    uint packed = ").append(name).append("[index >> 2u];\n")
                    .append("    return (packed >> ((index & 3u) * 8u)) & 255u;\n")
                    .append("}\n");
        }
        return withLookups.substring(0, firstFunction) + lookupPrototypes
                + withLookups.substring(firstFunction) + lookupFunctions;
    }

    private static int parseNoiseTableByte(String literal, String name, int index) {
        String value = literal.trim();
        if (!value.endsWith("u")) {
            throw new IllegalArgumentException("Noise table " + name + " entry " + index
                    + " is not an unsigned literal: " + value);
        }
        try {
            int parsed = Integer.parseInt(value.substring(0, value.length() - 1));
            if (parsed < 0 || parsed > 255) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Noise table " + name + " entry " + index
                    + " is not a byte literal: " + value, failure);
        }
    }

    private static String replaceNoiseTableLookups(String source, List<String> names) {
        String result = source;
        for (String name : names) {
            result = replaceNoiseTableLookups(result, name);
        }
        return result;
    }

    private static String replaceNoiseTableLookups(String source, String name) {
        StringBuilder replaced = new StringBuilder(source.length());
        int cursor = 0;
        while (true) {
            int start = findNoiseTableLookup(source, name, cursor);
            if (start < 0) {
                replaced.append(source, cursor, source.length());
                return replaced.toString();
            }
            int open = start + name.length();
            while (open < source.length() && Character.isWhitespace(source.charAt(open))) open++;
            int close = matchingBracket(source, open);
            replaced.append(source, cursor, start).append(name).append("_lookup(")
                    .append(replaceNoiseTableLookups(source.substring(open + 1, close), name)).append(')');
            cursor = close + 1;
        }
    }

    private static int findNoiseTableLookup(String source, String name, int from) {
        int start = from;
        while ((start = source.indexOf(name, start)) >= 0) {
            int before = start - 1;
            int after = start + name.length();
            boolean identifierBefore = before >= 0 && isIdentifierPart(source.charAt(before));
            boolean identifierAfter = after < source.length() && isIdentifierPart(source.charAt(after));
            int bracket = after;
            while (bracket < source.length() && Character.isWhitespace(source.charAt(bracket))) bracket++;
            if (!identifierBefore && !identifierAfter && bracket < source.length() && source.charAt(bracket) == '[') {
                return start;
            }
            start = after;
        }
        return -1;
    }

    private static boolean isIdentifierPart(char value) {
        return value == '_' || Character.isLetterOrDigit(value);
    }

    private static int matchingBracket(String source, int open) {
        if (open < 0 || open >= source.length() || source.charAt(open) != '[') {
            throw new IllegalArgumentException("Missing noise table opening bracket");
        }
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '[') depth++;
            else if (value == ']' && --depth == 0) return index;
        }
        throw new IllegalArgumentException("Unterminated noise table lookup");
    }

    private static void stageDebug(boolean enabled, String message) {
        if (!enabled) return;
        String file = System.getProperty("tellurium.gpuCandidate.debugStagesFile", "").trim();
        if (file.isEmpty()) return;
        try {
            Path path = Path.of(file).toAbsolutePath().normalize();
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, message + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write staged shader debug log", failure);
        }
    }

    /**
     * Draft-only view of the eight corner carriers feeding the material-input
     * interpolation ABI.  This is intentionally opt-in: it is a diagnosis of
     * the host packing boundary, not a qualification check.
     */
    private static void debugDensityCornerRows(int[] coordinates, int[] first,
                                               int[] second, int[] selector) {
        if (!Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugDensityCornerRows", "false"))) return;
        StringBuilder message = new StringBuilder("density-corner-rows");
        int count = coordinates.length / 4;
        for (int index = 0; index < count; index++) {
            int coordinate = index * 4;
            int value = index * DENSITY_STAGE_VALUE_WORDS;
            message.append(" [").append(coordinates[coordinate]).append(',')
                    .append(coordinates[coordinate + 1]).append(',')
                    .append(coordinates[coordinate + 2]).append("] first=")
                    .append(carrierValue(first, value)).append(" second=")
                    .append(carrierValue(second, value)).append(" selector=")
                    .append(carrierValue(selector, value));
        }
        stageDebug(true, message.toString());
    }

    /** Logs the corresponding CPU split rows when the internal oracle is enabled. */
    private static void debugDensityCpuCornerRows(int[] coordinates, double[] first,
                                                  double[] second, double[] selector) {
        if (!Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugDensityCornerRows", "false"))) return;
        if (first == null || second == null || selector == null) {
            stageDebug(true, "density-cpu-corner-rows unavailable");
            return;
        }
        StringBuilder message = new StringBuilder("density-cpu-corner-rows");
        int count = coordinates.length / 3;
        int[] targetCoordinates = {
                512, -64, 512, 512, -64, 516, 512, -56, 512, 512, -56, 516,
                516, -64, 512, 516, -64, 516, 516, -56, 512, 516, -56, 516};
        for (int index = 0; index < targetCoordinates.length / 3; index++) {
            int target = index * 3;
            for (int candidate = 0; candidate < count; candidate++) {
                int coordinate = candidate * 3;
                if (coordinates[coordinate] == targetCoordinates[target]
                        && coordinates[coordinate + 1] == targetCoordinates[target + 1]
                        && coordinates[coordinate + 2] == targetCoordinates[target + 2]) {
                    message.append(" [").append(coordinates[coordinate]).append(',')
                            .append(coordinates[coordinate + 1]).append(',')
                            .append(coordinates[coordinate + 2]).append("] first=")
                            .append(first[candidate]).append(" second=").append(second[candidate])
                            .append(" selector=").append(selector[candidate]);
                    break;
                }
            }
        }
        stageDebug(true, message.toString());
    }

    private static void writeLiveStageDiagnostic(String fileName, String source) {
        String directory = System.getProperty("tellurium.gpuCandidate.stagedShaderDir", "").trim();
        if (directory.isEmpty()) return;
        try {
            Path root = Path.of(directory).toAbsolutePath().normalize();
            Files.createDirectories(root);
            Files.writeString(root.resolve(fileName), source);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write live staged shader diagnostic", failure);
        }
    }

    private static String debugStageValues(int[] words, int[] coordinateWords) {
        if (words.length < DENSITY_STAGE_VALUE_WORDS) return " values=empty";
        StringBuilder result = new StringBuilder(" first=")
                .append(carrierValue(words, 0));
        DensityProbe configuredProbe = parseDensityProbe();
        int probeX = configuredProbe == null ? 512 : configuredProbe.x();
        int probeY = configuredProbe == null ? -56 : configuredProbe.y();
        int probeZ = configuredProbe == null ? 512 : configuredProbe.z();
        int target = -1;
        int count = coordinateWords.length / 4;
        for (int index = 0; index < count; index++) {
            int offset = index * 4;
            if (coordinateWords[offset] == probeX && coordinateWords[offset + 1] == probeY
                    && coordinateWords[offset + 2] == probeZ) {
                target = index;
                break;
            }
        }
        if (target >= 0 && target * DENSITY_STAGE_VALUE_WORDS + 1 < words.length) {
            result.append(" probe=").append(probeX).append(',').append(probeY).append(',').append(probeZ).append(':')
                    .append(carrierValue(words, target * DENSITY_STAGE_VALUE_WORDS));
        }
        return result.toString();
    }

    /** Bounded debug view for the three prepared normal-noise coordinates. */
    private static String debugNoiseCoordinateValues(int[] words, int[] coordinateWords) {
        int target = debugProbeIndex(coordinateWords);
        int first = 0;
        StringBuilder result = new StringBuilder(" first=(")
                .append(carrierValue(words, first)).append(',')
                .append(carrierValue(words, first + 2)).append(',')
                .append(carrierValue(words, first + 4)).append(')');
        if (target >= 0 && target * NORMAL_NOISE_COORD_OUTPUT_WORDS + 5 < words.length) {
            int base = target * NORMAL_NOISE_COORD_OUTPUT_WORDS;
            result.append(" probe=").append(probeText()).append(":(")
                    .append(carrierValue(words, base)).append(',')
                    .append(carrierValue(words, base + 2)).append(',')
                    .append(carrierValue(words, base + 4)).append(')');
        }
        return result.toString();
    }

    /** Bounded debug view for one two-word Perlin result. */
    private static String debugSingleStageValues(int[] words, int[] coordinateWords) {
        if (words.length < DENSITY_STAGE_VALUE_WORDS) return "empty";
        StringBuilder result = new StringBuilder()
                .append(carrierValue(words, 0));
        int target = debugProbeIndex(coordinateWords);
        if (target >= 0 && target * DENSITY_STAGE_VALUE_WORDS + 1 < words.length) {
            result.append(" probe=").append(probeText()).append(':')
                    .append(carrierValue(words, target * DENSITY_STAGE_VALUE_WORDS));
        }
        return result.toString();
    }

    private static int debugProbeIndex(int[] coordinateWords) {
        DensityProbe configuredProbe = parseDensityProbe();
        int probeX = configuredProbe == null ? 512 : configuredProbe.x();
        int probeY = configuredProbe == null ? -56 : configuredProbe.y();
        int probeZ = configuredProbe == null ? 512 : configuredProbe.z();
        for (int index = 0; index < coordinateWords.length / 4; index++) {
            int offset = index * 4;
            if (coordinateWords[offset] == probeX && coordinateWords[offset + 1] == probeY
                    && coordinateWords[offset + 2] == probeZ) return index;
        }
        return -1;
    }

    private static String probeText() {
        DensityProbe configuredProbe = parseDensityProbe();
        int x = configuredProbe == null ? 512 : configuredProbe.x();
        int y = configuredProbe == null ? -56 : configuredProbe.y();
        int z = configuredProbe == null ? 512 : configuredProbe.z();
        return x + "," + y + "," + z;
    }

    private static String densityCornerDiagnostic(int[] coordinates, double[] values,
                                                  DensityProbe probe, int xCellSize,
                                                  int yCellSize, int zCellSize) {
        int x0 = cellLow(probe.x(), xCellSize), x1 = x0 + xCellSize;
        int y0 = cellLow(probe.y(), yCellSize), y1 = y0 + yCellSize;
        int z0 = cellLow(probe.z(), zCellSize), z1 = z0 + zCellSize;
        int[][] corners = {
                {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z0}, {x0, y1, z1},
                {x1, y0, z0}, {x1, y0, z1}, {x1, y1, z0}, {x1, y1, z1}
        };
        double[] cornerValues = new double[corners.length];
        for (int corner = 0; corner < corners.length; corner++) {
            cornerValues[corner] = values[findCoordinateIndex(coordinates,
                    new DensityProbe(corners[corner][0], corners[corner][1], corners[corner][2]))];
        }
        double tx = (probe.x() - x0) / (double) xCellSize;
        double ty = (probe.y() - y0) / (double) yCellSize;
        double tz = (probe.z() - z0) / (double) zCellSize;
        double x00 = lerp(cornerValues[0], cornerValues[4], tx);
        double x01 = lerp(cornerValues[1], cornerValues[5], tx);
        double x10 = lerp(cornerValues[2], cornerValues[6], tx);
        double x11 = lerp(cornerValues[3], cornerValues[7], tx);
        double cpuInterpolation = lerp(lerp(x00, x10, ty), lerp(x01, x11, ty), tz);
        return " node5Corners=" + Arrays.toString(cornerValues)
                + " cornerFractions=" + Arrays.toString(new double[]{tx, ty, tz})
                + " cornerInterp=" + cpuInterpolation;
    }

    private static double lerp(double left, double right, double fraction) {
        return left + fraction * (right - left);
    }

    private static String debugRawCarrier(int[] words) {
        if (words.length < DENSITY_STAGE_VALUE_WORDS) return " value=empty";
        long bits = (Integer.toUnsignedLong(words[1]) << 32)
                | Integer.toUnsignedLong(words[0]);
        return " value=" + Double.longBitsToDouble(bits)
                + " bits=0x" + Long.toHexString(bits);
    }

    private record GlslFunction(String returnType, String name, int start, int end) {}

    private record DirectIntegerRamp(String selector, int lower, int upper,
                                     long lowerBits, long upperBits) {}

    private record GlobalTableCompaction(String source, int removed) {}

    private static EndStageInputs buildEndStageInputs(int[] coordinates) {
        if (coordinates.length == 0 || coordinates.length % 3 != 0) {
            throw new IllegalArgumentException("End staged coordinates must contain XYZ triples");
        }
        Map<Point, Integer> unique = new LinkedHashMap<>();
        int blockCount = coordinates.length / 3;
        int[] cornerIndices = new int[Math.multiplyExact(blockCount, 8)];
        for (int block = 0; block < blockCount; block++) {
            int coordinate = block * 3;
            int x = coordinates[coordinate], y = coordinates[coordinate + 1], z = coordinates[coordinate + 2];
            int x0 = cellLow(x, 8), x1 = Math.addExact(x0, 8);
            int y0 = cellLow(y, 4), y1 = Math.addExact(y0, 4);
            int z0 = cellLow(z, 8), z1 = Math.addExact(z0, 8);
            Point[] corners = {
                    new Point(x0, y0, z0), new Point(x0, y0, z1),
                    new Point(x0, y1, z0), new Point(x0, y1, z1),
                    new Point(x1, y0, z0), new Point(x1, y0, z1),
                    new Point(x1, y1, z0), new Point(x1, y1, z1)
            };
            for (int corner = 0; corner < corners.length; corner++) {
                cornerIndices[block * 8 + corner] = unique.computeIfAbsent(corners[corner], ignored -> unique.size());
            }
        }
        int[] uniqueCoordinates = new int[Math.multiplyExact(unique.size(), 4)];
        for (var entry : unique.entrySet()) {
            int word = entry.getValue() * 4;
            uniqueCoordinates[word] = entry.getKey().x();
            uniqueCoordinates[word + 1] = entry.getKey().y();
            uniqueCoordinates[word + 2] = entry.getKey().z();
        }
        return new EndStageInputs(uniqueCoordinates, cornerIndices);
    }

    private static int cellLow(int coordinate, int cellSize) {
        return Math.multiplyExact(Math.floorDiv(coordinate, cellSize), cellSize);
    }

    static void requireDenseMaterialScope(StructureBlendSnapshot blend) {
        Objects.requireNonNull(blend, "blend");
        if (blend.hasHeightAndBiomeData()
                || !blend.densities().isEmpty() || !blend.alphas().isEmpty()
                || !blend.densitySamples().isEmpty() || !blend.directDensitySamples().isEmpty()
                || !blend.heightSamples().isEmpty() || !blend.directHeightSamples().isEmpty()) {
            throw new UnsupportedOperationException(
                    "GPU candidate replay requires empty old-world blending data; captured terrain blending is not yet admitted");
        }
    }

    /**
     * Binds only immutable captured material inputs to the shader.  Ore state
     * IDs are result-local registry IDs, never Minecraft's process-global IDs;
     * the six entries must all be present before shader emission. Aquifer
     * statuses are captured for this exact chunk and their pressure decision
     * stays in the emitted shader.
     */
    static WorldgenShaderCompiler.MaterialOptions materialOptions(
            dev.tellurium.semantic.snapshot.WorldgenSnapshot snapshot, BlockStateTable table) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.generatorSettings().aquifersEnabled()) {
            throw new IllegalArgumentException("Aquifer material capture requires the target chunk coordinates");
        }
        return materialOptions(snapshot, table, 0, 0);
    }

    static WorldgenShaderCompiler.MaterialOptions materialOptions(
            dev.tellurium.semantic.snapshot.WorldgenSnapshot snapshot, BlockStateTable table,
            int chunkX, int chunkZ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(table, "table");
        GeneratorSettingsSnapshot settings = snapshot.generatorSettings();
        OreVeinProgram oreProgram = OreVeinProgram.disabled();
        dev.tellurium.semantic.snapshot.PositionalRandomFactorySnapshot oreRandom = null;
        List<Integer> oreStateIds = List.of();
        if (settings.oresEnabled()) {
            oreRandom = snapshot.randomState().oreRandom();
            if (oreRandom == null) {
                throw new IllegalStateException(
                        "GPU candidate replay cannot enable ores without a captured ore positional RNG");
            }
            oreProgram = OreVeinProgram.vanilla(snapshot.seed());
            List<Integer> stateIds = new ArrayList<>(oreProgram.materials().size());
            for (String material : oreProgram.materials()) {
                int stateId = table.id(material);
                if (stateId < 0) {
                    throw new IllegalStateException(
                            "GPU candidate replay registry is missing captured ore material " + material);
                }
                stateIds.add(stateId);
            }
            oreStateIds = List.copyOf(stateIds);
        }
        AquiferEmitter.Options aquifer = aquiferOptions(snapshot, table, chunkX, chunkZ);
        if (!oreProgram.enabled() && !aquifer.enabled() && !aquifer.defaultFluidFallback()) {
            return WorldgenShaderCompiler.MaterialOptions.disabled();
        }
        return new WorldgenShaderCompiler.MaterialOptions(oreProgram, oreRandom, oreStateIds, aquifer);
    }

    private static AquiferEmitter.Options aquiferOptions(
            dev.tellurium.semantic.snapshot.WorldgenSnapshot snapshot, BlockStateTable table,
            int chunkX, int chunkZ) {
        GeneratorSettingsSnapshot settings = snapshot.generatorSettings();
        if (!settings.aquifersEnabled()) {
            String defaultFluidState = settings.defaultFluid().canonical();
            String lavaState = capturedState(snapshot, "minecraft:lava", "minecraft:lava");
            int defaultFluidStateId = table.id(defaultFluidState);
            int lavaStateId = table.id(lavaState);
            if (defaultFluidStateId < 0 || lavaStateId < 0) {
                throw new IllegalStateException(
                        "Captured registry lacks the disabled-aquifer global fluid state(s)");
            }
            return AquiferEmitter.Options.defaultFluid(
                    settings.seaLevel(),
                    AquiferProgram.VANILLA_GLOBAL_LAVA_LEVEL,
                    defaultFluidStateId, lavaStateId);
        }
        var random = snapshot.randomState().aquiferRandom();
        if (random == null) {
            throw new IllegalStateException(
                    "GPU candidate replay cannot enable aquifers without a captured aquifer positional RNG");
        }
        if (!snapshot.router().complete()) {
            throw new IllegalStateException("GPU aquifer capture requires all 15 router roots");
        }
        String waterState = settings.defaultFluid().canonical();
        String lavaState = capturedState(snapshot, "minecraft:lava", "minecraft:lava");
        int waterStateId = table.id(waterState);
        int lavaStateId = table.id(lavaState);
        if (waterStateId < 0 || lavaStateId < 0) {
            throw new IllegalStateException("Captured registry lacks aquifer water or lava state");
        }
        AquiferProgram program = new AquiferProgram(true, 16, waterState, lavaState,
                settings.seaLevel(), AquiferProgram.VANILLA_GLOBAL_LAVA_LEVEL);
        int minX = Math.multiplyExact(chunkX, DenseNoiseGenerator.CHUNK_SIZE);
        int minZ = Math.multiplyExact(chunkZ, DenseNoiseGenerator.CHUNK_SIZE);
        int maxX = Math.addExact(minX, DenseNoiseGenerator.CHUNK_SIZE - 1);
        int maxZ = Math.addExact(minZ, DenseNoiseGenerator.CHUNK_SIZE - 1);
        int maxY = Math.addExact(settings.minY(), settings.logicalHeight() - 1);
        WorldgenProgram router = WorldgenProgram.builder().roots(snapshot.router().roots()).build();
        // The CPU candidate evaluates aquifer helper roots through the same
        // request-owned structure/blend and finite NoiseChunk FlatCache
        // domain used by captured generation.  Capturing statuses with the
        // default empty context silently changes marker/interpolation values
        // for real seeds, even though the density carrier itself is exact.
        int cellCountXZ = 16 / settings.cellWidth();
        int coveredBlocks = Math.multiplyExact(cellCountXZ, settings.cellWidth());
        int noiseSizeXZ = Math.floorDiv(coveredBlocks, 4);
        dev.tellurium.semantic.program.MarkerContext.FlatCacheBounds flatCacheBounds =
                new dev.tellurium.semantic.program.MarkerContext.FlatCacheBounds(
                        Math.floorDiv(minX, 4), Math.floorDiv(minZ, 4), noiseSizeXZ);
        AquiferEvaluator.EvaluationContext aquiferContext = new AquiferEvaluator.EvaluationContext(
                snapshot.structureBlend(), flatCacheBounds);
        List<AquiferEvaluator.CapturedCandidate> captured = new AquiferEvaluator().captureDeviceCandidates(
                program, router, random, settings, snapshot.seed(),
                minX, maxX, settings.minY(), maxY, minZ, maxZ, aquiferContext);
        List<AquiferEmitter.Candidate> candidates = new ArrayList<>(captured.size());
        for (AquiferEvaluator.CapturedCandidate candidate : captured) {
            int stateId = table.id(candidate.state());
            if (stateId < 0) {
                throw new IllegalStateException("Captured registry lacks aquifer status state " + candidate.state());
            }
            candidates.add(new AquiferEmitter.Candidate(candidate.x(), candidate.y(), candidate.z(),
                    candidate.level(), stateId, candidate.fluid()));
        }
        return new AquiferEmitter.Options(true, 16, candidates, settings.seaLevel(),
                AquiferProgram.VANILLA_GLOBAL_LAVA_LEVEL,
                waterStateId, lavaStateId);
    }

    private static String capturedState(
            dev.tellurium.semantic.snapshot.WorldgenSnapshot snapshot,
            String blockName, String fallback) {
        return snapshot.registry().states().stream()
                .filter(state -> state.name().equals(blockName))
                .sorted(java.util.Comparator
                        .comparingInt((dev.tellurium.semantic.snapshot.BlockStateDescriptor state)
                                -> "0".equals(state.properties().get("level")) ? 0 : 1)
                        .thenComparing(dev.tellurium.semantic.snapshot.BlockStateDescriptor::canonical))
                .map(dev.tellurium.semantic.snapshot.BlockStateDescriptor::canonical)
                .findFirst()
                .orElse(fallback);
    }

    /**
     * Builds the result ABI allow-list from every material stage bound to the
     * shader. The default/air/invalid sentinels are not enough once the
     * device-side ore or captured aquifer stage can emit result-local IDs.
     */
    static int[] allowedStateIds(int defaultStateId, int airStateId, int invalidStateId,
                                 WorldgenShaderCompiler.MaterialOptions material) {
        if (defaultStateId < 0 || airStateId < 0 || invalidStateId < 0) {
            throw new IllegalArgumentException("Result ABI state IDs must be non-negative");
        }
        Objects.requireNonNull(material, "material");
        var ids = new java.util.LinkedHashSet<Integer>();
        ids.add(defaultStateId);
        ids.add(airStateId);
        ids.add(invalidStateId);
        material.oreStateIds().forEach(ids::add);
        material.aquifer().candidates().stream().mapToInt(candidate -> candidate.stateId())
                .forEach(ids::add);
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Prototype-only ABI guard: every emitted ID must still belong to the captured registry. */
    private static int[] registryStateIds(int stateCount, int invalidStateId) {
        if (stateCount <= 0 || invalidStateId < 0) {
            throw new IllegalArgumentException("Captured registry state count must be positive");
        }
        int[] ids = new int[Math.addExact(stateCount, 1)];
        for (int index = 0; index < stateCount; index++) ids[index] = index;
        ids[stateCount] = invalidStateId;
        return ids;
    }

    private static void fillCoordinates(int[] coordinates, GeneratorSettingsSnapshot settings,
                                        int chunkX, int chunkZ) {
        int baseX = Math.multiplyExact(chunkX, DenseNoiseGenerator.CHUNK_SIZE);
        int baseZ = Math.multiplyExact(chunkZ, DenseNoiseGenerator.CHUNK_SIZE);
        int cursor = 0;
        for (int localY = 0; localY < settings.logicalHeight(); localY++) {
            int worldY = Math.addExact(settings.minY(), localY);
            for (int z = 0; z < DenseNoiseGenerator.CHUNK_SIZE; z++) {
                for (int x = 0; x < DenseNoiseGenerator.CHUNK_SIZE; x++) {
                    coordinates[cursor++] = Math.addExact(baseX, x);
                    coordinates[cursor++] = worldY;
                    coordinates[cursor++] = Math.addExact(baseZ, z);
                }
            }
        }
        if (cursor != coordinates.length) throw new AssertionError("Coordinate fill count changed");
    }

    private static String[] decodeStates(int[] stateIds, BlockStateTable table,
                                          GeneratorSettingsSnapshot settings, int invalidStateId) {
        int storageBlocks = Math.multiplyExact(settings.height(), 256);
        String[] states = new String[storageBlocks];
        String air = table.state(table.id("minecraft:air")).canonical();
        Arrays.fill(states, air);
        int logicalBlocks = Math.multiplyExact(settings.logicalHeight(), 256);
        if (stateIds.length != logicalBlocks) {
            throw new IllegalStateException("GPU logical output length does not match captured bounds");
        }
        for (int index = 0; index < stateIds.length; index++) {
            int stateId = stateIds[index];
            if (stateId == invalidStateId) throw new IllegalStateException("GPU returned invalid state sentinel at block " + index);
            states[index] = table.state(stateId).canonical();
        }
        return states;
    }

    private static void requireFiniteDensityWords(int[] words, int[] coordinates) {
        int elementCount = coordinates.length / 3;
        if (words.length != Math.multiplyExact(elementCount, DENSITY_STAGE_VALUE_WORDS)) {
            throw new IllegalStateException("GPU density material-input output length is " + words.length
                    + ", expected " + Math.multiplyExact(elementCount, DENSITY_STAGE_VALUE_WORDS));
        }
        for (int index = 0; index < elementCount; index++) {
            int word = index * DENSITY_STAGE_VALUE_WORDS;
            long bits = (Integer.toUnsignedLong(words[word + 1]) << 32)
                    | Integer.toUnsignedLong(words[word]);
            if (!Double.isFinite(Double.longBitsToDouble(bits))) {
                int coordinate = index * 3;
                throw new IllegalStateException("GPU density material-input returned a non-finite carrier at block "
                        + index + " point=" + coordinates[coordinate] + "," + coordinates[coordinate + 1]
                        + "," + coordinates[coordinate + 2] + " words=" + words[word] + "," + words[word + 1]);
            }
        }
    }

    private static String carrierValue(int[] words, int offset) {
        return Double.toString(carrierDouble(words, offset));
    }

    private static int[] cpuDensityWords(double[] values) {
        int[] words = new int[Math.multiplyExact(values.length, DENSITY_STAGE_VALUE_WORDS)];
        for (int index = 0; index < values.length; index++) {
            double value = values[index];
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("CPU density material fallback contains a non-finite value at "
                        + index);
            }
            long bits = Double.doubleToRawLongBits(value);
            int target = index * DENSITY_STAGE_VALUE_WORDS;
            words[target] = (int) bits;
            words[target + 1] = (int) (bits >>> 32);
        }
        return words;
    }

    private static double carrierDouble(int[] words, int offset) {
        long bits = (Integer.toUnsignedLong(words[offset + 1]) << 32)
                | Integer.toUnsignedLong(words[offset]);
        return Double.longBitsToDouble(bits);
    }

    private static void requireDirectBranchParity(double[] expected, int[] componentWords,
                                                   int[] coordinates) {
        requireDensityComponentParity(expected, componentWords, 1, coordinates, "direct density branch");
    }

    private static void requireDensityComponentParity(double[] expected, int[] componentWords,
                                                       int slot, int[] coordinates, String label) {
        requireDensityComponentParity(expected, componentWords, slot, 6, coordinates, label);
    }

    private static void requireDensityComponentParity(double[] expected, int[] componentWords,
                                                       int slot, int wordsPerElement,
                                                       int[] coordinates, String label) {
        String failure = densityComponentParityFailure(expected, componentWords, slot, wordsPerElement,
                coordinates, label);
        if (failure != null) throw new IllegalStateException(failure);
    }

    private static void appendDensityComponentFailure(StringBuilder output, double[] expected,
                                                       int[] componentWords, int slot,
                                                       int wordsPerElement, int[] coordinates, String label) {
        String failure = densityComponentParityFailure(expected, componentWords, slot, wordsPerElement,
                coordinates, label);
        if (failure != null) output.append(failure).append('\n');
    }

    private static String densityComponentParityFailure(double[] expected, int[] componentWords,
                                                         int slot, int wordsPerElement,
                                                         int[] coordinates, String label) {
        int elementCount = coordinates.length / 3;
        if (expected.length != elementCount) {
            return "CPU/GPU " + label + " diagnostic length mismatch: cpu="
                    + expected.length + " gpu=" + elementCount;
        }
        int mismatches = 0;
        int signMismatches = 0;
        StringBuilder first = new StringBuilder();
        for (int index = 0; index < elementCount; index++) {
            int word = index * wordsPerElement + slot * 2;
            long actualBits = (Integer.toUnsignedLong(componentWords[word + 1]) << 32)
                    | Integer.toUnsignedLong(componentWords[word]);
            long expectedBits = Double.doubleToRawLongBits(expected[index]);
            if (expectedBits == actualBits) continue;
            mismatches++;
            if ((expectedBits ^ actualBits) < 0) signMismatches++;
            if (mismatches <= 12) {
                int coordinate = index * 3;
                first.append("#").append(index).append("(")
                        .append(coordinates[coordinate]).append(",")
                        .append(coordinates[coordinate + 1]).append(",")
                        .append(coordinates[coordinate + 2]).append(") cpu=")
                        .append(Double.longBitsToDouble(expectedBits)).append(" gpu=")
                        .append(Double.longBitsToDouble(actualBits)).append(" cpuBits=0x")
                        .append(String.format(Locale.ROOT, "%016x", expectedBits))
                        .append(" gpuBits=0x")
                        .append(String.format(Locale.ROOT, "%016x", actualBits)).append("\n");
            }
        }
        if (mismatches != 0) {
            return "GPU " + label + " differs from CPU at " + mismatches
                    + " of " + elementCount + " logical blocks; signMismatches=" + signMismatches
                    + "\nfirst mismatches:\n" + first;
        }
        return null;
    }

    private static void requireDensityParity(double[] expected, int[] words, int[] coordinates) {
        int elementCount = coordinates.length / 3;
        if (expected.length != elementCount) {
            throw new IllegalStateException("CPU/GPU density diagnostic length mismatch: cpu="
                    + expected.length + " gpu=" + elementCount);
        }
        int mismatches = 0;
        int signMismatches = 0;
        StringBuilder first = new StringBuilder();
        for (int index = 0; index < elementCount; index++) {
            int word = index * DENSITY_STAGE_VALUE_WORDS;
            long actualBits = (Integer.toUnsignedLong(words[word + 1]) << 32)
                    | Integer.toUnsignedLong(words[word]);
            long expectedBits = Double.doubleToRawLongBits(expected[index]);
            if (expectedBits == actualBits) continue;
            mismatches++;
            if ((expectedBits ^ actualBits) < 0) signMismatches++;
            if (mismatches <= 12) {
                int coordinate = index * 3;
                first.append("#").append(index).append("(")
                        .append(coordinates[coordinate]).append(",")
                        .append(coordinates[coordinate + 1]).append(",")
                        .append(coordinates[coordinate + 2]).append(") cpu=")
                        .append(Double.longBitsToDouble(expectedBits)).append(" gpu=")
                        .append(Double.longBitsToDouble(actualBits)).append(" cpuBits=0x")
                        .append(String.format(Locale.ROOT, "%016x", expectedBits))
                        .append(" gpuBits=0x")
                        .append(String.format(Locale.ROOT, "%016x", actualBits)).append("\n");
            }
        }
        if (mismatches != 0) {
            throw new IllegalStateException("GPU density carrier differs from CPU at " + mismatches
                    + " of " + elementCount + " logical blocks; signMismatches=" + signMismatches
                    + "\nfirst mismatches:\n" + first);
        }
    }

    private static void requireFiniteStageWords(int[] words, int[] coordinates,
                                                int stageIndex, String root) {
        int elementCount = coordinates.length / 4;
        if (words.length != Math.multiplyExact(elementCount, DENSITY_STAGE_VALUE_WORDS)) {
            throw new IllegalStateException("GPU density stage " + stageIndex + " root=" + root
                    + " returned " + words.length + " words, expected "
                    + Math.multiplyExact(elementCount, DENSITY_STAGE_VALUE_WORDS));
        }
        for (int index = 0; index < elementCount; index++) {
            int word = index * DENSITY_STAGE_VALUE_WORDS;
            long bits = (Integer.toUnsignedLong(words[word + 1]) << 32)
                    | Integer.toUnsignedLong(words[word]);
            if (!Double.isFinite(Double.longBitsToDouble(bits))) {
                int coordinate = index * 4;
                throw new IllegalStateException("GPU density stage " + stageIndex + " root=" + root
                        + " returned a non-finite carrier at point=" + coordinates[coordinate] + ","
                        + coordinates[coordinate + 1] + "," + coordinates[coordinate + 2]
                        + " words=" + words[word] + "," + words[word + 1]);
            }
        }
    }

    private static void requireValidMaterialStageWords(int[] words, int[] coordinates,
                                                       int wordsPerElement, String stage) {
        int elementCount = coordinates.length / 3;
        if (words.length != Math.multiplyExact(elementCount, wordsPerElement)) {
            throw new IllegalStateException("GPU " + stage + " output length is " + words.length
                    + ", expected " + Math.multiplyExact(elementCount, wordsPerElement));
        }
        for (int index = 0; index < elementCount; index++) {
            int word = index * wordsPerElement;
            if (words[word] <= 1) continue;
            int coordinate = index * 3;
            throw new IllegalStateException("GPU " + stage + " stage returned failure sentinel " + words[word]
                    + " at block " + index + " point=" + coordinates[coordinate] + ","
                    + coordinates[coordinate + 1] + "," + coordinates[coordinate + 2]
                    + " words=" + Arrays.toString(Arrays.copyOfRange(words, word, word + wordsPerElement)));
        }
    }

    private static int compare(String[] expected, String[] actual) {
        if (expected.length != actual.length) throw new IllegalArgumentException("GPU/CPU state lengths differ");
        int mismatches = 0;
        for (int index = 0; index < expected.length; index++) {
            if (!Objects.equals(expected[index], actual[index])) mismatches++;
        }
        return mismatches;
    }

    private static String mismatchSummary(String[] expected, String[] actual, int[] coordinates) {
        Map<String, Integer> pairCounts = new LinkedHashMap<>();
        StringBuilder first = new StringBuilder("first mismatches:");
        int reported = 0;
        for (int index = 0; index < expected.length; index++) {
            if (Objects.equals(expected[index], actual[index])) continue;
            pairCounts.merge(expected[index] + " -> " + actual[index], 1, Math::addExact);
            if (reported < 12) {
                int coordinate = index * 3;
                first.append(" #").append(index).append("(")
                        .append(coordinates[coordinate]).append(',')
                        .append(coordinates[coordinate + 1]).append(',')
                        .append(coordinates[coordinate + 2]).append(") ")
                        .append(expected[index]).append(" -> ").append(actual[index]);
                reported++;
            }
        }
        return first.append("; top pairs=").append(pairCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(8)
                .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                .toList()).toString();
    }

    private static int compare(boolean[] expected, boolean[] actual) {
        if (expected.length != actual.length) throw new IllegalArgumentException("GPU/CPU fluid-mark lengths differ");
        int mismatches = 0;
        for (int index = 0; index < expected.length; index++) {
            if (expected[index] != actual[index]) mismatches++;
        }
        return mismatches;
    }

    static boolean decodeFluidMark(int value, String stage) {
        if (value == 0) return false;
        if (value == 1) return true;
        throw new IllegalStateException("GPU " + stage + " shader returned a non-boolean fluid mark: " + value);
    }

    /**
     * Expand the logical-height GPU mark stream to the captured storage height.
     * The blocks above the logical world are the air tail emitted by
     * {@link #decodeStates(int[], BlockStateTable, GeneratorSettingsSnapshot, int)}
     * and therefore cannot carry fluid post-processing marks.
     */
    static boolean[] decodeFluidMarks(boolean[] logicalMarks, GeneratorSettingsSnapshot settings) {
        Objects.requireNonNull(logicalMarks, "logicalMarks");
        Objects.requireNonNull(settings, "settings");
        int storageBlocks = Math.multiplyExact(settings.height(), 256);
        int logicalBlocks = Math.multiplyExact(settings.logicalHeight(), 256);
        if (logicalMarks.length != logicalBlocks) {
            throw new IllegalStateException("GPU logical fluid-mark output length does not match captured bounds");
        }
        boolean[] marks = new boolean[storageBlocks];
        System.arraycopy(logicalMarks, 0, marks, 0, logicalMarks.length);
        return marks;
    }

    static ChunkNoiseResult resultWithGpuStates(MinecraftCpuCandidate.Inputs inputs, String[] states,
                                                        boolean[] fluidMarks) {
        if (fluidMarks == null || fluidMarks.length != states.length) {
            throw new IllegalArgumentException("GPU post-processing mark count does not match dense states");
        }
        var snapshot = inputs.snapshot();
        var settings = snapshot.generatorSettings();
        DenseNoiseGenerator.GeneratedChunk generated = new DenseNoiseGenerator.GeneratedChunk(
                inputs.chunkX(), inputs.chunkZ(), settings.minY(), settings.height(),
                states, new int[256], fluidMarks, snapshot.fingerprint());
        var maps = generated.heightmapSet(snapshot.registry());
        var heightmaps = new HeightmapPayload(java.util.Map.of(
                "OCEAN_FLOOR_WG", maps.get("OCEAN_FLOOR_WG"),
                "WORLD_SURFACE_WG", maps.get("WORLD_SURFACE_WG")));
        var header = new ChunkResultHeader(inputs.chunkX(), inputs.chunkZ(), settings.minY(), settings.height(),
                snapshot.fingerprint(), snapshot.registry().fingerprint(), "chunk-result-v4");
        return ChunkNoiseResult.ofDense(header, BlockStateTable.fromRegistry(snapshot.registry()), states,
                heightmaps, fluidMarks, inputs.prerequisiteMetadata());
    }

    private static int integerProperty(String name, int fallback) {
        try {
            return Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid integer property " + name, failure);
        }
    }

    private static double doubleProperty(String name, double fallback) {
        final double value;
        try {
            value = Double.parseDouble(System.getProperty(name, Double.toString(fallback)));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid double property " + name, failure);
        }
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException("GPU candidate property " + name
                    + " must be a finite non-negative number");
        }
        return value;
    }

    /**
     * Applies the bounded direct-stage diagnostic to the entry domain only.
     * Interpolation children must retain their complete corner domain or the
     * parent eight-corner ABI becomes invalid; recursive child calls therefore
     * explicitly bypass this helper.
     */
    private static int[] directStageProbeCoordinates(int[] coordinateWords) {
        if (coordinateWords.length == 0 || coordinateWords.length % 4 != 0) {
            throw new IllegalArgumentException(
                    "GPU candidate direct-stage probe domain must contain four words per element");
        }
        int probeElements = integerProperty(
                "tellurium.gpuCandidate.directStageProbeElements", -1);
        if (probeElements == -1) return coordinateWords;
        int available = coordinateWords.length / 4;
        if (probeElements <= 0 || probeElements > available) {
            throw new IllegalArgumentException("GPU candidate directStageProbeElements must be -1 or in [1, "
                    + available + "]");
        }
        if (probeElements == available) return coordinateWords;
        return Arrays.copyOf(coordinateWords, Math.multiplyExact(probeElements, 4));
    }

    private static int densityStagePlanLimit() {
        int limit = integerProperty("tellurium.gpuCandidate.maxDensityStages",
                DENSITY_STAGE_MAX_PLAN_COUNT);
        if (limit <= 0) {
            throw new IllegalArgumentException("GPU candidate maxDensityStages must be positive");
        }
        return limit;
    }

    private static void enforceShaderSourceBudget(WorldgenShaderCompiler.Shader shader,
                                                  boolean stageDebugEnabled) {
        int limit = integerProperty("tellurium.gpuCandidate.maxShaderSourceChars",
                GPU_SHADER_SOURCE_MAX_CHARS);
        if (limit == -1) return;
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "GPU candidate maxShaderSourceChars must be -1 or positive");
        }
        int actual = shader.source().length();
        if (actual <= limit) return;
        String detail = "compile-budget-fail profile=" + shader.profile()
                + " sourceChars=" + actual + " maxSourceChars=" + limit
                + " programHash=" + shader.programHash();
        stageDebug(stageDebugEnabled, detail);
        throw new IllegalStateException("GPU candidate shader compile budget exceeded before dispatch: " + detail
                + "; set tellurium.gpuCandidate.maxShaderSourceChars=-1 only for a bounded driver experiment");
    }

    private static int densityStageBundleSourceLimit() {
        String configured = System.getProperty(
                "tellurium.gpuCandidate.densityStageBundleMaxSourceChars", "").trim();
        int limit;
        if (configured.isEmpty()) {
            // The native draft has already shown that bundled graph modules
            // can produce a different carrier on the target driver. Keep the
            // prototype on single-root stages until that ABI is repaired.
            limit = Boolean.getBoolean("tellurium.gpuCandidate.nativeDraft") ? 1
                    : DENSITY_STAGE_BUNDLE_MAX_SOURCE_CHARS;
        } else {
            try {
                limit = Integer.parseInt(configured);
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(
                        "Invalid integer property tellurium.gpuCandidate.densityStageBundleMaxSourceChars",
                        failure);
            }
        }
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "GPU candidate densityStageBundleMaxSourceChars must be positive");
        }
        return limit;
    }

    private static boolean splitNormalNoise(WorldgenShaderCompiler.Shader shader) {
        String configured = System.getProperty(
                "tellurium.gpuCandidate.splitNormalNoise", "").trim();
        if (!configured.isEmpty()) return Boolean.parseBoolean(configured);
        // The target NVIDIA driver miscompiles the exact monolithic normal-
        // noise leaf inside a density parent (the carrier collapses to zero),
        // while the bounded GPU Perlin route is finite and independently
        // probeable.  Keep the split path as the prototype default; callers
        // can still force the old monolithic experiment explicitly.
        return true;
    }

    private static boolean splitNormalNoise(String source) {
        String configured = System.getProperty(
                "tellurium.gpuCandidate.splitNormalNoise", "").trim();
        if (!configured.isEmpty()) return Boolean.parseBoolean(configured);
        return true;
    }

    /**
     * Keep native-draft modules below the target driver's observed compiler
     * envelope.  The exact integer-carrier route has already crossed the full
     * captured density plan with the wider 75k/200-function policy, while the
     * native FP64 route spends substantially more compiler memory per retained
     * graph closure.  The override exists for bounded compiler experiments and
     * is never used to relabel a receipt.
     */
    private static boolean aggressiveDensityStaging(WorldgenShaderCompiler.Shader shader) {
        String configured = System.getProperty(
                "tellurium.gpuCandidate.aggressiveDensityStaging", "").trim();
        if (!configured.isEmpty()) return Boolean.parseBoolean(configured);
        return shader.profile() == NumericProfile.GPU_NATIVE_DRAFT;
    }

    /**
     * Interpolation children are evaluated over the corner domain and can
     * contain the complete captured branch graph. Keep that domain on the
     * wider stage envelope by default; otherwise native-draft containment
     * expands a roughly 400-stage branch into thousands of tiny modules.
     * This remains an explicit diagnostic override for compiler experiments.
     */
    private static boolean interpolationAggressiveDensityStaging() {
        String configured = System.getProperty(
                "tellurium.gpuCandidate.interpolationAggressiveDensityStaging", "").trim();
        return !configured.isEmpty() && Boolean.parseBoolean(configured);
    }

    private record DensityProbe(int x, int y, int z) {}

    private static DensityProbe parseDensityProbe() {
        return parseConfiguredProbe("tellurium.gpuCandidate.debugDensityProbePoint", "debugDensityProbePoint");
    }

    private static DensityProbe parseConfiguredProbe(String property, String label) {
        String configured = System.getProperty(property, "").trim();
        if (configured.isEmpty()) return null;
        String[] parts = configured.split(",", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "GPU candidate " + label + " must be three comma-separated integers");
        }
        try {
            return new DensityProbe(Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "GPU candidate " + label + " must be three comma-separated integers", failure);
        }
    }

    private static int findCoordinateIndex(int[] coordinates, DensityProbe probe) {
        for (int index = 0; index < coordinates.length / 3; index++) {
            int offset = index * 3;
            if (coordinates[offset] == probe.x() && coordinates[offset + 1] == probe.y()
                    && coordinates[offset + 2] == probe.z()) return index;
        }
        throw new IllegalArgumentException("GPU candidate debugDensityProbePoint is outside the captured chunk: "
                + probe.x() + "," + probe.y() + "," + probe.z());
    }
}
