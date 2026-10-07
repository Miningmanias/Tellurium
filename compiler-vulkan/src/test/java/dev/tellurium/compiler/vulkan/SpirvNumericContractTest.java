// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan;

import dev.tellurium.compiler.vulkan.worldgen.SpirvNumericContract;
import dev.tellurium.semantic.program.NumericProfile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpirvNumericContractTest {
    @Test
    void compiledIntegerModuleHasNoFloatType() {
        var inspection = new SpirvNumericContract().inspectSpirv(module(), NumericProfile.GPU_IEEE_BITS);
        assertTrue(inspection.valid(), inspection.violations().toString());
    }

    @Test
    void compiledFloatTypeIsRejectedBySoftwareProfile() {
        var inspection = new SpirvNumericContract().inspectSpirv(module(0x00030016, 1, 32), NumericProfile.GPU_IEEE_BITS);
        assertFalse(inspection.valid());
        assertTrue(inspection.violations().stream().anyMatch(value -> value.contains("OpTypeFloat")));
    }

    @Test
    void malformedInstructionStreamFailsClosed() {
        var inspection = new SpirvNumericContract().inspectSpirv(module(0x00040001, 1, 2), NumericProfile.GPU_IEEE_BITS);
        assertFalse(inspection.valid());
        assertTrue(inspection.violations().stream().anyMatch(value -> value.contains("malformed")));
    }

    private static byte[] module(int... instructions) {
        int[] words = new int[5 + instructions.length];
        words[0] = 0x07230203;
        words[1] = 0x00010500;
        words[2] = 0;
        words[3] = 32;
        words[4] = 0;
        System.arraycopy(instructions, 0, words, 5, instructions.length);
        ByteBuffer bytes = ByteBuffer.allocate(words.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int word : words) bytes.putInt(word);
        return bytes.array();
    }
}
