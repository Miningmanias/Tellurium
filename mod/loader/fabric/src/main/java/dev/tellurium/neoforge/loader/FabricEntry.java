// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.loader;

import dev.tellurium.neoforge.TelluriumMod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Fabric's entry point: forwards the loader's events to {@link TelluriumMod}. */
public final class FabricEntry implements ModInitializer {
    @Override
    public void onInitialize() {
        TelluriumMod.initialise();
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> TelluriumMod.registerCommands(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(TelluriumMod::serverAboutToStart);
        ServerLifecycleEvents.SERVER_STARTED.register(TelluriumMod::serverStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(TelluriumMod::serverStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(TelluriumMod::serverStopped);
    }
}
