// SPDX-License-Identifier: MIT
package dev.worldgennext.engine.worldgen;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded subordinate coordinator; Minecraft remains the holder/ticket authority. */
public final class WorldgenCoordinator implements AutoCloseable {
    private final Executor executor;
    /**
     * Mailbox used for backend validation and publication. It defaults to the
     * coordinator worker for pure/runtime use, but loader integrations bind it
     * to the authoritative game-thread executor before admitting live work.
     */
    private volatile Executor completionExecutor;
    private final ResourceAdmission admission;
    private final StageExecutor stageExecutor;
    private final CommitCoordinator commits;
    private final FairWorkQueue queue;
    private final Map<WorkKey, WorkRecord> records = new ConcurrentHashMap<>();
    private final Map<WorkKey, CompletableFuture<WorkRecord.Terminal>> execution = new ConcurrentHashMap<>();
    /** Terminal outcomes prevent a duplicate commit while their identity is current. */
    private final LinkedHashMap<WorkKey, WorkRecord> terminalRecords = new LinkedHashMap<>(16, .75f, true);
    private final WorkCounters counters = new WorkCounters();
    private final DrainController drain = new DrainController();
    private final AtomicBoolean dispatcherScheduled = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private static final Duration DEFAULT_CLOSE_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_TERMINAL_RECORDS = 4096;
    private record CompletionPublication(CompletableFuture<WorkRecord.Terminal> future, WorkRecord record) {}
    public WorldgenCoordinator(Executor executor, ResourceAdmission admission, StageExecutor stageExecutor, CommitCoordinator commits, int queueCapacity) {
        this.executor = Objects.requireNonNull(executor); this.completionExecutor = executor; this.admission = Objects.requireNonNull(admission); this.stageExecutor = Objects.requireNonNull(stageExecutor); this.commits = Objects.requireNonNull(commits); queue = new FairWorkQueue(queueCapacity);
    }

    /**
     * Binds validation/publication to an authoritative mailbox such as the
     * Minecraft server executor. Dispatch and backend work remain owned by
     * this coordinator; only the completion boundary moves. Callers should
     * bind this during server startup, before submitting live work.
     */
    public void bindCompletionExecutor(Executor completionExecutor) {
        this.completionExecutor = Objects.requireNonNull(completionExecutor, "completionExecutor");
    }
    public RequestSubscription submit(GenerationRequest request) {
        return submit(request, stageExecutor, commits);
    }

    /**
     * Submits work with handlers bound to the captured request. The runtime
     * owns one queue/admission boundary, while loader adapters can still bind
     * the exact backend inputs and authoritative chunk committer for each
     * target. A duplicate subscriber must use the same handlers as the first
     * subscriber; sharing only a WorkKey while changing its publication
     * target would be an unsafe coalescing bug.
     */
    public RequestSubscription submit(GenerationRequest request, StageExecutor requestStageExecutor,
                                      CommitCoordinator requestCommits) {
        Objects.requireNonNull(request); counters.requested();
        Objects.requireNonNull(requestStageExecutor, "requestStageExecutor");
        Objects.requireNonNull(requestCommits, "requestCommits");
        RequestSubscription subscription;
        CompletionPublication publication = null;
        boolean schedule = false;
        synchronized (lifecycleLock) {
            if (!drain.admits()) { counters.rejected(); throw new java.util.concurrent.RejectedExecutionException("Coordinator is draining"); }
            WorkRecord record = records.get(request.key());
            boolean terminalReuse = false;
            if (record == null) {
                record = terminalRecords.get(request.key());
                if (record != null) {
                    terminalReuse = true;
                } else {
                    record = new WorkRecord(request, requestStageExecutor, requestCommits);
                    WorkRecord previous = records.putIfAbsent(request.key(), record);
                    if (previous != null) record = previous;
                    else counters.uniqueWork();
                }
            }
            if (!record.request().compatibleWith(request)) {
                counters.rejected();
                throw new IllegalArgumentException("Conflicting request for shared work key: "
                        + record.request().incompatibilityReason(request));
            }
            if (!terminalReuse && !record.uses(requestStageExecutor, requestCommits)) {
                counters.rejected();
                throw new IllegalArgumentException("Conflicting execution handlers for shared work key");
            }
            subscription = new RequestSubscription(record);
            if (terminalReuse) {
                // The terminal record has no new coordinator-owned execution.
            } else {
                if (record.state() == WorkRecord.State.WAITING
                        && execution.putIfAbsent(request.key(), new CompletableFuture<>()) == null) {
                    if (!queue.offer(request)) {
                        counters.rejected();
                        record.finishForCoordinator(WorkRecord.State.REJECTED, "bounded queue full", null);
                        publication = completeExecution(record);
                    } else {
                        schedule = true;
                    }
                }
            }
        }
        publishCompletion(publication);
        if (schedule) scheduleDispatch();
        return subscription;
    }
    public WorkCounters.Snapshot counters() { return counters.snapshot(); }
    public int retainedRecords() { return records.size(); }

