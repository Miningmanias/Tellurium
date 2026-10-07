// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.jvm.worldgen;

import dev.tellurium.semantic.program.*;
import java.util.Map;
import java.util.Objects;

/** Straightforward typed interpreter for the admitted subset of WorldgenProgram. */
public final class WorldgenInterpreter {
    private record SplineTraceTarget(ProgramNode.SplineNode node,
                                     java.util.function.BiConsumer<ProgramNode.SplineNode, String> consumer,
                                     Map<String, String> flatProducers) { }
    private static final ThreadLocal<SplineTraceTarget> SPLINE_TRACE = new ThreadLocal<>();

    /** Scoped, thread-local diagnostic of the actual ordered spline evaluation. */
    public static <T> T withSplineTrace(ProgramNode.SplineNode node,
                                       java.util.function.BiConsumer<ProgramNode.SplineNode, String> consumer,
                                       java.util.function.Supplier<T> operation) {
        SplineTraceTarget previous = SPLINE_TRACE.get();
        SPLINE_TRACE.set(new SplineTraceTarget(node, consumer, new java.util.HashMap<>()));
        try { return operation.get(); }
        finally {
            if (previous == null) SPLINE_TRACE.remove();
            else SPLINE_TRACE.set(previous);
        }
    }
    /* These evaluators are immutable; keeping them on the request interpreter
       avoids constructing one object for every captured router node visit. */
    private final NoiseEvaluator noise = new NoiseEvaluator();
    private final BeardifierEvaluator beardifier = new BeardifierEvaluator();
    private final BlendDensityEvaluator blendDensity = new BlendDensityEvaluator();
    private final BlendOffsetEvaluator blendOffset = new BlendOffsetEvaluator();
    private final MarkerEvaluator markerEvaluator = new MarkerEvaluator();
    /**
     * Minecraft evaluates router auxiliaries through SinglePointContext while
     * the same router is wrapped for NoiseChunk interpolation elsewhere.  A
     * thread-local boundary keeps that distinction explicit without making a
     * request interpreter unsafe when independent requests run in parallel.
     */
    private final ThreadLocal<Boolean> directPointBoundary = ThreadLocal.withInitial(() -> false);
    /** Captured Minecraft router roots retain the loader-era marker keying. */
    private final ThreadLocal<Boolean> capturedMarkerBoundary = ThreadLocal.withInitial(() -> false);
    /** Raw router helpers (aquifer/ore) never pass through NoiseChunk.wrap. */
    private final ThreadLocal<Boolean> capturedRawBoundary = ThreadLocal.withInitial(() -> false);
    public Object evaluate(WorldgenProgram program, String root, Map<String, Object> inputs, MarkerContext markers) {
        Objects.requireNonNull(program, "program"); Objects.requireNonNull(root, "root"); Objects.requireNonNull(inputs, "inputs"); Objects.requireNonNull(markers, "markers");
        ProgramNode node = Objects.requireNonNull(program.root(root), "Unknown program root: " + root);
        return evaluate(node, inputs, markers);
    }
    public double evaluateDouble(WorldgenProgram program, String root, Map<String, Object> inputs, MarkerContext markers) {
        Object value = evaluate(program, root, inputs, markers);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Root is not numeric: " + root);
        return number.doubleValue();
    }
    public double evaluateAt(WorldgenProgram program, String root, int x, int y, int z, MarkerContext markers) {
        return evaluateAt(program, root, 0L, x, y, z, markers);
    }
    public double evaluateAt(WorldgenProgram program, String root, long worldSeed, int x, int y, int z, MarkerContext markers) {
        var inputs = new java.util.HashMap<String, Object>(); inputs.put("x", x); inputs.put("y", y); inputs.put("z", z); inputs.put("worldX", x); inputs.put("worldY", y); inputs.put("worldZ", z); inputs.put("worldSeed", worldSeed);
        return evaluateDouble(program, root, inputs, markers);
    }
    /**
     * Evaluate a router root at Minecraft's plain-point boundary.  In this
     * mode {@code Interpolated} wrappers delegate to their child, matching
     * {@code NoiseInterpolator.compute(SinglePointContext)}.  The ordinary
     * {@link #evaluateAt(WorldgenProgram, String, long, int, int, int, MarkerContext)}
     * path remains the block-density interpolation path.
     */
    public double evaluateAtDirect(WorldgenProgram program, String root, long worldSeed, int x, int y, int z,
                                   MarkerContext markers) {
        boolean previous = directPointBoundary.get();
        directPointBoundary.set(true);
        try {
            return evaluateAt(program, root, worldSeed, x, y, z, markers);
        } finally {
            directPointBoundary.set(previous);
        }
    }
    /** Evaluate a captured density root with Minecraft's request-local marker domains. */
    public double evaluateAtCaptured(WorldgenProgram program, String root, long worldSeed, int x, int y, int z,
                                     MarkerContext markers) {
        boolean previous = capturedMarkerBoundary.get();
        capturedMarkerBoundary.set(true);
        try {
            return evaluateAt(program, root, worldSeed, x, y, z, markers);
        } finally {
            capturedMarkerBoundary.set(previous);
        }
    }

    /**
     * Evaluate captured Minecraft input at a plain point boundary.  This is
     * used by aquifer and ore helpers, whose router calls receive a
     * SinglePointContext rather than the block-density interpolation loop.
     * Keeping both boundary flags active preserves captured FlatCache and
     * marker semantics while making Interpolated wrappers transparent.
     */
    public double evaluateAtCapturedDirect(WorldgenProgram program, String root, long worldSeed, int x, int y, int z,
                                            MarkerContext markers) {
        boolean previousCaptured = capturedMarkerBoundary.get();
        boolean previousDirect = directPointBoundary.get();
        capturedMarkerBoundary.set(true);
        directPointBoundary.set(true);
        try {
            return evaluateAt(program, root, worldSeed, x, y, z, markers);
        } finally {
            capturedMarkerBoundary.set(previousCaptured);
            directPointBoundary.set(previousDirect);
        }
    }

