// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.fast;

import dev.tellurium.neoforge.version.Version;

import dev.tellurium.neoforge.snapshot.DensityNodeReader;
import dev.tellurium.neoforge.snapshot.MinecraftDynamicInputReader;
import dev.tellurium.neoforge.snapshot.MinecraftSnapshotReader;
import dev.tellurium.neoforge.snapshot.RegistrySnapshotReader;
import dev.tellurium.semantic.snapshot.GeneratorSettingsSnapshot;
import dev.tellurium.semantic.snapshot.RegistrySnapshot;
import dev.tellurium.semantic.snapshot.StructureBlendSnapshot;
import dev.tellurium.semantic.snapshot.WorldgenSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;

import java.util.List;

/**
 * Captures the seeded router of one loaded level once.  Per-chunk dynamic
 * inputs (structure pieces, blending) are not part of this snapshot; the fast
 * path supplies them separately and only admits chunks whose blender is empty.
 */
public final class FastRouterCapture {
    private FastRouterCapture() {}

    public static WorldgenSnapshot capture(ServerLevel level) {
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
            throw new IllegalStateException("Fast NOISE requires a NoiseBasedChunkGenerator in " + level.dimension().location());
        }
        NoiseGeneratorSettings noiseSettings = generator.generatorSettings().value();
        NoiseSettings dimensions = noiseSettings.noiseSettings().clampToHeightAccessor(level);
        RegistrySnapshot registry = new RegistrySnapshotReader().capture(
                noiseSettings.defaultBlock(), noiseSettings.defaultFluid());
        var settings = new GeneratorSettingsSnapshot(
                Version.minY(level), level.getHeight(), dimensions.height(), noiseSettings.seaLevel(),
                registry.defaultBlock(), registry.defaultFluid(), noiseSettings.isAquifersEnabled(),
                noiseSettings.oreVeinsEnabled(), dimensions.getCellWidth(), dimensions.getCellHeight());
        String settingsIdentity = generator.generatorSettings().unwrapKey()
                .map(key -> key.location().toString()).orElse("inline-noise-settings");
        String dimension = level.dimension().location().toString();
        var structureBlend = StructureBlendSnapshot.empty();
        var randomState = level.getChunkSource().randomState();
        var reader = new DensityNodeReader(randomState, level.getSeed(), settings.cellWidth(), settings.cellHeight());
        var captured = new MinecraftSnapshotReader().captureWithRuntimeRandomState(
                level.getSeed(), dimension, settingsIdentity, randomState.router(), randomState,
                registry, settings, MinecraftDynamicInputReader.capture(level.getServer(), structureBlend),
                "minecraft-1.21.1-neoforge-21.1.176", null, structureBlend, List.of(), reader);
        if (!captured.supported()) {
            throw new IllegalStateException("Router could not be lowered: " + captured.diagnostics().summary());
        }
        return captured.snapshot();
    }
}
