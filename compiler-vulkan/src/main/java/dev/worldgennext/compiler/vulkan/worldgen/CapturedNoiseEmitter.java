// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan.worldgen;

import dev.worldgennext.semantic.program.ValueType;
import dev.worldgennext.semantic.snapshot.BlendedNoiseParameters;
import dev.worldgennext.semantic.snapshot.NoiseParameters;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Emits the captured, seed-expanded 1.21.1 NormalNoise path.
 *
 * <p>The tables are immutable compiler inputs.  The generated code still
 * evaluates the octave stack, permutation lookups, gradients, interpolation
 * and final normalization on the device; no precomputed density values cross
 * the shader boundary.  All values use the integer binary64 carrier supplied
 * by {@link IntegerIeeeEmitter}.</p>
 */
final class CapturedNoiseEmitter {
    private final StringBuilder source = new StringBuilder();
    private final Map<NoiseParameters.CapturedNoise, String> normalNames = new HashMap<>();
    private final Map<NoiseParameters.PerlinNoiseSnapshot, String> perlinNames = new HashMap<>();
    private final Map<BlendedNoiseParameters, String> blendedNames = new HashMap<>();
    private final Map<NoiseParameters.PerlinNoiseSnapshot, Map<Integer, String>> legacyNames = new HashMap<>();
    private final Map<NoiseParameters.ImprovedNoiseSnapshot, String> improvedNames = new HashMap<>();
    private final Map<NoiseParameters.ImprovedNoiseSnapshot, String> smearedNames = new HashMap<>();
    private boolean commonEmitted;
    private int nextName;

    String source() {
        return source.toString();
    }

    String emit(NoiseParameters.CapturedNoise captured, double xzScale, double yScale, ValueType type) {
        Objects.requireNonNull(captured, "captured");
        if (type != ValueType.FP32 && type != ValueType.FP64) {
            throw new UnsupportedOperationException("Captured noise requires FP32 or FP64 output");
        }
        emitCommon();
        String normal = normalNames.computeIfAbsent(captured, this::appendNormal);
        String x = "wg_fp64_mul(wg_fp64_from_int(point.x), " + fp64Literal(xzScale) + ")";
        String y = "wg_fp64_mul(wg_fp64_from_int(point.y), " + fp64Literal(yScale) + ")";
        String z = "wg_fp64_mul(wg_fp64_from_int(point.z), " + fp64Literal(xzScale) + ")";
        return convert(normal + "(" + x + ", " + y + ", " + z + ")", type);
    }

    String emitRaw(NoiseParameters.CapturedNoise captured, String x, String y, String z) {
        Objects.requireNonNull(captured, "captured");
        emitCommon();
        String normal = normalNames.computeIfAbsent(captured, this::appendNormal);
        return normal + "(" + x + ", " + y + ", " + z + ")";
    }

    String emitBlended(BlendedNoiseParameters parameters, ValueType type) {
        if (type != ValueType.FP32 && type != ValueType.FP64) {
            throw new UnsupportedOperationException("Blended noise requires FP32 or FP64 output");
        }
        return convert(emitBlendedFunction(parameters) + "(point)", type);
    }

    String emitBlendedFunction(BlendedNoiseParameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        emitCommon();
        return blendedNames.computeIfAbsent(parameters, this::appendBlended);
    }

    String convert(String rawValue, ValueType type) {
        return type == ValueType.FP32 ? "wg_fp64_to_fp32(" + rawValue + ")" : rawValue;
    }

