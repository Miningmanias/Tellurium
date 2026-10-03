// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.snapshot;

import dev.worldgennext.frontend.mc1211.SourceNodeSnapshot;
import dev.worldgennext.frontend.mc1211.DensityNodeLowerer;
import dev.worldgennext.semantic.program.ProgramNode;
import dev.worldgennext.semantic.program.EvaluationDomain;
import dev.worldgennext.semantic.program.ValueType;
import dev.worldgennext.semantic.snapshot.NoiseParameters;
import dev.worldgennext.semantic.snapshot.BlendedNoiseParameters;
import dev.worldgennext.semantic.snapshot.EndIslandParameters;
import dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot;
import dev.worldgennext.semantic.snapshot.RandomStateSnapshot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loader-side boundary adapter for mapped 1.21.1 density records.
 *
 * NeoForge's mapped nested density record classes are deliberately package-private or
 * protected even though their record accessors are public. Reflection is therefore kept in
 * this loader module, where it can be version-pinned and audited; no reflective object is
 * allowed to cross into the pure frontend/compiler modules.
 */
public final class DensityNodeReader {
    private static final int MAX_NODES = 131_072;
    private final RandomState randomState;
    private final long worldSeed;
    private final int cellWidth;
    private final int cellHeight;
    private final LinkedHashMap<String, NoiseParameters> capturedNoiseParameters = new LinkedHashMap<>();
    /**
     * Minecraft density graphs are identity DAGs, not trees.  Terrain mods
     * commonly reuse the same spline coordinate or arithmetic subtree from
     * several router branches.  Retaining the immutable boundary descriptor
     * by source identity keeps those graphs finite and also preserves marker
     * identity instead of manufacturing a different cache key per path.
     */
    private final IdentityHashMap<DensityFunction, SourceNodeSnapshot> capturedNodes = new IdentityHashMap<>();
    private final DensityNodeLowerer splineCoordinateLowerer = new DensityNodeLowerer();
    private final IdentityHashMap<Object, ProgramNode.SplineNode> capturedSplines = new IdentityHashMap<>();
    // Paths restart at "root" for each router branch. They are diagnostics,
    // not identities: distinct source markers must never share a value cache.
    private int nextMarkerIdentity;

    public DensityNodeReader() { this(null, 0L); }
    public DensityNodeReader(RandomState randomState, long worldSeed) {
        this(randomState, worldSeed, 4, 8);
    }
    public DensityNodeReader(RandomState randomState, long worldSeed, int cellWidth, int cellHeight) {
        this.randomState = randomState;
        this.worldSeed = worldSeed;
        if (cellWidth <= 0 || cellHeight <= 0 || cellWidth % 4 != 0 || cellHeight % 4 != 0) {
            throw new IllegalArgumentException("Noise cell geometry must be positive multiples of four");
        }
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
    }

    /**
     * Returns whether this reader can be reused for another capture on the
     * same loaded RandomState.  The reader owns an identity-DAG cache, so
     * reusing it avoids rebuilding thousands of immutable node descriptors
     * for every neighboring candidate chunk while retaining the exact
     * loader-object identity boundary.
     */
    public boolean matchesRuntime(RandomState candidate, long candidateSeed,
                                  int candidateCellWidth, int candidateCellHeight) {
        return randomState == candidate
                && worldSeed == candidateSeed
                && cellWidth == candidateCellWidth
                && cellHeight == candidateCellHeight;
    }

    public SourceNodeSnapshot requireCaptured(Object value) {
        if (value instanceof SourceNodeSnapshot snapshot) return snapshot;
        if (value instanceof DensityFunction function) return capture(function);
        throw new IllegalArgumentException("Density node is not a captured 1.21.1 source descriptor; refusing opaque lowering");
    }

    boolean hasRuntimeRandomState() { return randomState != null; }

    /** Captures the two mutable-free positional random factories used by material rules. */
    public synchronized RandomStateSnapshot captureRandomStateSnapshot() {
        if (randomState == null) throw new IllegalStateException("Runtime RandomState is required for random-state capture");
        return new RandomStateSnapshot(worldSeed, 0L, List.of(), List.copyOf(capturedNoiseParameters.values()),
                captureFactory("aquiferRandom"), captureFactory("oreRandom"));
    }

