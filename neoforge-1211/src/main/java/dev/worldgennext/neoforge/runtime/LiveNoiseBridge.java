// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import dev.worldgennext.engine.worldgen.CommitCoordinator;
import dev.worldgennext.engine.worldgen.CommitReceipt;
import dev.worldgennext.engine.worldgen.CommitToken;
import dev.worldgennext.material.chunk.ChunkNoiseResult;
import dev.worldgennext.semantic.execution.ExecutionReceipt;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Composes one qualified NOISE attempt without owning Minecraft's holder
 * scheduler.  The loader calls this seam only after it has supplied a valid
 * ownership token and a registered, independently qualified context.
 *
 * <p>Capture runs before the compute callback and compute runs without the
 * bridge monitor.  The bridge then rechecks the runtime generation while
 * holding the runtime monitor before handing the result to the authoritative
 * commit coordinator.  The actual chunk committer must still perform its own
 * holder/status/revision check; the two checks protect different identities.</p>
 *
 * <p>No mixin or event registration is performed here.  The product remains
 * disabled by default until the independent Minecraft matrix qualifies the
 * selected route.</p>
 */
public final class LiveNoiseBridge {
    public enum State {
        ORIGINAL,
        CAPTURED,
        COMPUTING,
        STALE,
        CANCELLED,
        COMMITTING,
        COMMITTED,
        COMMITTED_DOWNSTREAM_FAILED,
        FAILED,
        UNSAFE
    }

    @FunctionalInterface
    public interface QualifiedGenerator {
        Produced generate(Object capturedInputs);
    }

    /** Result plus the backend-issued provenance required by CommitCoordinator. */
    public record Produced(ChunkNoiseResult result, ExecutionReceipt execution) {
        public Produced {
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(execution, "execution");
        }
    }

    public record Decision(boolean intercepted, State state, String route, String reason,
                           CommitReceipt commit) {
        public Decision {
            Objects.requireNonNull(state, "state");
            if (route == null || route.isBlank() || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Live decision requires route and reason");
            }
            if (intercepted != (state == State.COMMITTED || state == State.COMMITTED_DOWNSTREAM_FAILED)) {
                throw new IllegalArgumentException("Only committed states may report interception");
            }
            if (state == State.COMMITTED && (commit == null || !commit.committed())) {
                throw new IllegalArgumentException("Committed state requires a committed receipt");
            }
            if (state != State.COMMITTED && state != State.COMMITTED_DOWNSTREAM_FAILED && commit != null) {
                throw new IllegalArgumentException("Uncommitted state cannot carry a commit receipt");
            }
        }
    }

    private final RuntimeComposition runtime;
    private final GenerationInterceptor.RecoverySafety recoverySafety;

    public LiveNoiseBridge(RuntimeComposition runtime) {
        this(runtime, (chunk, ownership) -> false);
    }