    /**
     * Returns one immutable view of queue, active-record and admission state.
     * The view is diagnostic only; callers must not use it as a reservation or
     * admission decision because work may change immediately after return.
     */
    public CoordinatorSnapshot snapshot() {
        synchronized (lifecycleLock) {
            return new CoordinatorSnapshot(
                    drain.state(),
                    queue.size(),
                    queue.capacity(),
                    records.size(),
                    terminalRecords.size(),
                    admission.usedBytes(),
                    admission.capacityBytes(),
                    counters.snapshot());
        }
    }

    /**
     * Invalidates work whose captured world/device identity predates the
     * supplied generation.  Queued records are retired immediately.  Work
     * already handed to a backend remains retained until its completion
     * callback, so its reservation and native result cannot be mistaken for
     * released state.
     */
    public int invalidateBefore(long worldEpoch, long deviceGeneration) {
        if (worldEpoch < 0 || deviceGeneration < 0) {
            throw new IllegalArgumentException("Negative invalidation generation");
        }
        int invalidated = 0;
        var publications = new ArrayList<CompletionPublication>();
        synchronized (lifecycleLock) {
            for (WorkRecord record : new ArrayList<>(records.values())) {
                var context = record.request().key().context();
                if (context.worldEpoch() >= worldEpoch
                        && context.deviceGeneration() >= deviceGeneration) continue;
                if (!record.invalidate("captured world/device generation is stale")) continue;
                invalidated++;
                if (record.outcome() != null) {
                    counters.stale();
                    publications.add(completeExecution(record));
                }
            }
            Iterator<Map.Entry<WorkKey, WorkRecord>> terminal = terminalRecords.entrySet().iterator();
            while (terminal.hasNext()) {
                var context = terminal.next().getKey().context();
                if (context.worldEpoch() < worldEpoch || context.deviceGeneration() < deviceGeneration) {
                    terminal.remove();
                }
            }
        }
        publications.forEach(WorldgenCoordinator::publishCompletion);
        return invalidated;
    }
    public CompletionStage<Void> drain(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Positive drain timeout required");
        long timeoutMillis = timeoutMillis(timeout, "Drain timeout");
        CompletableFuture<Void> future;
        var publications = new ArrayList<CompletionPublication>();
        synchronized (lifecycleLock) {
            if (drain.state() == DrainController.State.CLOSED) return CompletableFuture.failedFuture(new IllegalStateException("Coordinator is closed"));
            drain.beginDrain();
            // Queued work cannot be dispatched after admission closes.  Give
            // it an explicit terminal outcome before taking the wait set, so
            // drain() cannot return a future that waits forever on abandoned
            // queue entries.
            publications.addAll(cancelQueued());
            future = CompletableFuture.allOf(execution.values().toArray(CompletableFuture[]::new));
        }
        publications.forEach(WorldgenCoordinator::publishCompletion);
        return future.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
    }
    private void scheduleDispatch() {
        if (!dispatcherScheduled.compareAndSet(false, true)) return;
        try {
            executor.execute(this::dispatch);
        } catch (RuntimeException failure) {
            dispatcherScheduled.set(false);
            failQueued("executor rejected work");
            throw failure;
        }
    }
    private void dispatch() {
        try {
            GenerationRequest request;
            while ((request = queue.poll()) != null) {
                WorkRecord record = records.get(request.key());
                if (record != null) run(record, request);
            }
        } finally {
            dispatcherScheduled.set(false);
            if (drain.admits() && !queue.isEmpty()) scheduleDispatch();
        }
    }
    /**
     * Starts one backend operation and returns immediately.  Joining a backend
     * future on the dispatcher worker can deadlock a legal single-worker
     * composition when the backend schedules its completion on that same
     * executor.  The reservation therefore belongs to the completion callback
     * and remains held until validation/commit has finished.
     */
    private void run(WorkRecord record, GenerationRequest request) {
        if (record.state() != WorkRecord.State.WAITING) { publishCompletion(completeExecution(record)); return; }
        if (record.subscriberCount() == 0) {
            counters.cancelled();
            record.finishForCoordinator(WorkRecord.State.CANCELLED, "all subscribers cancelled before admission", null);
            publishCompletion(completeExecution(record));
            return;
        }
        var reservation = admission.tryReserve(request.resources());
        if (reservation.isEmpty()) {
            counters.rejected();
            record.finishForCoordinator(WorkRecord.State.REJECTED, "resource budget exhausted", null);
            publishCompletion(completeExecution(record));
            return;
        }
        ResourceReservation resources = reservation.orElseThrow();
        try {
            record.state(WorkRecord.State.ADMITTED);
            record.state(WorkRecord.State.EXECUTING);
            counters.backendAttempt();
            CompletionStage<BackendResult> backend = record.stageExecutor().execute(request, resources);
            if (backend == null) throw new IllegalStateException("Stage executor returned no completion");
            backend.whenComplete((result, failure) -> scheduleBackendCompletion(
                    record, request, resources, result, failure));
        } catch (Throwable failure) {
            resources.close();
            if (record.outcome() != null) {
                publishCompletion(completeExecution(record));
                return;
            }
            if (record.invalidated() && record.outcome() == null) {
                counters.stale();
                record.finishForCoordinator(WorkRecord.State.STALE,
                        "captured world/device generation is stale", null);
            } else {
                counters.failed();
                record.finishForCoordinator(WorkRecord.State.FAILED, detail(failure), null);
            }
            publishCompletion(completeExecution(record));
        }
    }

