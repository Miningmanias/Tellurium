// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.fast;

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
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellurium.fast.lazyNoiseWrap", "true"));

    /** Developer switch for measuring {@link #sum}: false builds the constructor's sum at once, as the game does. */
    public static final boolean SUM = Boolean.parseBoolean(System.getProperty("tellurium.fast.lazyNoiseSum", "true"));

    private java.util.function.Supplier<DensityFunction> pending;
    private DensityFunction resolved;

    public LazyMappedDensity(DensityFunction source, Visitor visitor) {
        this.pending = () -> source.mapAll(visitor);
    }

    private LazyMappedDensity(java.util.function.Supplier<DensityFunction> pending) {
        this.pending = pending;
    }

    /**
     * {@code DensityFunctions.add(first, second)}, built on first use.  Building a sum asks both terms for their
     * bounds, which a deferred term can only answer by being mapped: NoiseChunk's constructor adds the
     * structure term to the mapped final density, and that alone used to undo the deferral of the largest
     * tree for every chunk.
     */
    public static LazyMappedDensity sum(LazyMappedDensity first, DensityFunction second) {
        return new LazyMappedDensity(() -> net.minecraft.world.level.levelgen.DensityFunctions.add(first.resolve(), second));
    }

    public DensityFunction resolve() {
        DensityFunction result = resolved;
        if (result == null) {
            result = pending.get();
            resolved = result;
            pending = null;
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