    public LiveNoiseBridge(RuntimeComposition runtime, GenerationInterceptor.RecoverySafety recoverySafety) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.recoverySafety = Objects.requireNonNull(recoverySafety, "recoverySafety");
    }

    /**
     * Runs one synchronous bridge attempt.  A loader hook may wrap this call
     * in its own future; this class deliberately does not block Minecraft's
     * holder executor while waiting for a backend future.
     */
    public Decision interceptAndCommit(String context, Object chunk,
                                       MinecraftOwnershipToken ownership,
                                       MinecraftStageAdapter stages,
                                       CommitCoordinator commits,
                                       QualifiedGenerator generator) {
        return interceptAndCommit(context, chunk, ownership, stages, commits, generator, () -> false);
    }

    /**
     * Cancellation-aware form used by asynchronous providers.  Cancellation
     * is checked after capture and generation, before any authoritative
     * commit can begin.  The final check is inside the runtime monitor so a
     * cancelled result cannot pass the lifecycle check while waiting for the
     * commit boundary.
     */
    public Decision interceptAndCommit(String context, Object chunk,
                                       MinecraftOwnershipToken ownership,
                                       MinecraftStageAdapter stages,
                                       CommitCoordinator commits,
                                       QualifiedGenerator generator,
                                       BooleanSupplier cancelled) {
        Objects.requireNonNull(cancelled, "cancelled");
        var compatibility = runtime.compatibility().decision(context);
        if (!runtime.config().enableQualifiedHook() || !compatibility.eligible()) {
            return unavailable("Qualified context is not eligible: " + compatibility.reason());
        }
        if (chunk == null) return unavailable("Minecraft chunk is missing");
        if (ownership == null) return unavailable("Minecraft ownership token is missing");
        if (ownership.ownershipToken().isBlank()) {
            return unavailable("Minecraft ownership token has no opaque holder identity");
        }
        if (!runtime.canRunQualifiedGeneration(ownership)) {
            return unavailable("Worldgen runtime is not running or the captured world/device generation is stale");
        }
        if (stages == null) return unavailable("Minecraft stage adapter is missing");
        if (commits == null) return unavailable("Commit coordinator is missing");
        if (generator == null) return unavailable("Qualified generator is missing");
        if (cancelled.getAsBoolean()) return cancelled(compatibility.route());

        Object captured;
        try {
            captured = stages.captureInputs(chunk, ownership);
            if (captured == null) return failure(chunk, ownership, "qualified input capture returned no inputs");
            if (cancelled.getAsBoolean()) return cancelled(compatibility.route());
        } catch (Throwable failure) {
            return failure(chunk, ownership, detail(failure));
        }

        try {
            Produced produced = generator.generate(captured);
            if (produced == null) return failure(chunk, ownership, "qualified generator returned no result");
            if (cancelled.getAsBoolean()) return cancelled(compatibility.route());

            // Compute is intentionally outside the runtime monitor.  A reload
            // or device transition can therefore invalidate this attempt while
            // a backend is working, without blocking lifecycle listeners.
            if (!runtime.isCurrent(ownership)) {
                return stale("captured world/device generation became stale before commit");
            }

            CommitReceipt published;
            synchronized (runtime) {
                if (cancelled.getAsBoolean()) return cancelled(compatibility.route());
                if (!runtime.isCurrent(ownership)) {
                    return stale("captured world/device generation became stale before commit");
                }
                CommitToken token = new CommitToken(ownership.ownershipToken(), ownership.context(),
                        ownership.worldEpoch(), ownership.revision());
                published = commits.publish(token, produced.result(), produced.execution());
            }
            if (published == null || !published.committed()) {
                String reason = published == null ? "commit coordinator returned no decision" : published.detail();
                if (isStale(reason)) return stale(reason);
                return failure(chunk, ownership, reason);
            }

            try {
                stages.continueOriginalStages(chunk, produced.result());
            } catch (Throwable downstreamFailure) {
                // The chunk is already authoritatively published.  Never run
                // original NOISE recovery over it; surface the downstream
                // failure as a distinct terminal state for the caller.
                return new Decision(true, State.COMMITTED_DOWNSTREAM_FAILED,
                        "QUALIFIED_COMMITTED_DOWNSTREAM_FAILED",
                        "qualified NOISE committed but original downstream continuation failed: "
                                + detail(downstreamFailure), published);
            }
            return new Decision(true, State.COMMITTED, compatibility.route(),
                    "qualified NOISE result committed", published);
        } catch (Throwable failure) {
            return failure(chunk, ownership, detail(failure));
        }
    }

    private Decision cancelled(String route) {
        return new Decision(false, State.CANCELLED, route,
                "qualified NOISE attempt was cancelled", null);
    }

    private Decision unavailable(String reason) {
        if (runtime.config().mode() == dev.worldgennext.neoforge.config.WorldgenNextConfig.Mode.GPU_REQUIRED) {
            return new Decision(false, State.FAILED, "GPU_REQUIRED_FAILED",
                    reason + "; no CPU fallback is permitted", null);
        }
        return new Decision(false, State.ORIGINAL, "CPU_ORIGINAL_PLANNED", reason, null);
    }

    private Decision stale(String reason) {
        if (runtime.config().mode() == dev.worldgennext.neoforge.config.WorldgenNextConfig.Mode.GPU_REQUIRED) {
            return new Decision(false, State.STALE, "GPU_REQUIRED_FAILED",
                    reason + "; no CPU fallback is permitted", null);
        }
        return new Decision(false, State.STALE, "CPU_ORIGINAL_PLANNED", reason, null);
    }

    private Decision failure(Object chunk, MinecraftOwnershipToken ownership, String reason) {
        if (runtime.config().mode() == dev.worldgennext.neoforge.config.WorldgenNextConfig.Mode.GPU_REQUIRED) {
            return new Decision(false, State.FAILED, "GPU_REQUIRED_FAILED",
                    "Qualified route failed; recovery is forbidden in GPU_REQUIRED: " + reason, null);
        }
        try {
            if (recoverySafety.targetRestored(chunk, ownership)) {
                return new Decision(false, State.FAILED, "CPU_RECOVERY",
                        "Qualified route failed after verified target restoration: " + reason, null);
            }
        } catch (Throwable restorationFailure) {
            reason += "; restoration proof failed: " + detail(restorationFailure);
        }
        return new Decision(false, State.UNSAFE, "QUALIFIED_ROUTE_FAILED_UNSAFE",
                "Qualified route failed and target restoration was not verified; refusing original recovery over an unsafe target: "
                        + reason, null);
    }

    private static String detail(Throwable failure) {
        return failure.getClass().getSimpleName() + ": "
                + (failure.getMessage() == null ? "no detail" : failure.getMessage());
    }

    private static boolean isStale(String detail) {
        if (detail == null) return false;
        String normalized = detail.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("stale") || normalized.contains("late")
                || normalized.contains("expired") || normalized.contains("revision changed")
                || normalized.contains("ownership changed") || normalized.contains("status changed")
                || normalized.contains("world epoch") || normalized.contains("device generation");
    }
}
