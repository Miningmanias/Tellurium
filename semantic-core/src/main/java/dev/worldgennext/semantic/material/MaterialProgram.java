// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic.material;

import java.util.Objects;

/** Ordered final material decision: aquifer, then ore, then configured default block. */
public final class MaterialProgram {
    public record Inputs(double density, boolean aquiferCandidate, String aquiferState,
                         boolean oreCandidate, String oreState, String defaultState,
                         boolean noAquiferUsesDefault) {
        public Inputs(double density, boolean aquiferCandidate, String aquiferState,
                      boolean oreCandidate, String oreState, String defaultState) {
            this(density, aquiferCandidate, aquiferState, oreCandidate, oreState, defaultState, false);
        }
        public Inputs {
            if (!Double.isFinite(density)) throw new IllegalArgumentException("Non-finite density");
            Objects.requireNonNull(aquiferState, "aquiferState"); Objects.requireNonNull(oreState, "oreState"); Objects.requireNonNull(defaultState, "defaultState");
        }
    }
    public record Decision(String state, Source source) {
        public Decision { Objects.requireNonNull(state, "state"); Objects.requireNonNull(source, "source"); }
    }
    public enum Source { AQUIFER, ORE, DEFAULT, AIR }

    private final double solidThreshold;
    public MaterialProgram(double solidThreshold) {
        if (!Double.isFinite(solidThreshold)) throw new IllegalArgumentException("Non-finite solid threshold");
        this.solidThreshold = solidThreshold;
    }
    public Decision decide(Inputs input) {
        Objects.requireNonNull(input, "input");
        // NoiseChunk's MaterialRuleList invokes the aquifer first, then the
        // ore rule, and finally falls back to the configured default block.
        // A null aquifer result is not an air decision: it must not suppress
        // an ore vein in a negative-density block, nor turn an ordinary solid
        // into air.  The density and noAquiferUsesDefault fields remain part
        // of the versioned input ABI for callers that report the intermediate
        // decision, but do not alter this rule ordering.
        if (input.aquiferCandidate()) return new Decision(input.aquiferState(), Source.AQUIFER);
        if (input.oreCandidate()) return new Decision(input.oreState(), Source.ORE);
        return new Decision(input.defaultState(), Source.DEFAULT);
    }
    public double solidThreshold() { return solidThreshold; }
}