    public synchronized SourceNodeSnapshot capture(DensityFunction function) {
        if (function == null) throw new IllegalArgumentException("Density function is missing");
        return capture(function, "root", new IdentityHashMap<>(), new int[1]);
    }

    private SourceNodeSnapshot capture(DensityFunction function, String path,
                                       IdentityHashMap<DensityFunction, Boolean> active, int[] count) {
        SourceNodeSnapshot cached = capturedNodes.get(function);
        if (cached != null) return cached;
        if (++count[0] > MAX_NODES) throw new IllegalArgumentException("Density graph exceeds capture node limit " + MAX_NODES);
        if (active.put(function, Boolean.TRUE) != null) throw new IllegalArgumentException("Cyclic density graph at " + path);
        try {
            String kind = function.getClass().getSimpleName();
            SourceNodeSnapshot captured;
            if (kind.equals("Constant")) captured = constant(path, number(invoke(function, "value")));
            else if (kind.equals("BeardifierMarker")) captured = node("beardifier", path, Map.of());
            else if (kind.equals("BlendAlpha")) captured = node("blend_alpha", path, Map.of());
            else if (kind.equals("BlendOffset")) captured = node("blend_offset", path, Map.of());
            else if (kind.equals("ShiftA")) captured = shift(path, function, "shift_a", "X0Z");
            else if (kind.equals("ShiftB")) captured = shift(path, function, "shift_b", "ZX0");
            else if (kind.equals("Shift")) captured = shift(path, function, "shift", "XYZ");
            else if (kind.equals("BlendDensity")) {
                captured = node("blend_density", path, Map.of(),
                        capture(functionValue(function, "input"), path + "/input", active, count));
            } else if (kind.equals("BlendedNoise")) captured = blendedNoise(path, function);
            else if (kind.equals("EndIslandDensityFunction")) captured = endIsland(path, function);
            else if (kind.equals("WeirdScaledSampler")) {
                Object holder = invoke(function, "noise");
                String name = noiseName(holder);
                String mapper = enumName(invoke(function, "rarityValueMapper"));
                captured = node("weird_scaled_sampler", path,
                        mapOf("name", name, "noise", noiseParameters(holder), "rarityMapper", mapper),
                        capturedNoise(holder, name),
                        capture(functionValue(function, "input"), path + "/input", active, count));
            } else if (kind.equals("Spline")) {
                ProgramNode.SplineNode spline = captureSpline(invoke(function, "spline"), path + "/spline", active, count);
                captured = node("spline", path, Map.of(), spline);
            } else if (kind.equals("RangeChoice")) {
                var parameters = mapOf("minInclusive", text(invoke(function, "minInclusive")), "maxExclusive", text(invoke(function, "maxExclusive")));
                captured = node("range", path, parameters,
                        capture(functionValue(function, "input"), path + "/input", active, count),
                        capture(functionValue(function, "whenInRange"), path + "/in", active, count),
                        capture(functionValue(function, "whenOutOfRange"), path + "/out", active, count));
            } else if (kind.equals("Ap2")) {
                String operation = switch (enumName(invoke(function, "type"))) {
                    case "ADD" -> "add";
                    case "MUL" -> "multiply";
                    case "MIN" -> "min";
                    case "MAX" -> "max";
                    default -> throw unsupported(path, "Unsupported Ap2 density operation " + enumName(invoke(function, "type")));
                };
                DensityFunction left = functionValue(function, "argument1");
                DensityFunction right = functionValue(function, "argument2");
                captured = node("ap2", path, mapOf("operation", operation,
                                "rightMinValue", Double.toHexString(number(invoke(right, "minValue"))),
                                "rightMaxValue", Double.toHexString(number(invoke(right, "maxValue")))),
                        capture(left, path + "/left", active, count),
                        capture(right, path + "/right", active, count));
            } else if (hasMethod(function, "argument1") && hasMethod(function, "argument2") && hasMethod(function, "type")) {
                String operation = enumName(invoke(function, "type"));
                String binary = switch (operation) {
                    case "ADD" -> "add";
                    case "MUL" -> "mul";
                    case "MIN" -> "min";
                    case "MAX" -> "max";
                    default -> throw unsupported(path, "Unsupported binary density operation " + operation);
                };
                captured = node(binary, path, Map.of(),
                        capture(functionValue(function, "argument1"), path + "/left", active, count),
                        capture(functionValue(function, "argument2"), path + "/right", active, count));
            } else if (kind.equals("Mapped")) {
                String mapped = enumName(invoke(function, "type"));
                String unary = switch (mapped) {
                    case "ABS" -> "abs";
                    case "SQUARE" -> "square";
                    case "CUBE" -> "cube";
                    case "HALF_NEGATIVE" -> "half_negative";
                    case "QUARTER_NEGATIVE" -> "quarter_negative";
                    case "SQUEEZE" -> "squeeze";
                    default -> throw unsupported(path, "Mapped operation " + mapped + " is not captured");
                };
                captured = node(unary, path, Map.of(), capture(functionValue(function, "input"), path + "/input", active, count));
            } else if (kind.equals("Clamp")) {
                captured = node("clamp", path, mapOf("min", text(invoke(function, "minValue")), "max", text(invoke(function, "maxValue"))),
                        capture(functionValue(function, "input"), path + "/input", active, count));
            } else if (kind.equals("YClampedGradient")) {
                captured = node("y_gradient", path, mapOf("fromY", text(invoke(function, "fromY")), "toY", text(invoke(function, "toY")),
                        "fromValue", text(invoke(function, "fromValue")), "toValue", text(invoke(function, "toValue"))));
            } else if (kind.equals("Invert")) {
                // Tectonic's version-pinned Invert record is exactly
                // transform(value) = 1.0 / value.  Lower it to the existing
                // typed division node so the custom object never crosses the
                // loader boundary and its IEEE divide-by-zero behavior stays
                // explicit.
                captured = node("divide", path, Map.of(), constant(path + "/one", 1.0),
                        capture(functionValue(function, "input"), path + "/input", active, count));
            } else if (kind.equals("Marker") || (hasMethod(function, "wrapped") && hasMethod(function, "type"))) {
                String marker = enumName(invoke(function, "type"));
                String markerKind = switch (marker) {
                    case "Interpolated" -> "interpolate";
                    case "CacheOnce" -> "cache_once";
                    case "CacheAllInCell" -> "cache_all_in_cell";
                    case "FlatCache", "Cache2D" -> "marker";
                    default -> throw unsupported(path, "Unknown NoiseChunk marker " + marker);
                };
                var markerParameters = mapOf("name", path + "#marker-" + nextMarkerIdentity++, "mode", marker,
                        "horizontalCell", Integer.toString(cellWidth),
                        "verticalCell", Integer.toString(cellHeight));
                captured = node(markerKind, path, markerParameters,
                        capture(functionValue(function, "wrapped"), path + "/wrapped", active, count));
            } else if (kind.equals("HolderHolder")) {
                Object holder = invoke(function, "function");
                if (!booleanValue(invoke(holder, "isBound"))) throw unsupported(path, "Unbound DensityFunction holder");
                captured = capture((DensityFunction) invoke(holder, "value"), path + "/holder", active, count);
            } else if (kind.equals("Noise")) captured = noise(path, invoke(function, "noise"), invoke(function, "xzScale"), invoke(function, "yScale"));
            else if (kind.equals("ShiftedNoise")) {
                Object holder = invoke(function, "noise");
                String name = noiseName(holder);
                var parameters = mapOf("xzScale", text(invoke(function, "xzScale")), "yScale", text(invoke(function, "yScale")),
                        "name", name, "noise", noiseParameters(holder));
                captured = node("shifted_noise", path, parameters, capturedNoise(holder, name),
                        capture(functionValue(function, "shiftX"), path + "/shiftX", active, count),
                        capture(functionValue(function, "shiftY"), path + "/shiftY", active, count),
                        capture(functionValue(function, "shiftZ"), path + "/shiftZ", active, count));
            } else throw unsupported(path, "Unsupported 1.21.1 density type " + function.getClass().getName());
            capturedNodes.put(function, captured);
            return captured;
        } finally {
            active.remove(function);
        }
    }