    /**
     * Evaluate a captured router root exactly as Minecraft's raw auxiliary
     * fields do.  NoiseBasedAquifer and OreVeinifier retain the router's
     * original roots; only the final density passed through NoiseChunk.wrap
     * receives the request-local cache/interpolation wrappers.  Every raw
     * marker therefore delegates to its child at a point boundary.
     */
    public double evaluateAtCapturedRaw(WorldgenProgram program, String root, long worldSeed, int x, int y, int z,
                                        MarkerContext markers) {
        boolean previousCaptured = capturedMarkerBoundary.get();
        boolean previousDirect = directPointBoundary.get();
        boolean previousRaw = capturedRawBoundary.get();
        capturedMarkerBoundary.set(true);
        directPointBoundary.set(true);
        capturedRawBoundary.set(true);
        try {
            return evaluateAt(program, root, worldSeed, x, y, z, markers);
        } finally {
            capturedMarkerBoundary.set(previousCaptured);
            directPointBoundary.set(previousDirect);
            capturedRawBoundary.set(previousRaw);
        }
    }
    private Object evaluate(ProgramNode node, Map<String, Object> inputs, MarkerContext markers) {
        return switch (node) {
            case ProgramNode.Constant value -> value.value();
            case ProgramNode.Input input -> coerce(Objects.requireNonNull(inputs.get(input.name()), "Missing input: " + input.name()), input.type(), input.name());
            case ProgramNode.Noise noise -> evaluateNoise(noise, inputs);
            case ProgramNode.ShiftedNoise noise -> evaluateShiftedNoise(noise, inputs, markers);
            case ProgramNode.Shift shift -> evaluateShift(shift, inputs);
            case ProgramNode.BlendedNoise noise -> evaluateBlendedNoise(noise, inputs);
            case ProgramNode.EndIsland endIsland -> evaluateEndIsland(endIsland, inputs);
            case ProgramNode.WeirdScaledSampler sampler -> evaluateWeirdScaledSampler(sampler, inputs, markers);
            case ProgramNode.BlendDensity blend -> evaluateBlendDensity(blend, inputs, markers);
            case ProgramNode.BlendAlpha blendAlpha -> evaluateBlendAlpha(blendAlpha, inputs, markers);
            case ProgramNode.BlendOffset blendOffset -> evaluateBlendOffset(blendOffset, inputs, markers);
            case ProgramNode.Beardifier beardifier -> evaluateBeardifier(beardifier, inputs, markers);
            case ProgramNode.Spline spline -> evaluateSpline(spline.spline(), inputs, markers);
            case ProgramNode.Unary unary -> unary(unary.operation(), unary.type(), evaluate(unary.child(), inputs, markers));
            case ProgramNode.Binary binary -> evaluateBinary(binary, inputs, markers);
            case ProgramNode.Ap2 ap2 -> evaluateAp2(ap2, inputs, markers);
            case ProgramNode.Select select -> truthy(evaluate(select.selector(), inputs, markers))
                    ? evaluate(select.whenTrue(), inputs, markers) : evaluate(select.whenFalse(), inputs, markers);
            case ProgramNode.RangeChoice range -> evaluateRange(range, inputs, markers);
            case ProgramNode.Interpolated interpolated -> directPointBoundary.get()
                    ? evaluate(interpolated.child(), inputs, markers)
                    : interpolate(interpolated, inputs, markers);
            case ProgramNode.Marker marker -> evaluateMarker(marker, inputs, markers);
        };
    }

    private Object evaluateBlendDensity(ProgramNode.BlendDensity node, Map<String, Object> inputs,
                                        MarkerContext markers) {
        int x = integerInput(inputs, "x", "worldX");
        int y = integerInput(inputs, "y", "worldY");
        int z = integerInput(inputs, "z", "worldZ");
        Object child = evaluate(node.child(), inputs, markers);
        double blended = blendDensity.blend(markers.structureBlend(), x, y, z, asDouble(child));
        return node.type() == ValueType.FP32 ? (float) blended : blended;
    }

    private Object evaluateBlendAlpha(ProgramNode.BlendAlpha node, Map<String, Object> inputs,
                                      MarkerContext markers) {
        int x = integerInput(inputs, "x", "worldX");
        int z = integerInput(inputs, "z", "worldZ");
        double value = blendOffset.blend(markers.structureBlend(), x, z).alpha();
        return node.type() == ValueType.FP32 ? (float) value : value;
    }

    private Object evaluateBlendOffset(ProgramNode.BlendOffset node, Map<String, Object> inputs,
                                       MarkerContext markers) {
        int x = integerInput(inputs, "x", "worldX");
        int z = integerInput(inputs, "z", "worldZ");
        double value = blendOffset.blend(markers.structureBlend(), x, z).blendingOffset();
        return node.type() == ValueType.FP32 ? (float) value : value;
    }

    private Object evaluateBeardifier(ProgramNode.Beardifier node, Map<String, Object> inputs,
                                      MarkerContext markers) {
        int x = integerInput(inputs, "x", "worldX");
        int y = integerInput(inputs, "y", "worldY");
        int z = integerInput(inputs, "z", "worldZ");
        double value = beardifier.compute(markers.structureBlend(), x, y, z);
        return node.type() == ValueType.FP32 ? (float) value : value;
    }

