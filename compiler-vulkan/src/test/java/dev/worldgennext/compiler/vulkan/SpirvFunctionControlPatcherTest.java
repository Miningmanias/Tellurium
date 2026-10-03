// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan;

import dev.worldgennext.compiler.vulkan.worldgen.SpirvFunctionControlPatcher;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpirvFunctionControlPatcherTest {
    @Test
    void marksOnlyFunctionsMatchingTheRequestedNamePrefix() {
        byte[] module = module(
                namedInstruction(1, "wg_heavy"),
                namedInstruction(2, "main"),
                instruction(54, 10, 1, 0, 20),
                instruction(54, 11, 2, 0, 20));

        byte[] patched = SpirvFunctionControlPatcher.addDontInlineToNamedFunctions(module, "wg_");
        int[] words = words(patched);
        assertEquals(54, words[14] & 0xffff);
        assertEquals(2, words[17]);
        assertEquals(54, words[19] & 0xffff);
        assertEquals(0, words[22]);
    }

    @Test
    void marksFunctionsMatchingAnyCommaSeparatedPrefix() {
        byte[] module = module(
                namedInstruction(1, "wg_node_1"),
                namedInstruction(2, "wg_spline_2"),
                namedInstruction(3, "wg_fp64_add"),
                instruction(54, 10, 1, 0, 20),
                instruction(54, 11, 2, 0, 20),
                instruction(54, 12, 3, 0, 20));

        int[] words = words(SpirvFunctionControlPatcher.addDontInlineToNamedFunctions(
                module, "wg_node_,wg_spline_"));
        assertEquals(2, words[23]);
        assertEquals(2, words[28]);
        assertEquals(0, words[33]);
    }

    @Test
    void rejectsAnEmptyPrefixListEntry() {
        byte[] module = module(namedInstruction(1, "wg_node_1"), instruction(54, 10, 1, 0, 20));
        assertThrows(IllegalArgumentException.class,
                () -> SpirvFunctionControlPatcher.addDontInlineToNamedFunctions(module, "wg_node_,"));
    }

    @Test
    void doesNotChangeAFunctionAlreadyMarkedDontInline() {
        byte[] module = module(
                namedInstruction(1, "wg_heavy"),
                instruction(54, 10, 1, 2, 20));
        byte[] patched = SpirvFunctionControlPatcher.addDontInlineToNamedFunctions(module, "wg_");
        assertArrayEquals(module, patched);
    }

    @Test
    void malformedInstructionFailsClosed() {
        byte[] module = module(new int[]{0x00040001, 1, 2});
        assertThrows(IllegalArgumentException.class,
                () -> SpirvFunctionControlPatcher.addDontInlineToNamedFunctions(module, "wg_"));
    }

    private static byte[] module(int[]... instructions) {
        int length = 5;
        for (int[] instruction : instructions) length += instruction.length;
        int[] words = new int[length];
        words[0] = 0x07230203;
        words[1] = 0x00010500;
        words[2] = 0;
        words[3] = 64;
        words[4] = 0;
        int offset = 5;
        for (int[] instruction : instructions) {
            System.arraycopy(instruction, 0, words, offset, instruction.length);
            offset += instruction.length;
        }
        return bytes(words);
    }

    private static int[] instruction(int opcode, int... operands) {
        int[] words = new int[operands.length + 1];
        words[0] = (operands.length + 1) << 16 | opcode;
        System.arraycopy(operands, 0, words, 1, operands.length);
        return words;
    }

    private static int[] namedInstruction(int id, String name) {
        int[] string = stringWords(name);
        int[] operands = new int[1 + string.length];
        operands[0] = id;
        System.arraycopy(string, 0, operands, 1, string.length);
        return instruction(5, operands);
    }

    private static int[] stringWords(String value) {
        byte[] bytes = (value + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int[] words = new int[(bytes.length + 3) / 4];
        for (int index = 0; index < bytes.length; index++) {
            words[index / 4] |= (bytes[index] & 0xff) << ((index % 4) * 8);
        }
        return words;
    }

    private static int[] words(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int[] words = new int[bytes.length / 4];
        for (int index = 0; index < words.length; index++) words[index] = buffer.getInt();
        return words;
    }

    private static byte[] bytes(int[] words) {
        ByteBuffer buffer = ByteBuffer.allocate(words.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int word : words) buffer.putInt(word);
        return buffer.array();
    }
}
