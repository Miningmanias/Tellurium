// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.loader;

import dev.worldgennext.neoforge.WorldgenNextMod;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** NeoForge's entry point: forwards the loader's events to {@link WorldgenNextMod}. */
@Mod(WorldgenNextMod.MOD_ID)
public final class NeoForgeEntry {
    public NeoForgeEntry() {
        WorldgenNextMod.initialise();
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> WorldgenNextMod.registerCommands(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> WorldgenNextMod.serverAboutToStart(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> WorldgenNextMod.serverStarted(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> WorldgenNextMod.serverStopping(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> WorldgenNextMod.serverStopped(event.getServer()));
    }
}
