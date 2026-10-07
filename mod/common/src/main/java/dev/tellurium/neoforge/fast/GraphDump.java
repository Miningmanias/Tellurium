// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.fast;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Developer diagnostic: {@code -Dtellurium.fast.dumpGraph=<file>} writes every level's captured router statistics. */
public final class GraphDump {
    private GraphDump() {}

    /** @return true when the dump asked for the server to halt afterwards ({@code tellurium.fast.dumpOnly}) */
    public static boolean runIfRequested(MinecraftServer server) {
        String target = System.getProperty("tellurium.fast.dumpGraph", "").trim();
        if (target.isEmpty()) return false;
        StringBuilder text = new StringBuilder();
        for (ServerLevel level : server.getAllLevels()) {
            text.append("=== ").append(level.dimension().location()).append('\n');
            try {
                text.append(GraphStatistics.describe(FastRouterCapture.capture(level)));
            } catch (RuntimeException failure) {
                text.append("capture failed: ").append(failure).append('\n');
            }
        }
        try {
            Files.writeString(Path.of(target), text.toString());
        } catch (IOException failure) {
            LoggerFactory.getLogger("tellurium-fast").error("Graph dump failed", failure);
        }
        if (!Boolean.getBoolean("tellurium.fast.dumpOnly")) return false;
        server.halt(false);
        return true;
    }
}
