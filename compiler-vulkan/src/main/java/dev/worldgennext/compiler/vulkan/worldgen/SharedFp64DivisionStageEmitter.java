// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan.worldgen;

import java.util.Set;

/**
 * Graph-independent integer-only FP64 division stages.
 * Input: eight words (four coordinate/reserved words, two operands).
 * Carrier: sixteen words; output: two raw result words. The 108-bit restoring
 * division remains four bits per dispatch, with unchanged explicit RNE packing.
 * Host-mediated dispatch is the conservative route; a resident chain requires
 * separate driver qualification. This emitter makes no parity/performance claim.
 */
public final class SharedFp64DivisionStageEmitter {
    public static final int INPUT_WORDS = 8;
    public static final int STATE_WORDS = 16;
    public static final int OUTPUT_WORDS = 2;
    public static final int CHUNK_COUNT = 27;

    private static final String INIT_HELPERS = IntegerIeeeEmitter.helperSource(Set.of(
            "wg_fp64_exp", "wg_fp64_sign", "wg_fp64_nan", "wg_fp64_qnan",
            "wg_fp64_zero", "wg_fp64_inf", "wg_fp64_frac", "wg_u64_shl1"));
    private static final String CHUNK_HELPERS = IntegerIeeeEmitter.helperSource(Set.of(
            "wg_u64_less", "wg_u64_equal", "wg_u64_sub"));
    private static final String FINISH_HELPERS = IntegerIeeeEmitter.helperSource(Set.of(
            "wg_u64_nonzero", "wg_u64_shl1", "wg_fp64_pack"));
    private SharedFp64DivisionStageEmitter() { }

    private static String header(int localSize) {
        if (localSize < 1 || localSize > 1024) throw new IllegalArgumentException("Division local size must be in [1,1024]");
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
                """ .formatted(localSize);
    }

    public static String initSource(int localSize, int leftOffset, int rightOffset) {
        if (!((leftOffset == 4 && rightOffset == 6) || (leftOffset == 6 && rightOffset == 4))) {
            throw new IllegalArgumentException("Division operands must occupy distinct two-word slots 4 and 6");
        }
        StringBuilder function = new StringBuilder("""
                void wg_stage_fp64_div_init(uvec2 left, uvec2 right, uint outputBase) {
                    uint leftExponent = wg_fp64_exp(left), rightExponent = wg_fp64_exp(right);
                    uint sign = wg_fp64_sign(left) ^ wg_fp64_sign(right);
                    uint mode = 0u;
                    uvec2 special = uvec2(0u);
                    if (wg_fp64_nan(left) || wg_fp64_nan(right)) {
                        mode = 1u;
                        special = wg_fp64_qnan();
                    } else if (wg_fp64_zero(right)) {
                        mode = 1u;
                        special = wg_fp64_zero(left) ? wg_fp64_qnan()
                                : uvec2(0u, (sign << 31) | 0x7ff00000u);
                    } else if (wg_fp64_inf(left) && wg_fp64_inf(right)) {
                        mode = 1u;
                        special = wg_fp64_qnan();
                    } else if (wg_fp64_inf(left)) {
                        mode = 1u;
                        special = uvec2(0u, (sign << 31) | 0x7ff00000u);
                    } else if (wg_fp64_inf(right)) {
                        mode = 1u;
                        special = uvec2(0u, sign << 31);
                    } else if (wg_fp64_zero(left)) {
                        mode = 1u;
                        special = uvec2(0u, sign << 31);
                    }
                    int normalizedLeftExponent = 0;
                    int normalizedRightExponent = 0;
                    uvec2 leftMantissa = uvec2(0u), rightMantissa = uvec2(0u);
                    uvec4 numerator = uvec4(0u);
                    if (mode == 0u) {
                        normalizedLeftExponent = int(leftExponent == 0u ? 1u : leftExponent);
                        normalizedRightExponent = int(rightExponent == 0u ? 1u : rightExponent);
                        leftMantissa = uvec2(wg_fp64_frac(left).x,
                                wg_fp64_frac(left).y | (leftExponent == 0u ? 0u : 0x00100000u));
                        rightMantissa = uvec2(wg_fp64_frac(right).x,
                                wg_fp64_frac(right).y | (rightExponent == 0u ? 0u : 0x00100000u));
                """);
        for (int step = 0; step < 52; step++) {
            function.append("        if (leftExponent == 0u && (leftMantissa.y & 0x00100000u) == 0u) {")
                    .append(" leftMantissa = wg_u64_shl1(leftMantissa); normalizedLeftExponent--; }\n")
                    .append("        if (rightExponent == 0u && (rightMantissa.y & 0x00100000u) == 0u) {")
                    .append(" rightMantissa = wg_u64_shl1(rightMantissa); normalizedRightExponent--; }\n");
        }
        function.append("""
                        numerator = uvec4(0u, leftMantissa.x << 23,
                                (leftMantissa.x >> 9) | (leftMantissa.y << 23), leftMantissa.y >> 9);
                    }
                    outputBits[outputBase] = numerator.x;
                    outputBits[outputBase + 1u] = numerator.y;
                    outputBits[outputBase + 2u] = numerator.z;
                    outputBits[outputBase + 3u] = numerator.w;
                    outputBits[outputBase + 4u] = rightMantissa.x;
                    outputBits[outputBase + 5u] = rightMantissa.y;
                    outputBits[outputBase + 6u] = 0u;
                    outputBits[outputBase + 7u] = 0u;
                    outputBits[outputBase + 8u] = 0u;
                    outputBits[outputBase + 9u] = 0u;
                    outputBits[outputBase + 10u] = uint(normalizedLeftExponent + 4096);
                    outputBits[outputBase + 11u] = uint(normalizedRightExponent + 4096);
                    outputBits[outputBase + 12u] = sign;
                    outputBits[outputBase + 13u] = mode;
                    outputBits[outputBase + 14u] = special.x;
                    outputBits[outputBase + 15u] = special.y;
                }
                """);
        String compacted = header(localSize) + INIT_HELPERS + function;
        return compacted + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    uint base = index * 8u;
                    wg_stage_fp64_div_init(
                            uvec2(inputBits[base + %du], inputBits[base + %du]),
                            uvec2(inputBits[base + %du], inputBits[base + %du]),
                            index * 16u);
                }
                """.formatted(leftOffset, leftOffset + 1, rightOffset, rightOffset + 1);
    }

