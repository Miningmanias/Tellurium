// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.compat;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates the terrain around players that Voxy shows as distant terrain, and hands it to Voxy.
 *
 * <p>Voxy only draws chunks it has been given: by itself it shows what the player has already been near.
 * When Voxy runs in the same game as the server (singleplayer, or the host of a LAN world), this class
 * generates the chunks around each player out to a set distance with the server's own chunk generation, nearest
 * first, and passes each finished chunk to Voxy's ingest service, the same entry Voxy uses for chunks the
 * client receives.  Which chunks have been handed over is remembered in the world folder, so nothing is
 * generated or loaded twice across sessions.</p>
 *
 * <p>Voxy is not a build dependency and is not changed; its two entry points are looked up by name.  On a
 * dedicated server there is no Voxy in the same process and this class does nothing.</p>
 */
public final class VoxyBridge {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("worldgennext.voxy.generate", "true"));
    /** How far around each player terrain is generated for Voxy, in chunks. */
    private static final int RADIUS = Math.max(8, Math.min(1024, Integer.getInteger("worldgennext.voxy.radius", 128)));
    /** Chunks being generated for Voxy at one time: the pregenerator's rule of one per 12 MB of heap, at most 1,024. */
    private static final int IN_FLIGHT = Math.max(16, Integer.getInteger("worldgennext.voxy.inFlight",
            (int) Math.min(1024, Runtime.getRuntime().maxMemory() / (12L << 20))));
    /** Voxy's ingest queue has no limit of its own; no more chunks are started while it holds this many sections. */
    private static final int QUEUE_LIMIT = Math.max(256, Integer.getInteger("worldgennext.voxy.queueLimit", 8192));
    /** No chunks are started while more chunks than this are loaded in the level. */
    private static final int LOADED_LIMIT = Integer.getInteger("worldgennext.voxy.loadedLimit", 8 * IN_FLIGHT + 8192);
    private static final AtomicLong HELD_BACK = new AtomicLong();
    private static final boolean PERIODIC_LOG = Boolean.getBoolean("worldgennext.voxy.log");
    private static final Logger LOG = LoggerFactory.getLogger("worldgennext");
    private static final TicketType<ChunkPos> TICKET =
            TicketType.create("worldgennext_voxy", Comparator.comparingLong(ChunkPos::toLong));

    private static volatile boolean looked, available, listening;
    private static final java.util.concurrent.ExecutorService HAND_OVER = java.util.concurrent.Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 4), task -> {
                Thread thread = new Thread(task, "worldgennext-voxy-handover");
                thread.setDaemon(true);
                return thread;
            });
    private static final java.util.concurrent.ExecutorService REQUESTS = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "worldgennext-voxy-requests");
        thread.setDaemon(true);
        return thread;
    });
    private static MethodHandle ingestChunk;     // (LevelChunk)boolean
    private static MethodHandle instance;        // ()Object
    private static MethodHandle ingestService;   // (Object)Object
    private static MethodHandle queued;          // (Object)int

    private static final Map<ServerLevel, Feeder> FEEDERS = new IdentityHashMap<>();
    private static final AtomicLong HANDED_OVER = new AtomicLong();
    private static final AtomicLong REFUSED = new AtomicLong();
    private static long startedNanos;
    private static volatile boolean otherGenerator;
    private static volatile boolean failureReported;

    private VoxyBridge() {}

    /** Looks Voxy's entry points up; false when Voxy is not in this process. */
    private static boolean available() {
        if (!looked) {
            synchronized (VoxyBridge.class) {
                if (!looked) {
                    try {
                        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                        Class<?> service = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService");
                        Class<?> common = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon");
                        Class<?> voxy = Class.forName("me.cortex.voxy.commonImpl.VoxyInstance");
                        ingestChunk = lookup.findStatic(service, "tryAutoIngestChunk", MethodType.methodType(boolean.class, LevelChunk.class));
                        instance = lookup.findStatic(common, "getInstance", MethodType.methodType(voxy)).asType(MethodType.methodType(Object.class));
                        ingestService = lookup.findVirtual(voxy, "getIngestService", MethodType.methodType(service))
                                .asType(MethodType.methodType(Object.class, Object.class));
                        queued = lookup.findVirtual(service, "getTaskCount", MethodType.methodType(int.class))
                                .asType(MethodType.methodType(int.class, Object.class));
                        available = true;
                    } catch (ClassNotFoundException absent) {
                        // Voxy is not installed, or this is a dedicated server.
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError changed) {
                        LOG.warn("Voxy is installed but its ingest entry points were not found ({}); no terrain is generated for it",
                                changed.toString());
                    }
                    looked = true;
                }
            }
        }
        return available;
    }

    public static void serverStarted(MinecraftServer server) {
        if (!ENABLED || !available()) return;
        if (net.neoforged.fml.ModList.get().isLoaded("voxyworldgenv2")) {
            otherGenerator = true;
            LOG.info("Voxy WorldGen is installed and generates terrain for Voxy; this mod leaves that to it. Remove it to use this mod's"
                    + " generation for Voxy instead (about 2,000 chunks/s on the reference machine).");
            return;
        }
        otherGenerator = false;
        HANDED_OVER.set(0);
        REFUSED.set(0);
        startedNanos = System.nanoTime();
        if (!listening) {
            NeoForge.EVENT_BUS.addListener(VoxyBridge::serverTicked);
            listening = true;
        }
        LOG.info("Voxy: terrain within {} chunks of each player is generated and handed to it (voxy.generate = false turns this off)", RADIUS);
    }

    public static void serverStopping(MinecraftServer server) {
        synchronized (FEEDERS) {
            for (Feeder feeder : FEEDERS.values()) feeder.save();
            FEEDERS.clear();
        }
    }

    /**
     * Heap in use right after the most recent garbage collection of each pool: what is really held, as opposed
     * to the momentary figure, most of which is garbage waiting for the next collection.
     */
    private static long liveHeap() {
        long live = 0;
        for (java.lang.management.MemoryPoolMXBean pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != java.lang.management.MemoryType.HEAP) continue;
            java.lang.management.MemoryUsage after = pool.getCollectionUsage();
            if (after != null) live += after.getUsed();
        }
        return live;
    }

    /** Sections waiting in Voxy's ingest queue, or -1 while Voxy has no world open. */
    private static int queueLength() {
        try {
            Object voxy = (Object) instance.invokeExact();
            if (voxy == null) return -1;
            Object service = (Object) ingestService.invokeExact(voxy);
            return service == null ? -1 : (int) queued.invokeExact(service);
        } catch (Throwable failure) {
            if (!failureReported) {
                failureReported = true;
                LOG.warn("Could not read Voxy's ingest queue ({}); no terrain is generated for it", failure.toString());
            }
            return -1;
        }
    }

    private static void serverTicked(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int waiting = queueLength();
        if (waiting < 0) {
            if (PERIODIC_LOG && server.getTickCount() % 200 == 0) LOG.info("Voxy has no world open yet");
            return;
        }
        synchronized (FEEDERS) {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.players().isEmpty() && !FEEDERS.containsKey(level)) continue;
                FEEDERS.computeIfAbsent(level, Feeder::new).tick(server, waiting);
            }
            if (server.getTickCount() % 1200 == 0) {
                for (Feeder feeder : FEEDERS.values()) feeder.save();
            }
        }
        if (PERIODIC_LOG && server.getTickCount() % 200 == 0) {
            Runtime runtime = Runtime.getRuntime();
            int loaded = 0;
            for (ServerLevel level : server.getAllLevels()) loaded += level.getChunkSource().getLoadedChunksCount();
            LOG.info("{}; {} sections waiting in its queue; heap {} MB in use, {} MB after the last collection, of {} MB; {} chunks loaded;"
                    + " held back for memory {} ticks", status(), waiting, (runtime.totalMemory() - runtime.freeMemory()) >> 20,
                    liveHeap() >> 20, runtime.maxMemory() >> 20, loaded, HELD_BACK.get());
        }
    }

    /** One level's work: which chunks Voxy has, which are being generated, and where to look next. */
    private static final class Feeder {
        private final ServerLevel level;
        private final Path file;
        private final LongOpenHashSet done = new LongOpenHashSet();
        private final LongOpenHashSet inFlight = new LongOpenHashSet();
        /** Tiles whose chunks Voxy all has, so that they are not looked through again. */
        private final LongOpenHashSet finishedTiles = new LongOpenHashSet();
        private static final int TILE_SHIFT = 4, TILE = 1 << TILE_SHIFT;
        private boolean dirty;
        private int restAfterRefusalUntilTick;

        Feeder(ServerLevel level) {
            this.level = level;
            var id = level.dimension().location();
            this.file = level.getServer().getWorldPath(LevelResource.ROOT).resolve("worldgennext-voxy")
                    .resolve(id.getNamespace() + "_" + id.getPath().replace('/', '_') + ".bin");
            try {
                ChunkSetFile.read(file, done::add);
            } catch (IOException | RuntimeException unreadable) {
                LOG.warn("Could not read {} ({}); chunks already given to Voxy will be given again", file, unreadable.toString());
                done.clear();
            }
        }

        /**
         * Server thread.  Starts chunks around each player until the window is full: 16x16-chunk tiles, nearest
         * tile first, each tile filled before the next is begun.  Going outward chunk by chunk in rings would
         * keep the whole ring's surroundings loaded half-generated (tens of thousands of chunks at this radius);
         * a tile at a time keeps that to the edge of a few tiles.
         */
        void tick(MinecraftServer server, int waiting) {
            if (waiting >= QUEUE_LIMIT || server.getTickCount() < restAfterRefusalUntilTick) return;
            // Chunks leave memory only after they are saved and unloaded, which generation can outrun.
            if (level.getChunkSource().getLoadedChunksCount() > LOADED_LIMIT) {
                HELD_BACK.incrementAndGet();
                return;
            }
            int room = IN_FLIGHT - inFlight.size();
            if (room <= 0) return;
            int reach = (RADIUS + TILE - 1) / TILE;
            for (ServerPlayer player : level.players()) {
                int centreX = player.chunkPosition().x >> TILE_SHIFT, centreZ = player.chunkPosition().z >> TILE_SHIFT;
                for (int ring = 0; ring <= reach && room > 0; ring++) {
                    for (int dz = -ring; dz <= ring && room > 0; dz++) {
                        boolean edge = dz == -ring || dz == ring;
                        for (int dx = -ring; dx <= ring && room > 0; dx += edge || ring == 0 ? 1 : 2 * ring) {
                            room = fill(server, centreX + dx, centreZ + dz, room);
                        }
                    }
                }
            }
        }

        /** Starts the chunks of one tile that Voxy does not have yet; returns the room left in the window. */
        private int fill(MinecraftServer server, int tileX, int tileZ, int room) {
            long tile = ChunkPos.asLong(tileX, tileZ);
            if (finishedTiles.contains(tile)) return room;
            boolean finished = true;
            int minX = tileX << TILE_SHIFT, minZ = tileZ << TILE_SHIFT;
            for (int z = 0; z < TILE; z++) {
                for (int x = 0; x < TILE; x++) {
                    if (done.contains(ChunkPos.asLong(minX + x, minZ + z))) continue;
                    finished = false;
                    if (room > 0) room -= start(server, minX + x, minZ + z);
                }
            }
            if (finished) finishedTiles.add(tile);
            return room;
        }

        /** Requests one chunk unless Voxy has it or it is on its way; returns how many were started. */
        private int start(MinecraftServer server, int x, int z) {
            long key = ChunkPos.asLong(x, z);
            if (done.contains(key) || !inFlight.add(key)) return 0;
            ChunkPos pos = new ChunkPos(x, z);
            ServerChunkCache cache = level.getChunkSource();
            cache.addRegionTicket(TICKET, pos, 0, pos);
            // Asked from the server thread the chunk system waits for the chunk; from another thread it does not.
            // The hand-over copies the chunk's light, which is too much work for the server thread at this rate:
            // it runs on a worker while the ticket keeps the chunk loaded, and only the bookkeeping comes back.
            REQUESTS.execute(() -> cache.getChunkFuture(x, z, ChunkStatus.FULL, true).whenCompleteAsync((result, failure) -> {
                ChunkAccess chunk = failure == null ? result.orElse(null) : null;
                boolean handed = chunk instanceof LevelChunk full && handOver(full);
                server.execute(() -> {
                    if (handed) {
                        done.add(key);
                        dirty = true;
                        HANDED_OVER.incrementAndGet();
                    } else {
                        // Voxy closed its world or has ingest turned off; the chunk is asked for again, but not at once.
                        REFUSED.incrementAndGet();
                        restAfterRefusalUntilTick = server.getTickCount() + 600;
                    }
                    inFlight.remove(key);
                    cache.removeRegionTicket(TICKET, pos, 0, pos);
                });
            }, HAND_OVER));
            return 1;
        }

        private static boolean handOver(LevelChunk chunk) {
            try {
                return (boolean) ingestChunk.invokeExact(chunk);
            } catch (Throwable failure) {
                return false;
            }
        }

        void save() {
            if (!dirty) return;
            try {
                ChunkSetFile.write(file, done.size(), done.iterator());
                dirty = false;
            } catch (IOException failure) {
                LOG.warn("Could not save {}: {}", file, failure.toString());
            }
        }
    }

    /**
     * Forgets which chunks of a level were handed to Voxy, so that all of them are handed over again: for after
     * Voxy's stored data was deleted.  Server thread.  Returns how many chunks were forgotten, or -1 when this
     * class is not active.
     */
    public static int forget(ServerLevel level) {
        if (!ENABLED || !looked || !available || otherGenerator) return -1;
        synchronized (FEEDERS) {
            Feeder feeder = FEEDERS.computeIfAbsent(level, Feeder::new);
            int count = feeder.done.size();
            feeder.done.clear();
            feeder.finishedTiles.clear();
            feeder.dirty = true;
            feeder.save();
            return count;
        }
    }

    /** One line for the status report, or null when Voxy is not in this process. */
    public static String status() {
        if (!looked || !available) return null;
        if (!ENABLED) return "Voxy: installed; no terrain is generated for it (voxy.generate = false)";
        if (otherGenerator) return "Voxy: installed; Voxy WorldGen is installed too and generates its terrain, not this mod";
        double seconds = Math.max(0.001, (System.nanoTime() - startedNanos) / 1e9);
        int flying;
        synchronized (FEEDERS) {
            flying = FEEDERS.values().stream().mapToInt(feeder -> feeder.inFlight.size()).sum();
        }
        return String.format(Locale.ROOT, "Voxy: %,d chunks generated and handed over this session (%,.0f chunks/s since the server started),"
                + " %,d in progress, %,d to be asked for again; radius %d chunks", HANDED_OVER.get(), HANDED_OVER.get() / seconds, flying,
                REFUSED.get(), RADIUS);
    }
}
