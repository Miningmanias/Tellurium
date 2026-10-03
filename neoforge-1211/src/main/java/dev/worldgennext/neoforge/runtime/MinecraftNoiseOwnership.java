// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.runtime;

import dev.worldgennext.semantic.identity.ContextIdentity;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Version-pinned ownership proof for a single BIOMES-to-NOISE transition.
 * The holder and target references are retained only as an authority check;
 * captured worldgen data must be carried separately as immutable inputs.
 */
public final class MinecraftNoiseOwnership {
    private static final AtomicLong NEXT_REVISION = new AtomicLong();

    private final StaticCache2D<GenerationChunkHolder> cache;
    private final GenerationChunkHolder holder;
    private final ChunkAccess target;
    private final ChunkPos position;
    private final MinecraftOwnershipToken token;

    private MinecraftNoiseOwnership(StaticCache2D<GenerationChunkHolder> cache,
                                    GenerationChunkHolder holder, ChunkAccess target,
                                    MinecraftOwnershipToken token) {
        this.cache = cache;
        this.holder = holder;
        this.target = target;
        this.position = target.getPos();
        this.token = token;
    }

    /**
     * Captures ownership before asynchronous compute. The center holder must
     * still expose the exact BIOMES target; a recycled or already-advanced
     * holder is rejected before any candidate work starts.
     */
    public static MinecraftNoiseOwnership capture(RuntimeComposition runtime,
                                                  StaticCache2D<GenerationChunkHolder> cache,
                                                  ChunkAccess target,
                                                  ContextIdentity context) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(context, "context");
        if (!(target instanceof ProtoChunk) || target instanceof ImposterProtoChunk) {
            throw new IllegalArgumentException("NOISE ownership requires a writable ProtoChunk");
        }
        if (target.getPersistedStatus() != ChunkStatus.BIOMES) {
            throw new IllegalStateException("NOISE ownership requires BIOMES input status; got "
                    + target.getPersistedStatus());
        }
        ChunkPos position = target.getPos();
        GenerationChunkHolder holder = Objects.requireNonNull(cache.get(position.x, position.z),
                "center generation holder");
        if (holder.getChunkIfPresentUnchecked(ChunkStatus.BIOMES) != target) {
            throw new IllegalStateException("center holder does not own the supplied BIOMES target");
        }
        long revision = NEXT_REVISION.incrementAndGet();
        if (revision < 0) throw new IllegalStateException("Minecraft ownership revision exhausted");
        var token = new MinecraftOwnershipToken(context, context.worldEpoch(), revision,
                ChunkStatus.BIOMES.getName(), "holder-" + UUID.randomUUID());
        if (!runtime.isCurrent(token)) {
            throw new IllegalStateException("captured Minecraft ownership is not current");
        }
        return new MinecraftNoiseOwnership(cache, holder, target, token);
    }

    public MinecraftOwnershipToken token() { return token; }
    public ChunkAccess target() { return target; }
    public GenerationChunkHolder holder() { return holder; }

    /** Rechecks holder identity, BIOMES ownership and status before mutation. */
    public boolean stillOwns() {
        try {
            return cache.get(position.x, position.z) == holder
                    && holder.getChunkIfPresentUnchecked(ChunkStatus.BIOMES) == target
                    && target.getPersistedStatus() == ChunkStatus.BIOMES;
        } catch (RuntimeException staleCache) {
            return false;
        }
    }

    /** Supplier form for {@link MinecraftChunkCommitter}; stale ownership fails closed. */
    public MinecraftOwnershipToken currentToken() {
        if (!stillOwns()) throw new IllegalStateException("Minecraft holder/status ownership changed before commit");
        return token;
    }
}
