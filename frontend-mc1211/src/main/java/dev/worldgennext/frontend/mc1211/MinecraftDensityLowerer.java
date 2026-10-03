// SPDX-License-Identifier: MIT
package dev.worldgennext.frontend.mc1211;

import dev.worldgennext.semantic.WorldgenIdentity;

/**
 * Version adapter seam. A future implementation must inspect actual Minecraft types and
 * preserve interpolation, RNG, material and registry semantics. Object is intentional here:
 * the unimplemented v0.1 seam adds no Minecraft/runtime dependency to the pure core.
 */
public interface MinecraftDensityLowerer {
    LoweringResult lower(WorldgenIdentity identity, Object source);
}
