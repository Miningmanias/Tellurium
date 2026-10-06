// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.mixin;

import dev.worldgennext.neoforge.loader.Loader;

import dev.worldgennext.neoforge.config.UserSettings;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Loads {@code config/worldgennext.toml} before any mixin is applied, so the options are in place when the
 * first class of the mod reads its switches.  It selects nothing: every mixin applies and gates itself.
 */
public final class WorldgenNextMixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
        Path configDirectory;
        try {
            configDirectory = Loader.configDirectory();
        } catch (Throwable notInitialised) {
            configDirectory = Path.of("config");
        }
        UserSettings.loadAndApply(configDirectory);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
