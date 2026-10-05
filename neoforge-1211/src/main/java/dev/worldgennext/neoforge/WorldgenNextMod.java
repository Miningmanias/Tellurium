// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.worldgennext.neoforge.bench.ChunkThroughputBenchmark;
import dev.worldgennext.neoforge.bench.PlayerTour;
import dev.worldgennext.neoforge.command.StatusReport;
import dev.worldgennext.neoforge.config.UserSettings;
import dev.worldgennext.neoforge.fast.BaseHeightCache;
import dev.worldgennext.neoforge.fast.CavePlans;
import dev.worldgennext.neoforge.fast.ClimateColumnCache;
import dev.worldgennext.neoforge.fast.FastNoiseEngine;
import dev.worldgennext.neoforge.fast.GraphDump;
import dev.worldgennext.neoforge.legacy.StagedRoute;
import dev.worldgennext.neoforge.pregen.Pregenerator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Mod entry point: settings, server lifecycle and commands.
 *
 * <p>Chunk generation itself is changed by the mixins, which call into
 * {@code fast} (GPU terrain and the exact CPU-side shortcuts) and
 * {@code threading} (parallel steps, background saving).  The older
 * evidence-gated route lives in {@link StagedRoute} and is developer tooling.</p>
 */
@Mod(WorldgenNextMod.MOD_ID)
public final class WorldgenNextMod {
    public static final String MOD_ID = "worldgennext";
    public static final String VERSION = "0.2.0";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public WorldgenNextMod() {
        // Normally already loaded by the mixin plugin; this covers a launcher that skipped it.
        UserSettings settings = UserSettings.loadAndApply(FMLPaths.CONFIGDIR.get());
        StagedRoute.init();
        NeoForge.EVENT_BUS.addListener(WorldgenNextMod::registerCommands);
        NeoForge.EVENT_BUS.addListener(WorldgenNextMod::serverAboutToStart);
        NeoForge.EVENT_BUS.addListener(WorldgenNextMod::serverStarted);
        NeoForge.EVENT_BUS.addListener(WorldgenNextMod::serverStopping);
        NeoForge.EVENT_BUS.addListener(WorldgenNextMod::serverStopped);
        if (settings.created()) LOG.info("WorldgenNext {}: wrote default settings to {}", VERSION, settings.file());
        for (String problem : settings.problems()) LOG.warn("WorldgenNext settings: {}", problem);
        if (!settings.enabled()) {
            LOG.info("WorldgenNext {} is disabled in {}; chunks generate and save as without the mod", VERSION, settings.file());
        } else {
            LOG.info("WorldgenNext {} loaded (GPU mode {}; '/worldgennext status' shows what is active)", VERSION, settings.gpuMode());
        }
    }

    private static void serverAboutToStart(ServerAboutToStartEvent event) {
        StagedRoute.serverAboutToStart(event);
    }