    private void emitCommon() {
        if (commonEmitted) return;
        commonEmitted = true;
        source.append("""

                // Captured NormalNoise helpers.  These are raw binary64 operations;
                // no GLSL floating-point type is used in the GPU_IEEE_BITS profile.
                uvec2 wg_noise_smoothstep(uvec2 value) {
                    uvec2 squared = wg_fp64_mul(value, value);
                    uvec2 cubed = wg_fp64_mul(squared, value);
                    uvec2 inner = wg_fp64_add(
                            wg_fp64_mul(value, wg_fp64_sub(wg_fp64_mul(value, uvec2(0u, 0x40180000u)), uvec2(0u, 0x402e0000u))),
                            uvec2(0u, 0x40240000u));
                    return wg_fp64_mul(cubed, inner);
                }

                uvec2 wg_noise_wrap(uvec2 value) {
                    // PerlinNoise.wrap uses 3.3554432E7, exactly 2^25.
                    uvec2 period = uvec2(0u, 0x41800000u);
                    uvec2 quotient = wg_fp64_floor(wg_fp64_add(
                            wg_fp64_div(value, period), uvec2(0u, 0x3fe00000u)));
                    return wg_fp64_sub(value, wg_fp64_mul(quotient, period));
                }

                uvec2 wg_noise_gradient(uint hash, uvec2 x, uvec2 y, uvec2 z) {
                    switch (hash & 15u) {
                        case 0u: return wg_fp64_add(x, y);
                        case 1u: return wg_fp64_add(wg_fp64_negate(x), y);
                        case 2u: return wg_fp64_add(x, wg_fp64_negate(y));
                        case 3u: return wg_fp64_add(wg_fp64_negate(x), wg_fp64_negate(y));
                        case 4u: return wg_fp64_add(x, z);
                        case 5u: return wg_fp64_add(wg_fp64_negate(x), z);
                        case 6u: return wg_fp64_add(x, wg_fp64_negate(z));
                        case 7u: return wg_fp64_add(wg_fp64_negate(x), wg_fp64_negate(z));
                        case 8u: return wg_fp64_add(y, z);
                        case 9u: return wg_fp64_add(wg_fp64_negate(y), z);
                        case 10u: return wg_fp64_add(y, wg_fp64_negate(z));
                        case 11u: return wg_fp64_add(wg_fp64_negate(y), wg_fp64_negate(z));
                        case 12u: return wg_fp64_add(x, y);
                        case 13u: return wg_fp64_add(wg_fp64_negate(y), z);
                        case 14u: return wg_fp64_add(wg_fp64_negate(x), y);
                        default: return wg_fp64_add(wg_fp64_negate(y), wg_fp64_negate(z));
                    }
                }

                // Keep the expensive gradient/interpolation body shared by
                // every captured octave. Each octave still performs its own
                // on-device permutation lookups and supplies its own hashes;
                // this is only a code-sharing boundary, not a CPU sample.
                uvec2 wg_noise_interpolate(uvec2 fx, uvec2 gradientFy, uvec2 blendFy, uvec2 fz,
                        uint h000, uint h100, uint h010, uint h110,
                        uint h001, uint h101, uint h011, uint h111) {
                    // ImprovedNoise.noise(x, y, z, yScale, yMax) smears only
                    // the Y coordinate supplied to the gradient dot products.
                    // Its sampleAndLerp call still smoothsteps the original
                    // fractional Y (d4), not d4 - d6. Keep both carriers
                    // explicit; using gradientFy for both changes every
                    // smeared BlendedNoise octave.
                    uvec2 v000 = wg_noise_gradient(h000, fx, gradientFy, fz);
                    uvec2 v100 = wg_noise_gradient(h100,
                            wg_fp64_sub(fx, uvec2(0u, 0x3ff00000u)), gradientFy, fz);
                    uvec2 v010 = wg_noise_gradient(h010, fx,
                            wg_fp64_sub(gradientFy, uvec2(0u, 0x3ff00000u)), fz);
                    uvec2 v110 = wg_noise_gradient(h110,
                            wg_fp64_sub(fx, uvec2(0u, 0x3ff00000u)),
                            wg_fp64_sub(gradientFy, uvec2(0u, 0x3ff00000u)), fz);
                    uvec2 v001 = wg_noise_gradient(h001, fx, gradientFy,
                            wg_fp64_sub(fz, uvec2(0u, 0x3ff00000u)));
                    uvec2 v101 = wg_noise_gradient(h101,
                            wg_fp64_sub(fx, uvec2(0u, 0x3ff00000u)), gradientFy,
                            wg_fp64_sub(fz, uvec2(0u, 0x3ff00000u)));
                    uvec2 v011 = wg_noise_gradient(h011, fx,
                            wg_fp64_sub(gradientFy, uvec2(0u, 0x3ff00000u)),
                            wg_fp64_sub(fz, uvec2(0u, 0x3ff00000u)));
                    uvec2 v111 = wg_noise_gradient(h111,
                            wg_fp64_sub(fx, uvec2(0u, 0x3ff00000u)),
                            wg_fp64_sub(gradientFy, uvec2(0u, 0x3ff00000u)),
                            wg_fp64_sub(fz, uvec2(0u, 0x3ff00000u)));
                    uvec2 blendX = wg_noise_smoothstep(fx), blendY = wg_noise_smoothstep(blendFy),
                            blendZ = wg_noise_smoothstep(fz);
                    uvec2 x00 = wg_fp64_lerp(v000, v100, blendX),
                            x10 = wg_fp64_lerp(v010, v110, blendX);
                    uvec2 x01 = wg_fp64_lerp(v001, v101, blendX),
                            x11 = wg_fp64_lerp(v011, v111, blendX);
                    return wg_fp64_lerp(wg_fp64_lerp(x00, x10, blendY),
                            wg_fp64_lerp(x01, x11, blendY), blendZ);
                }

                // Share the coordinate, permutation and interpolation body
                // across captured octaves. The table remains an immutable
                // per-caller input; no noise sample is computed on the host.
                //
                // Permutations are packed four bytes per uint. Keeping the
                // compact representation at this boundary matters for the
                // large captured Overworld graph: every reachable octave
                // otherwise contributes a 256-element constant array to each
                // staged module and causes shaderc/driver memory to grow far
                // faster than the actual arithmetic.
                // Keep the small packed-table helpers out of the driver's
                // broad wg_ no-inline policy.  NVIDIA's no-inline path can
                // mishandle an array parameter even when the packed bytes
                // are otherwise identical to the legacy 256-entry table.
                uint noise_table_value(in uint permutation[64], uint index) {
                    uint packed = permutation[index >> 2u];
                    return (packed >> ((index & 3u) * 8u)) & 255u;
                }

                uvec2 noise_sample(uvec2 x, uvec2 y, uvec2 z,
                        uvec2 xOffset, uvec2 yOffset, uvec2 zOffset,
                        uvec2 yScale, uvec2 yMax, bool smear,
                        in uint permutation[64]) {
                    uvec2 shiftedX = wg_fp64_add(x, xOffset);
                    uvec2 shiftedY = wg_fp64_add(y, yOffset);
                    uvec2 shiftedZ = wg_fp64_add(z, zOffset);
                    int x0 = wg_fp64_floor_to_i32(shiftedX);
                    int y0 = wg_fp64_floor_to_i32(shiftedY);
                    int z0 = wg_fp64_floor_to_i32(shiftedZ);
                    uvec2 fx = wg_fp64_sub(shiftedX, wg_fp64_from_int(x0));
                    uvec2 fy = wg_fp64_sub(shiftedY, wg_fp64_from_int(y0));
                    uvec2 fz = wg_fp64_sub(shiftedZ, wg_fp64_from_int(z0));
                    uvec2 adjustedFy = fy;
                    if (smear && !wg_fp64_zero(yScale)) {
                        uvec2 clamped = (wg_fp64_less_equal(uvec2(0u), yMax)
                                && wg_fp64_less(yMax, fy)) ? yMax : fy;
                        uvec2 ySmear = wg_fp64_mul(wg_fp64_floor(wg_fp64_add(
                                wg_fp64_div(clamped, yScale), uvec2(0xa0000000u, 0x3e7ad7f2u))), yScale);
                        adjustedFy = wg_fp64_sub(fy, ySmear);
                    }
                    uint xKey = wg_i32_to_bits(x0), yKey = wg_i32_to_bits(y0),
                            zKey = wg_i32_to_bits(z0);
                    uint xHash = noise_table_value(permutation, xKey & 255u);
                    uint xHashNext = noise_table_value(permutation, (xKey + 1u) & 255u);
                    uint xy00 = noise_table_value(permutation, (xHash + yKey) & 255u);
                    uint xy01 = noise_table_value(permutation, (xHash + yKey + 1u) & 255u);
                    uint xy10 = noise_table_value(permutation, (xHashNext + yKey) & 255u);
                    uint xy11 = noise_table_value(permutation, (xHashNext + yKey + 1u) & 255u);
                    return wg_noise_interpolate(fx, adjustedFy, fy, fz,
                            noise_table_value(permutation, (xy00 + zKey) & 255u),
                            noise_table_value(permutation, (xy10 + zKey) & 255u),
                            noise_table_value(permutation, (xy01 + zKey) & 255u),
                            noise_table_value(permutation, (xy11 + zKey) & 255u),
                            noise_table_value(permutation, (xy00 + zKey + 1u) & 255u),
                            noise_table_value(permutation, (xy10 + zKey + 1u) & 255u),
                            noise_table_value(permutation, (xy01 + zKey + 1u) & 255u),
                            noise_table_value(permutation, (xy11 + zKey + 1u) & 255u));
                }
                """);
    }

