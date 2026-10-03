// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.threading;

import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.CompletableFuture;

/** Implemented on ChunkStorage by mixin: saves whose encoding is still in flight. */
public interface PendingChunkSaves {
    void worldgenNext$track(ChunkPos pos, CompletableFuture<Void> save);

    void worldgenNext$awaitAll();

    /** True when the non-thread-safe legacy structure index exists; saves then stay synchronous. */
    boolean worldgenNext$legacyIndexActive();
}
