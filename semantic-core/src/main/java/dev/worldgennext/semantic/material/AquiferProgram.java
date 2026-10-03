// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic.material;

import java.util.Objects;

/** Ordered aquifer decision contract. */
public record AquiferProgram(boolean enabled, int cellSize, String waterState, String lavaState,
                             double waterLevel, double lavaLevel) {
    /** Minecraft 1.21.1's global lava FluidStatus level. */
    public static final int VANILLA_GLOBAL_LAVA_LEVEL = -54;

    public AquiferProgram {
        if (cellSize <= 0) throw new IllegalArgumentException("Aquifer cell size must be positive");
        Objects.requireNonNull(waterState, "waterState"); Objects.requireNonNull(lavaState, "lavaState");
        if (!Double.isFinite(waterLevel) || !Double.isFinite(lavaLevel)) throw new IllegalArgumentException("Non-finite aquifer level");
    }
    public static AquiferProgram disabled() { return new AquiferProgram(false, fluidCellSize(), "minecraft:water", "minecraft:lava", 0, 0); }
    private static int fluidCellSize() { return 16; }
}