    private String appendNormal(NoiseParameters.CapturedNoise captured) {
        String first = appendPerlin(captured.first());
        String second = appendPerlin(captured.second());
        String name = next("wg_noise_normal_");
        String factor = fp64Literal(1.0181268882175227);
        String valueFactor = fp64Literal(captured.valueFactor());
        source.append("uvec2 ").append(name).append("(uvec2 x, uvec2 y, uvec2 z) {\n")
                .append("    uvec2 first = ").append(first).append("(x, y, z);\n")
                .append("    uvec2 second = ").append(second).append("(wg_fp64_mul(x, ").append(factor)
                .append("), wg_fp64_mul(y, ").append(factor).append("), wg_fp64_mul(z, ").append(factor).append("));\n")
                .append("    return wg_fp64_mul(wg_fp64_add(first, second), ").append(valueFactor).append(");\n")
                .append("}\n");
        return name;
    }

    private String appendPerlin(NoiseParameters.PerlinNoiseSnapshot noise) {
        String existing = perlinNames.get(noise);
        if (existing != null) return existing;
        String name = next("wg_noise_perlin_");
        // PerlinNoise.firstOctave is Minecraft's signed lowest octave. Its
        // initial input scale is therefore 2^firstOctave.
        String inputFactor = fp64Literal(powerOfTwo(noise.firstOctave(), "input"));
        int count = noise.levels().size();
        double value = Math.scalb(1.0, count - 1) / (Math.scalb(1.0, count) - 1.0);
        if (!Double.isFinite(value)) throw new UnsupportedOperationException("Captured Perlin value factor is not finite");
        String valueFactor = fp64Literal(value);
        String[] levelNames = new String[count];
        for (int index = 0; index < count; index++) {
            var level = noise.levels().get(index);
            if (level != null) levelNames[index] = appendImproved(level);
        }
        source.append("uvec2 ").append(name).append("(uvec2 x, uvec2 y, uvec2 z) {\n")
                .append("    uvec2 result = uvec2(0u);\n")
                .append("    uvec2 inputFactor = ").append(inputFactor).append(";\n")
                .append("    uvec2 valueFactor = ").append(valueFactor).append(";\n");
        for (int index = 0; index < count; index++) {
            var level = noise.levels().get(index);
            if (level != null) {
                String improved = levelNames[index];
                String sample = improved + "(wg_noise_wrap(wg_fp64_mul(x, inputFactor)), "
                        + "wg_noise_wrap(wg_fp64_mul(y, inputFactor)), "
                        + "wg_noise_wrap(wg_fp64_mul(z, inputFactor)))";
                String amplitude = fp64Literal(noise.amplitudes().get(index));
                source.append("    uvec2 sampled").append(index).append(" = ").append(sample).append(";\n")
                        .append("    uvec2 weighted").append(index).append(" = wg_fp64_mul(wg_fp64_mul(")
                        .append(amplitude).append(", sampled").append(index).append("), valueFactor);\n")
                        .append("    result = wg_fp64_add(result, weighted").append(index).append(");\n");
            }
            source.append("    inputFactor = wg_fp64_mul(inputFactor, uvec2(0u, 0x40000000u));\n")
                    .append("    valueFactor = wg_fp64_mul(valueFactor, uvec2(0u, 0x3fe00000u));\n");
        }
        source.append("    return result;\n}\n");
        perlinNames.put(noise, name);
        return name;
    }