    /**
     * Serializes validation and publication onto the bound completion mailbox.
     * Backend futures commonly complete on a CPU pool or a native completion
     * thread; letting those threads mutate the authoritative target would
     * make the request boundary accidental.  Runnable::run remains a useful
     * injected reference executor for model tests.
     */
    private void scheduleBackendCompletion(WorkRecord record, GenerationRequest request,
                                            ResourceReservation resources,
                                            BackendResult result, Throwable failure) {
        Executor completion = completionExecutor;
        try {
            completion.execute(() -> finishBackend(record, request, resources, result, failure));
        } catch (Throwable dispatchFailure) {
            Throwable combined = failure == null ? dispatchFailure : combine(failure, dispatchFailure);
            // A rejected authoritative mailbox normally means that shutdown
            // has begun. Re-enter the coordinator worker with the failure so
            // resources are released and the record is terminal, but no
            // Minecraft mutation can occur after the authoritative executor
            // has rejected the task.
            try {
                executor.execute(() -> finishBackend(record, request, resources, result, combined));
            } catch (Throwable coordinatorFailure) {
                combined.addSuppressed(coordinatorFailure);
                finishBackend(record, request, resources, result, combined);
            }
        }
    }

    private static Throwable combine(Throwable original, Throwable schedulingFailure) {
        original.addSuppressed(schedulingFailure);
        return original;
    }

