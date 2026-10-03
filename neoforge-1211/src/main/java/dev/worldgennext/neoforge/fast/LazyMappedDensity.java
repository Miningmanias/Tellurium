// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.fast;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * {@code source.mapAll(visitor)}, evaluated on first use instead of at construction.
 *
 * <p>NoiseChunk maps the whole noise router through its cache-wrapping visitor in
 * its constructor.  A chunk whose NOISE and SURFACE came from the GPU never
 * evaluates final density or the ore-vein functions, yet their trees are the
 * bulk of that mapping.  The mapping is a pure function of the tree and the
 * NoiseChunk, so performing it later yields the same wrapped function; the
 * owning NoiseChunk resolves every instance before interpolation starts, since
 * interpolators register themselves while being wrapped.</p>
 *
 * <p>Used by one thread at a time, like the NoiseChunk that owns it.</p>
 */
public final class LazyMappedDensity implements DensityFunction {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("worldgennext.fast.lazyNoiseWrap", "true"));

    private DensityFunction source;
    private Visitor visitor;
    private DensityFunction resolved;

    public LazyMappedDensity(DensityFunction source, Visitor visitor) {
        this.source = source;
        this.visitor = visitor;
    }

    public DensityFunction resolve() {
        DensityFunction result = resolved;
        if (result == null) {
            result = source.mapAll(visitor);
            resolved = result;
            source = null;
            visitor = null;
        }
        return result;
    }

    @Override
    public double compute(FunctionContext context) {
        return resolve().compute(context);
    }

    @Override
    public void fillArray(double[] array, ContextProvider contextProvider) {
        resolve().fillArray(array, contextProvider);
    }

    @Override
    public DensityFunction mapAll(Visitor mapper) {
        return resolve().mapAll(mapper);
    }

    @Override
    public double minValue() {
        return resolve().minValue();
    }

    @Override
    public double maxValue() {
        return resolve().maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return resolve().codec();
    }
}
