// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan.worldgen;

public record StageLayout(String name, int inputOffset, int outputOffset, int elementCount, int elementBytes) {
    public StageLayout {
        if (name == null || name.isBlank() || inputOffset < 0 || outputOffset < 0 || elementCount <= 0 || elementBytes <= 0) throw new IllegalArgumentException("Invalid stage layout");
        try {
            Math.addExact(inputOffset, Math.multiplyExact(elementCount, elementBytes));
            Math.addExact(outputOffset, Math.multiplyExact(elementCount, elementBytes));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Stage range overflows the bounded layout", overflow);
        }
    }
    public int inputBytes() { return Math.multiplyExact(elementCount, elementBytes); }
    public int outputBytes() { return inputBytes(); }
    public int inputEndExclusive() { return Math.addExact(inputOffset, inputBytes()); }
    public int outputEndExclusive() { return Math.addExact(outputOffset, outputBytes()); }
}