    private void finishBackend(WorkRecord record, GenerationRequest request, ResourceReservation resources,
                               BackendResult backend, Throwable backendFailure) {
        try {
            if (record.outcome() != null) return;
            if (record.invalidated()) {
                if (record.outcome() == null) {
                    counters.stale();
                    record.finishForCoordinator(WorkRecord.State.STALE,
                            "captured world/device generation is stale", backend);
                }
                return;
            }
            if (backendFailure != null) throw backendFailure;
            if (backend == null) throw new IllegalStateException("Stage executor completed with no backend result");
            record.state(WorkRecord.State.RESULT_READY);
            record.state(WorkRecord.State.VALIDATING);
            if (request.gpuRequired() && backend.route() != dev.worldgennext.engine.ExecutionRoute.GPU) {
                throw new IllegalArgumentException("GPU-required request received a non-GPU route");
            }
            boolean receiptGpu = backend.receipt().backend().toLowerCase(java.util.Locale.ROOT).startsWith("gpu");
            if (receiptGpu != (backend.route() == dev.worldgennext.engine.ExecutionRoute.GPU)) {
                throw new IllegalArgumentException("Execution receipt backend does not match route");
            }
            if (backend.route() == dev.worldgennext.engine.ExecutionRoute.GPU
                    && !backend.receipt().hasCompiledArtifactProvenance()) {
                throw new IllegalArgumentException("GPU execution receipt is missing compiled shader/SPIR-V provenance");
            }
            if (!backend.receipt().context().equals(request.key().context())) {
                throw new IllegalArgumentException("execution context identity mismatch");
            }
            if (backend.result().header().chunkX() != request.key().chunkX()
                    || backend.result().header().chunkZ() != request.key().chunkZ()) {
                throw new IllegalArgumentException("backend chunk coordinate mismatch");
            }
            if (backend.route() == dev.worldgennext.engine.ExecutionRoute.GPU) {
                counters.gpuSubmitted(backend.receipt().submitted());
                counters.gpuCompleted(backend.receipt().completed());
            } else if (backend.route() == dev.worldgennext.engine.ExecutionRoute.CPU_RECOVERY) {
                counters.recovery();
            } else if (backend.route() == dev.worldgennext.engine.ExecutionRoute.CPU_ORIGINAL) {
                counters.originalStage();
            } else {
                counters.cpuOwned();
            }
            counters.validated();
            record.state(WorkRecord.State.COMMITTING);
            CommitToken token = new CommitToken(request.ownershipToken(), backend.receipt().context(),
                    request.key().context().worldEpoch(), request.expectedRevision());
            CommitReceipt receipt;
            synchronized (lifecycleLock) {
                if (record.invalidated()) {
                    counters.stale();
                    record.finishForCoordinator(WorkRecord.State.STALE,
                            "captured world/device generation is stale before commit", backend);
                    return;
                }
                receipt = record.commits().publish(token, backend.result(), backend.receipt());
            }
            if (!receipt.committed()) {
                if (isStale(receipt.detail())) {
                    counters.stale();
                    record.finishForCoordinator(WorkRecord.State.STALE, receipt.detail(), backend);
                } else {
                    counters.failed();
                    record.finishForCoordinator(WorkRecord.State.FAILED, receipt.detail(), backend);
                }
            } else {
                counters.committed();
                record.finishForCoordinator(WorkRecord.State.COMMITTED, receipt.detail(), backend);
            }
        } catch (Throwable failure) {
            String failureDetail = detail(failure);
            if (record.invalidated() || isStale(failure)) {
                counters.stale();
                record.finishForCoordinator(WorkRecord.State.STALE, failureDetail, backend);
            } else {
                counters.failed();
                record.finishForCoordinator(WorkRecord.State.FAILED, failureDetail, backend);
            }
        } finally {
            resources.close();
            publishCompletion(completeExecution(record));
        }
    }

    private static String detail(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        return cause.getClass().getSimpleName() + ": " + (cause.getMessage() == null ? "no detail" : cause.getMessage());
    }

    private static boolean isStale(String detail) {
        if (detail == null) return false;
        String normalized = detail.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("stale") || normalized.contains("late")
                || normalized.contains("expired") || normalized.contains("revision changed")
                || normalized.contains("ownership changed") || normalized.contains("status changed")
                || normalized.contains("world epoch") || normalized.contains("device generation");
    }

