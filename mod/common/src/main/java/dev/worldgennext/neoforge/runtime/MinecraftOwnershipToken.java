// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import dev.worldgennext.semantic.identity.ContextIdentity;

/**
 * Loader-side ownership identity captured before an asynchronous NOISE
 * computation.  The opaque token is optional for the old four-argument
 * model, but a live committer must carry it so a recycled holder cannot be
 * mistaken for the original target.
 */
public record MinecraftOwnershipToken(ContextIdentity context, long worldEpoch, long revision,
                                      String status, String ownershipToken) {
    public MinecraftOwnershipToken {
        if (context == null || worldEpoch < 0 || revision < 0 || status == null || status.isBlank()
                || ownershipToken == null) {
            throw new IllegalArgumentException("Invalid Minecraft ownership token");
        }
    }

    /** Compatibility constructor for callers that have not yet exposed an opaque holder token. */
    public MinecraftOwnershipToken(ContextIdentity context, long worldEpoch, long revision, String status) {
        this(context, worldEpoch, revision, status, "");
    }

    public boolean matches(ContextIdentity candidate, long epoch, long candidateRevision, String candidateStatus) {
        return matches(candidate, epoch, candidateRevision, candidateStatus, "");
    }

    public boolean matches(ContextIdentity candidate, long epoch, long candidateRevision,
                           String candidateStatus, String candidateOwnershipToken) {
        return candidate != null
                && candidateOwnershipToken != null
                && context.withoutDeviceGeneration().worldKey().equals(candidate.withoutDeviceGeneration().worldKey())
                && context.deviceGeneration() == candidate.deviceGeneration()
                && worldEpoch == epoch
                && revision == candidateRevision
                && status.equals(candidateStatus)
                && (ownershipToken.isBlank() || ownershipToken.equals(candidateOwnershipToken));
    }
}
