// SPDX-License-Identifier: MIT
package dev.tellurium.engine.worldgen;

import dev.tellurium.engine.GenerationStage;
import dev.tellurium.semantic.identity.ContextIdentity;
import dev.tellurium.semantic.identity.DynamicInputIdentity;
import dev.tellurium.semantic.program.NumericProfile;
import dev.tellurium.semantic.program.WorldgenProgram;
import dev.tellurium.semantic.snapshot.WorldgenSnapshot;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkRecordTest {
    @Test
    void duplicateDirectFinishDoesNotDrainTerminalNotificationsTwice() {
        ContextIdentity context = ContextIdentity.of(
                WorldgenSnapshot.builder(42L, "minecraft:overworld").build(),
                WorldgenProgram.builder().build(), NumericProfile.JAVA_REFERENCE,
                "abi-v2", "compiler-v2", 0, 0);
        var request = new GenerationRequest(
                new WorkKey(context, 0, 0, GenerationStage.NOISE, "noise"),
                "owner", 0, 8, false);
        WorkRecord record = new WorkRecord(request);
        var subscription = new RequestSubscription(record);
        AtomicInteger notifications = new AtomicInteger();
        subscription.completion().whenComplete((terminal, failure) -> notifications.incrementAndGet());

        record.finish(WorkRecord.State.FAILED, "first", null);
        record.finish(WorkRecord.State.CANCELLED, "late", null);

        assertEquals(WorkRecord.State.FAILED, subscription.completion().toCompletableFuture().join().state());
        assertEquals(1, notifications.get());
    }

    @Test
    void cancellationAfterSharedWorkIsTerminalCannotReplaceItsAuthoritativeOutcome() {
        ContextIdentity context = ContextIdentity.of(
                WorldgenSnapshot.builder(42L, "minecraft:overworld").build(),
                WorldgenProgram.builder().build(), NumericProfile.JAVA_REFERENCE,
                "abi-v2", "compiler-v2", 0, 0);
        var request = new GenerationRequest(
                new WorkKey(context, 0, 0, GenerationStage.NOISE, "noise"),
                "owner", 0, 8, false);
        WorkRecord record = new WorkRecord(request);
        var subscription = new RequestSubscription(record);
        record.finish(WorkRecord.State.COMMITTED, "published", null);

        assertFalse(subscription.cancel());
        assertEquals(WorkRecord.State.COMMITTED,
                subscription.completion().toCompletableFuture().join().state());
    }
}
