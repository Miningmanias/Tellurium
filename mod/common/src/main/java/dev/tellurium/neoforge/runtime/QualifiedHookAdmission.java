// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.runtime;

import dev.tellurium.neoforge.config.TelluriumConfig;

import java.util.Objects;

/** Pure admission predicate shared by the loader composition and receipt tests. */
public final class QualifiedHookAdmission {
    private QualifiedHookAdmission() {}

    /** Immutable identity declared by the provider that is about to run. */
    public record ProviderIdentity(String route, String resultAbi, String compilerVersion) {
        public ProviderIdentity {
            requireText(route, "route");
            requireText(resultAbi, "resultAbi");
            requireText(compilerVersion, "compilerVersion");
        }
    }

    /**
     * Validates every receipt/provider/configuration relationship that can be
     * decided without constructing a Minecraft object or native device.
     */
    public static void validate(QualifiedHookEvidence evidence, ProviderIdentity provider,
                                TelluriumConfig config) {
        validate(QualifiedHookEvidenceBundle.single(evidence), provider, config);
    }

    public static void validate(QualifiedHookEvidenceBundle evidence, ProviderIdentity provider,
                                TelluriumConfig config) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(config, "config");
        evidence.requireAdmissible();
        if (!config.enableQualifiedHook()) {
            throw new IllegalStateException("Qualified generation hook is disabled by configuration");
        }
        if (!evidence.route().equals(provider.route())) {
            throw new IllegalArgumentException("Provider route " + provider.route()
                    + " does not match qualification route " + evidence.route());
        }
        if (!evidence.resultAbi().equals(provider.resultAbi())) {
            throw new IllegalArgumentException("Provider result ABI " + provider.resultAbi()
                    + " does not match qualification result ABI " + evidence.resultAbi());
        }
        if (!evidence.compilerVersion().equals(provider.compilerVersion())) {
            throw new IllegalArgumentException("Provider compiler version " + provider.compilerVersion()
                    + " does not match qualification compiler version " + evidence.compilerVersion());
        }
        if (config.mode() == TelluriumConfig.Mode.GPU_REQUIRED
                && !evidence.route().equals("GPU_IEEE_BITS")) {
            throw new IllegalArgumentException("GPU_REQUIRED admits only GPU_IEEE_BITS qualification evidence");
        }
        if (config.mode() == TelluriumConfig.Mode.CPU_ONLY
                && !evidence.route().equals("CPU_OWNED")) {
            throw new IllegalArgumentException("CPU_ONLY admits only CPU_OWNED qualification evidence");
        }
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