    /**
     * Replay a raw captured {@code DensityFunctions.Marker} boundary.
     *
     * <p>The loader snapshot is taken from the immutable router graph, before
     * Minecraft's {@code NoiseChunk.wrap} installs its mutable cache objects.
     * At a plain point boundary every marker's
     * {@code compute(SinglePointContext)} delegates directly to its wrapped
     * function. During an explicit interpolation cell this interpreter owns a
     * request-local counter and models the cache domains that are observable at
     * the eight corners.
     */
    private Object evaluateMarker(ProgramNode.Marker marker, Map<String, Object> inputs, MarkerContext markers) {
        if (capturedMarkerBoundary.get()) return evaluateCapturedMarker(marker, inputs, markers);
        MarkerEvaluator.Mode cacheMode = mode(marker.cacheMode());
        if (cacheMode == MarkerEvaluator.Mode.NONE) return evaluate(marker.child(), inputs, markers);
        // CacheOnce, CacheAllInCell and FlatCache are NoiseChunk wrappers.
        // A plain SinglePointContext does not own their mutable lifecycle;
        // preserve the wrapped function at that boundary. Cache2D is the
        // one wrapper whose contract is defined for every point context.
        if (!markers.interpolating() && cacheMode != MarkerEvaluator.Mode.CACHE_2D) {
            return evaluate(marker.child(), inputs, markers);
        }
        Coordinate coordinate = coordinate(inputs);
        String seed = String.valueOf(inputs.getOrDefault("worldSeed", inputs.getOrDefault("seed", 0L)));
        String scope = "request";
        Map<String, Object> producerInputs = inputs;
        switch (cacheMode) {
            case ONCE -> scope = "interpolation/" + markers.interpolationToken();
            case ALL_IN_CELL -> {
                int horizontal = positiveParameter(marker.effects(), "horizontalCell", 4);
                int vertical = positiveParameter(marker.effects(), "verticalCell", 8);
                scope = "cell/" + seed + "/" + Math.floorDiv(coordinate.x(), horizontal) + "/"
                        + Math.floorDiv(coordinate.y(), vertical) + "/" + Math.floorDiv(coordinate.z(), horizontal)
                        + "/" + coordinate.point();
            }
            case CACHE_2D -> scope = "column/" + seed + "/" + coordinate.x() + "/" + coordinate.z();
            case FLAT_CACHE -> {
                int quartX = Math.floorDiv(coordinate.x(), 4);
                int quartZ = Math.floorDiv(coordinate.z(), 4);
                scope = "flat/" + seed + "/" + quartX + "/" + quartZ;
                if (coordinate.present()) {
                    int flatX = Math.multiplyExact(quartX, 4);
                    int flatZ = Math.multiplyExact(quartZ, 4);
                    producerInputs = pointInputs(inputs, flatX, 0, flatZ);
                }
            }
            case NONE -> throw new AssertionError("handled above");
        }
        Map<String, Object> finalProducerInputs = producerInputs;
        if (cacheMode == MarkerEvaluator.Mode.CACHE_2D) {
            return markerEvaluator.evaluateLast(marker.marker(), markers, scope,
                    () -> evaluate(marker.child(), finalProducerInputs, markers));
        }
        return markerEvaluator.evaluate(marker.marker(), cacheMode, markers, scope,
                () -> evaluate(marker.child(), finalProducerInputs, markers));
    }

    /** Loader-era point keys used by the captured Minecraft density path. */
    private Object evaluateCapturedMarker(ProgramNode.Marker marker, Map<String, Object> inputs,
                                          MarkerContext markers) {
        MarkerEvaluator.Mode cacheMode = mode(marker.cacheMode());
        if (cacheMode == MarkerEvaluator.Mode.NONE) return evaluate(marker.child(), inputs, markers);
        if (capturedRawBoundary.get()) return evaluate(marker.child(), inputs, markers);
        // NoiseChunk's CacheOnce and CacheAllInCell wrappers are transparent
        // when called with SinglePointContext.  Aquifer and ore roots enter
        // through this direct boundary, even though the same captured graph is
        // interpolated for block-density evaluation elsewhere.
        if (directPointBoundary.get()
                && (cacheMode == MarkerEvaluator.Mode.ONCE || cacheMode == MarkerEvaluator.Mode.ALL_IN_CELL)) {
            return evaluate(marker.child(), inputs, markers);
        }
        // NoiseChunk's CacheOnce and Cache2D wrappers delegate to their
        // wrapped function while a density function is being filled through
        // a ContextProvider.  Explicit Interpolated nodes reach this branch
        // through their eight corner fills, not through the later scalar
        // compute path.  Reusing one scalar value across the two Y corners
        // would turn a functional cache into a wrong value cache.
        if (markers.interpolating()
                && (cacheMode == MarkerEvaluator.Mode.ONCE || cacheMode == MarkerEvaluator.Mode.CACHE_2D)) {
            return evaluate(marker.child(), inputs, markers);
        }
        if (cacheMode == MarkerEvaluator.Mode.FLAT_CACHE) {
            int x = integerInput(inputs, "x", "worldX");
            int z = integerInput(inputs, "z", "worldZ");
            // NoiseChunk.FlatCache only substitutes its quantized table for
            // points inside the chunk's populated quart range.  Its
            // out-of-range branch calls the wrapped function with the
            // original point context; quantizing every request would change
            // aquifer preliminary-surface samples outside the target chunk.
            if (markers.flatCacheBounds() != null
                    && !markers.flatCacheBounds().contains(x, z)) {
                return evaluate(marker.child(), inputs, markers);
            }
            int flatX = Math.multiplyExact(Math.floorDiv(x, 4), 4);
            int flatZ = Math.multiplyExact(Math.floorDiv(z, 4), 4);
            String key = marker.marker() + "@" + flatX + "," + flatZ + "," + seedInput(inputs);
            SplineTraceTarget trace = SPLINE_TRACE.get();
            if (trace != null) {
                String producer = WorldgenProgram.nodeFingerprint(marker.child());
                String previousProducer = trace.flatProducers().putIfAbsent(key, producer);
                if (previousProducer != null && !previousProducer.equals(producer)) {
                    trace.consumer().accept(trace.node(), "flatAlias key=" + key
                            + " storedProducer=" + previousProducer + " requestedProducer=" + producer
                            + " point=" + coordinate(inputs));
                }
            }
            return markerEvaluator.evaluate(key, MarkerEvaluator.Mode.ONCE, markers,
                    () -> evaluateAtNode(marker.child(), inputs, markers, flatX, 0, flatZ));
        }
        if (cacheMode == MarkerEvaluator.Mode.CACHE_2D) {
            return evaluate(marker.child(), inputs, markers);
        }
        if (!markers.interpolating()
                && (cacheMode == MarkerEvaluator.Mode.ONCE || cacheMode == MarkerEvaluator.Mode.ALL_IN_CELL)) {
            return evaluate(marker.child(), inputs, markers);
        }
        String scope = cacheMode == MarkerEvaluator.Mode.ONCE
                ? "interpolation/" + markers.interpolationToken()
                : scopedMarker(marker.marker(), inputs) + "/cell/" + markers.interpolationToken();
        return markerEvaluator.evaluate(marker.marker(), cacheMode, markers, scope,
                () -> evaluate(marker.child(), inputs, markers));
    }

