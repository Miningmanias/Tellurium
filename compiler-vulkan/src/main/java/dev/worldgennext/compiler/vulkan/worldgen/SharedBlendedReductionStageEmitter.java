// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan.worldgen;

import java.util.Set;

/**
 * Graph-independent blended-noise reduction over low-word-first IEEE carriers.
 * Sample rows contain four coordinate/reserved words, eight MAIN samples,
 * sixteen MIN samples and sixteen MAX samples, all in octave order.
 * Prepare emits coordinates and operands for the separately dispatched shared
 * FP64 divider. Finish consumes the original sample row plus its GPU quotient
 * and emits two result words. The reserved word is copied, never interpreted.
 * Buffer sizing and quotient assembly belong to the caller. This prototype is
 * unqualified until native execution and independent same-stack checks pass.
 */
public final class SharedBlendedReductionStageEmitter {
    public static final int SAMPLE_WORDS = 84;
    public static final int DIVISION_INPUT_WORDS = 8;
    public static final int FINISH_WORDS = 86;

    private static final String PREPARE_HELPERS = IntegerIeeeEmitter.helperSource(Set.of(
            "wg_fp64_finite", "wg_fp64_qnan", "wg_fp64_mul", "wg_fp64_add"));
    private static final String FINISH_HELPERS = IntegerIeeeEmitter.helperSource(Set.of(
            "wg_fp64_finite", "wg_fp64_qnan", "wg_fp64_mul", "wg_fp64_add",
            "wg_fp64_sub", "wg_fp64_less_equal", "wg_fp64_min", "wg_fp64_max"));

    private SharedBlendedReductionStageEmitter() { }

    private static String header(int localSize) {
        if (localSize < 1 || localSize > 1024) {
            throw new IllegalArgumentException("Blended reduction local size must be in [1,1024]");
        }
        return """
                #version 450
                layout(local_size_x=%d, local_size_y=1, local_size_z=1) in;
                layout(std430, binding=0) readonly buffer Inputs { uint inputBits[]; };
                layout(std430, binding=1) writeonly buffer Outputs { uint outputBits[]; };
                layout(push_constant) uniform Dispatch {
                    uint count;
                    uint defaultStateId;
                    uint airStateId;
                    uint invalidStateId;
                } dispatch;
                bool wg_failed = false;
                """.formatted(localSize);
    }

    /** Prepare MAIN sum / exact ten operands; division is owned by another stage. */
    public static String prepareSource(int localSize) {
        return header(localSize) + PREPARE_HELPERS + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uint base = index * 84u;
                    uint outputBase = index * 8u;
                    uvec2 mainSum = uvec2(0u);
                    for (uint octave = 0u; octave < 8u; octave++) {
                        uint slot = base + 4u + 2u * octave;
                        uvec2 octaveValue = uvec2(inputBits[slot], inputBits[slot + 1u]);
                        if (!wg_fp64_finite(octaveValue)) { wg_failed = true; break; }
                        uvec2 factor = uvec2(0u, 0x3ff00000u + (octave << 20u));
                        uvec2 scaled = wg_fp64_mul(octaveValue, factor);
                        mainSum = wg_fp64_add(mainSum, scaled);
                        if (!wg_fp64_finite(mainSum)) { wg_failed = true; break; }
                    }
                    if (wg_failed || !wg_fp64_finite(mainSum)) mainSum = wg_fp64_qnan();
                    outputBits[outputBase] = inputBits[base];
                    outputBits[outputBase + 1u] = inputBits[base + 1u];
                    outputBits[outputBase + 2u] = inputBits[base + 2u];
                    outputBits[outputBase + 3u] = inputBits[base + 3u];
                    outputBits[outputBase + 4u] = mainSum.x;
                    outputBits[outputBase + 5u] = mainSum.y;
                    outputBits[outputBase + 6u] = 0u;
                    outputBits[outputBase + 7u] = 0x40240000u;
                }
                """;
    }

    /** Finish with the GPU MAIN/10 quotient at words 84/85; output is one FP64. */
    public static String finishSource(int localSize) {
        return header(localSize) + FINISH_HELPERS + """
                uvec2 wg_shared_blended_finish(uint base) {
                    uvec2 zero = uvec2(0u);
                    uvec2 one = uvec2(0u, 0x3ff00000u);
                    uvec2 quotient = uvec2(inputBits[base + 84u], inputBits[base + 85u]);
                    if (!wg_fp64_finite(quotient)) { wg_failed = true; return wg_fp64_qnan(); }
                    uvec2 shifted = wg_fp64_add(quotient, one);
                    uvec2 d16 = wg_fp64_mul(shifted, uvec2(0u, 0x3fe00000u));
                    if (!wg_fp64_finite(d16)) { wg_failed = true; return wg_fp64_qnan(); }
                    bool skipMin = wg_fp64_less_equal(one, d16);
                    bool skipMax = wg_fp64_less_equal(d16, zero);
                    uvec2 d8 = zero;
                    uvec2 d9 = zero;
                    if (!skipMin) {
                        for (uint octave = 0u; octave < 16u; octave++) {
                            uint slot = base + 20u + 2u * octave;
                            uvec2 octaveValue = uvec2(inputBits[slot], inputBits[slot + 1u]);
                            if (!wg_fp64_finite(octaveValue)) { wg_failed = true; return wg_fp64_qnan(); }
                            uvec2 factor = uvec2(0u, 0x3ff00000u + (octave << 20u));
                            uvec2 scaled = wg_fp64_mul(octaveValue, factor);
                            d8 = wg_fp64_add(d8, scaled);
                            if (!wg_fp64_finite(d8)) { wg_failed = true; return wg_fp64_qnan(); }
                        }
                    }
                    if (!skipMax) {
                        for (uint octave = 0u; octave < 16u; octave++) {
                            uint slot = base + 52u + 2u * octave;
                            uvec2 octaveValue = uvec2(inputBits[slot], inputBits[slot + 1u]);
                            if (!wg_fp64_finite(octaveValue)) { wg_failed = true; return wg_fp64_qnan(); }
                            uvec2 factor = uvec2(0u, 0x3ff00000u + (octave << 20u));
                            uvec2 scaled = wg_fp64_mul(octaveValue, factor);
                            d9 = wg_fp64_add(d9, scaled);
                            if (!wg_fp64_finite(d9)) { wg_failed = true; return wg_fp64_qnan(); }
                        }
                    }
                    uvec2 lower = wg_fp64_mul(d8, uvec2(0u, 0x3f600000u));
                    uvec2 upper = wg_fp64_mul(d9, uvec2(0u, 0x3f600000u));
                    uvec2 blend = wg_fp64_max(zero, wg_fp64_min(one, d16));
                    uvec2 delta = wg_fp64_sub(upper, lower);
                    uvec2 weighted = wg_fp64_mul(blend, delta);
                    uvec2 lerp = wg_fp64_add(lower, weighted);
                    return wg_fp64_mul(lerp, uvec2(0u, 0x3f800000u));
                }

                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uint base = index * 86u;
                    uvec2 result = wg_shared_blended_finish(base);
                    if (wg_failed || !wg_fp64_finite(result)) result = wg_fp64_qnan();
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = result.x;
                    outputBits[outputBase + 1u] = result.y;
                }
                """;
    }
}
