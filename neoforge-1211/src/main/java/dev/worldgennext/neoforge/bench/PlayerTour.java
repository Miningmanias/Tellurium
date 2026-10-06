// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.bench;

import dev.worldgennext.neoforge.version.Version;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Developer check of ordinary play: moves the first player to join across ungenerated terrain in steps, the
 * way someone flying with an elytra would load it, and reports how the server kept up.
 *
 * <p>{@code -Dworldgennext.bench.tour=<steps>} starts it; each step moves the player
 * {@code worldgennext.bench.tourStride} blocks (default 96) along x every
 * {@code worldgennext.bench.tourMillis} ms (default 500).  Chunks around the player are generated, sent,
 * ticked, and unloaded behind them through the normal player-ticket path, which the pregenerator and the
 * benchmark driver do not use.</p>
 */
public final class PlayerTour {
    private static final Logger LOG = LoggerFactory.getLogger("worldgennext-bench");

    private PlayerTour() {}

    public static boolean requested() {
        return Integer.getInteger("worldgennext.bench.tour", 0) > 0;
    }

    public static void startAsync(MinecraftServer server) {
        int steps = Integer.getInteger("worldgennext.bench.tour", 0);
        int stride = Integer.getInteger("worldgennext.bench.tourStride", 96);
        long pause = Long.getLong("worldgennext.bench.tourMillis", 500L);
        Thread thread = new Thread(() -> run(server, steps, stride, pause), "worldgennext-bench-tour");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run(MinecraftServer server, int steps, int stride, long pause) {
        try {
            ServerPlayer player = null;
            for (int waited = 0; waited < 600 && player == null; waited++) {
                Thread.sleep(500);
                player = CompletableFuture.supplyAsync(() -> server.getPlayerList().getPlayers().stream().findFirst().orElse(null), server).join();
            }
            if (player == null) {
                LOG.error("WorldgenNext player tour FAIL: no player joined");
                return;
            }
            ServerPlayer traveller = player;
            double startX = CompletableFuture.supplyAsync(traveller::getX, server).join();
            double z = CompletableFuture.supplyAsync(traveller::getZ, server).join();
            LOG.info("WorldgenNext player tour: {} steps of {} blocks every {} ms from x={}", steps, stride, pause, (int) startX);
            long worstTickNanos = 0;
            long begin = System.nanoTime();
            for (int step = 1; step <= steps; step++) {
                double x = startX + (double) step * stride;
                CompletableFuture.runAsync(() -> {
                    ServerLevel level = traveller.serverLevel();
                    Version.teleport(traveller, level, x, 200.0, z, traveller.getYRot(), traveller.getXRot());
                }, server).join();
                Thread.sleep(pause);
                worstTickNanos = Math.max(worstTickNanos, max(server.getTickTimesNanos()));
            }
            // Let the last chunks arrive and the ones left behind unload and save.
            Thread.sleep(5000);
            int loaded = CompletableFuture.supplyAsync(() -> traveller.serverLevel().getChunkSource().getLoadedChunksCount(), server).join();
            LOG.info(String.format(Locale.ROOT, "WorldgenNext player tour PASS: %d steps, %,d blocks in %.1f s; average tick %.1f ms,"
                            + " longest recent tick %.0f ms; %,d chunks loaded at the end",
                    steps, (long) steps * stride, (System.nanoTime() - begin) / 1e9, server.getAverageTickTimeNanos() / 1e6,
                    worstTickNanos / 1e6, loaded));
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            LOG.error("WorldgenNext player tour FAIL", failure);
        }
    }

    private static long max(long[] values) {
        long best = 0;
        for (long value : values) best = Math.max(best, value);
        return best;
    }
}
