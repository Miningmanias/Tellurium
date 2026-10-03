// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan.worldgen;

import dev.worldgennext.semantic.program.NumericProfile;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Emits the portable integer-carrier arithmetic used by the conservative GPU
 * profile. The helpers deliberately use only 32-bit integer GLSL operations;
 * no native floating type or operation is hidden behind this profile.
 *
 * <p>The carriers are raw IEEE-754 binary32 values and two-limb binary64
 * values. The implementation keeps three low bits while aligning and
 * normalising significands, making the round-to-nearest-even point explicit
 * while preserving signed zero, infinity and NaN classification. The FP64
 * path is qualified independently from the worldgen graph and remains subject
 * to the same real-device and graph-replay gates.</p>
 */
public final class IntegerIeeeEmitter {
    /**
     * Exact helper closure from this emitter's own declarations, not captured GLSL.
     * Preserve declaration order and arithmetic text. This deliberately accepts
     * only named helpers; unknown roots/dependencies fail closed.
     */
    public static String helperSource(Set<String> roots) {
        if (roots == null || roots.isEmpty()) throw new IllegalArgumentException("IEEE helper roots required");
        Map<String, String> functions = HelperDeclarations.FUNCTIONS;
        var reachable = new LinkedHashSet<String>();
        var pending = new ArrayDeque<String>();
        for (String root : roots) {
            if (!functions.containsKey(root)) throw new IllegalArgumentException("Unknown IEEE helper: " + root);
            if (reachable.add(root)) pending.add(root);
        }
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            var calls = HelperDeclarations.CALL.matcher(functions.get(current));
            while (calls.find()) {
                String callee = calls.group(1);
                if (!functions.containsKey(callee)) throw new IllegalStateException("Unknown IEEE dependency: " + callee);
                if (reachable.add(callee)) pending.addLast(callee);
            }
        }
        var result = new StringBuilder();
        functions.forEach((name, declaration) -> {
            if (reachable.contains(name)) result.append(declaration).append('\n');
        });
        return result.toString();
    }

    private static final class HelperDeclarations {
        private static final Pattern CALL = Pattern.compile("\\b(wg_[A-Za-z0-9_]+)\\s*\\(");
        private static final Map<String, String> FUNCTIONS = parse();

        private static Map<String, String> parse() {
            String emitted = source();
            var header = Pattern.compile("(?m)^(?:uint|int|bool|uvec2|uvec4)\\s+(wg_[A-Za-z0-9_]+)\\([^;{}]*\\)\\s*\\{")
                    .matcher(emitted);
            var result = new LinkedHashMap<String, String>();
            while (header.find()) {
                int end = header.end();
                int depth = 1;
                while (end < emitted.length() && depth > 0) {
                    char value = emitted.charAt(end++);
                    if (value == '{') depth++;
                    else if (value == '}') depth--;
                }
                if (depth != 0) throw new IllegalStateException("Unterminated IEEE helper: " + header.group(1));
                if (result.put(header.group(1), emitted.substring(header.start(), end)) != null)
                    throw new IllegalStateException("Duplicate IEEE helper: " + header.group(1));
            }
            return java.util.Collections.unmodifiableMap(result);
        }
    }

    public String emit(NumericProfile profile) {
        if (profile != NumericProfile.GPU_IEEE_BITS) {
            throw new IllegalArgumentException("Integer emitter requires GPU_IEEE_BITS");
        }
        return source();
    }

    public static String source() {
        return """
                // WorldgenNext GPU_IEEE_BITS v1: binary32 raw carriers only.
                uint wg_fp32_sign(uint bits) { return bits >> 31; }
                uint wg_fp32_exp(uint bits) { return (bits >> 23) & 0xffu; }
                uint wg_fp32_frac(uint bits) { return bits & 0x7fffffu; }
                bool wg_fp32_nan(uint bits) { return wg_fp32_exp(bits) == 255u && wg_fp32_frac(bits) != 0u; }
                bool wg_fp32_inf(uint bits) { return wg_fp32_exp(bits) == 255u && wg_fp32_frac(bits) == 0u; }
                bool wg_fp32_zero(uint bits) { return (bits & 0x7fffffffu) == 0u; }
                bool wg_fp32_finite(uint bits) { return wg_fp32_exp(bits) != 255u; }
                uint wg_fp32_qnan() { return 0x7fc00000u; }

                // Right shift with a sticky bit. The shift is guarded before
                // constructing the mask so a 32-bit shift is never evaluated.
                uint wg_fp32_shift_sticky(uint value, uint shift) {
                    if (shift == 0u) return value;
                    if (shift >= 32u) return value == 0u ? 0u : 1u;
                    uint mask = (1u << shift) - 1u;
                    uint result = value >> shift;
                    return (value & mask) == 0u ? result : (result | 1u);
                }

                // extendedMantissa has three low rounding bits and a leading
                // significand bit at position 26 for a normal result.
                uint wg_fp32_pack(uint sign, int exponent, uint extendedMantissa) {
                    if (extendedMantissa == 0u) return sign << 31;
                    while (extendedMantissa >= (1u << 27)) {
                        extendedMantissa = wg_fp32_shift_sticky(extendedMantissa, 1u);
                        exponent++;
                    }
                    while (extendedMantissa < (1u << 26) && exponent > 1) {
                        extendedMantissa <<= 1;
                        exponent--;
                    }
                    if (exponent <= 0) {
                        extendedMantissa = wg_fp32_shift_sticky(extendedMantissa, uint(1 - exponent));
                        exponent = 0;
                    }
                    uint rounded = extendedMantissa >> 3;
                    uint guard = (extendedMantissa >> 2) & 1u;
                    uint sticky = extendedMantissa & 3u;
                    if (guard != 0u && (sticky != 0u || (rounded & 1u) != 0u)) rounded++;
                    if (rounded >= (1u << 24)) {
                        rounded >>= 1;
                        exponent++;
                    }
                    if (exponent >= 255) return (sign << 31) | 0x7f800000u;
                    if (exponent == 0) return (sign << 31) | (rounded & 0x7fffffu);
                    if (exponent == 1 && rounded < (1u << 23)) exponent = 0;
                    return (sign << 31) | (uint(exponent) << 23) | (rounded & 0x7fffffu);
                }

                uint wg_fp32_add(uint left, uint right) {
                    uint leftExponent = wg_fp32_exp(left), rightExponent = wg_fp32_exp(right);
                    uint leftFraction = wg_fp32_frac(left), rightFraction = wg_fp32_frac(right);
                    bool leftNan = wg_fp32_nan(left), rightNan = wg_fp32_nan(right);
                    if (leftNan || rightNan) return wg_fp32_qnan();
                    if (wg_fp32_inf(left) && wg_fp32_inf(right) && wg_fp32_sign(left) != wg_fp32_sign(right)) return wg_fp32_qnan();
                    if (wg_fp32_inf(left)) return left;
                    if (wg_fp32_inf(right)) return right;
                    if (wg_fp32_zero(left) && wg_fp32_zero(right)) {
                        // Round-to-nearest-even gives negative zero only when
                        // both operands are negative zero.
                        return (wg_fp32_sign(left) & wg_fp32_sign(right)) << 31;
                    }
                    if (wg_fp32_zero(left)) return right;
                    if (wg_fp32_zero(right)) return left;

                    int exponentLeft = int(leftExponent == 0u ? 1u : leftExponent);
                    int exponentRight = int(rightExponent == 0u ? 1u : rightExponent);
                    uint mantissaLeft = (leftExponent == 0u ? leftFraction : (leftFraction | (1u << 23))) << 3;
                    uint mantissaRight = (rightExponent == 0u ? rightFraction : (rightFraction | (1u << 23))) << 3;
                    uint signLeft = wg_fp32_sign(left), signRight = wg_fp32_sign(right);
                    if (exponentLeft < exponentRight || (exponentLeft == exponentRight && mantissaLeft < mantissaRight)) {
                        uint unsignedSwap = mantissaLeft; mantissaLeft = mantissaRight; mantissaRight = unsignedSwap;
                        int signedSwap = exponentLeft; exponentLeft = exponentRight; exponentRight = signedSwap;
                        uint signSwap = signLeft; signLeft = signRight; signRight = signSwap;
                    }
                    mantissaRight = wg_fp32_shift_sticky(mantissaRight, uint(exponentLeft - exponentRight));
                    uint magnitude;
                    uint sign = signLeft;
                    if (signLeft == signRight) {
                        magnitude = mantissaLeft + mantissaRight;
                    } else {
                        magnitude = mantissaLeft - mantissaRight;
                        if (magnitude == 0u) return 0u;
                    }
                    return wg_fp32_pack(sign, exponentLeft, magnitude);
                }

                uint wg_fp32_sub(uint left, uint right) { return wg_fp32_add(left, right ^ 0x80000000u); }

                uvec2 wg_u32_mul(uint left, uint right) {
                    uvec2 result;
                    // GLSL returns msb then lsb; carrier lanes are low then high.
                    umulExtended(left, right, result.y, result.x);
                    return result;
                }

                uint wg_fp32_mul(uint left, uint right) {
                    uint leftExponent = wg_fp32_exp(left), rightExponent = wg_fp32_exp(right);
                    uint leftFraction = wg_fp32_frac(left), rightFraction = wg_fp32_frac(right);
                    uint sign = wg_fp32_sign(left) ^ wg_fp32_sign(right);
                    if (wg_fp32_nan(left) || wg_fp32_nan(right)) return wg_fp32_qnan();
                    if ((wg_fp32_inf(left) && wg_fp32_zero(right)) || (wg_fp32_inf(right) && wg_fp32_zero(left))) return wg_fp32_qnan();
                    if (wg_fp32_zero(left) || wg_fp32_zero(right)) return sign << 31;
                    if (wg_fp32_inf(left) || wg_fp32_inf(right)) return (sign << 31) | 0x7f800000u;
                    int normalizedLeftExponent = int(leftExponent == 0u ? 1u : leftExponent);
                    int normalizedRightExponent = int(rightExponent == 0u ? 1u : rightExponent);
                    uint leftMantissa = leftExponent == 0u ? leftFraction : (leftFraction | (1u << 23));
                    uint rightMantissa = rightExponent == 0u ? rightFraction : (rightFraction | (1u << 23));
                    while (leftExponent == 0u && (leftMantissa & (1u << 23)) == 0u) { leftMantissa <<= 1; normalizedLeftExponent--; }
                    while (rightExponent == 0u && (rightMantissa & (1u << 23)) == 0u) { rightMantissa <<= 1; normalizedRightExponent--; }
                    uvec2 product = wg_u32_mul(leftMantissa, rightMantissa);
                    // A 24-by-24 product is normalized at bit 47 or bit 46.
                    bool top = (product.y & 0x00008000u) != 0u;
                    uint shift = top ? 21u : 20u;
                    uint extended = (product.x >> shift) | (product.y << (32u - shift));
                    uint mask = (1u << shift) - 1u;
                    if ((product.x & mask) != 0u) extended |= 1u;
                    int exponent = normalizedLeftExponent + normalizedRightExponent - 127;
                    if (top) exponent++;
                    return wg_fp32_pack(sign, exponent, extended);
                }

                uint wg_fp32_div(uint left, uint right) {
                    uint leftExponent = wg_fp32_exp(left), rightExponent = wg_fp32_exp(right);
                    uint leftFraction = wg_fp32_frac(left), rightFraction = wg_fp32_frac(right);
                    uint sign = wg_fp32_sign(left) ^ wg_fp32_sign(right);
                    if (wg_fp32_nan(left) || wg_fp32_nan(right)) return wg_fp32_qnan();
                    if (wg_fp32_zero(right)) return wg_fp32_zero(left) ? wg_fp32_qnan() : ((sign << 31) | 0x7f800000u);
                    if (wg_fp32_inf(left) && wg_fp32_inf(right)) return wg_fp32_qnan();
                    if (wg_fp32_inf(left)) return (sign << 31) | 0x7f800000u;
                    if (wg_fp32_inf(right)) return sign << 31;
                    if (wg_fp32_zero(left)) return sign << 31;
                    int normalizedLeftExponent = int(leftExponent == 0u ? 1u : leftExponent);
                    int normalizedRightExponent = int(rightExponent == 0u ? 1u : rightExponent);
                    uint numeratorSignificand = leftExponent == 0u ? leftFraction : (leftFraction | (1u << 23));
                    uint denominatorSignificand = rightExponent == 0u ? rightFraction : (rightFraction | (1u << 23));
                    while (leftExponent == 0u && (numeratorSignificand & (1u << 23)) == 0u) { numeratorSignificand <<= 1; normalizedLeftExponent--; }
                    while (rightExponent == 0u && (denominatorSignificand & (1u << 23)) == 0u) { denominatorSignificand <<= 1; normalizedRightExponent--; }
                    // Divide (numeratorSignificand << 26) by the denominator
                    // with restoring integer division. The quotient carries
                    // three rounding positions and remains below 29 bits.
                    uvec2 numerator = uvec2(numeratorSignificand << 26, numeratorSignificand >> 6);
                    uint remainder = 0u, quotient = 0u;
                    for (int bit = 49; bit >= 0; bit--) {
                        uint incoming = bit >= 32 ? ((numerator.y >> uint(bit - 32)) & 1u) : ((numerator.x >> uint(bit)) & 1u);
                        remainder = (remainder << 1) | incoming;
                        uint selected = remainder >= denominatorSignificand ? 1u : 0u;
                        if (selected != 0u) remainder -= denominatorSignificand;
                        quotient = (quotient << 1) | selected;
                    }
                    if (remainder != 0u) quotient |= 1u;
                    int exponent = normalizedLeftExponent - normalizedRightExponent + 127;
                    if (quotient >= (1u << 27)) { quotient = wg_fp32_shift_sticky(quotient, 1u); exponent++; }
                    else if (quotient < (1u << 26)) { quotient <<= 1; exponent--; }
                    return wg_fp32_pack(sign, exponent, quotient);
                }

                uint wg_fp32_negate(uint bits) { return bits ^ 0x80000000u; }
                uint wg_fp32_abs(uint bits) { return bits & 0x7fffffffu; }
                bool wg_fp32_equal(uint left, uint right) {
                    if (wg_fp32_nan(left) || wg_fp32_nan(right)) return false;
                    if (wg_fp32_zero(left) && wg_fp32_zero(right)) return true;
                    return left == right;
                }
                bool wg_fp32_less(uint left, uint right) {
                    if (wg_fp32_nan(left) || wg_fp32_nan(right)) return false;
                    if (wg_fp32_equal(left, right)) return false;
                    bool leftNegative = wg_fp32_sign(left) != 0u, rightNegative = wg_fp32_sign(right) != 0u;
                    if (leftNegative != rightNegative) return leftNegative;
                    uint leftMagnitude = left & 0x7fffffffu, rightMagnitude = right & 0x7fffffffu;
                    return leftNegative ? leftMagnitude > rightMagnitude : leftMagnitude < rightMagnitude;
                }
                bool wg_fp32_less_equal(uint left, uint right) { return wg_fp32_less(left, right) || wg_fp32_equal(left, right); }
                uint wg_fp32_min(uint left, uint right) {
                    if (wg_fp32_nan(left) || wg_fp32_nan(right)) return wg_fp32_qnan();
                    if (wg_fp32_zero(left) && wg_fp32_zero(right)) return (wg_fp32_sign(left) | wg_fp32_sign(right)) << 31;
                    return wg_fp32_less(left, right) ? left : right;
                }
                uint wg_fp32_max(uint left, uint right) {
                    if (wg_fp32_nan(left) || wg_fp32_nan(right)) return wg_fp32_qnan();
                    if (wg_fp32_zero(left) && wg_fp32_zero(right)) return (wg_fp32_sign(left) & wg_fp32_sign(right)) << 31;
                    return wg_fp32_less(left, right) ? right : left;
                }

                uint wg_fp32_from_int(int value) {
                    if (value == 0) return 0u;
                    uint sign = value < 0 ? 1u : 0u;
                    uint magnitude = value < 0 ? uint(-(value + 1)) + 1u : uint(value);
                    int mostSignificant = 31;
                    while (((magnitude >> uint(mostSignificant)) & 1u) == 0u) mostSignificant--;
                    int exponent = mostSignificant + 127;
                    int shift = mostSignificant - 23;
                    uint rounded;
                    if (shift <= 0) {
                        rounded = magnitude << uint(-shift);
                    } else {
                        rounded = magnitude >> uint(shift);
                        uint remainderMask = (1u << uint(shift)) - 1u;
                        uint remainder = magnitude & remainderMask;
                        uint halfway = 1u << uint(shift - 1);
                        if (remainder > halfway || (remainder == halfway && (rounded & 1u) != 0u)) rounded++;
                        if (rounded == (1u << 24)) { rounded >>= 1; exponent++; }
                    }
                    return (sign << 31) | (uint(exponent) << 23) | (rounded & 0x7fffffu);
                }

                uint wg_fp32_floor(uint bits) {
                    if (!wg_fp32_finite(bits) || wg_fp32_zero(bits)) return bits;
                    int exponent = int(wg_fp32_exp(bits)) - 127;
                    if (exponent >= 23) return bits;
                    if (exponent < 0) return wg_fp32_sign(bits) == 0u ? 0u : 0xbf800000u;
                    uint fractionalBits = uint(23 - exponent);
                    uint mask = (1u << fractionalBits) - 1u;
                    if ((wg_fp32_frac(bits) & mask) == 0u) return bits;
                    uint truncated = bits & ~mask;
                    return wg_fp32_sign(bits) == 0u ? truncated : wg_fp32_add(truncated, 0xbf800000u);
                }

                // Integer restoring square root for a 54-bit unsigned value.
                // The returned x limb is the floor root and y is a sticky
                // remainder flag.  This is used at a scale that leaves the
                // three low IEEE rounding positions in the root itself.
                uvec2 wg_sqrt_u64_add(uvec2 left, uvec2 right) {
                    uint low = left.x + right.x;
                    uint carry = low < left.x ? 1u : 0u;
                    return uvec2(low, left.y + right.y + carry);
                }
                uvec2 wg_sqrt_u64_sub(uvec2 left, uvec2 right) {
                    uint low = left.x - right.x;
                    uint borrow = left.x < right.x ? 1u : 0u;
                    return uvec2(low, left.y - right.y - borrow);
                }
                bool wg_sqrt_u64_less(uvec2 left, uvec2 right) {
                    return left.y < right.y || (left.y == right.y && left.x < right.x);
                }
                uvec2 wg_sqrt_u64_shr1(uvec2 value) { return uvec2((value.x >> 1u) | (value.y << 31u), value.y >> 1u); }
                uvec2 wg_sqrt_u64_shr2(uvec2 value) { return uvec2((value.x >> 2u) | (value.y << 30u), value.y >> 2u); }
                uvec2 wg_sqrt_u64_isqrt(uvec2 value) {
                    uvec2 root = uvec2(0u), bit = uvec2(0u, 1u << 20);
                    uvec2 remainder = value;
                    while (bit.x != 0u || bit.y != 0u) {
                        uvec2 candidate = wg_sqrt_u64_add(root, bit);
                        if (!wg_sqrt_u64_less(remainder, candidate)) {
                            remainder = wg_sqrt_u64_sub(remainder, candidate);
                            root = wg_sqrt_u64_add(wg_sqrt_u64_shr1(root), bit);
                        } else {
                            root = wg_sqrt_u64_shr1(root);
                        }
                        bit = wg_sqrt_u64_shr2(bit);
                    }
                    return uvec2(root.x, (remainder.x != 0u || remainder.y != 0u) ? 1u : 0u);
                }
                uint wg_fp32_sqrt(uint bits) {
                    uint exponent = wg_fp32_exp(bits);
                    if (wg_fp32_nan(bits)) return wg_fp32_qnan();
                    if (wg_fp32_zero(bits)) return bits;
                    if (wg_fp32_sign(bits) != 0u) return wg_fp32_qnan();
                    if (wg_fp32_inf(bits)) return bits;
                    int unbiased = exponent == 0u ? -126 : int(exponent) - 127;
                    uint mantissa = wg_fp32_frac(bits);
                    if (exponent != 0u) mantissa |= 1u << 23;
                    else while ((mantissa & (1u << 23)) == 0u) { mantissa <<= 1u; unbiased--; }
                    if ((unbiased & 1) != 0) { mantissa <<= 1u; unbiased--; }
                    uvec2 radicand = uvec2(mantissa << 29u, mantissa >> 3u);
                    uvec2 root = wg_sqrt_u64_isqrt(radicand);
                    uint extended = root.x;
                    if (root.y != 0u) extended |= 1u;
                    return wg_fp32_pack(0u, unbiased / 2 + 127, extended);
                }
                bool wg_fp32_nonzero(uint bits) { return !wg_fp32_zero(bits) && !wg_fp32_nan(bits); }
                """ + source64();
    }

    /** Emits the two-limb binary64 carrier and its reached conversion helpers. */
    private static String source64() {
        return """

                // binary64 raw carriers use uvec2(lo, hi); all paths stay integer-only.
                uint wg_fp64_sign(uvec2 bits) { return bits.y >> 31; }
                uint wg_fp64_exp(uvec2 bits) { return (bits.y >> 20) & 0x7ffu; }
                uvec2 wg_fp64_frac(uvec2 bits) { return uvec2(bits.x, bits.y & 0xfffffu); }
                bool wg_fp64_nan(uvec2 bits) { return wg_fp64_exp(bits) == 2047u && (bits.x != 0u || (bits.y & 0xfffffu) != 0u); }
                bool wg_fp64_inf(uvec2 bits) { return wg_fp64_exp(bits) == 2047u && bits.x == 0u && (bits.y & 0xfffffu) == 0u; }
                bool wg_fp64_zero(uvec2 bits) { return (bits.x | (bits.y & 0x7fffffffu)) == 0u; }
                bool wg_fp64_finite(uvec2 bits) { return wg_fp64_exp(bits) != 2047u; }
                // Sign-bit classification avoids routing the common positive
                // density shortcut through the target driver's larger FP64
                // comparison helper. NaN and both signed zeros are non-positive.
                bool wg_fp64_positive(uvec2 bits) {
                    return !wg_fp64_nan(bits) && !wg_fp64_zero(bits)
                            && (wg_fp64_sign(bits) == 0u);
                }
                uvec2 wg_fp64_qnan() { return uvec2(0u, 0x7ff80000u); }
                bool wg_u64_nonzero(uvec2 value) { return value.x != 0u || value.y != 0u; }
                bool wg_u64_equal(uvec2 left, uvec2 right) { return left.x == right.x && left.y == right.y; }
                uvec2 wg_u64_shl1(uvec2 value) { return uvec2(value.x << 1, (value.y << 1) | (value.x >> 31)); }
                uvec2 wg_u64_shl3(uvec2 value) { return uvec2(value.x << 3, (value.y << 3) | (value.x >> 29)); }

                uvec2 wg_u64_add(uvec2 left, uvec2 right) {
                    uint low = left.x + right.x;
                    uint carry = low < left.x ? 1u : 0u;
                    uint high = left.y + right.y;
                    uint highCarry = high < left.y ? 1u : 0u;
                    uint withCarry = high + carry;
                    highCarry |= withCarry < high ? 1u : 0u;
                    return uvec2(low, withCarry);
                }
                uvec2 wg_u64_sub(uvec2 left, uvec2 right) {
                    uint low = left.x - right.x;
                    uint borrow = left.x < right.x ? 1u : 0u;
                    return uvec2(low, left.y - right.y - borrow);
                }
                uvec2 wg_i64_add(uvec2 left, uvec2 right) { return wg_u64_add(left, right); }
                uvec2 wg_i64_sub(uvec2 left, uvec2 right) { return wg_u64_sub(left, right); }
                uvec2 wg_i64_negate(uvec2 value) { return wg_u64_add(uvec2(~value.x, ~value.y), uvec2(1u, 0u)); }
                uvec2 wg_u64_shl(uvec2 value, uint shift) {
                    if (shift == 0u) return value;
                    if (shift >= 64u) return uvec2(0u);
                    if (shift < 32u) return uvec2(value.x << shift, (value.y << shift) | (value.x >> (32u - shift)));
                    return uvec2(0u, value.x << (shift - 32u));
                }
                uvec2 wg_u64_shr(uvec2 value, uint shift) {
                    if (shift == 0u) return value;
                    if (shift >= 64u) return uvec2(0u);
                    if (shift < 32u) return uvec2((value.x >> shift) | (value.y << (32u - shift)), value.y >> shift);
                    return uvec2(value.y >> (shift - 32u), 0u);
                }
                uvec2 wg_u64_rotl(uvec2 value, uint shift) {
                    uint amount = shift & 63u;
                    return wg_u64_shl(value, amount) | wg_u64_shr(value, (64u - amount) & 63u);
                }
                uvec2 wg_xoroshiro128pp_next(inout uvec2 stateLo, inout uvec2 stateHi) {
                    uvec2 i = stateLo, j = stateHi;
                    uvec2 result = wg_u64_add(wg_u64_rotl(wg_u64_add(i, j), 17u), i);
                    j ^= i;
                    stateLo = wg_u64_rotl(i, 49u) ^ j ^ wg_u64_shl(j, 21u);
                    stateHi = wg_u64_rotl(j, 28u);
                    return result;
                }
                bool wg_u64_less(uvec2 left, uvec2 right) {
                    return left.y < right.y || (left.y == right.y && left.x < right.x);
                }
                uvec2 wg_u64_shr_sticky(uvec2 value, uint shift) {
                    if (shift == 0u) return value;
                    if (shift >= 64u) return wg_u64_nonzero(value) ? uvec2(1u, 0u) : uvec2(0u);
                    uvec2 result;
                    bool discarded;
                    if (shift < 32u) {
                        uint mask = (1u << shift) - 1u;
                        result = uvec2((value.x >> shift) | (value.y << (32u - shift)), value.y >> shift);
                        discarded = (value.x & mask) != 0u;
                    } else if (shift == 32u) {
                        result = uvec2(value.y, 0u);
                        discarded = value.x != 0u;
                    } else {
                        uint lowShift = shift - 32u;
                        uint mask = (1u << lowShift) - 1u;
                        result = uvec2(value.y >> lowShift, 0u);
                        discarded = value.x != 0u || (value.y & mask) != 0u;
                    }
                    if (discarded) result.x |= 1u;
                    return result;
                }

                // extendedMantissa has three low rounding bits and bit 55 is the normal lead.
                uvec2 wg_fp64_pack(uint sign, int exponent, uvec2 extendedMantissa) {
                    if (!wg_u64_nonzero(extendedMantissa)) return uvec2(0u, sign << 31);
                    while ((extendedMantissa.y & 0x01000000u) != 0u) {
                        extendedMantissa = wg_u64_shr_sticky(extendedMantissa, 1u);
                        exponent++;
                    }
                    while ((extendedMantissa.y & 0x00800000u) == 0u && exponent > 1) {
                        extendedMantissa = wg_u64_shl1(extendedMantissa);
                        exponent--;
                    }
                    if (exponent <= 0) {
                        extendedMantissa = wg_u64_shr_sticky(extendedMantissa, uint(1 - exponent));
                        exponent = 0;
                    }
                    uvec2 rounded = uvec2((extendedMantissa.x >> 3) | (extendedMantissa.y << 29), extendedMantissa.y >> 3);
                    uint guard = (extendedMantissa.x >> 2) & 1u;
                    bool sticky = (extendedMantissa.x & 3u) != 0u;
                    if (guard != 0u && (sticky || (rounded.x & 1u) != 0u)) {
                        uint before = rounded.x;
                        rounded.x++;
                        if (rounded.x < before) rounded.y++;
                    }
                    if ((rounded.y & 0x00200000u) != 0u) {
                        rounded = wg_u64_shr_sticky(rounded, 1u);
                        exponent++;
                    }
                    if (exponent >= 2047) return uvec2(0u, (sign << 31) | 0x7ff00000u);
                    if (exponent == 1 && (rounded.y & 0x00100000u) == 0u) exponent = 0;
                    return uvec2(rounded.x, (sign << 31) | (uint(exponent) << 20) | (rounded.y & 0xfffffu));
                }

                uvec2 wg_fp64_add(uvec2 left, uvec2 right) {
                    uint leftExponent = wg_fp64_exp(left), rightExponent = wg_fp64_exp(right);
                    uvec2 leftFraction = wg_fp64_frac(left), rightFraction = wg_fp64_frac(right);
                    if (wg_fp64_nan(left) || wg_fp64_nan(right)) return wg_fp64_qnan();
                    if (wg_fp64_inf(left) && wg_fp64_inf(right) && wg_fp64_sign(left) != wg_fp64_sign(right)) return wg_fp64_qnan();
                    if (wg_fp64_inf(left)) return left;
                    if (wg_fp64_inf(right)) return right;
                    if (wg_fp64_zero(left) && wg_fp64_zero(right)) return uvec2(0u, (wg_fp64_sign(left) & wg_fp64_sign(right)) << 31);
                    if (wg_fp64_zero(left)) return right;
                    if (wg_fp64_zero(right)) return left;
                    int exponentLeft = int(leftExponent == 0u ? 1u : leftExponent);
                    int exponentRight = int(rightExponent == 0u ? 1u : rightExponent);
                    uvec2 mantissaLeft = wg_u64_shl3(uvec2(leftFraction.x, leftFraction.y | (leftExponent == 0u ? 0u : 0x00100000u)));
                    uvec2 mantissaRight = wg_u64_shl3(uvec2(rightFraction.x, rightFraction.y | (rightExponent == 0u ? 0u : 0x00100000u)));
                    uint signLeft = wg_fp64_sign(left), signRight = wg_fp64_sign(right);
                    if (exponentLeft < exponentRight || (exponentLeft == exponentRight && wg_u64_less(mantissaLeft, mantissaRight))) {
                        uvec2 mantissaSwap = mantissaLeft; mantissaLeft = mantissaRight; mantissaRight = mantissaSwap;
                        int exponentSwap = exponentLeft; exponentLeft = exponentRight; exponentRight = exponentSwap;
                        uint signSwap = signLeft; signLeft = signRight; signRight = signSwap;
                    }
                    mantissaRight = wg_u64_shr_sticky(mantissaRight, uint(exponentLeft - exponentRight));
                    if (signLeft == signRight) return wg_fp64_pack(signLeft, exponentLeft, wg_u64_add(mantissaLeft, mantissaRight));
                    uvec2 magnitude = wg_u64_sub(mantissaLeft, mantissaRight);
                    if (!wg_u64_nonzero(magnitude)) return uvec2(0u, 0u);
                    return wg_fp64_pack(signLeft, exponentLeft, magnitude);
                }
                uvec2 wg_fp64_sub(uvec2 left, uvec2 right) { return wg_fp64_add(left, uvec2(right.x, right.y ^ 0x80000000u)); }

                uvec4 wg_u128_from_u64_product(uvec2 left, uvec2 right) {
                    uint mask = 0xffffu;
                    uint a0 = left.x & mask, a1 = left.x >> 16u;
                    uint a2 = left.y & mask, a3 = left.y >> 16u;
                    uint b0 = right.x & mask, b1 = right.x >> 16u;
                    uint b2 = right.y & mask, b3 = right.y >> 16u;
                    uint p00 = a0 * b0;
                    uint c0 = p00 & mask;
                    uint carry = p00 >> 16u;
                    uint p01 = a0 * b1, p10 = a1 * b0;
                    uint sum = (p01 & mask) + (p10 & mask) + carry;
                    uint c1 = sum & mask;
                    carry = (p01 >> 16u) + (p10 >> 16u) + (sum >> 16u);
                    uint p02 = a0 * b2, p11 = a1 * b1, p20 = a2 * b0;
                    sum = (p02 & mask) + (p11 & mask) + (p20 & mask) + carry;
                    uint c2 = sum & mask;
                    carry = (p02 >> 16u) + (p11 >> 16u) + (p20 >> 16u) + (sum >> 16u);
                    uint p03 = a0 * b3, p12 = a1 * b2, p21 = a2 * b1, p30 = a3 * b0;
                    sum = (p03 & mask) + (p12 & mask) + (p21 & mask) + (p30 & mask) + carry;
                    uint c3 = sum & mask;
                    carry = (p03 >> 16u) + (p12 >> 16u) + (p21 >> 16u) + (p30 >> 16u) + (sum >> 16u);
                    uint p13 = a1 * b3, p22 = a2 * b2, p31 = a3 * b1;
                    sum = (p13 & mask) + (p22 & mask) + (p31 & mask) + carry;
                    uint c4 = sum & mask;
                    carry = (p13 >> 16u) + (p22 >> 16u) + (p31 >> 16u) + (sum >> 16u);
                    uint p23 = a2 * b3, p32 = a3 * b2;
                    sum = (p23 & mask) + (p32 & mask) + carry;
                    uint c5 = sum & mask;
                    carry = (p23 >> 16u) + (p32 >> 16u) + (sum >> 16u);
                    uint p33 = a3 * b3;
                    sum = (p33 & mask) + carry;
                    uint c6 = sum & mask;
                    uint c7 = (p33 >> 16u) + (sum >> 16u);
                    return uvec4(c0 | (c1 << 16u), c2 | (c3 << 16u),
                            c4 | (c5 << 16u), c6 | (c7 << 16u));
                }
                uvec2 wg_fp64_product_extended(uvec4 product, out bool sticky) {
                    bool top = (product.w & 0x00000200u) != 0u;
                    uint shift = top ? 50u : 49u;
                    uint lowShift = shift - 32u;
                    uint mask = (1u << lowShift) - 1u;
                    uvec2 extended = uvec2((product.y >> lowShift) | (product.z << (32u - lowShift)),
                            (product.z >> lowShift) | (product.w << (32u - lowShift)));
                    sticky = product.x != 0u || (product.y & mask) != 0u;
                    if (sticky) extended.x |= 1u;
                    return extended;
                }
                uvec2 wg_fp64_mul(uvec2 left, uvec2 right) {
                    uint leftExponent = wg_fp64_exp(left), rightExponent = wg_fp64_exp(right);
                    uint sign = wg_fp64_sign(left) ^ wg_fp64_sign(right);
                    if (wg_fp64_nan(left) || wg_fp64_nan(right)) return wg_fp64_qnan();
                    if ((wg_fp64_inf(left) && wg_fp64_zero(right)) || (wg_fp64_inf(right) && wg_fp64_zero(left))) return wg_fp64_qnan();
                    if (wg_fp64_zero(left) || wg_fp64_zero(right)) return uvec2(0u, sign << 31);
                    if (wg_fp64_inf(left) || wg_fp64_inf(right)) return uvec2(0u, (sign << 31) | 0x7ff00000u);
                    int normalizedLeftExponent = int(leftExponent == 0u ? 1u : leftExponent);
                    int normalizedRightExponent = int(rightExponent == 0u ? 1u : rightExponent);
                    uvec2 leftMantissa = uvec2(wg_fp64_frac(left).x, wg_fp64_frac(left).y | (leftExponent == 0u ? 0u : 0x00100000u));
                    uvec2 rightMantissa = uvec2(wg_fp64_frac(right).x, wg_fp64_frac(right).y | (rightExponent == 0u ? 0u : 0x00100000u));
                    while (leftExponent == 0u && (leftMantissa.y & 0x00100000u) == 0u) { leftMantissa = wg_u64_shl1(leftMantissa); normalizedLeftExponent--; }
                    while (rightExponent == 0u && (rightMantissa.y & 0x00100000u) == 0u) { rightMantissa = wg_u64_shl1(rightMantissa); normalizedRightExponent--; }
                    uvec4 product = wg_u128_from_u64_product(leftMantissa, rightMantissa);
                    bool sticky;
                    uvec2 extended = wg_fp64_product_extended(product, sticky);
                    int exponent = normalizedLeftExponent + normalizedRightExponent - 1023;
                    if ((product.w & 0x00000200u) != 0u) exponent++;
                    return wg_fp64_pack(sign, exponent, extended);
                }
                uvec2 wg_fp64_lerp(uvec2 left, uvec2 right, uvec2 fraction) {
                    return wg_fp64_add(left, wg_fp64_mul(fraction, wg_fp64_sub(right, left)));
                }

                uvec2 wg_fp64_div(uvec2 left, uvec2 right) {
                    uint leftExponent = wg_fp64_exp(left), rightExponent = wg_fp64_exp(right);
                    uint sign = wg_fp64_sign(left) ^ wg_fp64_sign(right);
                    if (wg_fp64_nan(left) || wg_fp64_nan(right)) return wg_fp64_qnan();
                    if (wg_fp64_zero(right)) return wg_fp64_zero(left) ? wg_fp64_qnan() : uvec2(0u, (sign << 31) | 0x7ff00000u);
                    if (wg_fp64_inf(left) && wg_fp64_inf(right)) return wg_fp64_qnan();
                    if (wg_fp64_inf(left)) return uvec2(0u, (sign << 31) | 0x7ff00000u);
                    if (wg_fp64_inf(right)) return uvec2(0u, sign << 31);
                    if (wg_fp64_zero(left)) return uvec2(0u, sign << 31);
                    int normalizedLeftExponent = int(leftExponent == 0u ? 1u : leftExponent);
                    int normalizedRightExponent = int(rightExponent == 0u ? 1u : rightExponent);
                    uvec2 leftMantissa = uvec2(wg_fp64_frac(left).x, wg_fp64_frac(left).y | (leftExponent == 0u ? 0u : 0x00100000u));
                    uvec2 rightMantissa = uvec2(wg_fp64_frac(right).x, wg_fp64_frac(right).y | (rightExponent == 0u ? 0u : 0x00100000u));
                    while (leftExponent == 0u && (leftMantissa.y & 0x00100000u) == 0u) { leftMantissa = wg_u64_shl1(leftMantissa); normalizedLeftExponent--; }
                    while (rightExponent == 0u && (rightMantissa.y & 0x00100000u) == 0u) { rightMantissa = wg_u64_shl1(rightMantissa); normalizedRightExponent--; }
                    // Shift the 53-bit numerator left by 55 positions. The
                    // leading zero limb is intentional: bit 52 becomes bit 107.
                    uvec4 numerator = uvec4(0u, leftMantissa.x << 23,
                            (leftMantissa.x >> 9) | (leftMantissa.y << 23), leftMantissa.y >> 9);
                    uvec2 remainder = uvec2(0u), quotient = uvec2(0u);
                    for (int bit = 107; bit >= 0; bit--) {
                        uint incoming;
                        if (bit >= 96) incoming = (numerator.w >> uint(bit - 96)) & 1u;
                        else if (bit >= 64) incoming = (numerator.z >> uint(bit - 64)) & 1u;
                        else if (bit >= 32) incoming = (numerator.y >> uint(bit - 32)) & 1u;
                        else incoming = (numerator.x >> uint(bit)) & 1u;
                        remainder = uvec2((remainder.x << 1) | incoming, (remainder.y << 1) | (remainder.x >> 31));
                        uint selected = wg_u64_less(rightMantissa, remainder) || wg_u64_equal(rightMantissa, remainder) ? 1u : 0u;
                        if (selected != 0u) remainder = wg_u64_sub(remainder, rightMantissa);
                        quotient = uvec2((quotient.x << 1) | selected, (quotient.y << 1) | (quotient.x >> 31));
                    }
                    if (wg_u64_nonzero(remainder)) quotient.x |= 1u;
                    int exponent = normalizedLeftExponent - normalizedRightExponent + 1023;
                    if ((quotient.y & 0x00800000u) == 0u) { quotient = wg_u64_shl1(quotient); exponent--; }
                    return wg_fp64_pack(sign, exponent, quotient);
                }

                uvec2 wg_fp64_negate(uvec2 bits) { return uvec2(bits.x, bits.y ^ 0x80000000u); }
                uvec2 wg_fp64_abs(uvec2 bits) { return uvec2(bits.x, bits.y & 0x7fffffffu); }
                bool wg_fp64_equal(uvec2 left, uvec2 right) {
                    if (wg_fp64_nan(left) || wg_fp64_nan(right)) return false;
                    if (wg_fp64_zero(left) && wg_fp64_zero(right)) return true;
                    return wg_u64_equal(left, right);
                }
                bool wg_fp64_less(uvec2 left, uvec2 right) {
                    if (wg_fp64_nan(left) || wg_fp64_nan(right)) return false;
                    if (wg_fp64_equal(left, right)) return false;
                    bool leftNegative = wg_fp64_sign(left) != 0u, rightNegative = wg_fp64_sign(right) != 0u;
                    if (leftNegative != rightNegative) return leftNegative;
                    uvec2 leftMagnitude = wg_fp64_abs(left), rightMagnitude = wg_fp64_abs(right);
                    return leftNegative ? wg_u64_less(rightMagnitude, leftMagnitude) : wg_u64_less(leftMagnitude, rightMagnitude);
                }
                bool wg_fp64_less_equal(uvec2 left, uvec2 right) { return wg_fp64_less(left, right) || wg_fp64_equal(left, right); }
                uvec2 wg_fp64_min(uvec2 left, uvec2 right) {
                    if (wg_fp64_nan(left) || wg_fp64_nan(right)) return wg_fp64_qnan();
                    if (wg_fp64_zero(left) && wg_fp64_zero(right)) return uvec2(0u, (wg_fp64_sign(left) | wg_fp64_sign(right)) << 31);
                    return wg_fp64_less(left, right) ? left : right;
                }
                uvec2 wg_fp64_max(uvec2 left, uvec2 right) {
                    if (wg_fp64_nan(left) || wg_fp64_nan(right)) return wg_fp64_qnan();
                    if (wg_fp64_zero(left) && wg_fp64_zero(right)) return uvec2(0u, (wg_fp64_sign(left) & wg_fp64_sign(right)) << 31);
                    return wg_fp64_less(left, right) ? right : left;
                }

                uvec2 wg_fp64_from_fp32(uint bits) {
                    uint sign = wg_fp32_sign(bits);
                    uint exponent = wg_fp32_exp(bits), fraction = wg_fp32_frac(bits);
                    if (wg_fp32_nan(bits)) return wg_fp64_qnan();
                    if (wg_fp32_inf(bits)) return uvec2(0u, (sign << 31) | 0x7ff00000u);
                    if (wg_fp32_zero(bits)) return uvec2(0u, sign << 31);
                    int unbiased;
                    if (exponent == 0u) {
                        unbiased = -126;
                        while ((fraction & 0x00800000u) == 0u) { fraction <<= 1; unbiased--; }
                    } else unbiased = int(exponent) - 127;
                    uint fraction64 = (fraction & 0x7fffffu);
                    return uvec2(fraction64 << 29, (sign << 31) | (uint(unbiased + 1023) << 20) | (fraction64 >> 3));
                }
                // An int must be widened directly.  Going through the FP32
                // carrier loses low bits for coordinates outside +/-2^24
                // (and used to turn Integer.MIN_VALUE into a rounded value).
                uvec2 wg_fp64_from_int(int value) {
                    if (value == 0) return uvec2(0u);
                    uint sign = value < 0 ? 1u : 0u;
                    uint magnitude = value < 0 ? uint(-(value + 1)) + 1u : uint(value);
                    int highest = 31;
                    while (highest > 0 && ((magnitude >> uint(highest)) & 1u) == 0u) highest--;
                    // The magnitude has at most 32 significant bits, so it
                    // can be aligned exactly into the binary64 significand.
                    uvec2 significand = wg_u64_shl(uvec2(magnitude, 0u), uint(52 - highest));
                    return uvec2(significand.x,
                            (sign << 31) | (uint(1023 + highest) << 20)
                                    | (significand.y & 0xfffffu));
                }
                uint wg_fp64_to_fp32(uvec2 bits) {
                    uint sign = wg_fp64_sign(bits), exponent = wg_fp64_exp(bits);
                    if (wg_fp64_nan(bits)) return wg_fp32_qnan();
                    if (wg_fp64_inf(bits)) return (sign << 31) | 0x7f800000u;
                    if (wg_fp64_zero(bits)) return sign << 31;
                    uvec2 fraction = wg_fp64_frac(bits);
                    int unbiased;
                    uvec2 significand;
                    if (exponent == 0u) {
                        unbiased = -1022;
                        significand = fraction;
                        while ((significand.y & 0x00100000u) == 0u) { significand = wg_u64_shl1(significand); unbiased--; }
                    } else {
                        unbiased = int(exponent) - 1023;
                        significand = uvec2(fraction.x, fraction.y | 0x00100000u);
                    }
                    uint targetExponent = uint(unbiased + 127);
                    uint top = (significand.y << 3) | (significand.x >> 29);
                    uint guard = (significand.x >> 28) & 1u;
                    uint sticky = (significand.x & 0x0fffffffu) != 0u ? 1u : 0u;
                    uint extended = (top << 3) | (guard << 2) | sticky;
                    return wg_fp32_pack(sign, int(targetExponent), extended);
                }
                uvec2 wg_fp64_floor(uvec2 bits) {
                    if (!wg_fp64_finite(bits) || wg_fp64_zero(bits)) return bits;
                    uint exponent = wg_fp64_exp(bits), sign = wg_fp64_sign(bits);
                    if (exponent == 0u) return sign == 0u ? uvec2(0u, 0u) : uvec2(0u, 0xbff00000u);
                    int unbiased = int(exponent) - 1023;
                    if (unbiased >= 52) return bits;
                    if (unbiased < 0) return sign == 0u ? uvec2(0u, 0u) : uvec2(0u, 0xbff00000u);
                    uint fractionalBits = uint(52 - unbiased);
                    uvec2 fraction = wg_fp64_frac(bits);
                    bool changed;
                    if (fractionalBits < 32u) {
                        uint mask = (1u << fractionalBits) - 1u;
                        changed = (fraction.x & mask) != 0u;
                        fraction.x &= ~mask;
                    } else if (fractionalBits == 32u) {
                        changed = fraction.x != 0u;
                        fraction.x = 0u;
                    } else {
                        uint mask = (1u << (fractionalBits - 32u)) - 1u;
                        changed = fraction.x != 0u || (fraction.y & mask) != 0u;
                        fraction.x = 0u;
                        fraction.y &= ~mask;
                    }
                    uvec2 truncated = uvec2(fraction.x, (sign << 31) | (exponent << 20) | fraction.y);
                    return sign != 0u && changed ? wg_fp64_add(truncated, uvec2(0u, 0xbff00000u)) : truncated;
                }

                // Convert a finite binary64 floor to a signed 32-bit lattice
                // coordinate.  Captured Minecraft noise is wrapped before
                // this conversion, so an out-of-range result is a failed
                // invocation rather than an implementation-defined cast.
                int wg_fp64_floor_to_i32(uvec2 bits) {
                    if (wg_fp64_nan(bits) || wg_fp64_inf(bits)) { wg_failed = true; return 0; }
                    if (wg_fp64_zero(bits)) return 0;
                    uint exponent = wg_fp64_exp(bits), sign = wg_fp64_sign(bits);
                    int unbiased = int(exponent) - 1023;
                    if (exponent == 0u || unbiased < 0) return sign != 0u ? -1 : 0;
                    if (unbiased > 31) { wg_failed = true; return 0; }

                    uvec2 significand = wg_fp64_frac(bits);
                    significand.y |= 0x00100000u;
                    int shift = 52 - unbiased;
                    uint magnitude;
                    bool fractional;
                    if (shift >= 32) {
                        uint lowShift = uint(shift - 32);
                        magnitude = significand.y >> lowShift;
                        fractional = significand.x != 0u
                                || (lowShift != 0u && (significand.y & ((1u << lowShift) - 1u)) != 0u);
                    } else if (shift > 0) {
                        magnitude = (significand.x >> uint(shift)) | (significand.y << uint(32 - shift));
                        fractional = (significand.x & ((1u << uint(shift)) - 1u)) != 0u;
                    } else {
                        magnitude = significand.x;
                        fractional = false;
                        if (significand.y != 0u) { wg_failed = true; return 0; }
                    }
                    if (sign != 0u && fractional) {
                        if (magnitude == 0xffffffffu) { wg_failed = true; return 0; }
                        magnitude++;
                    }
                    if (sign == 0u) {
                        if (magnitude >= 0x80000000u) { wg_failed = true; return 0; }
                        return int(magnitude);
                    }
                    if (magnitude > 0x80000000u) { wg_failed = true; return 0; }
                    if (magnitude == 0x80000000u) return -2147483647 - 1;
                    return -int(magnitude);
                }
                bool wg_fp64_nonzero(uvec2 bits) { return !wg_fp64_zero(bits) && !wg_fp64_nan(bits); }

                uvec4 wg_sqrt_u128_shl58(uvec2 value) {
                    return uvec4(0u, value.x << 26u,
                            (value.x >> 6u) | (value.y << 26u), value.y >> 6u);
                }
                uvec4 wg_sqrt_u128_add(uvec4 left, uvec4 right) {
                    uvec2 low = wg_u64_add(uvec2(left.x, left.y), uvec2(right.x, right.y));
                    bool carry = wg_u64_less(low, uvec2(left.x, left.y));
                    uvec2 high = wg_u64_add(uvec2(left.z, left.w), uvec2(right.z, right.w));
                    if (carry) high = wg_u64_add(high, uvec2(1u, 0u));
                    return uvec4(low.x, low.y, high.x, high.y);
                }
                bool wg_sqrt_u128_less(uvec4 left, uvec4 right) {
                    if (left.w != right.w) return left.w < right.w;
                    if (left.z != right.z) return left.z < right.z;
                    if (left.y != right.y) return left.y < right.y;
                    return left.x < right.x;
                }
                uvec4 wg_sqrt_u128_sub(uvec4 left, uvec4 right) {
                    uvec2 leftLow = uvec2(left.x, left.y), rightLow = uvec2(right.x, right.y);
                    uvec2 low = wg_u64_sub(leftLow, rightLow);
                    bool borrow = wg_u64_less(leftLow, rightLow);
                    uvec2 high = wg_u64_sub(uvec2(left.z, left.w), uvec2(right.z, right.w));
                    if (borrow) high = wg_u64_sub(high, uvec2(1u, 0u));
                    return uvec4(low.x, low.y, high.x, high.y);
                }
                uvec4 wg_sqrt_u128_shr1(uvec4 value) {
                    return uvec4((value.x >> 1u) | (value.y << 31u),
                            (value.y >> 1u) | (value.z << 31u),
                            (value.z >> 1u) | (value.w << 31u), value.w >> 1u);
                }
                uvec4 wg_sqrt_u128_shr2(uvec4 value) {
                    return uvec4((value.x >> 2u) | (value.y << 30u),
                            (value.y >> 2u) | (value.z << 30u),
                            (value.z >> 2u) | (value.w << 30u), value.w >> 2u);
                }
                uvec4 wg_sqrt_u128_isqrt(uvec4 value) {
                    uvec4 root = uvec4(0u), bit = uvec4(0u, 0u, 0u, 1u << 14);
                    uvec4 remainder = value;
                    while (bit.x != 0u || bit.y != 0u || bit.z != 0u || bit.w != 0u) {
                        uvec4 candidate = wg_sqrt_u128_add(root, bit);
                        if (!wg_sqrt_u128_less(remainder, candidate)) {
                            remainder = wg_sqrt_u128_sub(remainder, candidate);
                            root = wg_sqrt_u128_add(wg_sqrt_u128_shr1(root), bit);
                        } else {
                            root = wg_sqrt_u128_shr1(root);
                        }
                        bit = wg_sqrt_u128_shr2(bit);
                    }
                    return uvec4(root.x, root.y, (remainder.x != 0u || remainder.y != 0u
                            || remainder.z != 0u || remainder.w != 0u) ? 1u : 0u, 0u);
                }
                uvec2 wg_fp64_sqrt(uvec2 bits) {
                    uint exponent = wg_fp64_exp(bits);
                    if (wg_fp64_nan(bits)) return wg_fp64_qnan();
                    if (wg_fp64_zero(bits)) return bits;
                    if (wg_fp64_sign(bits) != 0u) return wg_fp64_qnan();
                    if (wg_fp64_inf(bits)) return bits;
                    int unbiased = exponent == 0u ? -1022 : int(exponent) - 1023;
                    uvec2 mantissa = wg_fp64_frac(bits);
                    if (exponent != 0u) mantissa.y |= 0x00100000u;
                    else while ((mantissa.y & 0x00100000u) == 0u) { mantissa = wg_u64_shl1(mantissa); unbiased--; }
                    if ((unbiased & 1) != 0) { mantissa = wg_u64_shl1(mantissa); unbiased--; }
                    uvec4 root = wg_sqrt_u128_isqrt(wg_sqrt_u128_shl58(mantissa));
                    uvec2 extended = uvec2(root.x, root.y);
                    if (root.z != 0u) extended.x |= 1u;
                    return wg_fp64_pack(0u, unbiased / 2 + 1023, extended);
                }
                """;
    }
}