    private SourceNodeSnapshot noise(String path, Object holder, Object xzScale, Object yScale) {
        var parameters = new LinkedHashMap<String, String>();
        parameters.put("xzScale", text(xzScale));
        parameters.put("yScale", text(yScale));
        String name = noiseName(holder);
        parameters.put("name", name);
        parameters.put("noise", noiseParameters(holder));
        return new SourceNodeSnapshot("noise", path, ValueType.FP64, EvaluationDomain.WORLD, parameters, List.of(), capturedNoise(holder, name));
    }

    private SourceNodeSnapshot shift(String path, Object function, String kind, String axis) {
        Object holder = invoke(function, "offsetNoise");
        String name = noiseName(holder);
        return node(kind, path, mapOf("name", name, "axis", axis, "scale", "0.25", "noise", noiseParameters(holder)),
                capturedNoise(holder, name));
    }

    private SourceNodeSnapshot blendedNoise(String path, Object function) {
        var captured = new BlendedNoiseParameters(worldSeed,
                capturePerlin((PerlinNoise) field(function, "minLimitNoise")),
                capturePerlin((PerlinNoise) field(function, "maxLimitNoise")),
                capturePerlin((PerlinNoise) field(function, "mainNoise")),
                number(field(function, "xzScale")), number(field(function, "yScale")),
                number(field(function, "xzFactor")), number(field(function, "yFactor")),
                number(field(function, "smearScaleMultiplier")));
        return node("blended_noise", path, mapOf("xzScale", text(captured.xzScale()), "yScale", text(captured.yScale())), captured);
    }

