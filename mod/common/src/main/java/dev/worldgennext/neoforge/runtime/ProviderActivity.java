// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Logical cancellation does not prove that a backend has finished using provider resources. */
final class ProviderActivity {
    record Snapshot(int requests, int computations, boolean closed, boolean releaseStarted,
                    boolean releaseFinished, boolean releaseFailed) { }

    private final Runnable disposer;
    private int requests;
    private int computations;
    private boolean closed;
    private boolean releaseStarted;
    private boolean releaseFinished;
    private Throwable releaseFailure;

    ProviderActivity(Runnable disposer) { this.disposer = Objects.requireNonNull(disposer, "disposer"); }

    synchronized void beginRequest() {
        requireOpen();
        requests = Math.incrementExact(requests);
    }

    void endRequest() { end(false); }

    <T> T compute(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        synchronized (this) {
            requireOpen();
            computations = Math.incrementExact(computations);
        }
        Throwable operationFailure = null;
        try { return operation.get(); }
        catch (RuntimeException | Error failure) { operationFailure = failure; throw failure; }
        finally {
            try { end(true); }
            catch (RuntimeException | Error cleanupFailure) {
                if (operationFailure == null) throw cleanupFailure;
                if (operationFailure != cleanupFailure) operationFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    synchronized boolean closed() { return closed; }

    synchronized Snapshot snapshot() {
        return new Snapshot(requests, computations, closed, releaseStarted, releaseFinished, releaseFailure != null);
    }

    /** False means disposal is deferred, not that active native ownership was released. */
    boolean close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Positive close timeout required");
        final long timeoutNanos;
        try { timeoutNanos = timeout.toNanos(); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("Close timeout overflows", overflow); }
        long started = System.nanoTime();
        boolean disposeHere = false;
        synchronized (this) {
            closed = true;
            while (!releaseFinished) {
                if (claimRelease()) { disposeHere = true; break; }
                long remaining = timeoutNanos - (System.nanoTime() - started);
                if (remaining <= 0) return false;
                try { TimeUnit.NANOSECONDS.timedWait(this, remaining); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        if (disposeHere) dispose();
        synchronized (this) {
            if (releaseFailure != null) throw new IllegalStateException("Provider resource disposal failed", releaseFailure);
            return releaseFinished;
        }
    }

    private void end(boolean computation) {
        boolean disposeHere;
        synchronized (this) {
            if (computation) {
                if (computations <= 0) throw new IllegalStateException("Provider computation accounting underflow");
                computations--;
            } else {
                if (requests <= 0) throw new IllegalStateException("Provider request accounting underflow");
                requests--;
            }
            disposeHere = claimRelease();
            notifyAll();
        }
        if (disposeHere) dispose();
    }

    private boolean claimRelease() {
        if (!closed || requests != 0 || computations != 0 || releaseStarted) return false;
        releaseStarted = true;
        return true;
    }

    private void dispose() {
        try { disposer.run(); }
        catch (RuntimeException | Error failure) {
            synchronized (this) { releaseFailure = failure; }
            throw failure;
        } finally {
            synchronized (this) { releaseFinished = true; notifyAll(); }
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("NOISE provider is closed");
    }
}
