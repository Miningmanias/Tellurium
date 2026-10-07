// SPDX-License-Identifier: MIT
package dev.tellurium.semantic.snapshot;

import java.util.List;
import java.util.Objects;

/**
 * Immutable structure-terrain inputs used by Minecraft's Beardifier.
 * Loader-specific StructurePiece and JigsawJunction objects never cross the
 * snapshot boundary.
 */
public record BeardifierSnapshot(List<Rigid> pieces, List<Junction> junctions) {
    public BeardifierSnapshot {
        pieces = List.copyOf(pieces == null ? List.of() : pieces);
        junctions = List.copyOf(junctions == null ? List.of() : junctions);
        if (pieces.stream().anyMatch(Objects::isNull) || junctions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Beardifier inputs cannot contain null entries");
        }
    }

    public static BeardifierSnapshot empty() { return new BeardifierSnapshot(List.of(), List.of()); }

    public enum Adjustment { NONE, BURY, BEARD_THIN, BEARD_BOX, ENCAPSULATE }

    public record Rigid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                        Adjustment adjustment, int groundLevelDelta) {
        public Rigid {
            Objects.requireNonNull(adjustment, "adjustment");
            if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Invalid beardifier box");
        }
    }

    public record Junction(int sourceX, int sourceGroundY, int sourceZ) {}
}
