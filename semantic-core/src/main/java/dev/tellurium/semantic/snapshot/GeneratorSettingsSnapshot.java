// SPDX-License-Identifier: MIT
package dev.tellurium.semantic.snapshot;

import java.util.Objects;

/** Dimension storage and material settings captured before lowering. */
public record GeneratorSettingsSnapshot(int minY, int height, int logicalHeight, int seaLevel,
                                        BlockStateDescriptor defaultBlock, BlockStateDescriptor defaultFluid,
                                        boolean aquifersEnabled, boolean oresEnabled,
                                        int cellWidth, int cellHeight) {
    public GeneratorSettingsSnapshot(int minY, int height, int logicalHeight, int seaLevel,
                                      BlockStateDescriptor defaultBlock, BlockStateDescriptor defaultFluid,
                                      boolean aquifersEnabled, boolean oresEnabled) {
        this(minY, height, logicalHeight, seaLevel, defaultBlock, defaultFluid, aquifersEnabled, oresEnabled, 4, 8);
    }
    public GeneratorSettingsSnapshot {
        if (height <= 0 || height % 16 != 0 || logicalHeight <= 0 || logicalHeight > height) throw new IllegalArgumentException("Invalid generation height");
        if ((long) minY + height > Integer.MAX_VALUE) throw new IllegalArgumentException("Generation bounds overflow");
        if (cellWidth <= 0 || cellHeight <= 0 || cellWidth % 4 != 0 || cellHeight % 4 != 0) throw new IllegalArgumentException("Invalid noise cell geometry");
        Objects.requireNonNull(defaultBlock, "defaultBlock"); Objects.requireNonNull(defaultFluid, "defaultFluid");
    }
    public int maxYExclusive() { return Math.addExact(minY, height); }
    public static GeneratorSettingsSnapshot overworld() {
        var registry = RegistrySnapshot.minimal();
        return new GeneratorSettingsSnapshot(-64, 384, 384, 63, registry.defaultBlock(), registry.defaultFluid(), true, true, 4, 8);
    }
}
