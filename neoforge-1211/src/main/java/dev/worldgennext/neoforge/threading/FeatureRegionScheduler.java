// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.threading;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.Util;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Runs FEATURES steps on the worldgen worker pool while keeping the vanilla
 * exclusivity that matters: a feature step writes block states within one
 * chunk of its centre, so two steps may run together only when their 3x3
 * chunk neighbourhoods are disjoint.  Steps that conflict with a running
 * step wait in submission order.
 *
 * <p>As in vanilla, the relative order of neighbouring feature steps is
 * decided by scheduling, so border-crossing decoration can differ between
 * runs; each step still uses its own chunk-seeded random.</p>
 */
public final class FeatureRegionScheduler {
    private static final Map<Object, FeatureRegionScheduler> BY_LEVEL = new WeakHashMap<>();

    public static synchronized FeatureRegionScheduler forLevel(Object level) {
        return BY_LEVEL.computeIfAbsent(level, k -> new FeatureRegionScheduler());
    }

    private record Pending<T>(int x, int z, Supplier<CompletableFuture<T>> body, CompletableFuture<T> result) {}

    private final LongOpenHashSet busy = new LongOpenHashSet();
    private final ArrayDeque<Pending<?>> waiting = new ArrayDeque<>();

    public <T> CompletableFuture<T> submit(ChunkPos pos, Supplier<CompletableFuture<T>> body) {
        Pending<T> pending = new Pending<>(pos.x, pos.z, body, new CompletableFuture<>());
        boolean start;
        synchronized (this) {
            start = free(pending.x, pending.z);
            if (start) mark(pending.x, pending.z, true);
            else waiting.add(pending);
        }
        if (start) run(pending);
        return pending.result;
    }

    private boolean free(int x, int z) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (busy.contains(ChunkPos.asLong(x + dx, z + dz))) return false;
        }
        return true;
    }

    private void mark(int x, int z, boolean value) {
        long key = ChunkPos.asLong(x, z);
        if (value) busy.add(key);
        else busy.remove(key);
    }

    private <T> void run(Pending<T> pending) {
        CompletableFuture.supplyAsync(Util.wrapThreadWithTaskName("wgen_features", pending.body::get), Util.backgroundExecutor())
                .thenCompose(future -> future)
                .whenComplete((value, error) -> {
                    release(pending.x, pending.z);
                    if (error != null) pending.result.completeExceptionally(error);
                    else pending.result.complete(value);
                });
    }

    private void release(int x, int z) {
        java.util.List<Pending<?>> ready = new java.util.ArrayList<>();
        synchronized (this) {
            mark(x, z, false);
            Iterator<Pending<?>> it = waiting.iterator();
            while (it.hasNext()) {
                Pending<?> next = it.next();
                if (free(next.x, next.z)) {
                    mark(next.x, next.z, true);
                    it.remove();
                    ready.add(next);
                }
            }
        }
        for (Pending<?> next : ready) run(next);
    }
}
