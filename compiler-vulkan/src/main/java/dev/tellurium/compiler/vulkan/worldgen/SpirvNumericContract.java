// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.worldgen;

import dev.tellurium.semantic.program.NumericProfile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Pure source/SPIR-V text contract inspection; native compilation remains an opt-in gate. */
public final class SpirvNumericContract {
    public record Inspection(boolean valid, List<String> violations) { public Inspection { violations = List.copyOf(violations); } }
    public Inspection inspect(String source, NumericProfile profile) {
        if (source == null || source.isBlank()) return new Inspection(false, List.of("empty shader source"));
        var violations = new ArrayList<String>();
        if (profile == NumericProfile.GPU_IEEE_BITS) {
            if (source.matches("(?s).*\\b(float|double|fvec[234]|dvec[234])\\b.*")) violations.add("native floating type in GPU_IEEE_BITS source");
            if (source.contains("OpFAdd") || source.contains("OpFSub") || source.contains("OpFMul") || source.contains("OpFDiv") || source.contains("OpConvertSToF")) violations.add("native floating SPIR-V operation in GPU_IEEE_BITS module");
        }
        if (!source.contains("bounds") && !source.contains("gl_GlobalInvocationID")) violations.add("dispatch bounds contract not visible");
        return new Inspection(violations.isEmpty(), violations);
    }

    /**
     * Inspect a compiled SPIR-V module for the software profile.  Source
     * inspection remains useful for fast unit tests, but it cannot prove that
     * shaderc did not introduce a floating type or operation while lowering
     * macros and helper calls.  The module parser is intentionally small: the
     * presence of OpTypeFloat is already disqualifying for GPU_IEEE_BITS, so
     * no vendor-specific disassembler is needed for this gate.
     */
    public Inspection inspectSpirv(byte[] module, NumericProfile profile) {
        if (module == null || module.length == 0) return new Inspection(false, List.of("empty SPIR-V module"));
        if ((module.length & 3) != 0) return new Inspection(false, List.of("SPIR-V byte length is not word aligned"));
        ByteBuffer words = ByteBuffer.wrap(module).order(ByteOrder.LITTLE_ENDIAN);
        if (words.getInt(0) != 0x07230203) return new Inspection(false, List.of("invalid SPIR-V magic"));
        if (module.length < 20) return new Inspection(false, List.of("truncated SPIR-V header"));

        var violations = new ArrayList<String>();
        int offset = 20;
        while (offset < module.length) {
            int instruction = words.getInt(offset);
            int wordCount = instruction >>> 16;
            int opcode = instruction & 0xffff;
            if (wordCount < 1 || offset + (long) wordCount * Integer.BYTES > module.length) {
                violations.add("malformed SPIR-V instruction at byte " + offset);
                break;
            }
            if (profile == NumericProfile.GPU_IEEE_BITS && opcode == 22) {
                violations.add("OpTypeFloat declared in GPU_IEEE_BITS module");
            }
            offset += wordCount * Integer.BYTES;
        }
        if (offset != module.length && violations.stream().noneMatch(value -> value.startsWith("malformed"))) {
            violations.add("SPIR-V instruction stream does not end on a word boundary");
        }
        return new Inspection(violations.isEmpty(), violations);
    }

    public static void require(String source, NumericProfile profile) { Inspection result = new SpirvNumericContract().inspect(source, profile); if (!result.valid()) throw new IllegalArgumentException(String.join("; ", result.violations())); }
    public static void requireSpirv(byte[] module, NumericProfile profile) {
        Inspection result = new SpirvNumericContract().inspectSpirv(module, profile);
        if (!result.valid()) throw new IllegalArgumentException(String.join("; ", result.violations()));
    }
}
