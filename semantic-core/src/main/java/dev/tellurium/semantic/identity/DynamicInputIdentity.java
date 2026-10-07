// SPDX-License-Identifier: MIT
package dev.tellurium.semantic.identity;

import java.util.Objects;

/** Stable identity for reload-sensitive inputs such as datapacks, blends and structures. */
public record DynamicInputIdentity(String datapackHash, String modStackHash, String structureHash,
                                   String blendHash, long revision) {
    public DynamicInputIdentity {
        requireText(datapackHash, "datapackHash"); requireText(modStackHash, "modStackHash");
        requireText(structureHash, "structureHash"); requireText(blendHash, "blendHash");
        if (revision < 0) throw new IllegalArgumentException("Negative dynamic revision");
    }
    public static DynamicInputIdentity empty() { return new DynamicInputIdentity("none", "none", "none", "none", 0); }
    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) throw new IllegalArgumentException(name + " is blank");
    }
}
