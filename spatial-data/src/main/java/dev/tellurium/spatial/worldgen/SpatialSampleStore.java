// SPDX-License-Identifier: MIT
package dev.tellurium.spatial.worldgen;

import java.util.concurrent.CompletionStage;

/** Loader-composed producer/consumer boundary. It does not issue Minecraft tickets. */
public interface SpatialSampleStore extends AutoCloseable {
    CompletionStage<SampleLease> acquire(SampleKey key, SampleProducer producer);
    /**
     * Acquires a sample from a producer that completes asynchronously.  The
     * store must not join this stage: a producer may depend on the same
     * single worker that invoked the acquire request.
     */
    CompletionStage<SampleLease> acquireAsync(SampleKey key, AsyncSampleProducer producer);
    /** Reserved resident payload bytes, excluding caller-owned copies. */
    default long reservedBytes() { return 0L; }
    /** Number of resident or in-flight identities retained by this store. */
    default int residentEntries() { return 0; }
    static SpatialSampleStore bounded(long maximumBytes, int maximumEntries, java.util.concurrent.Executor executor) { return new BoundedSampleStore(maximumBytes, maximumEntries, executor); }
    default void close() {}
}
