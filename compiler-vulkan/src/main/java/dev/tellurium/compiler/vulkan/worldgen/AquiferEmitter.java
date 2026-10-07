// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.worldgen;

import dev.tellurium.semantic.material.AquiferProgram;
import java.util.List;
import java.util.Objects;

/**
 * Emits the device-side portion of the 1.21.1 aquifer decision.
 *
 * <p>The host may capture cell centres and immutable fluid statuses, but it
 * must not capture the answer for every block. This emitter therefore keeps
 * the nearest-three selection, similarity/pressure tests and barrier lookup
 * in the shader. A candidate is a status (level plus fluid identity), not a
 * precomputed block material.</p>
 */
public final class AquiferEmitter {
    /** One captured cell candidate and its immutable fluid status. */
    public record Candidate(int x, int y, int z, int level, int stateId, boolean fluid) {
        public Candidate(int x, int y, int z, int level, int stateId) {
            this(x, y, z, level, stateId, true);
        }

        public Candidate {
            if (stateId < 0) throw new IllegalArgumentException("Aquifer state ID must be non-negative");
        }
    }

    /**
     * Immutable upload description. The current dense ABI embeds this bounded
     * table as constant GLSL arrays and iterates it on the device; a future
     * SSBO path can preserve the same fields without changing the decision
     * contract.
     *
     * @param seaLevel captured global water level
     * @param globalLavaLevel captured global lava threshold (1.21.1 uses -54)
     * @param waterStateId result-local water identity used for water/lava pressure
     * @param lavaStateId result-local lava identity used for global fluid and pressure
     */
    public record Options(boolean enabled, int cellSize, List<Candidate> candidates,
                          int seaLevel, int globalLavaLevel,
                          int waterStateId, int lavaStateId,
                          boolean defaultFluidFallback) {
        /** Compatibility constructor for the original captured-cell smoke. */
        public Options(boolean enabled, int cellSize, List<Candidate> candidates) {
            this(enabled, cellSize, candidates, 63, AquiferProgram.VANILLA_GLOBAL_LAVA_LEVEL, 0, 0, false);
        }

        /** Compatibility constructor for an enabled captured aquifer. */
        public Options(boolean enabled, int cellSize, List<Candidate> candidates,
                       int seaLevel, int globalLavaLevel, int waterStateId, int lavaStateId) {
            this(enabled, cellSize, candidates, seaLevel, globalLavaLevel,
                    waterStateId, lavaStateId, false);
        }

        public Options {
            if (cellSize <= 0) throw new IllegalArgumentException("Aquifer cell size must be positive");
            candidates = List.copyOf(candidates == null ? List.of() : candidates);
            if (candidates.size() > 4096) throw new IllegalArgumentException("Aquifer capture is too large");
            if (enabled && candidates.isEmpty()) throw new IllegalArgumentException("Enabled aquifer requires captured candidates");
            if (!enabled && !candidates.isEmpty()) throw new IllegalArgumentException("Disabled aquifer cannot carry candidates");
            if (defaultFluidFallback && enabled) throw new IllegalArgumentException("Default-fluid fallback cannot enable aquifer candidates");
            if (defaultFluidFallback && (waterStateId < 0 || lavaStateId < 0)) {
                throw new IllegalArgumentException("Default-fluid fallback requires fluid state IDs");
            }
            if (enabled && (waterStateId < 0 || lavaStateId < 0)) {
                throw new IllegalArgumentException("Aquifer fluid state IDs must be non-negative");
            }
        }

        public static Options disabled() {
            return new Options(false, 16, List.of(), 63, AquiferProgram.VANILLA_GLOBAL_LAVA_LEVEL, 0, 0, false);
        }

        /** Emits Minecraft's disabled-aquifer global-fluid picker path. */
        public static Options defaultFluid(int seaLevel, int globalLavaLevel,
                                            int defaultFluidStateId, int lavaStateId) {
            return new Options(false, 16, List.of(), seaLevel, globalLavaLevel,
                    defaultFluidStateId, lavaStateId, true);
        }
    }

    /** Structured result prevents callers from confusing source text with its entry point. */
    public record Emission(String source, String entryPoint) {
        public Emission {
            Objects.requireNonNull(source, "source");
            if (entryPoint != null && entryPoint.isBlank()) throw new IllegalArgumentException("Blank aquifer entry point");
        }
    }

    public Emission emit(Options options) {
        return emit(options, 0, null, null);
    }

    /** Emit using a compiler-owned suffix so independent fragments cannot collide. */
    public Emission emit(Options options, int suffix) {
        return emit(options, suffix, null, null);
    }

    /**
     * Emit the exact pressure path with a captured barrier root. The barrier
     * function must return an integer-carrier FP32 or FP64 value; FP32 is
     * widened inside the helper before it participates in pressure.
     */
    public Emission emit(Options options, int suffix, String barrierFunction,
                          WorldgenShaderCompiler.BarrierType barrierType) {
        return emit(options, suffix, barrierFunction, barrierType, false);
    }

