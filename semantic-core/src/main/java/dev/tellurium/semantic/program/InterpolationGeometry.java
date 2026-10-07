// SPDX-License-Identifier: MIT
package dev.tellurium.semantic.program;

import java.util.Objects;

/** Explicit block-to-lattice geometry used by an interpolated program node. */
public record InterpolationGeometry(int horizontalCell, int verticalCell) {
    public InterpolationGeometry {
        if (horizontalCell <= 0 || verticalCell <= 0 || 16 % horizontalCell != 0) {
            throw new IllegalArgumentException("Cell sizes must be positive and horizontal size must divide 16");
        }
    }

    public Cell cellAt(int x, int y, int z) {
        return new Cell(Math.floorDiv(x, horizontalCell), Math.floorDiv(y, verticalCell),
                Math.floorDiv(z, horizontalCell));
    }

    public record Cell(int x, int y, int z) {
        public Cell { Objects.requireNonNull(this); }
    }
}
