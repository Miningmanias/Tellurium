// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import dev.tellurium.neoforge.threading.IdleUnloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Between ticks the server thread runs queued tasks and otherwise sleeps
 * until the next tick is due, and after an overlong tick it still waits a
 * full tick period.  Chunk unloads (the per-chunk part of saving that must
 * run on this thread) are only worked on inside the tick, so during
 * generation they pile up while the thread sleeps and then stretch the tick,
 * which delays the promotion of finished chunks.
 *
 * <p>Here the thread, when it has no task to run in that between-ticks wait,
 * takes one queued unload instead of sleeping.  Tasks keep priority; the
 * unloads are the same runnables the tick would have run, on the same thread,
 * and only at the top level of the wait (never inside a nested blocking
 * call).</p>
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerIdleUnloadMixin {
    @Unique private boolean tellurium$betweenTicks;

    @Shadow public abstract Iterable<ServerLevel> getAllLevels();
    @Shadow private long nextTickTimeNanos;

    @Inject(method = "waitUntilNextTick", at = @At("HEAD"))
    private void tellurium$enterWait(CallbackInfo callback) {
        tellurium$betweenTicks = true;
    }

    @Inject(method = "waitUntilNextTick", at = @At("RETURN"))
    private void tellurium$leaveWait(CallbackInfo callback) {
        tellurium$betweenTicks = false;
    }

    @Inject(method = "pollTaskInternal", at = @At("RETURN"), cancellable = true)
    private void tellurium$unloadWhenIdle(CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ() || !tellurium$betweenTicks) return;
        if (((BlockableEventLoopAccessor) this).tellurium$blockingCount() != 1) return;
        // An unload takes a millisecond or a few; started just before the next tick is due, it makes that tick late.
        if (nextTickTimeNanos - net.minecraft.Util.getNanos() < 4_000_000L) return;
        for (ServerLevel level : getAllLevels()) {
            if (((IdleUnloads) level.getChunkSource().chunkMap).tellurium$runIdleUnload()) {
                callback.setReturnValue(true);
                return;
            }
        }
    }
}
