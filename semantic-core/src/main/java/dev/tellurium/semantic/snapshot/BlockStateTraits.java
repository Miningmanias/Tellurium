// SPDX-License-Identifier: MIT
package dev.tellurium.semantic.snapshot;

import java.util.Objects;

/**
 * Loader-independent predicates captured for a block state.  These are kept
 * beside the canonical state identity so result consumers do not have to
 * guess Minecraft block behavior from a name.
 */
public record BlockStateTraits(boolean air, boolean blocksMotion, boolean fluid, boolean leaves) {
    public static BlockStateTraits infer(BlockStateDescriptor descriptor) {
        return inferCanonical(Objects.requireNonNull(descriptor, "descriptor").canonical());
    }

    /** Conservative compatibility traits for synthetic snapshots. */
    public static BlockStateTraits inferCanonical(String canonical) {
        Objects.requireNonNull(canonical, "canonical");
        int properties = canonical.indexOf('[');
        String name = properties < 0 ? canonical : canonical.substring(0, properties);
        boolean air = name.endsWith(":air") || name.endsWith("_air");
        boolean fluid = name.endsWith(":water") || name.endsWith(":lava")
                || name.endsWith("_water") || name.endsWith("_lava");
        boolean leaves = name.endsWith(":leaves") || name.endsWith("_leaves");
        boolean nonMotion = air || fluid || leaves
                || name.endsWith(":grass") || name.endsWith(":fern")
                || name.endsWith(":flower") || name.endsWith(":torch")
                || name.endsWith(":redstone_wire") || name.endsWith(":tripwire")
                || name.endsWith(":vine") || name.endsWith(":snow")
                || name.endsWith(":button") || name.endsWith(":pressure_plate")
                || name.endsWith(":carpet");
        return new BlockStateTraits(air, !nonMotion, fluid, leaves);
    }
}
