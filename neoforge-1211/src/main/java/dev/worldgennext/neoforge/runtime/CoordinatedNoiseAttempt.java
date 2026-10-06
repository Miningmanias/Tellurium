// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import dev.worldgennext.neoforge.version.Version;

import dev.worldgennext.engine.GenerationStage;
import dev.worldgennext.engine.worldgen.BackendResult;
import dev.worldgennext.engine.worldgen.CommitCoordinator;
import dev.worldgennext.engine.worldgen.GenerationRequest;
import dev.worldgennext.engine.worldgen.RequestSubscription;
import dev.worldgennext.engine.worldgen.ResourceEstimate;
import dev.worldgennext.engine.worldgen.StageExecutor;
import dev.worldgennext.engine.worldgen.WorkKey;
import dev.worldgennext.engine.worldgen.WorkRecord;
import dev.worldgennext.semantic.identity.ContextIdentity;
import dev.worldgennext.semantic.program.EvaluationDomain;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loader-side adapter for the runtime-owned coordinator.
 *
 * <p>Capture and ownership remain caller responsibilities. This class only
 * turns one captured NOISE attempt into a bounded coordinator request, binds
 * the backend/commit handlers to that request, and converts its terminal
 * record back into the holder future. No Minecraft object is placed in the
 * core coordinator's work key or retained by the shared queue.</p>
 */
final class CoordinatedNoiseAttempt {
    private static final long MIN_RESOURCE_BYTES = 4 * 1024L;
    private static final long BYTES_PER_BLOCK = 8L;
    private static final long DEFAULT_PROTOTYPE_CPU_LIVE_TIMEOUT_MILLIS = 120_000L;

    private CoordinatedNoiseAttempt() { }

    static CompletableFuture<ChunkAccess> submit(RuntimeComposition runtime,
                                                   ChunkAccess chunk,
                                                   MinecraftOwnershipToken ownership,
                                                   ContextIdentity context,
                                                   StageExecutor backend,
                                                   CommitCoordinator commits,
                                                   AtomicBoolean cancelled,
                                                   Runnable continueOriginalStages,
                                                   Runnable releaseRequest) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(commits, "commits");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(continueOriginalStages, "continueOriginalStages");
        Objects.requireNonNull(releaseRequest, "releaseRequest");

        GenerationRequest request = new GenerationRequest(
                new WorkKey(context, chunk.getPos().x, chunk.getPos().z,
                        GenerationStage.NOISE, "noise", Version.minY(chunk),
                        chunk.getHeight(), EvaluationDomain.BLOCK),
                ownership.ownershipToken(), 0,
                resourceEstimate(chunk),
                context.numericProfile() == dev.worldgennext.semantic.program.NumericProfile.GPU_IEEE_BITS,
                ownership.revision());

        AtomicReference<RequestSubscription> subscriptionRef = new AtomicReference<>();
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) releaseRequest.run();
        };
        CompletableFuture<ChunkAccess> result = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelledFuture = super.cancel(mayInterruptIfRunning);
                if (cancelledFuture) {
                    cancelled.set(true);
                    RequestSubscription subscription = subscriptionRef.get();
                    if (subscription != null) subscription.cancel();
                    release.run();
                }
                return cancelledFuture;
            }
        };

        RequestSubscription subscription;
        try {
            subscription = runtime.coordinator().submit(request, backend, commits);
            subscriptionRef.set(subscription);
        } catch (Throwable failure) {
            release.run();
            result.completeExceptionally(failure);
            return result;
        }

        CompletableFuture<WorkRecord.Terminal> terminal = subscription.completion().toCompletableFuture();
        long timeoutMillis = timeoutMillis(runtime);
        terminal.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenComplete((outcome, failure) -> {
            try {
                if (failure != null) {
                    cancelled.set(true);
                    subscription.cancel();
                    result.completeExceptionally(unwrap(failure));
                    return;
                }
                if (outcome == null) {
                    result.completeExceptionally(new IllegalStateException("coordinator completed without a terminal record"));
                    return;
                }
                if (outcome.state() != WorkRecord.State.COMMITTED) {
                    result.completeExceptionally(new IllegalStateException(
                            "coordinated NOISE attempt ended " + outcome.state() + ": " + outcome.detail()));
                    return;
                }
                try {
                    if (cancelled.get()) throw new CancellationException("coordinated NOISE request was cancelled");
                    continueOriginalStages.run();
                    result.complete(chunk);
                } catch (Throwable downstreamFailure) {
                    result.completeExceptionally(downstreamFailure);
                }
            } finally {
                release.run();
            }
        });
        return result;
    }

    private static ResourceEstimate resourceEstimate(ChunkAccess chunk) {
        long elements;
        try {
            elements = Math.multiplyExact((long) chunk.getHeight(), 256L);
            long material = Math.max(MIN_RESOURCE_BYTES, Math.multiplyExact(elements, BYTES_PER_BLOCK));
            long metadata = Math.max(MIN_RESOURCE_BYTES, Math.multiplyExact(256L, Long.BYTES));
            long application = Math.addExact(material, metadata);
            return new ResourceEstimate(
                    Math.max(MIN_RESOURCE_BYTES, material / 4),
                    Math.max(MIN_RESOURCE_BYTES, material / 2),
                    material,
                    material,
                    application);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Chunk resource estimate overflows", overflow);
        }
    }

    private static long timeoutMillis(RuntimeComposition runtime) {
        Duration timeout = runtime.config().requestTimeout();
        try {
            long configured = Math.max(1L, timeout.toMillis());
            if (!Boolean.parseBoolean(System.getProperty(
                    "worldgennext.prototype.cpuLive", "false"))) return configured;
            String value = System.getProperty("worldgennext.prototype.cpuLiveTimeoutMillis",
                    Long.toString(DEFAULT_PROTOTYPE_CPU_LIVE_TIMEOUT_MILLIS));
            long prototype = Long.parseLong(value);
            if (prototype <= 0) {
                throw new IllegalArgumentException(
                        "worldgennext.prototype.cpuLiveTimeoutMillis must be positive");
            }
            // A prototype override may make the startup fan-out usable, but
            // it cannot silently weaken an operator's configured deadline.
            return Math.max(configured, prototype);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "Invalid worldgennext.prototype.cpuLiveTimeoutMillis", failure);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Runtime request timeout is too large", overflow);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof java.util.concurrent.CompletionException completion
                && completion.getCause() != null) return completion.getCause();
        if (failure instanceof java.util.concurrent.ExecutionException execution
                && execution.getCause() != null) return execution.getCause();
        if (failure instanceof TimeoutException) {
            return new IllegalStateException("coordinated NOISE request exceeded its runtime deadline", failure);
        }
        return failure;
    }
}
