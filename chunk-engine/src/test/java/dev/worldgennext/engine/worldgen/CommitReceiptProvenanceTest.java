// SPDX-License-Identifier: MIT
package dev.worldgennext.engine.worldgen;

import dev.worldgennext.material.chunk.BlockStateTable;
import dev.worldgennext.material.chunk.ChunkNoiseResult;
import dev.worldgennext.material.chunk.ChunkResultHeader;
import dev.worldgennext.semantic.execution.ExecutionReceipt;
import dev.worldgennext.semantic.identity.ContextIdentity;
import dev.worldgennext.semantic.program.NumericProfile;
import dev.worldgennext.semantic.program.WorldgenProgram;
import dev.worldgennext.semantic.snapshot.RegistrySnapshot;
import dev.worldgennext.semantic.snapshot.WorldgenSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CommitReceiptProvenanceTest {
    @Test
    void committedReceiptWithDifferentRevisionIsRolledBack() {
        ContextIdentity context = context(0L);
        ExecutionReceipt expected = execution(context, "execution-revision");
        AtomicInteger rollbacks = new AtomicInteger();
        ChunkCommitter committer = new ChunkCommitter() {
            @Override public CommitReceipt commit(CommitToken token, ChunkNoiseResult result,
                                                   ExecutionReceipt execution) {
                return new CommitReceipt(true, "published", token.expectedRevision() + 1, execution);
            }
            @Override public void rollback(CommitToken token) { rollbacks.incrementAndGet(); }
        };

        CommitReceipt receipt = new CommitCoordinator(committer).publish(
                new CommitToken("owner", context, context.worldEpoch(), 7), result(context), expected);

        assertFalse(receipt.committed());
        assertEquals("committed receipt revision mismatch", receipt.detail());
        assertEquals(1, rollbacks.get());
    }

    @Test
    void committedReceiptWithoutExecutionProvenanceIsRolledBack() {
        ContextIdentity context = context(1L);
        AtomicInteger rollbacks = new AtomicInteger();
        ChunkCommitter committer = new ChunkCommitter() {
            @Override public CommitReceipt commit(CommitToken token, ChunkNoiseResult result,
                                                   ExecutionReceipt execution) {
                return new CommitReceipt(true, "published", 0, null);
            }
            @Override public void rollback(CommitToken token) { rollbacks.incrementAndGet(); }
        };

        CommitReceipt receipt = publish(committer, context, execution(context, "execution-1"));

        assertFalse(receipt.committed());
        assertEquals("committed receipt omitted execution provenance", receipt.detail());
        assertEquals(1, rollbacks.get());
    }

    @Test
    void committedReceiptWithDifferentExecutionIdentityIsRolledBack() {
        ContextIdentity context = context(2L);
        ExecutionReceipt expected = execution(context, "execution-1");
        ExecutionReceipt different = execution(context, "execution-2");
        AtomicInteger rollbacks = new AtomicInteger();
        ChunkCommitter committer = new ChunkCommitter() {
            @Override public CommitReceipt commit(CommitToken token, ChunkNoiseResult result,
                                                   ExecutionReceipt execution) {
                return new CommitReceipt(true, "published", 0, different);
            }
            @Override public void rollback(CommitToken token) { rollbacks.incrementAndGet(); }
        };

        CommitReceipt receipt = publish(committer, context, expected);

        assertFalse(receipt.committed());
        assertEquals("committed receipt execution provenance mismatch", receipt.detail());
        assertEquals(1, rollbacks.get());
    }

    @Test
    void committedReceiptCannotStripCompiledArtifactProvenance() {
        ContextIdentity context = context(3L);
        ExecutionReceipt expected = execution(context, "execution-artifacts")
                .withShaderProvenance("shader-a", "spirv-a");
        ExecutionReceipt stripped = expected.withShaderProvenance(
                ExecutionReceipt.NOT_APPLICABLE, ExecutionReceipt.NOT_APPLICABLE);
        AtomicInteger rollbacks = new AtomicInteger();
        ChunkCommitter committer = new ChunkCommitter() {
            @Override public CommitReceipt commit(CommitToken token, ChunkNoiseResult result,
                                                   ExecutionReceipt execution) {
                return new CommitReceipt(true, "published", 0, stripped);
            }
            @Override public void rollback(CommitToken token) { rollbacks.incrementAndGet(); }
        };

        CommitReceipt receipt = publish(committer, context, expected);

        assertFalse(receipt.committed());
        assertEquals("committed receipt execution provenance mismatch", receipt.detail());
        assertEquals(1, rollbacks.get());
    }

    private static CommitReceipt publish(ChunkCommitter committer, ContextIdentity context,
                                         ExecutionReceipt execution) {
        return new CommitCoordinator(committer).publish(
                new CommitToken("owner", context, context.worldEpoch(), 0), result(context), execution);
    }

    private static ContextIdentity context(long seed) {
        return ContextIdentity.of(WorldgenSnapshot.builder(seed, "minecraft:overworld").build(),
                WorldgenProgram.builder().build(), NumericProfile.JAVA_REFERENCE,
                "abi-v2", "compiler-v2", 0, 0);
    }

    private static ExecutionReceipt execution(ContextIdentity context, String id) {
        return ExecutionReceipt.issued(id, "cpu", context, NumericProfile.JAVA_REFERENCE,
                context.programHash(), "abi-v2", 0, 1).completed(1).validated(1);
    }

    private static ChunkNoiseResult result(ContextIdentity context) {
        BlockStateTable table = BlockStateTable.fromRegistry(RegistrySnapshot.minimal());
        String[] states = new String[4096];
        Arrays.fill(states, "minecraft:air");
        return ChunkNoiseResult.ofDense(new ChunkResultHeader(0, 0, 0, 16,
                        context.worldKey(), context.registryHash(), "abi-v2"),
                table, states, new int[256], new boolean[4096]);
    }
}
