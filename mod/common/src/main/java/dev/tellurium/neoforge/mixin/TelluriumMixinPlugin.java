// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.mixin;

import dev.tellurium.neoforge.loader.Loader;

import dev.tellurium.neoforge.config.UserSettings;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Loads {@code config/tellurium.toml} before any mixin is applied, so the options are in place when the
 * first class of the mod reads its switches.  It selects nothing: every mixin applies and gates itself.
 */
public final class TelluriumMixinPlugin implements IMixinConfigPlugin {
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
        // -Dtellurium.bench.noMixins=true (with enabled = false in the config): nothing of the mod touches the
        // game, for recording another chunk mod or the unmodified game through the same player tour.
        return !Boolean.getBoolean("tellurium.bench.noMixins");
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
