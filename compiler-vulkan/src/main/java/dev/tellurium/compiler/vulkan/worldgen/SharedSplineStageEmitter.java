// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.worldgen;

import java.util.Set;

/**
 * Bounded spline stage over raw IEEE carriers. The caller supplies layouts,
 * integer IEEE helpers and {@code wg_failed}; this fragment owns no declarations.
 * Rows contain a common knot count, an FP64 coordinate and four words per knot:
 * FP32 location, FP32 derivative and FP64 value (low word first).
 * Coefficients are immutable parameters; values come from parameters or GPU
 * predecessor outputs, never CPU density seeding. Host integration owns buffer
 * sizing and strictly increasing knot locations. Source checks do not qualify
 * numerical parity or real-device execution.
 */
public final class SharedSplineStageEmitter {
    private SharedSplineStageEmitter() {}

    /** Direct roots to retain when compacting the caller's IEEE helper source. */
    public static Set<String> helperRoots() {
        return Set.of("wg_fp32_qnan", "wg_fp32_less", "wg_fp32_zero",
                "wg_fp32_sub", "wg_fp32_div", "wg_fp32_mul", "wg_fp32_add",
                "wg_fp32_negate", "wg_fp32_finite", "wg_fp64_to_fp32",
                "wg_fp64_from_fp32", "wg_fp64_qnan");
    }

    /** Helper and entry point only, to append after the caller's shader prefix. */
    public static String source() {
        return """
                uint wg_shared_spline_value(uint base, uint knots) {
                    if (knots < 1u || knots > 64u) return wg_fp32_qnan();
                    uint coordinate = wg_fp64_to_fp32(uvec2(inputBits[base + 1u], inputBits[base + 2u]));
                    uint first = base + 3u;
                    uint location0 = inputBits[first];
                    if (wg_fp32_less(coordinate, location0)) {
                        uint value0 = wg_fp64_to_fp32(uvec2(inputBits[first + 2u], inputBits[first + 3u]));
                        uint derivative0 = inputBits[first + 1u];
                        if (wg_fp32_zero(derivative0)) return value0;
                        return wg_fp32_add(value0, wg_fp32_mul(derivative0, wg_fp32_sub(coordinate, location0)));
                    }
                    for (uint segment = 0u; segment < knots - 1u; segment++) {
                        uint left = first + 4u * segment;
                        uint right = left + 4u;
                        uint location1 = inputBits[right];
                        if (wg_fp32_less(coordinate, location1)) {
                            location0 = inputBits[left];
                            uint value0 = wg_fp64_to_fp32(uvec2(inputBits[left + 2u], inputBits[left + 3u]));
                            uint value1 = wg_fp64_to_fp32(uvec2(inputBits[right + 2u], inputBits[right + 3u]));
                            uint derivative0 = inputBits[left + 1u];
                            uint derivative1 = inputBits[right + 1u];
                            uint width = wg_fp32_sub(location1, location0);
                            uint t = wg_fp32_div(wg_fp32_sub(coordinate, location0), width);
                            uint delta = wg_fp32_sub(value1, value0);
                            uint a = wg_fp32_sub(wg_fp32_mul(derivative0, width), delta);
                            uint b = wg_fp32_add(wg_fp32_negate(wg_fp32_mul(derivative1, width)), delta);
                            uint interpolated = wg_fp32_add(value0, wg_fp32_mul(t, wg_fp32_sub(value1, value0)));
                            uint correctionLerp = wg_fp32_add(a, wg_fp32_mul(t, wg_fp32_sub(b, a)));
                            uint correction = wg_fp32_mul(wg_fp32_mul(t, wg_fp32_sub(0x3f800000u, t)), correctionLerp);
                            return wg_fp32_add(interpolated, correction);
                        }
                    }
                    uint last = first + 4u * (knots - 1u);
                    uint value = wg_fp64_to_fp32(uvec2(inputBits[last + 2u], inputBits[last + 3u]));
                    uint derivative = inputBits[last + 1u];
                    if (wg_fp32_zero(derivative)) return value;
                    return wg_fp32_add(value, wg_fp32_mul(derivative, wg_fp32_sub(coordinate, inputBits[last])));
                }

                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uint knots = inputBits[0];
                    uvec2 result = wg_fp64_qnan();
                    if (knots >= 1u && knots <= 64u) {
                        uint stride = 3u + 4u * knots;
                        uint base = index * stride;
                        uint value = wg_shared_spline_value(base, knots);
                        if (!wg_failed && wg_fp32_finite(value)) result = wg_fp64_from_fp32(value);
                    }
                    outputBits[index * 2u] = result.x;
                    outputBits[index * 2u + 1u] = result.y;
                }
                """;
    }
}
