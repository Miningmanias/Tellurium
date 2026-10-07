// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Every chunk holder inside a ticket's radius owns a generation task, and a
 * task holds a reference on every chunk in its dependency neighbourhood until
 * it has run to its end.  When a holder's tickets go away its task is only
 * marked cancelled; it still has to be run once more to drop those
 * references, and that run is queued at the holder's new priority: the
 * lowest, because the holder is now unloaded.  While generation keeps the
 * queue busy with higher-priority work, those runs never happen, the
 * referenced chunks cannot unload, and loaded chunks pile up (tens of
 * thousands, gigabytes of heap) until the queue happens to drain, at which
 * point they all unload at once.
 *
 * <p>This queues work for holders at an unloaded level at the highest
 * priority instead.  For the worldgen queue that work is exactly those
 * cancelled tasks, which do nothing but release their references.  Which
 * chunks generate and what they contain is unaffected.</p>
 */
@Mixin(ChunkTaskPriorityQueue.class)
public abstract class ChunkTaskPriorityQueueMixin {
    @Unique private static final boolean tellurium$ENABLED =
            Boolean.parseBoolean(System.getProperty("tellurium.promptTaskRelease", "true"));

    @Unique
    private static int tellurium$priority(int level) {
        return tellurium$ENABLED && level > ChunkLevel.MAX_LEVEL ? 0 : level;
    }

    @ModifyVariable(method = "resortChunkTasks", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private int tellurium$resortFrom(int level) {
        return tellurium$priority(level);
    }

    @ModifyVariable(method = "resortChunkTasks", at = @At("HEAD"), ordinal = 1, argsOnly = true)
    private int tellurium$resortTo(int level) {
        return tellurium$priority(level);
    }

    @ModifyVariable(method = "submit", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private int tellurium$submitAt(int level) {
        return tellurium$priority(level);
    }
}