    private record Coordinate(int x, int y, int z, boolean present) {
        String point() { return present ? x + "," + y + "," + z : "missing"; }
    }

    private static Coordinate coordinate(Map<String, Object> inputs) {
        Object x = inputs.containsKey("x") ? inputs.get("x") : inputs.get("worldX");
        Object y = inputs.containsKey("y") ? inputs.get("y") : inputs.get("worldY");
        Object z = inputs.containsKey("z") ? inputs.get("z") : inputs.get("worldZ");
        if (x instanceof Number nx && y instanceof Number ny && z instanceof Number nz) {
            return new Coordinate(nx.intValue(), ny.intValue(), nz.intValue(), true);
        }
        return new Coordinate(0, 0, 0, false);
    }

    private static int positiveParameter(Map<String, String> parameters, String name, int fallback) {
        String encoded = parameters.get(name);
        if (encoded == null || encoded.isBlank()) return fallback;
        int value = Integer.parseInt(encoded);
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static Map<String, Object> pointInputs(Map<String, Object> original, int x, int y, int z) {
        var point = new java.util.HashMap<>(original);
        point.put("x", x); point.put("y", y); point.put("z", z);
        point.put("worldX", x); point.put("worldY", y); point.put("worldZ", z);
        return point;
    }

    private static String scopedMarker(String marker, Map<String, Object> inputs) {
        Object x = inputs.getOrDefault("x", inputs.get("worldX"));
        Object y = inputs.getOrDefault("y", inputs.get("worldY"));
        Object z = inputs.getOrDefault("z", inputs.get("worldZ"));
        Object seed = inputs.getOrDefault("worldSeed", inputs.get("seed"));
        if (x == null || y == null || z == null) return marker;
        return marker + "@" + x + "," + y + "," + z + "," + String.valueOf(seed);
    }
    private static Object seedInput(Map<String, Object> inputs) {
        return inputs.getOrDefault("worldSeed", inputs.getOrDefault("seed", 0L));
    }
    private Object evaluateNoise(ProgramNode.Noise node, Map<String, Object> inputs) {
        int x = integerInput(inputs, "x", "worldX");
        int y = integerInput(inputs, "y", "worldY");
        int z = integerInput(inputs, "z", "worldZ");
        Object seedValue = inputs.getOrDefault("worldSeed", inputs.getOrDefault("seed", 0L));
        if (!(seedValue instanceof Number seed)) throw new IllegalArgumentException("Noise requires a numeric worldSeed input");
        double value = noise.sample(node.parameters(), seed.longValue(),
                x * (double) node.xzScale(), y * (double) node.yScale(), z * (double) node.xzScale());
        return node.type() == ValueType.FP32 ? (float) value : value;
    }
    private Object evaluateShiftedNoise(ProgramNode.ShiftedNoise node, Map<String, Object> inputs, MarkerContext markers) {
        int x = integerInput(inputs, "x", "worldX");
        int y = integerInput(inputs, "y", "worldY");
        int z = integerInput(inputs, "z", "worldZ");
        Object seedValue = inputs.getOrDefault("worldSeed", inputs.getOrDefault("seed", 0L));
        if (!(seedValue instanceof Number seed)) throw new IllegalArgumentException("Noise requires a numeric worldSeed input");
        double shiftX = asDouble(evaluate(node.shiftX(), inputs, markers));
        double shiftY = asDouble(evaluate(node.shiftY(), inputs, markers));
        double shiftZ = asDouble(evaluate(node.shiftZ(), inputs, markers));
        double value = noise.sample(node.parameters(), seed.longValue(),
                x * (double) node.xzScale() + shiftX,
                y * (double) node.yScale() + shiftY,
                z * (double) node.xzScale() + shiftZ);
        return node.type() == ValueType.FP32 ? (float) value : value;
    }
    private Object evaluateShift(ProgramNode.Shift node, Map<String, Object> inputs) {
        int x = integerInput(inputs, "x", "worldX");
        int y = integerInput(inputs, "y", "worldY");
        int z = integerInput(inputs, "z", "worldZ");
        Object seedValue = inputs.getOrDefault("worldSeed", inputs.getOrDefault("seed", 0L));
        if (!(seedValue instanceof Number seed)) throw new IllegalArgumentException("Shift requires a numeric worldSeed input");
        double scale = node.scale();
        double sampleX = switch (node.axis()) { case "X0Z", "XYZ" -> x * scale; case "ZX0" -> z * scale; default -> throw new IllegalStateException("Unknown shift axis: " + node.axis()); };
        // Minecraft's ShiftB samples (blockZ, blockX, 0), not (blockZ, 0, 0).
        // Keeping the axis permutation explicit is important because ShiftB is
        // the shifted-Z field used by the climate and terrain router roots.
        double sampleY = switch (node.axis()) { case "XYZ" -> y * scale; case "X0Z" -> 0.0; case "ZX0" -> x * scale; default -> throw new IllegalStateException("Unknown shift axis: " + node.axis()); };
        double sampleZ = switch (node.axis()) { case "X0Z", "XYZ" -> z * scale; case "ZX0" -> 0.0; default -> throw new IllegalStateException("Unknown shift axis: " + node.axis()); };
        double value = noise.sample(node.parameters(), seed.longValue(), sampleX, sampleY, sampleZ) * 4.0;
        return node.type() == ValueType.FP32 ? (float) value : value;
    }
    private Object evaluateBlendedNoise(ProgramNode.BlendedNoise node, Map<String, Object> inputs) {
        int x = integerInput(inputs, "x", "worldX");
        int y = integerInput(inputs, "y", "worldY");
        int z = integerInput(inputs, "z", "worldZ");
        Object seedValue = inputs.getOrDefault("worldSeed", inputs.getOrDefault("seed", 0L));
        if (!(seedValue instanceof Number seed)) throw new IllegalArgumentException("Blended noise requires a numeric worldSeed input");
        if (node.parameters().worldSeed() != seed.longValue()) throw new IllegalArgumentException("Blended noise seed does not match captured world seed");
        double value = noise.sampleBlended(node.parameters(), x, y, z);
        return node.type() == ValueType.FP32 ? (float) value : value;
    }
    private Object evaluateEndIsland(ProgramNode.EndIsland node, Map<String, Object> inputs) {
        int x = integerInput(inputs, "x", "worldX");
        int z = integerInput(inputs, "z", "worldZ");
        double value = noise.sampleEndIsland(node.parameters(), x / 8, z / 8);
        return node.type() == ValueType.FP32 ? (float) value : value;
    }
    private Object evaluateWeirdScaledSampler(ProgramNode.WeirdScaledSampler node,
                                              Map<String, Object> inputs, MarkerContext markers) {
        double input = asDouble(evaluate(node.input(), inputs, markers));
        double rarity = node.rarityMapper().equals("TYPE1") ? spaghettiRarity3D(input) : spaghettiRarity2D(input);
        int x = integerInput(inputs, "x", "worldX");
        int y = integerInput(inputs, "y", "worldY");
        int z = integerInput(inputs, "z", "worldZ");
        Object seedValue = inputs.getOrDefault("worldSeed", inputs.getOrDefault("seed", 0L));
        if (!(seedValue instanceof Number seed)) throw new IllegalArgumentException("Weird scaled sampler requires a numeric worldSeed input");
        double value = rarity * Math.abs(noise.sample(node.parameters(), seed.longValue(), x / rarity, y / rarity, z / rarity));
        return node.type() == ValueType.FP32 ? (float) value : value;
    }
    private static double spaghettiRarity2D(double value) {
        if (value < -0.75) return 0.5;
        if (value < -0.5) return 0.75;
        if (value < 0.5) return 1.0;
        return value < 0.75 ? 2.0 : 3.0;
    }
    private static double spaghettiRarity3D(double value) {
        if (value < -0.5) return 0.75;
        if (value < 0.0) return 1.0;
        return value < 0.5 ? 1.5 : 2.0;
    }
    private Object evaluateSpline(ProgramNode.SplineNode node, Map<String, Object> inputs, MarkerContext markers) {
        if (node instanceof ProgramNode.SplineConstant constant) return constant.value();
        var multipoint = (ProgramNode.SplineMultipoint) node;
        float coordinate = (float) asDouble(evaluate(multipoint.coordinate(), inputs, markers));
        var locations = multipoint.locations();
        int interval = findIntervalStart(locations, coordinate);
        int last = locations.size() - 1;
        if (interval < 0) {
            float value = (float) evaluateSpline(multipoint.values().get(0), inputs, markers);
            return linearExtend(coordinate, locations.get(0), value, multipoint.derivatives().get(0));
        }
        if (interval == last) {
            float value = (float) evaluateSpline(multipoint.values().get(last), inputs, markers);
            return linearExtend(coordinate, locations.get(last), value, multipoint.derivatives().get(last));
        }
        float location0 = locations.get(interval), location1 = locations.get(interval + 1);
        float t = (coordinate - location0) / (location1 - location0);
        float value0 = (float) evaluateSpline(multipoint.values().get(interval), inputs, markers);
        float value1 = (float) evaluateSpline(multipoint.values().get(interval + 1), inputs, markers);
        float derivative0 = multipoint.derivatives().get(interval), derivative1 = multipoint.derivatives().get(interval + 1);
        float delta = value1 - value0;
        float a = derivative0 * (location1 - location0) - delta;
        float b = -derivative1 * (location1 - location0) + delta;
        float result = lerpFloat(t, value0, value1) + t * (1.0f - t) * lerpFloat(t, a, b);
        SplineTraceTarget trace = SPLINE_TRACE.get();
        if (trace != null) {
            trace.consumer().accept(node, "point=" + coordinate(inputs) + " coordinate=" + coordinate + " interval=" + interval
                    + " left=" + value0 + " right=" + value1 + " t=" + t
                    + " a=" + a + " b=" + b + " result=" + result);
        }
        return result;
    }
    private static int findIntervalStart(java.util.List<Float> locations, float value) {
        int low = 0, high = locations.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (value < locations.get(middle)) high = middle; else low = middle + 1;
        }
        return low - 1;
    }
    private static float linearExtend(float coordinate, float location, float value, float derivative) {
        return derivative == 0.0f ? value : value + derivative * (coordinate - location);
    }
    private Object evaluateBinary(ProgramNode.Binary node, Map<String, Object> inputs, MarkerContext markers) {
        // Logical nodes are explicitly short-circuiting.  This is observable for worldgen
        // inputs whose evaluation can fail or mutate a request-owned marker.
        if (node.operation().equals("and") || node.operation().equals("or")) {
            boolean left = truthy(evaluate(node.left(), inputs, markers));
            if (node.operation().equals("and") && !left) return false;
            if (node.operation().equals("or") && left) return true;
            return node.operation().equals("and")
                    ? truthy(evaluate(node.right(), inputs, markers))
                    : truthy(evaluate(node.right(), inputs, markers));
        }
        return binary(node.operation(), node.type(), evaluate(node.left(), inputs, markers), evaluate(node.right(), inputs, markers));
    }
    private Object evaluateAp2(ProgramNode.Ap2 node, Map<String, Object> inputs, MarkerContext markers) {
        Object leftValue = evaluate(node.left(), inputs, markers);
        if (node.type() == ValueType.FP32) {
            float left = asFloat(leftValue);
            return switch (node.operation()) {
                case "add" -> left + asFloat(evaluate(node.right(), inputs, markers));
                case "multiply" -> left == 0.0f ? 0.0f
                        : left * asFloat(evaluate(node.right(), inputs, markers));
                case "min" -> left < (float) node.rightMinValue() ? left
                        : Math.min(left, asFloat(evaluate(node.right(), inputs, markers)));
                case "max" -> left > (float) node.rightMaxValue() ? left
                        : Math.max(left, asFloat(evaluate(node.right(), inputs, markers)));
                default -> throw new UnsupportedOperationException("Unknown Ap2 operation: " + node.operation());
            };
        }
        if (node.type() == ValueType.FP64) {
            double left = asDouble(leftValue);
            return switch (node.operation()) {
                case "add" -> left + asDouble(evaluate(node.right(), inputs, markers));
                case "multiply" -> left == 0.0 ? 0.0
                        : left * asDouble(evaluate(node.right(), inputs, markers));
                case "min" -> left < node.rightMinValue() ? left
                        : Math.min(left, asDouble(evaluate(node.right(), inputs, markers)));
                case "max" -> left > node.rightMaxValue() ? left
                        : Math.max(left, asDouble(evaluate(node.right(), inputs, markers)));
                default -> throw new UnsupportedOperationException("Unknown Ap2 operation: " + node.operation());
            };
        }
        throw new IllegalArgumentException("Ap2 requires a floating-point type: " + node.type());
    }
    private Object evaluateRange(ProgramNode.RangeChoice node, Map<String, Object> inputs, MarkerContext markers) {
        double selector = asDouble(evaluate(node.input(), inputs, markers));
        return selector >= node.minInclusive() && selector < node.maxExclusive()
                ? evaluate(node.whenInRange(), inputs, markers) : evaluate(node.whenOutOfRange(), inputs, markers);
    }
    private Object interpolate(ProgramNode.Interpolated node, Map<String, Object> inputs, MarkerContext markers) {
        markers.beginInterpolation();
        try {
            return interpolateCell(node, inputs, markers);
        } finally {
            markers.endInterpolation();
        }
    }

