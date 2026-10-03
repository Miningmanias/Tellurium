// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan;

import dev.worldgennext.compiler.vulkan.worldgen.SharedBlendedReductionStageEmitter;
import dev.worldgennext.compiler.vulkan.worldgen.SharedFp64DivisionStageEmitter;
import dev.worldgennext.compiler.vulkan.worldgen.WorldgenShaderCompiler;
import dev.worldgennext.runtime.vulkan.production.VulkanWorldgenExecutor;
import dev.worldgennext.semantic.program.NumericProfile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/** Physical raw-carrier reduction counterexamples, not release or Minecraft qualification. */
public final class NativeBlendedReductionSmoke {
    public static void main(String[] args) {
        var vectors = new ArrayList<double[]>();
        Random random = new Random(0x30b1eL);
        for (int index = 0; index < 128; index++) {
            double[] samples = new double[40];
            for (int slot = 0; slot < 40; slot++) samples[slot] = random.nextDouble() * 2.0 - 1.0;
            vectors.add(samples);
        }
        for (double main : new double[]{-20, -10, Math.nextDown(-10), Math.nextUp(-10),
                -0.0, 0.0, Math.nextDown(10), 10, Math.nextUp(10), 20}) {
            double[] samples = new double[40];
            samples[0] = main;
            for (int i = 8; i < 40; i++) samples[i] = ((i % 3) - 1) / 32.0;
            if (main >= 10) Arrays.fill(samples, 8, 24, Double.NaN); // Skipped min branch.
            if (main <= -10) Arrays.fill(samples, 24, 40, Double.NaN); // Skipped max branch.
            vectors.add(samples);
        }
        for (long raw : new long[]{0L, Long.MIN_VALUE, 1L, Long.MIN_VALUE | 1L,
                0x000fffffffffffffL, 0x0010000000000000L}) {
            double[] samples = new double[40]; Arrays.fill(samples, Double.longBitsToDouble(raw));
            vectors.add(samples);
        }
        for (int slot : new int[]{0, 7, 8, 23, 24, 39}) {
            double[] samples = new double[40]; samples[slot] = Double.NaN; vectors.add(samples);
        }
        int count = vectors.size();
        int[] input = new int[count * 84];
        for (int index = 0; index < count; index++) {
            int base = index * 84;
            input[base] = -index; input[base + 1] = Integer.MIN_VALUE;
            input[base + 2] = index; input[base + 3] = 0x7fc00001;
            for (int slot = 0; slot < 40; slot++) store(input, base + 4 + slot * 2,
                    Double.doubleToRawLongBits(vectors.get(index)[slot]));
        }
        var prepare = shader(SharedBlendedReductionStageEmitter.prepareSource(64), "prepare");
        var init = shader(SharedFp64DivisionStageEmitter.initSource(64, 4, 6), "div-init");
        var divFinish = shader(SharedFp64DivisionStageEmitter.finishSource(64), "div-finish");
        var finish = shader(SharedBlendedReductionStageEmitter.finishSource(64), "finish");
        var chunks = new ArrayList<WorldgenShaderCompiler.Shader>();
        for (int chunk = 0; chunk < SharedFp64DivisionStageEmitter.CHUNK_COUNT; chunk++)
            chunks.add(shader(SharedFp64DivisionStageEmitter.chunkSource(64, chunk), "div-chunk-" + chunk));
        try (var executor = new VulkanWorldgenExecutor(4 * 1024 * 1024)) {
            for (boolean disabled : new boolean[]{true, false}) {
                for (int slice : new int[]{count, 37}) {
                    System.out.println("nativeBlendedReduction dispatch-start count=" + count + " slice=" + slice
                            + " disableOptimization=" + disabled + " prepareChars=" + prepare.source().length()
                            + " finishChars=" + finish.source().length());
                    var prepared = dispatch(executor, prepare, input, 84, 8, count, disabled, slice);
                    int[] preparedWords = prepared.outputWords();
                    for (int index = 0; index < count; index++) {
                        double expected = mainSum(vectors.get(index));
                        requireRaw(expected, load(preparedWords, index * 8 + 4), "main sum", index);
                        if (load(preparedWords, index * 8 + 6) != Double.doubleToRawLongBits(10.0))
                            throw new IllegalStateException("Reduction denominator differs at " + index);
                    }
                    var state = dispatch(executor, init, preparedWords, 8, 16, count, disabled, slice);
                    for (var chunk : chunks)
                        state = dispatch(executor, chunk, state.outputWords(), 16, 16, count, disabled, slice);
                    var quotient = dispatch(executor, divFinish, state.outputWords(), 16, 2, count, disabled, slice);
                    int[] quotientWords = quotient.outputWords();
                    int[] finishInput = new int[count * 86];
                    for (int index = 0; index < count; index++) {
                        requireRaw(mainSum(vectors.get(index)) / 10.0, load(quotientWords, index * 2), "main division", index);
                        System.arraycopy(input, index * 84, finishInput, index * 86, 84);
                        finishInput[index * 86 + 84] = quotientWords[index * 2];
                        finishInput[index * 86 + 85] = quotientWords[index * 2 + 1];
                    }
                    var result = dispatch(executor, finish, finishInput, 86, 2, count, disabled, slice);
                    int[] words = result.outputWords();
                    for (int index = 0; index < count; index++)
                        requireRaw(reference(vectors.get(index)), load(words, index * 2), "final", index);
                    System.out.println("nativeBlendedReduction DEVICE_PASS qualification=UNQUALIFIED_REGRESSION_ONLY"
                            + " comparedElements=" + count + " checkpoints=3 slice=" + slice
                            + " disableOptimization=" + disabled + " device=" + result.device().name()
                            + " prepareShader=" + prepared.shaderHash() + " finishShader=" + result.shaderHash()
                            + " finishSpirv=" + result.spirvHash());
                }
            }
        }
    }

