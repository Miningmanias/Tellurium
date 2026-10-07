// SPDX-License-Identifier: MIT
package dev.tellurium.semantic.execution;

import java.nio.ByteOrder;

/** Versioned little-endian result ABI shared by runtime and material decoding. */
public record ProgramAbi(int major, int minor, int magic, ByteOrder byteOrder, int alignment) {
    public ProgramAbi {
        if (major < 1 || minor < 0 || alignment <= 0 || Integer.bitCount(alignment) != 1) throw new IllegalArgumentException("Invalid ABI version/alignment");
        if (byteOrder == null || byteOrder != ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("Only little-endian ABI is supported");
    }
    public String version() { return major + "." + minor; }
    public static ProgramAbi v1() { return new ProgramAbi(1, 0, 0x57474E32, ByteOrder.LITTLE_ENDIAN, 4); }
}
