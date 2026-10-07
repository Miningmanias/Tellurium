// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

import java.util.List;

public record RuntimeDiagnostics(String state, long deviceGeneration, long inFlight, long reservedBytes,
                                 long quarantinedBytes, List<String> failures) {
    public RuntimeDiagnostics { if (state == null || state.isBlank() || deviceGeneration < 0 || inFlight < 0 || reservedBytes < 0 || quarantinedBytes < 0) throw new IllegalArgumentException("Invalid runtime diagnostics"); failures = List.copyOf(failures == null ? List.of() : failures); }
}
