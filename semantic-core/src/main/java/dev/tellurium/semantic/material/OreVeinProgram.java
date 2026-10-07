// SPDX-License-Identifier: MIT
package dev.tellurium.semantic.material;

import java.util.List;
import java.util.Objects;

/** Exact material ordering metadata for an ore-vein rule. */
public record OreVeinProgram(boolean enabled, String toggleNode, String ridgedNode, String gapNode,
                             List<String> materials, long randomSalt) {
    public OreVeinProgram {
        Objects.requireNonNull(toggleNode, "toggleNode"); Objects.requireNonNull(ridgedNode, "ridgedNode");
        Objects.requireNonNull(gapNode, "gapNode"); materials = List.copyOf(materials == null ? List.of() : materials);
        if (materials.stream().anyMatch(value -> value == null || value.isBlank())) throw new IllegalArgumentException("Invalid ore material");
    }
    public static OreVeinProgram disabled() { return new OreVeinProgram(false, "vein_toggle", "vein_ridged", "vein_gap", List.of(), 0); }
    public static OreVeinProgram vanilla(long randomSalt) {
        return new OreVeinProgram(true, "veinToggle", "veinRidged", "veinGap", List.of(
                "minecraft:copper_ore", "minecraft:raw_copper_block", "minecraft:granite",
                "minecraft:deepslate_iron_ore", "minecraft:raw_iron_block", "minecraft:tuff"), randomSalt);
    }
}