    /** Emit one legacy BlendedNoise octave where octave 0 addresses the last captured level. */
    private String appendLegacyPerlin(NoiseParameters.PerlinNoiseSnapshot noise, int octave) {
        if (!hasLegacyLevel(noise, octave)) {
            throw new IllegalArgumentException("Cannot emit an absent legacy Perlin octave: " + octave);
        }
        Map<Integer, String> octaveNames = legacyNames.computeIfAbsent(noise, ignored -> new HashMap<>());
        String existing = octaveNames.get(octave);
        if (existing != null) return existing;
        String name = next("wg_noise_legacy_perlin_");
        int levelIndex = noise.levels().size() - 1 - octave;
        String levelName = appendSmearedImproved(noise.levels().get(levelIndex));
        source.append("uvec2 ").append(name).append("(uvec2 x, uvec2 y, uvec2 z, uvec2 yScale, uvec2 yMax) {\n")
                .append("    return ").append(levelName).append("(x, y, z, yScale, yMax);\n}\n");
        octaveNames.put(octave, name);
        return name;
    }

    private String appendBlended(BlendedNoiseParameters parameters) {
        String name = next("wg_noise_blended_");
        String xzMultiplier = fp64Literal(684.412 * parameters.xzScale());
        String yMultiplier = fp64Literal(684.412 * parameters.yScale());
        String xzFactor = fp64Literal(parameters.xzFactor());
        String yFactor = fp64Literal(parameters.yFactor());
        String smearMultiplier = fp64Literal(parameters.smearScaleMultiplier());
        String one = fp64Literal(1.0);
        String half = fp64Literal(0.5);
        String ten = fp64Literal(10.0);
        String oneTwentyEight = fp64Literal(128.0);
        String fiveHundredTwelve = fp64Literal(512.0);
        StringBuilder body = new StringBuilder()
                .append("uvec2 ").append(name).append("(ivec3 point) {\n")
                .append("    uvec2 xzMultiplier = ").append(xzMultiplier).append(", yMultiplier = ").append(yMultiplier).append(";\n")
                .append("    uvec2 d0 = wg_fp64_mul(wg_fp64_from_int(point.x), xzMultiplier);\n")
                .append("    uvec2 d1 = wg_fp64_mul(wg_fp64_from_int(point.y), yMultiplier);\n")
                .append("    uvec2 d2 = wg_fp64_mul(wg_fp64_from_int(point.z), xzMultiplier);\n")
                .append("    uvec2 d3 = wg_fp64_div(d0, ").append(xzFactor).append("), d4 = wg_fp64_div(d1, ").append(yFactor)
                .append("), d5 = wg_fp64_div(d2, ").append(xzFactor).append(");\n")
                .append("    uvec2 d6 = wg_fp64_mul(yMultiplier, ").append(smearMultiplier).append("), d7 = wg_fp64_div(d6, ")
                .append(yFactor).append(");\n")
                .append("    uvec2 d8 = uvec2(0u), d9 = uvec2(0u), d10 = uvec2(0u), d11 = ").append(one).append(";\n");
        for (int octave = 0; octave < 8; octave++) {
            if (hasLegacyLevel(parameters.mainNoise(), octave)) {
                String main = appendLegacyPerlin(parameters.mainNoise(), octave);
                body.append("    uvec2 legacySample").append(octave).append(" = ").append(main)
                        .append("(wg_noise_wrap(wg_fp64_mul(d3, d11)),\n")
                        .append("            wg_noise_wrap(wg_fp64_mul(d4, d11)), wg_noise_wrap(wg_fp64_mul(d5, d11)),\n")
                        .append("            wg_fp64_mul(d7, d11), wg_fp64_mul(d4, d11));\n")
                        .append("    d10 = wg_fp64_add(d10, wg_fp64_div(legacySample").append(octave).append(", d11));\n");
            }
            body.append("    d11 = wg_fp64_mul(d11, ").append(half).append(");\n");
        }
        body.append("    uvec2 d16 = wg_fp64_mul(wg_fp64_add(wg_fp64_div(d10, ").append(ten)
                .append("), ").append(one).append("), ").append(half).append(");\n")
                .append("    bool skipMin = wg_fp64_less_equal(").append(one).append(", d16);\n")
                .append("    bool skipMax = wg_fp64_less_equal(d16, uvec2(0u));\n")
                .append("    d11 = ").append(one).append(";\n")
                .append("    uvec2 d12, d13, d14, d15, yMax;\n");
        for (int octave = 0; octave < 16; octave++) {
            body.append("    d12 = wg_noise_wrap(wg_fp64_mul(d0, d11));\n")
                    .append("    d13 = wg_noise_wrap(wg_fp64_mul(d1, d11));\n")
                    .append("    d14 = wg_noise_wrap(wg_fp64_mul(d2, d11));\n")
                    .append("    d15 = wg_fp64_mul(d6, d11); yMax = wg_fp64_mul(d1, d11);\n");
            if (hasLegacyLevel(parameters.minLimitNoise(), octave)) {
                String min = appendLegacyPerlin(parameters.minLimitNoise(), octave);
                body.append("    if (!skipMin) d8 = wg_fp64_add(d8, wg_fp64_div(").append(min)
                        .append("(d12, d13, d14, d15, yMax), d11));\n");
            }
            if (hasLegacyLevel(parameters.maxLimitNoise(), octave)) {
                String max = appendLegacyPerlin(parameters.maxLimitNoise(), octave);
                body.append("    if (!skipMax) d9 = wg_fp64_add(d9, wg_fp64_div(").append(max)
                        .append("(d12, d13, d14, d15, yMax), d11));\n");
            }
            body.append("    d11 = wg_fp64_mul(d11, ").append(half).append(");\n");
        }
        body.append("    uvec2 lower = wg_fp64_div(d8, ").append(fiveHundredTwelve).append("), upper = wg_fp64_div(d9, ")
                .append(fiveHundredTwelve).append(");\n")
                .append("    uvec2 blend = wg_fp64_max(uvec2(0u), wg_fp64_min(").append(one).append(", d16));\n")
                .append("    return wg_fp64_div(wg_fp64_lerp(lower, upper, blend), ").append(oneTwentyEight).append(");\n")
                .append("}\n");
        source.append(body);
        return name;
    }

