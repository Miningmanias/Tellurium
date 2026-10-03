// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import dev.worldgennext.compiler.jvm.worldgen.DenseNoiseGenerator;
import dev.worldgennext.compiler.jvm.worldgen.AquiferEvaluator;
import dev.worldgennext.material.chunk.BlockStateTable;
import dev.worldgennext.material.chunk.ChunkMetadataPayload;
import dev.worldgennext.material.chunk.ChunkNoiseResult;
import dev.worldgennext.material.chunk.ChunkResultHeader;
import dev.worldgennext.neoforge.snapshot.MinecraftSnapshotReader;
import dev.worldgennext.neoforge.snapshot.MinecraftDynamicInputReader;
import dev.worldgennext.neoforge.snapshot.DensityNodeReader;
import dev.worldgennext.neoforge.snapshot.RegistrySnapshotReader;
import dev.worldgennext.semantic.identity.DynamicInputIdentity;
import dev.worldgennext.semantic.program.ProgramNode;
import dev.worldgennext.semantic.snapshot.GeneratorSettingsSnapshot;
import dev.worldgennext.semantic.snapshot.BeardifierSnapshot;
import dev.worldgennext.semantic.snapshot.RegistrySnapshot;
import dev.worldgennext.semantic.snapshot.StructureBlendSnapshot;
import dev.worldgennext.semantic.snapshot.WorldgenSnapshot;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.StructureManager;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.util.StaticCache2D;

import java.util.Objects;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loader-side CPU candidate boundary for a single real Minecraft chunk.
 *
 * <p>This class deliberately does not intercept a vanilla stage or mutate a
 * ChunkAccess. It captures the same-stack inputs, crosses into immutable data,
 * runs the pure candidate evaluator, and returns a complete result ABI. The
 * caller must compare the result against an independent oracle before it can be
 * considered an admitted route.</p>
 */
public final class MinecraftCpuCandidate {
    /*
     * The pure generator owns only request-local caches and thread-local
     * interpreter boundary flags. Reusing it avoids rebuilding the evaluator
     * graph for every isolated or live NOISE request while remaining safe for
     * concurrent requests.
     */
    private final DenseNoiseGenerator denseGenerator = new DenseNoiseGenerator();
    private final MinecraftSnapshotReader snapshotReader = new MinecraftSnapshotReader();
    private final IdentityHashMap<RandomState, DensityNodeReader> runtimeReaders = new IdentityHashMap<>();

    /** Immutable loader capture passed to the pure CPU materialization step. */
    public record Inputs(int chunkX, int chunkZ, WorldgenSnapshot snapshot,
                         ChunkMetadataPayload prerequisiteMetadata) {
        public Inputs {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(prerequisiteMetadata, "prerequisiteMetadata");
        }
    }

    public record Capture(WorldgenSnapshot snapshot, DenseNoiseGenerator.GeneratedChunk dense,
                          ChunkNoiseResult result) {
        public Capture {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(dense, "dense");
            Objects.requireNonNull(result, "result");
        }
    }