    private Object interpolateCell(ProgramNode.Interpolated node, Map<String, Object> inputs, MarkerContext markers) {
        int x = integerInput(inputs, "x", "worldX"), y = integerInput(inputs, "y", "worldY"), z = integerInput(inputs, "z", "worldZ");
        var geometry = node.geometry(); int w = geometry.horizontalCell(), h = geometry.verticalCell();
        int x0 = Math.multiplyExact(Math.floorDiv(x, w), w), x1 = Math.addExact(x0, w);
        int y0 = Math.multiplyExact(Math.floorDiv(y, h), h), y1 = Math.addExact(y0, h);
        int z0 = Math.multiplyExact(Math.floorDiv(z, w), w), z1 = Math.addExact(z0, w);
        if (capturedMarkerBoundary.get()) {
            // The version-pinned NoiseChunk uses Mth.lerp3 here: X within
            // each Y/Z plane, then Y, then Z.  The original synthetic
            // language intentionally retains its historical Y/X/Z order;
            // captured Minecraft graphs must keep the loader's order.
            return interpolateCapturedCell(node, inputs, markers, x, y, z, x0, x1, y0, y1, z0, z1, w, h);
        }
        if (node.type() == ValueType.FP32) {
            float fx = (float) Math.floorMod(x, w) / (float) w, fy = (float) Math.floorMod(y, h) / (float) h, fz = (float) Math.floorMod(z, w) / (float) w;
            float y00 = lerpFloat(fy, asFloat(evaluateAtNode(node.child(), inputs, markers, x0, y0, z0)), asFloat(evaluateAtNode(node.child(), inputs, markers, x0, y1, z0)));
            float y01 = lerpFloat(fy, asFloat(evaluateAtNode(node.child(), inputs, markers, x0, y0, z1)), asFloat(evaluateAtNode(node.child(), inputs, markers, x0, y1, z1)));
            float y10 = lerpFloat(fy, asFloat(evaluateAtNode(node.child(), inputs, markers, x1, y0, z0)), asFloat(evaluateAtNode(node.child(), inputs, markers, x1, y1, z0)));
            float y11 = lerpFloat(fy, asFloat(evaluateAtNode(node.child(), inputs, markers, x1, y0, z1)), asFloat(evaluateAtNode(node.child(), inputs, markers, x1, y1, z1)));
            return lerpFloat(fz, lerpFloat(fx, y00, y10), lerpFloat(fx, y01, y11));
        }
        if (node.type() == ValueType.FP64) {
            double fx = (double) Math.floorMod(x, w) / w, fy = (double) Math.floorMod(y, h) / h, fz = (double) Math.floorMod(z, w) / w;
            double y00 = lerpDouble(fy, asDouble(evaluateAtNode(node.child(), inputs, markers, x0, y0, z0)), asDouble(evaluateAtNode(node.child(), inputs, markers, x0, y1, z0)));
            double y01 = lerpDouble(fy, asDouble(evaluateAtNode(node.child(), inputs, markers, x0, y0, z1)), asDouble(evaluateAtNode(node.child(), inputs, markers, x0, y1, z1)));
            double y10 = lerpDouble(fy, asDouble(evaluateAtNode(node.child(), inputs, markers, x1, y0, z0)), asDouble(evaluateAtNode(node.child(), inputs, markers, x1, y1, z0)));
            double y11 = lerpDouble(fy, asDouble(evaluateAtNode(node.child(), inputs, markers, x1, y0, z1)), asDouble(evaluateAtNode(node.child(), inputs, markers, x1, y1, z1)));
            return lerpDouble(fz, lerpDouble(fx, y00, y10), lerpDouble(fx, y01, y11));
        }
        throw new UnsupportedOperationException("Interpolation requires FP32 or FP64, got " + node.type());
    }

