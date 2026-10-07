// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

/** A diagnostic domain is never promoted to full floating-point qualification. */
public enum Fp64Profile {
    STRICT,
    NORMAL_RANGE_DIAGNOSTIC
}
