// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import dev.worldgennext.engine.ExecutionRoute;
import dev.worldgennext.engine.worldgen.BackendResult;
import dev.worldgennext.engine.worldgen.CommitCoordinator;
import dev.worldgennext.material.chunk.ChunkNoiseResult;
import dev.worldgennext.neoforge.WorldgenNextMod;
import dev.worldgennext.runtime.vulkan.production.VulkanWorldgenExecutor;
import dev.worldgennext.semantic.execution.ExecutionReceipt;
import dev.worldgennext.semantic.identity.ContextIdentity;
import dev.worldgennext.semantic.program.NumericProfile;
import dev.worldgennext.semantic.program.WorldgenProgram;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.WorldGenContext;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;

/**
 * Qualified GPU composition for the version-pinned NOISE boundary.
 *
 * <p>The provider is intentionally a separately constructed object. Its
 * Vulkan executor is persistent for the provider lifetime, while each request
 * captures the caller-owned {@code WorldGenRegion} inputs and commits through
 * the authoritative holder/status ownership token. The mod constructor never
 * creates or registers this class; an independently qualified evidence
 * receipt is still required at the static registration boundary.</p>
 */
public final class MinecraftGpuNoiseProvider implements WorldgenNextMod.QualifiedNoiseProvider, AutoCloseable {
    public static final String RESULT_ABI = "chunk-result-v4";
    public static final String COMPILER_VERSION = "worldgennext-gpu-live-v0.2";

    private final RuntimeComposition runtime;
    private final MinecraftCpuCandidate captureCandidate;
    private final MinecraftGpuCandidate candidate;
    private final Set<String> qualifiedContextKeys;
    private final Executor computeExecutor;
    private final VulkanWorldgenExecutor executor;
    private final boolean isolatedEndpoint;
    private final AtomicLong executionIds = new AtomicLong();
    private final AtomicLong requestsStarted = new AtomicLong();
    private final ProviderActivity activity;

    public MinecraftGpuNoiseProvider(RuntimeComposition runtime, String qualifiedContextKey,
                                     Executor computeExecutor) {
        this(runtime, new MinecraftCpuCandidate(), new MinecraftGpuCandidate(),
                Set.of(requireText(qualifiedContextKey, "qualifiedContextKey")),
                computeExecutor,
                new VulkanWorldgenExecutor(Objects.requireNonNull(runtime, "runtime").config().nativeBudgetBytes()));
    }

    /** Creates a provider admitted for a finite set of exact captured contexts. */
    public MinecraftGpuNoiseProvider(RuntimeComposition runtime, Set<String> qualifiedContextKeys,
                                     Executor computeExecutor) {
        this(runtime, new MinecraftCpuCandidate(), new MinecraftGpuCandidate(),
                normalizeContextKeys(qualifiedContextKeys), computeExecutor,
                new VulkanWorldgenExecutor(Objects.requireNonNull(runtime, "runtime").config().nativeBudgetBytes()));
    }

    MinecraftGpuNoiseProvider(RuntimeComposition runtime, MinecraftCpuCandidate captureCandidate,
                              MinecraftGpuCandidate candidate, Set<String> qualifiedContextKeys,
                              Executor computeExecutor,
                              VulkanWorldgenExecutor executor) {
        this(runtime, captureCandidate, candidate, qualifiedContextKeys, computeExecutor, executor, false);
    }

    /** Verification-only composition; its empty allowlist cannot pass production registration. */
    public static MinecraftGpuNoiseProvider forIsolatedEndpoint(RuntimeComposition runtime,
                                                                 Executor computeExecutor) {
        LogicalCandidateBackend.GPU_IEEE_BITS.requireRuntimeCompatibility(runtime);
        return new MinecraftGpuNoiseProvider(runtime, new MinecraftCpuCandidate(), new MinecraftGpuCandidate(),
                Set.of(), computeExecutor, new VulkanWorldgenExecutor(runtime.config().nativeBudgetBytes()), true);
    }

