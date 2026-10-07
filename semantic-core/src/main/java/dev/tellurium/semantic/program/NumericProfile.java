// SPDX-License-Identifier: MIT
package dev.tellurium.semantic.program;

/** Numeric execution identities are part of every reusable program/result. */
public enum NumericProfile {
    JAVA_REFERENCE,
    GPU_IEEE_BITS,
    NATIVE_QUALIFIED,
    /** Explicit prototype only; never eligible for exact integer-profile qualification. */
    GPU_NATIVE_DRAFT
}