    private SourceNodeSnapshot endIsland(String path, Object function) {
        Object simplex = field(function, "islandNoise");
        int[] values = (int[]) field(simplex, "p");
        if (values.length < 256) throw unsupported(path, "End-island simplex permutation is shorter than 256 entries");
        var permutation = new ArrayList<Integer>(256);
        for (int i = 0; i < 256; i++) permutation.add(values[i]);
        var captured = new EndIslandParameters(number(field(simplex, "xo")), number(field(simplex, "yo")), permutation);
        return node("end_island", path,
                mapOf("xOffset", Double.toHexString(captured.xOffset()), "yOffset", Double.toHexString(captured.yOffset())),
                captured);
    }

    private ProgramNode.SplineNode captureSpline(Object spline, String path,
                                                  IdentityHashMap<DensityFunction, Boolean> active, int[] count) {
        ProgramNode.SplineNode cached = capturedSplines.get(spline);
        if (cached != null) return cached;
        String kind = spline.getClass().getSimpleName();
        if (kind.equals("Constant")) {
            var result = new ProgramNode.SplineConstant((float) number(invoke(spline, "value")));
            capturedSplines.put(spline, result);
            return result;
        }
        if (!kind.equals("Multipoint")) throw unsupported(path, "Unsupported cubic spline type " + spline.getClass().getName());
        Object coordinate = invoke(spline, "coordinate");
        Object holder = invoke(coordinate, "function");
        if (!booleanValue(invoke(holder, "isBound"))) throw unsupported(path + "/coordinate", "Unbound spline coordinate holder");
        SourceNodeSnapshot coordinateSource = capture((DensityFunction) invoke(holder, "value"), path + "/coordinate", active, count);
        // One lowerer per spline manufactured duplicate coordinate/noise
        // subgraphs, which the identity-based Vulkan emitter then dispatched
        // repeatedly. Preserve the source DAG all the way through lowering.
        var lowered = splineCoordinateLowerer.lower(coordinateSource);
        if (!lowered.supported()) throw unsupported(path + "/coordinate", lowered.diagnostics().summary());
        float[] locations = (float[]) invoke(spline, "locations");
        float[] derivatives = (float[]) invoke(spline, "derivatives");
        List<?> values = (List<?>) invoke(spline, "values");
        var nested = new ArrayList<ProgramNode.SplineNode>(values.size());
        for (int i = 0; i < values.size(); i++) nested.add(captureSpline(values.get(i), path + "/value[" + i + "]", active, count));
        var result = new ProgramNode.SplineMultipoint(lowered.node(), toFloatList(locations), nested, toFloatList(derivatives));
        capturedSplines.put(spline, result);
        return result;
    }

    private static List<Float> toFloatList(float[] values) {
        var result = new ArrayList<Float>(values.length);
        for (float value : values) result.add(value);
        return result;
    }