    private Object interpolateCapturedCell(ProgramNode.Interpolated node, Map<String, Object> inputs,
                                           MarkerContext markers, int x, int y, int z,
                                           int x0, int x1, int y0, int y1, int z0, int z1,
                                           int w, int h) {
        if (node.type() == ValueType.FP32) {
            float fx = (float) Math.floorMod(x, w) / (float) w;
            float fy = (float) Math.floorMod(y, h) / (float) h;
            float fz = (float) Math.floorMod(z, w) / (float) w;
            float x00 = lerpFloat(fx, asFloat(evaluateAtNode(node.child(), inputs, markers, x0, y0, z0)),
                    asFloat(evaluateAtNode(node.child(), inputs, markers, x1, y0, z0)));
            float x10 = lerpFloat(fx, asFloat(evaluateAtNode(node.child(), inputs, markers, x0, y1, z0)),
                    asFloat(evaluateAtNode(node.child(), inputs, markers, x1, y1, z0)));
            float x01 = lerpFloat(fx, asFloat(evaluateAtNode(node.child(), inputs, markers, x0, y0, z1)),
                    asFloat(evaluateAtNode(node.child(), inputs, markers, x1, y0, z1)));
            float x11 = lerpFloat(fx, asFloat(evaluateAtNode(node.child(), inputs, markers, x0, y1, z1)),
                    asFloat(evaluateAtNode(node.child(), inputs, markers, x1, y1, z1)));
            return lerpFloat(fz, lerpFloat(fy, x00, x10), lerpFloat(fy, x01, x11));
        }
        if (node.type() == ValueType.FP64) {
            double fx = (double) Math.floorMod(x, w) / w;
            double fy = (double) Math.floorMod(y, h) / h;
            double fz = (double) Math.floorMod(z, w) / w;
            double x00 = lerpDouble(fx, asDouble(evaluateAtNode(node.child(), inputs, markers, x0, y0, z0)),
                    asDouble(evaluateAtNode(node.child(), inputs, markers, x1, y0, z0)));
            double x10 = lerpDouble(fx, asDouble(evaluateAtNode(node.child(), inputs, markers, x0, y1, z0)),
                    asDouble(evaluateAtNode(node.child(), inputs, markers, x1, y1, z0)));
            double x01 = lerpDouble(fx, asDouble(evaluateAtNode(node.child(), inputs, markers, x0, y0, z1)),
                    asDouble(evaluateAtNode(node.child(), inputs, markers, x1, y0, z1)));
            double x11 = lerpDouble(fx, asDouble(evaluateAtNode(node.child(), inputs, markers, x0, y1, z1)),
                    asDouble(evaluateAtNode(node.child(), inputs, markers, x1, y1, z1)));
            return lerpDouble(fz, lerpDouble(fy, x00, x10), lerpDouble(fy, x01, x11));
        }
        throw new UnsupportedOperationException("Interpolation requires FP32 or FP64, got " + node.type());
    }
    private Object evaluateAtNode(ProgramNode node, Map<String, Object> original, MarkerContext markers, int x, int y, int z) {
        var inputs = new java.util.HashMap<>(original); inputs.put("x", x); inputs.put("y", y); inputs.put("z", z); inputs.put("worldX", x); inputs.put("worldY", y); inputs.put("worldZ", z); return evaluate(node, inputs, markers);
    }
    private static int integerInput(Map<String, Object> inputs, String first, String second) { Object value = inputs.containsKey(first) ? inputs.get(first) : inputs.get(second); if (!(value instanceof Number number)) throw new IllegalArgumentException("Interpolation requires integer input " + first); return number.intValue(); }
    private static float lerpFloat(float fraction, float low, float high) { float difference = high - low; float scaled = fraction * difference; return low + scaled; }
    private static double lerpDouble(double fraction, double low, double high) { double difference = high - low; double scaled = fraction * difference; return low + scaled; }
    private static Object unary(String operation, ValueType type, Object input) {
        return switch (type) {
            case FP32 -> { float value = asFloat(input); yield switch (operation) { case "negate", "-" -> -value; case "abs" -> Math.abs(value); case "square" -> value * value; case "cube" -> value * value * value; case "floor" -> (float) Math.floor(value); case "sqrt" -> (float) Math.sqrt(value); case "half_negative" -> value > 0.0f ? value : value * 0.5f; case "quarter_negative" -> value > 0.0f ? value : value * 0.25f; case "squeeze" -> { float clamped = Math.max(-1.0f, Math.min(1.0f, value)); yield clamped / 2.0f - clamped * clamped * clamped / 24.0f; } default -> throw new UnsupportedOperationException("Unknown unary op: " + operation); }; }
            case FP64 -> { double value = asDouble(input); yield switch (operation) { case "negate", "-" -> -value; case "abs" -> Math.abs(value); case "square" -> value * value; case "cube" -> value * value * value; case "floor" -> Math.floor(value); case "sqrt" -> Math.sqrt(value); case "half_negative" -> value > 0.0 ? value : value * 0.5; case "quarter_negative" -> value > 0.0 ? value : value * 0.25; case "squeeze" -> { double clamped = Math.max(-1.0, Math.min(1.0, value)); yield clamped / 2.0 - clamped * clamped * clamped / 24.0; } default -> throw new UnsupportedOperationException("Unknown unary op: " + operation); }; }
            case INT32 -> { int value = asInt(input); yield switch (operation) { case "negate", "-" -> -value; case "abs" -> Math.abs(value); case "square" -> value * value; case "cube" -> value * value * value; case "floor" -> value; default -> throw new UnsupportedOperationException("Unknown unary op: " + operation); }; }
            case INT64 -> { long value = asLong(input); yield switch (operation) { case "negate", "-" -> -value; case "abs" -> Math.abs(value); case "square" -> value * value; case "cube" -> value * value * value; case "floor" -> value; default -> throw new UnsupportedOperationException("Unknown unary op: " + operation); }; }
            default -> throw new IllegalArgumentException("Unary operation requires a numeric type: " + type);
        };
    }
    private static Object binary(String operation, ValueType type, Object left, Object right) {
        return switch (type) {
            case FP32 -> { float a = asFloat(left), b = asFloat(right); yield switch (operation) { case "+", "add" -> a + b; case "-", "subtract" -> a - b; case "*", "multiply" -> a * b; case "/", "divide" -> a / b; case "min" -> Math.min(a, b); case "max" -> Math.max(a, b); default -> throw new UnsupportedOperationException("Unknown FP32 binary op: " + operation); }; }
            case FP64 -> { double a = asDouble(left), b = asDouble(right); yield switch (operation) { case "+", "add" -> a + b; case "-", "subtract" -> a - b; case "*", "multiply" -> a * b; case "/", "divide" -> a / b; case "min" -> Math.min(a, b); case "max" -> Math.max(a, b); default -> throw new UnsupportedOperationException("Unknown FP64 binary op: " + operation); }; }
            case INT32 -> { int a = asInt(left), b = asInt(right); yield switch (operation) { case "+", "add" -> a + b; case "-", "subtract" -> a - b; case "*", "multiply" -> a * b; case "/", "divide" -> a / b; case "min" -> Math.min(a, b); case "max" -> Math.max(a, b); case "floor_div" -> Math.floorDiv(a, b); case "floor_mod" -> Math.floorMod(a, b); default -> throw new UnsupportedOperationException("Unknown INT32 binary op: " + operation); }; }
            case INT64 -> { long a = asLong(left), b = asLong(right); yield switch (operation) { case "+", "add" -> a + b; case "-", "subtract" -> a - b; case "*", "multiply" -> a * b; case "/", "divide" -> a / b; case "min" -> Math.min(a, b); case "max" -> Math.max(a, b); case "floor_div" -> Math.floorDiv(a, b); case "floor_mod" -> Math.floorMod(a, b); default -> throw new UnsupportedOperationException("Unknown INT64 binary op: " + operation); }; }
            case BOOLEAN -> throw new IllegalArgumentException("Boolean operations must use and/or");
            default -> throw new IllegalArgumentException("Binary operation requires a scalar type: " + type);
        };
    }
    private static Object coerce(Object value, ValueType type, String name) {
        return switch (type) {
            case FP32 -> value instanceof Number n ? n.floatValue() : wrongType(name, type, value);
            case FP64 -> value instanceof Number n ? n.doubleValue() : wrongType(name, type, value);
            case INT32 -> value instanceof Number n ? n.intValue() : wrongType(name, type, value);
            case INT64 -> value instanceof Number n ? n.longValue() : wrongType(name, type, value);
            case BOOLEAN -> value instanceof Boolean ? value : wrongType(name, type, value);
            case BLOCK_STATE, FLUID_STATE, VOID -> value instanceof String || value instanceof Integer ? value : wrongType(name, type, value);
        };
    }
    private static <T> T wrongType(String name, ValueType type, Object value) { throw new IllegalArgumentException("Input " + name + " is not " + type + ": " + value.getClass().getName()); }
    private static float asFloat(Object value) { if (!(value instanceof Number number)) throw new IllegalArgumentException("Expected FP32 number"); return number.floatValue(); }
    private static double asDouble(Object value) { if (!(value instanceof Number number)) throw new IllegalArgumentException("Expected FP64 number"); return number.doubleValue(); }
    private static int asInt(Object value) { if (!(value instanceof Number number)) throw new IllegalArgumentException("Expected INT32 number"); return number.intValue(); }
    private static long asLong(Object value) { if (!(value instanceof Number number)) throw new IllegalArgumentException("Expected INT64 number"); return number.longValue(); }
    private static boolean truthy(Object value) { return value instanceof Boolean b ? b : ((Number) value).doubleValue() != 0.0; }
    private static MarkerEvaluator.Mode mode(String value) { return switch (value.toUpperCase(java.util.Locale.ROOT)) {
        case "NONE", "TRANSPARENT" -> MarkerEvaluator.Mode.NONE;
        case "CACHE_ONCE", "ONCE" -> MarkerEvaluator.Mode.ONCE;
        case "CACHE_ALL_IN_CELL", "ALL_IN_CELL" -> MarkerEvaluator.Mode.ALL_IN_CELL;
        case "CACHE2D", "CACHE_2D" -> MarkerEvaluator.Mode.CACHE_2D;
        case "FLATCACHE", "FLAT_CACHE" -> MarkerEvaluator.Mode.FLAT_CACHE;
        default -> throw new UnsupportedOperationException("Unknown marker cache mode: " + value);
    }; }
}
