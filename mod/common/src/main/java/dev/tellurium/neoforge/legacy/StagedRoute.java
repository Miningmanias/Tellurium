// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.legacy;

import dev.tellurium.neoforge.loader.Loader;

import dev.tellurium.frontend.mc1211.Minecraft1211Frontend;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.tellurium.material.chunk.ChunkResultCodec;
import dev.tellurium.neoforge.DiagnosticSelfTest;
import dev.tellurium.neoforge.TelluriumMod;
import dev.tellurium.neoforge.command.TelluriumCommands;
import dev.tellurium.neoforge.compat.CompatibilityRegistry;
import dev.tellurium.neoforge.config.TelluriumConfig;
import dev.tellurium.neoforge.config.TelluriumConfigLoader;
import dev.tellurium.neoforge.runtime.MinecraftCpuCandidate;
import dev.tellurium.neoforge.runtime.MinecraftCpuNoiseProvider;
import dev.tellurium.neoforge.runtime.MinecraftGpuCandidate;
import dev.tellurium.neoforge.runtime.MinecraftGpuNoiseProvider;
import dev.tellurium.neoforge.runtime.LogicalCandidateBackend;
import dev.tellurium.neoforge.runtime.MinecraftLogicalSnapshotWriter;
import dev.tellurium.neoforge.runtime.HookTelemetry;
import dev.tellurium.neoforge.runtime.QualificationEvidenceFile;
import dev.tellurium.neoforge.runtime.QualificationEvidenceBundleFile;
import dev.tellurium.neoforge.runtime.QualifiedHookAdmission;
import dev.tellurium.neoforge.runtime.QualifiedHookEvidence;
import dev.tellurium.neoforge.runtime.QualifiedHookEvidenceBundle;
import dev.tellurium.neoforge.runtime.RuntimeComposition;
import dev.tellurium.runtime.vulkan.production.VulkanWorldgenExecutor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.util.StaticCache2D;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The staged qualification route: evidence-gated NOISE providers, candidate capture endpoints and the
 * runtime composition behind them.  Developer tooling only.  Nothing here generates chunks unless an
 * operator supplies qualification evidence or a prototype flag; the terrain path players get is the
 * fused GPU path in {@code dev.tellurium.neoforge.fast}.
 *
 * <p>{@link TelluriumMod} forwards the server lifecycle here and mounts {@link #developerCommands()}
 * under {@code /tellurium dev}.</p>
 */
public final class StagedRoute {
    private static final String MOD_ID = TelluriumMod.MOD_ID;
    private static final String VERSION = TelluriumMod.VERSION;
    /** Benchmark-only ceiling probe (empty NOISE); requires the benchmark autorun to be active. */
    private static final boolean BENCH_NULL_NOISE = Boolean.getBoolean("tellurium.bench.nullNoise")
            && Boolean.getBoolean("tellurium.bench.autorun");
    private static volatile RuntimeComposition RUNTIME = new RuntimeComposition(TelluriumConfig.defaults(), new CompatibilityRegistry());
    private static volatile QualifiedNoiseProvider QUALIFIED_NOISE_PROVIDER;
    private static volatile QualifiedHookEvidence QUALIFIED_NOISE_EVIDENCE;
    private static volatile QualifiedHookEvidenceBundle QUALIFIED_NOISE_BUNDLE;
    private static volatile ExecutorService QUALIFIED_COMPUTE_EXECUTOR;
    private static volatile Path QUALIFIED_EVIDENCE_FILE;
    private static volatile String QUALIFIED_EVIDENCE_STATUS = "NOT_CONFIGURED";
    private static final HookTelemetry HOOK_TELEMETRY = new HookTelemetry();
    /** Provider used by the opt-in isolated endpoint and draft CPU-live run. */
    private static volatile QualifiedNoiseProvider ISOLATED_CANDIDATE_PROVIDER;
    private static volatile ExecutorService ISOLATED_COMPUTE_EXECUTOR;
    private static final long DEFAULT_CANDIDATE_LOGICAL_TIMEOUT_MILLIS = 180_000L;
    private record CandidateCase(int chunkX, int chunkZ, Path output) {}
    private record GpuCandidateCase(CandidateCase request, MinecraftCpuCandidate.Inputs inputs) {}
    private record CpuCandidateWork(CandidateCase request, Future<MinecraftCpuCandidate.Capture> result) {}

    /**
     * Version-pinned provider boundary used by the optional generateNoise
     * mixin.  Implementations must capture and validate Minecraft ownership
     * themselves; this API never grants the provider a second holder scheduler.
     */
    @FunctionalInterface
    public interface QualifiedNoiseProvider {
        CompletionStage<ChunkAccess> generate(WorldGenContext context, ChunkStep step,
                                               StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk);

        /** Route identity is part of admission evidence, never inferred from a callback label. */
        default String route() { return "UNDECLARED"; }

        /** Result wire identity must match the receipt before a live hook is admitted. */
        default String resultAbi() { return "UNDECLARED"; }

        /** Compiler identity prevents stale or differently lowered providers from being rebound. */
        default String compilerVersion() { return "UNDECLARED"; }

        /** Exact context identities admitted by this provider; empty means not qualified for registration. */
        default Set<String> qualifiedContextKeys() { return Set.of(); }

        /** Diagnostic count of actual NOISE requests; no qualification is inferred from it. */
        default long requestsStarted() { return 0; }
    }

    public enum HookAction { BYPASS, REPLACE, FAIL }

    /** Mixin-facing decision; BYPASS is the only result that invokes vanilla. */
    public record HookResult(HookAction action, CompletableFuture<ChunkAccess> future, String reason) {
        public HookResult {
            Objects.requireNonNull(action, "action");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Hook reason is required");
            if (action == HookAction.BYPASS && future != null) throw new IllegalArgumentException("Bypass cannot carry a future");
            if (action != HookAction.BYPASS && future == null) throw new IllegalArgumentException("Active hook result requires a future");
        }
        public static HookResult bypass(String reason) { return new HookResult(HookAction.BYPASS, null, reason); }
        public static HookResult replace(CompletableFuture<ChunkAccess> future, String reason) {
            return new HookResult(HookAction.REPLACE, Objects.requireNonNull(future, "future"), reason);
        }
        public static HookResult failed(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            return new HookResult(HookAction.FAIL, CompletableFuture.failedFuture(failure),
                    "qualified provider failed: " + detail(failure));
        }
    }

    private StagedRoute() {}

    /** Called once from the mod constructor. */
    public static void init() {
        clearQualifiedNoiseProvider(null);
        QUALIFIED_EVIDENCE_STATUS = "NOT_CONFIGURED";
        var loadedConfig = TelluriumConfigLoader.load(Loader.configDirectory());
        RUNTIME = new RuntimeComposition(loadedConfig.config(), new CompatibilityRegistry());
        LoggerFactory.getLogger(MOD_ID).debug(
                "Staged route: mode={}, configFile={}, configPresent={}, native={}; the GPU_IEEE_BITS hook stays disabled until its oracle qualification",
                RUNTIME.config().mode(), loadedConfig.file(), loadedConfig.filePresent(), RUNTIME.nativeBootstrap().state());
    }

    /** Benchmark route label when a staged or prototype provider is active, else null. */
    public static String benchmarkRoute() {
        if (BENCH_NULL_NOISE) return "DIAGNOSTIC_NULL_NOISE_CEILING";
        if (draftCpuLiveMode()) return "PROTOTYPE_CPU_LIVE";
        return RUNTIME.config().enableQualifiedHook() ? "QUALIFIED_HOOK" : null;
    }

    /**
     * Retained as a fail-closed compatibility seam. A callback without its
     * immutable qualification evidence must never replace vanilla NOISE.
     */
    public static synchronized void registerQualifiedNoiseProvider(QualifiedNoiseProvider provider) {
        throw new IllegalStateException("Independent qualification evidence is required; use the evidence-bearing registration method");
    }

    /**
     * Installs the one provider allowed to replace vanilla NOISE after the
     * independent capture comparison has met the product admission bar.
     */
    public static synchronized void registerQualifiedNoiseProvider(QualifiedNoiseProvider provider,
                                                                    QualifiedHookEvidence evidence) {
        QualifiedHookEvidenceBundle bundle;
        try {
            bundle = QualifiedHookEvidenceBundle.single(evidence);
        } catch (RuntimeException failure) {
            // Invalid single-receipt evidence can fail before reaching bundle
            // admission. That newly-created provider still needs teardown.
            if (QUALIFIED_NOISE_PROVIDER != provider) closeQualifiedNoiseProvider(provider);
            throw failure;
        }
        registerQualifiedNoiseProvider(provider, bundle);
    }

    /**
     * Installs one provider against a finite allowlist of exact context keys.
     * Aggregate evidence may cover the six required matrix contexts while
     * every live request still has to match one captured identity exactly.
     */
    public static synchronized void registerQualifiedNoiseProvider(QualifiedNoiseProvider provider,
                                                                    QualifiedHookEvidenceBundle evidence) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(evidence, "evidence");
        try {
            validateQualifiedProviderAdmission(provider, evidence, RUNTIME.config());
            if (QUALIFIED_NOISE_PROVIDER != null && QUALIFIED_NOISE_PROVIDER != provider) {
                throw new IllegalStateException("A qualified NOISE provider is already registered");
            }
            if (QUALIFIED_NOISE_PROVIDER == provider && !evidence.equals(QUALIFIED_NOISE_BUNDLE)) {
                throw new IllegalStateException("The registered provider cannot be rebound to different qualification evidence");
            }
            for (String contextKey : evidence.contextKeys()) {
                RUNTIME.compatibility().register(contextKey,
                        new CompatibilityRegistry.Decision(true, evidence.route(),
                                "independent same-stack NOISE qualification admitted"));
            }
            QUALIFIED_NOISE_PROVIDER = provider;
            QUALIFIED_NOISE_BUNDLE = evidence;
            QUALIFIED_NOISE_EVIDENCE = evidence.entries().size() == 1 ? evidence.entries().get(0) : null;
        } catch (RuntimeException failure) {
            // A provider created for a rejected admission has no runtime owner.
            // Close only that uninstalled instance; never tear down an already
            // registered provider during a conflicting rebind attempt.
            if (QUALIFIED_NOISE_PROVIDER != provider) closeQualifiedNoiseProvider(provider);
            throw failure;
        }
    }

    /**
     * Pure admission contract shared by startup registration and the direct
     * receipt-path test. Keeping it separate makes the identity checks
     * executable without constructing a Minecraft server or Vulkan device.
     */
    static void validateQualifiedProviderAdmission(QualifiedNoiseProvider provider,
                                                   QualifiedHookEvidence evidence,
                                                   TelluriumConfig config) {
        validateQualifiedProviderAdmission(provider, QualifiedHookEvidenceBundle.single(evidence), config);
    }

    static void validateQualifiedProviderAdmission(QualifiedNoiseProvider provider,
                                                   QualifiedHookEvidenceBundle evidence,
                                                   TelluriumConfig config) {
        Objects.requireNonNull(provider, "provider");
        QualifiedHookAdmission.validate(evidence,
                new QualifiedHookAdmission.ProviderIdentity(provider.route(), provider.resultAbi(),
                        provider.compilerVersion()), config);
        Set<String> providerContexts = Set.copyOf(provider.qualifiedContextKeys());
        if (!providerContexts.equals(evidence.contextKeys())) {
            throw new IllegalArgumentException("Provider qualified context allowlist does not exactly match evidence bundle");
        }
    }

    /** Clears a provider during controlled server teardown or test setup. */
    public static synchronized void clearQualifiedNoiseProvider(QualifiedNoiseProvider provider) {
        if (provider == null || QUALIFIED_NOISE_PROVIDER == provider) {
            QualifiedNoiseProvider previous = QUALIFIED_NOISE_PROVIDER;
            QUALIFIED_NOISE_PROVIDER = null;
            QUALIFIED_NOISE_EVIDENCE = null;
            QUALIFIED_NOISE_BUNDLE = null;
            closeQualifiedNoiseProvider(previous);
        }
    }

    /**
     * Installs the one-shot provider used by the isolated candidate endpoint
     * harness. It is deliberately separate from qualified production
     * registration: the harness exercises the real ChunkStatus chain without
     * turning an output artifact into admission evidence.
     */
    private static synchronized void installIsolatedCandidateProvider(QualifiedNoiseProvider provider) {
        Objects.requireNonNull(provider, "provider");
        if (!candidateLiveMode()) {
            throw new IllegalStateException("isolated candidate provider requires candidate live mode");
        }
        if (ISOLATED_CANDIDATE_PROVIDER != null) {
            throw new IllegalStateException("An isolated candidate provider is already registered");
        }
        ISOLATED_CANDIDATE_PROVIDER = provider;
    }

    private static synchronized void clearIsolatedCandidateProvider() {
        QualifiedNoiseProvider previous = ISOLATED_CANDIDATE_PROVIDER;
        ISOLATED_CANDIDATE_PROVIDER = null;
        closeQualifiedNoiseProvider(previous);
    }

    /**
     * Installs the deliberately unqualified CPU prototype for an ordinary
     * running server.  This is separate from evidence-bearing registration:
     * it is useful for proving that the live commit/downstream wiring works,
     * but it must never be mistaken for a release-qualified route.
     */
    private static void installDraftCpuLiveProviderIfRequested() {
        if (!Boolean.parseBoolean(System.getProperty("tellurium.prototype.cpuLive", "false"))) return;
        if (Boolean.parseBoolean(System.getProperty("tellurium.candidate.capture", "false"))
                || Boolean.parseBoolean(System.getProperty("tellurium.gpuCandidate.capture", "false"))) return;
        TelluriumConfig config = RUNTIME.config();
        if (config.mode() == TelluriumConfig.Mode.GPU_REQUIRED) {
            throw new IllegalStateException(
                    "tellurium.prototype.cpuLive is forbidden in GPU_REQUIRED mode");
        }
        if (QUALIFIED_NOISE_PROVIDER != null) {
            throw new IllegalStateException(
                    "Draft CPU-live mode cannot be combined with a qualified NOISE provider");
        }
        ExecutorService compute = Executors.newFixedThreadPool(
                prototypeCpuWorkerCount(), candidateThreadFactory());
        QualifiedNoiseProvider provider = MinecraftCpuNoiseProvider.forIsolatedEndpoint(RUNTIME, compute);
        try {
            installIsolatedCandidateProvider(provider);
            ISOLATED_COMPUTE_EXECUTOR = compute;
            LoggerFactory.getLogger(MOD_ID).warn(
                    "Enabled unqualified draft CPU-live NOISE hook; this is prototype-only and does not satisfy G9/G12 qualification");
        } catch (RuntimeException failure) {
            closeQualifiedNoiseProvider(provider);
            compute.shutdownNow();
            throw failure;
        }
    }

    private static void shutdownIsolatedComputeExecutor() {
        ExecutorService executor = ISOLATED_COMPUTE_EXECUTOR;
        ISOLATED_COMPUTE_EXECUTOR = null;
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                LoggerFactory.getLogger(MOD_ID).error(
                        "Draft CPU-live compute executor did not stop before the lifecycle deadline");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LoggerFactory.getLogger(MOD_ID).error(
                    "Interrupted while stopping draft CPU-live compute executor");
        }
    }

    /** Called only by the version-pinned ChunkStatusTasks mixin. */
    public static HookResult interceptNoise(WorldGenContext context, ChunkStep step,
                                            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk) {
        RuntimeComposition runtime = RUNTIME;
        if (BENCH_NULL_NOISE) {
            // Diagnostic ceiling measurement only: leaves the chunk empty. Never a generation route.
            return HookResult.replace(CompletableFuture.completedFuture(chunk), "benchmark null-NOISE ceiling probe");
        }
        // The isolated logical verifier requests live mode before the
        // ServerStarted callback, but its provider is not installed until
        // that callback's queued task runs.  Do not make the startup spawn
        // region fail just because the verifier is not armed yet.
        boolean candidateLive = candidateLiveMode()
                && (ISOLATED_CANDIDATE_PROVIDER != null || draftCpuLiveMode());
        if (!runtime.config().enableQualifiedHook() && !candidateLive) {
            if (runtime.config().mode() == TelluriumConfig.Mode.GPU_REQUIRED) {
                return observe(HookResult.failed(new IllegalStateException(
                        "GPU_REQUIRED cannot run with enableQualifiedHook=false")));
            }
            return observe(HookResult.bypass("qualified generation hook is disabled by configuration"));
        }
        QualifiedNoiseProvider provider = QUALIFIED_NOISE_PROVIDER;
        QualifiedHookEvidenceBundle evidence = QUALIFIED_NOISE_BUNDLE;
        if (provider == null && candidateLive) provider = ISOLATED_CANDIDATE_PROVIDER;
        if (provider == null) {
            if (runtime.config().mode() == TelluriumConfig.Mode.GPU_REQUIRED || candidateLive) {
                return observe(HookResult.failed(new IllegalStateException(
                        candidateLive ? "draft candidate live mode has no installed provider"
                                : "GPU_REQUIRED has no independently qualified NOISE provider")));
            }
            return observe(HookResult.bypass("no independently qualified NOISE provider is registered"));
        }
        if (!candidateLive && evidence == null) {
            if (runtime.config().mode() == TelluriumConfig.Mode.GPU_REQUIRED) {
                return observe(HookResult.failed(new IllegalStateException(
                        "GPU_REQUIRED has no independently qualified NOISE evidence")));
            }
            return observe(HookResult.bypass("no independently qualified NOISE evidence is registered"));
        }
        if (!candidateLive && !evidence.route().equals(provider.route())) {
            return observe(HookResult.failed(new IllegalStateException("registered provider route no longer matches qualification evidence")));
        }
        if (!candidateLive && (!Objects.equals(evidence.resultAbi(), provider.resultAbi())
                || !Objects.equals(evidence.compilerVersion(), provider.compilerVersion()))) {
            return observe(HookResult.failed(new IllegalStateException(
                    "registered provider identity no longer matches qualification evidence")));
        }
        if (context == null || step == null || cache == null || chunk == null) {
            return observe(HookResult.failed(new IllegalArgumentException("qualified NOISE hook received incomplete Minecraft task inputs")));
        }
        if (step.targetStatus() != ChunkStatus.NOISE) {
            return observe(HookResult.failed(new IllegalStateException("qualified NOISE hook received non-NOISE target status: "
                    + step.targetStatus().getName())));
        }
        try {
            CompletionStage<ChunkAccess> result = provider.generate(context, step, cache, chunk);
            if (result == null) {
                return observe(HookResult.failed(new IllegalStateException("qualified provider returned no future")));
            }
            CompletableFuture<ChunkAccess> sameTarget = validatedProviderFuture(result, chunk);
            return observe(HookResult.replace(sameTarget, "qualified NOISE provider accepted the qualified stage"));
        } catch (Throwable failure) {
            return observe(HookResult.failed(failure));
        }
    }

    private static HookResult observe(HookResult result) {
        switch (result.action()) {
            case BYPASS -> HOOK_TELEMETRY.recordBypass();
            case FAIL -> HOOK_TELEMETRY.recordFailure();
            case REPLACE -> HOOK_TELEMETRY.recordReplacement(result.future());
        }
        return result;
    }

    /** Process-local hook telemetry for operator diagnostics and draft tuning. */
    public static HookTelemetry.Snapshot hookTelemetry() {
        return HOOK_TELEMETRY.snapshot();
    }

    /** Builds the cancellation-aware target-validation future used by the hook. */
    public static CompletableFuture<ChunkAccess> validatedProviderFuture(CompletionStage<ChunkAccess> result,
                                                                         ChunkAccess chunk) {
        Objects.requireNonNull(result, "result");
        CompletableFuture<ChunkAccess> providerFuture = result.toCompletableFuture();
            // The mixin-facing validation future must retain cancellation
            // authority over the provider future.  Without this forwarding,
            // cancelling the holder's replacement stage would leave the
            // provider's worker (and, for GPU, its native request) running
            // with no observable consumer.
        CompletableFuture<ChunkAccess> sameTarget = new CompletableFuture<>() {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning) {
                    boolean cancelled = super.cancel(mayInterruptIfRunning);
                    if (cancelled) providerFuture.cancel(mayInterruptIfRunning);
                    return cancelled;
                }
            };
        providerFuture.whenComplete((candidate, failure) -> {
                if (failure != null) {
                    if (providerFuture.isCancelled()) sameTarget.cancel(false);
                    else sameTarget.completeExceptionally(failure);
                    return;
                }
                if (candidate != chunk) {
                    sameTarget.completeExceptionally(new IllegalStateException(
                            "qualified provider completed with a different ChunkAccess target"));
                    return;
                }
                sameTarget.complete(candidate);
        });
        return sameTarget;
    }

    public static String status() {
        QualifiedNoiseProvider provider = QUALIFIED_NOISE_PROVIDER;
        String providerStatus = provider == null ? "NONE" : provider.route();
        String providerAbi = provider == null ? "NONE" : provider.resultAbi();
        String providerCompiler = provider == null ? "NONE" : provider.compilerVersion();
        var coordinator = RUNTIME.coordinator();
        var coordinatorStatus = coordinator.snapshot();
        var work = coordinatorStatus.counters();
        String evidenceStatus = QUALIFIED_EVIDENCE_FILE == null
                ? "NONE" : QUALIFIED_EVIDENCE_FILE.toString();
        return "Tellurium " + VERSION + ": runtime contracts ready; generationInterception="
                + new Minecraft1211Frontend().canInterceptGeneration()
                + "; MinecraftFrontend=CAPTURE_ADAPTER_PARTIAL; qualifiedProvider=" + providerStatus
                + "; qualifiedProviderAbi=" + providerAbi + "; qualifiedProviderCompiler=" + providerCompiler
                + "; qualificationFile=" + evidenceStatus + "; qualificationStatus="
                + QUALIFIED_EVIDENCE_STATUS + "; vanillaParity=NOT_RUN"
                + "; lifecycle=" + RUNTIME.serverLifecycle().state()
                + "; worldEpoch=" + RUNTIME.reloadCoordinator().worldEpoch()
                + "; deviceGeneration=" + RUNTIME.reloadCoordinator().deviceGeneration()
                + "; coordinatorRequested=" + work.requested()
                + "; coordinatorUnique=" + work.uniqueWork()
                + "; coordinatorActive=" + coordinatorStatus.activeRecords()
                + "; coordinatorQueue=" + coordinatorStatus.queueDepth() + "/" + coordinatorStatus.queueCapacity()
                + "; coordinatorReservedBytes=" + coordinatorStatus.reservedBytes() + "/"
                + coordinatorStatus.resourceBudgetBytes()
                + "; coordinatorLifecycle=" + coordinatorStatus.lifecycle()
                + "; coordinatorCommitted=" + work.committed()
                + "; coordinatorStale=" + work.stale()
                + "; coordinatorFailed=" + work.failed()
                + "; qualifiedContexts=" + (QUALIFIED_NOISE_BUNDLE == null ? 0 : QUALIFIED_NOISE_BUNDLE.contextKeys().size())
                + "; qualifiedCases=" + (QUALIFIED_NOISE_BUNDLE == null ? 0 : QUALIFIED_NOISE_BUNDLE.comparedCases())
                + "; prototypeCpuLive=" + Boolean.parseBoolean(
                        System.getProperty("tellurium.prototype.cpuLive", "false"))
                + "; prototypeProvider=" + (ISOLATED_CANDIDATE_PROVIDER == null
                        ? "NONE" : ISOLATED_CANDIDATE_PROVIDER.route())
                + "; " + TelluriumCommands.status(RUNTIME.config(), RUNTIME.nativeBootstrap(), false,
                coordinator, HOOK_TELEMETRY);
    }

    /** Machine-readable companion to the human-facing status command. */
    public static String statusJson() {
        return TelluriumCommands.statusJson(RUNTIME.config(), RUNTIME.nativeBootstrap(), false,
                RUNTIME.coordinator(), HOOK_TELEMETRY, hookStatus());
    }

    private static TelluriumCommands.HookStatus hookStatus() {
        QualifiedNoiseProvider provider = QUALIFIED_NOISE_PROVIDER;
        return new TelluriumCommands.HookStatus(
                QUALIFIED_EVIDENCE_STATUS,
                QUALIFIED_EVIDENCE_FILE == null ? "" : QUALIFIED_EVIDENCE_FILE.toString(),
                provider == null ? "NONE" : provider.route(),
                provider == null ? "NONE" : provider.resultAbi(),
                provider == null ? "NONE" : provider.compilerVersion(),
                QUALIFIED_NOISE_BUNDLE == null ? 0 : QUALIFIED_NOISE_BUNDLE.contextKeys().size(),
                QUALIFIED_NOISE_BUNDLE == null ? 0 : QUALIFIED_NOISE_BUNDLE.comparedCases(),
                QUALIFIED_NOISE_BUNDLE == null ? 0 : QUALIFIED_NOISE_BUNDLE.comparedFields(),
                ISOLATED_CANDIDATE_PROVIDER != null,
                ISOLATED_CANDIDATE_PROVIDER == null ? "NONE" : ISOLATED_CANDIDATE_PROVIDER.route());
    }

    /** True once a server has stopped and closed {@link #RUNTIME}. */
    private static volatile boolean RUNTIME_CLOSED;

    public static void serverAboutToStart(MinecraftServer server) {
        // Singleplayer opens several worlds in one JVM: each gets a runtime of its own.
        if (RUNTIME_CLOSED) {
            RUNTIME = new RuntimeComposition(TelluriumConfigLoader.load(Loader.configDirectory()).config(), new CompatibilityRegistry());
            QUALIFIED_EVIDENCE_STATUS = "NOT_CONFIGURED";
            RUNTIME_CLOSED = false;
        }
        // NeoForge constructs the spawn region from this callback before it
        // emits ServerStarted.  The explicit draft CPU-live route therefore
        // cannot queue its completion onto the server mailbox yet: the server
        // thread is synchronously waiting for the same futures.  Keep the
        // draft's startup commit callback local until the ready transition;
        // qualified production work retains the normal server mailbox.
        if (draftCpuLiveMode()) RUNTIME.bindAuthoritativeExecutor(Runnable::run);
        else RUNTIME.bindAuthoritativeExecutor(server::execute);
        RUNTIME.serverStarting();
        installConfiguredQualifiedProvider();
        installDraftCpuLiveProviderIfRequested();
    }

    public static void serverStarted(MinecraftServer server) {
        if (draftCpuLiveMode()) RUNTIME.bindAuthoritativeExecutor(server::execute);
        RUNTIME.serverReady();
    }

    public static void serverStopping(MinecraftServer server) {
        RUNTIME.serverStopping();
    }

    public static void serverStopped(MinecraftServer server) {
        try {
            RUNTIME.serverStopped();
        } finally {
            clearQualifiedNoiseProvider(null);
            clearIsolatedCandidateProvider();
            shutdownIsolatedComputeExecutor();
            shutdownQualifiedComputeExecutor();
            closeRuntimeCoordinator(RUNTIME);
            RUNTIME_CLOSED = true;
        }
    }

    /**
     * Installs a provider only when the operator supplies a complete,
     * independently produced receipt. Configuration alone never admits a
     * route. AUTO/CPU_ONLY keep vanilla generation when the receipt is
     * absent or rejected; GPU_REQUIRED fails startup so it cannot silently
     * run the original route under a strict setting.
     */
    private static void installConfiguredQualifiedProvider() {
        TelluriumConfig config = RUNTIME.config();
        QUALIFIED_EVIDENCE_FILE = null;
        QUALIFIED_EVIDENCE_STATUS = config.enableQualifiedHook() ? "NO_RECEIPT_CONFIGURED" : "HOOK_DISABLED";
        if (config.mode() == TelluriumConfig.Mode.GPU_REQUIRED && !config.enableQualifiedHook()) {
            throw new IllegalStateException("GPU_REQUIRED requires enableQualifiedHook=true");
        }
        String configured = config.qualifiedEvidenceFile();
        if (!config.enableQualifiedHook() || configured.isBlank()) {
            if (config.mode() == TelluriumConfig.Mode.GPU_REQUIRED) {
                throw new IllegalStateException("GPU_REQUIRED requires qualifiedEvidenceFile");
            }
            return;
        }

        Path file = Path.of(configured);
        if (!file.isAbsolute()) file = Loader.configDirectory().resolve(file);
        file = file.toAbsolutePath().normalize();
        QUALIFIED_EVIDENCE_FILE = file;
        QualifiedHookEvidenceBundle evidence;
        try {
            if (QualificationEvidenceBundleFile.hasBundleSchema(file)) {
                evidence = QualificationEvidenceBundleFile.load(file).bundle();
            } else {
                evidence = QualifiedHookEvidenceBundle.single(QualificationEvidenceFile.load(file).evidence());
            }
        } catch (RuntimeException failure) {
            handleConfiguredProviderFailure(config, file, failure);
            return;
        }

        ExecutorService compute = Executors.newSingleThreadExecutor(qualifiedThreadFactory());
        QualifiedNoiseProvider provider = null;
        try {
            provider = switch (evidence.route()) {
                case "CPU_OWNED" -> new MinecraftCpuNoiseProvider(RUNTIME, evidence.contextKeys(), compute);
                case "GPU_IEEE_BITS" -> new dev.tellurium.neoforge.runtime.MinecraftGpuNoiseProvider(
                        RUNTIME, evidence.contextKeys(), compute);
                default -> throw new IllegalArgumentException("Unsupported qualification route: " + evidence.route());
            };
            registerQualifiedNoiseProvider(provider, evidence);
            QUALIFIED_COMPUTE_EXECUTOR = compute;
            QUALIFIED_EVIDENCE_STATUS = "ADMITTED:" + evidence.route();
            LoggerFactory.getLogger(MOD_ID).info(
                    "Admitted qualified NOISE provider route={} contexts={} evidenceFile={} comparedCases={} comparedFields={}",
                    evidence.route(), evidence.contextKeys().size(), file, evidence.comparedCases(), evidence.comparedFields());
        } catch (RuntimeException failure) {
            compute.shutdownNow();
            handleConfiguredProviderFailure(config, file, failure);
        }
    }

    private static void handleConfiguredProviderFailure(TelluriumConfig config, Path file, RuntimeException failure) {
        QUALIFIED_EVIDENCE_STATUS = "REJECTED:" + singleLine(detail(failure));
        if (config.mode() == TelluriumConfig.Mode.GPU_REQUIRED) {
            throw new IllegalStateException("GPU_REQUIRED qualification admission failed for " + file + ": "
                    + detail(failure), failure);
        }
        LoggerFactory.getLogger(MOD_ID).error(
                "Qualified NOISE evidence was rejected; continuing with explicit original route: {}", file, failure);
    }

    private static ThreadFactory qualifiedThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "tellurium-qualified-noise");
            thread.setDaemon(false);
            return thread;
        };
    }

    private static void shutdownQualifiedComputeExecutor() {
        ExecutorService executor = QUALIFIED_COMPUTE_EXECUTOR;
        QUALIFIED_COMPUTE_EXECUTOR = null;
        QUALIFIED_EVIDENCE_FILE = null;
        QUALIFIED_EVIDENCE_STATUS = "NOT_CONFIGURED";
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                LoggerFactory.getLogger(MOD_ID).error("Qualified NOISE compute executor did not stop before the lifecycle deadline");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LoggerFactory.getLogger(MOD_ID).error("Interrupted while stopping qualified NOISE compute executor");
        }
    }

    private static void closeQualifiedNoiseProvider(QualifiedNoiseProvider provider) {
        if (!(provider instanceof AutoCloseable closeable)) return;
        try {
            closeable.close();
        } catch (Exception failure) {
            LoggerFactory.getLogger(MOD_ID).error("Qualified NOISE provider close failed; provider was detached", failure);
        }
    }

    /** The {@code dev} subtree of /tellurium: staged-route status, its properties file and the synthetic selftest. */
    public static LiteralArgumentBuilder<CommandSourceStack> developerCommands() {
        return Commands.literal("dev")
                .then(Commands.literal("status").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(status()), false);
                    return 1;
                }))
                .then(Commands.literal("status-json").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(statusJson()), false);
                    return 1;
                }))
                .then(Commands.literal("write-default-config").executes(context -> {
                    try {
                        Path file = TelluriumConfigLoader.writeDefaults(Loader.configDirectory());
                        context.getSource().sendSuccess(() -> Component.literal(
                                "Wrote default configuration to " + file + "; restart the server to apply it"), false);
                        return 1;
                    } catch (RuntimeException failure) {
                        context.getSource().sendFailure(Component.literal(
                                "Default configuration was not written: " + singleLine(detail(failure))));
                        return 0;
                    }
                }))
                .then(Commands.literal("selftest").executes(context -> {
                    var result = DiagnosticSelfTest.run();
                    String text = "Synthetic CPU/core selftest " + (result.passed() ? "PASS" : "FAIL")
                            + ": densityCompared=" + result.densityCompared() + ", materialCompared=" + result.materialCompared()
                            + ", mismatches=" + result.mismatches() + "; no Minecraft parity or GPU execution tested";
                    if (result.passed()) context.getSource().sendSuccess(() -> Component.literal(text), false);
                    else context.getSource().sendFailure(Component.literal(text));
                    return result.passed() ? 1 : 0;
                }));
    }

    /** Opt-in artifact producer; logical endpoints use the explicitly selected isolated backend. */
    public static void candidateCapture(MinecraftServer server) {
        boolean gpu = Boolean.parseBoolean(System.getProperty("tellurium.gpuCandidate.capture", "false"));
        if (!Boolean.parseBoolean(System.getProperty("tellurium.candidate.capture", "false")) && !gpu) return;
        String endpoint = System.getProperty("tellurium.candidate.endpoint", "NOISE")
                .trim().toUpperCase(java.util.Locale.ROOT);
        server.execute(() -> {
            boolean live = candidateLiveMode();
            if (endpoint.equals("SAVED") || (live && isCandidateLogicalEndpoint(endpoint))) {
                captureCandidateLogicalEndpoint(server, endpoint);
            } else if (gpu) captureGpuCandidate(server);
            else captureCandidate(server);
        });
    }

    /**
     * Runs a complete real Minecraft status chain with the selected candidate
     * route replacing only NOISE. SURFACE through FULL remain the pinned game
     * implementations, so this endpoint proves that candidate NOISE does not
     * leave hidden NoiseChunk or holder state behind.
     */
    private static void captureCandidateLogicalEndpoint(MinecraftServer server, String endpoint) {
        LogicalCandidateBackend selectedBackend = LogicalCandidateBackend.fromExternal(
                System.getProperty("tellurium.candidate.logicalBackend", "CPU_OWNED"));
        RuntimeComposition candidateRuntime = RuntimeComposition.forInlineVerifier(
                new TelluriumConfig(selectedBackend == LogicalCandidateBackend.GPU_IEEE_BITS
                        ? TelluriumConfig.Mode.GPU_REQUIRED : TelluriumConfig.Mode.CPU_ONLY,
                        256L * 1024 * 1024, 256L * 1024 * 1024, 64,
                        candidateLogicalTimeout(), true),
                new CompatibilityRegistry());
        // This verifier is invoked from the server mailbox and waits for the
        // requested chunk to reach its logical status.  Sending the isolated
        // publication back to that same mailbox deadlocks the wait. This
        // disposable verifier keeps dispatch, compute and publication inline
        // in the actual Minecraft NOISE task, including its completion timing.
        // The normal draft live path's worker/mailbox bindings are unchanged.
        candidateRuntime.bindAuthoritativeExecutor(Runnable::run);
        // The reference composition blocks the calling worldgen task. It is
        // bounded externally by the owned-process deadline, not a TPS policy.
        Executor compute = Runnable::run;
        selectedBackend.requireRuntimeCompatibility(candidateRuntime);
        QualifiedNoiseProvider provider = selectedBackend == LogicalCandidateBackend.GPU_IEEE_BITS
                ? MinecraftGpuNoiseProvider.forIsolatedEndpoint(candidateRuntime, compute)
                : MinecraftCpuNoiseProvider.forIsolatedEndpoint(candidateRuntime, compute);
        boolean installed = false;
        try {
            candidateRuntime.serverStarting();
            candidateRuntime.serverReady();
            installIsolatedCandidateProvider(provider);
            installed = true;
            String dimension = System.getProperty("tellurium.candidate.dimension", "minecraft:overworld").trim();
            List<CandidateCase> cases = candidateCases();
            for (CandidateCase candidateCase : cases) {
                Path status = candidateStatusPath(candidateCase.output());
                if (Files.exists(candidateCase.output()) || Files.exists(status)) {
                    throw new IllegalStateException("Candidate logical endpoint refuses to reuse an existing artifact or status: "
                            + candidateCase.output());
                }
            }
            for (CandidateCase candidateCase : cases) {
                ServerLevel level = findLevel(server, dimension);
                ChunkStatus requestedStatus = endpoint.equals("SAVED") ? ChunkStatus.FULL
                        : ChunkStatus.byName(endpoint.toLowerCase(java.util.Locale.ROOT));
                if (requestedStatus == null) {
                    throw new IllegalArgumentException("Unknown candidate logical endpoint: " + endpoint);
                }
                ChunkAccess full = level.getChunkSource().getChunk(candidateCase.chunkX(), candidateCase.chunkZ(),
                        requestedStatus, true);
                if (full == null || !full.getPersistedStatus().isOrAfter(requestedStatus)) {
                    throw new IllegalStateException("Candidate endpoint did not reach " + requestedStatus.getName() + " at "
                            + candidateCase.chunkX() + "," + candidateCase.chunkZ());
                }
                if (endpoint.equals("SAVED")) server.saveEverything(false, true, false);
                MinecraftLogicalSnapshotWriter.write(candidateCase.output(), level, full,
                        "CANDIDATE", "minecraft-1.21.1-neoforge-21.1.176", endpoint);
                LoggerFactory.getLogger(MOD_ID).info(
                        "Wrote candidate {} endpoint {} for {} at chunk {},{}",
                        candidateCase.output(), endpoint, level.dimension().location(),
                        candidateCase.chunkX(), candidateCase.chunkZ());
            }
            if (Boolean.parseBoolean(System.getProperty("tellurium.candidate.requireLiveNoise", "true"))
                    && provider.requestsStarted() == 0) {
                throw new IllegalStateException("Candidate logical endpoint completed without a real candidate NOISE task; choose a chunk outside the startup area");
            }
            for (CandidateCase candidateCase : cases) writeCandidateStatus(candidateCase.output(), "PASS\n");
            LoggerFactory.getLogger(MOD_ID).info("Candidate {} endpoint dispatch=INLINE_REFERENCE route={} admitted real NOISE tasks={}",
                    endpoint, provider.route(), provider.requestsStarted());
            LoggerFactory.getLogger(MOD_ID).info("Completed candidate {} endpoint cases={}", endpoint, cases.size());
        } catch (Throwable failure) {
            LoggerFactory.getLogger(MOD_ID).error("Candidate " + endpoint + " endpoint failed", failure);
            try {
                for (CandidateCase candidateCase : candidateCases()) {
                    Path status = candidateStatusPath(candidateCase.output());
                    if (!Files.exists(status)) writeCandidateStatus(candidateCase.output(), "FAIL\n" + detail(failure) + "\n");
                }
            } catch (Throwable statusFailure) {
                LoggerFactory.getLogger(MOD_ID).error("Candidate " + endpoint + " endpoint status write failed", statusFailure);
            }
        } finally {
            if (installed) {
                synchronized (StagedRoute.class) {
                    if (ISOLATED_CANDIDATE_PROVIDER == provider) ISOLATED_CANDIDATE_PROVIDER = null;
                }
            }
            closeQualifiedNoiseProvider(provider);
            try {
                candidateRuntime.serverStopping();
            } catch (RuntimeException failure) {
                LoggerFactory.getLogger(MOD_ID).error("Candidate endpoint runtime stop failed", failure);
            } finally {
                try {
                    candidateRuntime.serverStopped();
                } catch (RuntimeException failure) {
                    LoggerFactory.getLogger(MOD_ID).error("Candidate endpoint runtime close failed", failure);
                }
            }
            closeRuntimeCoordinator(candidateRuntime);
            server.saveEverything(false, true, false);
            server.halt(false);
        }
    }

    private static void closeRuntimeCoordinator(RuntimeComposition runtime) {
        try {
            runtime.closeCoordinator();
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(MOD_ID).error(
                    "Tellurium coordinator did not drain before the lifecycle deadline", failure);
        }
    }

    private static boolean isCandidateLogicalEndpoint(String endpoint) {
        return switch (endpoint) {
            case "NOISE", "SURFACE", "CARVERS", "FEATURES", "INITIALIZE_LIGHT", "LIGHT", "SPAWN", "FULL" -> true;
            default -> false;
        };
    }

    private static void captureCandidate(MinecraftServer server) {
        ExecutorService compute = null;
        try {
            String dimension = System.getProperty("tellurium.candidate.dimension", "minecraft:overworld").trim();
            MinecraftCpuCandidate candidate = new MinecraftCpuCandidate();
            List<CandidateCase> cases = candidateCases();
            int workers = candidateWorkerCount();
            compute = Executors.newFixedThreadPool(workers, candidateThreadFactory());
            var pending = new ArrayDeque<CpuCandidateWork>(workers);
            for (CandidateCase candidateCase : cases) {
                ServerLevel level = findLevel(server, dimension);
                // Minecraft capture remains on the authoritative server
                // thread.  Pure immutable materialization is pipelined on a
                // small bounded pool so evidence runs do not serialize the
                // expensive captured graph evaluator with the loader.
                MinecraftCpuCandidate.Inputs inputs = candidate.capture(level, candidateCase.chunkX(), candidateCase.chunkZ());
                pending.addLast(new CpuCandidateWork(candidateCase,
                        compute.submit(() -> candidate.generate(inputs))));
                if (pending.size() >= workers) writeCpuCandidateWork(pending.removeFirst(), level);
            }
            while (!pending.isEmpty()) writeCpuCandidateWork(pending.removeFirst(), findLevel(server, dimension));
            LoggerFactory.getLogger(MOD_ID).info("Completed CPU candidate capture batch cases={}", cases.size());
        } catch (Throwable failure) {
            LoggerFactory.getLogger(MOD_ID).error("CPU candidate capture failed", failure);
            writeFailureStatusesForConfiguredCases(failure);
        } finally {
            if (compute != null) {
                compute.shutdownNow();
                try {
                    if (!compute.awaitTermination(10, TimeUnit.SECONDS)) {
                        LoggerFactory.getLogger(MOD_ID).error("CPU candidate compute workers did not stop before server shutdown");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    LoggerFactory.getLogger(MOD_ID).error("Interrupted while stopping CPU candidate compute workers");
                }
            }
            server.saveEverything(false, true, false);
            server.halt(false);
        }
    }

    private static void writeCpuCandidateWork(CpuCandidateWork work, ServerLevel level) throws Exception {
        MinecraftCpuCandidate.Capture capture = work.result().get();
        writeCandidateResult(work.request().output(), capture.result());
        writeCandidateStatus(work.request().output(), "PASS\n");
        LoggerFactory.getLogger(MOD_ID).info(
                "Wrote CPU candidate result {} for {} at chunk {},{} (snapshot={}, checksum={})",
                work.request().output(), level.dimension().location(), work.request().chunkX(), work.request().chunkZ(),
                capture.snapshot().fingerprint(), capture.result().logicalChecksum());
    }

    private static int candidateWorkerCount() {
        int fallback = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors()));
        int workers = integerProperty("tellurium.candidate.cpuWorkers", fallback);
        if (workers <= 0 || workers > 8) {
            throw new IllegalArgumentException("tellurium.candidate.cpuWorkers must be between 1 and 8");
        }
        return workers;
    }

    private static int prototypeCpuWorkerCount() {
        int fallback = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors()));
        int workers = integerProperty("tellurium.prototype.cpuWorkers", fallback);
        if (workers <= 0 || workers > 8) {
            throw new IllegalArgumentException("tellurium.prototype.cpuWorkers must be between 1 and 8");
        }
        return workers;
    }

    private static ThreadFactory candidateThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "tellurium-candidate-cpu");
            thread.setDaemon(false);
            return thread;
        };
    }

    private static void captureGpuCandidate(MinecraftServer server) {
        String dimension = System.getProperty("tellurium.candidate.dimension", "minecraft:overworld").trim();
        List<GpuCandidateCase> captured;
        try {
            List<CandidateCase> cases = candidateCases();
            MinecraftCpuCandidate cpuCandidate = new MinecraftCpuCandidate();
            captured = new ArrayList<>(cases.size());
            for (CandidateCase candidateCase : cases) {
                ServerLevel level = findLevel(server, dimension);
                // Capture is the only part that touches Minecraft objects. The
                // resulting Inputs record contains immutable snapshots and
                // serialized prerequisite metadata, so shader compilation,
                // Vulkan dispatch and result encoding can leave the server
                // thread without racing holder state.
                captured.add(new GpuCandidateCase(candidateCase,
                        cpuCandidate.capture(level, candidateCase.chunkX(), candidateCase.chunkZ())));
            }
            for (GpuCandidateCase candidateCase : captured) {
                Path output = candidateCase.request().output();
                Path receipt = output.resolveSibling(output.getFileName() + ".gpu-receipt.json");
                Path status = candidateStatusPath(output);
                if (Files.exists(output) || Files.exists(receipt) || Files.exists(status)) {
                    throw new IllegalStateException("GPU candidate refuses to reuse an existing artifact, receipt or status: "
                            + output);
                }
            }
        } catch (Throwable failure) {
            LoggerFactory.getLogger(MOD_ID).error("GPU candidate capture failed during Minecraft input capture", failure);
            writeFailureStatusesForConfiguredCases(failure);
            server.execute(() -> finishCandidateServer(server));
            return;
        }

        Thread worker = new Thread(() -> {
            try {
                MinecraftGpuCandidate candidate = new MinecraftGpuCandidate();
                try (VulkanWorldgenExecutor executor = new VulkanWorldgenExecutor()) {
                    executor.resetTelemetry();
                    for (GpuCandidateCase candidateCase : captured) {
                        if (Boolean.getBoolean("tellurium.gpuCandidate.deviceOnly")) {
                            var generation = candidate.generateDevice(candidateCase.inputs(), executor);
                            writeCandidateResult(candidateCase.request().output(), generation.gpuResult());
                            Path receipt = candidateCase.request().output().resolveSibling(
                                    candidateCase.request().output().getFileName() + ".gpu-receipt.json");
                            writeNewText(receipt, gpuDeviceReceiptJson(generation, candidateCase.inputs(),
                                    executor.telemetry(), executor.storageTelemetry(), executor.compilationTelemetry(), executor.spirvCacheTelemetry()));
                            writeCandidateStatus(candidateCase.request().output(), "DEVICE_EXECUTION_ONLY\n");
                            LoggerFactory.getLogger(MOD_ID).info(
                                    "Wrote device-only GPU artifact {} (GPU rows={}, no CPU comparison, no qualification)",
                                    candidateCase.request().output(), generation.logicalGpuElements());
                            continue;
                        }
                        MinecraftGpuCandidate.Replay replay = candidate.generate(candidateCase.inputs(), executor);
                        writeCandidateResult(candidateCase.request().output(), replay.gpuResult());
                        Path receipt = candidateCase.request().output().resolveSibling(
                                candidateCase.request().output().getFileName() + ".gpu-receipt.json");
                        writeNewText(receipt, gpuReceiptJson(replay, executor.telemetry(), executor.storageTelemetry(),
                                executor.compilationTelemetry(), executor.spirvCacheTelemetry()));
                        writeCandidateStatus(candidateCase.request().output(), "PASS\n");
                        LoggerFactory.getLogger(MOD_ID).info(
                                "Wrote isolated GPU candidate result {} and receipt {} for {} at chunk {},{} (blocksCompared={}, device={}, shader={}, spirv={}, checksum={})",
                                candidateCase.request().output(), receipt, replay.cpuCapture().snapshot().dimension(),
                                candidateCase.request().chunkX(), candidateCase.request().chunkZ(), replay.comparedBlocks(),
                                replay.device().name(), replay.shaderHash(), replay.spirvHash(), replay.gpuResult().logicalChecksum());
                    }
                }
                LoggerFactory.getLogger(MOD_ID).info("Completed GPU candidate capture batch cases={}", captured.size());
            } catch (Throwable failure) {
                LoggerFactory.getLogger(MOD_ID).error("GPU candidate capture failed", failure);
                writeCandidateFailureStatuses(captured.stream().map(GpuCandidateCase::request).toList(), failure);
            } finally {
                server.execute(() -> finishCandidateServer(server));
            }
        }, "tellurium-gpu-candidate");
        worker.setDaemon(false);
        worker.start();
    }

    private static void finishCandidateServer(MinecraftServer server) {
        try {
            server.saveEverything(false, true, false);
        } finally {
            server.halt(false);
        }
    }

    private static void writeCandidateResult(Path path, dev.tellurium.material.chunk.ChunkNoiseResult result)
            throws java.io.IOException {
        Path normalized = path.toAbsolutePath().normalize();
        writeNewBytes(normalized, ChunkResultCodec.encode(result));
    }

    private static Path candidateStatusPath(Path output) {
        Path normalized = output.toAbsolutePath().normalize();
        return normalized.resolveSibling(normalized.getFileName() + ".candidate-status");
    }

    private static void writeCandidateStatus(Path output, String content) throws java.io.IOException {
        Path status = candidateStatusPath(output);
        writeNewText(status, content);
    }

    /** Publish a new artifact without exposing a partially written final path. */
    private static void writeNewBytes(Path path, byte[] content) throws java.io.IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        Path normalized = path.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) throw new java.io.IOException("Artifact has no parent directory: " + normalized);
        Files.createDirectories(parent);
        if (Files.exists(normalized, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new java.io.IOException("Refusing to overwrite candidate artifact: " + normalized);
        }
        Path temporary = Files.createTempFile(parent, normalized.getFileName().toString() + ".", ".tmp");
        try {
            Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, normalized, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, normalized);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeNewText(Path path, String content) throws java.io.IOException {
        writeNewBytes(path, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Leaves a terminal failure marker without overwriting a prior artifact or status. */
    private static void writeCandidateFailureStatuses(List<CandidateCase> cases, Throwable failure) {
        if (cases == null || cases.isEmpty()) return;
        String content = "FAIL\n" + detail(failure) + "\n";
        for (CandidateCase candidateCase : cases) {
            Path status = candidateStatusPath(candidateCase.output());
            if (Files.exists(status)) continue;
            try {
                writeCandidateStatus(candidateCase.output(), content);
            } catch (Throwable statusFailure) {
                LoggerFactory.getLogger(MOD_ID).error(
                        "Candidate failure status could not be written for {}", candidateCase.output(), statusFailure);
            }
        }
    }

    private static void writeFailureStatusesForConfiguredCases(Throwable failure) {
        try {
            writeCandidateFailureStatuses(candidateCases(), failure);
        } catch (Throwable casesFailure) {
            LoggerFactory.getLogger(MOD_ID).error(
                    "Candidate failure status could not resolve the configured case manifest", casesFailure);
        }
    }

    /** Reads the optional bounded batch manifest used by the isolated replay scripts. */
    private static List<CandidateCase> candidateCases() throws java.io.IOException {
        String caseFile = System.getProperty("tellurium.candidate.caseFile", "").trim();
        if (caseFile.isEmpty()) {
            String output = System.getProperty("tellurium.candidate.output", "").trim();
            if (output.isEmpty()) throw new IllegalArgumentException(
                    "Missing tellurium.candidate.output or tellurium.candidate.caseFile");
            return List.of(new CandidateCase(integerProperty("tellurium.candidate.chunkX", 0),
                    integerProperty("tellurium.candidate.chunkZ", 0),
                    Path.of(output).toAbsolutePath().normalize()));
        }
        Path manifest = Path.of(caseFile).toAbsolutePath().normalize();
        if (!Files.isRegularFile(manifest, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Candidate case manifest is not a regular file: " + manifest);
        }
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        var result = new ArrayList<CandidateCase>();
        Set<String> coordinates = new HashSet<>();
        Set<Path> outputs = new HashSet<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 3 || fields[2].isBlank()) {
                throw new IllegalArgumentException("Malformed candidate case manifest line " + (index + 1));
            }
            int chunkX;
            int chunkZ;
            try {
                chunkX = Integer.parseInt(fields[0]);
                chunkZ = Integer.parseInt(fields[1]);
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("Invalid candidate case coordinates at line " + (index + 1), failure);
            }
            if (!coordinates.add(chunkX + "," + chunkZ)) {
                throw new IllegalArgumentException("Duplicate candidate case coordinates at line " + (index + 1));
            }
            Path output = Path.of(fields[2]).toAbsolutePath().normalize();
            if (!outputs.add(output)) {
                throw new IllegalArgumentException("Duplicate candidate case output at line " + (index + 1));
            }
            result.add(new CandidateCase(chunkX, chunkZ, output));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Candidate case manifest is empty: " + manifest);
        return List.copyOf(result);
    }

    private static String storageTelemetryJson(VulkanWorldgenExecutor.StorageTelemetry storage) {
        return "{\"bufferAllocations\":" + storage.bufferAllocations()
                + ",\"reusedDispatches\":" + storage.reusedDispatches()
                + ",\"retainedBytes\":" + storage.retainedBytes() + "}";
    }

    private static String spirvCacheTelemetryJson(dev.tellurium.runtime.vulkan.production.SpirvModuleCache.Telemetry cache) {
        return "{\"scope\":\"executor_lifetime\",\"retainedEntries\":" + cache.retainedEntries()
                + ",\"retainedBytes\":" + cache.retainedBytes() + ",\"maximumEntries\":" + cache.maximumEntries()
                + ",\"maximumBytes\":" + cache.maximumBytes() + ",\"evictions\":" + cache.evictions()
                + ",\"oversizedRejections\":" + cache.oversizedRejections() + "}";
    }

    private static String compilationTelemetryJson(VulkanWorldgenExecutor.CompilationTelemetry compilation) {
        return "{\"scope\":\"executor_lifetime\",\"shadercCacheHits\":" + compilation.shadercCacheHits()
                + ",\"shadercCompilations\":" + compilation.shadercCompilations()
                + ",\"shadercNanos\":" + compilation.shadercNanos()
                + ",\"pipelineCacheHits\":" + compilation.pipelineCacheHits()
                + ",\"pipelineCreations\":" + compilation.pipelineCreations()
                + ",\"pipelineNanos\":" + compilation.pipelineNanos() + "}";
    }

    private static String gpuDeviceReceiptJson(MinecraftGpuCandidate.DeviceGeneration generation,
                                               MinecraftCpuCandidate.Inputs inputs,
                                               VulkanWorldgenExecutor.Telemetry telemetry,
                                               VulkanWorldgenExecutor.StorageTelemetry storage,
                                               VulkanWorldgenExecutor.CompilationTelemetry compilation,
                                               dev.tellurium.runtime.vulkan.production.SpirvModuleCache.Telemetry cache) {
        return "{\n"
                + "  \"schemaVersion\":1,\n"
                + "  \"kind\":\"tellurium_gpu_device_execution\",\n"
                + "  \"status\":\"DEVICE_EXECUTION_ONLY\",\n"
                + "  \"route\":" + jsonQuote(generation.numericProfile().name()) + ",\n"
                + "  \"resultAbi\":" + jsonQuote(MinecraftGpuNoiseProvider.RESULT_ABI) + ",\n"
                + "  \"compilerVersion\":" + jsonQuote(MinecraftGpuNoiseProvider.COMPILER_VERSION) + ",\n"
                + "  \"dimension\":" + jsonQuote(inputs.snapshot().dimension()) + ",\n"
                + "  \"chunkX\":" + inputs.chunkX() + ",\n"
                + "  \"chunkZ\":" + inputs.chunkZ() + ",\n"
                + "  \"snapshotFingerprint\":" + jsonQuote(inputs.snapshot().fingerprint()) + ",\n"
                + "  \"resultChecksum\":" + jsonQuote(generation.gpuResult().logicalChecksum()) + ",\n"
                + "  \"logicalGpuElements\":" + generation.logicalGpuElements() + ",\n"
                + "  \"storageBlocks\":" + generation.gpuResult().denseStates().length + ",\n"
                + "  \"comparedBlocks\":0,\n"
                + "  \"mismatches\":null,\n"
                + "  \"shaderHash\":" + jsonQuote(generation.shaderHash()) + ",\n"
                + "  \"spirvHash\":" + jsonQuote(generation.spirvHash()) + ",\n"
                + "  \"device\":{\"name\":" + jsonQuote(generation.device().name()) + "},\n"
                + "  \"spirvCacheTelemetry\":" + spirvCacheTelemetryJson(cache) + ",\n"
                + "  \"storageTelemetry\":" + storageTelemetryJson(storage) + ",\n"
                + "  \"compilationTelemetry\":" + compilationTelemetryJson(compilation) + ",\n"
                + "  \"telemetryScope\":\"executor_lifetime_since_batch_start\",\n"
                + "  \"telemetry\":{\"dispatches\":" + telemetry.dispatches()
                + ",\"elements\":" + telemetry.elements()
                + ",\"inputBytes\":" + telemetry.inputBytes()
                + ",\"outputBytes\":" + telemetry.outputBytes()
                + ",\"elapsedNanos\":" + telemetry.elapsedNanos()
                + ",\"maxElapsedNanos\":" + telemetry.maxElapsedNanos() + "}\n"
                + "}\n";
    }

    private static String gpuReceiptJson(MinecraftGpuCandidate.Replay replay,
                                         VulkanWorldgenExecutor.Telemetry telemetry,
                                         VulkanWorldgenExecutor.StorageTelemetry storage,
                                         VulkanWorldgenExecutor.CompilationTelemetry compilation,
                                         dev.tellurium.runtime.vulkan.production.SpirvModuleCache.Telemetry cache) {
        var device = replay.device();
        String dimension = replay.cpuCapture().snapshot().dimension();
        int chunkX = replay.cpuCapture().result().header().chunkX();
        int chunkZ = replay.cpuCapture().result().header().chunkZ();
        return "{\n"
                + "  \"schemaVersion\":1,\n"
                + "  \"kind\":\"tellurium_gpu_candidate_receipt\",\n"
                + "  \"status\":" + jsonQuote(replay.numericProfile() == dev.tellurium.semantic.program.NumericProfile.GPU_IEEE_BITS
                        ? "PASS" : "DRAFT_PARITY_PASS") + ",\n"
                + "  \"route\":" + jsonQuote(replay.numericProfile().name()) + ",\n"
                + "  \"resultAbi\":" + jsonQuote(MinecraftGpuNoiseProvider.RESULT_ABI) + ",\n"
                + "  \"compilerVersion\":" + jsonQuote(MinecraftGpuNoiseProvider.COMPILER_VERSION) + ",\n"
                + "  \"dimension\":" + jsonQuote(dimension) + ",\n"
                + "  \"chunkX\":" + chunkX + ",\n"
                + "  \"chunkZ\":" + chunkZ + ",\n"
                + "  \"snapshotFingerprint\":" + jsonQuote(replay.cpuCapture().snapshot().fingerprint()) + ",\n"
                + "  \"resultChecksum\":" + jsonQuote(replay.gpuResult().logicalChecksum()) + ",\n"
                + "  \"comparedBlocks\":" + replay.comparedBlocks() + ",\n"
                + "  \"mismatches\":" + replay.mismatches() + ",\n"
                + "  \"shaderHash\":" + jsonQuote(replay.shaderHash()) + ",\n"
                + "  \"spirvHash\":" + jsonQuote(replay.spirvHash()) + ",\n"
                + "  \"spirvCacheTelemetry\":" + spirvCacheTelemetryJson(cache) + ",\n"
                + "  \"storageTelemetry\":" + storageTelemetryJson(storage) + ",\n"
                + "  \"compilationTelemetry\":" + compilationTelemetryJson(compilation) + ",\n"
                + "  \"telemetryScope\":\"executor_lifetime_since_batch_start\",\n"
                + "  \"telemetry\":{\n"
                + "    \"dispatches\":" + telemetry.dispatches() + ",\n"
                + "    \"elements\":" + telemetry.elements() + ",\n"
                + "    \"inputBytes\":" + telemetry.inputBytes() + ",\n"
                + "    \"outputBytes\":" + telemetry.outputBytes() + ",\n"
                + "    \"elapsedNanos\":" + telemetry.elapsedNanos() + ",\n"
                + "    \"maxElapsedNanos\":" + telemetry.maxElapsedNanos() + "\n"
                + "  },\n"
                + "  \"device\":{\n"
                + "    \"name\":" + jsonQuote(device.name()) + ",\n"
                + "    \"apiVersion\":" + jsonQuote(Integer.toUnsignedString(device.apiVersion())) + ",\n"
                + "    \"driverVersion\":" + jsonQuote(Integer.toUnsignedString(device.driverVersion())) + ",\n"
                + "    \"deviceType\":" + device.deviceType() + ",\n"
                + "    \"computeQueueFamily\":" + device.computeQueueFamily() + ",\n"
                + "    \"float64\":" + device.float64() + ",\n"
                + "    \"preserveSignedZeroInfNan64\":" + device.preserveSignedZeroInfNan64() + ",\n"
                + "    \"preserveDenorm64\":" + device.preserveDenorm64() + ",\n"
                + "    \"roundingRte64\":" + device.roundingRte64() + "\n"
                + "  }\n"
                + "}\n";
    }

    private static String jsonQuote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + "\"";
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimension) {
        if (dimension.isEmpty()) throw new IllegalArgumentException("Candidate dimension is blank");
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) return level;
        }
        throw new IllegalArgumentException("Candidate dimension is not loaded: " + dimension);
    }

    private static int integerProperty(String name, int fallback) {
        try {
            return Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid integer property " + name, failure);
        }
    }

    private static Duration candidateLogicalTimeout() {
        String value = System.getProperty("tellurium.candidate.timeoutMillis",
                Long.toString(DEFAULT_CANDIDATE_LOGICAL_TIMEOUT_MILLIS)).trim();
        try {
            long millis = Long.parseLong(value);
            if (millis <= 0) throw new IllegalArgumentException(
                    "tellurium.candidate.timeoutMillis must be positive");
            return Duration.ofMillis(millis);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "Invalid tellurium.candidate.timeoutMillis", failure);
        }
    }

    private static boolean candidateLiveMode() {
        return candidateLiveRequested()
                || Boolean.parseBoolean(System.getProperty("tellurium.prototype.cpuLive", "false"));
    }

    private static boolean candidateLiveRequested() {
        return Boolean.parseBoolean(System.getProperty("tellurium.candidate.live", "false"));
    }

    private static boolean draftCpuLiveMode() {
        return Boolean.parseBoolean(System.getProperty("tellurium.prototype.cpuLive", "false"));
    }

    private static String detail(Throwable failure) {
        return failure.getClass().getSimpleName() + ": "
                + (failure.getMessage() == null ? "no detail" : failure.getMessage());
    }

    private static String singleLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ').replace(';', ',');
    }
}
