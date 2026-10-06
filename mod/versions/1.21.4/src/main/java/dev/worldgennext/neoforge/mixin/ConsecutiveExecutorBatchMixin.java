// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import net.minecraft.util.thread.AbstractConsecutiveExecutor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * An IO worker handles one message per dispatch and then hands itself back to a cached thread pool, so every
 * store request, every single pending write and every read costs a hand-off to a parked thread.  Here an IO
 * worker keeps taking messages, in the queue's own order, up to a bound per dispatch.  (Minecraft 1.21.2 and
 * later; 1.21.1: ProcessorMailboxDispatchMixin, which also moves the scheduling mailboxes to threads of their
 * own.  That part is not carried over.)
 */
@Mixin(AbstractConsecutiveExecutor.class)
public abstract class ConsecutiveExecutorBatchMixin {
    @Unique private static final int worldgenNext$BATCH = Math.max(1, Integer.getInteger("worldgennext.asyncIoMailboxBatch", 256));

    @Shadow @Final private String name;

    @Shadow
    private boolean pollTask() {
        throw new AssertionError();
    }

    @Redirect(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/AbstractConsecutiveExecutor;pollTask()Z"))
    private boolean worldgenNext$batchMessages(AbstractConsecutiveExecutor<?> self) {
        if (worldgenNext$BATCH <= 1 || !name.startsWith("IOWorker-")) return pollTask();
        boolean any = false;
        for (int handled = 0; handled < worldgenNext$BATCH && pollTask(); handled++) any = true;
        return any;
    }
}