    private NoiseParameters capturedNoise(Object holder, String name) {
        String encoded = noiseParameters(holder);
        String[] parts = encoded.split(";", -1);
        int firstOctave = Integer.parseInt(parts[0].substring(parts[0].indexOf('=') + 1));
        var amplitudes = new ArrayList<Double>();
        for (String value : parts[1].substring(parts[1].indexOf('=') + 1).split(",", -1)) amplitudes.add(Double.valueOf(value));
        NoiseParameters.CapturedNoise captured = null;
        if (randomState != null) {
            try {
                Object runtimeNoise = invoke(holder, "noise");
                if (runtimeNoise instanceof NormalNoise normalNoise) {
                    captured = captureNormalNoise(normalNoise);
                } else if (!name.equals("direct-noise")) {
                    var key = ResourceKey.create(Registries.NOISE, ResourceLocation.parse(name));
                    captured = captureNormalNoise(randomState.getOrCreateNoise(key));
                } else {
                    throw new IllegalArgumentException("Noise holder has no bound runtime NormalNoise");
                }
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("Cannot capture seed-expanded noise " + name, failure);
            }
        }
        var result = new NoiseParameters(name, firstOctave, amplitudes, 0L, captured);
        if (captured != null) {
            var previous = capturedNoiseParameters.putIfAbsent(name, result);
            if (previous != null && !previous.equals(result)) {
                throw new IllegalArgumentException("Seed-expanded noise key was captured with conflicting tables: " + name);
            }
        }
        return result;
    }

    private NoiseParameters.CapturedNoise captureNormalNoise(NormalNoise noise) {
        return new NoiseParameters.CapturedNoise(worldSeed, number(field(noise, "valueFactor")),
                capturePerlin((PerlinNoise) field(noise, "first")), capturePerlin((PerlinNoise) field(noise, "second")));
    }

    private static NoiseParameters.PerlinNoiseSnapshot capturePerlin(PerlinNoise noise) {
        int firstOctave = ((Number) field(noise, "firstOctave")).intValue();
        Object amplitudes = field(noise, "amplitudes");
        int count = ((Number) invoke(amplitudes, "size")).intValue();
        var values = new ArrayList<Double>(count);
        for (int i = 0; i < count; i++) values.add(((Number) invoke(amplitudes, "getDouble", int.class, i)).doubleValue());
        Object levels = field(noise, "noiseLevels");
        var snapshots = new ArrayList<NoiseParameters.ImprovedNoiseSnapshot>(count);
        for (int i = 0; i < count; i++) snapshots.add(Array.get(levels, i) == null ? null : captureImproved((ImprovedNoise) Array.get(levels, i)));
        return new NoiseParameters.PerlinNoiseSnapshot(firstOctave, values, snapshots);
    }

    private static NoiseParameters.ImprovedNoiseSnapshot captureImproved(ImprovedNoise noise) {
        byte[] permutation = (byte[]) field(noise, "p");
        var values = new ArrayList<Integer>(permutation.length);
        for (byte value : permutation) values.add(Byte.toUnsignedInt(value));
        return new NoiseParameters.ImprovedNoiseSnapshot(noise.xo, noise.yo, noise.zo, values);
    }

    private PositionalRandomFactorySnapshot captureFactory(String fieldName) {
        Object factory = field(randomState, fieldName);
        String kind = factory.getClass().getSimpleName();
        if (kind.contains("XoroshiroPositionalRandomFactory")) {
            return PositionalRandomFactorySnapshot.xoroshiro(
                    ((Number) field(factory, "seedLo")).longValue(), ((Number) field(factory, "seedHi")).longValue());
        }
        if (kind.contains("LegacyPositionalRandomFactory")) {
            return PositionalRandomFactorySnapshot.legacy(((Number) field(factory, "seed")).longValue());
        }
        throw new IllegalArgumentException("Unsupported positional random factory " + factory.getClass().getName());
    }

    private static String noiseName(Object noiseHolder) {
        Object noiseData = invoke(noiseHolder, "noiseData");
        Object optional = invoke(noiseData, "unwrapKey");
        if (optional instanceof java.util.Optional<?> value && value.isPresent()) return text(invoke(value.get(), "location"));
        return "direct-noise";
    }

