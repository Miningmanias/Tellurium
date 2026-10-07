// SPDX-License-Identifier: MIT
package dev.tellurium.frontend.mc1211;

import dev.tellurium.semantic.material.AquiferProgram;
import dev.tellurium.semantic.material.OreVeinProgram;

public final class MaterialLowerer {
    public AquiferProgram aquifer(boolean enabled, int cellSize, String water, String lava, double waterLevel, double lavaLevel) { return new AquiferProgram(enabled, cellSize, water, lava, waterLevel, lavaLevel); }
    public OreVeinProgram ore(boolean enabled, String toggle, String ridged, String gap, java.util.List<String> materials, long salt) { return new OreVeinProgram(enabled, toggle, ridged, gap, materials, salt); }
}
