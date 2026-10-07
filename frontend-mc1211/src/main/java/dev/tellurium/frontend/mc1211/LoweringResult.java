// SPDX-License-Identifier: MIT
package dev.tellurium.frontend.mc1211;

import dev.tellurium.semantic.*;
import java.util.Objects;

/** Explicit capability result. No v0.1 implementation emits Lowered. */
public sealed interface LoweringResult {
    record Lowered(WorldgenIdentity identity, DensityExpression expression) implements LoweringResult {
        public Lowered { Objects.requireNonNull(identity, "identity"); ExpressionValidation.validate(expression); }
    }
    record TypedLowered(WorldgenIdentity identity, dev.tellurium.semantic.snapshot.WorldgenSnapshot snapshot,
                        dev.tellurium.semantic.program.WorldgenProgram program) implements LoweringResult {
        public TypedLowered {
            Objects.requireNonNull(identity, "identity"); Objects.requireNonNull(snapshot, "snapshot"); Objects.requireNonNull(program, "program");
            if (identity.seed() != snapshot.seed()) {
                throw new IllegalArgumentException("Typed lowering identity seed does not match captured snapshot");
            }
            if (!identity.dimension().equals(snapshot.dimension())) {
                throw new IllegalArgumentException("Typed lowering identity dimension does not match captured snapshot");
            }
            if (!snapshot.router().complete() || !program.hasExactRouterRoots()) {
                throw new IllegalArgumentException("Typed lowering requires exactly the pinned 1.21.1 router roots");
            }
            if (!program.roots().equals(snapshot.router().roots())) {
                throw new IllegalArgumentException("Typed lowering program roots do not match captured snapshot roots");
            }
        }
    }
    record Unsupported(WorldgenIdentity identity, Reason reason, String sourceType, String explanation) implements LoweringResult {
        public Unsupported {
            Objects.requireNonNull(identity, "identity"); Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(sourceType, "sourceType"); Objects.requireNonNull(explanation, "explanation");
            if (explanation.isBlank()) throw new IllegalArgumentException("Unsupported reason must be actionable");
        }
    }
    enum Reason { MISSING_SOURCE, MINECRAFT_FRONTEND_NOT_IMPLEMENTED }
}
