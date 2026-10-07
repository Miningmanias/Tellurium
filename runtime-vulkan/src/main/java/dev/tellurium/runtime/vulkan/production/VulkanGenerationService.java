// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

import dev.tellurium.semantic.execution.ExecutionReceipt;
import dev.tellurium.semantic.execution.ResultBufferLease;
import dev.tellurium.semantic.execution.CapabilityDecision;
import dev.tellurium.semantic.identity.ContextIdentity;
import dev.tellurium.semantic.program.NumericProfile;
import dev.tellurium.runtime.vulkan.DeviceCapabilities;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Persistent production-service lifecycle. The native dispatch function is injected so CPU
 * tests can exercise ownership, identity and failure contracts without loading a driver.
 */
public final class VulkanGenerationService implements AutoCloseable {
    public enum State { NEW, READY, DRAINING, DISABLED, LOST, CLOSED }
    @FunctionalInterface public interface NativeDispatcher { CompletionStage<ResultBufferLease> dispatch(DispatchDescriptor descriptor) throws Exception; }
    private final VulkanContext context;
    private final NativeBudgetLedger budget;
    private final NativeDispatcher dispatcher;
    private final AtomicLong executionIds = new AtomicLong();
    private final List<CompletionStage<?>> inFlight = new ArrayList<>();
    private final java.util.Set<NativeBudgetLedger.Lease> liveLeases = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static final int MAX_FAILURES = 64;
    private final Deque<String> failures = new ArrayDeque<>();
    private State state = State.NEW;
    private final QuarantineLedger quarantine = new QuarantineLedger();
    public VulkanGenerationService(DeviceCapabilities capabilities, long nativeBudgetBytes, NativeDispatcher dispatcher) {
        context = new VulkanContext(Objects.requireNonNull(capabilities), new DeviceGeneration(0)); budget = new NativeBudgetLedger(nativeBudgetBytes); this.dispatcher = Objects.requireNonNull(dispatcher);
    }
    public synchronized State state() { return state; }
    public synchronized long deviceGeneration() { return context.generation().value(); }
    public synchronized void start() { if (state != State.NEW) throw new IllegalStateException("Service cannot start from " + state); context.start(); state = State.READY; }
    public CompletionStage<Submission> submit(ContextIdentity identity, DispatchDescriptor descriptor, NumericProfile profile, String programHash, String abiVersion) {
        return submit(identity, descriptor, profile, programHash, abiVersion,
                ExecutionReceipt.NOT_APPLICABLE, ExecutionReceipt.NOT_APPLICABLE);
    }