    public Capture generate(ServerLevel level, int chunkX, int chunkZ) {
        Objects.requireNonNull(level, "level");
        // Structures/references and biomes are NOISE prerequisites and feed
        // the live
        // Beardifier.  Materialize that prerequisite through Minecraft's own
        // holder/status machinery before capturing the immutable candidate
        // inputs; the candidate still never reads or mutates vanilla block
        // output.
        ChunkAccess prerequisite = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.BIOMES, true);
        return generate(capture(level, prerequisite, chunkX, chunkZ));
    }

    /**
     * Captures a target already owned by the version-pinned NOISE task.  It
     * never schedules another holder request, which avoids recursively
     * blocking the generation executor when used by a live adapter.
     */
    public Inputs capture(ServerLevel level, ChunkAccess prerequisite) {
        Objects.requireNonNull(prerequisite, "prerequisite");
        ChunkPos position = prerequisite.getPos();
        return capture(level, prerequisite, position.x, position.z);
    }

    /**
     * Captures the same version-pinned blending neighborhood that the live
     * {@code ChunkStatusTasks.generateNoise} call gives vanilla.  The region
     * is backed by the caller's existing cache and step; constructing it does
     * not schedule a second holder request.  Standalone artifact capture uses
     * the overload above and therefore remains an explicit no-blend capture.
     */
    public Inputs capture(ServerLevel level, StaticCache2D<GenerationChunkHolder> cache,
                          ChunkStep step, ChunkAccess prerequisite) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(prerequisite, "prerequisite");
        WorldGenRegion region = new WorldGenRegion(level, cache, step, prerequisite);
        ChunkPos position = prerequisite.getPos();
        return capture(level, prerequisite, position.x, position.z, region);
    }

    /** Captures a BIOMES prerequisite through Minecraft's authoritative holder path. */
    public Inputs capture(ServerLevel level, int chunkX, int chunkZ) {
        Objects.requireNonNull(level, "level");
        ChunkAccess prerequisite = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.BIOMES, true);
        return capture(level, prerequisite, chunkX, chunkZ);
    }

    private Inputs capture(ServerLevel level, ChunkAccess prerequisite, int chunkX, int chunkZ) {
        return capture(level, prerequisite, chunkX, chunkZ, null);
    }

    private Inputs capture(ServerLevel level, ChunkAccess prerequisite, int chunkX, int chunkZ,
                           WorldGenRegion region) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(prerequisite, "prerequisite");
        NoiseBasedChunkGenerator generator = requireGenerator(level);
        ChunkPos position = prerequisite.getPos();
        if (position.x != chunkX || position.z != chunkZ) {
            throw new IllegalArgumentException("Captured prerequisite coordinates do not match request");
        }
        if (prerequisite.getPersistedStatus() != ChunkStatus.BIOMES) {
            throw new IllegalStateException("CPU candidate capture requires BIOMES prerequisite; got "
                    + prerequisite.getPersistedStatus());
        }
        CompoundTag prerequisiteNbt = ChunkSerializer.write(level, prerequisite);

        NoiseGeneratorSettings noiseSettings = generator.generatorSettings().value();
        NoiseSettings dimensions = noiseSettings.noiseSettings().clampToHeightAccessor(level);
        int storageMinY = level.getMinBuildHeight();
        int storageHeight = level.getHeight();
        if (dimensions.minY() != storageMinY || dimensions.height() > storageHeight) {
            throw new IllegalStateException("Noise generation bounds do not fit the loaded dimension: generation="
                    + dimensions.minY() + ".." + Math.addExact(dimensions.minY(), dimensions.height())
                    + ", storage=" + storageMinY + ".." + Math.addExact(storageMinY, storageHeight));
        }
        Blender blender = region == null ? Blender.empty() : Blender.of(region);
        StructureManager structures = region == null
                ? level.structureManager()
                : level.structureManager().forWorldGenRegion(region);
        // BIOMES normally creates this object first. Calling getOrCreate here
        // is still important for loaded BIOMES targets and is side-effect free
        // with respect to block storage: the later stages reuse this exact
        // cache object after the candidate publishes its dense result.
        MinecraftNoiseChunkLifecycle.ensure(level, prerequisite, noiseSettings, structures, blender);
        RegistrySnapshot registry = new RegistrySnapshotReader().capture(
                noiseSettings.defaultBlock(), noiseSettings.defaultFluid());
        var settings = new GeneratorSettingsSnapshot(
                storageMinY, storageHeight, dimensions.height(), noiseSettings.seaLevel(),
                registry.defaultBlock(), registry.defaultFluid(), noiseSettings.isAquifersEnabled(),
                noiseSettings.oreVeinsEnabled(),
                dimensions.getCellWidth(), dimensions.getCellHeight());

        String settingsIdentity = generator.generatorSettings().unwrapKey()
                .map(key -> key.location().toString()).orElse("inline-noise-settings");
        String dimension = level.dimension().location().toString();
        String stack = "minecraft-1.21.1-neoforge-21.1.176";
        var beardifier = captureBeardifier(structures, chunkX, chunkZ);
        var structureBlend = region == null
                ? new StructureBlendSnapshot("beardifier:" + dimension + ":" + chunkX + ":" + chunkZ,
                java.util.Map.of(), java.util.Map.of(), beardifier)
                : new dev.worldgennext.neoforge.snapshot.StructureBlendReader().capture(blender, beardifier);
        DynamicInputIdentity dynamicInputs = MinecraftDynamicInputReader.capture(level.getServer(), structureBlend);
        DensityNodeReader runtimeReader = runtimeReader(level.getChunkSource().randomState(), level.getSeed(),
                settings.cellWidth(), settings.cellHeight());
        var captured = snapshotReader.captureWithRuntimeRandomState(
                level.getSeed(), dimension, settingsIdentity,
                level.getChunkSource().randomState().router(), level.getChunkSource().randomState(),
                registry, settings, dynamicInputs, stack, null,
                structureBlend, java.util.List.of(), runtimeReader);
        if (!captured.supported()) {
            throw new IllegalStateException("Real Minecraft snapshot could not be lowered: "
                    + captured.diagnostics().summary());
        }
        WorldgenSnapshot snapshot = captured.snapshot();
        return new Inputs(chunkX, chunkZ, snapshot,
                new ChunkMetadataPayload(capturePrerequisiteMetadata(prerequisiteNbt, prerequisite)));
    }

    /** Retains one exact descriptor cache for each loaded RandomState identity. */
    private synchronized DensityNodeReader runtimeReader(RandomState randomState, long seed,
                                                         int cellWidth, int cellHeight) {
        DensityNodeReader existing = runtimeReaders.get(randomState);
        if (existing != null && existing.matchesRuntime(randomState, seed, cellWidth, cellHeight)) {
            return existing;
        }
        DensityNodeReader created = new DensityNodeReader(randomState, seed, cellWidth, cellHeight);
        runtimeReaders.put(randomState, created);
        return created;
    }

    /**
     * Materializes only from immutable captured inputs. No Minecraft object or
     * holder lookup is reachable from this method.
     */
    public Capture generate(Inputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        WorldgenSnapshot snapshot = inputs.snapshot();
        int chunkX = inputs.chunkX();
        int chunkZ = inputs.chunkZ();
        RegistrySnapshot registry = snapshot.registry();
        // A real candidate is never allowed to fall back to the synthetic
        // fixture noise path. Missing/unsupported capture is an explicit
        // route rejection, not a plausible-looking Minecraft result.
        DenseNoiseGenerator.GeneratedChunk dense = denseGenerator.generateCaptured(snapshot, chunkX, chunkZ);
        var header = new ChunkResultHeader(chunkX, chunkZ, dense.minY(), dense.height(),
                snapshot.fingerprint(), registry.fingerprint(), "chunk-result-v4");
        var generatedMaps = dense.heightmapSet(registry);
        // NOISE owns only the two heightmaps primed by the version-pinned
        // fillFromNoise step.  The dense evaluator exposes the later endpoint
        // families too, but applying those here would create maps before their
        // authoritative downstream stages own them and would make rollback
        // impossible on a BIOMES target.
        var noiseHeightmaps = Map.of(
                "OCEAN_FLOOR_WG", generatedMaps.get("OCEAN_FLOOR_WG"),
                "WORLD_SURFACE_WG", generatedMaps.get("WORLD_SURFACE_WG"));
        ChunkNoiseResult result = ChunkNoiseResult.ofDense(header, BlockStateTable.fromRegistry(registry),
                dense.states(), new dev.worldgennext.material.chunk.HeightmapPayload(noiseHeightmaps), dense.fluidMarks(),
                inputs.prerequisiteMetadata());
        return new Capture(snapshot, dense, result);
    }

    /**
     * Diagnostic-only access to the exact CPU density carrier used by the
     * captured candidate.  No loader object is reachable from this method.
     */
    public double[] capturedDensityValues(Inputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        return denseGenerator.capturedDensityValues(inputs.snapshot(), inputs.chunkX(), inputs.chunkZ());
    }

    /** Diagnostic access to one direct final-density branch. */
    public double[] capturedDirectDensityValues(Inputs inputs, int branchIndex) {
        Objects.requireNonNull(inputs, "inputs");
        return denseGenerator.capturedDirectDensityValues(
                inputs.snapshot(), inputs.chunkX(), inputs.chunkZ(), branchIndex);
    }

    /** Diagnostic access to one captured final-density branch child path. */
    public double[] capturedDirectDensityPathValues(Inputs inputs, int branchIndex, int... childPath) {
        Objects.requireNonNull(inputs, "inputs");
        return denseGenerator.capturedDirectDensityPathValues(
                inputs.snapshot(), inputs.chunkX(), inputs.chunkZ(), branchIndex, childPath);
    }

    /** Diagnostic access to a compiler-owned captured semantic node. */
    public double[] capturedNodeValues(Inputs inputs, ProgramNode node) {
        Objects.requireNonNull(inputs, "inputs");
        return denseGenerator.capturedNodeValues(
                inputs.snapshot(), inputs.chunkX(), inputs.chunkZ(), node);
    }

    /** Diagnostic access to one compiler-owned captured node at one point. */
    public double capturedNodeValueAt(Inputs inputs, ProgramNode node, int x, int y, int z) {
        Objects.requireNonNull(inputs, "inputs");
        return denseGenerator.capturedNodeValueAt(
                inputs.snapshot(), inputs.chunkX(), inputs.chunkZ(), node, x, y, z);
    }

    /** Diagnostic access to the exact CPU aquifer/ore/material decision at one point. */
    public DenseNoiseGenerator.CapturedMaterialDiagnostic capturedMaterialDiagnostic(
            Inputs inputs, int x, int y, int z) {
        Objects.requireNonNull(inputs, "inputs");
        return denseGenerator.capturedMaterialDiagnostic(
                inputs.snapshot(), inputs.chunkX(), inputs.chunkZ(), x, y, z);
    }

    /** Diagnostic access to the captured aquifer decision at a custom density. */
    public AquiferEvaluator.Result capturedAquiferAtDensity(
            Inputs inputs, int x, int y, int z, double density) {
        Objects.requireNonNull(inputs, "inputs");
        return denseGenerator.capturedAquiferAtDensity(
                inputs.snapshot(), inputs.chunkX(), inputs.chunkZ(), x, y, z, density);
    }

    /** Diagnostic access to the exact aquifer branch inputs at a custom density. */
    public AquiferEvaluator.DecisionTrace capturedAquiferTraceAtDensity(
            Inputs inputs, int x, int y, int z, double density) {
        Objects.requireNonNull(inputs, "inputs");
        return denseGenerator.capturedAquiferTraceAtDensity(
                inputs.snapshot(), inputs.chunkX(), inputs.chunkZ(), x, y, z, density);
    }

    private static NoiseBasedChunkGenerator requireGenerator(ServerLevel level) {
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
            throw new IllegalArgumentException("CPU candidate requires NoiseBasedChunkGenerator; got "
                    + level.getChunkSource().getGenerator().getClass().getName());
        }
        return generator;
    }

    /**
     * Captures only prerequisite/endpoint metadata.  The candidate block
     * states and heightmaps still come from the pure dense evaluator; this
     * serializer view supplies BIOMES and structure identity without asking
     * Minecraft to fill the target chunk's NOISE blocks for us.
     */
    private static Map<String, String> capturePrerequisiteMetadata(CompoundTag serialized, ChunkAccess chunk) {
        var fields = new LinkedHashMap<String, String>();
        fields.put("BIOMES", canonicalPalettedSections(serialized, "biomes", 64));
        fields.put("LIGHT", emptyLight());
        fields.put("TICKS", "{\"block_ticks\":[],\"fluid_ticks\":[]}");
        fields.put("BLOCK_ENTITIES", "[]");
        fields.put("STRUCTURES", canonical(serialized.get("structures")));
        fields.put("ENTITIES", "[]");

        CompoundTag lifecycle = new CompoundTag();
        for (String key : List.of("xPos", "yPos", "zPos")) {
            if (serialized.contains(key)) lifecycle.put(key, serialized.get(key).copy());
        }
        lifecycle.putString("Status", "minecraft:noise");
        lifecycle.putString("minBuildHeight", Integer.toString(chunk.getMinBuildHeight()));
        lifecycle.putString("maxBuildHeight", Integer.toString(chunk.getMaxBuildHeight()));
        fields.put("LIFECYCLE", canonical(lifecycle));
        return fields;
    }

    private static String emptyLight() {
        CompoundTag light = new CompoundTag();
        CompoundTag block = new CompoundTag();
        CompoundTag sky = new CompoundTag();
        block.put("sections", new ListTag());
        sky.put("sections", new ListTag());
        light.put("block", block);
        light.put("sky", sky);
        return canonical(light);
    }

    private static String canonicalPalettedSections(CompoundTag serialized, String key, int entryCount) {
        StringBuilder out = new StringBuilder("{\"sections\":[");
        Tag sectionsTag = serialized.get("sections");
        if (!(sectionsTag instanceof ListTag sections)) return out.append("]}").toString();
        boolean firstSection = true;
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            Tag raw = sections.get(sectionIndex);
            if (!(raw instanceof CompoundTag section) || !section.contains(key)) continue;
            if (!firstSection) out.append(',');
            firstSection = false;
            Tag containerTag = section.get(key);
            if (!(containerTag instanceof CompoundTag container)) throw new IllegalStateException("Expected paletted compound for " + key);
            List<String> palette = readPalette(container, key);
            int[] decoded = decodePalette(container, palette.size(), key, entryCount);
            List<String> canonicalPalette = palette.stream().distinct().sorted().toList();
            Map<String, Integer> canonicalIds = new HashMap<>();
            for (int index = 0; index < canonicalPalette.size(); index++) canonicalIds.put(canonicalPalette.get(index), index);
            out.append('{');
            if (section.contains("Y")) out.append("\"Y\":").append(canonical(section.get("Y"))).append(',');
            out.append("\"palette\":[");
            for (int index = 0; index < canonicalPalette.size(); index++) {
                if (index > 0) out.append(',');
                out.append(quote(canonicalPalette.get(index)));
            }
            out.append("],\"indices\":[");
            for (int index = 0; index < decoded.length; index++) {
                if (index > 0) out.append(',');
                Integer remapped = canonicalIds.get(palette.get(decoded[index]));
                if (remapped == null) throw new IllegalStateException("Palette remap lost value for " + key);
                out.append(remapped);
            }
            out.append("]}");
        }
        return out.append("]}").toString();
    }

    private static List<String> readPalette(CompoundTag container, String key) {
        Tag paletteTag = container.get("palette");
        if (!(paletteTag instanceof ListTag palette) || palette.isEmpty()) throw new IllegalStateException("Missing palette for " + key);
        var values = new ArrayList<String>(palette.size());
        for (int index = 0; index < palette.size(); index++) values.add(canonical(palette.get(index)));
        return values;
    }

    private static int[] decodePalette(CompoundTag container, int paletteSize, String key, int entryCount) {
        Tag dataTag = container.get("data");
        int[] decoded = new int[entryCount];
        if (!(dataTag instanceof LongArrayTag data)) {
            if (paletteSize != 1) throw new IllegalStateException("Missing packed data for " + key);
            return decoded;
        }
        long[] words = data.getAsLongArray();
        if (words.length == 0) throw new IllegalStateException("Invalid packed data for " + key);
        int minimumBits = key.equals("block_states") ? 4 : 1;
        int minimumCandidate = Math.max(minimumBits, ceilLog2(paletteSize));
        // Minecraft's SimpleBitStorage packs floor(64/bits) values per word;
        // values never straddle a word boundary.
        for (int bits = minimumCandidate; bits <= 32; bits++) {
            int valuesPerWord = 64 / bits;
            long expectedWords = (entryCount + (long) valuesPerWord - 1L) / valuesPerWord;
            if (expectedWords != words.length) continue;
            long mask = (1L << bits) - 1L;
            boolean valid = true;
            for (int index = 0; index < decoded.length; index++) {
                int wordIndex = index / valuesPerWord;
                int bitOffset = (index % valuesPerWord) * bits;
                int paletteIndex = (int) ((words[wordIndex] >>> bitOffset) & mask);
                if (paletteIndex >= paletteSize) {
                    valid = false;
                    break;
                }
                decoded[index] = paletteIndex;
            }
            if (valid) return decoded;
        }
        throw new IllegalStateException("Packed data does not match a valid " + key
                + " palette width: words=" + words.length + ", palette=" + paletteSize);
    }

    private static int ceilLog2(int value) {
        if (value <= 0) throw new IllegalStateException("Palette size must be positive");
        return value <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(value - 1);
    }

    private static String canonical(Tag tag) {
        if (tag == null) return "<missing>";
        if (tag instanceof CompoundTag compound) {
            StringBuilder out = new StringBuilder("{");
            compound.getAllKeys().stream().sorted().forEachOrdered(key -> {
                if (out.length() > 1) out.append(',');
                out.append(quote(key)).append(':').append(canonical(compound.get(key)));
            });
            return out.append('}').toString();
        }
        if (tag instanceof ListTag list) {
            StringBuilder out = new StringBuilder("[");
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) out.append(',');
                out.append(canonical(list.get(index)));
            }
            return out.append(']').toString();
        }
        return tag.toString();
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }

    private static BeardifierSnapshot captureBeardifier(StructureManager structures, int chunkX, int chunkZ) {
        Beardifier beardifier = Beardifier.forStructuresInChunk(structures, new ChunkPos(chunkX, chunkZ));
        var pieces = new java.util.ArrayList<BeardifierSnapshot.Rigid>();
        var junctions = new java.util.ArrayList<BeardifierSnapshot.Junction>();
        Object pieceIterator = field(beardifier, "pieceIterator");
        while (hasNext(pieceIterator)) {
            Object value = next(pieceIterator);
            if (!(value instanceof Beardifier.Rigid rigid)) throw new IllegalStateException("Unexpected Beardifier piece " + value);
            BoundingBox box = rigid.box();
            pieces.add(new BeardifierSnapshot.Rigid(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(),
                    BeardifierSnapshot.Adjustment.valueOf(rigid.terrainAdjustment().name()), rigid.groundLevelDelta()));
        }
        rewind(pieceIterator);
        Object junctionIterator = field(beardifier, "junctionIterator");
        while (hasNext(junctionIterator)) {
            Object value = next(junctionIterator);
            if (!(value instanceof JigsawJunction junction)) throw new IllegalStateException("Unexpected Beardifier junction " + value);
            junctions.add(new BeardifierSnapshot.Junction(junction.getSourceX(), junction.getSourceGroundY(), junction.getSourceZ()));
        }
        rewind(junctionIterator);
        return new BeardifierSnapshot(pieces, junctions);
    }

    private static Object field(Object target, String name) {
        try {
            Class<?> type = target.getClass();
            while (type != null) {
                try {
                    var value = type.getDeclaredField(name);
                    value.setAccessible(true);
                    return value.get(target);
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                }
            }
            throw new NoSuchFieldException(name);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot capture Beardifier field " + name, failure);
        }
    }

    private static boolean hasNext(Object iterator) {
        try { var method = iterator.getClass().getMethod("hasNext"); method.setAccessible(true); return (boolean) method.invoke(iterator); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot iterate Beardifier", failure); }
    }

    private static Object next(Object iterator) {
        try { var method = iterator.getClass().getMethod("next"); method.setAccessible(true); return method.invoke(iterator); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot iterate Beardifier", failure); }
    }

    private static void rewind(Object iterator) {
        try { var method = iterator.getClass().getMethod("back", int.class); method.setAccessible(true); method.invoke(iterator, Integer.MAX_VALUE); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot rewind Beardifier", failure); }
    }

}