    private static boolean hasLegacyLevel(NoiseParameters.PerlinNoiseSnapshot noise, int octave) {
        int levelIndex = noise.levels().size() - 1 - octave;
        return levelIndex >= 0 && levelIndex < noise.levels().size() && noise.levels().get(levelIndex) != null;
    }

    private String appendImproved(NoiseParameters.ImprovedNoiseSnapshot noise) {
        String existing = improvedNames.get(noise);
        if (existing != null) return existing;
        String suffix = Integer.toString(nextName++);
        String permutationName = "wg_noise_perm_" + suffix;
        source.append("const uint ").append(permutationName).append("[64] = uint[64](");
        for (int index = 0; index < noise.permutation().size(); index += 4) {
            if (index > 0) source.append(',');
            long packed = 0;
            for (int byteIndex = 0; byteIndex < 4; byteIndex++) {
                packed |= (long) noise.permutation().get(index + byteIndex) << (byteIndex * 8);
            }
            source.append(String.format(Locale.ROOT, "0x%08xu", packed));
        }
        source.append(");\n");

        String name = "wg_noise_improved_" + suffix;
        source.append("uvec2 ").append(name).append("(uvec2 x, uvec2 y, uvec2 z) {\n")
                .append("    return noise_sample(x, y, z, ")
                .append(fp64Literal(noise.xOffset())).append(", ")
                .append(fp64Literal(noise.yOffset())).append(", ")
                .append(fp64Literal(noise.zOffset())).append(", uvec2(0u), uvec2(0u), false, ")
                .append(permutationName).append(");\n")
                .append("}\n");
        improvedNames.put(noise, name);
        return name;
    }