    private static double mainSum(double[] samples) {
        double sum = 0.0, scale = 1.0;
        for (int octave = 0; octave < 8; octave++) { sum += samples[octave] / scale; scale *= 0.5; }
        return Double.isFinite(sum) ? sum : Double.NaN;
    }

    /** Original division/order/lazy accumulation, not the emitter's multiplication implementation. */
    private static double reference(double[] samples) {
        double blendCoordinate = (mainSum(samples) / 10.0 + 1.0) * 0.5;
        double min = 0.0, max = 0.0, scale = 1.0;
        for (int octave = 0; octave < 16; octave++) {
            if (!(1.0 <= blendCoordinate)) min += samples[8 + octave] / scale;
            if (!(blendCoordinate <= 0.0)) max += samples[24 + octave] / scale;
            scale *= 0.5;
        }
        double lower = min / 512.0, upper = max / 512.0;
        double blend = Math.max(0.0, Math.min(1.0, blendCoordinate));
        double value = (lower + blend * (upper - lower)) / 128.0;
        return Double.isFinite(value) ? value : Double.NaN;
    }

    private static void requireRaw(double expected, long actual, String label, int index) {
        if (actual != Double.doubleToRawLongBits(expected)
                && !(Double.isNaN(expected) && Double.isNaN(Double.longBitsToDouble(actual))))
            throw new IllegalStateException("Blended reduction " + label + " differs at " + index
                    + " expected=" + Double.toHexString(expected) + " actual=" + Long.toHexString(actual));
    }
    private static VulkanWorldgenExecutor.RawResult dispatch(VulkanWorldgenExecutor executor,
            WorldgenShaderCompiler.Shader shader, int[] input, int stride, int output,
            int count, boolean disabled, int slice) {
        return executor.executeRawBatched(new VulkanWorldgenExecutor.RawRequest(shader, input, stride, output, count)
                .withDontInlineFunctions(false, "").withPipelineOptimizationDisabled(disabled), slice);
    }
    private static WorldgenShaderCompiler.Shader shader(String source, String label) {
        return new WorldgenShaderCompiler.Shader(source, "shared-blended-reduction-v1/" + label, NumericProfile.GPU_IEEE_BITS, 64);
    }
    private static void store(int[] words, int offset, long raw) { words[offset] = (int) raw; words[offset + 1] = (int) (raw >>> 32); }
    private static long load(int[] words, int offset) { return Integer.toUnsignedLong(words[offset]) | ((long) words[offset + 1] << 32); }
}