    /**
     * Emit the aquifer consumer with an optional device-produced barrier
     * carrier. The carrier form keeps the captured barrier graph out of the
     * standalone aquifer stage; the legacy two-argument ABI remains the
     * default for existing callers and fixtures.
     */
    public Emission emit(Options options, int suffix, String barrierFunction,
                         WorldgenShaderCompiler.BarrierType barrierType,
                         boolean externalBarrierInput) {
        Objects.requireNonNull(options, "options");
        if (suffix < 0) throw new IllegalArgumentException("Aquifer function suffix must be non-negative");
        if (!options.enabled() && !options.defaultFluidFallback()) return new Emission("", null);
        if (!options.enabled()) return emitDefaultFluid(options, suffix);
        if (barrierFunction == null || barrierFunction.isBlank() || barrierType == null) {
            throw new IllegalArgumentException("Enabled aquifer requires a captured barrier root");
        }

        String name = "wg_aquifer_" + suffix;
        String similarity = "wg_aquifer_similarity_" + suffix;
        String pressure = "wg_aquifer_pressure_" + suffix;
        // Diagnostic-only scalar trace used while qualifying the exact
        // pressure consumer on the target driver.  It is intentionally
        // opt-in and changes the aquifer ABI only for a fail-fast probe.
        boolean debugTrace = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugAquiferTrace", "false"));
        boolean debugMultiply = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugAquiferMultiply", "false"));
        boolean debugRationalComparator = Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.debugAquiferRationalComparator", "false"));
        boolean integerPressureDraft = debugTrace || Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.exactAquiferStage", "false"));
        boolean rationalPressure = integerPressureDraft && !debugTrace;
        StringBuilder source = new StringBuilder();
        source.append("// Captured aquifer statuses; cellSize=").append(options.cellSize())
                .append(", nearestThree=true, devicePressure=true\n");
        if (!rationalPressure) {
            source.append("uvec2 ").append(similarity).append("(uvec2 first, uvec2 second) {\n")
                    .append("    uvec2 delta = wg_u64_less(first, second) ? wg_u64_sub(second, first) : wg_u64_sub(first, second);\n")
                    .append("    if (delta.y != 0u) { wg_failed = true; return wg_fp64_qnan(); }\n")
                    // This consumer only needs the exact 0..1 similarity band
                    // for pressure and the fixed schedule threshold.  The
                    // generic FP64 divide path is still too fragile on the
                    // target driver for these tiny integer ratios, so use the
                    // precomputed binary64 values from the vanilla 25-cell
                    // denominator. Pressure only needs 0..25; the schedule
                    // threshold still distinguishes deltas through 44.
                    .append("    if (delta.x >= 45u) return uvec2(0u, 0xbff00000u);\n");
            for (int delta = 0; delta <= 44; delta++) {
                long bits = Double.doubleToRawLongBits(1.0 - ((double) delta / 25.0));
                source.append("    if (delta.x == ").append(delta).append("u) return uvec2(")
                        .append(rawUInt((int) bits)).append(", ")
                        .append(rawUInt((int) (bits >>> 32))).append(");\n");
            }
            source.append("    return wg_fp64_qnan();\n")
                    .append("}\n");
        }

        boolean useExternalBarrier = externalBarrierInput && options.enabled();
        String barrier = useExternalBarrier ? null : barrierType == WorldgenShaderCompiler.BarrierType.FP64
                ? barrierFunction + "(point)"
                : "wg_fp64_from_fp32(" + barrierFunction + "(point))";
        String pressureDensityExpression = useExternalBarrier ? "pressureDensity" : "density";
        String pressureBarrierArgument = useExternalBarrier ? ", barrier" : "";
        String pressureExternalParameter = useExternalBarrier ? ", uvec2 externalBarrier" : "";
        // The full pressure carrier is retained only for the scalar trace.
        // The normal exact-stage route uses the integer pressure carrier below;
        // emitting this unused ratio table made the target driver spend minutes
        // compiling hundreds of literal branches before it could dispatch.
        if (debugTrace) {
            source.append("uvec2 wg_aquifer_half_").append(suffix).append("(int value) {\n")
                .append("    int whole = value / 2;\n")
                .append("    if ((value & 1) == 0) return wg_fp64_from_int(whole);\n")
                .append("    return wg_fp64_add(wg_fp64_from_int(whole), value < 0\n")
                .append("            ? uvec2(0u, 0xbfe00000u) : uvec2(0u, 0x3fe00000u));\n")
                .append("}\n");
        }
        if (debugTrace) {
        // The target driver returns zero for dynamic indexing into large
        // uvec2 constant tables in this exact stage.  Use a bounded literal
        // map instead. Values outside the map are saturated: those pressures
        // are already far beyond the +/-2 barrier and only their sign can
        // affect the material decision.
        int[] ratioDenominators = {3, 5, 6, 20};
        int ratioMinimum = -64;
        int ratioMaximum = 64;
        source.append("uvec2 wg_aquifer_ratio_").append(suffix)
                .append("(int numerator, int denominator) {\n");
        for (int denominator : ratioDenominators) {
            source.append("    if (denominator == ").append(denominator).append(") {\n");
            for (int numerator : new int[]{ratioMinimum, ratioMaximum}) {
                long bits = Double.doubleToRawLongBits((double) numerator / denominator);
                source.append("        if (numerator ").append(numerator == ratioMinimum ? "<=" : ">=")
                        .append(' ').append(numerator).append(" ) return uvec2(")
                        .append(rawUInt((int) bits)).append(", ")
                        .append(rawUInt((int) (bits >>> 32))).append(");\n");
            }
            for (int numerator = ratioMinimum + 1; numerator < ratioMaximum; numerator++) {
                long bits = Double.doubleToRawLongBits((double) numerator / denominator);
                source.append("        if (numerator == ").append(numerator).append(") return uvec2(")
                        .append(rawUInt((int) bits)).append(", ")
                        .append(rawUInt((int) (bits >>> 32))).append(");\n");
            }
            source.append("    }\n");
        }
        source.append("    return wg_fp64_div(wg_fp64_from_int(numerator), wg_fp64_from_int(denominator));\n")
                .append("}\n");
        } else if (!integerPressureDraft) {
            source.append("uvec2 wg_aquifer_ratio_").append(suffix)
                    .append("(int numerator, int denominator) {\n")
                    .append("    return wg_fp64_div(wg_fp64_from_int(numerator), wg_fp64_from_int(denominator));\n")
                    .append("}\n");
        }
        if (integerPressureDraft) {
        // Carry the pressure as a small rational until the final comparison.
        // The shared FP64 multiply is still being repaired separately; this
        // exact-stage path uses integer ratios for the Minecraft
        // similarity/pressure product. The external-barrier form also scales
        // the captured barrier with the same rational similarity factors;
        // the legacy exact form without an external carrier remains a
        // diagnostic containment route.
        source.append("ivec3 wg_aquifer_pressure_parts_").append(suffix)
                .append("(ivec3 point, int firstLevel, uint firstState, int secondLevel, uint secondState) {\n")
                .append("    if ((firstState == ").append(rawUInt(options.waterStateId()))
                .append(" && secondState == ").append(rawUInt(options.lavaStateId())).append(") ||\n")
                .append("        (firstState == ").append(rawUInt(options.lavaStateId()))
                .append(" && secondState == ").append(rawUInt(options.waterStateId())).append(")) return ivec3(2, 1, 0);\n")
                .append("    int difference = firstLevel >= secondLevel ? firstLevel - secondLevel : secondLevel - firstLevel;\n")
                .append("    if (difference == 0) return ivec3(0, 1, 0);\n")
                .append("    int offsetNumerator = point.y + point.y + 1 - (firstLevel + secondLevel);\n")
                .append("    int absoluteOffsetNumerator = offsetNumerator < 0 ? -offsetNumerator : offsetNumerator;\n")
                .append("    int pressureNumerator = difference - absoluteOffsetNumerator;\n")
                .append("    int numerator;\n")
                .append("    int denominator;\n")
                .append("    if (offsetNumerator > 0) {\n")
                .append("        numerator = pressureNumerator;\n")
                .append("        denominator = pressureNumerator > 0 ? 3 : 5;\n")
                .append("    } else {\n")
                .append("        int adjustedNumerator = 6 + pressureNumerator;\n")
                .append("        numerator = adjustedNumerator;\n")
                .append("        denominator = adjustedNumerator > 0 ? 6 : 20;\n")
                .append("    }\n")
                .append("    bool barrierEligible = numerator >= -2 * denominator && numerator <= 2 * denominator;\n")
                .append("    return ivec3(numerator + numerator, denominator, barrierEligible ? 1 : 0);\n")
                .append("}\n");
        source.append("uint wg_aquifer_similarity_numerator_").append(suffix)
                .append("(uvec2 first, uvec2 second) {\n")
                .append("    uvec2 delta = wg_u64_less(first, second) ? wg_u64_sub(second, first) : wg_u64_sub(first, second);\n")
                .append("    return delta.y == 0u && delta.x < 26u ? 25u - delta.x : 0u;\n")
                .append("}\n");
        source.append("uint wg_aquifer_similarity_delta_").append(suffix)
                .append("(uvec2 first, uvec2 second) {\n")
                .append("    uvec2 delta = wg_u64_less(first, second) ? wg_u64_sub(second, first) : wg_u64_sub(first, second);\n")
                .append("    return delta.y == 0u ? delta.x : 0xffffffffu;\n")
                .append("}\n");
        // Compare a finite positive binary64 carrier with a positive rational
        // without routing through the target driver's broken FP64 multiply or
        // divide. The mantissa*denominator product needs at most 128 bits;
        // aquifer numerators and denominators stay small enough for this
        // bounded helper.
        source.append("uvec4 wg_aquifer_mul_u64_u32_").append(suffix)
                .append("(uvec2 value, uint multiplier) {\n")
                .append("    uint mask = 0xffffu;\n")
                .append("    uint a0 = value.x & mask, a1 = value.x >> 16u;\n")
                .append("    uint a2 = value.y & mask, a3 = value.y >> 16u;\n")
                .append("    uint p = a0 * multiplier;\n")
                .append("    uint c0 = p & mask, carry = p >> 16u;\n")
                .append("    p = a1 * multiplier + carry;\n")
                .append("    uint c1 = p & mask; carry = p >> 16u;\n")
                .append("    p = a2 * multiplier + carry;\n")
                .append("    uint c2 = p & mask; carry = p >> 16u;\n")
                .append("    p = a3 * multiplier + carry;\n")
                .append("    uint c3 = p & mask, c4 = p >> 16u;\n")
                .append("    return uvec4(c0 | (c1 << 16u), c2 | (c3 << 16u), c4, 0u);\n")
                .append("}\n")
                .append(useExternalBarrier
                        ? "uvec2 wg_aquifer_scale_rational_" + suffix + "(uvec2 value, uint numerator, uint denominator) {\n"
                        + "    if (numerator == 0u || wg_fp64_zero(value)) return uvec2(0u, wg_fp64_sign(value) << 31);\n"
                        + "    if (!wg_fp64_finite(value)) return value;\n"
                        + "    uint exponent = wg_fp64_exp(value);\n"
                        + "    uvec2 mantissa = wg_fp64_frac(value);\n"
                        + "    if (exponent != 0u) mantissa.y |= 0x00100000u;\n"
                        + "    uvec4 product = wg_aquifer_mul_u64_u32_" + suffix + "(mantissa, numerator);\n"
                        + "    uint remainder = 0u;\n"
                        + "    uvec2 quotient = uvec2(0u);\n"
                        + "    for (int bit = 63; bit >= 0; bit--) {\n"
                        + "        uint incoming = bit >= 32 ? ((product.y >> uint(bit - 32)) & 1u) : ((product.x >> uint(bit)) & 1u);\n"
                        + "        remainder = (remainder << 1u) | incoming;\n"
                        + "        if (remainder >= denominator) {\n"
                        + "            remainder -= denominator;\n"
                        + "            if (bit >= 32) quotient.y |= 1u << uint(bit - 32);\n"
                        + "            else quotient.x |= 1u << uint(bit);\n"
                        + "        }\n"
                        + "    }\n"
                        + "    uvec2 extended = uvec2(quotient.x << 3u, (quotient.y << 3u) | (quotient.x >> 29u));\n"
                        + "    for (int bit = 2; bit >= 0; bit--) {\n"
                        + "        remainder <<= 1u;\n"
                        + "        if (remainder >= denominator) { remainder -= denominator; extended.x |= 1u << uint(bit); }\n"
                        + "    }\n"
                        + "    if (remainder != 0u) extended.x |= 1u;\n"
                        + "    return wg_fp64_pack(wg_fp64_sign(value), exponent == 0u ? 1 : int(exponent), extended);\n"
                        + "}\n"
                        : "")
                .append(useExternalBarrier
                        ? "uvec2 wg_aquifer_add_ordered_" + suffix + "(uvec2 large, uvec2 small) {\n"
                        + "    uint largeExponent = wg_fp64_exp(large), smallExponent = wg_fp64_exp(small);\n"
                        + "    int exponentLarge = int(largeExponent == 0u ? 1u : largeExponent);\n"
                        + "    int exponentSmall = int(smallExponent == 0u ? 1u : smallExponent);\n"
                        + "    uvec2 largeFraction = wg_fp64_frac(large), smallFraction = wg_fp64_frac(small);\n"
                        + "    uvec2 largeMantissa = wg_u64_shl3(uvec2(largeFraction.x, largeFraction.y | (largeExponent == 0u ? 0u : 0x00100000u)));\n"
                        + "    uvec2 smallMantissa = wg_u64_shl3(uvec2(smallFraction.x, smallFraction.y | (smallExponent == 0u ? 0u : 0x00100000u)));\n"
                        + "    smallMantissa = wg_u64_shr_sticky(smallMantissa, uint(exponentLarge - exponentSmall));\n"
                        + "    if (wg_fp64_sign(large) == wg_fp64_sign(small))\n"
                        + "        return wg_fp64_pack(wg_fp64_sign(large), exponentLarge, wg_u64_add(largeMantissa, smallMantissa));\n"
                        + "    uvec2 magnitude = wg_u64_sub(largeMantissa, smallMantissa);\n"
                        + "    if (!wg_u64_nonzero(magnitude)) return uvec2(0u);\n"
                        + "    return wg_fp64_pack(wg_fp64_sign(large), exponentLarge, magnitude);\n"
                        + "}\n"
                        + "uvec2 wg_aquifer_add_carriers_" + suffix + "(uvec2 left, uvec2 right) {\n"
                        + "    if (!wg_fp64_finite(left) || !wg_fp64_finite(right) || wg_fp64_zero(left) || wg_fp64_zero(right))\n"
                        + "        return wg_fp64_add(left, right);\n"
                        + "    uvec2 leftMagnitude = uvec2(left.x, left.y & 0x7fffffffu);\n"
                        + "    uvec2 rightMagnitude = uvec2(right.x, right.y & 0x7fffffffu);\n"
                        + "    if (wg_u64_less(leftMagnitude, rightMagnitude)) return wg_aquifer_add_ordered_" + suffix + "(right, left);\n"
                        + "    return wg_aquifer_add_ordered_" + suffix + "(left, right);\n"
                        + "}\n"
                        : "")
                .append("uvec4 wg_aquifer_shl_u32_").append(suffix)
                .append("(uint value, int shift) {\n")
                .append("    if (shift <= 0) return uvec4(value, 0u, 0u, 0u);\n")
                .append("    if (shift < 32) return uvec4(value << uint(shift), value >> uint(32 - shift), 0u, 0u);\n")
                .append("    if (shift == 32) return uvec4(0u, value, 0u, 0u);\n")
                .append("    if (shift < 64) return uvec4(0u, value << uint(shift - 32), value >> uint(64 - shift), 0u);\n")
                .append("    if (shift == 64) return uvec4(0u, 0u, value, 0u);\n")
                .append("    if (shift < 96) return uvec4(0u, 0u, value << uint(shift - 64), value >> uint(96 - shift));\n")
                .append("    if (shift == 96) return uvec4(0u, 0u, 0u, value);\n")
                .append("    if (shift < 128) return uvec4(0u, 0u, 0u, value << uint(shift - 96));\n")
                .append("    return uvec4(0u);\n")
                .append("}\n")
                .append("bool wg_aquifer_u128_less_").append(suffix)
                .append("(uvec4 left, uvec4 right) {\n")
                .append("    return left.w < right.w || (left.w == right.w && (left.z < right.z || (left.z == right.z && (left.y < right.y || (left.y == right.y && left.x < right.x)))));\n")
                .append("}\n")
                .append("bool wg_aquifer_u128_equal_").append(suffix)
                .append("(uvec4 left, uvec4 right) { return all(equal(left, right)); }\n")
                .append("bool wg_aquifer_density_less_rational_").append(suffix)
                .append("(uvec2 density, uint numerator, uint denominator) {\n")
                .append("    uint exponent = wg_fp64_exp(density);\n")
                .append("    uvec2 mantissa = wg_fp64_frac(density);\n")
                .append("    if (exponent != 0u) mantissa.y |= 0x00100000u;\n")
                .append("    int binaryExponent = exponent == 0u ? -1074 : int(exponent) - 1075;\n")
                .append("    if (binaryExponent >= 0) return false;\n")
                .append("    int shift = -binaryExponent;\n")
                .append("    if (shift >= 128) return true;\n")
                .append("    uvec4 left = wg_aquifer_mul_u64_u32_").append(suffix)
                .append("(mantissa, denominator);\n")
                .append("    uvec4 right = wg_aquifer_shl_u32_").append(suffix)
                .append("(numerator, shift);\n")
                .append("    return wg_aquifer_u128_less_").append(suffix).append("(left, right);\n")
                .append("}\n")
                .append("bool wg_aquifer_density_equal_rational_").append(suffix)
                .append("(uvec2 density, uint numerator, uint denominator) {\n")
                .append("    uint exponent = wg_fp64_exp(density);\n")
                .append("    uvec2 mantissa = wg_fp64_frac(density);\n")
                .append("    if (exponent != 0u) mantissa.y |= 0x00100000u;\n")
                .append("    int binaryExponent = exponent == 0u ? -1074 : int(exponent) - 1075;\n")
                .append("    if (binaryExponent >= 0) return false;\n")
                .append("    int shift = -binaryExponent;\n")
                .append("    if (shift >= 128) return false;\n")
                .append("    return wg_aquifer_u128_equal_").append(suffix).append("(\n")
                .append("            wg_aquifer_mul_u64_u32_").append(suffix).append("(mantissa, denominator),\n")
                .append("            wg_aquifer_shl_u32_").append(suffix).append("(numerator, shift));\n")
                .append("}\n")
                .append("bool wg_aquifer_pressure_positive_").append(suffix)
                .append("(uvec2 density").append(useExternalBarrier ? ", uvec2 barrier" : "")
                .append(", ivec3 parts, uint similarityNumerator, uint similarityDenominator")
                .append(useExternalBarrier ? ", uint similarityFirst, uint similaritySecond" : "")
                .append(") {\n")
                .append(useExternalBarrier
                        ? "    // Combine both similarity factors before scaling the barrier to avoid\n"
                                + "    // a second binary64 rounding at the pressure boundary.\n"
                                + "    uvec2 pressureDensity = density;\n"
                                + "    if (parts.z != 0) {\n"
                                + "        uvec2 scaledBarrier = wg_aquifer_scale_rational_" + suffix
                                + "(barrier, similarityFirst * similaritySecond * 2u, 625u);\n"
                                + "        pressureDensity = wg_aquifer_add_carriers_" + suffix
                                + "(density, scaledBarrier);\n"
                                + "    }\n"
                        : "")
                .append("    if (wg_fp64_nan(").append(pressureDensityExpression).append(")) return false;\n")
                .append("    if (parts.x == 0 || similarityNumerator == 0u) return wg_fp64_positive(")
                .append(pressureDensityExpression).append(");\n")
                .append("    uint pressureMagnitude = uint(parts.x < 0 ? -parts.x : parts.x);\n")
                .append("    uint numerator = pressureMagnitude * similarityNumerator;\n")
                .append("    uint denominator = uint(parts.y) * similarityDenominator;\n")
                .append("    if (parts.x > 0) {\n")
                .append("        if (wg_fp64_zero(").append(pressureDensityExpression)
                .append(") || wg_fp64_sign(").append(pressureDensityExpression).append(") == 0u) return true;\n")
                .append("        return wg_aquifer_density_less_rational_").append(suffix)
                .append("(").append(pressureDensityExpression).append(", numerator, denominator);\n")
                .append("    }\n")
                .append("    if (wg_fp64_zero(").append(pressureDensityExpression)
                .append(") || wg_fp64_sign(").append(pressureDensityExpression).append(") != 0u) return false;\n")
                .append("    return !wg_aquifer_density_less_rational_").append(suffix)
                .append("(").append(pressureDensityExpression).append(", numerator, denominator)\n")
                .append("            && !wg_aquifer_density_equal_rational_").append(suffix)
                .append("(").append(pressureDensityExpression).append(", numerator, denominator);\n")
                .append("}\n");
        }
        if (debugTrace || !integerPressureDraft) {
        source.append("uvec2 ").append(pressure)
                .append("(ivec3 point, int firstLevel, uint firstState, int secondLevel, uint secondState")
                .append(pressureExternalParameter).append(") {\n")
                .append("    if ((firstState == ").append(rawUInt(options.waterStateId()))
                .append(" && secondState == ").append(rawUInt(options.lavaStateId())).append(") ||\n")
                .append("        (firstState == ").append(rawUInt(options.lavaStateId()))
                .append(" && secondState == ").append(rawUInt(options.waterStateId())).append(")) {\n")
                .append("        return uvec2(0u, 0x40000000u);\n")
                .append("    }\n")
                .append("    int difference = firstLevel >= secondLevel ? firstLevel - secondLevel : secondLevel - firstLevel;\n")
                .append("    if (difference == 0) return uvec2(0u);\n")
                .append("    int offsetNumerator = point.y + point.y + 1 - (firstLevel + secondLevel);\n")
                .append("    int absoluteOffsetNumerator = offsetNumerator < 0 ? -offsetNumerator : offsetNumerator;\n")
                .append("    int pressureNumerator = difference - absoluteOffsetNumerator;\n")
                .append("    uvec2 result;\n")
                .append("    if (offsetNumerator > 0) {\n")
                .append("        result = wg_aquifer_ratio_").append(suffix)
                .append("(pressureNumerator, pressureNumerator > 0 ? 3 : 5);\n")
                .append("    } else {\n")
                .append("        int adjustedNumerator = 6 + pressureNumerator;\n")
                .append("        result = wg_aquifer_ratio_").append(suffix)
                .append("(adjustedNumerator, adjustedNumerator > 0 ? 6 : 20);\n")
                .append("    }\n")
                .append(debugTrace ? "    return result;\n" : "")
                // Preserve Minecraft's !(pressure < -2) && !(pressure > 2)
                // branch shape.  The inclusive-looking form would reject a
                // NaN before the barrier root gets its chance to fail closed.
                .append("    uvec2 barrier = ").append(useExternalBarrier ? "externalBarrier" : "uvec2(0u)").append(";\n")
                .append("    if (!wg_fp64_less(result, uvec2(0u, 0xc0000000u))\n")
                .append("            && !wg_fp64_less(uvec2(0u, 0x40000000u), result)) {\n")
                .append(useExternalBarrier ? "" : "        barrier = " + barrier + ";\n")
                .append("        if (!wg_fp64_finite(barrier)) { wg_failed = true; return wg_fp64_qnan(); }\n")
                .append("        result = wg_fp64_add(result, barrier);\n")
                .append("    }\n")
                .append("    return wg_fp64_add(result, result);\n")
                .append("}\n");
        }

        source.append("uvec4 ").append(name).append("(ivec3 point, uvec2 density")
                .append(useExternalBarrier ? ", uvec2 barrier" : "").append(") {\n")
                .append(debugMultiply
                        ? "    uvec2 product = wg_fp64_mul(uvec2(0x1eb851ecu, 0x3fe1eb85u), "
                                + "uvec2(0x70a3d70au, 0x3fd70a3du));\n"
                                + "    return uvec4(product.x, product.y, 0u, 0u);\n"
                        : "")
                // NoiseBasedAquifer is never consulted for a positive final
                // density.  Preserve its null result before the global lava
                // shortcut or any captured candidate lookup; the ore rule is
                // allowed to run only after this explicit null aquifer path.
                .append("    if (wg_fp64_positive(density)) return uvec4(0u, dispatch.airStateId, 0u, 0u);\n")
                .append("    if (point.y < min(").append(options.seaLevel()).append(", ")
                .append(options.globalLavaLevel()).append(")) return uvec4(1u, ")
                .append(rawUInt(options.lavaStateId())).append(", 0u, 0u);\n")
                // The capture covers a rectangular request and therefore
                // contains extra statuses. NoiseBasedAquifer ranks only the
                // 2 x 3 x 2 grid cells around the queried point.
                .append("    int aquiferGridX = wg_i32_from_bits(wg_i32_floor_div("
                        + "wg_i32_sub(wg_i32_to_bits(point.x), 5u), 16u));\n")
                .append("    int aquiferGridY = wg_i32_from_bits(wg_i32_floor_div("
                        + "wg_i32_add(wg_i32_to_bits(point.y), 1u), 12u));\n")
                .append("    int aquiferGridZ = wg_i32_from_bits(wg_i32_floor_div("
                        + "wg_i32_sub(wg_i32_to_bits(point.z), 5u), 16u));\n")
                .append("    int aquiferGridXNext = wg_i32_from_bits(wg_i32_add("
                        + "wg_i32_to_bits(aquiferGridX), 1u));\n")
                .append("    int aquiferGridYPrevious = wg_i32_from_bits(wg_i32_sub("
                        + "wg_i32_to_bits(aquiferGridY), 1u));\n")
                .append("    int aquiferGridYNext = wg_i32_from_bits(wg_i32_add("
                        + "wg_i32_to_bits(aquiferGridY), 1u));\n")
                .append("    int aquiferGridZNext = wg_i32_from_bits(wg_i32_add("
                        + "wg_i32_to_bits(aquiferGridZ), 1u));\n")
                .append("    uvec2 firstDistance = uvec2(0xffffffffu, 0xffffffffu);\n")
                .append("    uvec2 secondDistance = uvec2(0xffffffffu, 0xffffffffu);\n")
                .append("    uvec2 thirdDistance = uvec2(0xffffffffu, 0xffffffffu);\n")
                .append("    int firstLevel = 0, secondLevel = 0, thirdLevel = 0;\n")
                .append("    uint firstState = dispatch.airStateId, secondState = dispatch.airStateId, thirdState = dispatch.airStateId;\n")
                .append("    bool firstFluid = false, secondFluid = false, thirdFluid = false;\n")
                .append("    bool foundFirst = false, foundSecond = false, foundThird = false;\n");

        String candidateMeta = "wg_aquifer_meta_" + suffix;
        String candidatePosition = "wg_aquifer_position_" + suffix;
        String candidateStatus = "wg_aquifer_status_" + suffix;
        int candidateCount = options.candidates().size();
        source.append("const ivec4 ").append(candidateMeta).append("[").append(candidateCount)
                .append("] = ivec4[").append(candidateCount).append("](\n");
        for (int index = 0; index < candidateCount; index++) {
            Candidate candidate = options.candidates().get(index);
            source.append("    ivec4(")
                    .append(Math.floorDiv(candidate.x(), 16)).append(", ")
                    .append(Math.floorDiv(candidate.y(), 12)).append(", ")
                    .append(Math.floorDiv(candidate.z(), 16)).append(", ")
                    .append(candidate.level()).append(")")
                    .append(index + 1 == candidateCount ? "\n" : ",\n");
        }
        source.append(");\n");
        source.append("const ivec3 ").append(candidatePosition).append("[").append(candidateCount)
                .append("] = ivec3[").append(candidateCount).append("](\n");
        for (int index = 0; index < candidateCount; index++) {
            Candidate candidate = options.candidates().get(index);
            source.append("    ivec3(").append(candidate.x()).append(", ")
                    .append(candidate.y()).append(", ").append(candidate.z()).append(")")
                    .append(index + 1 == candidateCount ? "\n" : ",\n");
        }
        source.append(");\n");
        source.append("const uvec2 ").append(candidateStatus).append("[").append(candidateCount)
                .append("] = uvec2[").append(candidateCount).append("](\n");
        for (int index = 0; index < candidateCount; index++) {
            Candidate candidate = options.candidates().get(index);
            source.append("    uvec2(").append(rawUInt(candidate.stateId())).append(", ")
                    .append(candidate.fluid() ? "1u" : "0u").append(")")
                    .append(index + 1 == candidateCount ? "\n" : ",\n");
        }
        source.append(");\n");
        source.append("    for (int candidateIndex = 0; candidateIndex < ")
                .append(candidateCount).append("; candidateIndex++) {\n")
                .append("        ivec4 candidate = ").append(candidateMeta).append("[candidateIndex];\n")
                .append("        if ((aquiferGridX == candidate.x || aquiferGridXNext == candidate.x)\n")
                .append("                && (aquiferGridYPrevious == candidate.y || aquiferGridY == candidate.y\n")
                .append("                || aquiferGridYNext == candidate.y)\n")
                .append("                && (aquiferGridZ == candidate.z || aquiferGridZNext == candidate.z)) {\n")
                .append("            ivec3 position = ").append(candidatePosition).append("[candidateIndex];\n")
                .append("            uvec2 status = ").append(candidateStatus).append("[candidateIndex];\n")
                // Captured candidates are in the bounded 16 x 12 x 16
                // aquifer neighborhood.  Keep this distance calculation in
                // the target driver's native integer lane; the generic
                // emulated i64 multiply path collapses these small squares
                // to zero on the current device.
                .append("            int candidateDx = point.x - position.x;\n")
                .append("            int candidateDy = point.y - position.y;\n")
                .append("            int candidateDz = point.z - position.z;\n")
                // The target driver has collapsed both the generic emulated
                // i64 square and signed int square forms to zero in this
                // small exact stage.  Widen magnitudes into the unsigned
                // native lane before squaring.
                .append("            uint candidateAbsDx = uint(candidateDx < 0 ? -candidateDx : candidateDx);\n")
                .append("            uint candidateAbsDy = uint(candidateDy < 0 ? -candidateDy : candidateDy);\n")
                .append("            uint candidateAbsDz = uint(candidateDz < 0 ? -candidateDz : candidateDz);\n")
                // Temporary scalar diagnostic: this is the CPU trace's first
                // candidate for the one-point probe.  Return the constructed
                // distance before any sorting or pressure arithmetic.
                .append("            uint candidateDistanceLow = candidateAbsDx * candidateAbsDx\n")
                .append("                    + candidateAbsDy * candidateAbsDy\n")
                .append("                    + candidateAbsDz * candidateAbsDz;\n")
                .append("            uvec2 candidateDistance = uvec2(candidateDistanceLow, 0u);\n")
                .append("            int candidateLevel = candidate.w;\n")
                .append("            uint candidateState = status.x;\n")
                .append("            bool candidateFluid = status.y != 0u;\n")
                .append("            if (!foundFirst || wg_u64_less(candidateDistance, firstDistance)\n")
                .append("                    || wg_u64_equal(candidateDistance, firstDistance)) {\n")
                .append("                thirdDistance = secondDistance; thirdLevel = secondLevel; thirdState = secondState;\n")
                .append("                thirdFluid = secondFluid; foundThird = foundSecond;\n")
                .append("                secondDistance = firstDistance; secondLevel = firstLevel; secondState = firstState;\n")
                .append("                secondFluid = firstFluid; foundSecond = foundFirst;\n")
                .append("                firstDistance = candidateDistance; firstLevel = candidateLevel; firstState = candidateState;\n")
                .append("                firstFluid = candidateFluid; foundFirst = true;\n")
                .append("            } else if (!foundSecond || wg_u64_less(candidateDistance, secondDistance)\n")
                .append("                    || wg_u64_equal(candidateDistance, secondDistance)) {\n")
                .append("                thirdDistance = secondDistance; thirdLevel = secondLevel; thirdState = secondState;\n")
                .append("                thirdFluid = secondFluid; foundThird = foundSecond;\n")
                .append("                secondDistance = candidateDistance; secondLevel = candidateLevel; secondState = candidateState;\n")
                .append("                secondFluid = candidateFluid; foundSecond = true;\n")
                .append("            } else if (!foundThird || wg_u64_less(candidateDistance, thirdDistance)\n")
                .append("                    || wg_u64_equal(candidateDistance, thirdDistance)) {\n")
                .append("                thirdDistance = candidateDistance; thirdLevel = candidateLevel; thirdState = candidateState;\n")
                .append("                thirdFluid = candidateFluid; foundThird = true;\n")
                .append("            }\n")
                .append("        }\n")
                .append("    }\n");

        // Every queried point in a qualified capture must resolve the same
        // nearest three statuses that NoiseBasedAquifer sees.  Returning AIR
        // for a missing table entry would turn an incomplete capture into a
        // plausible material result and could also admit the ore rule.  Keep
        // this explicitly fail-closed; the caller can then reject the dense
        // dispatch using wg_failed and its invalid-state output.
        source.append("    if (!foundFirst || !foundSecond || !foundThird) {\n")
                .append("        wg_failed = true; return uvec4(0u, dispatch.invalidStateId, 1u, 0u);\n")
                .append("    }\n");
        source.append("    bool firstAt = foundFirst && firstFluid && point.y < firstLevel;\n")
                .append("    uint firstMaterial = firstAt ? firstState : dispatch.airStateId;\n")
                .append("    uint secondMaterial = foundSecond && secondFluid && point.y < secondLevel\n")
                .append("            ? secondState : dispatch.airStateId;\n")
                .append("    uint thirdMaterial = foundThird && thirdFluid && point.y < thirdLevel\n")
                .append("            ? thirdState : dispatch.airStateId;\n")
                .append(rationalPressure ? "" : "    uvec2 similarity12 = " + similarity
                        + "(firstDistance, secondDistance);\n")
                .append(integerPressureDraft
                        ? "    uint similarity12Numerator = wg_aquifer_similarity_numerator_" + suffix
                                + "(firstDistance, secondDistance);\n" : "")
                .append(rationalPressure
                        ? "    if (similarity12Numerator == 0u) {\n"
                        : "    if (!wg_fp64_positive(similarity12)) {\n")
                .append(rationalPressure
                        ? "        bool schedule = wg_aquifer_similarity_delta_" + suffix
                                + "(firstDistance, secondDistance) <= 44u;\n"
                        : "        bool schedule = wg_fp64_less_equal(uvec2(0x851eb852u, 0xbfe851ebu), similarity12);\n")
                // The aquifer rule returned firstMaterial even when it is
                // AIR. Keep candidate presence separate from fluid-at-point
                // status so the following ore rule is not run for a dry
                // aquifer result.
                .append("        return uvec4(1u, firstMaterial, 0u, schedule ? 1u : 0u);\n")
                .append("    }\n")
                .append("    if (firstAt && firstMaterial == ").append(rawUInt(options.waterStateId()))
                .append(" && point.y <= ").append(Math.min(options.seaLevel(), options.globalLavaLevel())).append(") {\n")
                .append("        return uvec4(1u, firstState, 0u, 1u);\n")
                .append("    }\n")
                .append(rationalPressure
                        ? "    if (wg_aquifer_pressure_positive_" + suffix
                                + "(density" + pressureBarrierArgument + ", wg_aquifer_pressure_parts_" + suffix
                                + "(point, firstLevel, firstMaterial, secondLevel, secondMaterial), "
                                + "similarity12Numerator, 25u"
                                + (useExternalBarrier ? ", similarity12Numerator, 25u" : "") + "))\n"
                                + "        return uvec4(0u, dispatch.airStateId, 1u, 0u);\n"
                        : "    uvec2 pressure12 = " + pressure
                                + "(point, firstLevel, firstMaterial, secondLevel, secondMaterial"
                                + pressureBarrierArgument + ");\n"
                                + (debugTrace ? "" : "    if (wg_fp64_positive(wg_fp64_add(density, pressure12)))\n"
                                        + "        return uvec4(0u, dispatch.airStateId, 1u, 0u);\n"))
                .append(rationalPressure ? "" : "    uvec2 similarity13 = " + similarity
                        + "(firstDistance, thirdDistance);\n")
                .append(integerPressureDraft
                        ? "    uint similarity13Numerator = wg_aquifer_similarity_numerator_" + suffix
                                + "(firstDistance, thirdDistance);\n" : "")
                .append(rationalPressure
                        ? "    if (similarity13Numerator != 0u) {\n"
                        : "    if (wg_fp64_positive(similarity13)) {\n")
                .append(rationalPressure
                        ? "        if (wg_aquifer_pressure_positive_" + suffix
                                + "(density" + pressureBarrierArgument + ", wg_aquifer_pressure_parts_" + suffix
                                + "(point, firstLevel, firstMaterial, thirdLevel, thirdMaterial), "
                                + "similarity12Numerator * similarity13Numerator, 625u"
                                + (useExternalBarrier
                                        ? ", similarity12Numerator, similarity13Numerator" : "")
                                + "))\n"
                                + "            return uvec4(0u, dispatch.airStateId, 1u, 0u);\n"
                        : "        uvec2 pressure13 = " + pressure
                                + "(point, firstLevel, firstMaterial, thirdLevel, thirdMaterial"
                                + pressureBarrierArgument + ");\n")
                .append(rationalPressure ? "" : (debugTrace ? "" : "        if (wg_fp64_positive(wg_fp64_add(density, pressure13)))\n"
                        + "            return uvec4(0u, dispatch.airStateId, 1u, 0u);\n"))
                .append("    }\n")
                .append(rationalPressure ? "" : "    uvec2 similarity23 = " + similarity
                        + "(secondDistance, thirdDistance);\n")
                .append(integerPressureDraft
                        ? "    uint similarity23Numerator = wg_aquifer_similarity_numerator_" + suffix
                                + "(secondDistance, thirdDistance);\n" : "")
                .append(debugRationalComparator
                        ? "    ivec3 debugPair23 = wg_aquifer_pressure_parts_" + suffix
                                + "(point, secondLevel, secondMaterial, thirdLevel, thirdMaterial);\n"
                                + (useExternalBarrier
                                        ? "    uvec2 debugScaledBarrier = wg_aquifer_scale_rational_" + suffix
                                                + "(barrier, similarity12Numerator * similarity23Numerator * 2u, 625u);\n"
                                                + "    uvec2 debugPressureDensity = wg_aquifer_add_carriers_" + suffix
                                                + "(density, debugScaledBarrier);\n"
                                                + "    return uvec4(debugPressureDensity, uint(debugPair23.y), "
                                                + "wg_aquifer_pressure_positive_" + suffix
                                                + "(density, barrier, debugPair23, "
                                                + "similarity12Numerator * similarity23Numerator, 625u, "
                                                + "similarity12Numerator, similarity23Numerator) ? 1u : 0u);\n"
                                        : "    return uvec4(uint(debugPair23.x), uint(debugPair23.y), uint(debugPair23.z), 0u);\n")
                        : "")
                .append(rationalPressure
                        ? "    if (similarity23Numerator != 0u) {\n"
                        : "    if (wg_fp64_positive(similarity23)) {\n")
                .append(debugTrace ? "        uvec2 pressure23Base = " + pressure
                        + "(point, secondLevel, secondMaterial, thirdLevel, thirdMaterial"
                        + pressureBarrierArgument + ");\n" : "")
                .append(rationalPressure
                        ? "        bool pressure23Positive = wg_aquifer_pressure_positive_" + suffix
                                + "(density" + pressureBarrierArgument + ", wg_aquifer_pressure_parts_" + suffix
                                + "(point, secondLevel, secondMaterial, thirdLevel, thirdMaterial), "
                                + "similarity12Numerator * similarity23Numerator, 625u"
                                + (useExternalBarrier
                                        ? ", similarity12Numerator, similarity23Numerator" : "")
                                + ");\n"
                        : "        uvec2 pressure23 = " + pressure
                                + "(point, secondLevel, secondMaterial, thirdLevel, thirdMaterial"
                                + pressureBarrierArgument + ");\n")
                .append(rationalPressure ? "" : "        uvec2 pressure23Sum = wg_fp64_add(density, pressure23);\n")
                .append(debugTrace
                        ? "        return uvec4(pressure23Base.x, pressure23Base.y, pressure23.x, pressure23.y);\n"
                        : rationalPressure
                                ? "        if (pressure23Positive)\n"
                                : "        if (wg_fp64_positive(pressure23Sum))\n")
                .append(debugTrace ? "" : "")
                .append(debugTrace ? "" : "            return uvec4(0u, dispatch.airStateId, 1u, 0u);\n")
                .append("    }\n")
                .append(debugTrace
                        ? "    return uvec4(1u, firstDistance.x, secondDistance.x, "
                        + "thirdDistance.x"
                        + " | (wg_fp64_positive(similarity12) ? 0x10000000u : 0u)"
                        + " | (wg_fp64_positive(similarity23) ? 0x20000000u : 0u));\n"
                        : "    return uvec4(1u, firstMaterial, 0u, 1u);\n")
                .append("}\n");
        return new Emission(source.toString(), name);
    }

