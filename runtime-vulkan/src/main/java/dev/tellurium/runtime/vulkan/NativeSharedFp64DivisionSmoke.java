// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

import dev.tellurium.compiler.vulkan.worldgen.SharedFp64DivisionStageEmitter;
import dev.tellurium.compiler.vulkan.worldgen.WorldgenShaderCompiler;
import dev.tellurium.runtime.vulkan.production.VulkanWorldgenExecutor;
import dev.tellurium.semantic.program.NumericProfile;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Random;

/** Independent Java division/BigInteger carrier regression, not release/Minecraft qualification. */
public final class NativeSharedFp64DivisionSmoke {
    private record Vector(long leftBits, long rightBits) {
        double left() { return Double.longBitsToDouble(leftBits); }
        double right() { return Double.longBitsToDouble(rightBits); }
        long expected() { return Double.doubleToRawLongBits(left() / right()); }
        boolean ordinary() { return Double.isFinite(left()) && Double.isFinite(right()) && left() != 0.0 && right() != 0.0; }
    }

    public static void main(String[] args) {
        var vectors = new ArrayList<Vector>();
        long[] edges = {0L, Long.MIN_VALUE, 1L, Long.MIN_VALUE | 1L, 2L, 0x000fffffffffffffL,
                0x0010000000000000L, 0x0010000000000001L, 0x8010000000000000L,
                Double.doubleToRawLongBits(1.0), Double.doubleToRawLongBits(-1.0),
                Double.doubleToRawLongBits(3.0), Double.doubleToRawLongBits(0.1),
                Double.doubleToRawLongBits(Math.nextDown(1.0)), Double.doubleToRawLongBits(Math.nextUp(1.0)),
                Double.doubleToRawLongBits(Double.MAX_VALUE), Double.doubleToRawLongBits(-Double.MAX_VALUE),
                Double.doubleToRawLongBits(Double.POSITIVE_INFINITY), Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY),
                0x7ff8000000000000L, 0x7ff0000000000001L, 0xfff8000000012345L};
        for (long left : edges) for (long right : edges) vectors.add(new Vector(left, right));
        Random random = new Random(0x6430d1L);
        for (int index = 0; index < 1024; index++) vectors.add(new Vector(random.nextLong(), random.nextLong()));
        int count = vectors.size();
        var init = shader(SharedFp64DivisionStageEmitter.initSource(64, 4, 6), "init");
        var reversedInit = shader(SharedFp64DivisionStageEmitter.initSource(64, 6, 4), "init-reversed-slots");
        var finish = shader(SharedFp64DivisionStageEmitter.finishSource(64), "finish");
        var chunks = new ArrayList<WorldgenShaderCompiler.Shader>();
        for (int chunk = 0; chunk < SharedFp64DivisionStageEmitter.CHUNK_COUNT; chunk++)
            chunks.add(shader(SharedFp64DivisionStageEmitter.chunkSource(64, chunk), "chunk-" + chunk));
        try (var executor = new VulkanWorldgenExecutor(count * 40L * Integer.BYTES)) {
            for (boolean disabled : new boolean[]{true, false}) {
                for (int slice : new int[]{count, 257}) {
                    run(executor, vectors, init, chunks, finish, disabled, slice, false,
                            disabled && slice == count);
                }
            }
            run(executor, vectors, reversedInit, chunks, finish, true, 257, true, false);
            System.out.println("nativeSharedFp64Division compilationTelemetry=" + executor.compilationTelemetry());
            System.out.println("nativeSharedFp64Division spirvCacheTelemetry=" + executor.spirvCacheTelemetry());
        }
    }

    private static void run(VulkanWorldgenExecutor executor, ArrayList<Vector> vectors,
                            WorldgenShaderCompiler.Shader init, ArrayList<WorldgenShaderCompiler.Shader> chunks,
                            WorldgenShaderCompiler.Shader finish, boolean disabled, int slice,
                            boolean reversedSlots, boolean checkCarriers) {
        int count = vectors.size();
        int[] input = new int[count * 8];
        for (int index = 0; index < count; index++) {
            // Coordinate/reserved sentinels must not participate in the division.
            input[index * 8] = index;
            input[index * 8 + 1] = Integer.MIN_VALUE;
            store(input, index * 8 + (reversedSlots ? 6 : 4), vectors.get(index).leftBits());
            store(input, index * 8 + (reversedSlots ? 4 : 6), vectors.get(index).rightBits());
        }
        var state = dispatch(executor, init, input, 8, 16, count, disabled, slice);
        for (int chunk = 0; chunk < chunks.size(); chunk++) {
            state = dispatch(executor, chunks.get(chunk), state.outputWords(), 16, 16, count, disabled, slice);
            if (checkCarriers) checkCarrier(vectors, state.outputWords(), chunk);
        }
        var result = dispatch(executor, finish, state.outputWords(), 16, 2, count, disabled, slice);
        int[] output = result.outputWords();
        for (int index = 0; index < count; index++) {
            long actual = load(output, index * 2);
            long expected = vectors.get(index).expected();
            if (actual != expected && !(Double.isNaN(Double.longBitsToDouble(actual))
                    && Double.isNaN(Double.longBitsToDouble(expected)))) {
                throw new IllegalStateException("FP64 division differs at " + index + " " + vectors.get(index)
                        + " expected=" + Long.toHexString(expected) + " actual=" + Long.toHexString(actual));
            }
        }
        System.out.println("nativeSharedFp64Division DEVICE_PASS qualification=UNQUALIFIED_REGRESSION_ONLY"
                + " comparedElements=" + count + " slice=" + slice + " disableOptimization=" + disabled
                + " reversedSlots=" + reversedSlots + " carrierChecks=" + checkCarriers
                + " device=" + result.device().name() + " shader=" + result.shaderHash() + " spirv=" + result.spirvHash());
    }

    private static void checkCarrier(ArrayList<Vector> vectors, int[] words, int chunk) {
        for (int index = 0; index < vectors.size(); index++) {
            Vector vector = vectors.get(index);
            if (!vector.ordinary()) continue;
            BigInteger numerator = normalizedMantissa(vector.leftBits()).shiftLeft(55);
            BigInteger divisor = normalizedMantissa(vector.rightBits());
            BigInteger[] expected = numerator.shiftRight(104 - chunk * 4).divideAndRemainder(divisor);
            int base = index * 16;
            if (load(words, base + 6) != expected[1].longValue() || load(words, base + 8) != expected[0].longValue())
                throw new IllegalStateException("Division carrier differs at vector=" + index + " chunk=" + chunk);
        }
    }

    private static BigInteger normalizedMantissa(long raw) {
        long fraction = raw & 0x000fffffffffffffL;
        boolean subnormal = (raw & 0x7ff0000000000000L) == 0;
        BigInteger value = BigInteger.valueOf(subnormal ? fraction : fraction | (1L << 52));
        return subnormal ? value.shiftLeft(53 - value.bitLength()) : value;
    }

    private static VulkanWorldgenExecutor.RawResult dispatch(VulkanWorldgenExecutor executor,
            WorldgenShaderCompiler.Shader shader, int[] input, int inputWords, int outputWords,
            int count, boolean disabled, int slice) {
        return executor.executeRawBatched(new VulkanWorldgenExecutor.RawRequest(shader, input,
                inputWords, outputWords, count).withPipelineOptimizationDisabled(disabled)
                .withDontInlineFunctions(true, "wg_u64_,wg_fp64_"), slice);
    }

    private static WorldgenShaderCompiler.Shader shader(String source, String label) {
        return new WorldgenShaderCompiler.Shader(source, "shared-fp64-division-v1/" + label, NumericProfile.GPU_IEEE_BITS, 64);
    }
    private static void store(int[] words, int offset, long raw) { words[offset] = (int) raw; words[offset + 1] = (int) (raw >>> 32); }
    private static long load(int[] words, int offset) { return Integer.toUnsignedLong(words[offset]) | ((long) words[offset + 1] << 32); }
}