    private static String noiseParameters(Object noiseHolder) {
        Object noiseData = invoke(noiseHolder, "noiseData");
        Object parameters = invoke(noiseData, "value");
        Object amplitudes = invoke(parameters, "amplitudes");
        StringBuilder values = new StringBuilder();
        int size = ((Number) invoke(amplitudes, "size")).intValue();
        for (int i = 0; i < size; i++) {
            if (i > 0) values.append(',');
            values.append(Double.toHexString(((Number) invoke(amplitudes, "getDouble", int.class, i)).doubleValue()));
        }
        return "firstOctave=" + text(invoke(parameters, "firstOctave")) + ";amplitudes=" + values;
    }

    private static SourceNodeSnapshot constant(String path, double value) {
        return new SourceNodeSnapshot("constant", path, ValueType.FP64, EvaluationDomain.WORLD, Map.of("value", Double.toString(value)), List.of());
    }

    private static SourceNodeSnapshot node(String kind, String path, Map<String, String> parameters, SourceNodeSnapshot... children) {
        return new SourceNodeSnapshot(kind, path, ValueType.FP64, EvaluationDomain.WORLD, parameters, List.of(children));
    }

    private static SourceNodeSnapshot node(String kind, String path, Map<String, String> parameters, NoiseParameters capturedNoise, SourceNodeSnapshot... children) {
        return new SourceNodeSnapshot(kind, path, ValueType.FP64, EvaluationDomain.WORLD, parameters, List.of(children), capturedNoise);
    }

    private static SourceNodeSnapshot node(String kind, String path, Map<String, String> parameters,
                                           ProgramNode.SplineNode capturedSpline, SourceNodeSnapshot... children) {
        return new SourceNodeSnapshot(kind, path, ValueType.FP64, EvaluationDomain.WORLD, parameters, List.of(children), null, capturedSpline);
    }

    private static SourceNodeSnapshot node(String kind, String path, Map<String, String> parameters,
                                           BlendedNoiseParameters capturedBlendedNoise, SourceNodeSnapshot... children) {
        return new SourceNodeSnapshot(kind, path, ValueType.FP64, EvaluationDomain.WORLD, parameters, List.of(children), null, null, capturedBlendedNoise);
    }

    private static SourceNodeSnapshot node(String kind, String path, Map<String, String> parameters,
                                           EndIslandParameters capturedEndIsland, SourceNodeSnapshot... children) {
        return new SourceNodeSnapshot(kind, path, ValueType.FP64, EvaluationDomain.WORLD, parameters, List.of(children), null, null, null, capturedEndIsland);
    }

    private static Map<String, String> mapOf(String... values) {
        if ((values.length & 1) != 0) throw new IllegalArgumentException("Key/value pairs required");
        var result = new LinkedHashMap<String, String>();
        for (int i = 0; i < values.length; i += 2) result.put(values[i], values[i + 1]);
        return Map.copyOf(result);
    }

    private static DensityFunction functionValue(Object target, String method) { return (DensityFunction) invoke(target, method); }
    private static boolean hasMethod(Object target, String name) { for (Method method : target.getClass().getMethods()) if (method.getName().equals(name) && method.getParameterCount() == 0) return true; return false; }
    private static String enumName(Object value) { return value instanceof Enum<?> enumeration ? enumeration.name() : text(value); }
    private static double number(Object value) { return ((Number) value).doubleValue(); }
    private static boolean booleanValue(Object value) { return Boolean.TRUE.equals(value); }
    private static String text(Object value) { return String.valueOf(value); }

    private static Object invoke(Object target, String method, Class<?> parameterType, Object argument) {
        try {
            Method found = target.getClass().getMethod(method, parameterType);
            if (!found.canAccess(target)) found.setAccessible(true);
            return found.invoke(target, argument);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot read " + method + " from " + target.getClass().getName(), failure);
        }
    }

    private static Object invoke(Object target, String method) {
        try {
            Method found = target.getClass().getMethod(method);
            if (!found.canAccess(target)) found.setAccessible(true);
            return found.invoke(target);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot read " + method + " from " + target.getClass().getName(), failure);
        }
    }

    private static Object field(Object target, String name) {
        try {
            Field found = target.getClass().getDeclaredField(name);
            if (!found.canAccess(target)) found.setAccessible(true);
            return found.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot read field " + name + " from " + target.getClass().getName(), failure);
        }
    }

    private static IllegalArgumentException unsupported(String path, String message) { return new IllegalArgumentException(message + " at " + path); }
}