    private static boolean isStale(Throwable failure) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (isStale(current.getMessage()) || isStale(current.toString())) return true;
        }
        return false;
    }
    private void failQueued(String detail) {
        GenerationRequest request;
        while ((request = queue.poll()) != null) {
            WorkRecord record = records.get(request.key());
            if (record != null) {
                counters.failed();
                record.finishForCoordinator(WorkRecord.State.FAILED, detail, null);
                publishCompletion(completeExecution(record));
            }
        }
    }
    private CompletionPublication completeExecution(WorkRecord record) {
        CompletableFuture<WorkRecord.Terminal> future;
        synchronized (lifecycleLock) {
            future = execution.remove(record.request().key());
            if (records.remove(record.request().key(), record) && record.outcome() != null
                    && drain.state() != DrainController.State.CLOSED) {
                terminalRecords.put(record.request().key(), record);
                while (terminalRecords.size() > MAX_TERMINAL_RECORDS) {
                    terminalRecords.remove(terminalRecords.entrySet().iterator().next().getKey());
                }
            }
        }
        return new CompletionPublication(future, record);
    }
    /** Publishes callbacks outside lifecycleLock, including synchronous paths. */
    private static void publishCompletion(CompletionPublication publication) {
        if (publication == null) return;
        WorkRecord record = publication.record();
        if (publication.future() != null) publication.future().complete(record.outcome());
        record.notifyTerminalSubscribers();
    }
    @Override public void close() { close(DEFAULT_CLOSE_TIMEOUT); }

    /**
     * Performs a bounded safe shutdown. Queued work is cancelled, while an
     * executing backend is allowed to finish and release its reservation. A
     * timeout leaves the coordinator in DRAINING rather than pretending that
     * native or host resources are no longer live.
     */
    public void close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Positive close timeout required");
        long timeoutMillis = timeoutMillis(timeout, "Close timeout");
        CompletableFuture<Void> outstanding;
        var publications = new ArrayList<CompletionPublication>();
        synchronized (lifecycleLock) {
            if (drain.state() == DrainController.State.CLOSED) return;
            drain.beginDrain();
            publications.addAll(cancelQueued());
            outstanding = CompletableFuture.allOf(execution.values().toArray(CompletableFuture[]::new));
        }
        publications.forEach(WorldgenCoordinator::publishCompletion);
        try {
            outstanding.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Coordinator close interrupted; work remains draining", interrupted);
        } catch (TimeoutException timeoutFailure) {
            throw new IllegalStateException("Coordinator close timed out; work remains draining", timeoutFailure);
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IllegalStateException("Coordinator close observed a terminal failure", failure.getCause());
        }
        synchronized (lifecycleLock) {
            if (!drain.close()) return;
            terminalRecords.clear();
            admission.close();
        }
        if (executor instanceof ExecutorService service) service.shutdown();
    }

    /** Number of active terminal outcomes retained for same-generation deduplication. */
    public int retainedTerminalRecords() {
        synchronized (lifecycleLock) {
            return terminalRecords.size();
        }
    }

    private List<CompletionPublication> cancelQueued() {
        var publications = new ArrayList<CompletionPublication>();
        GenerationRequest request;
        while ((request = queue.poll()) != null) {
            WorkRecord record = records.get(request.key());
            if (record != null && record.outcome() == null) {
                counters.cancelled();
                record.finishForCoordinator(WorkRecord.State.CANCELLED, "coordinator closed before dispatch", null);
                publications.add(completeExecution(record));
            }
        }
        return publications;
    }

    private static long timeoutMillis(Duration timeout, String label) {
        try {
            // Duration#toMillis truncates. Preserve a positive deadline for
            // sub-millisecond callers instead of turning it into an immediate
            // timeout, while rejecting an unrepresentable duration before the
            // coordinator changes lifecycle state.
            return Math.max(1L, timeout.toMillis());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(label + " is too large", overflow);
        }
    }
}
