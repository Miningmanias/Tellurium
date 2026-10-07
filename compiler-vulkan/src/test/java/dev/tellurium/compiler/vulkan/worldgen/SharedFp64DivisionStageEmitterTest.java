// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.worldgen;

import dev.tellurium.semantic.program.NumericProfile;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedFp64DivisionStageEmitterTest {
    @Test void helperClosureRetainsExactDeclarationsAndRejectsUnknownRoots() {
        String source = IntegerIeeeEmitter.helperSource(Set.of("wg_fp64_nan"));
        assertTrue(source.contains("uint wg_fp64_exp(uvec2 bits) { return (bits.y >> 20) & 0x7ffu; }"));
        assertTrue(source.contains("bool wg_fp64_nan(uvec2 bits)"));
        assertFalse(source.contains("wg_fp64_div"));
        assertFalse(source.contains("wg_fp32_"));
        assertEquals(source, IntegerIeeeEmitter.helperSource(Set.of("wg_fp64_exp", "wg_fp64_nan")));
        assertThrows(IllegalArgumentException.class, () -> IntegerIeeeEmitter.helperSource(null));
        assertThrows(IllegalArgumentException.class, () -> IntegerIeeeEmitter.helperSource(Set.of()));
        assertThrows(IllegalArgumentException.class, () -> IntegerIeeeEmitter.helperSource(Set.of("not_a_helper")));
    }

    @Test void canonicalStagesStayBoundedAndIntegerOnlyWithoutCapturedGlobals() {
        String init = SharedFp64DivisionStageEmitter.initSource(64, 4, 6);
        String finish = SharedFp64DivisionStageEmitter.finishSource(64);
        assertTrue(init.length() < 24000, "init chars=" + init.length());
        assertTrue(finish.length() < 6000);
        for (String source : new String[]{init, finish}) {
            SpirvNumericContract.require(source, NumericProfile.GPU_IEEE_BITS);
            assertFalse(source.contains("wg_noise_"));
            assertFalse(source.contains("wg_node_"));
            assertFalse(source.contains("wg_blend_"));
            assertFalse(source.contains("wg_fp64_div("));
        }
        assertEquals(52, init.split("normalizedLeftExponent--;", -1).length - 1);
        assertEquals(52, init.split("normalizedRightExponent--;", -1).length - 1);
        assertTrue(init.contains("uint base = index * 8u;"));
        assertTrue(finish.contains("uint base = index * 16u;"));
        assertTrue(finish.contains("outputBits[outputBase + 1u] = value.y;"));
        assertTrue(finish.contains("if (wg_u64_nonzero(remainder)) quotient.x |= 1u;"));
    }

    @Test void chunksVisitExactly108BitsInOrderAndPreserveTheStateAbi() {
        int nextBit = 107;
        for (int chunk = 0; chunk < SharedFp64DivisionStageEmitter.CHUNK_COUNT; chunk++) {
            String source = SharedFp64DivisionStageEmitter.chunkSource(64, chunk);
            SpirvNumericContract.require(source, NumericProfile.GPU_IEEE_BITS);
            int word = nextBit / 32;
            int shift = nextBit % 32 - 3;
            assertTrue(source.contains("(inputBits[base + " + word + "u] >> " + shift + "u) & 15u)"));
            assertEquals(4, source.split("if \\(selected != 0u\\)", -1).length - 1);
            assertEquals(16, source.split("outputBits\\[base", -1).length - 1);
            assertTrue(source.length() < 4000);
            assertFalse(source.contains("for ("));
            assertFalse(source.contains("while ("));
            nextBit -= 4;
        }
        assertEquals(-1, nextBit);
    }

    @Test void layoutsAndInvalidStageRequestsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> SharedFp64DivisionStageEmitter.initSource(64, 4, 4));
        assertThrows(IllegalArgumentException.class, () -> SharedFp64DivisionStageEmitter.initSource(64, 3, 6));
        assertThrows(IllegalArgumentException.class, () -> SharedFp64DivisionStageEmitter.chunkSource(64, -1));
        assertThrows(IllegalArgumentException.class, () -> SharedFp64DivisionStageEmitter.chunkSource(64, 27));
        assertThrows(IllegalArgumentException.class, () -> SharedFp64DivisionStageEmitter.finishSource(0));
        assertThrows(IllegalArgumentException.class, () -> SharedFp64DivisionStageEmitter.finishSource(1025));
        assertTrue(SharedFp64DivisionStageEmitter.initSource(128, 6, 4).contains("local_size_x=128"));
        assertTrue(SharedFp64DivisionStageEmitter.initSource(128, 6, 4).contains("uvec2(inputBits[base + 6u], inputBits[base + 7u])"));
    }
}
