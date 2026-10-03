// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic.execution;

import dev.worldgennext.semantic.program.NumericProfile;
import java.util.Objects;

/** Admission decision that cannot silently turn an unsupported route into success. */
public record CapabilityDecision(boolean supported, String route, String reason, NumericProfile numericProfile) {
    public CapabilityDecision {
        if (route == null || route.isBlank()) throw new IllegalArgumentException("Route required");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Decision reason required");
        Objects.requireNonNull(numericProfile, "numericProfile");
    }
    public static CapabilityDecision supported(String route, NumericProfile profile) {
        return new CapabilityDecision(true, route, "qualified", profile);
    }
    public static CapabilityDecision unsupported(String route, String reason, NumericProfile profile) {
        return new CapabilityDecision(false, route, reason, profile);
    }
}