    /**
     * Submits a GPU dispatch with the exact source and compiled binary
     * identities that produced it.  The shorter overload remains for
     * lifecycle-only seams whose injected dispatcher has no compiler output;
     * a release GPU path should use this overload.
     */
    public CompletionStage<Submission> submit(ContextIdentity identity, DispatchDescriptor descriptor,
                                              NumericProfile profile, String programHash, String abiVersion,
                                              String shaderHash, String spirvHash) {
        Objects.requireNonNull(identity); Objects.requireNonNull(descriptor); Objects.requireNonNull(profile);
        Objects.requireNonNull(programHash, "programHash"); Objects.requireNonNull(abiVersion, "abiVersion");
        Objects.requireNonNull(shaderHash, "shaderHash"); Objects.requireNonNull(spirvHash, "spirvHash");
        if (profile == NumericProfile.JAVA_REFERENCE) return CompletableFuture.failedFuture(new IllegalArgumentException("Vulkan service cannot execute JAVA_REFERENCE"));
        if (identity.numericProfile() != profile) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Context numeric profile " + identity.numericProfile() + " does not match requested Vulkan profile " + profile));
        }
        if (!identity.programHash().equals(programHash)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Program identity does not match Vulkan submission"));
        }
        if (!identity.abiVersion().equals(abiVersion)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("ABI identity does not match Vulkan submission"));
        }
        CapabilityDecision qualification = new CapabilityQualifier().qualify(context.capabilities(), profile);
        if (!qualification.supported()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Vulkan capability is not qualified for " + profile + ": " + qualification.reason()));
        }
        long requestedBytes;
        try { requestedBytes = Math.addExact(descriptor.inputBytes(), descriptor.outputBytes()); }
        catch (ArithmeticException overflow) { return CompletableFuture.failedFuture(new IllegalArgumentException("Native dispatch byte count overflow", overflow)); }
        NativeBudgetLedger.Lease lease;
        ExecutionReceipt issued;
        synchronized (this) {
            if (state != State.READY) return CompletableFuture.failedFuture(new IllegalStateException("Vulkan service is " + state));
            if (identity.deviceGeneration() != context.generation().value()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Context belongs to device generation "
                        + identity.deviceGeneration() + ", current generation is " + context.generation().value()));
            }
            lease = budget.tryReserve(requestedBytes);
            if (lease == null) return CompletableFuture.failedFuture(new IllegalStateException("Native budget exhausted"));
            try {
                long generation = context.generation().value();
                String backend = profile == NumericProfile.GPU_IEEE_BITS ? "GPU_IEEE_BITS" : "GPU_" + profile.name();
                issued = ExecutionReceipt.issued("gpu-" + executionIds.incrementAndGet(), backend, identity, profile,
                        programHash, abiVersion, shaderHash, spirvHash, generation, descriptor.elementCount());
                liveLeases.add(lease);
            } catch (RuntimeException failure) {
                lease.close();
                return CompletableFuture.failedFuture(failure);
            }
        }
        CompletionStage<ResultBufferLease> nativeResult;
        try { nativeResult = dispatcher.dispatch(descriptor); }
        catch (Throwable failure) {
            recordFailure("dispatch threw: " + detail(failure));
            releaseLease(lease);
            return CompletableFuture.failedFuture(failure);
        }
        if (nativeResult == null) {
            IllegalStateException missing = new IllegalStateException("Native dispatcher returned no completion");
            recordFailure(detail(missing));
            releaseLease(lease);
            return CompletableFuture.failedFuture(missing);
        }
        // Do not derive the tracking future with thenApply. A dispatcher is
        // allowed to complete synchronously, in which case thenApply may run
        // its callback before the future is inserted into inFlight and leave a
        // permanently retained completion behind. The explicit bridge is
        // registered first and removes itself exactly once on every path.
        var completion = new CompletableFuture<Submission>();
        synchronized (this) { inFlight.add(completion); }
        nativeResult.whenComplete((buffer, failure) -> {
            Throwable completionFailure = failure;
            try {
                if (completionFailure == null) {
                    if (buffer == null) throw new IllegalStateException("Native dispatcher completed with a null result");
                    synchronized (this) {
                        if (state == State.LOST || state == State.CLOSED || issued.deviceGeneration() != deviceGeneration()) {
                            buffer.close();
                            buffer = null;
                            throw new IllegalStateException("GPU result belongs to an invalid device generation");
                        }
                    }
                    // Coverage is only called validated for a result that is there in full.
                    if (buffer.isClosed()) throw new IllegalStateException("Native dispatcher completed with a closed result buffer");
                    if (buffer.capacity() < descriptor.outputBytes()) {
                        throw new IllegalStateException("Native result buffer holds " + buffer.capacity() + " bytes, the dispatch produces " + descriptor.outputBytes());
                    }
                    Submission submission = new Submission(
                            issued.completed(descriptor.elementCount()).validated(descriptor.elementCount()), buffer, lease,
                            () -> releaseLease(lease));
                    // The caller may cancel or otherwise complete the returned
                    // future before native work finishes.  In that case the
                    // result has no consumer, so close it here instead of
                    // leaving the result buffer and budget lease live forever.
                    if (!completion.complete(submission)) submission.close();
                } else {
                    // A failed CompletionStage should not retain an
                    // accidentally supplied result buffer.  Native dispatch
                    // failures normally provide null, but ownership remains
                    // unambiguous for custom dispatchers and test seams.
                    if (buffer != null) buffer.close();
                    recordFailure("dispatch failed: " + detail(completionFailure));
                    completion.completeExceptionally(completionFailure);
                }
            } catch (Throwable callbackFailure) {
                if (buffer != null) buffer.close();
                completionFailure = callbackFailure;
                recordFailure("completion callback failed: " + detail(callbackFailure));
                completion.completeExceptionally(callbackFailure);
            } finally {
                boolean quarantined = false;
                synchronized (this) {
                    inFlight.remove(completion);
                    boolean lostGeneration = state == State.LOST && issued.deviceGeneration() != context.generation().value();
                    if (completionFailure != null && !lostGeneration) {
                        quarantine.quarantine(issued.executionId(), lease.bytes(), "dispatch failure");
                        quarantined = true;
                        // No longer a live lease that a close has to wait for; its bytes stay reserved.
                        liveLeases.remove(lease);
                    }
                }
                // What is quarantined was not proven given back: its bytes stay counted against the budget, so
                // repeated failures cannot admit more than the bound in total.  They return with the generation.
                if (completionFailure != null && !quarantined) releaseLease(lease);
            }
        });
        return completion;
    }
    private void releaseLease(NativeBudgetLedger.Lease lease) { if (lease == null) return; lease.close(); synchronized (this) { liveLeases.remove(lease); } }
    public synchronized RuntimeDiagnostics diagnostics() {
        return new RuntimeDiagnostics(state.name(), deviceGeneration(), inFlight.size(), budget.used(),
                quarantine.bytes(), List.copyOf(failures));
    }
    public synchronized void beginDrain() { if (state == State.READY) { state = State.DRAINING; context.beginDrain(); } }
    public CompletionStage<Void> awaitDrain(long timeoutMillis) { if (timeoutMillis <= 0) throw new IllegalArgumentException("Positive drain timeout required"); CompletableFuture<?>[] futures; synchronized (this) { futures = inFlight.stream().map(CompletionStage::toCompletableFuture).toArray(CompletableFuture[]::new); } return CompletableFuture.allOf(futures).orTimeout(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS); }
    public synchronized void disable() { if (state == State.READY) state = State.DISABLED; }
    public synchronized void deviceLost(String reason) {
        if (state == State.LOST || state == State.CLOSED) return;
        String detail = reason == null || reason.isBlank() ? "device lost" : reason;
        recordFailure(detail);
        quarantine.quarantine("device-" + deviceGeneration(), budget.used(), detail);
        state = State.LOST;
        context.markLost();
    }
    @Override public synchronized void close() {
        if (state == State.CLOSED) return;
        if (!inFlight.isEmpty() || !liveLeases.isEmpty()) {
            beginDrain();
            throw new IllegalStateException("Cannot close while GPU work or result leases are outstanding; drain and release first");
        }
        state = State.CLOSED;
        context.close();
        budget.close();
    }
    public record Submission(ExecutionReceipt receipt, ResultBufferLease buffer, NativeBudgetLedger.Lease nativeLease,
                             Runnable onClose) implements AutoCloseable {
        public Submission {
            Objects.requireNonNull(receipt); Objects.requireNonNull(buffer); Objects.requireNonNull(nativeLease); Objects.requireNonNull(onClose);
        }
        public Submission(ExecutionReceipt receipt, ResultBufferLease buffer, NativeBudgetLedger.Lease nativeLease) {
            this(receipt, buffer, nativeLease, () -> {});
        }
        @Override public void close() {
            try { buffer.close(); }
            finally {
                try { nativeLease.close(); }
                finally { onClose.run(); }
            }
        }
    }

    private synchronized void recordFailure(String detail) {
        if (detail == null || detail.isBlank()) detail = "unspecified native failure";
        if (failures.size() == MAX_FAILURES) failures.removeFirst();
        failures.addLast(detail);
    }

    private static String detail(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + ": " + (message == null || message.isBlank() ? "no detail" : message);
    }
}
