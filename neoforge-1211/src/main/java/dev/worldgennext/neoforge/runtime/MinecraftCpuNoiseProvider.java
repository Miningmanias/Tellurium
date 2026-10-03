// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import dev.worldgennext.engine.ExecutionRoute;
import dev.worldgennext.engine.worldgen.BackendResult;
import dev.worldgennext.engine.worldgen.CommitCoordinator;
import dev.worldgennext.material.chunk.ChunkNoiseResult;
import dev.worldgennext.material.chunk.ChunkResultCodec;
import dev.worldgennext.neoforge.WorldgenNextMod;
import dev.worldgennext.semantic.execution.ExecutionReceipt;
import dev.worldgennext.semantic.identity.ContextIdentity;
import dev.worldgennext.semantic.program.NumericProfile;
import dev.worldgennext.semantic.program.WorldgenProgram;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;

import java.util.Objects;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;

/**
 * Qualified CPU composition for the version-pinned NOISE boundary.
 *
 * <p>This class is deliberately not registered by the mod constructor. A
 * caller must first obtain qualified evidence and pass its exact context
 * keys to this provider, then register it through the evidence-bearing
 * {@code WorldgenNextMod} method. Compute is scheduled on the supplied
 * executor; the Minecraft holder executor is used only for the initial
 * immutable capture and for the normal status future that follows.</p>
 *
 * <p>The continuation preserves the small version-pinned retrogen tail after
 * {@code fillFromNoise}; returning the same chunk still lets Minecraft's
 * existing {@code ChunkStep}/{@code ChunkGenerationTask} chain perform the
 * status transition and schedule SURFACE and later stages.</p>
 */
public final class MinecraftCpuNoiseProvider implements WorldgenNextMod.QualifiedNoiseProvider, AutoCloseable {
    public static final String RESULT_ABI = "chunk-result-v4";
    public static final String COMPILER_VERSION = "worldgennext-cpu-live-v0.2";

    private final RuntimeComposition runtime;
    private final MinecraftCpuCandidate candidate;
    private final Set<String> qualifiedContextKeys;
    private final Executor computeExecutor;
    private final boolean isolatedEndpoint;
    private final AtomicLong executionIds = new AtomicLong();
    private final AtomicLong requestsStarted = new AtomicLong();
    private final ProviderActivity activity = new ProviderActivity(() -> { });

    public MinecraftCpuNoiseProvider(RuntimeComposition runtime, String qualifiedContextKey,
                                     Executor computeExecutor) {
        this(runtime, new MinecraftCpuCandidate(), Set.of(requireText(qualifiedContextKey, "qualifiedContextKey")), computeExecutor,
                false);
    }

    /** Creates a provider admitted for a finite set of exact captured contexts. */
    public MinecraftCpuNoiseProvider(RuntimeComposition runtime, Set<String> qualifiedContextKeys,
                                     Executor computeExecutor) {
        this(runtime, new MinecraftCpuCandidate(), normalizeContextKeys(qualifiedContextKeys), computeExecutor,
                false);
    }

    /**
     * Creates a CPU provider for the opt-in isolated FULL/SAVED verifier.
     * This provider is never installed through the qualification registry and
     * accepts the captured context identity produced by each real server
     * request.  The caller still supplies a running runtime with the normal
     * lifecycle and commit checks enabled.
     */
    public static MinecraftCpuNoiseProvider forIsolatedEndpoint(RuntimeComposition runtime,
                                                                 Executor computeExecutor) {
        return new MinecraftCpuNoiseProvider(runtime, new MinecraftCpuCandidate(), null,
                computeExecutor, true);
    }

    @Override
    public String route() { return "CPU_OWNED"; }

    @Override
    public String resultAbi() { return RESULT_ABI; }

    @Override
    public String compilerVersion() { return COMPILER_VERSION; }

    @Override
    public Set<String> qualifiedContextKeys() { return qualifiedContextKeys; }

    /** Number of real Minecraft NOISE tasks admitted to this provider. */
    public long requestsStarted() { return requestsStarted.get(); }

