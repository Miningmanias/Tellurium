// SPDX-License-Identifier: MIT
package dev.tellurium.frontend.mc1211;

import dev.tellurium.semantic.WorldgenIdentity;
import dev.tellurium.semantic.program.WorldgenProgram;
import dev.tellurium.semantic.snapshot.WorldgenSnapshot;
import java.util.Objects;

/** Truthful capability gate: synthetic replay is not Minecraft lowering. */
public final class Minecraft1211Frontend implements MinecraftDensityLowerer {
    public static final String MINECRAFT_VERSION = "1.21.1";

    @Override
    public LoweringResult lower(WorldgenIdentity identity, Object source) {
        Objects.requireNonNull(identity, "identity");
        if (source == null) return new LoweringResult.Unsupported(identity, LoweringResult.Reason.MISSING_SOURCE,
                "null", "Provide the original Minecraft 1.21.1 worldgen source; generation interception is unavailable in v0.1.");
        if (source instanceof WorldgenSnapshot snapshot) {
            if (!snapshot.router().complete()) {
                return new LoweringResult.Unsupported(identity, LoweringResult.Reason.MINECRAFT_FRONTEND_NOT_IMPLEMENTED,
                        WorldgenSnapshot.class.getName(), "Typed Minecraft capture is missing one or more of the 15 NoiseRouter roots.");
            }
            try {
                WorldgenProgram program = WorldgenProgram.builder().roots(snapshot.router().roots()).build();
                return lowerTyped(identity, snapshot, program);
            } catch (RuntimeException failure) {
                return new LoweringResult.Unsupported(identity, LoweringResult.Reason.MINECRAFT_FRONTEND_NOT_IMPLEMENTED,
                        WorldgenSnapshot.class.getName(), "Typed Minecraft capture could not build a validated program: "
                                + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            }
        }
        return new LoweringResult.Unsupported(identity, LoweringResult.Reason.MINECRAFT_FRONTEND_NOT_IMPLEMENTED,
                source.getClass().getName(), "Minecraft noise, aquifer, ore and material lowering require an independent same-stack oracle; v0.1 only runs synthetic expressions.");
    }

    public boolean canInterceptGeneration() { return false; }

    /** Typed capture seam used by the v0.2 composition root; the live hook remains gated separately. */
    public LoweringResult lowerTyped(WorldgenIdentity identity, dev.tellurium.semantic.snapshot.WorldgenSnapshot snapshot,
                                     dev.tellurium.semantic.program.WorldgenProgram program) {
        Objects.requireNonNull(identity, "identity"); Objects.requireNonNull(snapshot, "snapshot"); Objects.requireNonNull(program, "program");
        if (!snapshot.router().complete()) return new LoweringResult.Unsupported(identity, LoweringResult.Reason.MINECRAFT_FRONTEND_NOT_IMPLEMENTED,
                "WorldgenSnapshot", "Typed capture is missing one or more of the pinned 1.21.1 NoiseRouter roots.");
        if (!program.hasExactRouterRoots()) return new LoweringResult.Unsupported(identity,
                LoweringResult.Reason.MINECRAFT_FRONTEND_NOT_IMPLEMENTED, "WorldgenProgram",
                "Typed program must contain exactly the pinned 1.21.1 NoiseRouter roots.");
        if (!program.roots().equals(snapshot.router().roots())) return new LoweringResult.Unsupported(identity,
                LoweringResult.Reason.MINECRAFT_FRONTEND_NOT_IMPLEMENTED, "WorldgenProgram",
                "Typed program roots do not match the captured snapshot roots.");
        if (identity.seed() != snapshot.seed() || !identity.dimension().equals(snapshot.dimension())) {
            return new LoweringResult.Unsupported(identity, LoweringResult.Reason.MINECRAFT_FRONTEND_NOT_IMPLEMENTED,
                    "WorldgenSnapshot", "Typed capture seed and dimension must match the request identity.");
        }
        return new LoweringResult.TypedLowered(identity, snapshot, program);
    }
}
