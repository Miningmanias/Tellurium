// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.pregen;

import dev.worldgennext.neoforge.command.StatusReport;
import dev.worldgennext.neoforge.config.UserSettings;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.world.level.Level;

/**
 * Generates a square of chunks to completion, a region-sized tile at a time
 * (see {@link TileOrder}).  One job runs at a time.  It asks the chunk system
 * for full chunks exactly as a player's view distance would, with a bounded
 * number in flight, so everything it produces goes through the normal
 * generation and save paths.
 *
 * <p>Progress is written to {@code worldgennext-pregen.properties} in the
 * world folder, so a job that was paused or cut short by a restart can be
 * resumed.</p>
 */
public final class Pregenerator {
    private static final Logger LOG = LoggerFactory.getLogger("worldgennext");
    private static final TicketType<ChunkPos> TICKET =
            TicketType.create("worldgennext_pregen", Comparator.comparingLong(ChunkPos::toLong));
    private static final String STATE_FILE = "worldgennext-pregen.properties";
    /** Largest radius accepted, in chunks (a 160,000-block-wide square). */
    public static final int MAX_RADIUS = 5000;

    /** What to generate: a square of chunks, {@code radius} chunks in every direction from the centre chunk. */
    public record Area(ResourceKey<Level> dimension, int centerX, int centerZ, int radius) {
        public long chunks() {
            long side = 2L * radius + 1;
            return side * side;
        }
    }

    private static volatile Job CURRENT;

    private Pregenerator() {}

    /** Starts a job; returns a message for the caller, and reports progress to {@code feedback}. */
    public static synchronized String start(MinecraftServer server, Area area, long firstIndex, Consumer<String> feedback) {
        Job running = CURRENT;
        if (running != null && !running.finished) {
            return "A pregeneration is already " + (running.paused ? "paused" : "running") + ": " + running.progressLine()
                    + ". Use /worldgennext pregen stop first.";
        }
        if (area.radius() < 0 || area.radius() > MAX_RADIUS) return "Radius must be between 0 and " + MAX_RADIUS + " chunks.";
        ServerLevel level = server.getLevel(area.dimension());
        if (level == null) return "Dimension " + area.dimension().location() + " is not loaded.";
        Job job = new Job(server, level, area, Math.max(0, firstIndex), feedback);
        CURRENT = job;
        job.thread.start();
        StringBuilder message = new StringBuilder(String.format(Locale.ROOT,
                "Pregenerating %,d chunks in %s (%,d blocks across, centred on chunk %d, %d)%s.",
                area.chunks(), area.dimension().location(), (2L * area.radius() + 1) * 16, area.centerX(), area.centerZ(),
                firstIndex > 0 ? String.format(Locale.ROOT, ", continuing at %,d", firstIndex) : ""));
        if (job.inFlight < UserSettings.get().pregenInFlight()) {
            message.append(String.format(Locale.ROOT, " Working on %,d chunks at a time instead of %,d because the heap is %,d MB;"
                    + " give the game more memory for more speed.", job.inFlight, UserSettings.get().pregenInFlight(),
                    Runtime.getRuntime().maxMemory() >> 20));
        }
        String syncWrites = StatusReport.syncWritesTip(server);
        if (syncWrites != null) message.append(" Tip: ").append(syncWrites);
        return message.toString();
    }

