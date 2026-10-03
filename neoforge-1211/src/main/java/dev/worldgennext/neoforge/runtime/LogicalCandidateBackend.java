// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import java.util.Objects;

/** Explicit backend for the isolated verifier, never a production admission policy. */
public enum LogicalCandidateBackend {
    CPU_OWNED, GPU_IEEE_BITS;

    public static LogicalCandidateBackend fromExternal(String value) {
        try { return valueOf(Objects.requireNonNull(value, "logicalBackend").trim()); }
        catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("logicalBackend must be CPU_OWNED or GPU_IEEE_BITS; got " + value, failure);
        }
    }

    /** Pure preflight, callable before linking any Minecraft/GPU provider class. */
    public void requireRuntimeCompatibility(RuntimeComposition runtime) {
        Objects.requireNonNull(runtime, "runtime");
        if (this == GPU_IEEE_BITS && !runtime.canInitializeNative()) {
            throw new IllegalArgumentException("Isolated GPU verification is forbidden in CPU_ONLY mode");
        }
    }
}
