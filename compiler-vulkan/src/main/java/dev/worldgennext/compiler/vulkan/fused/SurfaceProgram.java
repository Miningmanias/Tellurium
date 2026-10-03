// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan.fused;

import dev.worldgennext.semantic.snapshot.NoiseParameters;
import dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot;

import java.util.List;
import java.util.Objects;

/**
 * Loader-neutral capture of one level's SurfaceSystem and surface rule tree
 * (Minecraft 1.21.1), lowered to a flat branch program the fused surface
 * kernels interpret.
 *
 * <p>Code is {@link #INSTRUCTION_INTS} ints per instruction:
 * {@code [opcode | NEGATE, a, b, c, d, failTarget]}.  A condition whose
 * (possibly negated) result is true falls through to the next instruction,
 * otherwise control moves to {@code failTarget} (an instruction index).
 * RETURN opcodes end evaluation.  OP_NOISE reads noise slot {@code a} (one value per column)
 * against the bounds pair {@code b}.</p>
 */
public record SurfaceProgram(
        int[] code,
        int[] biomeMasks, int maskWords,
        List<NoiseParameters.CapturedNoise> conditionNoises, double[] conditionNoiseBounds,
        List<PositionalRandomFactorySnapshot> gradientRandoms,
        NoiseParameters.CapturedNoise surfaceNoise, NoiseParameters.CapturedNoise surfaceSecondaryNoise,
        NoiseParameters.CapturedNoise clayBandsOffsetNoise,
        NoiseParameters.CapturedNoise badlandsPillarNoise, NoiseParameters.CapturedNoise badlandsPillarRoofNoise,
        NoiseParameters.CapturedNoise badlandsSurfaceNoise,
        NoiseParameters.CapturedNoise icebergPillarNoise, NoiseParameters.CapturedNoise icebergPillarRoofNoise,
        NoiseParameters.CapturedNoise icebergSurfaceNoise,
        PositionalRandomFactorySnapshot noiseRandom,
        int[] clayBands, int[] paletteFlags,
        int snowBlock, int packedIce, int defaultBlock,
        float[] biomeBaseTemperature, int[] biomeFlags,
        boolean biomeLookupAtY0, long biomeZoomSeed,
        SimplexOctaves temperatureNoise, SimplexOctaves frozenTemperatureNoise, SimplexOctaves biomeInfoNoise,
        String identity) {

    public static final int INSTRUCTION_INTS = 6;
    public static final int NEGATE = 0x100;
    public static final int OP_RETURN_NULL = 0, OP_RETURN_STATE = 1, OP_RETURN_BAND = 2,
            OP_BIOME = 3, OP_NOISE = 4, OP_VERTICAL_GRADIENT = 5, OP_Y_ABOVE = 6, OP_WATER = 7,
            OP_TEMPERATURE = 8, OP_STEEP = 9, OP_HOLE = 10, OP_ABOVE_PRELIMINARY = 11, OP_STONE_DEPTH = 12;

    /** Palette flag bits beyond {@link FusedNoiseCompiler.MaterialPalette}'s three. */
    public static final int FLAG_IS_WATER_BLOCK = 8, FLAG_IS_DEFAULT_BLOCK = 16, FLAG_IS_DEFAULT_STATE = 32;
    public static final int BIOME_FROZEN_MODIFIER = 1, BIOME_ERODED_BADLANDS = 2, BIOME_FROZEN_OCEAN = 4;
    /** One byte per block with the top bit reserved for the post-processing mark. */
    public static final int MAX_PALETTE = 127;
    public static final int MAX_CONDITION_NOISES = 128;
    public static final int MAX_BIOMES = 65535;

    /** PerlinSimplexNoise: octave levels from index 0, null levels have an empty permutation. */
    public record SimplexOctaves(double highestFreqInputFactor, double highestFreqValueFactor, List<List<Integer>> permutations) {
        public SimplexOctaves {
            permutations = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(permutations));
        }
    }

    public SurfaceProgram {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(identity, "identity");
        if (code.length == 0 || code.length % INSTRUCTION_INTS != 0) throw new IllegalArgumentException("Malformed surface code");
        if (paletteFlags.length > MAX_PALETTE) throw new IllegalArgumentException("Surface palette exceeds " + MAX_PALETTE + " states");
        if (conditionNoises.size() > MAX_CONDITION_NOISES) throw new IllegalArgumentException("Too many noise conditions");
        if (conditionNoiseBounds.length % 2 != 0) throw new IllegalArgumentException("Noise bounds must be min/max pairs");
        if (clayBands.length != 192) throw new IllegalArgumentException("Clay bands must have 192 entries");
        if (biomeBaseTemperature.length != biomeFlags.length || biomeFlags.length > MAX_BIOMES) throw new IllegalArgumentException("Invalid biome table");
        if (maskWords != (biomeFlags.length + 31) / 32 || biomeMasks.length % Math.max(1, maskWords) != 0) throw new IllegalArgumentException("Invalid biome masks");
    }

    public int biomeCount() { return biomeFlags.length; }
}
