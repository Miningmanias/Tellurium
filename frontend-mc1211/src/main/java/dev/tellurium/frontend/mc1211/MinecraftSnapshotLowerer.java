// SPDX-License-Identifier: MIT
package dev.tellurium.frontend.mc1211;

import dev.tellurium.semantic.identity.DynamicInputIdentity;
import dev.tellurium.semantic.snapshot.*;
import java.util.List;
import java.util.Map;

/** Lowers a pure captured representation into immutable WorldgenSnapshot data. */
public final class MinecraftSnapshotLowerer {
    public record Result(WorldgenSnapshot snapshot, NoiseRouterLowerer.Result router, LoweringDiagnostics diagnostics) { public boolean supported() { return snapshot != null && router.supported() && !diagnostics.hasErrors(); } }
    private final NoiseRouterLowerer routerLowerer = new NoiseRouterLowerer();
    public Result lower(long seed, String dimension, String settingsIdentity, Map<String, SourceNodeSnapshot> roots,
                        RegistrySnapshot registry, GeneratorSettingsSnapshot settings, DynamicInputIdentity dynamicInputs,
                        String sourceStackFingerprint) {
        return lower(seed, dimension, settingsIdentity, roots, registry, settings, dynamicInputs, sourceStackFingerprint,
                null, StructureBlendSnapshot.empty(), List.of());
    }

    /**
     * Full capture overload. The short overload remains convenient for pure fixtures, but
     * production callers can pass the same-stack random tables, blend inputs and structure
     * identities instead of silently rebuilding them from defaults.
     */
    public Result lower(long seed, String dimension, String settingsIdentity, Map<String, SourceNodeSnapshot> roots,
                        RegistrySnapshot registry, GeneratorSettingsSnapshot settings, DynamicInputIdentity dynamicInputs,
                        String sourceStackFingerprint, RandomStateSnapshot randomState,
                        StructureBlendSnapshot structureBlend, List<String> structures) {
        var router = routerLowerer.lower(roots); var diagnostics = new LoweringDiagnostics(); diagnostics.addAll(router.diagnostics().entries());
        if (!router.supported()) return new Result(null, router, diagnostics);
        var builder = WorldgenSnapshot.builder(seed, dimension).settingsIdentity(settingsIdentity).router(router.snapshot()).registry(registry).generatorSettings(settings).dynamicInputs(dynamicInputs).sourceStackFingerprint(sourceStackFingerprint).structureBlend(structureBlend).structures(structures);
        if (randomState != null) builder.randomState(randomState);
        return new Result(builder.build(), router, diagnostics);
    }
}
