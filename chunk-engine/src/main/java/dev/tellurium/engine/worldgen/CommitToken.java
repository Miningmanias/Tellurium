// SPDX-License-Identifier: MIT
package dev.tellurium.engine.worldgen;

import dev.tellurium.semantic.identity.ContextIdentity;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-use authoritative publication token supplied by the loader adapter. */
public final class CommitToken {
    private final String ownershipToken;
    private final ContextIdentity context;
    private final long expectedEpoch;
    private final long expectedRevision;
    private final AtomicBoolean consumed = new AtomicBoolean();
    public CommitToken(String ownershipToken, ContextIdentity context, long expectedEpoch, long expectedRevision) {
        if (ownershipToken == null || ownershipToken.isBlank()) throw new IllegalArgumentException("Ownership token required");
        this.ownershipToken = ownershipToken; this.context = java.util.Objects.requireNonNull(context); this.expectedEpoch = expectedEpoch; this.expectedRevision = expectedRevision;
        if (expectedEpoch < 0 || expectedRevision < 0) throw new IllegalArgumentException("Negative commit identity");
    }
    public String ownershipToken() { return ownershipToken; }
    public ContextIdentity context() { return context; }
    public long expectedEpoch() { return expectedEpoch; }
    public long expectedRevision() { return expectedRevision; }
    public boolean consumed() { return consumed.get(); }
    boolean consume() { return consumed.compareAndSet(false, true); }
}
