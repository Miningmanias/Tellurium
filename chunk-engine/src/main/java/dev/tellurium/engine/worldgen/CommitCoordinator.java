// SPDX-License-Identifier: MIT
package dev.tellurium.engine.worldgen;

import dev.tellurium.material.chunk.ChunkNoiseResult;
import dev.tellurium.material.chunk.ChunkResultValidator;
import dev.tellurium.semantic.execution.ExecutionReceipt;
import java.util.Objects;

/** Validates identity and delegates exactly one authoritative mutation. */
public final class CommitCoordinator {
    private final ChunkCommitter committer;
    public CommitCoordinator(ChunkCommitter committer) { this.committer = Objects.requireNonNull(committer); }
    public CommitReceipt publish(CommitToken token, ChunkNoiseResult result, ExecutionReceipt receipt) {
        Objects.requireNonNull(token); Objects.requireNonNull(result); Objects.requireNonNull(receipt);
        if (token.consumed()) return CommitReceipt.rejected("duplicate or late commit token");
        if (receipt.context().worldEpoch() != token.expectedEpoch()) return CommitReceipt.rejected("stale world epoch");
        if (!receipt.context().equals(token.context())) return CommitReceipt.rejected("context identity mismatch");
        if (receipt.numericProfile() != token.context().numericProfile()
                || !receipt.programHash().equals(token.context().programHash())
                || !receipt.abiVersion().equals(token.context().abiVersion())
                || receipt.deviceGeneration() != receipt.context().deviceGeneration()) {
            return CommitReceipt.rejected("execution provenance mismatch");
        }
        ChunkResultValidator.requireValid(result, token.context().worldKey(), token.context().registryHash());
        if (!result.header().abiVersion().equals(token.context().abiVersion())) {
            return CommitReceipt.rejected("result ABI identity mismatch");
        }
        if (!token.consume()) return CommitReceipt.rejected("duplicate or late commit token");
        try {
            CommitReceipt published = committer.commit(token, result, receipt);
            if (published == null) {
                try { committer.rollback(token); }
                catch (Throwable rollbackFailure) { throw new IllegalStateException("Rollback failed after rejected commit", rollbackFailure); }
                return CommitReceipt.rejected("committer returned null");
            }
            if (!published.committed()) {
                try { committer.rollback(token); }
                catch (Throwable rollbackFailure) { throw new IllegalStateException("Rollback failed after rejected commit", rollbackFailure); }
                return published;
            }
            if (published.revision() != token.expectedRevision()) {
                try { committer.rollback(token); }
                catch (Throwable rollbackFailure) { throw new IllegalStateException("Rollback failed after revision mismatch", rollbackFailure); }
                return CommitReceipt.rejected("committed receipt revision mismatch");
            }
            if (published.execution() == null) {
                try { committer.rollback(token); }
                catch (Throwable rollbackFailure) { throw new IllegalStateException("Rollback failed after missing commit provenance", rollbackFailure); }
                return CommitReceipt.rejected("committed receipt omitted execution provenance");
            }
            if (!sameProvenance(receipt, published.execution())) {
                try { committer.rollback(token); }
                catch (Throwable rollbackFailure) { throw new IllegalStateException("Rollback failed after provenance mismatch", rollbackFailure); }
                return CommitReceipt.rejected("committed receipt execution provenance mismatch");
            }
            return published;
        } catch (Exception | Error failure) {
            try { committer.rollback(token); } catch (Throwable rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            throw new IllegalStateException("Commit failed", failure);
        }
    }

    private static boolean sameProvenance(ExecutionReceipt expected, ExecutionReceipt actual) {
        return expected.executionId().equals(actual.executionId())
                && expected.backend().equals(actual.backend())
                && expected.context().equals(actual.context())
                && expected.numericProfile() == actual.numericProfile()
                && expected.programHash().equals(actual.programHash())
                && expected.abiVersion().equals(actual.abiVersion())
                && expected.shaderHash().equals(actual.shaderHash())
                && expected.spirvHash().equals(actual.spirvHash())
                && expected.deviceGeneration() == actual.deviceGeneration();
    }
}
