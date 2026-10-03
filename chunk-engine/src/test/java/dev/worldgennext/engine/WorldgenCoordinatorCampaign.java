// SPDX-License-Identifier: MIT
package dev.worldgennext.engine;

import dev.worldgennext.engine.worldgen.*;
import dev.worldgennext.material.chunk.*;
import dev.worldgennext.semantic.execution.ExecutionReceipt;
import dev.worldgennext.semantic.identity.ContextIdentity;
import dev.worldgennext.semantic.program.*;
import dev.worldgennext.semantic.snapshot.*;
import java.nio.file.*;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * Opt-in, driver-free coordinator MODEL campaign; deliberately no JUnit entrypoint.
 * Compile only this source with Java 21 -implicit:none -sourcepath "" into a private
 * directory against existing semantic/material/spatial/engine classes. Run with
 * -Xmx512m. Arguments: requests-per-seed (default 100000), optional create-new JSON.
 *
 * Each seed starts with 4200 randomized signed-coordinate valid publications to
 * challenge the actual 4096-record LRU bound (small runs use 1/5 of their quota).
 * The remaining requests run randomized bounded minibatches of 1..8 unique work
 * items, 1..3 initial subscribers and one counted terminal observer per item.
 * Coordinators close after at most 128 distinct coordinates; fixtures are reused
 * across these lifetimes. Endpoint is always NOISE, extent is -64..-48 BLOCK.
 * Resource budget fits two 65536-byte five-class reservations, queue fits four.
 * This tests accounting, not a measured host-memory estimate or native allocation.
 * All executing-subscriber cancellations still allow publication under the current
 * API; world/device invalidation must prevent it. No unsupported cancellation
 * semantics are invented. Equal priority makes dispatch FIFO despite queue ageing.
 * Independent predictions are made before dispatch and checked against every
 * subscription, shared terminal object, resource lifetime and counter delta.
 * Reflection reads record subscription counts solely for diagnostic leak checks.
 */
