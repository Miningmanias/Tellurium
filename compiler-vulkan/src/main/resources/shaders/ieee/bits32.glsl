// SPDX-License-Identifier: MIT
// Reference fragments for the generated GPU_IEEE_BITS profile. Values are uint raw carriers.
uint wg_bits32_sign(uint bits) { return bits >> 31; }
uint wg_bits32_exp(uint bits) { return (bits >> 23) & 0xffu; }
uint wg_bits32_frac(uint bits) { return bits & 0x7fffffu; }
