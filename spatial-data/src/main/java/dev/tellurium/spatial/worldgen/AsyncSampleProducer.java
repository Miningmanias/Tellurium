// SPDX-License-Identifier: MIT
package dev.tellurium.spatial.worldgen;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Nonblocking producer boundary for samples whose work is already scheduled
 * elsewhere (for example, by a chunk coordinator or a native completion
 * pump).  The returned stage owns only the producer result; the sample store
 * owns validation, caching and consumer leases.
 */
@FunctionalInterface
public interface AsyncSampleProducer {
    CompletionStage<double[]> produce(SampleKey key);

    /** Adapts a synchronous producer without making the store wait on it. */
    static AsyncSampleProducer from(SampleProducer producer) {
        Objects.requireNonNull(producer, "producer");
        return key -> {
            try {
                return CompletableFuture.completedFuture(producer.produce(key));
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        };
    }

    static AsyncSampleProducer lattice(SampleProducer.TypedFactory<LatticeSamples> factory) {
        return from(SampleProducer.lattice(factory));
    }

    static AsyncSampleProducer column(SampleProducer.TypedFactory<ColumnSamples> factory) {
        return from(SampleProducer.column(factory));
    }

    static AsyncSampleProducer surfaceHeights(SampleProducer.TypedFactory<SurfaceColumnAtlas> factory) {
        return from(SampleProducer.surfaceHeights(factory));
    }

    static AsyncSampleProducer aquiferLevels(SampleProducer.TypedFactory<AquiferCellAtlas> factory) {
        return from(SampleProducer.aquiferLevels(factory));
    }
}