    /** Emit ImprovedNoise.noise(x, y, z, yScale, yMax) for legacy blending. */
    private String appendSmearedImproved(NoiseParameters.ImprovedNoiseSnapshot noise) {
        String existing = smearedNames.get(noise);
        if (existing != null) return existing;
        String suffix = Integer.toString(nextName++);
        String permutationName = "wg_noise_smeared_perm_" + suffix;
        source.append("const uint ").append(permutationName).append("[64] = uint[64](");
        for (int index = 0; index < noise.permutation().size(); index += 4) {
            if (index > 0) source.append(',');
            long packed = 0;
            for (int byteIndex = 0; byteIndex < 4; byteIndex++) {
                packed |= (long) noise.permutation().get(index + byteIndex) << (byteIndex * 8);
            }
            source.append(String.format(Locale.ROOT, "0x%08xu", packed));
        }
        source.append(");\n");

        String name = "wg_noise_smeared_" + suffix;
        source.append("uvec2 ").append(name).append("(uvec2 x, uvec2 y, uvec2 z, uvec2 yScale, uvec2 yMax) {\n")
                .append("    return noise_sample(x, y, z, ")
                .append(fp64Literal(noise.xOffset())).append(", ")
                .append(fp64Literal(noise.yOffset())).append(", ")
                .append(fp64Literal(noise.zOffset())).append(", yScale, yMax, true, ")
                .append(permutationName).append(");\n")
                .append("}\n");
        smearedNames.put(noise, name);
        return name;
    }

    private String next(String prefix) {
        return prefix + nextName++;
    }

    private static double powerOfTwo(int exponent, String name) {
        double value = Math.scalb(1.0, exponent);
        if (!Double.isFinite(value)) throw new UnsupportedOperationException("Captured Perlin " + name + " factor is not finite");
        return value;
    }

    private static String fp64Literal(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid binary64 literal");
        long bits = Double.doubleToRawLongBits(value);
        return "uvec2(" + raw((int) bits) + ", " + raw((int) (bits >>> 32)) + ")";
    }

    private static String raw(int bits) {
        return "0x" + Integer.toUnsignedString(bits, 16) + "u";
    }
}
