// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.loader;

import dev.worldgennext.neoforge.WorldgenNextMod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Fabric's entry point: forwards the loader's events to {@link WorldgenNextMod}. */
public final class FabricEntry implements ModInitializer {
    @Override
    public void onInitialize() {
        WorldgenNextMod.initialise();
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> WorldgenNextMod.registerCommands(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(WorldgenNextMod::serverAboutToStart);
        ServerLifecycleEvents.SERVER_STARTED.register(WorldgenNextMod::serverStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(WorldgenNextMod::serverStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(WorldgenNextMod::serverStopped);
    }
}