    private MinecraftGpuNoiseProvider(RuntimeComposition runtime, MinecraftCpuCandidate captureCandidate,
                                     MinecraftGpuCandidate candidate, Set<String> qualifiedContextKeys,
                                     Executor computeExecutor, VulkanWorldgenExecutor executor,
                                     boolean isolatedEndpoint) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.captureCandidate = Objects.requireNonNull(captureCandidate, "captureCandidate");
        this.candidate = Objects.requireNonNull(candidate, "candidate");
        this.qualifiedContextKeys = isolatedEndpoint ? Set.of() : normalizeContextKeys(qualifiedContextKeys);
        this.computeExecutor = Objects.requireNonNull(computeExecutor, "computeExecutor");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.isolatedEndpoint = isolatedEndpoint;
        this.activity = new ProviderActivity(executor::close);
    }

    @Override
    public String route() {
        return "GPU_IEEE_BITS";
    }

    @Override
    public String resultAbi() {
        return RESULT_ABI;
    }

    @Override
    public String compilerVersion() {
        return COMPILER_VERSION;
    }

    @Override
    public Set<String> qualifiedContextKeys() {
        return qualifiedContextKeys;
    }

    public long requestsStarted() { return requestsStarted.get(); }

    @Override
    public CompletionStage<ChunkAccess> generate(WorldGenContext context, ChunkStep step,
                                                 StaticCache2D<GenerationChunkHolder> cache,
                                                 ChunkAccess chunk) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(chunk, "chunk");
        if (step.targetStatus() != ChunkStatus.NOISE) {
            throw new IllegalArgumentException("GPU provider requires the NOISE step; got "
                    + step.targetStatus().getName());
        }
        ServerLevel level = Objects.requireNonNull(context.level(), "context.level");
        beginRequest();
        AtomicBoolean ended = new AtomicBoolean();
        Runnable releaseRequest = () -> {
            if (ended.compareAndSet(false, true)) endRequest();
        };

        try {
            // Capture the target's actual Blender boundary once. This does not
            // request another holder or schedule a competing generation task.
            MinecraftCpuCandidate.Inputs inputs = captureCandidate.capture(level, cache, step, chunk);
            WorldgenProgram program = WorldgenProgram.builder().roots(inputs.snapshot().router().roots()).build();
            ContextIdentity identity = ContextIdentity.of(inputs.snapshot(), program,
                    NumericProfile.GPU_IEEE_BITS, RESULT_ABI, COMPILER_VERSION,
                    runtime.reloadCoordinator().worldEpoch(), runtime.reloadCoordinator().deviceGeneration());
            if (!isolatedEndpoint && !qualifiedContextKeys.contains(identity.worldKey())) {
                throw new IllegalStateException("captured context is not in the independently qualified GPU context bundle");
            }
            if (isolatedEndpoint) {
                runtime.compatibility().register(identity.worldKey(),
                        new dev.worldgennext.neoforge.compat.CompatibilityRegistry.Decision(
                                true, route(), "isolated GPU candidate endpoint; not production qualification"));
            }

            MinecraftNoiseOwnership ownership = MinecraftNoiseOwnership.capture(runtime, cache, chunk, identity);
            MinecraftChunkCommitter committer = MinecraftChunkCommitter.forChunk(chunk,
                    level.registryAccess().lookupOrThrow(Registries.BLOCK), ownership::currentToken,
                    ChunkStatus.BIOMES.getName());
            CommitCoordinator commits = new CommitCoordinator(committer);
            AtomicBoolean cancelled = new AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<ExecutionReceipt> backendReceipt =
                    new java.util.concurrent.atomic.AtomicReference<>();
            dev.worldgennext.engine.worldgen.StageExecutor backend = (request, reservation) ->
                    CompletableFuture.supplyAsync(() -> activity.compute(() -> {
                        if (cancelled.get() || activity.closed()) throw new CancellationException("GPU NOISE request was cancelled or provider closed");
                        MinecraftGpuCandidate.DeviceGeneration generation = candidate.generateDevice(inputs, executor);
                        if (generation.numericProfile() != NumericProfile.GPU_IEEE_BITS) {
                            throw new IllegalStateException("Qualified GPU provider refuses prototype numerical results");
                        }
                        if (cancelled.get() || activity.closed()) throw new CancellationException("GPU NOISE request was cancelled or provider closed");
                        ChunkNoiseResult rebound = generation.gpuResult().withContextIdentity(identity.worldKey());
                        // Commit coverage is the storage ABI, including an explicit air tail;
                        // logicalGpuElements separately counts actual device output rows.
                        int elements = rebound.denseStates().length;
                        String executionId = "gpu-live-" + executionIds.incrementAndGet();
                        ExecutionReceipt receipt = ExecutionReceipt.issued(executionId, "gpu", identity,
                                        NumericProfile.GPU_IEEE_BITS, identity.programHash(), RESULT_ABI,
                                        generation.shaderHash(), generation.spirvHash(),
                                        identity.deviceGeneration(), elements)
                                .completed(elements).validated(elements);
                        backendReceipt.set(receipt);
                        if (isolatedEndpoint) {
                            IsolatedGpuEvidence.writeBackend(chunk.getPos().x, chunk.getPos().z,
                                    rebound, receipt, generation);
                        }
                        return new BackendResult(ExecutionRoute.GPU, receipt, rebound);
                    }), computeExecutor);
            CompletionStage<ChunkAccess> completion = CoordinatedNoiseAttempt.submit(runtime, chunk, ownership.token(), identity,
                    backend, commits, cancelled,
                    () -> MinecraftNoisePostProcessor.apply(chunk), releaseRequest);
            if (!isolatedEndpoint) return completion;
            return CompletionObservation.observe(completion.toCompletableFuture(), (committed, failure) ->
                    IsolatedGpuEvidence.writeTerminal(chunk.getPos().x, chunk.getPos().z,
                            backendReceipt.get(), failure));
        } catch (Throwable failure) {
            releaseRequest.run();
            throw failure;
        }
    }

    /** Closes the provider-owned persistent Vulkan device and queue. */
    @Override
    public void close() {
        try {
            if (!activity.close(java.time.Duration.ofSeconds(10))) {
                LoggerFactory.getLogger(MinecraftGpuNoiseProvider.class).error(
                        "GPU provider close deadline/interruption; disposal deferred: {}", activity.snapshot());
            }
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(MinecraftGpuNoiseProvider.class).error(
                    "GPU provider resource close failed; no healthy drain claimed", failure);
        }
    }

    private void beginRequest() {
        activity.beginRequest();
        requestsStarted.incrementAndGet();
    }

    private void endRequest() {
        activity.endRequest();
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
