// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ProviderActivityTest {
    @Test void cancelledLogicalRequestCannotDisposeWhileItsBackendStillRuns() throws Exception {
        AtomicInteger disposed = new AtomicInteger();
        ProviderActivity activity = new ProviderActivity(disposed::incrementAndGet);
        activity.beginRequest();
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        CompletableFuture<Integer> computation = CompletableFuture.supplyAsync(() -> activity.compute(() -> {
            started.countDown(); await(finish); return 42;
        }));
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            activity.endRequest(); // subscriber cancellation, not backend completion
            assertFalse(activity.close(Duration.ofMillis(5)));
            assertEquals(0, activity.snapshot().requests());
            assertEquals(1, activity.snapshot().computations());
            assertFalse(activity.snapshot().releaseStarted());
            assertEquals(0, disposed.get());
            assertThrows(IllegalStateException.class, activity::beginRequest);
            assertThrows(IllegalStateException.class, () -> activity.compute(() -> fail("late backend must not start")));
        } finally { finish.countDown(); }
        assertEquals(42, computation.get(1, TimeUnit.SECONDS));
        assertTrue(activity.close(Duration.ofSeconds(1)));
        assertEquals(1, disposed.get());
        assertEquals(0, activity.snapshot().computations());
    }

    @Test void completedBackendStillWaitsForItsLogicalPublicationRequest() {
        AtomicInteger disposed = new AtomicInteger();
        ProviderActivity activity = new ProviderActivity(disposed::incrementAndGet);
        activity.beginRequest();
        assertEquals(5, activity.compute(() -> 5));
        assertFalse(activity.close(Duration.ofMillis(5)));
        assertEquals(0, disposed.get());
        activity.endRequest();
        assertTrue(activity.close(Duration.ofSeconds(1)));
        assertEquals(1, disposed.get());
    }

    @Test void repeatedCloseCannotDisposeTwiceAndUnderflowIsExplicit() {
        AtomicInteger disposed = new AtomicInteger();
        ProviderActivity activity = new ProviderActivity(disposed::incrementAndGet);
        assertThrows(IllegalStateException.class, activity::endRequest);
        assertTrue(activity.close(Duration.ofSeconds(1)));
        assertTrue(activity.close(Duration.ofSeconds(1)));
        assertEquals(1, disposed.get());
        assertTrue(activity.snapshot().closed());
        assertTrue(activity.snapshot().releaseFinished());
        assertFalse(activity.snapshot().releaseFailed());
    }

    @Test void operationFailureRetiresPhysicalOwnership() {
        AtomicInteger disposed = new AtomicInteger();
        ProviderActivity activity = new ProviderActivity(disposed::incrementAndGet);
        activity.beginRequest();
        RuntimeException failure = new IllegalArgumentException("compute failed");
        assertSame(failure, assertThrows(RuntimeException.class, () -> activity.compute(() -> { throw failure; })));
        assertEquals(0, activity.snapshot().computations());
        activity.endRequest();
        assertTrue(activity.close(Duration.ofSeconds(1)));
        assertEquals(1, disposed.get());
    }

    @Test void invalidDeadlineDoesNotChangeAdmissionState() {
        ProviderActivity activity = new ProviderActivity(() -> fail("invalid close must not dispose"));
        assertThrows(IllegalArgumentException.class, () -> activity.close(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> activity.close(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> activity.close(Duration.ofSeconds(Long.MAX_VALUE)));
        assertFalse(activity.snapshot().closed());
    }

    @Test void interruptionClosesAdmissionWithoutPretendingPhysicalDrain() {
        AtomicInteger disposed = new AtomicInteger();
        ProviderActivity activity = new ProviderActivity(disposed::incrementAndGet);
        activity.beginRequest();
        Thread.currentThread().interrupt();
        try {
            assertFalse(activity.close(Duration.ofSeconds(1)));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, disposed.get());
        } finally { Thread.interrupted(); activity.endRequest(); }
        assertTrue(activity.close(Duration.ofSeconds(1)));
        assertEquals(1, disposed.get());
    }

    @Test void disposalFailureIsRetainedAndCannotBecomeAHealthySecondClose() {
        AtomicInteger disposed = new AtomicInteger();
        ProviderActivity activity = new ProviderActivity(() -> { disposed.incrementAndGet(); throw new IllegalStateException("dispose failed"); });
        assertThrows(IllegalStateException.class, () -> activity.close(Duration.ofSeconds(1)));
        assertTrue(activity.snapshot().releaseFailed());
        assertThrows(IllegalStateException.class, () -> activity.close(Duration.ofSeconds(1)));
        assertEquals(1, disposed.get());
    }

    @Test void alreadyClaimedButUnfinishedDisposalIsNotReportedAsComplete() throws Exception {
        AtomicInteger disposed = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        ProviderActivity activity = new ProviderActivity(() -> { disposed.incrementAndGet(); entered.countDown(); await(finish); });
        activity.beginRequest();
        assertFalse(activity.close(Duration.ofMillis(5)));
        CompletableFuture<Void> retired = CompletableFuture.runAsync(activity::endRequest);
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertFalse(activity.close(Duration.ofMillis(5)));
            assertTrue(activity.snapshot().releaseStarted());
            assertFalse(activity.snapshot().releaseFinished());
        } finally { finish.countDown(); }
        retired.get(1, TimeUnit.SECONDS);
        assertTrue(activity.close(Duration.ofSeconds(1)));
        assertEquals(1, disposed.get());
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("test latch deadline"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
}
