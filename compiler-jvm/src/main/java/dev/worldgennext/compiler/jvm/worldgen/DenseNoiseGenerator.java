// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.jvm.worldgen;

import dev.worldgennext.semantic.material.AquiferProgram;
import dev.worldgennext.semantic.material.MaterialProgram;
import dev.worldgennext.semantic.material.OreVeinProgram;
import dev.worldgennext.semantic.snapshot.NoiseParameters;
import dev.worldgennext.semantic.snapshot.BlockStateTraits;
import dev.worldgennext.semantic.snapshot.RegistrySnapshot;
import dev.worldgennext.semantic.snapshot.WorldgenSnapshot;
import dev.worldgennext.semantic.program.ProgramNode;
import dev.worldgennext.semantic.program.MarkerContext;
import dev.worldgennext.semantic.program.WorldgenProgram;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.Map;

/**
 * Scalar complete-result baseline. It deliberately returns dense canonical state names so
 * material encoding happens after every block has been classified.
 */
public final class DenseNoiseGenerator {
    public static final int CHUNK_SIZE = 16;
    public record GeneratedChunk(int chunkX, int chunkZ, int minY, int height, String[] states,
                                 int[] heightmaps, boolean[] fluidMarks, String generatorIdentity) {
        public GeneratedChunk {
            int expectedStateCount;
            try { expectedStateCount = Math.multiplyExact(Math.multiplyExact(height, CHUNK_SIZE), CHUNK_SIZE); }
            catch (ArithmeticException overflow) { throw new IllegalArgumentException("Dense chunk geometry overflows", overflow); }
            if (height <= 0 || states == null || states.length != expectedStateCount) throw new IllegalArgumentException("Invalid dense chunk");
            states = states.clone(); heightmaps = heightmaps == null ? new int[256] : heightmaps.clone(); fluidMarks = fluidMarks == null ? new boolean[states.length] : fluidMarks.clone();
            if (heightmaps.length != 256 || fluidMarks.length != states.length) throw new IllegalArgumentException("Invalid chunk metadata");
            if (generatorIdentity == null || generatorIdentity.isBlank()) throw new IllegalArgumentException("Generator identity required");
        }
        public String state(int x, int y, int z) { if (x < 0 || x >= 16 || z < 0 || y < 0 || y >= height) throw new IndexOutOfBoundsException(); return states[(y * 16 + z) * 16 + x]; }
        public String[] states() { return states.clone(); }
        public int[] heightmaps() { return heightmaps.clone(); }
        public boolean[] fluidMarks() { return fluidMarks.clone(); }

        /**
         * Computes the four named heightmap families carried by a Minecraft
         * chunk result.  The scalar candidate has no block registry objects,
         * so the predicates intentionally use the canonical captured state
         * names.  Values are Minecraft's "first available" heights (the
         * highest matching block plus one), rather than highest-block Y.
         */
        public java.util.Map<String, int[]> heightmapSet() {
            return heightmapSet(null);
        }

        /** Uses loader-captured predicates when available; null preserves synthetic fixtures. */
        public java.util.Map<String, int[]> heightmapSet(RegistrySnapshot registry) {
            int empty = minY;
            int[] worldSurfaceWg = new int[256];
            int[] oceanFloorWg = new int[256];
            int[] worldSurface = new int[256];
            int[] oceanFloor = new int[256];
            int[] motionBlocking = new int[256];
            int[] motionBlockingNoLeaves = new int[256];
            java.util.Arrays.fill(worldSurfaceWg, empty);
            java.util.Arrays.fill(oceanFloorWg, empty);
            java.util.Arrays.fill(worldSurface, empty);
            java.util.Arrays.fill(oceanFloor, empty);
            java.util.Arrays.fill(motionBlocking, empty);
            java.util.Arrays.fill(motionBlockingNoLeaves, empty);
            for (int localY = 0; localY < height; localY++) {
                int worldY = Math.addExact(minY, localY);
                for (int z = 0; z < CHUNK_SIZE; z++) for (int x = 0; x < CHUNK_SIZE; x++) {
                    String state = states[(localY * CHUNK_SIZE + z) * CHUNK_SIZE + x];
                    if (state == null) continue;
                    BlockStateTraits traits = registry == null
                            ? BlockStateTraits.inferCanonical(state) : registry.stateTraits(state);
                    if (traits == null) {
                        throw new IllegalArgumentException("Heightmap state is absent from captured registry: " + state);
                    }
                    if (traits.air()) continue;
                    int column = z * CHUNK_SIZE + x;
                    int firstAvailable = Math.addExact(worldY, 1);
                    worldSurfaceWg[column] = Math.max(worldSurfaceWg[column], firstAvailable);
                    worldSurface[column] = Math.max(worldSurface[column], firstAvailable);
                    if (traits.blocksMotion()) {
                        oceanFloorWg[column] = Math.max(oceanFloorWg[column], firstAvailable);
                        oceanFloor[column] = Math.max(oceanFloor[column], firstAvailable);
                    }
                    // Runtime motion-blocking includes fluids; leaves are
                    // excluded only from the *_NO_LEAVES variant.
                    if (traits.blocksMotion() || traits.fluid()) {
                        motionBlocking[column] = Math.max(motionBlocking[column], firstAvailable);
                        if (!traits.leaves()) {
                            motionBlockingNoLeaves[column] = Math.max(motionBlockingNoLeaves[column], firstAvailable);
                        }
                    }
                }
            }
            var result = new java.util.LinkedHashMap<String, int[]>();
            result.put("OCEAN_FLOOR_WG", oceanFloorWg);
            result.put("WORLD_SURFACE_WG", worldSurfaceWg);
            result.put("MOTION_BLOCKING", motionBlocking);
            result.put("MOTION_BLOCKING_NO_LEAVES", motionBlockingNoLeaves);
            result.put("OCEAN_FLOOR", oceanFloor);
            result.put("WORLD_SURFACE", worldSurface);
            return java.util.Collections.unmodifiableMap(result);
        }

    }
    private final NoiseEvaluator noise = new NoiseEvaluator();
    private final AquiferEvaluator aquifer = new AquiferEvaluator();
    private final OreVeinEvaluator ore = new OreVeinEvaluator();
    private final BeardifierEvaluator beardifier = new BeardifierEvaluator();
    private final MaterialProgram material = new MaterialProgram(0.0);
    private final WorldgenCpuCompiler compiler = new WorldgenCpuCompiler();
    private final IdentityHashMap<ProgramNode, CompiledRouter> compiledRouters = new IdentityHashMap<>();

