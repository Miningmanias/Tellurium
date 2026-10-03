// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CompletionObservationTest {
    @Test void successfulCompletionIsObservedOnceAndRetainsItsValue() {
        CompletableFuture<String> source = new CompletableFuture<>();
        AtomicInteger observations = new AtomicInteger();
        var result = CompletionObservation.observe(source, (value, failure) -> {
            observations.incrementAndGet(); assertEquals("published", value); assertNull(failure);
        });
        source.complete("published");
        assertEquals("published", result.join());
        assertEquals(1, observations.get());
        assertFalse(result.cancel(true));
        assertFalse(source.isCancelled());
    }

    @Test void cancellingObservedFutureForwardsToActualRequest() {
        AtomicInteger cancellations = new AtomicInteger(), observations = new AtomicInteger();
        CompletableFuture<String> source = new CompletableFuture<>() {
            @Override public boolean cancel(boolean interrupt) { cancellations.incrementAndGet(); return super.cancel(interrupt); }
        };
        var result = CompletionObservation.observe(source, (value, failure) -> {
            observations.incrementAndGet(); assertInstanceOf(CancellationException.class, failure);
        });
        assertTrue(result.cancel(false));
        assertTrue(source.isCancelled());
        assertTrue(result.isCancelled());
        assertEquals(1, cancellations.get());
        assertEquals(1, observations.get());
    }

    @Test void sourceCancellationIsAlsoVisibleToItsObserver() {
        CompletableFuture<String> source = new CompletableFuture<>();
        var result = CompletionObservation.observe(source, (value, failure) -> { });
        source.cancel(false);
        assertTrue(result.isCancelled());
    }

    @Test void backendFailureStaysPrimaryWhenObservationAlsoFails() {
        CompletableFuture<String> source = new CompletableFuture<>();
        RuntimeException backend = new IllegalArgumentException("backend"), evidence = new IllegalStateException("evidence");
        var result = CompletionObservation.observe(source, (value, failure) -> { throw evidence; });
        source.completeExceptionally(backend);
        assertSame(backend, assertThrows(CompletionException.class, result::join).getCause());
        assertArrayEquals(new Throwable[]{evidence}, backend.getSuppressed());
    }

    @Test void observationFailureFailsVerifierWithoutRewritingSourcePublication() {
        CompletableFuture<String> source = new CompletableFuture<>();
        RuntimeException evidence = new IllegalStateException("write failed");
        var result = CompletionObservation.observe(source, (value, failure) -> { throw evidence; });
        source.complete("published");
        assertEquals("published", source.join());
        assertSame(evidence, assertThrows(CompletionException.class, result::join).getCause());
    }

    @Test void incompleteObservationContractsAreRejected() {
        assertThrows(NullPointerException.class, () -> CompletionObservation.observe(null, (value, failure) -> { }));
        assertThrows(NullPointerException.class, () -> CompletionObservation.observe(new CompletableFuture<>(), null));
    }
}
