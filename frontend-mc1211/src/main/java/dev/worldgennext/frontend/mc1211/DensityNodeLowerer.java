// SPDX-License-Identifier: MIT
package dev.worldgennext.frontend.mc1211;

import dev.worldgennext.semantic.program.*;
import dev.worldgennext.semantic.snapshot.NoiseParameters;
import java.util.Locale;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

public final class DensityNodeLowerer {
    public record Result(ProgramNode node, LoweringDiagnostics diagnostics) { public Result { if (node == null && !diagnostics.hasErrors()) throw new IllegalArgumentException("A successful lowering needs a node"); } public boolean supported() { return node != null && !diagnostics.hasErrors(); } }
    private final NodeCapabilityRegistry registry;
    /* SourceNodeSnapshot is an immutable identity-DAG.  Keep the same pure
       ProgramNode for shared children and adjacent captures instead of
       lowering the graph once per router root and once per chunk. */
    private final IdentityHashMap<SourceNodeSnapshot, ProgramNode> loweredNodes = new IdentityHashMap<>();
    public DensityNodeLowerer() { this(new NodeCapabilityRegistry()); }
    public DensityNodeLowerer(NodeCapabilityRegistry registry) { this.registry = java.util.Objects.requireNonNull(registry); }
    public Result lower(SourceNodeSnapshot source) {
        var diagnostics = new LoweringDiagnostics(); if (source == null) { diagnostics.error("<root>", "MISSING_NODE", "source node is null"); return new Result(null, diagnostics); }
        ProgramNode cached = loweredNodes.get(source);
        if (cached != null) return new Result(cached, diagnostics);
        var capability = registry.capability(source.kind()); if (!capability.supported()) { diagnostics.error(source.sourcePath(), "UNSUPPORTED_NODE", capability.reason()); return new Result(null, diagnostics); }
        try {
            ProgramNode lowered = lowerNode(source, diagnostics);
            if (!diagnostics.hasErrors()) loweredNodes.put(source, lowered);
            return new Result(lowered, diagnostics);
        } catch (RuntimeException failure) { diagnostics.error(source.sourcePath(), "LOWERING_FAILED", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()); return new Result(null, diagnostics); }
    }
    private ProgramNode lowerNode(SourceNodeSnapshot source, LoweringDiagnostics diagnostics) {
        var loweredChildren = source.children().stream().map(child -> lower(child)).toList();
        for (var child : loweredChildren) if (!child.supported()) diagnostics.error(source.sourcePath(), "CHILD_UNSUPPORTED", child.diagnostics().summary());
        if (diagnostics.hasErrors()) throw new IllegalArgumentException("Unsupported child");
        var children = loweredChildren.stream().map(Result::node).toList();
        String kind = NodeCapabilityRegistry.normalize(source.kind());
        return switch (kind) {
            case "constant" -> { requireArity(kind, source, 0); yield new ProgramNode.Constant(source.type(), parseValue(source.type(), source.parameters().get("value")), source.domain()); }
            case "input" -> { requireArity(kind, source, 0); yield new ProgramNode.Input(source.parameters().getOrDefault("name", source.sourcePath()), source.type(), source.domain()); }
            case "noise" -> { requireArity(kind, source, 0); yield lowerNoise(source); }
            case "shift_a", "shift_b", "shift" -> { requireArity(kind, source, 0); requireNumeric(source.type(), kind); yield lowerShift(source, kind); }
            case "blended_noise" -> { requireArity(kind, source, 0); requireNumeric(source.type(), kind); if (source.capturedBlendedNoise() == null) throw new IllegalArgumentException("blended noise state was not captured"); yield new ProgramNode.BlendedNoise(source.capturedBlendedNoise(), source.type(), source.domain()); }
            case "end_island" -> { requireArity(kind, source, 0); requireFloating(source.type(), kind); if (source.capturedEndIsland() == null) throw new IllegalArgumentException("End-island simplex state was not captured"); yield new ProgramNode.EndIsland(source.capturedEndIsland(), source.type(), source.domain()); }
            case "weird_scaled_sampler" -> { requireArity(kind, source, 1); requireNumeric(source.type(), kind); requireChildType(source, children.get(0), source.type()); yield new ProgramNode.WeirdScaledSampler(children.get(0), parseNoiseParameters(source), source.parameters().getOrDefault("rarityMapper", "TYPE1"), source.type(), source.domain()); }
            case "blend_density" -> { requireArity(kind, source, 1); requireChildType(source, children.get(0), source.type()); yield new ProgramNode.BlendDensity(children.get(0), source.type(), source.domain()); }
            case "blend_alpha" -> { requireArity(kind, source, 0); requireFloating(source.type(), kind); yield new ProgramNode.BlendAlpha(source.type(), source.domain()); }
            case "blend_offset" -> { requireArity(kind, source, 0); requireFloating(source.type(), kind); yield new ProgramNode.BlendOffset(source.type(), source.domain()); }
            case "beardifier" -> { requireArity(kind, source, 0); requireFloating(source.type(), kind); yield new ProgramNode.Beardifier(source.type(), source.domain()); }
            case "spline" -> { requireArity(kind, source, 0); if (source.capturedSpline() == null) throw new IllegalArgumentException("spline control points were not captured"); yield new ProgramNode.Spline(source.capturedSpline(), source.type(), source.domain()); }
             case "shifted_noise" -> { requireArity(kind, source, 3); requireNumeric(source.type(), kind); requireMatchingChildren(source, children, kind); yield lowerShiftedNoise(source, children); }
             case "ap2" -> {
                 requireArity(kind, source, 2);
                 requireFloating(source.type(), kind);
                 requireMatchingChildren(source, children, kind);
                 String operation = source.parameters().get("operation");
                 double rightMin = parseDoubleParameter(source, "rightMinValue");
                 double rightMax = parseDoubleParameter(source, "rightMaxValue");
                 yield new ProgramNode.Ap2(operation, source.type(), source.domain(), children.get(0), children.get(1), rightMin, rightMax);
             }
             case "add", "subtract", "mul", "multiply", "div", "divide", "min", "max", "floor_div", "floor_mod", "and", "or" -> {
                requireArity(kind, source, 2); requireMatchingChildren(source, children, kind);
                if ((kind.equals("and") || kind.equals("or")) && source.type() != dev.worldgennext.semantic.program.ValueType.BOOLEAN) throw new IllegalArgumentException(kind + " requires BOOLEAN type");
                if ((kind.equals("floor_div") || kind.equals("floor_mod")) && source.type() != ValueType.INT32 && source.type() != ValueType.INT64) throw new IllegalArgumentException(kind + " requires an integer type");
                yield new ProgramNode.Binary(canonicalBinary(kind), source.type(), source.domain(), children.get(0), children.get(1));
            }
            case "abs", "square", "cube", "negate", "negative", "floor", "sqrt", "half_negative", "quarter_negative", "squeeze" -> {
                requireArity(kind, source, 1); requireNumeric(source.type(), kind); requireChildType(source, children.get(0), source.type());
                if ((kind.equals("half_negative") || kind.equals("quarter_negative") || kind.equals("squeeze")) && source.type() != ValueType.FP32 && source.type() != ValueType.FP64) throw new IllegalArgumentException(kind + " requires floating-point input");
                yield new ProgramNode.Unary(canonicalUnary(kind), source.type(), source.domain(), children.get(0));
            }
            case "find_top_surface" -> {
                // Three nested binary nodes under operation names of their own, which only the fused compiler
                // reads, and only at the router's preliminary-surface root; everything else refuses them.
                requireArity(kind, source, 2); requireFloating(source.type(), kind); requireMatchingChildren(source, children, kind);
                var lowerBound = new ProgramNode.Constant(source.type(), parseValue(source.type(), Integer.toString(Integer.parseInt(source.parameters().get("lowerBound")))), source.domain());
                var cellHeight = new ProgramNode.Constant(source.type(), parseValue(source.type(), Integer.toString(Integer.parseInt(source.parameters().get("cellHeight")))), source.domain());
                var step = new ProgramNode.Binary("find_top_surface_step", source.type(), source.domain(), lowerBound, cellHeight);
                var from = new ProgramNode.Binary("find_top_surface_from", source.type(), source.domain(), children.get(1), step);
                yield new ProgramNode.Binary("find_top_surface", source.type(), source.domain(), children.get(0), from);
            }
            case "range" -> {
                requireArity(kind, source, 3); requireMatchingChildren(source, children.subList(1, 3), kind);
                if (source.parameters().containsKey("minInclusive") || source.parameters().containsKey("maxExclusive")) {
                    double min = Double.parseDouble(source.parameters().getOrDefault("minInclusive", "0"));
                    double max = Double.parseDouble(source.parameters().getOrDefault("maxExclusive", "1"));
                    yield new ProgramNode.RangeChoice(children.get(0), min, max, children.get(1), children.get(2), source.type(), source.domain());
                }
                yield new ProgramNode.Select("range", source.type(), source.domain(), children.get(0), children.get(1), children.get(2));
            }
            case "clamp" -> {
                requireNumeric(source.type(), kind);
                if (children.size() == 1 && source.parameters().containsKey("min") && source.parameters().containsKey("max")) {
                    requireChildType(source, children.get(0), source.type());
                    var min = new ProgramNode.Constant(source.type(), parseValue(source.type(), source.parameters().get("min")), source.domain());
                    var max = new ProgramNode.Constant(source.type(), parseValue(source.type(), source.parameters().get("max")), source.domain());
                    var bounded = new ProgramNode.Binary("max", source.type(), source.domain(), children.get(0), min);
                    yield new ProgramNode.Binary("min", source.type(), source.domain(), bounded, max);
                }
                requireArity(kind, source, 3); requireMatchingChildren(source, children, kind);
                var bounded = new ProgramNode.Binary("max", source.type(), source.domain(), children.get(0), children.get(1));
                yield new ProgramNode.Binary("min", source.type(), source.domain(), bounded, children.get(2));
            }
            case "y_gradient" -> {
                requireArity(kind, source, 0); requireNumeric(source.type(), kind);
                int from = Integer.parseInt(source.parameters().getOrDefault("fromY", "0"));
                int to = Integer.parseInt(source.parameters().getOrDefault("toY", "1"));
                // Minecraft delegates this node to Mth.clampedMap.  That
                // implementation intentionally accepts reversed bounds and
                // preserves the IEEE result of a zero-width map (including
                // NaN at the single boundary), so do not impose a stricter
                // source-side ordering rule here.
                var y = new ProgramNode.Input("y", source.type(), source.domain());
                var fromConstant = new ProgramNode.Constant(source.type(), parseValue(source.type(), source.parameters().getOrDefault("fromValue", "0")), source.domain());
                var toConstant = new ProgramNode.Constant(source.type(), parseValue(source.type(), source.parameters().getOrDefault("toValue", "1")), source.domain());
                var lowY = new ProgramNode.Constant(source.type(), parseValue(source.type(), Integer.toString(from)), source.domain());
                // The difference of two ints as Minecraft takes it, in double: it does not wrap.
                var span = new ProgramNode.Constant(source.type(), parseValue(source.type(), Long.toString((long) to - from)), source.domain());
                var fraction = new ProgramNode.Binary("divide", source.type(), source.domain(), new ProgramNode.Binary("subtract", source.type(), source.domain(), y, lowY), span);
                var zero = new ProgramNode.Constant(source.type(), parseValue(source.type(), "0"), source.domain());
                var one = new ProgramNode.Constant(source.type(), parseValue(source.type(), "1"), source.domain());
                var clamped = new ProgramNode.Binary("min", source.type(), source.domain(), new ProgramNode.Binary("max", source.type(), source.domain(), fraction, zero), one);
                var valueSpan = new ProgramNode.Binary("subtract", source.type(), source.domain(), toConstant, fromConstant);
                // Mth.clampedMap returns the end values themselves outside the bounds and interpolates only
                // between them.  Interpolating with the clamped fraction gives the same bits only where
                // from + 0 * (to - from) is from and from + 1 * (to - from) is to, which rounding does not
                // promise (-1 + (0.1 - -1) is 0.10000000000000009).  Where it does hold, as for every gradient
                // of the tested generators, the node stays as it always was; otherwise the three cases are
                // written out.
                if (endpointsSurviveInterpolation(source.type(), fromConstant.value(), toConstant.value())) {
                    yield new ProgramNode.Binary("add", source.type(), source.domain(), fromConstant,
                            new ProgramNode.Binary("multiply", source.type(), source.domain(), clamped, valueSpan));
                }
                if (to == from) throw new IllegalArgumentException("y_gradient with fromY equal to toY and end values that interpolation does not reproduce");
                var between = new ProgramNode.Binary("add", source.type(), source.domain(), fromConstant,
                        new ProgramNode.Binary("multiply", source.type(), source.domain(), fraction, valueSpan));
                // fraction < 0: the start value; fraction > 1: the end value; otherwise (1 included) the interpolation.
                var outside = new ProgramNode.RangeChoice(fraction, -Double.MAX_VALUE, 0.0, fromConstant, toConstant, source.type(), source.domain());
                yield new ProgramNode.RangeChoice(fraction, 0.0, Math.nextUp(1.0), between, outside, source.type(), source.domain());
            }
            case "interpolate" -> { requireArity(kind, source, 1); requireNumeric(source.type(), kind); requireChildType(source, children.get(0), source.type()); yield new ProgramNode.Interpolated(children.get(0), new InterpolationGeometry(parsePositive(source, "horizontalCell", 4), parsePositive(source, "verticalCell", 8)), source.type()); }
            case "marker", "cache_once", "cache_all_in_cell" -> { requireArity(kind, source, 1); requireChildType(source, children.get(0), source.type()); String mode = kind.equals("cache_once") ? "ONCE" : kind.equals("cache_all_in_cell") ? "ALL_IN_CELL" : source.parameters().getOrDefault("mode", "NONE"); yield new ProgramNode.Marker(source.parameters().getOrDefault("name", source.sourcePath()), mode, source.type(), source.domain(), children.get(0), source.parameters()); }
            default -> throw new IllegalArgumentException("No lowerer for " + source.kind());
        };
    }
    /** Whether {@code from + t * (to - from)} gives exactly {@code from} at t = 0 and exactly {@code to} at t = 1, in the node's own arithmetic. */
    static boolean endpointsSurviveInterpolation(ValueType type, Object from, Object to) {
        if (type == ValueType.FP64) {
            double a = (Double) from, b = (Double) to;
            return Double.doubleToRawLongBits(a + 0.0 * (b - a)) == Double.doubleToRawLongBits(a)
                    && Double.doubleToRawLongBits(a + 1.0 * (b - a)) == Double.doubleToRawLongBits(b);
        }
        if (type == ValueType.FP32) {
            float a = (Float) from, b = (Float) to;
            return Float.floatToRawIntBits(a + 0.0f * (b - a)) == Float.floatToRawIntBits(a)
                    && Float.floatToRawIntBits(a + 1.0f * (b - a)) == Float.floatToRawIntBits(b);
        }
        return true; // integer gradients have no rounding to lose
    }
    private static String canonicalBinary(String kind) { return switch (kind) { case "add" -> "add"; case "subtract" -> "subtract"; case "mul", "multiply" -> "multiply"; case "div", "divide" -> "divide"; default -> kind; }; }
    private static String canonicalUnary(String kind) { return switch (kind) { case "negative" -> "negate"; default -> kind; }; }
    private static void requireArity(String kind, SourceNodeSnapshot source, int expected) { if (source.children().size() != expected) throw new IllegalArgumentException(kind + " requires " + expected + " children, got " + source.children().size()); }
    private static void requireNumeric(ValueType type, String kind) { if (type != ValueType.FP32 && type != ValueType.FP64 && type != ValueType.INT32 && type != ValueType.INT64) throw new IllegalArgumentException(kind + " requires a numeric type"); }
    private static void requireFloating(ValueType type, String kind) { if (type != ValueType.FP32 && type != ValueType.FP64) throw new IllegalArgumentException(kind + " requires a floating-point type"); }
    private static void requireChildType(SourceNodeSnapshot source, ProgramNode child, ValueType expected) { if (child.type() != expected) throw new IllegalArgumentException("Type mismatch at " + source.sourcePath() + ": expected " + expected + " but got " + child.type()); }
    private static void requireMatchingChildren(SourceNodeSnapshot source, java.util.List<ProgramNode> children, String kind) { if (children.stream().anyMatch(child -> child.type() != source.type())) throw new IllegalArgumentException(kind + " children must match result type " + source.type()); }
    private static int parsePositive(SourceNodeSnapshot source, String name, int fallback) { int value = Integer.parseInt(source.parameters().getOrDefault(name, Integer.toString(fallback))); if (value <= 0) throw new IllegalArgumentException(name + " must be positive"); return value; }
    private static double parseDoubleParameter(SourceNodeSnapshot source, String name) {
        String value = source.parameters().get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return Double.parseDouble(value);
    }
    private static ProgramNode lowerNoise(SourceNodeSnapshot source) {
        requireNumeric(source.type(), "noise");
        String name = source.parameters().get("name");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("noise requires a captured name");
        return new ProgramNode.Noise(name, parseNoiseParameters(source),
                Double.parseDouble(source.parameters().getOrDefault("xzScale", "1.0")),
                Double.parseDouble(source.parameters().getOrDefault("yScale", "1.0")), source.type(), source.domain());
    }
    private static ProgramNode lowerShiftedNoise(SourceNodeSnapshot source, java.util.List<ProgramNode> children) {
        String name = source.parameters().get("name");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("shifted_noise requires a captured name");
        return new ProgramNode.ShiftedNoise(name, parseNoiseParameters(source),
                Double.parseDouble(source.parameters().getOrDefault("xzScale", "1.0")),
                Double.parseDouble(source.parameters().getOrDefault("yScale", "1.0")),
                children.get(0), children.get(1), children.get(2), source.type(), source.domain());
    }
    private static ProgramNode lowerShift(SourceNodeSnapshot source, String kind) {
        String name = source.parameters().get("name");
        if (name == null || name.isBlank()) throw new IllegalArgumentException(kind + " requires a captured name");
        String axis = switch (kind) { case "shift_a" -> "X0Z"; case "shift_b" -> "ZX0"; default -> "XYZ"; };
        return new ProgramNode.Shift(name, parseNoiseParameters(source), axis,
                Double.parseDouble(source.parameters().getOrDefault("scale", "0.25")), source.type(), source.domain());
    }
    private static NoiseParameters parseNoiseParameters(SourceNodeSnapshot source) {
        if (source.capturedNoise() != null) return source.capturedNoise();
        String encoded = source.parameters().get("noise");
        String name = source.parameters().get("name");
        if (name == null || name.isBlank() || encoded == null || encoded.isBlank()) throw new IllegalArgumentException("noise requires captured name and octave parameters");
        var values = new java.util.LinkedHashMap<String, String>();
        for (String part : encoded.split(";", -1)) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2 || pair[0].isBlank()) throw new IllegalArgumentException("Malformed captured noise parameters");
            values.put(pair[0], pair[1]);
        }
        int firstOctave = Integer.parseInt(values.getOrDefault("firstOctave", "0"));
        String amplitudes = values.get("amplitudes");
        if (amplitudes == null || amplitudes.isBlank()) throw new IllegalArgumentException("Captured noise amplitudes are missing");
        var parsed = new ArrayList<Double>();
        for (String amplitude : amplitudes.split(",", -1)) parsed.add(Double.valueOf(amplitude));
        return new NoiseParameters(name, firstOctave, List.copyOf(parsed), 0L);
    }
    private static Object parseValue(ValueType type, String value) {
        if (value == null) throw new IllegalArgumentException("constant value missing");
        return switch (type) {
            case FP32 -> Float.parseFloat(value);
            case FP64 -> Double.parseDouble(value);
            case INT32 -> Integer.parseInt(value);
            case INT64 -> Long.parseLong(value);
            case BOOLEAN -> { if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IllegalArgumentException("boolean constant must be true or false"); yield Boolean.parseBoolean(value); }
            case BLOCK_STATE, FLUID_STATE, VOID -> value;
        };
    }
}
