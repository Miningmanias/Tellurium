// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.loader;

import dev.tellurium.neoforge.TelluriumMod;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** NeoForge's entry point: forwards the loader's events to {@link TelluriumMod}. */
@Mod(TelluriumMod.MOD_ID)
public final class NeoForgeEntry {
    public NeoForgeEntry() {
        TelluriumMod.initialise();
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> TelluriumMod.registerCommands(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> TelluriumMod.serverAboutToStart(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> TelluriumMod.serverStarted(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> TelluriumMod.serverStopping(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> TelluriumMod.serverStopped(event.getServer()));
    }
}