    private static Emission emitDefaultFluid(Options options, int suffix) {
        String name = "wg_aquifer_default_fluid_" + suffix;
        StringBuilder source = new StringBuilder();
        source.append("// Disabled aquifer: use the captured global fluid picker.\n");
        source.append("uvec4 ").append(name).append("(ivec3 point, uvec2 density) {\n")
                .append("    if (wg_fp64_positive(density))\n")
                .append("        return uvec4(0u, dispatch.airStateId, 0u, 0u);\n")
                .append("    if (point.y < ").append(Math.min(options.seaLevel(), options.globalLavaLevel())).append(")\n")
                .append("        return uvec4(1u, ").append(rawUInt(options.lavaStateId())).append(", 0u, 0u);\n")
                .append("    if (point.y < ").append(options.seaLevel()).append(")\n")
                .append("        return uvec4(1u, ").append(rawUInt(options.waterStateId())).append(", 0u, 0u);\n")
                .append("    return uvec4(1u, dispatch.airStateId, 0u, 0u);\n")
                .append("}\n");
        return new Emission(source.toString(), name);
    }

    private static String i64Literal(int value) {
        return "uvec2(" + rawUInt(value) + ", " + rawUInt(value < 0 ? -1 : 0) + ")";
    }

    private static String rawUInt(int value) {
        return "0x" + Integer.toUnsignedString(value, 16) + "u";
    }
}
