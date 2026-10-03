// SPDX-License-Identifier: MIT
// Reference fragments for the generated GPU_IEEE_BITS profile.
// Two-limb raw carrier layout: x is low 32 bits and y is high 32 bits.
uint wg_bits64_sign(uvec2 bits) { return bits.y >> 31; }
uint wg_bits64_exp(uvec2 bits) { return (bits.y >> 20) & 0x7ffu; }
uvec2 wg_bits64_canonical_nan() { return uvec2(0u, 0x7ff80000u); }