    public static String chunkSource(int localSize, int chunk) {
        if (chunk < 0 || chunk >= CHUNK_COUNT) {
            throw new IllegalArgumentException("Invalid FP64 division chunk: " + chunk);
        }
        int highestBit = 107 - chunk * 4;
        String word = highestBit >= 96 ? "w" : highestBit >= 64 ? "z" : highestBit >= 32 ? "y" : "x";
        int wordOffset = switch (word) {
            case "w" -> 3;
            case "z" -> 2;
            case "y" -> 1;
            default -> 0;
        };
        String function = """
                uvec4 wg_stage_fp64_div_chunk(uvec4 state, uvec2 divisor, uint incoming) {
                    uvec2 remainder = state.xy;
                    uvec2 quotient = state.zw;
                    {
                        uint bit = (incoming >> 3u) & 1u;
                        remainder = uvec2((remainder.x << 1) | bit,
                                (remainder.y << 1) | (remainder.x >> 31));
                        uint selected = wg_u64_less(divisor, remainder) ||
                                wg_u64_equal(divisor, remainder) ? 1u : 0u;
                        if (selected != 0u) remainder = wg_u64_sub(remainder, divisor);
                        quotient = uvec2((quotient.x << 1) | selected,
                                (quotient.y << 1) | (quotient.x >> 31));
                    }
                    {
                        uint bit = (incoming >> 2u) & 1u;
                        remainder = uvec2((remainder.x << 1) | bit,
                                (remainder.y << 1) | (remainder.x >> 31));
                        uint selected = wg_u64_less(divisor, remainder) ||
                                wg_u64_equal(divisor, remainder) ? 1u : 0u;
                        if (selected != 0u) remainder = wg_u64_sub(remainder, divisor);
                        quotient = uvec2((quotient.x << 1) | selected,
                                (quotient.y << 1) | (quotient.x >> 31));
                    }
                    {
                        uint bit = (incoming >> 1u) & 1u;
                        remainder = uvec2((remainder.x << 1) | bit,
                                (remainder.y << 1) | (remainder.x >> 31));
                        uint selected = wg_u64_less(divisor, remainder) ||
                                wg_u64_equal(divisor, remainder) ? 1u : 0u;
                        if (selected != 0u) remainder = wg_u64_sub(remainder, divisor);
                        quotient = uvec2((quotient.x << 1) | selected,
                                (quotient.y << 1) | (quotient.x >> 31));
                    }
                    {
                        uint bit = incoming & 1u;
                        remainder = uvec2((remainder.x << 1) | bit,
                                (remainder.y << 1) | (remainder.x >> 31));
                        uint selected = wg_u64_less(divisor, remainder) ||
                                wg_u64_equal(divisor, remainder) ? 1u : 0u;
                        if (selected != 0u) remainder = wg_u64_sub(remainder, divisor);
                        quotient = uvec2((quotient.x << 1) | selected,
                                (quotient.y << 1) | (quotient.x >> 31));
                    }
                    return uvec4(remainder, quotient);
                }
                """;
        String compacted = header(localSize) + CHUNK_HELPERS + function;
        return compacted + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    uint base = index * 16u;
                    uvec4 state = uvec4(
                            uvec2(inputBits[base + 6u], inputBits[base + 7u]),
                            uvec2(inputBits[base + 8u], inputBits[base + 9u]));
                    uvec4 updated = wg_stage_fp64_div_chunk(state,
                            uvec2(inputBits[base + 4u], inputBits[base + 5u]),
                            (inputBits[base + %du] >> %du) & 15u);
                    outputBits[base] = inputBits[base];
                    outputBits[base + 1u] = inputBits[base + 1u];
                    outputBits[base + 2u] = inputBits[base + 2u];
                    outputBits[base + 3u] = inputBits[base + 3u];
                    outputBits[base + 4u] = inputBits[base + 4u];
                    outputBits[base + 5u] = inputBits[base + 5u];
                    outputBits[base + 6u] = updated.x;
                    outputBits[base + 7u] = updated.y;
                    outputBits[base + 8u] = updated.z;
                    outputBits[base + 9u] = updated.w;
                    outputBits[base + 10u] = inputBits[base + 10u];
                    outputBits[base + 11u] = inputBits[base + 11u];
                    outputBits[base + 12u] = inputBits[base + 12u];
                    outputBits[base + 13u] = inputBits[base + 13u];
                    outputBits[base + 14u] = inputBits[base + 14u];
                    outputBits[base + 15u] = inputBits[base + 15u];
                }
                """.formatted(wordOffset, highestBit - wordOffset * 32 - 3);
    }

    public static String finishSource(int localSize) {
        String function = """
                uvec2 wg_stage_fp64_div_finish(uvec2 remainder, uvec2 quotient,
                                               int leftExponent, int rightExponent,
                                               uint sign, uint mode, uvec2 special) {
                    if (mode != 0u) return special;
                    if (wg_u64_nonzero(remainder)) quotient.x |= 1u;
                    int exponent = leftExponent - rightExponent + 1023;
                    if ((quotient.y & 0x00800000u) == 0u) {
                        quotient = wg_u64_shl1(quotient);
                        exponent--;
                    }
                    return wg_fp64_pack(sign, exponent, quotient);
                }
                """;
        String compacted = header(localSize) + FINISH_HELPERS + function;
        return compacted + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    uint base = index * 16u;
                    uvec2 value = wg_stage_fp64_div_finish(
                            uvec2(inputBits[base + 6u], inputBits[base + 7u]),
                            uvec2(inputBits[base + 8u], inputBits[base + 9u]),
                            int(inputBits[base + 10u]) - 4096,
                            int(inputBits[base + 11u]) - 4096,
                            inputBits[base + 12u], inputBits[base + 13u],
                            uvec2(inputBits[base + 14u], inputBits[base + 15u]));
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
                """;
    }

}

