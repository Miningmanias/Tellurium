// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.worldgen;

/**
 * Emits the ordered final material decision shared by dense worldgen stages.
 * Candidate values are explicit inputs so an eventual aquifer/ore upload
 * cannot silently change the ordering or turn a missing candidate into air.
 */
public final class MaterialEmitter {
    public String emit() {
        return """
                // MaterialRuleList order is part of the result ABI: aquifer
                // first, then ore, then the configured default block. A null
                // aquifer result is not an air decision.
                uint wg_material_decide(uint density, bool aquiferCandidate, uint aquiferState,
                        bool oreCandidate, uint oreState, uint defaultStateId,
                        uint airStateId, bool noAquiferUsesDefault) {
                    if (aquiferCandidate) return aquiferState;
                    if (oreCandidate) return oreState;
                    // A pressure rejection is a non-aquifer result that
                    // deliberately falls through to the generator default.
                    // The v0.2 material contract does not reinterpret a
                    // missing candidate as air: density has already been
                    // consumed by the aquifer/ore rules at this boundary.
                    return defaultStateId;
                }

                uint wg_material_decide64(uvec2 density, bool aquiferCandidate, uint aquiferState,
                        bool oreCandidate, uint oreState, uint defaultStateId,
                        uint airStateId, bool noAquiferUsesDefault) {
                    if (aquiferCandidate) return aquiferState;
                    if (oreCandidate) return oreState;
                    // A pressure rejection and an ordinary dry result both
                    // fall through to the configured default block. Keep the
                    // density carrier in the ABI for ordering/diagnostics,
                    // but do not use it to invent an air material here.
                    return defaultStateId;
                }
                """;
    }
}
