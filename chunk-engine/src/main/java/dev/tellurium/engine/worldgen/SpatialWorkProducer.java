// SPDX-License-Identifier: MIT
package dev.tellurium.engine.worldgen;

import dev.tellurium.spatial.worldgen.AsyncSampleProducer;
import dev.tellurium.spatial.worldgen.AquiferCellAtlas;
import dev.tellurium.spatial.worldgen.ColumnSamples;
import dev.tellurium.spatial.worldgen.LatticeSamples;
import dev.tellurium.spatial.worldgen.SampleKey;
import dev.tellurium.spatial.worldgen.SampleProducer;
import dev.tellurium.spatial.worldgen.SurfaceColumnAtlas;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface SpatialWorkProducer extends AsyncSampleProducer {
    @Override CompletionStage<double[]> produce(SampleKey key);

    /** Bridges a synchronous typed producer without joining on a worker. */
    static SpatialWorkProducer from(SampleProducer producer) {
        AsyncSampleProducer delegate = AsyncSampleProducer.from(producer);
        return delegate::produce;
    }

    static SpatialWorkProducer lattice(SampleProducer.TypedFactory<LatticeSamples> factory) {
        AsyncSampleProducer delegate = AsyncSampleProducer.lattice(factory);
        return delegate::produce;
    }

    static SpatialWorkProducer column(SampleProducer.TypedFactory<ColumnSamples> factory) {
        AsyncSampleProducer delegate = AsyncSampleProducer.column(factory);
        return delegate::produce;
    }

    static SpatialWorkProducer surfaceHeights(SampleProducer.TypedFactory<SurfaceColumnAtlas> factory) {
        AsyncSampleProducer delegate = AsyncSampleProducer.surfaceHeights(factory);
        return delegate::produce;
    }

    static SpatialWorkProducer aquiferLevels(SampleProducer.TypedFactory<AquiferCellAtlas> factory) {
        AsyncSampleProducer delegate = AsyncSampleProducer.aquiferLevels(factory);
        return delegate::produce;
    }
}
