// SPDX-License-Identifier: MIT
package dev.tellurium.material.chunk;

import java.util.Objects;

public final class ChunkResultValidator {
    private ChunkResultValidator() {}
    public record Validation(boolean valid, String reason, String checksum) {
        public Validation { Objects.requireNonNull(reason); Objects.requireNonNull(checksum); }
    }
    public static Validation validate(ChunkNoiseResult result, String expectedContext, String expectedRegistry) {
        if (result == null) return new Validation(false, "missing result", "");
        if (expectedContext == null || expectedRegistry == null) return new Validation(false, "missing expected identity", "");
        if (!expectedContext.equals(result.header().contextIdentity())) return new Validation(false, "context identity mismatch", result.logicalChecksum());
        if (!expectedRegistry.equals(result.header().registryFingerprint())) return new Validation(false, "registry identity mismatch", result.logicalChecksum());
        try { return new Validation(true, "valid", result.logicalChecksum()); }
        catch (RuntimeException failure) { return new Validation(false, failure.getMessage(), ""); }
    }
    public static void requireValid(ChunkNoiseResult result, String expectedContext, String expectedRegistry) {
        Validation validation = validate(result, expectedContext, expectedRegistry);
        if (!validation.valid()) throw new IllegalArgumentException("Invalid chunk result: " + validation.reason());
    }
}
