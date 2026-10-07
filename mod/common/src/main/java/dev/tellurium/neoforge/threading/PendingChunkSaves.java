// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.threading;

import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.CompletableFuture;

/** Implemented on ChunkStorage by mixin: saves whose encoding is still in flight. */
public interface PendingChunkSaves {
    void tellurium$track(ChunkPos pos, CompletableFuture<Void> save, AsyncSectionEncoding.Task encoding);

    void tellurium$awaitAll();

    /** True when the non-thread-safe legacy structure index exists; saves then stay synchronous. */
    boolean tellurium$legacyIndexActive();
}