public final class WorldgenCoordinatorCampaign {
    private static final long[] SEEDS = {0L, 1L, -1L, 20260930L, 0x5EEDC0DEL};
    private static final long BYTES = 65536, BUDGET = 2 * BYTES;
    private static final ResourceEstimate FIT = new ResourceEstimate(4096, 8192, 24576, 8192, 20480);
    private static final int QUEUE = 4, RETENTION = 4096;
    private static final ContextIdentity NORMAL = context(1, 1);
    private static final ContextIdentity OLD_WORLD = context(0, 1);
    private static final ContextIdentity OLD_DEVICE = context(1, 0);
    private enum Fault { VALID, CANCEL_ONE_PRE, CANCEL_ALL_PRE, CANCEL_ONE_POST, CANCEL_ALL_POST,
        THROW_SYNC, THROW_ASYNC, OVERSIZE, OLD_WORLD, OLD_DEVICE, WRONG_CONTEXT,
        WRONG_PROGRAM, WRONG_COORDINATES, DUPLICATE_COMPLETION }
    private static final class Manual implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        int high;
        public void execute(Runnable task) {
            check(tasks.size() < 32, "manual mailbox overflow");
            tasks.add(task); high = Math.max(high, tasks.size());
        }
        void step() { check(!tasks.isEmpty(), "missing manual task"); tasks.remove().run(); }
        void flush() { int limit = 64; while (!tasks.isEmpty()) { check(--limit > 0, "mailbox did not drain"); step(); } }
    }
    private static final class Work {
        final GenerationRequest request;
        final Fault fault;
        final ChunkNoiseResult result;
        final List<RequestSubscription> subscriptions = new ArrayList<>();
        final Set<RequestSubscription> cancelled = Collections.newSetFromMap(new IdentityHashMap<>());
        final CompletableFuture<BackendResult> future = new CompletableFuture<>();
        ResourceReservation reservation;
        WorkRecord.State expected;
        int attempts, publications;
        boolean queued, stale;
        Work(GenerationRequest request, Fault fault, ChunkNoiseResult result) {
            this.request = request; this.fault = fault; this.result = result;
        }
    }
    private static final class Metrics {
        final long seed;
        long requested, unique, attempts, commits, cancelledSubscribers, terminals, duplicateRejected;
        long rejected, cancelled, failed, stale, validated, lateReuse, delayed, batches, closed;
        int queueHigh, recordsHigh, terminalHigh, subscriptionsHigh, reservationsHigh, mailboxHigh;
        long bytesHigh;
        final Map<String, Long> scenarios = new TreeMap<>();
        Metrics(long seed) { this.seed = seed; }
        void event(String key) { scenarios.merge(key, 1L, Long::sum); }
        String json() {
            return "{\"seed\":" + seed + ",\"subscriberRequests\":" + requested
                + ",\"uniqueWork\":" + unique + ",\"backendAttempts\":" + attempts
                + ",\"commits\":" + commits + ",\"sharedTerminals\":" + terminals
                + ",\"cancelledSubscribers\":" + cancelledSubscribers + ",\"terminalReuseRequests\":" + lateReuse
                + ",\"duplicateAttemptsRejected\":" + duplicateRejected + ",\"delayedBackends\":" + delayed
                + ",\"rejectedWork\":" + rejected + ",\"cancelledWork\":" + cancelled
                + ",\"failedWork\":" + failed + ",\"staleWork\":" + stale
                + ",\"validatedWork\":" + validated + ",\"minibatches\":" + batches
                + ",\"closedCoordinators\":" + closed + ",\"highwater\":{\"queue\":" + queueHigh
                + ",\"activeRecords\":" + recordsHigh + ",\"terminalRetention\":" + terminalHigh
                + ",\"attachedSubscriptions\":" + subscriptionsHigh + ",\"reservations\":" + reservationsHigh
                + ",\"admissionBytes\":" + bytesHigh + ",\"mailboxTasks\":" + mailboxHigh
                + "},\"scenarios\":" + mapJson(scenarios)
                + ",\"final\":{\"queues\":0,\"records\":0,\"subscriptions\":0,\"reservations\":0,\"admissionBytes\":0,\"retainedTerminals\":0}}";
        }
    }
    private static final class Harness {
        final Metrics m;
        final Manual manual = new Manual();
        final ResourceAdmission admission = new ResourceAdmission(BUDGET);
        final Map<WorkKey, Work> work = new HashMap<>();
        final CommitCoordinator commits;
        final WorldgenCoordinator coordinator;
        Harness(Metrics m) {
            this.m = m;
            commits = new CommitCoordinator((token, result, receipt) -> {
                Work w = work.values().stream().filter(candidate -> candidate.request.ownershipToken().equals(token.ownershipToken())).findFirst().orElseThrow();
                check(w.expected == WorkRecord.State.COMMITTED && !w.stale, "unexpected/late publication");
                check(++w.publications == 1 && token.consumed(), "duplicate publication per token");
                check(result == w.result && receipt.context().equals(w.request.key().context()), "wrong publication target/context");
                // Consumed-token retry goes through the real CommitCoordinator; it must never recurse into mutation.
                check(!commitsPublishAgain(token, result, receipt), "consumed token accepted twice");
                m.duplicateRejected++;
                return new CommitReceipt(true, "MODEL publication", token.expectedRevision(), receipt.committed(1));
            });
            coordinator = new WorldgenCoordinator(manual, admission, (request, reservation) -> {
                Work w = work.get(request.key());
                check(w != null && ++w.attempts == 1, "unexpected/duplicate backend attempt");
                w.reservation = reservation;
                check(reservation.totalBytes() == BYTES && !reservation.isClosed(), "reservation geometry/lifetime");
                observe();
                if (w.fault == Fault.THROW_SYNC) throw new IllegalStateException("MODEL injected synchronous backend exception");
                m.delayed++;
                return w.future;
            }, commits, QUEUE);
        }
        boolean commitsPublishAgain(CommitToken token, ChunkNoiseResult result, ExecutionReceipt receipt) {
            return commits.publish(token, result, receipt).committed();
        }
        RequestSubscription submit(Work w) {
            m.requested++;
            RequestSubscription s = coordinator.submit(w.request);
            w.subscriptions.add(s); observe(); return s;
        }
        void cancel(Work w, boolean all) {
            int count = all ? w.subscriptions.size() : Math.max(0, w.subscriptions.size() - 1);
            if (count > 0) m.event("EFFECTIVE_" + w.fault);
            for (int i = 0; i < count; i++) {
                RequestSubscription s = w.subscriptions.get(i);
                check(s.cancel() && !s.cancel(), "subscription cancellation must detach exactly once");
                w.cancelled.add(s); m.cancelledSubscribers++;
            }
            observe();
        }
        void observe() {
            var s = coordinator.snapshot();
            check(s.queueDepth() <= QUEUE && s.reservedBytes() <= BUDGET && s.retainedTerminalRecords() <= RETENTION, "bounded state exceeded");
            m.queueHigh = Math.max(m.queueHigh, s.queueDepth());
            m.recordsHigh = Math.max(m.recordsHigh, s.activeRecords());
            m.terminalHigh = Math.max(m.terminalHigh, s.retainedTerminalRecords());
            m.bytesHigh = Math.max(m.bytesHigh, s.reservedBytes());
            m.reservationsHigh = Math.max(m.reservationsHigh, (int)(s.reservedBytes() / BYTES));
            m.mailboxHigh = Math.max(m.mailboxHigh, manual.high);
            m.subscriptionsHigh = Math.max(m.subscriptionsHigh, attached(coordinator));
        }
        void verify(Work w, boolean observer) {
            WorkRecord.Terminal shared = null;
            if (observer) {
                int attempts = w.attempts, publications = w.publications;
                RequestSubscription s = submit(w); m.lateReuse++;
                check(w.attempts == attempts && w.publications == publications, "terminal reuse restarted work");
                shared = done(s);
            }
            for (RequestSubscription s : w.subscriptions) {
                WorkRecord.Terminal t = done(s);
                if (w.cancelled.contains(s)) check(t.state() == WorkRecord.State.CANCELLED, "cancelled subscriber changed outcome");
                else {
                    check(t.state() == w.expected, "model terminal mismatch " + w.fault + ": " + t);
                    if (shared == null) shared = t;
                    check(t == shared, "subscribers did not receive one identical shared terminal");
                    check(!s.cancel(), "terminal subscriber detached late");
                }
            }
            check(shared != null, "unobserved shared terminal");
            check(w.publications == (w.expected == WorkRecord.State.COMMITTED ? 1 : 0), "publication count differs from model");
            if (w.reservation != null) check(w.reservation.isClosed(), "reservation leaked");
            m.unique++; m.terminals++; m.attempts += w.attempts; m.commits += w.publications;
            if (w.expected == WorkRecord.State.REJECTED) {
                m.event(!w.queued ? "QUEUE_FULL_REJECTION" : w.fault == Fault.OVERSIZE ? "OVERSIZE_REJECTION" : "BUDGET_EXHAUSTION_REJECTION");
            }
            if (w.expected == WorkRecord.State.STALE) m.event("EFFECTIVE_" + w.fault + (w.attempts == 0 ? "_QUEUED" : "_EXECUTING"));
            if (w.expected == WorkRecord.State.FAILED) m.event("EFFECTIVE_" + w.fault);
            if (w.fault == Fault.DUPLICATE_COMPLETION && w.attempts > 0) m.event("EFFECTIVE_DUPLICATE_COMPLETION");
            switch (w.expected) {
                case REJECTED -> m.rejected++;
                case CANCELLED -> m.cancelled++;
                case FAILED -> m.failed++;
                case STALE -> m.stale++;
                default -> { }
            }
            if (w.attempts > 0 && !w.stale && w.fault != Fault.THROW_SYNC && w.fault != Fault.THROW_ASYNC
                    && w.fault != Fault.WRONG_CONTEXT && w.fault != Fault.WRONG_COORDINATES) m.validated++;
        }
        void close() {
            check(manual.tasks.isEmpty() && admission.usedBytes() == 0 && coordinator.retainedRecords() == 0, "active state before close");
            observe(); check(attached(coordinator) == 0, "subscriptions retained after terminal completion");
            coordinator.close(Duration.ofSeconds(1)); m.closed++;
            var s = coordinator.snapshot();
            check(s.queueDepth() == 0 && s.activeRecords() == 0 && s.retainedTerminalRecords() == 0
                && s.reservedBytes() == 0 && attached(coordinator) == 0, "final coordinator leak");
            check(admission.tryReserve(FIT).isEmpty(), "closed admission accepted a reservation");
        }
    }
    public static void main(String[] args) throws Exception {
        int count = args.length == 0 ? 100000 : Integer.parseInt(args[0]);
        check(count >= 500, "at least 500 requests per seed required");
        String inputFingerprint = inputFingerprint();
        long started = System.nanoTime();
        List<String> reports = new ArrayList<>();
        for (long seed : SEEDS) {
            Metrics m = run(seed, count); reports.add(m.json());
            System.out.println("MODEL_SEED_COMPLETE " + m.json());
        }
        check(inputFingerprint.equals(inputFingerprint()), "compiled campaign inputs changed during execution");
        String json = "{\"verdict\":\"MODEL_CAMPAIGN_PASS\",\"nativeExecution\":false,\"MinecraftParity\":false,\"releaseQualification\":false,"
            + "\"javaVersion\":\"" + System.getProperty("java.version") + "\",\"maxHeapBytes\":" + Runtime.getRuntime().maxMemory()
            + ",\"compiledInputsSha256\":\"" + inputFingerprint + "\","
            + "\"requestsPerSeed\":" + count + ",\"totalSubscriberRequests\":" + (5L * count)
            + ",\"queueCapacity\":4,\"resourceBudgetBytes\":" + BUDGET
            + ",\"reservationBytes\":" + BYTES + ",\"reusableTerminalRetentionLimit\":4096,"
            + "\"retentionPolicy\":\"LRU per coordinator; invalidation retires old generations; close clears all\","
            + "\"fixturePolicy\":\"immutable full 4096-state mixed air/stone/water section, two heightmaps, fluid marks and metadata; signed coordinates; cached across bounded lifetimes\","
            + "\"elapsedMillis\":" + ((System.nanoTime() - started) / 1000000)
            + ",\"seeds\":[" + String.join(",", reports) + "]}";
        if (args.length > 1) Files.writeString(Path.of(args[1]), json + System.lineSeparator(), StandardOpenOption.CREATE_NEW);
        System.out.println(json);
    }
    private static Metrics run(long seed, int count) {
        Metrics m = new Metrics(seed);
        Random random = new Random(seed);
        // Independent valid-work LRU probe, within (not added to) the exact request quota.
        Harness probe = new Harness(m);
        int probeCount = Math.min(4200, count / 5);
        for (int i = 0; i < probeCount; i++) {
            int x = (random.nextBoolean() ? -1 : 1) * (i + 129);
            int z = random.nextInt(8193) - 4096;
            Work w = make(x, z, Fault.VALID, fixture(NORMAL, x, z), random);
            w.expected = WorkRecord.State.COMMITTED; probe.work.put(w.request.key(), w);
            probe.submit(w); probe.manual.step();
            check(!w.future.isDone() && probe.admission.usedBytes() == BYTES, "delayed backend did not hold reservation");
            w.future.complete(backend(w)); probe.manual.flush();
            probe.verify(w, false); probe.work.clear(); m.event("RETENTION_VALID");
            check(probe.coordinator.retainedTerminalRecords() == Math.min(i + 1, RETENTION), "LRU retention size mismatch");
            check(probe.coordinator.counters().uniqueWork() == i + 1, "retention probe unique counter");
        }
        check(count < 21000 || m.terminalHigh == RETENTION, "full run did not challenge LRU bound");
        checkCounters(probe, 0, 0, 0, 0, 0, 0, 0); probe.close();
        Map<String, ChunkNoiseResult> fixtures = new HashMap<>();
        while (m.requested < count) {
            Harness h = new Harness(m);
            long r0 = m.requested, u0 = m.unique, a0 = m.attempts, c0 = m.commits;
            long reject0 = m.rejected, cancel0 = m.cancelled, fail0 = m.failed, stale0 = m.stale, valid0 = m.validated;
            int coordinate = 0;
            while (coordinate < 128 && m.requested < count) {
                m.batches++;
                int n = Math.min(1 + random.nextInt(8), 128 - coordinate);
                List<Work> batch = new ArrayList<>();
                for (int i = 0; i < n && m.requested < count; i++) {
                    long remaining = count - m.requested;
                    Fault f = remaining == 1 ? Fault.VALID : Fault.values()[random.nextInt(Fault.values().length)];
                    int slot = coordinate++, x = slot % 2 == 0 ? -slot - 1 : slot + 1, z = (slot % 7 - 3) * 17;
                    ContextIdentity context = faultContext(f);
                    String key = context.toString() + ":" + x + ":" + z;
                    Work w = make(x, z, f, fixtures.computeIfAbsent(key, ignored -> fixture(context, x, z)), random);
                    w.queued = batch.size() < QUEUE;
                    w.expected = w.queued ? null : WorkRecord.State.REJECTED;
                    h.work.put(w.request.key(), w); batch.add(w); m.event(f.name());
                    boolean wantsObserver = remaining > 1 && f != Fault.OLD_WORLD && f != Fault.OLD_DEVICE;
                    int desired = 1 + random.nextInt(3);
                    if (f == Fault.CANCEL_ONE_PRE || f == Fault.CANCEL_ONE_POST) desired = Math.max(2, desired);
                    int subscribers = (int)Math.min(remaining - (wantsObserver ? 1 : 0), desired);
                    for (int j = 0; j < subscribers; j++) h.submit(w);
                    if (w.queued && f == Fault.CANCEL_ONE_PRE) h.cancel(w, false);
                    if (w.queued && f == Fault.CANCEL_ALL_PRE) h.cancel(w, true);
                    // Each normal group budgets a counted late observer. The final singleton does not need one.
                    if (wantsObserver) { m.requested++; w.subscriptions.add(null); }
                }
                boolean earlyInvalidation = random.nextBoolean();
                for (Work w : batch) if (w.queued && (w.fault == Fault.OLD_WORLD || w.fault == Fault.OLD_DEVICE)) w.stale = true;
                if (earlyInvalidation) { h.coordinator.invalidateBefore(1, 1); m.event("INVALIDATE_PRE_DISPATCH"); }
                long reserved = 0;
                for (Work w : batch) {
                    if (!w.queued) continue;
                    if (w.stale && earlyInvalidation) w.expected = WorkRecord.State.STALE;
                    else if (w.fault == Fault.CANCEL_ALL_PRE) w.expected = WorkRecord.State.CANCELLED;
                    else if (w.fault == Fault.OVERSIZE || reserved + BYTES > BUDGET) { w.expected = WorkRecord.State.REJECTED; w.stale = false; }
                    else {
                        w.expected = w.stale ? WorkRecord.State.STALE : switch (w.fault) {
                            case THROW_SYNC, THROW_ASYNC, WRONG_CONTEXT, WRONG_PROGRAM, WRONG_COORDINATES -> WorkRecord.State.FAILED;
                            default -> WorkRecord.State.COMMITTED;
                        };
                        if (w.fault != Fault.THROW_SYNC) reserved += BYTES;
                    }
                }
                // Remove quota placeholders before touching actual subscribers.
                Set<Work> observers = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Work w : batch) if (w.subscriptions.remove(null)) { observers.add(w); m.requested--; }
                h.manual.flush(); h.observe();
                for (Work w : batch) if (w.attempts > 0 && w.fault != Fault.THROW_SYNC) {
                    check(!w.future.isDone() && !w.reservation.isClosed(), "backend completed before manual release");
                    if (w.fault == Fault.CANCEL_ONE_POST) h.cancel(w, false);
                    if (w.fault == Fault.CANCEL_ALL_POST) h.cancel(w, true);
                }
                if (!earlyInvalidation) { h.coordinator.invalidateBefore(1, 1); m.event("INVALIDATE_POST_DISPATCH"); }
                Collections.shuffle(batch, random);
                for (Work w : batch) if (w.attempts > 0 && w.fault != Fault.THROW_SYNC) {
                    int publications = w.publications;
                    if (w.fault == Fault.THROW_ASYNC) w.future.completeExceptionally(new IllegalStateException("MODEL injected async backend exception"));
                    else check(w.future.complete(backend(w)), "first completion lost");
                    check(w.publications == publications && !w.reservation.isClosed(), "publication bypassed manual mailbox");
                    if (w.fault == Fault.DUPLICATE_COMPLETION) {
                        check(!w.future.complete(backend(w)) && !w.future.completeExceptionally(new IllegalStateException("MODEL late completion")), "duplicate future completion accepted");
                        m.duplicateRejected += 2;
                    }
                    h.observe(); if (random.nextBoolean()) h.manual.flush();
                }
                h.manual.flush();
                for (Work w : batch) h.verify(w, observers.contains(w));
                h.work.clear(); h.observe();
                check(h.admission.usedBytes() == 0 && h.coordinator.retainedRecords() == 0 && attached(h.coordinator) == 0, "minibatch leaked active state");
            }
            checkCounters(h, r0, u0, a0, c0, reject0, cancel0, fail0, stale0, valid0);
            h.close();
        }
        check(m.requested == count && m.terminals == m.unique && m.commits <= m.attempts && m.attempts <= m.unique, "campaign count invariant");
        if (count >= 100000) for (String event : List.of("QUEUE_FULL_REJECTION", "OVERSIZE_REJECTION", "BUDGET_EXHAUSTION_REJECTION",
                "EFFECTIVE_CANCEL_ONE_PRE", "EFFECTIVE_CANCEL_ALL_PRE", "EFFECTIVE_CANCEL_ONE_POST", "EFFECTIVE_CANCEL_ALL_POST",
                "EFFECTIVE_THROW_SYNC", "EFFECTIVE_THROW_ASYNC", "EFFECTIVE_WRONG_CONTEXT", "EFFECTIVE_WRONG_PROGRAM",
                "EFFECTIVE_WRONG_COORDINATES", "EFFECTIVE_DUPLICATE_COMPLETION", "EFFECTIVE_OLD_WORLD_QUEUED",
                "EFFECTIVE_OLD_WORLD_EXECUTING", "EFFECTIVE_OLD_DEVICE_QUEUED", "EFFECTIVE_OLD_DEVICE_EXECUTING")) {
            check(m.scenarios.getOrDefault(event, 0L) >= 100, "insufficient effective fault rate: " + event);
        }
        for (Fault f : Fault.values()) check(m.scenarios.getOrDefault(f.name(), 0L) > 0, "missing scenario " + f);
        check(m.rejected > 0 && m.cancelled > 0 && m.stale > 0 && m.failed > 0 && m.cancelledSubscribers > 0, "missing effective fault transitions");
        return m;
    }
    private static void checkCounters(Harness h, long... base) {
        long r = base[0], u = base[1], a = base[2], c = base[3], re = base[4], ca = base[5], fa = base[6];
        long st = base.length > 7 ? base[7] : 0, va = base.length > 8 ? base[8] : 0;
        var s = h.coordinator.counters(); Metrics m = h.m;
        check(s.requested() == m.requested-r && s.uniqueWork() == m.unique-u && s.backendAttempts() == m.attempts-a
            && s.committed() == m.commits-c && s.rejected() == m.rejected-re && s.cancelled() == m.cancelled-ca
            && s.failed() == m.failed-fa && s.stale() == m.stale-st && s.validated() == m.validated-va, "counter/model divergence " + s);
        check(s.gpuSubmitted() == 0 && s.gpuCompleted() == 0 && s.originalStages() == 0 && s.recovery() == 0
            && s.cpuOwned() == s.validated() && s.comparedFields() == 0 && s.fullCompleted() == 0
            && s.saveBarrierCompleted() == 0 && s.reopenedVerified() == 0, "model scope counter leakage");
    }
    private static Work make(int x, int z, Fault f, ChunkNoiseResult result, Random random) {
        var key = new WorkKey(faultContext(f), x, z, GenerationStage.NOISE, "NOISE", -64, 16, EvaluationDomain.BLOCK);
        return new Work(new GenerationRequest(key, "MODEL:" + x + ":" + z, 0,
            f == Fault.OVERSIZE ? ResourceEstimate.uniform(BUDGET + 1) : FIT, false, random.nextInt(32)), f, result);
    }
    private static ContextIdentity faultContext(Fault f) { return f == Fault.OLD_WORLD ? OLD_WORLD : f == Fault.OLD_DEVICE ? OLD_DEVICE : NORMAL; }
    private static ContextIdentity context(long epoch, long device) {
        return ContextIdentity.of(WorldgenSnapshot.builder(20260930, "minecraft:overworld").build(),
            WorldgenProgram.builder().build(), NumericProfile.JAVA_REFERENCE, "abi-v2", "MODEL-campaign-v1", epoch, device);
    }
    private static BackendResult backend(Work w) {
        ContextIdentity c = w.fault == Fault.WRONG_CONTEXT ? OLD_WORLD : w.request.key().context();
        var receipt = ExecutionReceipt.issued("MODEL:" + w.request.ownershipToken(), "cpu-model", c,
            NumericProfile.JAVA_REFERENCE, w.fault == Fault.WRONG_PROGRAM ? "wrong-program" : c.programHash(),
            c.abiVersion(), c.deviceGeneration(), 1).completed(1).validated(1);
        ChunkNoiseResult result = w.fault == Fault.WRONG_COORDINATES
            ? fixture(c, w.request.key().chunkX() + 1, w.request.key().chunkZ()) : w.result;
        return new BackendResult(ExecutionRoute.CPU_PLANNED, receipt, result);
    }
    private static ChunkNoiseResult fixture(ContextIdentity c, int x, int z) {
        String[] states = new String[4096]; boolean[] fluids = new boolean[4096];
        int[] surface = new int[256], floor = new int[256];
        for (int y = 0; y < 16; y++) for (int column = 0; column < 256; column++) {
            int index = y * 256 + column;
            states[index] = y < 7 ? "minecraft:stone" : y < 10 ? "minecraft:water" : "minecraft:air";
            fluids[index] = y >= 7 && y < 10;
        }
        Arrays.fill(surface, -54); Arrays.fill(floor, -57);
        return ChunkNoiseResult.ofDense(new ChunkResultHeader(x, z, -64, 16, c.worldKey(), c.registryHash(), c.abiVersion()),
            BlockStateTable.fromRegistry(RegistrySnapshot.minimal()), states,
            new HeightmapPayload(Map.of("WORLD_SURFACE_WG", surface, "OCEAN_FLOOR_WG", floor)), fluids,
            new ChunkMetadataPayload(Map.of("fixture", "MODEL-only", "coordinates", x + "," + z)));
    }
    private static WorkRecord.Terminal done(RequestSubscription s) {
        var future = s.completion().toCompletableFuture(); check(future.isDone(), "subscriber stranded"); return future.join();
    }
    @SuppressWarnings("unchecked")
    private static int attached(WorldgenCoordinator c) {
        try {
            int total = 0;
            for (String name : List.of("records", "terminalRecords")) {
                var field = WorldgenCoordinator.class.getDeclaredField(name); field.setAccessible(true);
                for (WorkRecord w : ((Map<WorkKey, WorkRecord>)field.get(c)).values()) total += w.subscriberCount();
            }
            return total;
        } catch (ReflectiveOperationException failure) { throw new AssertionError("diagnostic API layout changed", failure); }
    }
    private static String mapJson(Map<String, Long> values) {
        List<String> entries = new ArrayList<>(); values.forEach((key, value) -> entries.add("\"" + key + "\":" + value));
        return "{" + String.join(",", entries) + "}";
    }
    private static String inputFingerprint() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            Path root = Path.of(entry);
            check(Files.isDirectory(root), "campaign classpath must contain only compiled-class directories");
            try (var paths = Files.walk(root)) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                    digest.update(root.resolve(root.relativize(path)).toString().replace('\\', '/').getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    digest.update(Files.readAllBytes(path));
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
