// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.fast;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.worldgennext.compiler.vulkan.fused.FusedNoiseCompiler;
import dev.worldgennext.compiler.vulkan.fused.SurfaceProgram;
import dev.worldgennext.semantic.snapshot.NoiseParameters;
import dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.PerlinSimplexNoise;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lowers one level's surface rule tree and SurfaceSystem state to a
 * {@link SurfaceProgram}.  The tree is read through its own codec, so only the
 * eleven vanilla condition types and four vanilla rule types are understood;
 * any other type (a mod's custom rule) fails closed and the level keeps the
 * vanilla SURFACE step.
 */
public final class FastSurfaceCapture {
    private FastSurfaceCapture() {}

    public static final class UnsupportedSurfaceException extends RuntimeException {
        public UnsupportedSurfaceException(String message) { super(message); }
    }

    /** @param palette full block palette: the NOISE material palette followed by surface-only entries */
    public record Captured(SurfaceProgram program, BlockState[] palette, int basePaletteSize,
                           Reference2IntOpenHashMap<Holder<Biome>> biomeIds, Registry<Biome> biomes) {}

    public static Captured capture(ServerLevel level, NoiseBasedChunkGenerator generator, BlockState[] basePalette) {
        NoiseGeneratorSettings settings = generator.generatorSettings().value();
        RandomState randomState = level.getChunkSource().randomState();
        WorldGenerationContext context = new WorldGenerationContext(generator, level);
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        JsonElement tree = SurfaceRules.RuleSource.CODEC.encodeStart(JsonOps.INSTANCE, settings.surfaceRule())
                .getOrThrow(message -> new UnsupportedSurfaceException("Surface rule cannot be encoded: " + message));

        Builder builder = new Builder(level.getSeed(), randomState, context, biomes, basePalette, settings.defaultBlock());
        Label end = new Label();
        builder.rule(tree.getAsJsonObject(), end);
        builder.bind(end);
        builder.emit(SurfaceProgram.OP_RETURN_NULL, 0, 0, 0, 0, null);

        int defaultBlock = builder.state(settings.defaultBlock());
        int snow = builder.state(Blocks.SNOW_BLOCK.defaultBlockState());
        int packedIce = builder.state(Blocks.PACKED_ICE.defaultBlockState());
        Object system = randomState.surfaceSystem();
        BlockState[] bands = (BlockState[]) field(system, "clayBands");
        // The band sequence is seed derived; palette order (and so the program identity) must not be.
        Arrays.stream(bands).distinct()
                .sorted(Comparator.comparingInt(Block::getId))
                .forEach(builder::state);
        int[] bandIndices = new int[bands.length];
        for (int i = 0; i < bands.length; i++) bandIndices[i] = builder.state(bands[i]);
        if (builder.palette.size() > SurfaceProgram.MAX_PALETTE) {
            throw new UnsupportedSurfaceException("Surface rules use " + builder.palette.size() + " block states; the kernel supports "
                    + SurfaceProgram.MAX_PALETTE);
        }

        int biomeCount = 0;
        for (Biome biome : biomes) biomeCount = Math.max(biomeCount, biomes.getId(biome) + 1);
        if (biomeCount != builder.biomeCount) throw new IllegalStateException("Biome registry changed during capture");
        float[] temperatures = new float[biomeCount];
        int[] biomeFlags = new int[biomeCount];
        Reference2IntOpenHashMap<Holder<Biome>> biomeIds = new Reference2IntOpenHashMap<>();
        biomeIds.defaultReturnValue(-1);
        StringBuilder biomeIdentity = new StringBuilder();
        for (int id = 0; id < biomeCount; id++) {
            Biome biome = biomes.byId(id);
            if (biome == null) continue;
            ResourceKey<Biome> key = biomes.getResourceKey(biome).orElse(null);
            temperatures[id] = biome.getBaseTemperature();
            int flags = biome.getModifiedClimateSettings().temperatureModifier() == Biome.TemperatureModifier.FROZEN
                    ? SurfaceProgram.BIOME_FROZEN_MODIFIER : 0;
            if (biome.getModifiedClimateSettings().temperatureModifier() != Biome.TemperatureModifier.FROZEN
                    && biome.getModifiedClimateSettings().temperatureModifier() != Biome.TemperatureModifier.NONE) {
                throw new UnsupportedSurfaceException("Unknown temperature modifier on biome " + key);
            }
            if (key == Biomes.ERODED_BADLANDS) flags |= SurfaceProgram.BIOME_ERODED_BADLANDS;
            if (key == Biomes.FROZEN_OCEAN || key == Biomes.DEEP_FROZEN_OCEAN) flags |= SurfaceProgram.BIOME_FROZEN_OCEAN;
            biomeFlags[id] = flags;
            if (key != null) biomes.getHolder(key).ifPresent(holder -> biomeIds.put(holder, biomes.getId(biome)));
            biomeIdentity.append(id).append('=').append(key == null ? "?" : key.location()).append(':')
                    .append(Float.floatToRawIntBits(temperatures[id])).append(':').append(flags).append(';');
        }

        BlockState[] palette = builder.palette.toArray(BlockState[]::new);
        int[] paletteFlags = new int[palette.length];
        StringBuilder paletteIdentity = new StringBuilder();
        for (int i = 0; i < palette.length; i++) {
            BlockState state = palette[i];
            paletteFlags[i] = (state.getFluidState().isEmpty() ? 0 : FusedNoiseCompiler.MaterialPalette.FLAG_HAS_FLUID)
                    | (Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(state) ? FusedNoiseCompiler.MaterialPalette.FLAG_BLOCKS_MOTION : 0)
                    | (Heightmap.Types.WORLD_SURFACE_WG.isOpaque().test(state) ? FusedNoiseCompiler.MaterialPalette.FLAG_NOT_AIR : 0)
                    | (state.is(Blocks.WATER) ? SurfaceProgram.FLAG_IS_WATER_BLOCK : 0)
                    | (state.is(settings.defaultBlock().getBlock()) ? SurfaceProgram.FLAG_IS_DEFAULT_BLOCK : 0)
                    | (state == settings.defaultBlock() ? SurfaceProgram.FLAG_IS_DEFAULT_STATE : 0);
            paletteIdentity.append(state).append(':').append(paletteFlags[i]).append(';');
        }

        double[] bounds = new double[builder.noiseBounds.size()];
        for (int i = 0; i < bounds.length; i++) bounds[i] = builder.noiseBounds.get(i);
        long seed = level.getSeed();
        String identity = "surface-v1|" + tree + "|code=" + Arrays.toString(builder.code.toIntArray())
                + "|biomes=" + biomeIdentity + "|palette=" + paletteIdentity + "|base=" + basePalette.length
                + "|legacy=" + settings.useLegacyRandomSource() + "|sea=" + settings.seaLevel()
                + "|noises=" + builder.noiseNames + "|bounds=" + Arrays.toString(bounds)
                + "|gradients=" + builder.gradientNames;
        SurfaceProgram program = new SurfaceProgram(
                builder.code.toIntArray(), builder.masks.toIntArray(), builder.maskWords,
                builder.noises, bounds, builder.gradients,
                noise(seed, randomState, Noises.SURFACE), noise(seed, randomState, Noises.SURFACE_SECONDARY),
                noise(seed, randomState, Noises.CLAY_BANDS_OFFSET),
                noise(seed, randomState, Noises.BADLANDS_PILLAR), noise(seed, randomState, Noises.BADLANDS_PILLAR_ROOF),
                noise(seed, randomState, Noises.BADLANDS_SURFACE),
                noise(seed, randomState, Noises.ICEBERG_PILLAR), noise(seed, randomState, Noises.ICEBERG_PILLAR_ROOF),
                noise(seed, randomState, Noises.ICEBERG_SURFACE),
                factory(field(system, "noiseRandom")),
                bandIndices, paletteFlags, snow, packedIce, defaultBlock,
                temperatures, biomeFlags,
                settings.useLegacyRandomSource(), BiomeManager.obfuscateSeed(seed),
                simplex(staticField(Biome.class, "TEMPERATURE_NOISE")), simplex(staticField(Biome.class, "FROZEN_TEMPERATURE_NOISE")),
                simplex(staticField(Biome.class, "BIOME_INFO_NOISE")),
                identity);
        return new Captured(program, palette, basePalette.length, biomeIds, biomes);
    }

    // ------------------------------------------------------------------ lowering
    private static final class Label {
        int target = -1;
        final IntArrayList references = new IntArrayList();
    }

    private static final class Builder {
        final long seed;
        final RandomState randomState;
        final WorldGenerationContext context;
        final Registry<Biome> biomes;
        final int biomeCount;
        final int maskWords;
        final IntArrayList code = new IntArrayList();
        final IntArrayList masks = new IntArrayList();
        final Map<List<Integer>, Integer> maskOffsets = new HashMap<>();
        final List<NoiseParameters.CapturedNoise> noises = new ArrayList<>();
        final List<Double> noiseBounds = new ArrayList<>();
        final List<String> noiseNames = new ArrayList<>();
        final Map<String, Integer> noiseSlots = new HashMap<>();
        final List<PositionalRandomFactorySnapshot> gradients = new ArrayList<>();
        final List<String> gradientNames = new ArrayList<>();
        final List<BlockState> palette = new ArrayList<>();
        final Map<BlockState, Integer> surfaceStates = new IdentityHashMap<>();

        Builder(long seed, RandomState randomState, WorldGenerationContext context, Registry<Biome> biomes,
                BlockState[] basePalette, BlockState defaultBlock) {
            this.seed = seed;
            this.randomState = randomState;
            this.context = context;
            this.biomes = biomes;
            int count = 0;
            for (Biome biome : biomes) count = Math.max(count, biomes.getId(biome) + 1);
            if (count > SurfaceProgram.MAX_BIOMES) throw new UnsupportedSurfaceException("Too many biomes: " + count);
            this.biomeCount = count;
            this.maskWords = (count + 31) / 32;
            palette.addAll(Arrays.asList(basePalette));
        }

        /** Surface results always use indices past the NOISE palette, so the host can tell the two apart. */
        int state(BlockState state) {
            Integer existing = surfaceStates.get(state);
            if (existing != null) return existing;
            int index = palette.size();
            palette.add(state);
            surfaceStates.put(state, index);
            return index;
        }

        int emit(int op, int a, int b, int c, int d, Label fail) {
            int index = code.size() / SurfaceProgram.INSTRUCTION_INTS;
            code.add(op); code.add(a); code.add(b); code.add(c); code.add(d); code.add(0);
            if (fail != null) fail.references.add(index);
            return index;
        }

        void bind(Label label) {
            label.target = code.size() / SurfaceProgram.INSTRUCTION_INTS;
            for (int reference : label.references) code.set(reference * SurfaceProgram.INSTRUCTION_INTS + 5, label.target);
        }

        void rule(JsonObject rule, Label fail) {
            String type = type(rule);
            switch (type) {
                case "minecraft:sequence" -> {
                    JsonArray sequence = rule.getAsJsonArray("sequence");
                    for (int i = 0; i < sequence.size(); i++) {
                        if (i == sequence.size() - 1) {
                            rule(sequence.get(i).getAsJsonObject(), fail);
                        } else {
                            Label next = new Label();
                            rule(sequence.get(i).getAsJsonObject(), next);
                            bind(next);
                        }
                    }
                    if (sequence.isEmpty()) throw new UnsupportedSurfaceException("Empty surface rule sequence");
                }
                case "minecraft:condition" -> {
                    condition(rule.getAsJsonObject("if_true"), false, fail);
                    rule(rule.getAsJsonObject("then_run"), fail);
                }
                case "minecraft:block" -> {
                    BlockState state = BlockState.CODEC.parse(JsonOps.INSTANCE, rule.get("result_state"))
                            .getOrThrow(message -> new UnsupportedSurfaceException("Surface block state: " + message));
                    emit(SurfaceProgram.OP_RETURN_STATE, state(state), 0, 0, 0, null);
                }
                case "minecraft:bandlands" -> emit(SurfaceProgram.OP_RETURN_BAND, 0, 0, 0, 0, null);
                default -> throw new UnsupportedSurfaceException("Unsupported surface rule type " + type);
            }
        }

        void condition(JsonObject condition, boolean negate, Label fail) {
            String type = type(condition);
            int flag = negate ? SurfaceProgram.NEGATE : 0;
            switch (type) {
                case "minecraft:not" -> condition(condition.getAsJsonObject("invert"), !negate, fail);
                case "minecraft:biome" -> {
                    int[] mask = new int[maskWords];
                    for (JsonElement name : condition.getAsJsonArray("biome_is")) {
                        Biome biome = biomes.get(ResourceLocation.parse(name.getAsString()));
                        if (biome == null) continue; // a key with no registered biome never matches
                        int id = biomes.getId(biome);
                        mask[id >> 5] |= 1 << (id & 31);
                    }
                    List<Integer> key = Arrays.stream(mask).boxed().toList();
                    Integer offset = maskOffsets.get(key);
                    if (offset == null) {
                        offset = masks.size();
                        for (int word : mask) masks.add(word);
                        maskOffsets.put(key, offset);
                    }
                    emit(SurfaceProgram.OP_BIOME | flag, offset, 0, 0, 0, fail);
                }
                case "minecraft:noise_threshold" -> {
                    String name = condition.get("noise").getAsString();
                    double min = condition.get("min_threshold").getAsDouble();
                    double max = condition.get("max_threshold").getAsDouble();
                    Integer slot = noiseSlots.get(name);
                    if (slot == null) {
                        slot = noises.size();
                        if (slot >= SurfaceProgram.MAX_CONDITION_NOISES) {
                            throw new UnsupportedSurfaceException("Surface rules use more than " + SurfaceProgram.MAX_CONDITION_NOISES + " noises");
                        }
                        noises.add(noise(seed, randomState, ResourceKey.create(Registries.NOISE, ResourceLocation.parse(name))));
                        noiseNames.add(name);
                        noiseSlots.put(name, slot);
                    }
                    int bounds = noiseBounds.size() / 2;
                    noiseBounds.add(min);
                    noiseBounds.add(max);
                    emit(SurfaceProgram.OP_NOISE | flag, slot, bounds, 0, 0, fail);
                }
                case "minecraft:vertical_gradient" -> {
                    String name = condition.get("random_name").getAsString();
                    int index = gradientNames.indexOf(name);
                    if (index < 0) {
                        index = gradients.size();
                        gradients.add(factory(randomState.getOrCreateRandomFactory(ResourceLocation.parse(name))));
                        gradientNames.add(name);
                    }
                    emit(SurfaceProgram.OP_VERTICAL_GRADIENT | flag, index, anchor(condition.get("true_at_and_below")),
                            anchor(condition.get("false_at_and_above")), 0, fail);
                }
                case "minecraft:y_above" -> emit(SurfaceProgram.OP_Y_ABOVE | flag, anchor(condition.get("anchor")),
                        condition.get("surface_depth_multiplier").getAsInt(), condition.get("add_stone_depth").getAsBoolean() ? 1 : 0, 0, fail);
                case "minecraft:water" -> emit(SurfaceProgram.OP_WATER | flag, condition.get("offset").getAsInt(),
                        condition.get("surface_depth_multiplier").getAsInt(), condition.get("add_stone_depth").getAsBoolean() ? 1 : 0, 0, fail);
                case "minecraft:temperature" -> emit(SurfaceProgram.OP_TEMPERATURE | flag, 0, 0, 0, 0, fail);
                case "minecraft:steep" -> emit(SurfaceProgram.OP_STEEP | flag, 0, 0, 0, 0, fail);
                case "minecraft:hole" -> emit(SurfaceProgram.OP_HOLE | flag, 0, 0, 0, 0, fail);
                case "minecraft:above_preliminary_surface" -> emit(SurfaceProgram.OP_ABOVE_PRELIMINARY | flag, 0, 0, 0, 0, fail);
                case "minecraft:stone_depth" -> {
                    String surface = condition.get("surface_type").getAsString();
                    if (!surface.equals("floor") && !surface.equals("ceiling")) throw new UnsupportedSurfaceException("Unknown surface type " + surface);
                    emit(SurfaceProgram.OP_STONE_DEPTH | flag, condition.get("offset").getAsInt(),
                            condition.get("add_surface_depth").getAsBoolean() ? 1 : 0, condition.get("secondary_depth_range").getAsInt(),
                            surface.equals("ceiling") ? 1 : 0, fail);
                }
                default -> throw new UnsupportedSurfaceException("Unsupported surface condition type " + type);
            }
        }

        int anchor(JsonElement json) {
            VerticalAnchor anchor = VerticalAnchor.CODEC.parse(JsonOps.INSTANCE, json)
                    .getOrThrow(message -> new UnsupportedSurfaceException("Vertical anchor: " + message));
            return anchor.resolveY(context);
        }

        private static String type(JsonObject object) {
            if (!object.has("type")) throw new UnsupportedSurfaceException("Surface rule node without a type");
            String type = object.get("type").getAsString();
            return type.indexOf(':') < 0 ? "minecraft:" + type : type;
        }
    }

    // ------------------------------------------------------------------ capture helpers
    private static NoiseParameters.CapturedNoise noise(long seed, RandomState randomState, ResourceKey<NormalNoise.NoiseParameters> key) {
        NormalNoise noise;
        try {
            noise = randomState.getOrCreateNoise(key);
        } catch (RuntimeException failure) {
            throw new UnsupportedSurfaceException("Surface noise " + key.location() + " is not available: " + failure);
        }
        return new NoiseParameters.CapturedNoise(seed, ((Number) field(noise, "valueFactor")).doubleValue(),
                perlin((PerlinNoise) field(noise, "first")), perlin((PerlinNoise) field(noise, "second")));
    }

    private static NoiseParameters.PerlinNoiseSnapshot perlin(PerlinNoise noise) {
        int firstOctave = ((Number) field(noise, "firstOctave")).intValue();
        var amplitudes = (DoubleList) field(noise, "amplitudes");
        Object levels = field(noise, "noiseLevels");
        List<Double> values = new ArrayList<>();
        List<NoiseParameters.ImprovedNoiseSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < amplitudes.size(); i++) {
            values.add(amplitudes.getDouble(i));
            ImprovedNoise level = (ImprovedNoise) Array.get(levels, i);
            if (level == null) {
                snapshots.add(null);
            } else {
                byte[] permutation = (byte[]) field(level, "p");
                List<Integer> p = new ArrayList<>(permutation.length);
                for (byte value : permutation) p.add(Byte.toUnsignedInt(value));
                snapshots.add(new NoiseParameters.ImprovedNoiseSnapshot(level.xo, level.yo, level.zo, p));
            }
        }
        return new NoiseParameters.PerlinNoiseSnapshot(firstOctave, values, snapshots);
    }

    private static PositionalRandomFactorySnapshot factory(Object factory) {
        String kind = factory.getClass().getSimpleName();
        if (kind.contains("XoroshiroPositionalRandomFactory")) {
            return PositionalRandomFactorySnapshot.xoroshiro(
                    ((Number) field(factory, "seedLo")).longValue(), ((Number) field(factory, "seedHi")).longValue());
        }
        if (kind.contains("LegacyPositionalRandomFactory")) {
            return PositionalRandomFactorySnapshot.legacy(((Number) field(factory, "seed")).longValue());
        }
        throw new UnsupportedSurfaceException("Unsupported positional random factory " + factory.getClass().getName());
    }

    private static SurfaceProgram.SimplexOctaves simplex(Object noise) {
        PerlinSimplexNoise octaves = (PerlinSimplexNoise) noise;
        Object levels = field(octaves, "noiseLevels");
        List<List<Integer>> permutations = new ArrayList<>();
        for (int i = 0; i < Array.getLength(levels); i++) {
            SimplexNoise level = (SimplexNoise) Array.get(levels, i);
            if (level == null) {
                permutations.add(List.of());
            } else {
                int[] p = (int[]) field(level, "p");
                List<Integer> values = new ArrayList<>(256);
                for (int k = 0; k < 256; k++) values.add(p[k]);
                permutations.add(values);
            }
        }
        return new SurfaceProgram.SimplexOctaves(((Number) field(octaves, "highestFreqInputFactor")).doubleValue(),
                ((Number) field(octaves, "highestFreqValueFactor")).doubleValue(), permutations);
    }

    private static Object field(Object target, String name) {
        try {
            Field found = target.getClass().getDeclaredField(name);
            found.setAccessible(true);
            return found.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new UnsupportedSurfaceException("Cannot read " + target.getClass().getSimpleName() + "." + name + ": " + failure);
        }
    }

    private static Object staticField(Class<?> owner, String name) {
        try {
            Field found = owner.getDeclaredField(name);
            found.setAccessible(true);
            return found.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new UnsupportedSurfaceException("Cannot read " + owner.getSimpleName() + "." + name + ": " + failure);
        }
    }
}