    private record CompiledRouter(WorldgenProgram program, CompiledWorldgenProgram finalDensity) {
        private CompiledRouter {
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(finalDensity, "finalDensity");
        }
        private boolean matches(Map<String, ProgramNode> roots) {
            if (roots.size() != program.roots().size()) return false;
            for (Map.Entry<String, ProgramNode> entry : roots.entrySet()) {
                if (program.root(entry.getKey()) != entry.getValue()) return false;
            }
            return true;
        }
    }

    /**
     * Strict production entry point.  The compatibility {@link #generate}
     * method still supports the small synthetic fixture language, but a real
     * Minecraft candidate must never quietly replace a missing captured root
     * with the fixture noise fallback.
     */
    public GeneratedChunk generateCaptured(WorldgenSnapshot snapshot, int chunkX, int chunkZ) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.router().complete() || snapshot.router().root("finalDensity") == null) {
            throw new IllegalArgumentException("Captured Minecraft generation requires all 15 router roots");
        }
        if (snapshot.structureBlend().hasHeightAndBiomeData()
                && snapshot.structureBlend().heightSamples().isEmpty()
                && snapshot.structureBlend().directHeightSamples().isEmpty()) {
            throw new UnsupportedOperationException(
                    "Captured height/biome blending has no immutable height samples");
        }
        validateCapturedInputs(snapshot);
        validateCapturedGraph(snapshot);
        return generateInternal(snapshot, chunkX, chunkZ, true);
    }

    /**
     * Returns the raw captured final-density carrier in the same logical
     * y/z/x order as {@link GeneratedChunk#states()}.  This is intentionally a
     * diagnostic seam: it lets a device candidate identify whether a material
     * mismatch starts at density interpolation, before aquifer and ore rules
     * consume the value.
     */
    public double[] capturedDensityValues(WorldgenSnapshot snapshot, int chunkX, int chunkZ) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.router().complete() || snapshot.router().root("finalDensity") == null) {
            throw new IllegalArgumentException("Captured Minecraft density requires all 15 router roots");
        }
        if (snapshot.structureBlend().hasHeightAndBiomeData()
                && snapshot.structureBlend().heightSamples().isEmpty()
                && snapshot.structureBlend().directHeightSamples().isEmpty()) {
            throw new UnsupportedOperationException(
                    "Captured height/biome blending has no immutable height samples");
        }
        validateCapturedInputs(snapshot);
        validateCapturedGraph(snapshot);
        var settings = snapshot.generatorSettings();
        CompiledRouter compiled = compiledRouter(snapshot.router().roots());
        int logicalBlocks = Math.multiplyExact(settings.logicalHeight(), CHUNK_SIZE * CHUNK_SIZE);
        double[] values = new double[logicalBlocks];
        int baseX = Math.multiplyExact(chunkX, CHUNK_SIZE);
        int baseZ = Math.multiplyExact(chunkZ, CHUNK_SIZE);
        MarkerContext markers = new MarkerContext(0, snapshot.structureBlend(),
                flatCacheBounds(baseX, baseZ, settings));
        int cursor = 0;
        for (int localY = 0; localY < settings.logicalHeight(); localY++) {
            int worldY = Math.addExact(settings.minY(), localY);
            for (int z = 0; z < CHUNK_SIZE; z++) {
                for (int x = 0; x < CHUNK_SIZE; x++) {
                    int worldX = Math.addExact(baseX, x);
                    int worldZ = Math.addExact(baseZ, z);
                    double value = compiled.finalDensity().evaluateAtCaptured(
                            snapshot.seed(), worldX, worldY, worldZ, markers);
                    value += beardifier.compute(snapshot.structureBlend(), worldX, worldY, worldZ);
                    values[cursor++] = value;
                }
            }
        }
        if (cursor != values.length) throw new AssertionError("Captured density count changed");
        return values;
    }

    /** Diagnostic counterpart for one direct final-density branch. */
    public double[] capturedDirectDensityValues(WorldgenSnapshot snapshot, int chunkX, int chunkZ,
                                                int branchIndex) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.router().complete() || snapshot.router().root("finalDensity") == null) {
            throw new IllegalArgumentException("Captured Minecraft density requires all 15 router roots");
        }
        ProgramNode finalRoot = snapshot.router().root("finalDensity");
        if (branchIndex < 0 || branchIndex >= finalRoot.children().size()) {
            throw new IllegalArgumentException("Final density branch index is outside the captured root");
        }
        validateCapturedInputs(snapshot);
        validateCapturedGraph(snapshot);
        var settings = snapshot.generatorSettings();
        WorldgenProgram branchProgram = WorldgenProgram.builder()
                .version("worldgennext-density-diagnostic-v1")
                .root("branch", finalRoot.children().get(branchIndex))
                .build();
        CompiledWorldgenProgram branch = compiler.compile(branchProgram, "branch");
        int logicalBlocks = Math.multiplyExact(settings.logicalHeight(), CHUNK_SIZE * CHUNK_SIZE);
        double[] values = new double[logicalBlocks];
        int baseX = Math.multiplyExact(chunkX, CHUNK_SIZE);
        int baseZ = Math.multiplyExact(chunkZ, CHUNK_SIZE);
        MarkerContext markers = new MarkerContext(0, snapshot.structureBlend(),
                flatCacheBounds(baseX, baseZ, settings));
        int cursor = 0;
        for (int localY = 0; localY < settings.logicalHeight(); localY++) {
            int worldY = Math.addExact(settings.minY(), localY);
            for (int z = 0; z < CHUNK_SIZE; z++) {
                for (int x = 0; x < CHUNK_SIZE; x++) {
                    values[cursor++] = branch.evaluateAtCaptured(snapshot.seed(),
                            Math.addExact(baseX, x), worldY, Math.addExact(baseZ, z), markers);
                }
            }
        }
        return values;
    }

    /**
     * Diagnostic access to an arbitrary child below one captured final-density
     * branch.  The GPU candidate uses this only while tracing a staged graph;
     * it keeps the CPU oracle on the same immutable captured program instead
     * of approximating an intermediate value from the final branch result.
     */
    public double[] capturedDirectDensityPathValues(WorldgenSnapshot snapshot, int chunkX, int chunkZ,
                                                    int branchIndex, int... childPath) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(childPath, "childPath");
        if (!snapshot.router().complete() || snapshot.router().root("finalDensity") == null) {
            throw new IllegalArgumentException("Captured Minecraft density requires all 15 router roots");
        }
        ProgramNode finalRoot = snapshot.router().root("finalDensity");
        if (branchIndex < 0 || branchIndex >= finalRoot.children().size()) {
            throw new IllegalArgumentException("Final density branch index is outside the captured root");
        }
        validateCapturedInputs(snapshot);
        validateCapturedGraph(snapshot);
        ProgramNode component = finalRoot.children().get(branchIndex);
        for (int childIndex : childPath) {
            if (childIndex < 0 || childIndex >= component.children().size()) {
                throw new IllegalArgumentException("Captured density child path is outside the branch: "
                        + Arrays.toString(childPath));
            }
            component = component.children().get(childIndex);
        }
        var settings = snapshot.generatorSettings();
        WorldgenProgram componentProgram = WorldgenProgram.builder()
                .version("worldgennext-density-component-diagnostic-v1")
                .root("component", component)
                .build();
        CompiledWorldgenProgram compiled = compiler.compile(componentProgram, "component");
        int logicalBlocks = Math.multiplyExact(settings.logicalHeight(), CHUNK_SIZE * CHUNK_SIZE);
        double[] values = new double[logicalBlocks];
        int baseX = Math.multiplyExact(chunkX, CHUNK_SIZE);
        int baseZ = Math.multiplyExact(chunkZ, CHUNK_SIZE);
        MarkerContext markers = new MarkerContext(0, snapshot.structureBlend(),
                flatCacheBounds(baseX, baseZ, settings));
        int cursor = 0;
        for (int localY = 0; localY < settings.logicalHeight(); localY++) {
            int worldY = Math.addExact(settings.minY(), localY);
            for (int z = 0; z < CHUNK_SIZE; z++) {
                for (int x = 0; x < CHUNK_SIZE; x++) {
                    values[cursor++] = compiled.evaluateAtCaptured(snapshot.seed(),
                            Math.addExact(baseX, x), worldY, Math.addExact(baseZ, z), markers);
                }
            }
        }
        return values;
    }

    /**
     * Diagnostic access to one compiler-owned captured node.  The Vulkan
     * emitter retains the semantic {@link ProgramNode} for every generated
     * {@code wg_node_*} function; evaluating that node directly avoids
     * pretending that generated numeric IDs are stable source paths.
     */
    public double[] capturedNodeValues(WorldgenSnapshot snapshot, int chunkX, int chunkZ,
                                       ProgramNode node) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(node, "node");
        if (!snapshot.router().complete() || snapshot.router().root("finalDensity") == null) {
            throw new IllegalArgumentException("Captured Minecraft density requires all 15 router roots");
        }
        validateCapturedInputs(snapshot);
        validateCapturedGraph(snapshot);
        var settings = snapshot.generatorSettings();
        WorldgenProgram componentProgram = WorldgenProgram.builder()
                .version("worldgennext-density-node-diagnostic-v1")
                .root("component", node)
                .build();
        CompiledWorldgenProgram compiled = compiler.compile(componentProgram, "component");
        int logicalBlocks = Math.multiplyExact(settings.logicalHeight(), CHUNK_SIZE * CHUNK_SIZE);
        double[] values = new double[logicalBlocks];
        int baseX = Math.multiplyExact(chunkX, CHUNK_SIZE);
        int baseZ = Math.multiplyExact(chunkZ, CHUNK_SIZE);
        MarkerContext markers = new MarkerContext(0, snapshot.structureBlend(),
                flatCacheBounds(baseX, baseZ, settings));
        int cursor = 0;
        for (int localY = 0; localY < settings.logicalHeight(); localY++) {
            int worldY = Math.addExact(settings.minY(), localY);
            for (int z = 0; z < CHUNK_SIZE; z++) {
                for (int x = 0; x < CHUNK_SIZE; x++) {
                    values[cursor++] = compiled.evaluateAtCaptured(snapshot.seed(),
                            Math.addExact(baseX, x), worldY, Math.addExact(baseZ, z), markers);
                }
            }
        }
        return values;
    }

    /**
     * Evaluate one compiler-owned captured node at one world coordinate.
     *
     * <p>This intentionally mirrors {@link #capturedNodeValues} but avoids
     * materializing a complete chunk.  The Vulkan candidate uses it only for
     * an opt-in first-divergent-stage diagnostic, where compiling/evaluating a
     * single point is materially cheaper than rerunning every stage over the
     * full logical height.</p>
     */
    public double capturedNodeValueAt(WorldgenSnapshot snapshot, int chunkX, int chunkZ,
                                      ProgramNode node, int x, int y, int z) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(node, "node");
        if (!snapshot.router().complete() || snapshot.router().root("finalDensity") == null) {
            throw new IllegalArgumentException("Captured Minecraft density requires all 15 router roots");
        }
        validateCapturedInputs(snapshot);
        validateCapturedGraph(snapshot);
        WorldgenProgram componentProgram = WorldgenProgram.builder()
                .version("worldgennext-density-node-point-diagnostic-v1")
                .root("component", node)
                .build();
        CompiledWorldgenProgram compiled = compiler.compile(componentProgram, "component");
        var settings = snapshot.generatorSettings();
        int baseX = Math.multiplyExact(chunkX, CHUNK_SIZE);
        int baseZ = Math.multiplyExact(chunkZ, CHUNK_SIZE);
        MarkerContext markers = new MarkerContext(0, snapshot.structureBlend(),
                flatCacheBounds(baseX, baseZ, settings));
        return compiled.evaluateAtCaptured(snapshot.seed(), x, y, z, markers);
    }

    /**
     * Captured-Minecraft material trace for one world point.  This is a
     * diagnostic seam only: it deliberately returns the intermediate aquifer
     * and ore decisions so a device mismatch can be localized without
     * materializing another dense chunk.
     */
    public CapturedMaterialDiagnostic capturedMaterialDiagnostic(WorldgenSnapshot snapshot,
                                                                  int chunkX, int chunkZ,
                                                                  int x, int y, int z) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.router().complete() || snapshot.router().root("finalDensity") == null) {
            throw new IllegalArgumentException("Captured Minecraft material requires all 15 router roots");
        }
        validateCapturedInputs(snapshot);
        validateCapturedGraph(snapshot);
        var settings = snapshot.generatorSettings();
        CompiledRouter compiled = compiledRouter(snapshot.router().roots());
        WorldgenProgram router = compiled.program();
        int baseX = Math.multiplyExact(chunkX, CHUNK_SIZE);
        int baseZ = Math.multiplyExact(chunkZ, CHUNK_SIZE);
        MarkerContext markers = new MarkerContext(0, snapshot.structureBlend(),
                flatCacheBounds(baseX, baseZ, settings));
        double density = compiled.finalDensity().evaluateAtCaptured(snapshot.seed(), x, y, z, markers)
                + beardifier.compute(snapshot.structureBlend(), x, y, z);
        String lavaState = capturedState(snapshot, "minecraft:lava", "minecraft:lava");
        AquiferProgram aquiferProgram = new AquiferProgram(settings.aquifersEnabled(), 16,
                settings.defaultFluid().canonical(), lavaState, settings.seaLevel(),
                AquiferProgram.VANILLA_GLOBAL_LAVA_LEVEL);
        AquiferEvaluator.EvaluationContext aquiferContext = new AquiferEvaluator.EvaluationContext(
                snapshot.structureBlend(), flatCacheBounds(baseX, baseZ, settings));
        var aquiferResult = snapshot.randomState().aquiferRandom() != null
                ? aquifer.evaluate(aquiferProgram, router, snapshot.randomState().aquiferRandom(), settings,
                snapshot.seed(), x, y, z, density, aquiferContext)
                : aquifer.evaluate(aquiferProgram, snapshot.seed(), x, y, z, density);
        AquiferEvaluator.DecisionTrace aquiferTrace = snapshot.randomState().aquiferRandom() != null
                ? aquifer.trace(aquiferProgram, router, snapshot.randomState().aquiferRandom(), settings,
                snapshot.seed(), x, y, z, density, aquiferContext)
                : null;
        OreVeinProgram oreProgram = settings.oresEnabled()
                ? OreVeinProgram.vanilla(snapshot.seed()) : OreVeinProgram.disabled();
        var oreResult = !aquiferResult.aquiferCandidate() && snapshot.randomState().oreRandom() != null
                ? ore.evaluate(oreProgram, router, snapshot.randomState().oreRandom(), snapshot.seed(),
                x, y, z, markers)
                : ore.evaluate(oreProgram, snapshot.seed(), x, y, z, density);
        var decision = material.decide(new MaterialProgram.Inputs(density,
                aquiferResult.aquiferCandidate(), aquiferResult.state(), oreResult.ore(), oreResult.state(),
                settings.defaultBlock().canonical(), aquiferResult.noAquiferUsesDefault()));
        return new CapturedMaterialDiagnostic(x, y, z, density,
                aquiferResult.fluid(), aquiferResult.state(), aquiferResult.level(),
                aquiferResult.scheduleFluidUpdate(), aquiferResult.noAquiferUsesDefault(),
                aquiferResult.aquiferCandidate(), oreResult.ore(), oreResult.state(), oreResult.branch(),
                decision.state(), decision.source().name(), aquiferTrace);
    }

    /**
     * Diagnostic-only aquifer decision with a caller-supplied density.  The
     * point, captured router, random state and marker context remain the same
     * as the ordinary material trace; only the density carrier is replaced so
     * GPU pressure thresholds can be swept without rebuilding a chunk.
     */
    public AquiferEvaluator.Result capturedAquiferAtDensity(WorldgenSnapshot snapshot,
                                                            int chunkX, int chunkZ,
                                                            int x, int y, int z,
                                                            double density) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!Double.isFinite(density)) {
            throw new IllegalArgumentException("Aquifer threshold density must be finite");
        }
        if (!snapshot.router().complete() || snapshot.router().root("finalDensity") == null) {
            throw new IllegalArgumentException("Captured Minecraft aquifer requires all 15 router roots");
        }
        validateCapturedInputs(snapshot);
        validateCapturedGraph(snapshot);
        var settings = snapshot.generatorSettings();
        CompiledRouter compiled = compiledRouter(snapshot.router().roots());
        WorldgenProgram router = compiled.program();
        int baseX = Math.multiplyExact(chunkX, CHUNK_SIZE);
        int baseZ = Math.multiplyExact(chunkZ, CHUNK_SIZE);
        AquiferProgram aquiferProgram = new AquiferProgram(settings.aquifersEnabled(), 16,
                settings.defaultFluid().canonical(),
                capturedState(snapshot, "minecraft:lava", "minecraft:lava"),
                settings.seaLevel(), AquiferProgram.VANILLA_GLOBAL_LAVA_LEVEL);
        AquiferEvaluator.EvaluationContext aquiferContext = new AquiferEvaluator.EvaluationContext(
                snapshot.structureBlend(), flatCacheBounds(baseX, baseZ, settings));
        return snapshot.randomState().aquiferRandom() != null
                ? aquifer.evaluate(aquiferProgram, router, snapshot.randomState().aquiferRandom(), settings,
                snapshot.seed(), x, y, z, density, aquiferContext)
                : aquifer.evaluate(aquiferProgram, snapshot.seed(), x, y, z, density);
    }

    /** Diagnostic-only trace for the same caller-supplied density seam. */
    public AquiferEvaluator.DecisionTrace capturedAquiferTraceAtDensity(
            WorldgenSnapshot snapshot, int chunkX, int chunkZ,
            int x, int y, int z, double density) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!Double.isFinite(density)) {
            throw new IllegalArgumentException("Aquifer threshold density must be finite");
        }
        if (!snapshot.router().complete() || snapshot.router().root("finalDensity") == null) {
            throw new IllegalArgumentException("Captured Minecraft aquifer requires all 15 router roots");
        }
        validateCapturedInputs(snapshot);
        validateCapturedGraph(snapshot);
        var settings = snapshot.generatorSettings();
        CompiledRouter compiled = compiledRouter(snapshot.router().roots());
        WorldgenProgram router = compiled.program();
        int baseX = Math.multiplyExact(chunkX, CHUNK_SIZE);
        int baseZ = Math.multiplyExact(chunkZ, CHUNK_SIZE);
        AquiferProgram aquiferProgram = new AquiferProgram(settings.aquifersEnabled(), 16,
                settings.defaultFluid().canonical(),
                capturedState(snapshot, "minecraft:lava", "minecraft:lava"),
                settings.seaLevel(), AquiferProgram.VANILLA_GLOBAL_LAVA_LEVEL);
        AquiferEvaluator.EvaluationContext aquiferContext = new AquiferEvaluator.EvaluationContext(
                snapshot.structureBlend(), flatCacheBounds(baseX, baseZ, settings));
        return snapshot.randomState().aquiferRandom() != null
                ? aquifer.trace(aquiferProgram, router, snapshot.randomState().aquiferRandom(), settings,
                snapshot.seed(), x, y, z, density, aquiferContext)
                : null;
    }

    /** One-point CPU material trace used by the real-device diagnostic path. */
    public record CapturedMaterialDiagnostic(int x, int y, int z, double density,
                                             boolean aquiferFluid, String aquiferState,
                                             double aquiferLevel, boolean aquiferScheduleFluidUpdate,
                                             boolean aquiferNoAquiferUsesDefault,
                                             boolean aquiferCandidate, boolean oreCandidate,
                                             String oreState, String oreBranch,
                                             String materialState, String materialSource,
                                             AquiferEvaluator.DecisionTrace aquiferTrace) {
        public CapturedMaterialDiagnostic {
            if (!Double.isFinite(density)) throw new IllegalArgumentException("Material density must be finite");
            Objects.requireNonNull(aquiferState, "aquiferState");
            Objects.requireNonNull(oreState, "oreState");
            Objects.requireNonNull(oreBranch, "oreBranch");
            Objects.requireNonNull(materialState, "materialState");
            Objects.requireNonNull(materialSource, "materialSource");
        }
    }

    /**
     * Validate the inputs that the complete-result path is not allowed to
     * replace with the small fixture implementation.  The ordinary
     * {@link #generate} entry point intentionally retains that compatibility
     * behavior for pure synthetic fixtures; this entry point is the production
     * captured-Minecraft boundary.
     */
    private static void validateCapturedInputs(WorldgenSnapshot snapshot) {
        var settings = snapshot.generatorSettings();
        var registry = snapshot.registry();
        if (registry.id(settings.defaultBlock().canonical()) < 0
                || registry.id(settings.defaultFluid().canonical()) < 0) {
            throw new IllegalArgumentException("Captured generator defaults are missing from the registry");
        }
        requireCapturedState(snapshot, "minecraft:air");
        // The global fluid picker always has a lava branch, including when
        // aquifers are disabled, so lava cannot be synthesized at this seam.
        requireCapturedState(snapshot, "minecraft:lava");
        if (settings.oresEnabled()) {
            for (String material : OreVeinProgram.vanilla(snapshot.seed()).materials()) {
                requireCapturedState(snapshot, material);
            }
        }
        if (settings.aquifersEnabled() && snapshot.randomState().aquiferRandom() == null) {
            throw new IllegalArgumentException(
                    "Captured Minecraft generation requires an aquifer positional RNG when aquifers are enabled");
        }
        if (settings.oresEnabled() && snapshot.randomState().oreRandom() == null) {
            throw new IllegalArgumentException(
                    "Captured Minecraft generation requires an ore positional RNG when ore veins are enabled");
        }
    }

    /**
     * Reject parameter-only noise nodes before the captured entry point can
     * reach the compatibility sampler.  A complete router alone is not proof
     * that its seed-expanded NormalNoise tables crossed the loader boundary.
     */
    private static void validateCapturedGraph(WorldgenSnapshot snapshot) {
        Set<ProgramNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var entry : snapshot.router().roots().entrySet()) {
            validateCapturedNode(entry.getValue(), snapshot.seed(), entry.getKey(), visited);
        }
    }

    private static void validateCapturedNode(ProgramNode node, long worldSeed, String path,
                                             Set<ProgramNode> visited) {
        if (!visited.add(node)) return;
        if (node instanceof ProgramNode.Noise noise) {
            validateCapturedNoise(noise.parameters(), worldSeed, path);
        } else if (node instanceof ProgramNode.ShiftedNoise noise) {
            validateCapturedNoise(noise.parameters(), worldSeed, path);
        } else if (node instanceof ProgramNode.Shift shift) {
            validateCapturedNoise(shift.parameters(), worldSeed, path);
        } else if (node instanceof ProgramNode.WeirdScaledSampler sampler) {
            validateCapturedNoise(sampler.parameters(), worldSeed, path);
        } else if (node instanceof ProgramNode.BlendedNoise noise
                && noise.parameters().worldSeed() != worldSeed) {
            throw new IllegalArgumentException("Captured blended noise seed does not match world seed at " + path);
        }
        int childIndex = 0;
        for (ProgramNode child : node.children()) {
            validateCapturedNode(child, worldSeed, path + "/" + childIndex++, visited);
        }
    }

    private static void validateCapturedNoise(NoiseParameters parameters, long worldSeed, String path) {
        if (parameters.captured() == null) {
            throw new IllegalArgumentException("Captured noise tables are missing at " + path);
        }
        if (parameters.captured().worldSeed() != worldSeed) {
            throw new IllegalArgumentException("Captured noise seed does not match world seed at " + path);
        }
    }

    public GeneratedChunk generate(WorldgenSnapshot snapshot, int chunkX, int chunkZ) {
        return generateInternal(snapshot, chunkX, chunkZ, false);
    }

    private GeneratedChunk generateInternal(WorldgenSnapshot snapshot, int chunkX, int chunkZ,
                                             boolean capturedNoiseChunkDomain) {
        Objects.requireNonNull(snapshot, "snapshot");
        var settings = snapshot.generatorSettings(); int height = settings.height();
        int stateCount = Math.multiplyExact(height, 256);
        String[] states = new String[stateCount]; boolean[] fluids = new boolean[states.length]; int[] heights = new int[256]; Arrays.fill(heights, settings.minY());
        NoiseParameters parameters = snapshot.randomState().noiseParameters().stream().findFirst().orElse(NoiseParameters.single("worldgennext:default", 1));
        CompiledRouter compiledRouter = snapshot.router().complete()
                ? compiledRouter(snapshot.router().roots()) : null;
        CompiledWorldgenProgram capturedFinalDensity = compiledRouter == null ? null : compiledRouter.finalDensity();
        WorldgenProgram capturedRouter = compiledRouter == null ? null : compiledRouter.program();
        // A Minecraft fluid block is not necessarily represented by a property-free
        // canonical state.  In particular, the live registry contains lava as
        // minecraft:lava[level=0], while the old fixture program used the readable
        // block name.  Resolve the captured registry identity before it can reach
        // the result ABI; a missing identity remains a hard error at that boundary.
        String lavaState = capturedState(snapshot, "minecraft:lava", "minecraft:lava");
        String airState = capturedState(snapshot, "minecraft:air", "minecraft:air");
        AquiferProgram aquiferProgram = new AquiferProgram(settings.aquifersEnabled(), 16,
                settings.defaultFluid().canonical(), lavaState, settings.seaLevel(),
                AquiferProgram.VANILLA_GLOBAL_LAVA_LEVEL);
        OreVeinProgram oreProgram = settings.oresEnabled() ? OreVeinProgram.vanilla(snapshot.seed()) : OreVeinProgram.disabled();
        int baseX = Math.multiplyExact(chunkX, 16);
        int baseZ = Math.multiplyExact(chunkZ, 16);
        AquiferEvaluator.EvaluationContext aquiferContext = new AquiferEvaluator.EvaluationContext(
                snapshot.structureBlend(),
                capturedNoiseChunkDomain ? flatCacheBounds(baseX, baseZ, settings) : null);
        MarkerContext densityMarkers = capturedNoiseChunkDomain
                ? new MarkerContext(0, snapshot.structureBlend(), flatCacheBounds(baseX, baseZ, settings))
                : new MarkerContext(0, snapshot.structureBlend());
        for (int ly = 0; ly < height; ly++) for (int z = 0; z < CHUNK_SIZE; z++) for (int x = 0; x < CHUNK_SIZE; x++) {
            int worldY = Math.addExact(settings.minY(), ly);
            int worldX = Math.addExact(baseX, x);
            int worldZ = Math.addExact(baseZ, z);
            if (ly >= settings.logicalHeight()) {
                states[(ly * 16 + z) * 16 + x] = airState;
                continue;
            }
            double beardifierValue = 0.0;
            double density = capturedFinalDensity == null
                    ? noise.sample(parameters, snapshot.seed(), worldX / 48.0, worldY / 32.0, worldZ / 48.0) + (settings.seaLevel() - worldY) / 96.0
                    : capturedFinalDensity.evaluateAtCaptured(snapshot.seed(), worldX, worldY, worldZ, densityMarkers);
            if (capturedFinalDensity != null) {
                // NoiseChunk unconditionally adds its loader-owned
                // BeardifierMarker around the captured final-density root.
                // A modded router may already contain another marker; that
                // does not suppress the outer one.
                beardifierValue = beardifier.compute(snapshot.structureBlend(), worldX, worldY, worldZ);
                density += beardifierValue;
            }
            var fluid = capturedRouter != null && snapshot.randomState().aquiferRandom() != null
                    ? aquifer.evaluate(aquiferProgram, capturedRouter, snapshot.randomState().aquiferRandom(), settings,
                    snapshot.seed(), worldX, worldY, worldZ, density, aquiferContext)
                    : aquifer.evaluate(aquiferProgram, snapshot.seed(), worldX, worldY, worldZ, density);
            // Ore router roots are part of the same request-scoped capture as
            // final density.  Reusing the context preserves captured blend
            // inputs and marker/cache ownership if a vein root reaches one.
            var markerContext = densityMarkers;
            // NoiseChunk's first material rule can return a non-null AIR
            // state. Only a null aquifer result admits the ore rule; using
            // fluid() here would incorrectly place ore in dry aquifer cells.
            var vein = !fluid.aquiferCandidate() && capturedRouter != null && snapshot.randomState().oreRandom() != null
                    ? ore.evaluate(oreProgram, capturedRouter, snapshot.randomState().oreRandom(), snapshot.seed(), worldX, worldY, worldZ, markerContext)
                    : ore.evaluate(oreProgram, snapshot.seed(), worldX, worldY, worldZ, density);
            var decision = material.decide(new MaterialProgram.Inputs(density, fluid.aquiferCandidate(), fluid.state(), vein.ore(), vein.state(), settings.defaultBlock().canonical(), fluid.noAquiferUsesDefault()));
            String state = decision.state();
            int index = (ly * 16 + z) * 16 + x;
            states[index] = state;
            // Minecraft marks a fluid block for post-processing only when the
            // aquifer reports shouldScheduleFluidUpdate(), not merely when the
            // chosen material is a fluid.
            fluids[index] = fluid.fluid() && fluid.scheduleFluidUpdate();
            if (!state.equals("minecraft:air")) heights[z * 16 + x] = Math.max(heights[z * 16 + x], Math.addExact(worldY, 1));
        }
        return new GeneratedChunk(chunkX, chunkZ, settings.minY(), height, states, heights, fluids, snapshot.fingerprint());
    }

    /** Mirrors NoiseChunk.forChunk's populated horizontal FlatCache range. */
    private static MarkerContext.FlatCacheBounds flatCacheBounds(int baseX, int baseZ,
                                                                  dev.worldgennext.semantic.snapshot.GeneratorSettingsSnapshot settings) {
        int cellCountXZ = 16 / settings.cellWidth();
        int coveredBlocks = Math.multiplyExact(cellCountXZ, settings.cellWidth());
        int noiseSizeXZ = Math.floorDiv(coveredBlocks, 4);
        return new MarkerContext.FlatCacheBounds(Math.floorDiv(baseX, 4), Math.floorDiv(baseZ, 4), noiseSizeXZ);
    }

    private static String capturedState(WorldgenSnapshot snapshot, String blockName, String fallback) {
        return snapshot.registry().states().stream()
                .filter(state -> state.name().equals(blockName))
                .sorted(java.util.Comparator
                        .comparingInt((dev.worldgennext.semantic.snapshot.BlockStateDescriptor state)
                                -> "0".equals(state.properties().get("level")) ? 0 : 1)
                        .thenComparing(dev.worldgennext.semantic.snapshot.BlockStateDescriptor::canonical))
                .map(dev.worldgennext.semantic.snapshot.BlockStateDescriptor::canonical)
                .findFirst()
                .orElse(fallback);
    }

    private static String requireCapturedState(WorldgenSnapshot snapshot, String blockName) {
        String state = capturedState(snapshot, blockName, "");
        if (state.isBlank()) {
            throw new IllegalArgumentException("Captured registry is missing block state " + blockName);
        }
        return state;
    }

    private synchronized CompiledRouter compiledRouter(Map<String, ProgramNode> roots) {
        ProgramNode finalRoot = roots.get("finalDensity");
        if (finalRoot == null) throw new IllegalArgumentException("Captured router is missing finalDensity");
        CompiledRouter cached = compiledRouters.get(finalRoot);
        if (cached != null && cached.matches(roots)) return cached;
        WorldgenProgram program = WorldgenProgram.builder().roots(roots).build();
        CompiledRouter created = new CompiledRouter(program, compiler.compile(program, "finalDensity"));
        compiledRouters.put(finalRoot, created);
        return created;
    }

}
