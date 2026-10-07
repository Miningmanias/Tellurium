// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import dev.tellurium.material.chunk.ChunkNoiseResult;
import java.util.Objects;
import java.util.function.BiFunction;

/** Narrow hook seam. Eligibility is checked before capture and unsupported contexts fall back explicitly. */
public final class GenerationInterceptor {
    @FunctionalInterface
    public interface RecoverySafety {
        boolean targetRestored(Object chunk, MinecraftOwnershipToken ownership);
    }
    public record Decision(boolean intercepted, String route, String reason, ChunkNoiseResult result) {
        public Decision {
            if (route == null || route.isBlank() || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Interception decision requires route/reason");
            }
            if (intercepted != (result != null)) {
                throw new IllegalArgumentException("Intercepted decisions must carry exactly one result");
            }
        }
    }
    private final RuntimeComposition runtime;
    private final RecoverySafety recoverySafety;
    public GenerationInterceptor(RuntimeComposition runtime) { this(runtime, (chunk, ownership) -> false); }
    public GenerationInterceptor(RuntimeComposition runtime, RecoverySafety recoverySafety) {
        this.runtime = Objects.requireNonNull(runtime);
        this.recoverySafety = Objects.requireNonNull(recoverySafety, "recoverySafety");
    }
    public Decision intercept(String context, Object chunk, MinecraftOwnershipToken ownership, BiFunction<Object, MinecraftOwnershipToken, ChunkNoiseResult> qualifiedGenerator) {
        var compatibility = runtime.compatibility().decision(context);
        if (!runtime.config().enableQualifiedHook() || !compatibility.eligible()) {
            return unavailable("Qualified context is not eligible: " + compatibility.reason());
        }
        if (ownership == null) return unavailable("Minecraft ownership token is missing");
        if (chunk == null) return unavailable("Minecraft chunk is missing");
        if (!runtime.canRunQualifiedGeneration(ownership)) {
            return unavailable("Worldgen runtime is not running or the captured world/device generation is stale");
        }
        if (qualifiedGenerator == null) return unavailable("Qualified generator is missing");
        try {
            ChunkNoiseResult result = qualifiedGenerator.apply(chunk, ownership);
            if (result == null) return unavailable("Qualified generator returned no result");
            if (!runtime.isCurrent(ownership)) {
                return unavailable("Worldgen runtime became stale while the qualified result was being generated");
            }
            return new Decision(true, compatibility.route(), "qualified result ready", result);
        } catch (Throwable failure) {
            String detail = detail(failure);
            if (runtime.config().mode() == dev.tellurium.neoforge.config.TelluriumConfig.Mode.GPU_REQUIRED) {
                return new Decision(false, "GPU_REQUIRED_FAILED", "Qualified route failed; recovery is forbidden in GPU_REQUIRED: " + detail, null);
            }
            try {
                if (recoverySafety.targetRestored(chunk, ownership)) {
                return new Decision(false, "CPU_RECOVERY", "Qualified route failed after verified target restoration: " + detail, null);
                }
            } catch (Throwable restorationFailure) {
                return new Decision(false, "QUALIFIED_ROUTE_FAILED_UNSAFE",
                        "Qualified route failed and restoration proof threw; refusing original recovery over an unsafe target: "
                                + detail + "; restoration proof: " + detail(restorationFailure), null);
            }
            return new Decision(false, "QUALIFIED_ROUTE_FAILED_UNSAFE",
                    "Qualified route failed and target restoration was not verified; refusing original recovery over an unsafe target: " + detail, null);
        }
    }

    private static String detail(Throwable failure) {
        return failure.getClass().getSimpleName() + ": "
                + (failure.getMessage() == null ? "no detail" : failure.getMessage());
    }

    private Decision unavailable(String reason) {
        if (runtime.config().mode() == dev.tellurium.neoforge.config.TelluriumConfig.Mode.GPU_REQUIRED) {
            return new Decision(false, "GPU_REQUIRED_FAILED", reason + "; no CPU fallback is permitted", null);
        }
        return new Decision(false, "CPU_ORIGINAL_PLANNED", reason, null);
    }
}
