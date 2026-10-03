// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic;

public record CellGeometry(int width, int height) {
    public CellGeometry { if (width <= 0 || height <= 0 || 16 % width != 0) throw new IllegalArgumentException("Invalid cell geometry"); }
}
