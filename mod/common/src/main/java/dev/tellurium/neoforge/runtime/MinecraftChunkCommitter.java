// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import dev.tellurium.engine.worldgen.ChunkCommitter;
import dev.tellurium.engine.worldgen.CommitReceipt;
import dev.tellurium.engine.worldgen.CommitToken;
import dev.tellurium.material.chunk.ChunkNoiseResult;
import dev.tellurium.semantic.execution.ExecutionReceipt;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.Block;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Loader-injected commit adapter; it does not own Minecraft holder scheduling. */
public final class MinecraftChunkCommitter implements ChunkCommitter {
    @FunctionalInterface
    private interface Mutation {
        void apply(CommitToken token, ChunkNoiseResult result, ChunkMutationJournal journal);
    }

    private final Supplier<ChunkMutationJournal> journals;
    private final Mutation apply;
    private ChunkMutationJournal active;
    public MinecraftChunkCommitter(Supplier<ChunkMutationJournal> journals,
                                   BiConsumer<ChunkNoiseResult, ChunkMutationJournal> apply) {
        this(journals, (token, result, journal) -> apply.accept(result, journal));
    }

    private MinecraftChunkCommitter(Supplier<ChunkMutationJournal> journals, Mutation apply) {
        this.journals = Objects.requireNonNull(journals);
        this.apply = Objects.requireNonNull(apply);
    }

    /**
     * Creates the real 1.21.1 NOISE committer.  The current ownership supplier
     * must be backed by the authoritative holder/status owner; a stale or
     * recycled token is rejected before the first block mutation.
     */
    public static MinecraftChunkCommitter forChunk(ChunkAccess target, HolderGetter<Block> blocks,
                                                   Supplier<MinecraftOwnershipToken> currentOwnership,
                                                   String expectedStatus) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(currentOwnership, "currentOwnership");
        if (expectedStatus == null || expectedStatus.isBlank()) throw new IllegalArgumentException("Expected status required");
        MinecraftChunkApplier applier = new MinecraftChunkApplier(MinecraftChunkApplier.fromBlocks(blocks));
        return new MinecraftChunkCommitter(ChunkMutationJournal::new, (token, result, journal) -> {
            MinecraftOwnershipToken expected = new MinecraftOwnershipToken(token.context(), token.expectedEpoch(),
                    token.expectedRevision(), expectedStatus, token.ownershipToken());
            MinecraftOwnershipToken current = Objects.requireNonNull(currentOwnership.get(), "current ownership");
            if (!expected.matches(current.context(), current.worldEpoch(), current.revision(), current.status(), current.ownershipToken())) {
                throw new IllegalStateException("Minecraft target ownership/revision/status changed before commit");
            }
            if (!token.context().worldKey().equals(result.header().contextIdentity())
                    || !token.context().registryHash().equals(result.header().registryFingerprint())) {
                throw new IllegalArgumentException("Result identity does not match commit token");
            }
            applier.apply(target, result, journal);
        });
    }

    @Override
    public synchronized CommitReceipt commit(CommitToken token, ChunkNoiseResult result, ExecutionReceipt execution) {
        if (active != null) throw new IllegalStateException("Commit already active");
        active = Objects.requireNonNull(journals.get(), "journal");
        try {
            apply.apply(token, result, active);
            active.commit();
            return new CommitReceipt(true, "Minecraft result published through authoritative adapter",
                    token.expectedRevision(), execution.committed(execution.validated()));
        } catch (RuntimeException | Error failure) {
            try { active.rollback(); } finally { active = null; }
            throw failure;
        } finally { active = null; }
    }
    @Override public synchronized void rollback(CommitToken token) { if (active != null) active.rollback(); }
}
