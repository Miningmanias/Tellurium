// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.worldgen;

import dev.tellurium.semantic.snapshot.BeardifierSnapshot;
import dev.tellurium.semantic.snapshot.StructureBlendSnapshot;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BeardifierKernelInputStageTest {
    private static final String READ = "wg_beard_kernel[kernelZ * 576 + kernelX * 24 + kernelY]";
    private static final String SOURCE = BeardifierEmitter.source(new StructureBlendSnapshot(
            "kernel-test", Map.of(), Map.of(), new BeardifierSnapshot(
                    List.of(new BeardifierSnapshot.Rigid(-2, -2, -2, 2, 2, 2,
                            BeardifierSnapshot.Adjustment.BEARD_BOX, 2)),
                    List.of(new BeardifierSnapshot.Junction(-3, 0, 4)))));
    private static final int TABLE_END = SOURCE.indexOf(");") + 2;
    private static final String TABLE = SOURCE.substring(0, TABLE_END);

    @Test void realEmitterCarriersAndEveryOtherArithmeticByteArePreserved() {
        var stage = BeardifierKernelInputStage.bind(SOURCE, 4);
        String[] literals = TABLE.substring(TABLE.indexOf('(') + 1, TABLE.lastIndexOf(')')).split(",");
        assertEquals(13824, literals.length);
        int[] expected = Arrays.stream(literals).map(String::strip)
                .mapToInt(s -> (int) Long.parseLong(s.substring(2, s.length() - 1), 16)).toArray();
        assertArrayEquals(expected, stage.kernelWords());
        // Removing only the new helper and undoing only the admitted lookup must
        // recover the complete original body, including all ordered arithmetic.
        int helperEnd = stage.source().indexOf("\n}\n") + 3;
        assertEquals(SOURCE.substring(TABLE_END), stage.source().substring(helperEnd)
                .replace("wg_beard_kernel_lookup(kernelZ * 576 + kernelX * 24 + kernelY)", READ));
        assertFalse(stage.source().contains("const uint wg_beard_kernel"));
        assertFalse(stage.source().matches("(?s).*\\bwg_beard_kernel\\b.*"));
    }

    @Test void strideFourSixNineGuardsAndSuffixOffsetsAreExplicit() {
        for (int stride : new int[]{4, 6, 9}) {
            var stage = BeardifierKernelInputStage.bind(SOURCE, stride);
            String helper = stage.source().substring(0, stage.source().indexOf("\n}\n") + 3);
            assertTrue(helper.contains("index < 0 || index >= 13824"));
            assertTrue(helper.contains("uint(inputBits.length()) != dispatch.count * " + stride + "u + 13824u"));
            assertTrue(helper.contains("wg_failed = true;\n        return 0u;"));
            assertTrue(helper.contains("return inputBits[dispatch.count * " + stride + "u + uint(index)];"));
            int[] rows = new int[stride * 2];
            Arrays.setAll(rows, i -> i - stride);
            int[] input = stage.inputRows(rows);
            assertArrayEquals(rows, Arrays.copyOf(input, rows.length));
            assertArrayEquals(stage.kernelWords(), Arrays.copyOfRange(input, rows.length, input.length));
        }
        assertDoesNotThrow(() -> BeardifierKernelInputStage.bind(SOURCE, 1));
        assertDoesNotThrow(() -> BeardifierKernelInputStage.bind(SOURCE, 4096));
    }

    @Test void helperReplacesTheDeclarationAtItsOriginalFullShaderLocation() {
        String prefix = "#version 450\n// unchanged ABI prefix\n";
        var stage = BeardifierKernelInputStage.bind(prefix + SOURCE, 6);
        assertTrue(stage.source().startsWith(prefix + "uint wg_beard_kernel_lookup(int index) {"));
        String readBeforeTable = "uint value = " + READ + ";\n";
        var reordered = BeardifierKernelInputStage.bind(readBeforeTable + TABLE, 4);
        assertTrue(reordered.source().startsWith("uint value = wg_beard_kernel_lookup("
                + "kernelZ * 576 + kernelX * 24 + kernelY);\nuint wg_beard_kernel_lookup"));
    }

    @Test void constructorAccessorsAndEveryPackedArrayOwnTheirStorage() {
        int[] kernel = BeardifierKernelInputStage.bind(SOURCE, 4).kernelWords();
        int first = kernel[0];
        var stage = new BeardifierKernelInputStage.Stage("source", 4, kernel);
        kernel[0] ^= -1;
        assertEquals(first, stage.kernelWords()[0]);
        int[] exposed = stage.kernelWords();
        exposed[0] ^= -1;
        int[] rows = {1, -2, 3, 4};
        int[] packed = stage.inputRows(rows);
        rows[0] = 99;
        assertEquals(1, packed[0]);
        packed[4] ^= -1;
        packed[1] = 88;
        int[] fresh = stage.inputRows(new int[]{1, -2, 3, 4});
        assertEquals(first, fresh[4]);
        assertEquals(-2, fresh[1]);
        assertNotSame(packed, fresh);
    }

    @Test void malformedInputsAndRecordConstructionFailClosed() {
        for (int stride : new int[]{0, -1, 4097, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> BeardifierKernelInputStage.bind(SOURCE, stride));
            assertThrows(IllegalArgumentException.class, () -> new BeardifierKernelInputStage.Stage("s", stride, new int[13824]));
        }
        reject(null);
        reject("");
        var stage = BeardifierKernelInputStage.bind(SOURCE, 4);
        for (int[] rows : new int[][]{null, new int[0], new int[3], new int[5]}) {
            assertThrows(IllegalArgumentException.class, () -> stage.inputRows(rows));
        }
        for (int[] kernel : new int[][]{null, new int[0], new int[13823], new int[13825]}) {
            assertThrows(IllegalArgumentException.class, () -> new BeardifierKernelInputStage.Stage("s", 4, kernel));
        }
        assertThrows(IllegalArgumentException.class, () -> new BeardifierKernelInputStage.Stage(null, 4, new int[13824]));
    }

    @Test void missingDuplicateMalformedDimensionsCountsAndLiteralsFailClosed() {
        reject(SOURCE.substring(TABLE_END));
        reject(TABLE + SOURCE);
        reject(SOURCE.replaceFirst("\\[13824]", "[13823]"));
        reject(SOURCE.replace("uint[13824]", "uint[13825]"));
        reject(SOURCE.replaceFirst("0x[0-9a-f]+u,", ""));
        reject(SOURCE.replaceFirst("0x[0-9a-f]+u", "0u, 0u"));
        reject(SOURCE.replaceFirst("0x[0-9a-f]+u", "0x100000000u"));
        reject(SOURCE.replaceFirst("0x[0-9a-f]+u", "4294967296u"));
        for (String token : List.of("-1u", "1", "0xggu", "1.0u", "1u + 2u", "999999999999999999999u", "")) {
            reject(SOURCE.replaceFirst("0x[0-9a-f]+u", token));
        }
        reject(SOURCE.replace(");", ",);"));
        reject(SOURCE.replace("const uint", "uint"));
    }

    @Test void onlyOneAdmittedReadIsAcceptedWithWhitespaceAndRawUnsignedLiterals() {
        String spaced = SOURCE.replace(READ, "wg_beard_kernel [ kernelZ\n * 576 + kernelX *\t24 + kernelY ]")
                .replaceFirst("0x[0-9a-f]+u", "4294967295u")
                .replaceFirst("0x[0-9a-f]+u", "0x80000000u");
        var stage = BeardifierKernelInputStage.bind(spaced, 9);
        assertEquals(-1, stage.kernelWords()[0]);
        assertEquals(Integer.MIN_VALUE, stage.kernelWords()[1]);
        assertTrue(stage.source().contains("wg_beard_kernel_lookup(kernelZ\n * 576 + kernelX *\t24 + kernelY)"));
        reject(SOURCE.replace(READ, "wg_beard_kernel[0]"));
        reject(SOURCE.replace(READ, "wg_beard_kernel[kernelX * 576 + kernelZ * 24 + kernelY]"));
        reject(SOURCE.replace(READ, "0u"));
        reject(SOURCE + "\nuint unexpected = wg_beard_kernel[0];");
        reject(SOURCE + "\nuint unexpected = " + READ + ";");
        reject(SOURCE + "\nuint unexpected = wg_beard_kernel;");
        reject(SOURCE + "\nuint wg_beard_kernel_lookup(int index) { return 0u; }");
        assertDoesNotThrow(() -> BeardifierKernelInputStage.bind(SOURCE + "\nuint wg_beard_kernel_other;", 4));
    }

    private static void reject(String source) {
        assertThrows(IllegalArgumentException.class, () -> BeardifierKernelInputStage.bind(source, 4));
    }
}