    /** Continues the job recorded in the world folder, or a paused one. */
    public static synchronized String resume(MinecraftServer server, Consumer<String> feedback) {
        Job running = CURRENT;
        if (running != null && !running.finished) {
            if (!running.paused) return "Pregeneration is already running: " + running.progressLine();
            running.feedback = feedback;
            running.paused = false;
            return "Pregeneration resumed: " + running.progressLine();
        }
        Path file = stateFile(server);
        if (Files.notExists(file)) return "There is no unfinished pregeneration to resume.";
        try {
            Properties saved = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                saved.load(reader);
            }
            Area area = new Area(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(saved.getProperty("dimension"))),
                    Integer.parseInt(saved.getProperty("centerX")), Integer.parseInt(saved.getProperty("centerZ")),
                    Integer.parseInt(saved.getProperty("radius")));
            return start(server, area, Long.parseLong(saved.getProperty("next")), feedback);
        } catch (IOException | RuntimeException failure) {
            return "The saved pregeneration state in " + file + " could not be read (" + failure + "). Start a new one.";
        }
    }

    public static synchronized String pause() {
        Job job = CURRENT;
        if (job == null || job.finished) return "No pregeneration is running.";
        job.paused = true;
        job.saveState();
        return "Pregeneration paused: " + job.progressLine() + ". Chunks already in progress will finish.";
    }

    /** Stops and forgets the job, including one that only exists as saved state. */
    public static synchronized String stop(MinecraftServer server) {
        Job job = CURRENT;
        CURRENT = null;
        boolean hadState = false;
        try {
            hadState = Files.deleteIfExists(stateFile(server));
        } catch (IOException failure) {
            LOG.warn("Could not delete {}: {}", stateFile(server), failure.toString());
        }
        if (job == null || job.finished) return hadState ? "Discarded the unfinished pregeneration." : "No pregeneration is running.";
        job.cancelled = true;
        job.thread.interrupt();
        return "Pregeneration stopped: " + job.progressLine(false) + ". Chunks generated so far are kept.";
    }

    /** One line for the status command, or null when there is nothing to say. */
    public static String status(MinecraftServer server) {
        Job job = CURRENT;
        if (job != null && !job.finished) return (job.paused ? "paused, " : "running, ") + job.progressLine();
        return Files.exists(stateFile(server)) ? "unfinished job on disk; /worldgennext pregen resume continues it" : null;
    }

    /** Server start: say so if an earlier job was cut short. */
    public static void serverStarted(MinecraftServer server) {
        if (Files.exists(stateFile(server))) {
            LOG.info("An unfinished pregeneration was found in the world folder. Run '/worldgennext pregen resume' to continue it "
                    + "or '/worldgennext pregen stop' to discard it.");
        }
    }

    /** Server stop: keep the progress file, stop asking for chunks. */
    public static synchronized void serverStopping() {
        Job job = CURRENT;
        CURRENT = null;
        if (job == null || job.finished) return;
        job.saveState();
        job.cancelled = true;
        job.keepState = true;
        job.thread.interrupt();
    }

    private static Path stateFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(STATE_FILE);
    }

    private static final class Job {
        final MinecraftServer server;
        final ServerLevel level;
        final Area area;
        final TileOrder order;
        final long total;
        final long firstIndex;
        final int inFlight;
        final Thread thread;
        final AtomicLong completed = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        volatile Consumer<String> feedback;
        volatile long submitted;
        volatile boolean paused, cancelled, finished, keepState;
        volatile double recentRate;
        final long startNanos = System.nanoTime();

        Job(MinecraftServer server, ServerLevel level, Area area, long firstIndex, Consumer<String> feedback) {
            this.server = server;
            this.level = level;
            this.area = area;
            this.order = new TileOrder(area.centerX(), area.centerZ(), area.radius());
            this.total = order.size();
            this.firstIndex = Math.min(firstIndex, total);
            this.submitted = this.firstIndex;
            this.inFlight = InFlightWindow.size(UserSettings.get().pregenInFlight(), Runtime.getRuntime().maxMemory());
            this.feedback = feedback;
            this.thread = new Thread(this::run, "worldgennext-pregen");
            this.thread.setDaemon(true);
        }

        long done() {
            return firstIndex + completed.get() + failed.get();
        }

        String progressLine() {
            return progressLine(true);
        }

        /** @param withEstimate false for a job that is not going to continue */
        String progressLine(boolean withEstimate) {
            long done = done();
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%,d of %,d chunks (%.1f%%)", done, total,
                    total == 0 ? 100.0 : 100.0 * done / total));
            double rate = recentRate;
            if (withEstimate && rate > 0 && !paused) {
                line.append(String.format(Locale.ROOT, ", %,.0f chunks/s, about %s left", rate, duration((long) ((total - done) / rate))));
            }
            if (failed.get() > 0) line.append(String.format(Locale.ROOT, ", %,d failed", failed.get()));
            return line.toString();
        }

        static String duration(long seconds) {
            if (seconds < 90) return seconds + " s";
            if (seconds < 5400) return (seconds + 30) / 60 + " min";
            return String.format(Locale.ROOT, "%.1f h", seconds / 3600.0);
        }

        void run() {
            ServerChunkCache cache = level.getChunkSource();
            Semaphore permits = new Semaphore(inFlight);
            long reportEvery = TimeUnit.SECONDS.toNanos(UserSettings.get().pregenProgressSeconds());
            long lastReport = System.nanoTime();
            long lastDone = done();
            try {
                long index = firstIndex;
                while (!cancelled && (index < total || permits.availablePermits() < inFlight)) {
                    boolean submit = index < total && !paused;
                    if (submit && permits.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                        long packed = order.at(index++);
                        submitted = index;
                        ChunkPos pos = new ChunkPos((int) packed, (int) (packed >> 32));
                        server.execute(() -> cache.addRegionTicket(TICKET, pos, 0, pos));
                        cache.getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true).whenComplete((result, error) -> {
                            if (error == null && result != null && result.isSuccess()) completed.incrementAndGet();
                            else failed.incrementAndGet();
                            server.execute(() -> cache.removeRegionTicket(TICKET, pos, 0, pos));
                            permits.release();
                        });
                    } else if (!submit) {
                        Thread.sleep(200);
                    }
                    long now = System.nanoTime();
                    if (now - lastReport >= reportEvery) {
                        long done = done();
                        recentRate = (done - lastDone) / ((now - lastReport) / 1e9);
                        lastReport = now;
                        lastDone = done;
                        saveState();
                        if (!paused) say("Pregeneration: " + progressLine());
                    }
                }
            } catch (InterruptedException stop) {
                // cancelled or server stopping
            } catch (RuntimeException failure) {
                LOG.error("Pregeneration stopped by an error", failure);
                say("Pregeneration stopped by an error: " + failure + ". Progress is saved; /worldgennext pregen resume continues.");
                keepState = true;
                cancelled = true;
            }
            finished = true;
            if (cancelled) {
                if (!keepState) deleteState();
                return;
            }
            deleteState();
            double seconds = (System.nanoTime() - startNanos) / 1e9;
            long generated = completed.get() + failed.get();
            say(String.format(Locale.ROOT, "Pregeneration finished: %,d chunks in %s (%,.0f chunks/s)%s. Chunks are written as they unload;"
                            + " '/save-all flush' forces the rest to disk now.",
                    generated, duration((long) seconds), generated / Math.max(seconds, 0.001),
                    failed.get() > 0 ? String.format(Locale.ROOT, ", %,d failed (see the log)", failed.get()) : ""));
            if (Boolean.getBoolean("worldgennext.pregen.stopServerWhenDone")) {
                LOG.info("worldgennext.pregen.stopServerWhenDone is set: stopping the server");
                server.halt(false);
            }
        }

        void say(String text) {
            LOG.info(text);
            Consumer<String> sink = feedback;
            if (sink != null) {
                // Command feedback belongs on the server thread.
                server.execute(() -> {
                    try {
                        sink.accept(text);
                    } catch (RuntimeException gone) {
                        feedback = null;
                    }
                });
            }
        }

        /** Chunks complete out of order; restarting two windows back re-requests only chunks that already exist. */
        void saveState() {
            Properties state = new Properties();
            state.setProperty("dimension", area.dimension().location().toString());
            state.setProperty("centerX", Integer.toString(area.centerX()));
            state.setProperty("centerZ", Integer.toString(area.centerZ()));
            state.setProperty("radius", Integer.toString(area.radius()));
            state.setProperty("next", Long.toString(Math.max(0, submitted - 2L * inFlight)));
            Path file = stateFile(server);
            try {
                Path temporary = file.resolveSibling(STATE_FILE + ".tmp");
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    state.store(writer, "WorldgenNext pregeneration progress; delete to forget the job");
                }
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                LOG.warn("Could not save pregeneration progress to {}: {}", file, failure.toString());
            }
        }

        void deleteState() {
            try {
                Files.deleteIfExists(stateFile(server));
            } catch (IOException failure) {
                LOG.warn("Could not delete {}: {}", stateFile(server), failure.toString());
            }
        }
    }
}
