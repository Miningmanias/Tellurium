// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan.worldgen;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Small post-link SPIR-V transform used by opt-in driver diagnostics.
 *
 * <p>The transform changes only the {@code OpFunction} control mask for
 * functions selected by an {@code OpName} prefix.  It does not rewrite
 * executable instructions or numeric representations.  The caller must opt
 * in explicitly because function-control hints are a driver compilation
 * policy, not part of the worldgen numeric contract.</p>
 */
public final class SpirvFunctionControlPatcher {
    private static final int SPIRV_MAGIC = 0x07230203;
    private static final int OP_NAME = 5;
    private static final int OP_FUNCTION = 54;
    /** SPIR-V Function Control mask bit for DontInline. */
    private static final int DONT_INLINE = 0x2;

    private SpirvFunctionControlPatcher() { }

    /**
     * Add the SPIR-V DontInline hint to named functions whose name starts
     * with one of the comma-separated prefixes in {@code namePrefix}.  The
     * input is copied even when no function is selected.  Malformed modules
     * fail closed rather than being modified.
     *
     * <p>A prefix list lets a caller keep a captured program graph out of the
     * driver inlining pass without also marking the software arithmetic
     * helpers.  Those helpers contain bounded loops and must remain ordinary
     * callable code on drivers where a function-control hint on the helper
     * itself is unsafe.</p>
     */
    public static byte[] addDontInlineToNamedFunctions(byte[] module, String namePrefix) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(namePrefix, "namePrefix");
        String[] prefixes = namePrefix.split(",", -1);
        for (String prefix : prefixes) {
            if (prefix.isEmpty()) throw new IllegalArgumentException("Function name prefix must not be empty");
        }
        if (module.length < 20 || (module.length & 3) != 0) {
            throw new IllegalArgumentException("SPIR-V module must have a complete word-aligned header");
        }

        int[] words = toWords(module);
        if (words[0] != SPIRV_MAGIC) throw new IllegalArgumentException("Invalid SPIR-V magic");
        Map<Integer, String> names = new HashMap<>();
        for (int index = 5; index < words.length; ) {
            int wordCount = wordCount(words[index], index, words.length);
            int opcode = words[index] & 0xffff;
            if (opcode == OP_NAME) {
                if (wordCount < 3) throw new IllegalArgumentException("Malformed OpName at word " + index);
                names.put(words[index + 1], decodeString(words, index + 2, wordCount - 2));
            }
            index += wordCount;
        }

        int changed = 0;
        for (int index = 5; index < words.length; ) {
            int wordCount = wordCount(words[index], index, words.length);
            int opcode = words[index] & 0xffff;
            if (opcode == OP_FUNCTION) {
                if (wordCount < 5) throw new IllegalArgumentException("Malformed OpFunction at word " + index);
                String name = names.get(words[index + 2]);
                if (name != null && startsWithAny(name, prefixes)) {
                    int controlIndex = index + 3;
                    int previous = words[controlIndex];
                    words[controlIndex] = previous | DONT_INLINE;
                    if (words[controlIndex] != previous) changed++;
                }
            }
            index += wordCount;
        }
        return toBytes(words);
    }

    private static boolean startsWithAny(String name, String[] prefixes) {
        for (String prefix : prefixes) if (name.startsWith(prefix)) return true;
        return false;
    }

    private static int wordCount(int instruction, int index, int wordLength) {
        int count = instruction >>> 16;
        if (count < 1 || index + count > wordLength) {
            throw new IllegalArgumentException("Malformed SPIR-V instruction at word " + index);
        }
        return count;
    }

    private static String decodeString(int[] words, int offset, int wordCount) {
        byte[] bytes = new byte[wordCount * Integer.BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < wordCount; index++) buffer.putInt(words[offset + index]);
        int length = 0;
        while (length < bytes.length && bytes[length] != 0) length++;
        if (length == bytes.length) throw new IllegalArgumentException("SPIR-V literal string is not terminated");
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    private static int[] toWords(byte[] module) {
        ByteBuffer bytes = ByteBuffer.wrap(module).order(ByteOrder.LITTLE_ENDIAN);
        int[] words = new int[module.length / Integer.BYTES];
        for (int index = 0; index < words.length; index++) words[index] = bytes.getInt();
        return words;
    }

    private static byte[] toBytes(int[] words) {
        ByteBuffer bytes = ByteBuffer.allocate(words.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int word : words) bytes.putInt(word);
        return bytes.array();
    }
}
