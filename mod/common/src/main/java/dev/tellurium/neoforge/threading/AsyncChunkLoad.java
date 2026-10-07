// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.threading;

import com.google.common.collect.MapMaker;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

/**
 * Deserializes loaded chunks on the worker pool.
 *
 * <p>Vanilla reads a chunk's bytes on the IO thread but turns them into a
 * chunk (palette decoding, block counting, heightmaps, structures, ticks) on
 * the server thread, about a millisecond each.  Generation that keeps loading
 * neighbours from disk, such as a pregenerator walking outward ring by ring,
 * is then limited by the server thread.</p>
 *
 * <p>Here {@code ChunkSerializer.read} runs on a worker as soon as the bytes
 * arrive.  The one step in it that touches server-thread state, the
 * point-of-interest consistency check, is recorded instead of run.  When the
 * server thread reaches its own call of {@code read} for the same tag it gets
 * the finished chunk, after running the recorded checks there.  If the
 * off-thread read failed, nothing is kept and the server thread reads the tag
 * itself, so failures are reported exactly as before.</p>
 */
public final class AsyncChunkLoad {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellurium.asyncChunkLoad", "true"));

    /** A chunk read ahead of time, with the consistency checks its read skipped. */
    public record Prepared(ProtoChunk chunk, List<SectionPos> positions, List<LevelChunkSection> sections) {}

    private record Deferred(List<SectionPos> positions, List<LevelChunkSection> sections) {}

    /** Keyed by the tag's identity; an entry whose tag is never read on the server thread goes with the tag. */
    private static final ConcurrentMap<CompoundTag, Prepared> PREPARED = new MapMaker().weakKeys().makeMap();
    private static final ThreadLocal<Deferred> COLLECTING = new ThreadLocal<>();

    private AsyncChunkLoad() {}

    /** Worker thread: read the chunk now and keep it for the server thread. */
    public static void prepare(ServerLevel level, PoiManager poiManager, RegionStorageInfo storageInfo, ChunkPos pos, CompoundTag tag) {
        Deferred deferred = new Deferred(new ArrayList<>(), new ArrayList<>());
        COLLECTING.set(deferred);
        try {
            ProtoChunk chunk = ChunkSerializer.read(level, poiManager, storageInfo, pos, tag);
            PREPARED.put(tag, new Prepared(chunk, deferred.positions, deferred.sections));
        } catch (Throwable failure) {
            // The server thread's own read of this tag reports the problem the way it always did.
        } finally {
            COLLECTING.remove();
        }
    }

    /** The chunk read ahead for this tag, or null; an entry is handed out once. */
    public static Prepared take(CompoundTag tag) {
        return PREPARED.isEmpty() ? null : PREPARED.remove(tag);
    }

    /** True on a thread that is reading ahead: the consistency check was recorded and must not run here. */
    public static boolean defer(SectionPos position, LevelChunkSection section) {
        Deferred deferred = COLLECTING.get();
        if (deferred == null) return false;
        deferred.positions.add(position);
        deferred.sections.add(section);
        return true;
    }
}
