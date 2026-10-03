// SPDX-License-Identifier: MIT
package dev.worldgennext.engine.worldgen;

import dev.worldgennext.engine.GenerationStage;
import dev.worldgennext.semantic.identity.ContextIdentity;
import dev.worldgennext.semantic.identity.DynamicInputIdentity;
import dev.worldgennext.semantic.program.EvaluationDomain;
import dev.worldgennext.semantic.program.NumericProfile;
import java.util.Objects;

/**
 * Unique work identity; subscriber identity is intentionally not part of the
 * key.  The captured spatial extent and evaluation domain are part of the
 * identity as well: a column/lattice request must never share a result with a
 * block request, and a different vertical window must not reuse a partial
 * result.  The context carries the graph, numeric, dynamic-input and epoch
 * identities, so those values remain one immutable identity boundary rather
 * than a second set of independently mutable fields.
 */
public record WorkKey(ContextIdentity context, int chunkX, int chunkZ, GenerationStage stage, String endpoint,
                      int minY, int height, EvaluationDomain domain) {
    /** Backward-compatible whole-request key for callers that have no explicit Y/domain metadata yet. */
    public WorkKey(ContextIdentity context, int chunkX, int chunkZ, GenerationStage stage, String endpoint) {
        this(context, chunkX, chunkZ, stage, endpoint, 0, 1, EvaluationDomain.BLOCK);
    }

    public WorkKey {
        Objects.requireNonNull(context, "context"); Objects.requireNonNull(stage, "stage");
        if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("Endpoint required");
        Objects.requireNonNull(domain, "domain");
        if (height <= 0) throw new IllegalArgumentException("Positive Y extent height required");
        try {
            Math.addExact(minY, height);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Y extent overflows integer world coordinates", overflow);
        }
    }

    public int maxYExclusive() { return Math.addExact(minY, height); }
    public String graphIdentity() { return context.programHash(); }
    public NumericProfile numericProfile() { return context.numericProfile(); }
    public DynamicInputIdentity dynamicInputs() { return context.dynamicInputs(); }
    public long worldEpoch() { return context.worldEpoch(); }
}
