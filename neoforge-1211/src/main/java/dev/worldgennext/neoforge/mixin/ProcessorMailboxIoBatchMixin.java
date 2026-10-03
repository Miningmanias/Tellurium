// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import it.unimi.dsi.fastutil.ints.Int2BooleanFunction;
import net.minecraft.util.thread.ProcessorMailbox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A mailbox handles one message per dispatch and then re-submits itself to
 * its executor.  For the IO workers that executor is a cached thread pool, so
 * every message (a store request, each single pending write, a read) costs a
 * hand-off to a parked thread.  At a few thousand chunk saves per second the
 * IO worker then spends its time waiting to be woken, pending writes pile up
 * with their chunk data, and reads queue behind them.
 *
 * <p>An IO worker's mailbox instead keeps taking messages, in the queue's own
 * order, up to a bound per dispatch.  Only the number of dispatches changes;
 * other mailboxes (worldgen, light, the sorter) share a bounded pool and keep
 * the original one-message turns.</p>
 */
@Mixin(ProcessorMailbox.class)
public abstract class ProcessorMailboxIoBatchMixin {
    @Unique private static final int worldgenNext$BATCH = Math.max(1, Integer.getInteger("worldgennext.asyncIoMailboxBatch", 256));

    @Shadow @Final private String name;

    @Shadow
    private int pollUntil(Int2BooleanFunction condition) {
        throw new AssertionError();
    }

    @Redirect(method = "run", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/thread/ProcessorMailbox;pollUntil(Lit/unimi/dsi/fastutil/ints/Int2BooleanFunction;)I"))
    private int worldgenNext$batchIoMessages(ProcessorMailbox<?> self, Int2BooleanFunction oneMessage) {
        if (worldgenNext$BATCH > 1 && name.startsWith("IOWorker-")) {
            return pollUntil(handled -> handled < worldgenNext$BATCH);
        }
        return pollUntil(oneMessage);
    }
}
