// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.command;

import dev.worldgennext.neoforge.WorldgenNextMod;
import dev.worldgennext.neoforge.config.UserSettings;
import dev.worldgennext.neoforge.fast.FastNoiseEngine;
import dev.worldgennext.neoforge.pregen.Pregenerator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What the mod is doing, in words a server owner can act on.  Shown by /worldgennext status and at startup. */
public final class StatusReport {
    private StatusReport() {}

    public static List<String> lines(MinecraftServer server) {
        UserSettings settings = UserSettings.get();
        List<String> lines = new ArrayList<>();
        lines.add("WorldgenNext " + WorldgenNextMod.VERSION);
        if (!settings.enabled()) {
            lines.add("Disabled (enabled = false in " + settings.file() + "): chunks generate and save as without the mod.");
            return lines;
        }

        String device = FastNoiseEngine.deviceName();
        if (FastNoiseEngine.MODE == FastNoiseEngine.GpuMode.OFF) {
            lines.add("GPU: off (gpu.mode). CPU-side optimizations are active.");
        } else if (device != null) {
            lines.add("GPU: " + device + " (mode " + FastNoiseEngine.MODE.name().toLowerCase(Locale.ROOT) + ")");
        } else if (FastNoiseEngine.unavailableReason() != null) {
            lines.add("GPU: not available, terrain generates on the CPU. Reason: " + FastNoiseEngine.unavailableReason());
        } else {
            lines.add("GPU: not started yet");
        }
        for (FastNoiseEngine.DimensionStatus dimension : FastNoiseEngine.dimensions()) {
            lines.add("  " + dimension.dimension() + ": " + (dimension.terrain()
                    ? (dimension.surface() ? "terrain and surface on the GPU" : "terrain on the GPU, surface on the CPU")
                    + " (" + dimension.detail() + ")" : dimension.detail()));
        }
        FastNoiseEngine.Counters counters = FastNoiseEngine.counters();
        if (device != null) {
            lines.add(String.format(Locale.ROOT, "Chunks since start: %,d on the GPU, %,d on the CPU, %,d redone on the CPU after a GPU bail-out",
                    counters.gpu(), counters.cpuFallback(), counters.bail()));
        }
        lines.add("Parallel generation steps: " + onOff(settings.parallelSteps()) + ". Background saving: "
                + (settings.asyncSaving() ? "on, compression level " + settings.compressionLevel() : "off") + ".");
        String pregen = Pregenerator.status(server);
        if (pregen != null) lines.add("Pregeneration: " + pregen);

        if (!ModList.get().isLoaded("scalablelux")) {
            lines.add("Tip: ScalableLux is not installed. Lighting then runs on vanilla's single light thread; the published"
                    + " throughput figures were measured with ScalableLux.");
        }
        if (server instanceof DedicatedServer dedicated && dedicated.getProperties().syncChunkWrites) {
            lines.add("Tip: sync-chunk-writes=true (server.properties) makes every chunk write wait for the disk and limits"
                    + " sustained generation speed.");
        }
        for (String problem : settings.problems()) lines.add("Config: " + problem);
        lines.add("Config file: " + settings.file());
        return lines;
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }
}
