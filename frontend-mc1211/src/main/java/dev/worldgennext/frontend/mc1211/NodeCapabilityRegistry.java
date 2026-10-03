// SPDX-License-Identifier: MIT
package dev.worldgennext.frontend.mc1211;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Explicit allow-list for source nodes; unknown kinds fail closed. */
public final class NodeCapabilityRegistry {
    public record Capability(boolean supported, String reason) { public Capability { if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Capability reason required"); } }
    private final Map<String, Capability> capabilities = new LinkedHashMap<>();
    public NodeCapabilityRegistry() {
        register("constant", true, "literal");
        register("input", true, "captured input");
        register("noise", true, "captured noise parameters");
        register("add", true, "ordered addition");
        register("subtract", true, "ordered subtraction");
        register("mul", true, "ordered multiplication");
        register("multiply", true, "ordered multiplication");
        register("div", true, "ordered division");
        register("divide", true, "ordered division");
        register("min", true, "ordered minimum");
        register("max", true, "ordered maximum");
        register("floor_div", true, "Java floor division");
        register("floor_mod", true, "Java floor remainder");
        register("and", true, "short-circuit conjunction");
        register("or", true, "short-circuit disjunction");
        register("abs", true, "absolute value");
        register("square", true, "ordered square");
        register("cube", true, "ordered cube");
        register("negate", true, "signed negation");
        register("negative", true, "signed negation");
        register("floor", true, "Java floor");
        register("sqrt", true, "integer-carrier square root where qualified");
        register("half_negative", true, "captured negative half transform");
        register("quarter_negative", true, "captured negative quarter transform");
        register("squeeze", true, "captured clamped squeeze transform");
        register("clamp", true, "ordered numeric clamp");
        register("range", true, "lazy range branch");
        register("interpolate", true, "explicit interpolation");
        register("marker", true, "explicit marker");
        register("cache_once", true, "explicit cache-once marker");
        register("cache_all_in_cell", true, "explicit cell marker");
        register("y_gradient", true, "clamped Y gradient");
        register("shift_a", true, "captured ShiftA noise coordinates");
        register("shift_b", true, "captured ShiftB noise coordinates");
        register("shift", true, "captured Shift noise coordinates");
        register("blended_noise", true, "captured legacy BlendedNoise tables");
        register("weird_scaled_sampler", true, "captured spaghetti rarity transform");
        register("blend_density", true, "captured context-preserving blend wrapper");
        register("blend_alpha", true, "captured Blender height blend factor");
        register("blend_offset", true, "captured Blender height offset");
        register("spline", true, "captured float cubic spline control points");
        register("shifted_noise", true, "captured shifted-noise coordinate effects");
        register("ap2", true, "Minecraft lazy two-argument density operation");
        // These names are deliberately present as rejected capabilities.  A caller gets an
        // actionable diagnostic instead of accidentally treating a game object as an input.
        register("legacy_blended_noise", false, "legacy/blended noise tables are not captured yet");
        register("end_island", true, "captured End-island simplex state");
        register("gradient", false, "gradient source parameters are not captured yet");
        register("beardifier", true, "request-local structure beardifier snapshot");
    }
    public void register(String kind, boolean supported, String reason) {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("Node kind required");
        capabilities.put(normalize(kind), new Capability(supported, reason));
    }
    public Capability capability(String kind) {
        if (kind == null || kind.isBlank()) return new Capability(false, "source node kind is missing");
        return capabilities.getOrDefault(normalize(kind), new Capability(false, "unknown source node kind: " + kind));
    }
    public Map<String, Capability> snapshot() { return Map.copyOf(capabilities); }
    public static String normalize(String kind) { return kind.trim().toLowerCase(Locale.ROOT); }
}
