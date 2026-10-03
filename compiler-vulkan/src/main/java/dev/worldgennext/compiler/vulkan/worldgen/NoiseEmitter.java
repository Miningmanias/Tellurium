// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan.worldgen;

import dev.worldgennext.semantic.program.ValueType;
import dev.worldgennext.semantic.snapshot.BlendedNoiseParameters;
import dev.worldgennext.semantic.snapshot.NoiseParameters;
import java.util.Objects;

/**
 * Public entry point for device-side captured-noise emission.
 *
 * <p>The implementation is stateful only while one shader is being built: it
 * interns captured tables and helper functions so a graph that reuses a noise
 * snapshot does not duplicate its immutable permutation data. It never
 * accepts a live loader noise object or a precomputed answer.</p>
 */
public final class NoiseEmitter {
    private final CapturedNoiseEmitter delegate = new CapturedNoiseEmitter();

    /** Emit one captured NormalNoise invocation and its helper dependencies. */
    public String emit(NoiseParameters.CapturedNoise captured, double xzScale,
                       double yScale, ValueType type) {
        return delegate.emit(Objects.requireNonNull(captured, "captured"), xzScale, yScale, type);
    }

    /** Emit one invocation using already-formed raw binary64 coordinates. */
    public String emitRaw(NoiseParameters.CapturedNoise captured, String x,
                          String y, String z) {
        return delegate.emitRaw(Objects.requireNonNull(captured, "captured"), x, y, z);
    }

    /** Emit captured legacy BlendedNoise, including its smeared-Y octaves. */
    public String emitBlended(BlendedNoiseParameters parameters, ValueType type) {
        return delegate.emitBlended(Objects.requireNonNull(parameters, "parameters"), type);
    }

    /** Package-private exact entry point for runtime metadata keyed by the emitted function name. */
    String emitBlendedFunction(BlendedNoiseParameters parameters) {
        return delegate.emitBlendedFunction(Objects.requireNonNull(parameters, "parameters"));
    }

    /** Convert a raw binary64 carrier to the requested shader value type. */
    public String convert(String rawValue, ValueType type) {
        return delegate.convert(Objects.requireNonNull(rawValue, "rawValue"), type);
    }

    /** Package-private aggregation hook used by the worldgen compiler. */
    String source() {
        return delegate.source();
    }
}
