// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.threading;

import dev.tellurium.neoforge.version.Version;

import com.mojang.math.OctahedralGroup;
import net.minecraft.Util;
import net.minecraft.core.Direction;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Re-entrant offload of synchronous worldgen step bodies to Minecraft's
 * worldgen worker pool.  The worker re-invokes the vanilla body with the
 * offload guard set, so the injected hook falls through to the original code.
 */
public final class ParallelWorldgenSteps {
    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("tellurium.parallelStructureSteps", "true"));
    private static final ThreadLocal<Boolean> RUNNING_ORIGINAL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    static {
        // OctahedralGroup.rotate lazily builds an EnumMap without synchronization; a
        // concurrent reader can observe the half-filled map (null rotation, NPE in
        // jigsaw placement).  Build every table once on this thread, before any
        // structure step is offloaded, so later parallel calls are read-only.
        for (OctahedralGroup group : OctahedralGroup.values()) {
            for (Direction direction : Direction.values()) {
                group.rotate(direction);
            }
        }
    }

    private ParallelWorldgenSteps() {}

    public static boolean enabled() { return ENABLED; }

    /** True when the caller is the mailbox dispatch, not the worker re-invocation. */
    public static boolean shouldOffload() {
        return ENABLED && !RUNNING_ORIGINAL.get();
    }

    private static final boolean TERRAIN_ENABLED =
            Boolean.parseBoolean(System.getProperty("tellurium.parallelSurfaceCarvers", "true"));

    /** SURFACE/CARVERS offload gate; same re-entrancy rule as {@link #shouldOffload()}. */
    public static boolean shouldOffloadTerrain() {
        return TERRAIN_ENABLED && !RUNNING_ORIGINAL.get();
    }

    private static final boolean FEATURES_ENABLED =
            Boolean.parseBoolean(System.getProperty("tellurium.parallelFeatures", "true"));

    public static boolean shouldOffloadFeatures() {
        return FEATURES_ENABLED && !RUNNING_ORIGINAL.get();
    }

    /** Runs the vanilla body on the current thread with the offload guard set. */
    public static <T> T original(Supplier<T> body) {
        RUNNING_ORIGINAL.set(Boolean.TRUE);
        try {
            return body.get();
        } finally {
            RUNNING_ORIGINAL.set(Boolean.FALSE);
        }
    }

    public static <T> CompletableFuture<T> offload(String name, Supplier<CompletableFuture<T>> original) {
        return CompletableFuture.supplyAsync(Version.named("wgen_" + name, () -> {
            RUNNING_ORIGINAL.set(Boolean.TRUE);
            try {
                return original.get();
            } finally {
                RUNNING_ORIGINAL.set(Boolean.FALSE);
            }
        }), Util.backgroundExecutor()).thenCompose(future -> future);
    }
}
