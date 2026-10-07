// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.snapshot;

import dev.tellurium.neoforge.version.Version;

import dev.tellurium.frontend.mc1211.MinecraftSnapshotLowerer;
import dev.tellurium.frontend.mc1211.SourceNodeSnapshot;
import dev.tellurium.semantic.identity.DynamicInputIdentity;
import dev.tellurium.semantic.snapshot.GeneratorSettingsSnapshot;
import dev.tellurium.semantic.snapshot.RandomStateSnapshot;
import dev.tellurium.semantic.snapshot.RegistrySnapshot;
import dev.tellurium.semantic.snapshot.StructureBlendSnapshot;
import dev.tellurium.semantic.snapshot.BeardifierSnapshot;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;

/** Version-specific composition boundary. Live Minecraft reflection does not escape this adapter. */
public final class MinecraftSnapshotReader {
    public MinecraftSnapshotLowerer.Result capture(long seed, String dimension, String settingsIdentity,
                                                   Map<String, SourceNodeSnapshot> roots, RegistrySnapshot registry,
                                                   GeneratorSettingsSnapshot settings, DynamicInputIdentity dynamicInputs,
                                                   String sourceStackFingerprint) {
        return new MinecraftSnapshotLowerer().lower(seed, dimension, settingsIdentity, roots, registry, settings, dynamicInputs, sourceStackFingerprint);
    }

    /** Capture the version-pinned NoiseRouter before any pure-module lowering occurs. */
    public MinecraftSnapshotLowerer.Result capture(long seed, String dimension, String settingsIdentity,
                                                   NoiseRouter router, RegistrySnapshot registry,
                                                   GeneratorSettingsSnapshot settings, DynamicInputIdentity dynamicInputs,
                                                   String sourceStackFingerprint, RandomStateSnapshot randomState,
                                                   StructureBlendSnapshot structureBlend, List<String> structures) {
        return capture(seed, dimension, settingsIdentity, router, registry, settings, dynamicInputs, sourceStackFingerprint,
                randomState, structureBlend, structures, new DensityNodeReader());
    }

    /** Capture using the live loader RandomState so seed-expanded NormalNoise tables cross the boundary as data. */
    public MinecraftSnapshotLowerer.Result captureWithRuntimeRandomState(long seed, String dimension, String settingsIdentity,
                                                   NoiseRouter router, RandomState runtimeRandomState, RegistrySnapshot registry,
                                                   GeneratorSettingsSnapshot settings, DynamicInputIdentity dynamicInputs,
                                                   String sourceStackFingerprint, RandomStateSnapshot randomState,
                                                   StructureBlendSnapshot structureBlend, List<String> structures) {
        if (runtimeRandomState == null) throw new IllegalArgumentException("Runtime RandomState is missing");
        if (settings == null) throw new IllegalArgumentException("Generator settings are required for runtime capture");
        return captureWithRuntimeRandomState(seed, dimension, settingsIdentity, router, runtimeRandomState, registry,
                settings, dynamicInputs, sourceStackFingerprint, randomState, structureBlend, structures,
                new DensityNodeReader(runtimeRandomState, seed, settings.cellWidth(), settings.cellHeight()));
    }

    /**
     * Runtime capture overload for callers that retain the reader across
     * adjacent chunks.  The reader remains loader-owned and only returns
     * immutable SourceNodeSnapshot data to the pure lowering boundary.
     */
    public MinecraftSnapshotLowerer.Result captureWithRuntimeRandomState(long seed, String dimension, String settingsIdentity,
                                                   NoiseRouter router, RandomState runtimeRandomState, RegistrySnapshot registry,
                                                   GeneratorSettingsSnapshot settings, DynamicInputIdentity dynamicInputs,
                                                   String sourceStackFingerprint, RandomStateSnapshot randomState,
                                                   StructureBlendSnapshot structureBlend, List<String> structures,
                                                   DensityNodeReader reader) {
        if (runtimeRandomState == null) throw new IllegalArgumentException("Runtime RandomState is missing");
        if (settings == null) throw new IllegalArgumentException("Generator settings are required for runtime capture");
        if (reader == null || !reader.matchesRuntime(runtimeRandomState, seed,
                settings.cellWidth(), settings.cellHeight())) {
            throw new IllegalArgumentException("Runtime density reader does not match the loaded RandomState and geometry");
        }
        return capture(seed, dimension, settingsIdentity, router, registry, settings, dynamicInputs, sourceStackFingerprint,
                randomState, structureBlend, structures, reader);
    }

    /**
     * Runtime overload for the actual NoiseChunk/Blender path.  BlendingData
     * is captured while the loader still owns it; only its immutable sample
     * representation crosses into the pure snapshot.
     */
    public MinecraftSnapshotLowerer.Result captureWithRuntimeRandomState(long seed, String dimension, String settingsIdentity,
                                                   NoiseRouter router, RandomState runtimeRandomState, RegistrySnapshot registry,
                                                   GeneratorSettingsSnapshot settings, DynamicInputIdentity dynamicInputs,
                                                   String sourceStackFingerprint, RandomStateSnapshot randomState,
                                                   Object blender, BeardifierSnapshot beardifier, List<String> structures) {
        StructureBlendSnapshot structureBlend = new StructureBlendReader().capture(blender, beardifier);
        return captureWithRuntimeRandomState(seed, dimension, settingsIdentity, router, runtimeRandomState, registry,
                settings, dynamicInputs, sourceStackFingerprint, randomState, structureBlend, structures);
    }

    private MinecraftSnapshotLowerer.Result capture(long seed, String dimension, String settingsIdentity,
                                                   NoiseRouter router, RegistrySnapshot registry,
                                                   GeneratorSettingsSnapshot settings, DynamicInputIdentity dynamicInputs,
                                                   String sourceStackFingerprint, RandomStateSnapshot randomState,
                                                   StructureBlendSnapshot structureBlend, List<String> structures,
                                                   DensityNodeReader reader) {
        if (router == null) throw new IllegalArgumentException("NoiseRouter is missing");
        var roots = new LinkedHashMap<String, SourceNodeSnapshot>();
        roots.put("barrierNoise", reader.capture(router.barrierNoise()));
        roots.put("fluidLevelFloodedness", reader.capture(router.fluidLevelFloodednessNoise()));
        roots.put("fluidLevelSpread", reader.capture(router.fluidLevelSpreadNoise()));
        roots.put("lava", reader.capture(router.lavaNoise()));
        roots.put("temperature", reader.capture(router.temperature()));
        roots.put("vegetation", reader.capture(router.vegetation()));
        roots.put("continents", reader.capture(router.continents()));
        roots.put("erosion", reader.capture(router.erosion()));
        roots.put("depth", reader.capture(router.depth()));
        roots.put("ridges", reader.capture(router.ridges()));
        roots.put("initialDensityWithoutJaggedness", reader.capture(Version.preliminarySurface(router)));
        roots.put("finalDensity", reader.capture(router.finalDensity()));
        roots.put("veinToggle", reader.capture(router.veinToggle()));
        roots.put("veinRidged", reader.capture(router.veinRidged()));
        roots.put("veinGap", reader.capture(router.veinGap()));
        RandomStateSnapshot capturedRandomState = randomState;
        if (capturedRandomState == null && reader.hasRuntimeRandomState()) {
            capturedRandomState = reader.captureRandomStateSnapshot();
        }
        return new MinecraftSnapshotLowerer().lower(seed, dimension, settingsIdentity, roots, registry, settings, dynamicInputs,
                sourceStackFingerprint, capturedRandomState, structureBlend, structures);
    }
}
