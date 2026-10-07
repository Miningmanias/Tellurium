// SPDX-License-Identifier: MIT
package dev.tellurium.engine.worldgen;

import dev.tellurium.material.chunk.BlockStateTable;
import dev.tellurium.material.chunk.ChunkNoiseResult;
import dev.tellurium.material.chunk.ChunkResultHeader;
import dev.tellurium.semantic.execution.ExecutionReceipt;
import dev.tellurium.semantic.identity.ContextIdentity;
import dev.tellurium.semantic.program.NumericProfile;
import dev.tellurium.semantic.program.WorldgenProgram;
import dev.tellurium.semantic.snapshot.RegistrySnapshot;
import dev.tellurium.semantic.snapshot.WorldgenSnapshot;
import dev.tellurium.semantic.snapshot.BlockStateDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommitCoordinatorTest {
    @Test
    void identityAndEpochFailuresAreFailClosedBeforeCommit() {
        ContextIdentity context = context(42L);
        ChunkNoiseResult result = result(context);
        ExecutionReceipt execution = execution(context, NumericProfile.JAVA_REFERENCE,
                context.programHash(), "abi-v2");

        assertRejectedBeforeCommit(
                new CommitToken("owner", context, context.worldEpoch() + 1, 7),
                result, execution, "stale world epoch");

        ContextIdentity otherContext = context(43L);
        assertRejectedBeforeCommit(
                new CommitToken("owner", context, context.worldEpoch(), 7),
                result, execution(otherContext, NumericProfile.JAVA_REFERENCE,
                        otherContext.programHash(), "abi-v2"),
                "context identity mismatch");

        assertInvalidResultBeforeCommit(
                new CommitToken("owner", context, context.worldEpoch(), 7),
                resultWithHeader("wrong-context", context.registryHash()), execution,
                "context identity mismatch");
        assertInvalidResultBeforeCommit(
                new CommitToken("owner", context, context.worldEpoch(), 7),
                resultWithTable(context.worldKey(),
                        new BlockStateTable(java.util.List.of(BlockStateDescriptor.of("minecraft:air")))), execution,
                "registry identity mismatch");
    }

    @Test
    void executionProvenanceFailuresAreFailClosedBeforeCommit() {
        ContextIdentity context = context(42L);
        ChunkNoiseResult result = result(context);

        assertRejectedBeforeCommit(
                new CommitToken("owner", context, context.worldEpoch(), 7), result,
                execution(context, NumericProfile.GPU_IEEE_BITS, context.programHash(), "abi-v2"),
                "execution provenance mismatch");
        assertRejectedBeforeCommit(
                new CommitToken("owner", context, context.worldEpoch(), 7), result,
                execution(context, NumericProfile.JAVA_REFERENCE, "wrong-program", "abi-v2"),
                "execution provenance mismatch");
        assertRejectedBeforeCommit(
                new CommitToken("owner", context, context.worldEpoch(), 7), result,
                execution(context, NumericProfile.JAVA_REFERENCE, context.programHash(), "wrong-abi"),
                "execution provenance mismatch");
    }

    @Test
    void resultAbiMustMatchTheCommitContextBeforeMutation() {
        ContextIdentity context = context(42L);
        RecordingCommitter committer = new RecordingCommitter(Outcome.SUCCESS);
        CommitCoordinator coordinator = new CommitCoordinator(committer);
        CommitToken token = new CommitToken("owner", context, context.worldEpoch(), 7);
        ChunkNoiseResult result = resultWithHeader(context.worldKey(), context.registryHash(), "wrong-abi");

        CommitReceipt rejection = coordinator.publish(token, result,
                execution(context, NumericProfile.JAVA_REFERENCE, context.programHash(), "abi-v2"));

        assertFalse(rejection.committed());
        assertEquals("result ABI identity mismatch", rejection.detail());
        assertFalse(token.consumed());
        assertEquals(0, committer.commitCalls);
    }

    @Test
    void successfulCommitConsumesTokenAndRejectsReuse() {
        ContextIdentity context = context(42L);
        RecordingCommitter committer = new RecordingCommitter(Outcome.SUCCESS);
        CommitCoordinator coordinator = new CommitCoordinator(committer);
        CommitToken token = new CommitToken("owner", context, context.worldEpoch(), 7);
        ChunkNoiseResult result = result(context);
        ExecutionReceipt execution = execution(context, NumericProfile.JAVA_REFERENCE,
                context.programHash(), "abi-v2");

        CommitReceipt first = coordinator.publish(token, result, execution);
        CommitReceipt second = coordinator.publish(token, result, execution);

        assertTrue(first.committed());
        assertEquals("committed", first.detail());
        assertFalse(second.committed());
        assertEquals("duplicate or late commit token", second.detail());
        assertTrue(token.consumed());
        assertEquals(1, committer.commitCalls);
        assertEquals(0, committer.rollbackCalls);
    }

    @Test
    void rejectedCommitRollsBackMutation() {
        CommitAttempt attempt = attempt(Outcome.REJECTED);

        CommitReceipt receipt = attempt.coordinator.publish(attempt.token, attempt.result, attempt.execution);

        assertFalse(receipt.committed());
        assertEquals("rejected by committer", receipt.detail());
        assertTrue(attempt.token.consumed());
        assertEquals(1, attempt.committer.commitCalls);
        assertEquals(1, attempt.committer.rollbackCalls);
        assertFalse(attempt.committer.mutated);
    }

    @Test
    void nullCommitRollsBackMutationAndReturnsRejection() {
        CommitAttempt attempt = attempt(Outcome.NULL_RESULT);

        CommitReceipt receipt = attempt.coordinator.publish(attempt.token, attempt.result, attempt.execution);

        assertFalse(receipt.committed());
        assertEquals("committer returned null", receipt.detail());
        assertTrue(attempt.token.consumed());
        assertEquals(1, attempt.committer.commitCalls);
        assertEquals(1, attempt.committer.rollbackCalls);
        assertFalse(attempt.committer.mutated);
    }

    @Test
    void failedCommitRollsBackMutationAndPropagatesFailure() {
        CommitAttempt attempt = attempt(Outcome.FAILURE);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> attempt.coordinator.publish(attempt.token, attempt.result, attempt.execution));

        assertEquals("Commit failed", failure.getMessage());
        assertEquals("commit failure", failure.getCause().getMessage());
        assertTrue(attempt.token.consumed());
        assertEquals(1, attempt.committer.commitCalls);
        assertEquals(1, attempt.committer.rollbackCalls);
        assertFalse(attempt.committer.mutated);
    }

    private static void assertRejectedBeforeCommit(CommitToken token, ChunkNoiseResult result,
                                                    ExecutionReceipt execution, String reason) {
        RecordingCommitter committer = new RecordingCommitter(Outcome.SUCCESS);
        CommitCoordinator coordinator = new CommitCoordinator(committer);

        CommitReceipt rejection = coordinator.publish(token, result, execution);

        assertFalse(rejection.committed());
        assertTrue(rejection.detail().contains(reason), rejection.detail());
        assertFalse(token.consumed());
        assertEquals(0, committer.commitCalls);
        assertEquals(0, committer.rollbackCalls);
    }

    private static void assertInvalidResultBeforeCommit(CommitToken token, ChunkNoiseResult result,
                                                         ExecutionReceipt execution, String reason) {
        RecordingCommitter committer = new RecordingCommitter(Outcome.SUCCESS);
        CommitCoordinator coordinator = new CommitCoordinator(committer);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> coordinator.publish(token, result, execution));

        assertTrue(failure.getMessage().contains(reason), failure.getMessage());
        assertFalse(token.consumed());
        assertEquals(0, committer.commitCalls);
        assertEquals(0, committer.rollbackCalls);
    }

    private static CommitAttempt attempt(Outcome outcome) {
        ContextIdentity context = context(42L);
        RecordingCommitter committer = new RecordingCommitter(outcome);
        return new CommitAttempt(
                new CommitCoordinator(committer), committer,
                new CommitToken("owner", context, context.worldEpoch(), 7),
                result(context),
                execution(context, NumericProfile.JAVA_REFERENCE, context.programHash(), "abi-v2"));
    }

    private record CommitAttempt(CommitCoordinator coordinator, RecordingCommitter committer,
                                 CommitToken token, ChunkNoiseResult result,
                                 ExecutionReceipt execution) {}

    private enum Outcome { SUCCESS, REJECTED, NULL_RESULT, FAILURE }

    private static final class RecordingCommitter implements ChunkCommitter {
        private final Outcome outcome;
        private int commitCalls;
        private int rollbackCalls;
        private boolean mutated;

        private RecordingCommitter(Outcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public CommitReceipt commit(CommitToken token, ChunkNoiseResult result,
                                    ExecutionReceipt execution) throws Exception {
            commitCalls++;
            mutated = true;
            return switch (outcome) {
                case SUCCESS -> new CommitReceipt(true, "committed", token.expectedRevision(), execution.committed(1));
                case REJECTED -> new CommitReceipt(false, "rejected by committer", token.expectedRevision(), execution);
                case NULL_RESULT -> null;
                case FAILURE -> throw new Exception("commit failure");
            };
        }

        @Override
        public void rollback(CommitToken token) {
            rollbackCalls++;
            mutated = false;
        }
    }

    private static ContextIdentity context(long seed) {
        return ContextIdentity.of(
                WorldgenSnapshot.builder(seed, "minecraft:overworld").build(),
                WorldgenProgram.builder().build(), NumericProfile.JAVA_REFERENCE,
                "abi-v2", "compiler-v2", 0, 0);
    }

    private static ChunkNoiseResult result(ContextIdentity context) {
        return resultWithHeader(context.worldKey(), context.registryHash(), "abi-v2");
    }

    private static ChunkNoiseResult resultWithHeader(String resultContext, String registry) {
        return resultWithHeader(resultContext, registry, "abi-v2");
    }

    private static ChunkNoiseResult resultWithHeader(String resultContext, String registry, String abi) {
        BlockStateTable table = BlockStateTable.fromRegistry(RegistrySnapshot.minimal());
        String[] states = new String[4096];
        Arrays.fill(states, "minecraft:air");
        return ChunkNoiseResult.ofDense(
                new ChunkResultHeader(0, 0, 0, 16, resultContext, registry, abi),
                table, states, new int[256], new boolean[4096]);
    }

    private static ChunkNoiseResult resultWithTable(String resultContext, BlockStateTable table) {
        String[] states = new String[4096];
        Arrays.fill(states, "minecraft:air");
        return ChunkNoiseResult.ofDense(
                new ChunkResultHeader(0, 0, 0, 16, resultContext, table.fingerprint(), "abi-v2"),
                table, states, new int[256], new boolean[4096]);
    }

    private static ExecutionReceipt execution(ContextIdentity context, NumericProfile profile,
                                              String programHash, String abiVersion) {
        return ExecutionReceipt.issued("execution-1", "cpu", context, profile,
                programHash, abiVersion, 0, 1).completed(1).validated(1);
    }
}
