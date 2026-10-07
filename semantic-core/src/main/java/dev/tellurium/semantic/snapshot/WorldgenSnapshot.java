// SPDX-License-Identifier: MIT
package dev.tellurium.semantic.snapshot;

import dev.tellurium.semantic.identity.DynamicInputIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/** Loader-free immutable capture of every input needed to replay a worldgen request. */
public final class WorldgenSnapshot {
    private final long seed;
    private final String dimension;
    private final String settingsIdentity;
    private final NoiseRouterSnapshot router;
    private final RandomStateSnapshot randomState;
    private final RegistrySnapshot registry;
    private final GeneratorSettingsSnapshot generatorSettings;
    private final StructureBlendSnapshot structureBlend;
    private final List<String> structures;
    private final DynamicInputIdentity dynamicInputs;
    private final String sourceStackFingerprint;
    private final String fingerprint;

    public WorldgenSnapshot(long seed, String dimension, String settingsIdentity, NoiseRouterSnapshot router,
                            RandomStateSnapshot randomState, RegistrySnapshot registry,
                            GeneratorSettingsSnapshot generatorSettings, StructureBlendSnapshot structureBlend,
                            List<String> structures, DynamicInputIdentity dynamicInputs, String sourceStackFingerprint) {
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("Dimension required");
        if (settingsIdentity == null || settingsIdentity.isBlank()) throw new IllegalArgumentException("Settings identity required");
        if (sourceStackFingerprint == null || sourceStackFingerprint.isBlank()) throw new IllegalArgumentException("Source stack fingerprint required");
        this.seed = seed; this.dimension = dimension; this.settingsIdentity = settingsIdentity;
        this.router = Objects.requireNonNull(router, "router"); this.randomState = Objects.requireNonNull(randomState, "randomState");
        if (randomState.worldSeed() != seed) {
            throw new IllegalArgumentException("Random-state seed does not match world seed");
        }
        this.registry = Objects.requireNonNull(registry, "registry"); this.generatorSettings = Objects.requireNonNull(generatorSettings, "generatorSettings");
        this.structureBlend = Objects.requireNonNull(structureBlend, "structureBlend"); this.structures = List.copyOf(structures == null ? List.of() : structures);
        this.dynamicInputs = Objects.requireNonNull(dynamicInputs, "dynamicInputs"); this.sourceStackFingerprint = sourceStackFingerprint;
        this.fingerprint = hash();
    }
    public static Builder builder(long seed, String dimension) { return new Builder(seed, dimension); }
    public long seed() { return seed; }
    public String dimension() { return dimension; }
    public String settingsIdentity() { return settingsIdentity; }
    public NoiseRouterSnapshot router() { return router; }
    public RandomStateSnapshot randomState() { return randomState; }
    public RegistrySnapshot registry() { return registry; }
    public GeneratorSettingsSnapshot generatorSettings() { return generatorSettings; }
    public StructureBlendSnapshot structureBlend() { return structureBlend; }
    public List<String> structures() { return structures; }
    public DynamicInputIdentity dynamicInputs() { return dynamicInputs; }
    public String sourceStackFingerprint() { return sourceStackFingerprint; }
    public String fingerprint() { return fingerprint; }

    private String hash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Long.toString(seed).getBytes(StandardCharsets.UTF_8));
            digest.update((dimension + "\0" + settingsIdentity + "\0" + router.roots() + "\0" + randomState
                    + "\0" + registry.fingerprint() + "\0" + generatorSettings + "\0" + structureBlend
                    + "\0" + structures + "\0" + dynamicInputs + "\0" + sourceStackFingerprint).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    public static final class Builder {
        private final long seed; private final String dimension;
        private String settingsIdentity = "settings";
        private NoiseRouterSnapshot router = NoiseRouterSnapshot.empty();
        private RandomStateSnapshot randomState;
        private RegistrySnapshot registry = RegistrySnapshot.minimal();
        private GeneratorSettingsSnapshot generatorSettings;
        private StructureBlendSnapshot structureBlend = StructureBlendSnapshot.empty();
        private List<String> structures = List.of();
        private DynamicInputIdentity dynamicInputs = DynamicInputIdentity.empty();
        private String sourceStackFingerprint = "unbound";
        private Builder(long seed, String dimension) { this.seed = seed; this.dimension = Objects.requireNonNull(dimension); }
        public Builder settingsIdentity(String value) { settingsIdentity = value; return this; }
        public Builder router(NoiseRouterSnapshot value) { router = value; return this; }
        public Builder randomState(RandomStateSnapshot value) { randomState = value; return this; }
        public Builder registry(RegistrySnapshot value) { registry = value; return this; }
        public Builder generatorSettings(GeneratorSettingsSnapshot value) { generatorSettings = value; return this; }
        public Builder structureBlend(StructureBlendSnapshot value) { structureBlend = value; return this; }
        public Builder structures(List<String> value) { structures = value; return this; }
        public Builder dynamicInputs(DynamicInputIdentity value) { dynamicInputs = value; return this; }
        public Builder sourceStackFingerprint(String value) { sourceStackFingerprint = value; return this; }
        public WorldgenSnapshot build() {
            if (randomState == null) randomState = RandomStateSnapshot.forSeed(seed);
            if (generatorSettings == null) generatorSettings = GeneratorSettingsSnapshot.overworld();
            return new WorldgenSnapshot(seed, dimension, settingsIdentity, router, randomState, registry,
                    generatorSettings, structureBlend, structures, dynamicInputs, sourceStackFingerprint);
        }
    }
}
