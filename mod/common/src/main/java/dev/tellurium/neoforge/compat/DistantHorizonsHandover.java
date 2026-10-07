// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.compat;

import dev.tellurium.neoforge.version.Version;

import dev.tellurium.neoforge.loader.Loader;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stops Distant Horizons from building the same chunk's level-of-detail data twice.
 *
 * <p>A chunk handed to Distant Horizons by {@link DistantHorizonsBridge} is a real server chunk, so Distant
 * Horizons also hears about it the way it hears about any chunk: once when the server loads it and once when
 * the server saves it, and it builds the chunk's data again for the first of those.  That second build used
 * about half of Distant Horizons' worker time in this mod's measurements.  Distant Horizons keeps a set of
 * chunk positions whose events it ignores; this class puts a handed-over chunk in that set until the chunk
 * has been saved once or unloaded.</p>
 *
 * <p>Skipping an event is only right while the chunk is still what was handed over, so a chunk is never held
 * in the set (or is taken out of it at once) when it is already loaded, when a player is within view distance
 * of it or when it is force-loaded: those are the chunks that tick and that players change.  Any later change is saved again and
 * reaches Distant Horizons normally.</p>
 *
 * <p>The set is not part of Distant Horizons' API.  Everything here is looked up by name and, if a name is
 * missing in some other version, this class turns itself off and Distant Horizons simply does the extra
 * work.</p>
 */
final class DistantHorizonsHandover {
    static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellurium.dh.skipSecondBuild", "true"));
    private static final Logger LOG = LoggerFactory.getLogger("tellurium");
    /** After the first save or the unload has been seen, how long the chunk stays ignored (covers that event itself). */
    private static final long AFTER_SAVE_NANOS = 5_000_000_000L;
    /** Whatever happens, a chunk is not ignored for longer than this. */
    private static final long LONGEST_NANOS = 600_000_000_000L;

    private static volatile boolean available;
    private static volatile boolean listening;
    private static MethodHandle managerFor;   // (Object levelWrapper) -> Object manager, may return null
    private static MethodHandle newChunkPos;  // (int, int) -> Object
    private static MethodHandle ignore;       // (Object manager, Object chunkPos) -> void
    private static MethodHandle unignore;     // (Object manager, Object chunkPos) -> void

    private static final Map<ServerLevel, DistantHorizonsHandover> BY_LEVEL = new ConcurrentHashMap<>();
    static final AtomicLong SKIPPED = new AtomicLong();

    private final ServerLevel level;
    private final Object levelWrapper;
    /** Chunk position to the time after which it is taken out again. */
    private final ConcurrentHashMap<Long, Long> ignored = new ConcurrentHashMap<>();
    /** Chunk positions of the players in this level, refreshed once a second on the server thread. */
    private volatile long[] players = new long[0];
    private volatile int reach = 12;

    private DistantHorizonsHandover(ServerLevel level, Object levelWrapper) {
        this.level = level;
        this.levelWrapper = levelWrapper;
    }

