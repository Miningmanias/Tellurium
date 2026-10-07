// SPDX-License-Identifier: MIT
package dev.tellurium.semantic.program;

import dev.tellurium.semantic.snapshot.NoiseParameters;
import dev.tellurium.semantic.snapshot.BlendedNoiseParameters;
import dev.tellurium.semantic.snapshot.EndIslandParameters;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Typed immutable worldgen IR. The synthetic DensityExpression language is deliberately
 * separate; this IR can represent cache domains, effects and typed Minecraft inputs.
 */
public sealed interface ProgramNode permits ProgramNode.Constant, ProgramNode.Input,
        ProgramNode.Unary, ProgramNode.Binary, ProgramNode.Ap2, ProgramNode.Select, ProgramNode.Interpolated,
        ProgramNode.RangeChoice, ProgramNode.Marker, ProgramNode.Noise, ProgramNode.ShiftedNoise,
        ProgramNode.Shift, ProgramNode.BlendDensity, ProgramNode.Spline, ProgramNode.BlendedNoise,
        ProgramNode.WeirdScaledSampler, ProgramNode.EndIsland, ProgramNode.BlendAlpha,
        ProgramNode.BlendOffset, ProgramNode.Beardifier {
    ValueType type();
    EvaluationDomain domain();
    List<ProgramNode> children();
    String operation();

    record Constant(ValueType type, Object value, EvaluationDomain domain) implements ProgramNode {
        public Constant {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(domain, "domain");
            value = canonicalValue(type, value);
            validateValue(type, value);
        }
        @Override public List<ProgramNode> children() { return List.of(); }
        @Override public String operation() { return "constant"; }
    }

    record Input(String name, ValueType type, EvaluationDomain domain) implements ProgramNode {
        public Input {
            requireText(name, "name"); Objects.requireNonNull(type, "type"); Objects.requireNonNull(domain, "domain");
        }
        @Override public List<ProgramNode> children() { return List.of(); }
        @Override public String operation() { return "input:" + name; }
    }

    /** Seeded captured noise parameters; no live game sampler crosses this boundary. */
    record Noise(String name, NoiseParameters parameters, double xzScale, double yScale,
                 ValueType type, EvaluationDomain domain) implements ProgramNode {
        public Noise {
            requireText(name, "name"); Objects.requireNonNull(parameters, "parameters");
            Objects.requireNonNull(type, "type"); Objects.requireNonNull(domain, "domain");
            if (type != ValueType.FP32 && type != ValueType.FP64) throw new IllegalArgumentException("Noise requires a floating type");
            if (!Double.isFinite(xzScale) || !Double.isFinite(yScale)) throw new IllegalArgumentException("Noise scales must be finite");
        }
        @Override public List<ProgramNode> children() { return List.of(); }
        @Override public String operation() { return "noise:" + name; }
    }

    /** Captured ShiftedNoise coordinate effects; the three shifts remain ordered children. */
    record ShiftedNoise(String name, NoiseParameters parameters, double xzScale, double yScale,
                        ProgramNode shiftX, ProgramNode shiftY, ProgramNode shiftZ,
                        ValueType type, EvaluationDomain domain) implements ProgramNode {
        public ShiftedNoise {
            requireText(name, "name"); Objects.requireNonNull(parameters, "parameters");
            Objects.requireNonNull(shiftX, "shiftX"); Objects.requireNonNull(shiftY, "shiftY"); Objects.requireNonNull(shiftZ, "shiftZ");
            Objects.requireNonNull(type, "type"); Objects.requireNonNull(domain, "domain");
            if (type != ValueType.FP32 && type != ValueType.FP64) throw new IllegalArgumentException("Shifted noise requires a floating type");
            if (shiftX.type() != type || shiftY.type() != type || shiftZ.type() != type) throw new IllegalArgumentException("Shifted noise shifts must match result type");
            if (!Double.isFinite(xzScale) || !Double.isFinite(yScale)) throw new IllegalArgumentException("Noise scales must be finite");
        }
        @Override public List<ProgramNode> children() { return List.of(shiftX, shiftY, shiftZ); }
        @Override public String operation() { return "shifted_noise:" + name; }
    }

    /** Captured Minecraft Shift/ShiftA/ShiftB offset noise. */
    record Shift(String name, NoiseParameters parameters, String axis, double scale,
                 ValueType type, EvaluationDomain domain) implements ProgramNode {
        public Shift {
            requireText(name, "name"); Objects.requireNonNull(parameters, "parameters");
            requireText(axis, "axis"); Objects.requireNonNull(type, "type"); Objects.requireNonNull(domain, "domain");
            if (!axis.equals("XYZ") && !axis.equals("X0Z") && !axis.equals("ZX0")) {
                throw new IllegalArgumentException("Unknown shift axis: " + axis);
            }
            if (type != ValueType.FP32 && type != ValueType.FP64) throw new IllegalArgumentException("Shift requires a floating type");
            if (!Double.isFinite(scale)) throw new IllegalArgumentException("Shift scale must be finite");
        }
        @Override public List<ProgramNode> children() { return List.of(); }
        @Override public String operation() { return "shift:" + axis + ":" + name; }
    }

    /** Captured legacy 1.21.1 BlendedNoise with all three seeded Perlin fields. */
    record BlendedNoise(BlendedNoiseParameters parameters, ValueType type, EvaluationDomain domain) implements ProgramNode {
        public BlendedNoise {
            Objects.requireNonNull(parameters, "parameters"); Objects.requireNonNull(type, "type"); Objects.requireNonNull(domain, "domain");
            if (type != ValueType.FP32 && type != ValueType.FP64) throw new IllegalArgumentException("Blended noise requires a floating type");
        }
        @Override public List<ProgramNode> children() { return List.of(); }
        @Override public String operation() { return "blended_noise"; }
    }

    /** Captured 1.21.1 End-island simplex state. */
    record EndIsland(EndIslandParameters parameters, ValueType type, EvaluationDomain domain) implements ProgramNode {
        public EndIsland {
            Objects.requireNonNull(parameters, "parameters"); Objects.requireNonNull(type, "type"); Objects.requireNonNull(domain, "domain");
            if (type != ValueType.FP32 && type != ValueType.FP64) throw new IllegalArgumentException("End island requires a floating type");
        }
        @Override public List<ProgramNode> children() { return List.of(); }
        @Override public String operation() { return "end_island"; }
    }

    /** Captured spaghetti rarity transform and its seed-expanded noise sampler. */
    record WeirdScaledSampler(ProgramNode input, NoiseParameters parameters, String rarityMapper,
                              ValueType type, EvaluationDomain domain) implements ProgramNode {
        public WeirdScaledSampler {
            Objects.requireNonNull(input, "input"); Objects.requireNonNull(parameters, "parameters");
            requireText(rarityMapper, "rarityMapper"); Objects.requireNonNull(type, "type"); Objects.requireNonNull(domain, "domain");
            if (!rarityMapper.equals("TYPE1") && !rarityMapper.equals("TYPE2")) throw new IllegalArgumentException("Unknown rarity mapper: " + rarityMapper);
            if (input.type() != type || (type != ValueType.FP32 && type != ValueType.FP64)) throw new IllegalArgumentException("Weird scaled sampler requires a matching floating input");
        }
        @Override public List<ProgramNode> children() { return List.of(input); }
        @Override public String operation() { return "weird_scaled_sampler:" + rarityMapper; }
    }

    /** Context-preserving blend wrapper.  Structure blending is supplied separately at runtime. */
    record BlendDensity(ProgramNode child, ValueType type, EvaluationDomain domain) implements ProgramNode {
        public BlendDensity {
            Objects.requireNonNull(child, "child"); Objects.requireNonNull(type, "type"); Objects.requireNonNull(domain, "domain");
            if (child.type() != type) throw new IllegalArgumentException("Blend density type mismatch");
        }
        @Override public List<ProgramNode> children() { return List.of(child); }
        @Override public String operation() { return "blend_density"; }
    }

    /** Minecraft's request-local Blender alpha value. */
    record BlendAlpha(ValueType type, EvaluationDomain domain) implements ProgramNode {
        public BlendAlpha {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(domain, "domain");
            if (type != ValueType.FP32 && type != ValueType.FP64) {
                throw new IllegalArgumentException("Blend alpha requires a floating-point type");
            }
        }
        @Override public List<ProgramNode> children() { return List.of(); }
        @Override public String operation() { return "blend_alpha"; }
    }

    /** Minecraft's request-local Blender height offset value. */
    record BlendOffset(ValueType type, EvaluationDomain domain) implements ProgramNode {
        public BlendOffset {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(domain, "domain");
            if (type != ValueType.FP32 && type != ValueType.FP64) {
                throw new IllegalArgumentException("Blend offset requires a floating-point type");
            }
        }
        @Override public List<ProgramNode> children() { return List.of(); }
        @Override public String operation() { return "blend_offset"; }
    }

    /**
     * Minecraft's structure-terrain beardifier marker.  The marker has no
     * static arguments; its value comes from the request-local structure
     * snapshot carried by {@link MarkerContext}.
     */
    record Beardifier(ValueType type, EvaluationDomain domain) implements ProgramNode {
        public Beardifier {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(domain, "domain");
            if (type != ValueType.FP32 && type != ValueType.FP64) {
                throw new IllegalArgumentException("Beardifier requires a floating-point type");
            }
        }

        @Override public List<ProgramNode> children() { return List.of(); }
        @Override public String operation() { return "beardifier"; }
    }

    /** Pure immutable representation of Minecraft's float cubic spline tree. */
    sealed interface SplineNode permits SplineConstant, SplineMultipoint { }

    record SplineConstant(float value) implements SplineNode {
        public SplineConstant { if (!Float.isFinite(value)) throw new IllegalArgumentException("Spline constant must be finite"); }
    }

    record SplineMultipoint(ProgramNode coordinate, List<Float> locations,
                            List<SplineNode> values, List<Float> derivatives) implements SplineNode {
        public SplineMultipoint {
            Objects.requireNonNull(coordinate, "coordinate");
            locations = immutableFiniteFloats(locations, "locations");
            values = List.copyOf(values == null ? List.of() : values);
            derivatives = immutableFiniteFloats(derivatives, "derivatives");
            if (locations.isEmpty() || locations.size() != values.size() || locations.size() != derivatives.size()) {
                throw new IllegalArgumentException("Spline locations, values and derivatives must have the same nonzero size");
            }
        }
    }

    record Spline(SplineNode spline, ValueType type, EvaluationDomain domain) implements ProgramNode {
        public Spline {
            Objects.requireNonNull(spline, "spline"); Objects.requireNonNull(type, "type"); Objects.requireNonNull(domain, "domain");
            if (type != ValueType.FP32 && type != ValueType.FP64) throw new IllegalArgumentException("Spline requires a floating type");
        }
        @Override public List<ProgramNode> children() {
            var result = new java.util.ArrayList<ProgramNode>();
            collectSplineCoordinates(spline, result);
            return List.copyOf(result);
        }
        @Override public String operation() { return "spline"; }
    }

    record Unary(String operation, ValueType type, EvaluationDomain domain, ProgramNode child) implements ProgramNode {
        public Unary {
            requireText(operation, "operation"); Objects.requireNonNull(type, "type"); Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(child, "child");
            if (knownUnary(operation) && (child.type() != type || !isNumeric(type))) throw new IllegalArgumentException("Unary " + operation + " requires a same-typed numeric child");
        }
        @Override public List<ProgramNode> children() { return List.of(child); }
    }

    record Binary(String operation, ValueType type, EvaluationDomain domain,
                  ProgramNode left, ProgramNode right) implements ProgramNode {
        public Binary {
            requireText(operation, "operation"); Objects.requireNonNull(type, "type"); Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(left, "left"); Objects.requireNonNull(right, "right");
            if (knownBinary(operation)) {
                if (left.type() != type || right.type() != type) throw new IllegalArgumentException("Binary " + operation + " requires same-typed children");
                if ((operation.equals("and") || operation.equals("or")) != (type == ValueType.BOOLEAN)) throw new IllegalArgumentException("Logical binary operations require BOOLEAN; arithmetic operations do not");
                if (!(operation.equals("and") || operation.equals("or")) && !isNumeric(type)) throw new IllegalArgumentException("Binary " + operation + " requires a numeric type");
            }
        }
        @Override public List<ProgramNode> children() { return List.of(left, right); }
    }

    /**
     * Minecraft's {@code DensityFunctions.Ap2} operation.
     *
     * <p>This is intentionally distinct from {@link Binary}: the game evaluates
     * the left argument once and can skip the right argument for multiplication
     * by zero, or for a min/max result proven by the right argument's bounds.
     * Those lazy branches are observable when a captured function has effects or
     * an unsupported evaluation domain.</p>
     */
    record Ap2(String operation, ValueType type, EvaluationDomain domain,
               ProgramNode left, ProgramNode right,
               double rightMinValue, double rightMaxValue) implements ProgramNode {
        public Ap2 {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            operation = canonicalAp2Operation(operation);
            if (type != ValueType.FP32 && type != ValueType.FP64) {
                throw new IllegalArgumentException("Ap2 requires a floating-point type");
            }
            if (left.type() != type || right.type() != type) {
                throw new IllegalArgumentException("Ap2 children must match result type " + type);
            }
            if (Double.isNaN(rightMinValue) || Double.isNaN(rightMaxValue)
                    || rightMinValue > rightMaxValue) {
                throw new IllegalArgumentException("Ap2 right bounds must be ordered and non-NaN");
            }
        }
        @Override public List<ProgramNode> children() { return List.of(left, right); }
    }

    /** Ordered lazy branch. Only the selected branch may be evaluated. */
    record Select(String operation, ValueType type, EvaluationDomain domain,
                  ProgramNode selector, ProgramNode whenTrue, ProgramNode whenFalse) implements ProgramNode {
        public Select {
            requireText(operation, "operation"); Objects.requireNonNull(type, "type"); Objects.requireNonNull(domain, "domain");
            Objects.requireNonNull(selector, "selector"); Objects.requireNonNull(whenTrue, "whenTrue");
            Objects.requireNonNull(whenFalse, "whenFalse");
            if (selector.type() != ValueType.BOOLEAN && selector.type() != ValueType.FP32 && selector.type() != ValueType.FP64) {
                throw new IllegalArgumentException("Selector must be boolean or numeric");
            }
            if (whenTrue.type() != type || whenFalse.type() != type) throw new IllegalArgumentException("Branch type mismatch");
        }
        @Override public List<ProgramNode> children() { return List.of(selector, whenTrue, whenFalse); }
    }

    /** Minecraft RangeChoice semantics: evaluate the selector once, then one branch lazily. */
    record RangeChoice(ProgramNode input, double minInclusive, double maxExclusive,
                       ProgramNode whenInRange, ProgramNode whenOutOfRange,
                       ValueType type, EvaluationDomain domain) implements ProgramNode {
        public RangeChoice {
            Objects.requireNonNull(input, "input"); Objects.requireNonNull(whenInRange, "whenInRange");
            Objects.requireNonNull(whenOutOfRange, "whenOutOfRange"); Objects.requireNonNull(type, "type");
            Objects.requireNonNull(domain, "domain");
            if (!Double.isFinite(minInclusive) || !Double.isFinite(maxExclusive) || minInclusive >= maxExclusive) throw new IllegalArgumentException("Invalid range bounds");
            if (input.type() != ValueType.FP32 && input.type() != ValueType.FP64) throw new IllegalArgumentException("Range selector must be floating point");
            if (whenInRange.type() != type || whenOutOfRange.type() != type) throw new IllegalArgumentException("Range branch type mismatch");
        }
        @Override public List<ProgramNode> children() { return List.of(input, whenInRange, whenOutOfRange); }
        @Override public String operation() { return "range"; }
    }

    record Interpolated(ProgramNode child, InterpolationGeometry geometry, ValueType type) implements ProgramNode {
        public Interpolated {
            Objects.requireNonNull(child, "child"); Objects.requireNonNull(geometry, "geometry"); Objects.requireNonNull(type, "type");
            if (child.type() != type) throw new IllegalArgumentException("Interpolation type mismatch");
        }
        @Override public EvaluationDomain domain() { return EvaluationDomain.BLOCK; }
        @Override public List<ProgramNode> children() { return List.of(child); }
        @Override public String operation() { return "interpolate"; }
    }

    /** Marker/cache boundary with explicit cache semantics and dynamic effects. */
    record Marker(String marker, String cacheMode, ValueType type, EvaluationDomain domain,
                  ProgramNode child, Map<String, String> effects) implements ProgramNode {
        public Marker {
            requireText(marker, "marker"); requireText(cacheMode, "cacheMode");
            Objects.requireNonNull(type, "type"); Objects.requireNonNull(domain, "domain"); Objects.requireNonNull(child, "child");
            if (child.type() != type) throw new IllegalArgumentException("Marker type mismatch");
            effects = immutableEffects(effects);
        }
        @Override public List<ProgramNode> children() { return List.of(child); }
        @Override public String operation() { return "marker:" + marker + ":" + cacheMode; }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    private static Map<String, String> immutableEffects(Map<String, String> input) {
        if (input == null || input.isEmpty()) return Map.of();
        var sorted = new TreeMap<String, String>();
        input.forEach((key, value) -> {
            requireText(key, "effect key");
            sorted.put(key, Objects.requireNonNull(value, "effect value"));
        });
        return Collections.unmodifiableMap(sorted);
    }

    private static boolean knownUnary(String operation) {
        return switch (operation) { case "negate", "-", "abs", "square", "cube", "floor", "sqrt", "half_negative", "quarter_negative", "squeeze" -> true; default -> false; };
    }

    private static boolean knownBinary(String operation) {
        return switch (operation) { case "+", "add", "-", "subtract", "*", "multiply", "/", "divide", "min", "max", "floor_div", "floor_mod", "and", "or" -> true; default -> false; };
    }

    private static String canonicalAp2Operation(String operation) {
        requireText(operation, "operation");
        return switch (operation.toLowerCase(java.util.Locale.ROOT)) {
            case "+", "add" -> "add";
            case "*", "mul", "multiply" -> "multiply";
            case "min" -> "min";
            case "max" -> "max";
            default -> throw new IllegalArgumentException("Unknown Ap2 operation: " + operation);
        };
    }

    private static boolean isNumeric(ValueType type) {
        return type == ValueType.FP32 || type == ValueType.FP64 || type == ValueType.INT32 || type == ValueType.INT64;
    }

    private static List<Float> immutableFiniteFloats(List<Float> values, String name) {
        if (values == null) throw new NullPointerException(name);
        var result = new java.util.ArrayList<Float>(values.size());
        for (Float value : values) {
            if (value == null || !Float.isFinite(value)) throw new IllegalArgumentException(name + " must contain finite values");
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static void collectSplineCoordinates(SplineNode node, List<ProgramNode> result) {
        if (node instanceof SplineMultipoint multipoint) {
            result.add(multipoint.coordinate());
            for (SplineNode value : multipoint.values()) {
                if (value == null) throw new NullPointerException("spline value");
                collectSplineCoordinates(value, result);
            }
        }
    }

    private static void validateValue(ValueType type, Object value) {
        boolean valid = switch (type) {
            case FP32 -> value instanceof Float || value instanceof Number;
            case FP64 -> value instanceof Double || value instanceof Number;
            case INT32 -> value instanceof Integer || value instanceof Number;
            case INT64 -> value instanceof Long || value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case BLOCK_STATE, FLUID_STATE, VOID -> value instanceof String || value instanceof Integer;
        };
        if (!valid) throw new IllegalArgumentException("Value does not match " + type);
    }

    private static Object canonicalValue(ValueType type, Object value) {
        if (!(value instanceof Number number)) return value;
        return switch (type) {
            case FP32 -> number.floatValue();
            case FP64 -> number.doubleValue();
            case INT32 -> number.intValue();
            case INT64 -> number.longValue();
            default -> value;
        };
    }
}
