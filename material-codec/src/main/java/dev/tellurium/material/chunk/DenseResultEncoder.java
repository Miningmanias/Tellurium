// SPDX-License-Identifier: MIT
package dev.tellurium.material.chunk;

/** Host-side dense encoder; it classifies every block before choosing transport. */
public final class DenseResultEncoder {
    private DenseResultEncoder() {}
    public static ChunkNoiseResult encode(ChunkResultHeader header, BlockStateTable table, String[] states,
                                          int[] heightmaps, boolean[] fluidMarks) {
        return ChunkNoiseResult.ofDense(header, table, states, heightmaps, fluidMarks);
    }
}
