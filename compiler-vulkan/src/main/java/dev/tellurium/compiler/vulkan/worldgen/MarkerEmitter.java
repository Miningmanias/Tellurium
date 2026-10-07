// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.worldgen;

/**
 * Explicit boundary for standalone marker emission. Marker cache semantics
 * are emitted together with the typed node graph by
 * {@link WorldgenShaderCompiler}; a comment-only fragment would not preserve
 * those semantics.
 */
public final class MarkerEmitter {
    public String emit(String marker) {
        if (marker == null || marker.isBlank()) throw new IllegalArgumentException("Marker required");
        throw new UnsupportedOperationException(
                "Standalone marker emission requires the enclosing typed worldgen graph");
    }
}
