// SPDX-License-Identifier: MIT
package dev.worldgennext.engine.worldgen;

import dev.worldgennext.engine.GenerationStage;
import java.util.Objects;

/** Game-supplied ownership/dependency token plus a WorldgenNext logical request. */
public record GenerationRequest(WorkKey key, String ownershipToken, int priority,
                                ResourceEstimate resources, boolean gpuRequired, long expectedRevision) {
    public GenerationRequest {
        Objects.requireNonNull(key, "key");
        if (ownershipToken == null || ownershipToken.isBlank()) throw new IllegalArgumentException("Ownership token required");
        Objects.requireNonNull(resources, "resources");
        if (priority < 0 || expectedRevision < 0 || resources.totalBytes() <= 0) throw new IllegalArgumentException("Invalid request priority/revision/size");
        if (key.stage() == GenerationStage.SAVED && gpuRequired) throw new IllegalArgumentException("Saved endpoint cannot require GPU generation directly");
    }

    /** Backward-compatible constructor for the original conservative estimate. */
    public GenerationRequest(WorkKey key, String ownershipToken, int priority,
                             long estimatedBytes, boolean gpuRequired) {
        this(key, ownershipToken, priority, ResourceEstimate.uniform(estimatedBytes), gpuRequired, 0);
    }

    public GenerationRequest(WorkKey key, String ownershipToken, int priority,
                             ResourceEstimate resources, boolean gpuRequired) {
        this(key, ownershipToken, priority, resources, gpuRequired, 0);
    }

    /** Total reserved capacity across all resource classes. */
    public long estimatedBytes() { return resources.totalBytes(); }

    /**
     * Returns whether another subscriber describes the same executable work.
     * WorkKey intentionally excludes subscriber identity, but the first request
     * still establishes the ownership, route and reservation contract for every
     * later subscriber.  Silently coalescing a conflicting request would let a
     * stale holder or a different GPU policy share the wrong computation.
     */
    public boolean compatibleWith(GenerationRequest other) {
        Objects.requireNonNull(other, "other");
        return ownershipToken.equals(other.ownershipToken)
                && resources.equals(other.resources)
                && gpuRequired == other.gpuRequired
                && expectedRevision == other.expectedRevision;
    }

    public String incompatibilityReason(GenerationRequest other) {
        Objects.requireNonNull(other, "other");
        if (!ownershipToken.equals(other.ownershipToken)) return "ownership token differs";
        if (!resources.equals(other.resources)) return "resource reservation differs";
        if (gpuRequired != other.gpuRequired) return "GPU requirement differs";
        if (expectedRevision != other.expectedRevision) return "authoritative revision differs";
        return "compatible";
    }
}