    private static void serverStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        StagedRoute.serverStarted(event);
        // Developer check of what a client does when a world is closed right after opening and another is
        // opened: the engine stops while its kernels are still compiling, then starts again in the same JVM.
        if (Boolean.getBoolean("worldgennext.fast.restartCheck")) {
            FastNoiseEngine.start(server);
            FastNoiseEngine.stop();
        }
        FastNoiseEngine.start(server);
        ClimateColumnCache.start(server);
        if (GraphDump.runIfRequested(server)) return;
        Pregenerator.serverStarted(server);
        if (ChunkThroughputBenchmark.requested()) {
            String staged = StagedRoute.benchmarkRoute();
            String route = staged != null && staged.startsWith("DIAGNOSTIC") ? staged
                    : FastNoiseEngine.instance() != null ? (FastNoiseEngine.VERIFY ? "FAST_GPU_VERIFY" : "FAST_GPU")
                    : staged != null ? staged : "VANILLA_ORIGINAL";
            ChunkThroughputBenchmark.startAsync(server, route);
        }
        if (PlayerTour.requested()) PlayerTour.startAsync(server);
        // Unattended use: -Dworldgennext.pregen.autoresume=true continues a job that a restart cut short;
        // -Dworldgennext.pregen.autostart=<radius in chunks> starts one around the world spawn of the Overworld.
        Integer radius = Integer.getInteger("worldgennext.pregen.autostart");
        if (Boolean.getBoolean("worldgennext.pregen.autoresume") && Pregenerator.status(server) != null) {
            LOG.info(Pregenerator.resume(server, null));
        } else if (radius != null) {
            // -Dworldgennext.pregen.autostartDimension=minecraft:the_nether picks another dimension (centred on 0, 0 there).
            String dimension = System.getProperty("worldgennext.pregen.autostartDimension", "").trim();
            ServerLevel target = server.overworld();
            BlockPos centre = target.getSharedSpawnPos();
            if (!dimension.isEmpty()) {
                target = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension)));
                centre = BlockPos.ZERO;
            }
            if (target == null) {
                LOG.error("worldgennext.pregen.autostartDimension={} is not a loaded dimension", dimension);
            } else {
                LOG.info(Pregenerator.start(server, new Pregenerator.Area(target.dimension(), centre.getX() >> 4, centre.getZ() >> 4, radius),
                        0, null));
            }
        }
    }

    private static void serverStopping(ServerStoppingEvent event) {
        Pregenerator.serverStopping();
        FastNoiseEngine.Counters counters = FastNoiseEngine.counters();
        if (counters.gpu() + counters.cpuFallback() > 0) {
            LOG.info(String.format(java.util.Locale.ROOT, "WorldgenNext generated %,d chunks' terrain on the GPU and %,d on the CPU this session",
                    counters.gpu(), counters.cpuFallback() + counters.bail()));
        }
        LOG.debug("Terrain-height cache: {} hits, {} misses", BaseHeightCache.HITS.sum(), BaseHeightCache.MISSES.sum());
        // Unattended runs: -Dworldgennext.statusOnStop=true writes the status report to the log.
        if (Boolean.getBoolean("worldgennext.statusOnStop")) {
            for (String line : StatusReport.lines(event.getServer())) LOG.info(line);
        }
        FastNoiseEngine.stop();
        ClimateColumnCache.stop();
        CavePlans.clear();
        StagedRoute.serverStopping(event);
    }

    private static void serverStopped(ServerStoppedEvent event) {
        StagedRoute.serverStopped(event);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(MOD_ID).requires(WorldgenNextMod::mayUse)
                .then(Commands.literal("status").executes(context -> {
                    for (String line : StatusReport.lines(context.getSource().getServer())) say(context, line);
                    return 1;
                }))
                .then(Commands.literal("pregen")
                        .then(Commands.literal("start")
                                .then(Commands.literal("worldborder").executes(WorldgenNextMod::startPregenInsideBorder))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(0, Pregenerator.MAX_RADIUS))
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            BlockPos centre = BlockPos.containing(source.getPosition());
                                            return startPregen(context, centre.getX(), centre.getZ());
                                        })
                                        .then(Commands.argument("centerX", IntegerArgumentType.integer())
                                                .then(Commands.argument("centerZ", IntegerArgumentType.integer())
                                                        .executes(context -> startPregen(context,
                                                                IntegerArgumentType.getInteger(context, "centerX"),
                                                                IntegerArgumentType.getInteger(context, "centerZ")))))))
                        .then(Commands.literal("pause").executes(context -> say(context, Pregenerator.pause())))
                        .then(Commands.literal("resume").executes(context ->
                                say(context, Pregenerator.resume(context.getSource().getServer(), feedback(context.getSource())))))
                        .then(Commands.literal("stop").executes(context ->
                                say(context, Pregenerator.stop(context.getSource().getServer()))))
                        .then(Commands.literal("status").executes(context -> {
                            String status = Pregenerator.status(context.getSource().getServer());
                            return say(context, status == null ? "No pregeneration is running." : "Pregeneration: " + status);
                        })))
                .then(StagedRoute.developerCommands()));
    }

    /** Operators, the console, and the owner of a singleplayer world (who has no operator level without cheats). */
    private static boolean mayUse(CommandSourceStack source) {
        if (source.hasPermission(2)) return true;
        return source.getEntity() instanceof ServerPlayer player && source.getServer().isSingleplayerOwner(player.getGameProfile());
    }

    /** Radius is in chunks; the centre is given in blocks, like every other coordinate a player types. */
    private static int startPregen(CommandContext<CommandSourceStack> context, int centreBlockX, int centreBlockZ) {
        CommandSourceStack source = context.getSource();
        return startPregen(source, new Pregenerator.Area(levelOf(source).dimension(), centreBlockX >> 4, centreBlockZ >> 4,
                IntegerArgumentType.getInteger(context, "radius")));
    }

    /**
     * The dimension a command applies to.  A console command typed while the server is still starting is run
     * with a source that has no level yet; it means the Overworld.
     */
    private static ServerLevel levelOf(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        return level != null ? level : source.getServer().overworld();
    }

    private static int startPregen(CommandSourceStack source, Pregenerator.Area area) {
        try {
            String answer = Pregenerator.start(source.getServer(), area, 0, feedback(source));
            source.sendSuccess(() -> Component.literal(answer), false);
            return 1;
        } catch (Exception failure) {
            // Without this the player only sees "An unexpected error occurred" and the log says nothing.
            LOG.error("Could not start the pregeneration", failure);
            source.sendFailure(Component.literal("Could not start the pregeneration: " + failure + " (details are in the server log)"));
            return 0;
        }
    }

    /** The square of chunks that covers the dimension's world border. */
    private static int startPregenInsideBorder(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        WorldBorder border = levelOf(source).getWorldBorder();
        long radius = (long) Math.ceil(border.getSize() / 2.0 / 16.0);
        if (radius > Pregenerator.MAX_RADIUS) {
            source.sendFailure(Component.literal(String.format(java.util.Locale.ROOT,
                    "The world border is %,.0f blocks wide; the pregenerator handles up to %,d. Shrink it first, for example"
                            + " '/worldborder set 10000', or give a radius: /worldgennext pregen start <radius>.",
                    border.getSize(), (2L * Pregenerator.MAX_RADIUS + 1) * 16)));
            return 0;
        }
        var area = new Pregenerator.Area(levelOf(source).dimension(), Mth.floor(border.getCenterX()) >> 4,
                Mth.floor(border.getCenterZ()) >> 4, (int) radius);
        return startPregen(source, area);
    }

    /** Progress messages for whoever started the job; the console already sees them in the log. */
    private static Consumer<String> feedback(CommandSourceStack source) {
        if (source.getEntity() == null) return null;
        return text -> source.sendSuccess(() -> Component.literal(text), false);
    }

    private static int say(CommandContext<CommandSourceStack> context, String text) {
        context.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}