    /** Looks the internals up once.  Called from the bridge's thread before any level is registered. */
    static void prepare() {
        if (!ENABLED || available) return;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> managers = Class.forName("com.seibel.distanthorizons.core.api.internal.chunkUpdating.WorldChunkUpdateManager");
            Class<?> manager = Class.forName("com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkUpdateQueueManager");
            Class<?> wrapper = Class.forName("com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper");
            Class<?> chunkPos = Class.forName("com.seibel.distanthorizons.core.pos.DhChunkPos");
            Object instance = managers.getField("INSTANCE").get(null);
            managerFor = lookup.findVirtual(managers, "getByLevelWrapper", MethodType.methodType(manager, wrapper))
                    .bindTo(instance).asType(MethodType.methodType(Object.class, Object.class));
            newChunkPos = lookup.findConstructor(chunkPos, MethodType.methodType(void.class, int.class, int.class))
                    .asType(MethodType.methodType(Object.class, int.class, int.class));
            MethodType change = MethodType.methodType(void.class, chunkPos);
            MethodType erased = MethodType.methodType(void.class, Object.class, Object.class);
            ignore = lookup.findVirtual(manager, "addPosToIgnore", change).asType(erased);
            unignore = lookup.findVirtual(manager, "removePosToIgnore", change).asType(erased);
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) {
            LOG.info("This version of Distant Horizons does not have the hooks this mod uses to avoid building each chunk's"
                    + " distant terrain twice ({}); it will do that extra work", missing.toString());
        }
    }

    /** One per level Distant Horizons has loaded; null when the internals are not there. */
    static DistantHorizonsHandover forLevel(ServerLevel level, Object levelWrapper) {
        if (!available) return null;
        if (!listening) {
            synchronized (DistantHorizonsHandover.class) {
                if (!listening) {
                    // Unfinished chunks are saved too, but that is not the save Distant Horizons would have acted
                    // on; the loader reports full chunks only.  Where it reports no saves at all, a chunk that
                    // came from disk unchanged is let go when it unloads, like one that was saved.
                    Loader.onFullChunkSaved(DistantHorizonsHandover::letGoSoon);
                    Loader.onChunkUnloaded(DistantHorizonsHandover::letGoSoon);
                    Loader.onServerTickEnd(DistantHorizonsHandover::serverTicked);
                    listening = true;
                }
            }
        }
        DistantHorizonsHandover handover = new DistantHorizonsHandover(level, levelWrapper);
        BY_LEVEL.put(level, handover);
        return handover;
    }

    /** Chunks whose own events Distant Horizons is currently ignoring, over all levels. */
    static int waitingForFirstSave() {
        int count = 0;
        for (DistantHorizonsHandover handover : BY_LEVEL.values()) count += handover.ignored.size();
        return count;
    }

    static void serverStopping() {
        BY_LEVEL.clear();
    }

    /** Any thread.  Asks Distant Horizons to ignore this chunk's own events until it has been saved once. */
    void handingOver(ChunkPos pos) {
        if (nearPlayer(pos.x, pos.z)) return;
        // A chunk the server already has loaded has had its load event, and may never be saved again.
        LightChunk loaded = level.getChunkSource().getChunkForLighting(pos.x, pos.z);
        if (loaded instanceof LevelChunk || loaded instanceof ImposterProtoChunk) return;
        if (ignored.put(pos.toLong(), System.nanoTime() + LONGEST_NANOS) == null) {
            if (change(ignore, pos.x, pos.z)) SKIPPED.incrementAndGet();
            else ignored.remove(pos.toLong());
        }
    }

    /** Any thread.  The chunk could not be handed over after all. */
    void notHandedOver(ChunkPos pos) {
        if (ignored.remove(pos.toLong()) != null) change(unignore, pos.x, pos.z);
    }

    private boolean nearPlayer(int x, int z) {
        int reach = this.reach;
        for (long player : players) {
            if (Math.abs(ChunkPos.getX(player) - x) <= reach && Math.abs(ChunkPos.getZ(player) - z) <= reach) return true;
        }
        return false;
    }

    private boolean change(MethodHandle how, int x, int z) {
        try {
            Object manager = managerFor.invoke(levelWrapper);
            if (manager == null) return false;
            how.invoke(manager, newChunkPos.invoke(x, z));
            return true;
        } catch (Throwable failure) {
            if (available) {
                available = false;
                LOG.warn("Could not tell Distant Horizons which chunks it already has ({}); it will build them twice", failure.toString());
            }
            return false;
        }
    }

    /** Server thread.  Once saved or unloaded the chunk cannot change unnoticed any more. */
    private static void letGoSoon(ServerLevel level, ChunkPos chunk) {
        DistantHorizonsHandover handover = BY_LEVEL.get(level);
        if (handover == null) return;
        long soon = System.nanoTime() + AFTER_SAVE_NANOS;
        handover.ignored.computeIfPresent(chunk.toLong(), (pos, until) -> Math.min(until, soon));
    }

    private static void serverTicked(MinecraftServer server) {
        if (server.getTickCount() % 20 != 0) return;
        for (DistantHorizonsHandover handover : BY_LEVEL.values()) handover.sweep(server);
    }

    /** Server thread, once a second: lets go of chunks that were saved, that tick, or that a player can reach. */
    private void sweep(MinecraftServer server) {
        int reach = server.getPlayerList().getViewDistance() + 2;
        this.reach = reach;
        long[] positions = new long[level.players().size()];
        int count = 0;
        for (ServerPlayer player : level.players()) positions[count++] = player.chunkPosition().toLong();
        players = positions;
        if (ignored.isEmpty()) return;
        for (long player : positions) {
            int centreX = ChunkPos.getX(player), centreZ = ChunkPos.getZ(player);
            for (int z = centreZ - reach; z <= centreZ + reach; z++) {
                for (int x = centreX - reach; x <= centreX + reach; x++) release(x, z);
            }
        }
        Version.forcedChunks(level).forEach(forced -> release(ChunkPos.getX(forced), ChunkPos.getZ(forced)));
        long now = System.nanoTime();
        Iterator<Map.Entry<Long, Long>> entries = ignored.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Long, Long> entry = entries.next();
            if (now - entry.getValue() >= 0) {
                entries.remove();
                change(unignore, ChunkPos.getX(entry.getKey()), ChunkPos.getZ(entry.getKey()));
            }
        }
    }

    private void release(int x, int z) {
        if (ignored.remove(ChunkPos.asLong(x, z)) != null) change(unignore, x, z);
    }
}
