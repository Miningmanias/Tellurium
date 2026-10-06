// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import dev.worldgennext.neoforge.threading.MailboxThreads;
import it.unimi.dsi.fastutil.ints.Int2BooleanFunction;
import net.minecraft.util.thread.ProcessorMailbox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.Executor;

/**
 * A mailbox handles one message per dispatch and then re-submits itself to
 * its executor.
 *
 * <p>For the IO workers that executor is a cached thread pool, so every
 * message (a store request, each single pending write, a read) costs a
 * hand-off to a parked thread.  At a few thousand chunk saves per second the
 * IO worker then spends its time waiting to be woken, pending writes pile up
 * with their chunk data, and reads queue behind them.  An IO worker's mailbox
 * instead keeps taking messages, in the queue's own order, up to a bound per
 * dispatch.</p>
 *
 * <p>The chunk system's "worldgen" and "sorter" mailboxes are dispatched to
 * the worldgen worker pool, the same pool that runs the generation steps
 * themselves.  While every worker is inside a step, a scheduling message
 * waits for one to finish, and the workers it would have fed stay idle
 * afterwards.  These two mailboxes are dispatched to threads of their own
 * (see {@link MailboxThreads}) and likewise take several messages per
 * dispatch.</p>
 *
 * <p>Only where and how often a mailbox is dispatched changes; messages are
 * still handled one at a time in queue order.</p>
 */
@Mixin(ProcessorMailbox.class)
public abstract class ProcessorMailboxDispatchMixin {
    @Unique private static final int worldgenNext$BATCH = Math.max(1, Integer.getInteger("worldgennext.asyncIoMailboxBatch", 256));

    @Shadow @Final private String name;

    @Shadow
    private int pollUntil(Int2BooleanFunction condition) {
        throw new AssertionError();
    }

    @Unique
    private boolean worldgenNext$schedulingMailbox() {
        return MailboxThreads.ENABLED && (name.equals("worldgen") || name.equals("sorter"));
    }

    @Redirect(method = "run", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/thread/ProcessorMailbox;pollUntil(Lit/unimi/dsi/fastutil/ints/Int2BooleanFunction;)I"))
    private int worldgenNext$batchMessages(ProcessorMailbox<?> self, Int2BooleanFunction oneMessage) {
        if (worldgenNext$BATCH > 1 && name.startsWith("IOWorker-")) {
            return pollUntil(handled -> handled < worldgenNext$BATCH);
        }
        if (worldgenNext$schedulingMailbox()) {
            return pollUntil(handled -> handled < MailboxThreads.BATCH);
        }
        return pollUntil(oneMessage);
    }

    @Redirect(method = "registerForExecution", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/Executor;execute(Ljava/lang/Runnable;)V"))
    private void worldgenNext$ownThread(Executor dispatcher, Runnable mailbox) {
        if (worldgenNext$schedulingMailbox()) {
            MailboxThreads.execute(mailbox);
        } else {
            dispatcher.execute(mailbox);
        }
    }
}
