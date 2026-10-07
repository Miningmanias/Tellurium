// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.jvm.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AquiferEvaluatorTest {
    @Test
    void randomizedFluidSurfaceIsCappedByTheLocalPreliminarySurface() {
        assertEquals(24, AquiferEvaluator.randomizedFluidSurfaceLevel(24, 0, 0.669142756860209));
        assertEquals(26, AquiferEvaluator.randomizedFluidSurfaceLevel(32, 0, 0.669142756860209));
    }

    @Test
    void lavaReplacementUsesExactDefaultStateIdentity() {
        assertEquals("minecraft:lava[level=0]", AquiferEvaluator.selectFluidType(
                "minecraft:lava[level=1]", -20, 0.31,
                "minecraft:lava[level=0]"));
        assertEquals("minecraft:lava[level=1]", AquiferEvaluator.selectFluidType(
                "minecraft:lava[level=1]", -20, 0.30,
                "minecraft:lava[level=0]"));
    }
}