    private MinecraftCpuNoiseProvider(RuntimeComposition runtime, MinecraftCpuCandidate candidate,
                                      Set<String> qualifiedContextKeys, Executor computeExecutor,
                                      boolean isolatedEndpoint) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.candidate = Objects.requireNonNull(candidate, "candidate");
        this.qualifiedContextKeys = isolatedEndpoint ? Set.of() : normalizeContextKeys(qualifiedContextKeys);
        this.computeExecutor = Objects.requireNonNull(computeExecutor, "computeExecutor");
        this.isolatedEndpoint = isolatedEndpoint;
    }

    @Override
    public CompletionStage<ChunkAccess> generate(WorldGenContext context, ChunkStep step,
                                                 StaticCache2D<GenerationChunkHolder> cache,
                                                 ChunkAccess chunk) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(chunk, "chunk");
        if (step.targetStatus() != ChunkStatus.NOISE) {
            throw new IllegalArgumentException("CPU provider requires the NOISE step; got "
                    + step.targetStatus().getName());
        }
        ServerLevel level = Objects.requireNonNull(context.level(), "context.level");
        beginRequest();
        AtomicBoolean ended = new AtomicBoolean();
        Runnable releaseRequest = () -> {
            if (ended.compareAndSet(false, true)) endRequest();
        };
        try {

        // This call is the only candidate-side capture before compute. It
        // serializes the already-owned BIOMES target and does not schedule a
        // second holder request.
        MinecraftCpuCandidate.Inputs inputs = candidate.capture(level, cache, step, chunk);
        WorldgenProgram program = WorldgenProgram.builder().roots(inputs.snapshot().router().roots()).build();
        ContextIdentity identity = ContextIdentity.of(inputs.snapshot(), program,
                NumericProfile.JAVA_REFERENCE, RESULT_ABI, COMPILER_VERSION,
                runtime.reloadCoordinator().worldEpoch(), runtime.reloadCoordinator().deviceGeneration());
        final String bridgeContext = identity.worldKey();
        if (!isolatedEndpoint && !qualifiedContextKeys.contains(identity.worldKey())) {
            throw new IllegalStateException("captured context is not in the independently qualified context bundle");
        }
        if (isolatedEndpoint) {
            runtime.compatibility().register(bridgeContext,
                    new dev.worldgennext.neoforge.compat.CompatibilityRegistry.Decision(
                            true, route(), "isolated candidate endpoint"));
        }

        MinecraftNoiseOwnership ownership = MinecraftNoiseOwnership.capture(runtime, cache, chunk, identity);
        MinecraftChunkCommitter committer = MinecraftChunkCommitter.forChunk(chunk,
                level.registryAccess().lookupOrThrow(Registries.BLOCK), ownership::currentToken,
                ChunkStatus.BIOMES.getName());
        CommitCoordinator commits = new CommitCoordinator(committer);
        AtomicBoolean cancelled = new AtomicBoolean();
        dev.worldgennext.engine.worldgen.StageExecutor backend = (request, reservation) ->
                CompletableFuture.supplyAsync(() -> activity.compute(() -> {
                    if (cancelled.get() || activity.closed()) throw new CancellationException("CPU NOISE request was cancelled or provider closed");
                    MinecraftCpuCandidate.Capture generated = candidate.generate(inputs);
                    if (cancelled.get() || activity.closed()) throw new CancellationException("CPU NOISE request was cancelled or provider closed");
                    ChunkNoiseResult rebound = generated.result().withContextIdentity(identity.worldKey());
                    writeIsolatedDebugResult(chunk, rebound);
                    long elements = generated.dense().states().length;
                    String executionId = "cpu-live-" + executionIds.incrementAndGet();
                    ExecutionReceipt receipt = ExecutionReceipt.issued(executionId, "cpu", identity,
                                    NumericProfile.JAVA_REFERENCE, identity.programHash(), RESULT_ABI,
                                    identity.deviceGeneration(), elements)
                            .completed(elements).validated(elements).committed(elements);
                    writeIsolatedDebugReceipt(chunk, rebound, receipt);
                    return new BackendResult(ExecutionRoute.CPU_PLANNED, receipt, rebound);
                }), computeExecutor);
        CompletionStage<ChunkAccess> completion = CoordinatedNoiseAttempt.submit(runtime, chunk, ownership.token(), identity,
                backend, commits, cancelled,
                () -> MinecraftNoisePostProcessor.apply(chunk), releaseRequest);
        if (!isolatedEndpoint) return completion;
        return CompletionObservation.observe(completion.toCompletableFuture(),
                (committed, failure) -> writeIsolatedDebugStatus(chunk, failure));
        } catch (Throwable failure) {
            releaseRequest.run();
            throw failure;
        }
    }

    /** Closes admission and waits for the actual compute task, not a cancelled dependent future. */
    @Override
    public void close() {
        if (!activity.close(java.time.Duration.ofSeconds(10))) {
            LoggerFactory.getLogger(MinecraftCpuNoiseProvider.class).error(
                    "CPU provider close deadline/interruption; physical work remains owned: {}", activity.snapshot());
        }
    }

    private void beginRequest() {
        activity.beginRequest();
        requestsStarted.incrementAndGet();
    }

    private void endRequest() {
        activity.endRequest();
    }

    /**
     * Optional evidence seam for the isolated FULL/SAVED verifier.  It is
     * deliberately unavailable to the normal qualified provider and never
     * changes the commit path; the verifier uses it to compare each live
     * publication with the matching independent NOISE capture.
     */
    private void writeIsolatedDebugResult(ChunkAccess chunk, ChunkNoiseResult result) {
        if (!isolatedEndpoint) return;
        String directory = System.getProperty("worldgennext.candidate.liveNoiseResultDir", "").trim();
        if (directory.isEmpty()) return;
        try {
            Path root = Path.of(directory).toAbsolutePath().normalize();
            Files.createDirectories(root);
            Path output = root.resolve("chunk-" + chunk.getPos().x + "-" + chunk.getPos().z + ".chunk");
            Files.write(output, ChunkResultCodec.encode(result),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot write isolated live NOISE evidence for " + chunk.getPos(), failure);
        }
    }

    private void writeIsolatedDebugReceipt(ChunkAccess chunk, ChunkNoiseResult result,
                                           ExecutionReceipt receipt) {
        if (!isolatedEndpoint) return;
        String directory = System.getProperty("worldgennext.candidate.liveNoiseResultDir", "").trim();
        if (directory.isEmpty()) return;
        try {
            Path root = Path.of(directory).toAbsolutePath().normalize();
            Files.createDirectories(root);
            String stem = "chunk-" + chunk.getPos().x + "-" + chunk.getPos().z;
            Path output = root.resolve(stem + ".cpu-receipt.json");
            String json = "{\n"
                    + "  \"schemaVersion\":1,\n"
                    + "  \"kind\":\"worldgennext_cpu_live_receipt\",\n"
                    + "  \"status\":\"BACKEND_VALIDATED\",\n"
                    + "  \"executionId\":" + jsonQuote(receipt.executionId()) + ",\n"
                    + "  \"backend\":" + jsonQuote(receipt.backend()) + ",\n"
                    + "  \"route\":\"CPU_OWNED\",\n"
                    + "  \"resultAbi\":" + jsonQuote(receipt.abiVersion()) + ",\n"
                    + "  \"compilerVersion\":" + jsonQuote(MinecraftCpuNoiseProvider.COMPILER_VERSION) + ",\n"
                    + "  \"contextKey\":" + jsonQuote(receipt.context().worldKey()) + ",\n"
                    + "  \"snapshotHash\":" + jsonQuote(receipt.context().snapshotHash()) + ",\n"
                    + "  \"programHash\":" + jsonQuote(receipt.programHash()) + ",\n"
                    + "  \"worldEpoch\":" + receipt.context().worldEpoch() + ",\n"
                    + "  \"deviceGeneration\":" + receipt.deviceGeneration() + ",\n"
                    + "  \"submitted\":" + receipt.submitted() + ",\n"
                    + "  \"completed\":" + receipt.completed() + ",\n"
                    + "  \"validated\":" + receipt.validated() + ",\n"
                    + "  \"committed\":" + receipt.committed() + ",\n"
                    + "  \"resultChecksum\":" + jsonQuote(result.logicalChecksum()) + ",\n"
                    + "  \"shaderHash\":\"NOT_APPLICABLE\",\n"
                    + "  \"spirvHash\":\"NOT_APPLICABLE\"\n"
                    + "}\n";
            Files.writeString(output, json, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot write isolated live NOISE receipt for " + chunk.getPos(), failure);
        }
    }

    private void writeIsolatedDebugStatus(ChunkAccess chunk, Throwable failure) {
        if (!isolatedEndpoint) return;
        String directory = System.getProperty("worldgennext.candidate.liveNoiseResultDir", "").trim();
        if (directory.isEmpty()) return;
        try {
            Path root = Path.of(directory).toAbsolutePath().normalize();
            Files.createDirectories(root);
            String stem = "chunk-" + chunk.getPos().x + "-" + chunk.getPos().z;
            Path output = root.resolve(stem + ".candidate-status");
            String status = failure == null ? "PASS\nCOMMITTED\n" : "FAIL\n" + detail(failure) + "\n";
            Files.writeString(output, status, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (java.io.IOException statusFailure) {
            LoggerFactory.getLogger(MinecraftCpuNoiseProvider.class).error(
                    "Cannot write isolated live NOISE status for {}", chunk.getPos(), statusFailure);
        }
    }

    private static String jsonQuote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    private static String detail(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException completion
                && completion.getCause() != null ? completion.getCause() : failure;
        return cause.getClass().getSimpleName() + ": "
                + (cause.getMessage() == null ? "no detail" : cause.getMessage())
                .replace('\r', ' ').replace('\n', ' ');
    }

    private static String requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) throw new IllegalArgumentException(name + " is blank");
        return value;
    }

    private static Set<String> normalizeContextKeys(Set<String> values) {
        Objects.requireNonNull(values, "qualifiedContextKeys");
        if (values.isEmpty()) throw new IllegalArgumentException("qualifiedContextKeys is empty");
        var normalized = new java.util.TreeSet<String>();
        for (String value : values) normalized.add(requireText(value, "qualifiedContextKey"));
        return Set.copyOf(normalized);
    }
}
