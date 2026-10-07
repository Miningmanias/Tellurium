// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

import dev.tellurium.compiler.vulkan.worldgen.IntegerIeeeEmitter;
import dev.tellurium.compiler.vulkan.worldgen.NativeDraftMath;
import dev.tellurium.compiler.vulkan.worldgen.WorldgenShaderCompiler;
import dev.tellurium.runtime.vulkan.production.VulkanWorldgenExecutor;
import dev.tellurium.semantic.program.NumericProfile;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Opt-in actual-device regression for the staged wg_node_685 FP64 range parent.
 * This is neither Minecraft parity nor G6 qualification. CPU reference words
 * are comparison data only; all reported output comes from executeRawBatched.
 *
 * <p>Input stride: xyz/reserved followed by selector, whenIn, whenOut (each
 * low/high uint). Output stride: normalized result low/high, parent return
 * low/high, sticky stage-failure flag, parent-return finite flag. Like the
 * captured stage accessor, a read of a nonfinite child sets wg_failed and
 * returns canonical qNaN; an unread child must have no effect.</p>
 */
public final class NativeRangeChoiceSmoke {
    static final int INPUT_STRIDE = 10;
    static final int OUTPUT_STRIDE = 6;
    static final int LOCAL_SIZE = 64;
    static final int MAX_CASES = 256;
    static final long QNAN = 0x7ff8000000000000L;

    private NativeRangeChoiceSmoke() { }

    record Vector(long selector, long whenIn, long whenOut) { }

    record Options(int batchSize, boolean nativeDraft) {
        static Options parse(String[] args) {
            int batch = 7;
            boolean draft = false;
            for (String arg : args) {
                if (arg.equals("--native-draft")) draft = true;
                else if (arg.startsWith("--batch-size=")) {
                    batch = Integer.parseInt(arg.substring("--batch-size=".length()));
                } else throw new IllegalArgumentException("Unknown argument: " + arg
                        + "; usage: [--batch-size=1..64] [--native-draft]");
            }
            if (batch < 1 || batch > LOCAL_SIZE) {
                throw new IllegalArgumentException("Batch size must be in [1, 64]");
            }
            return new Options(batch, draft);
        }
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        List<Vector> vectors = vectors();
        int[] input = inputWords(vectors);
        // Full request, configurable slices, and mandatory partial workgroup/tail.
        var sizes = new LinkedHashSet<Integer>();
        sizes.add(vectors.size());
        sizes.add(options.batchSize());
        sizes.add(63); // 192 fixtures => three full slices plus a three-element tail.
        try (var executor = new VulkanWorldgenExecutor()) {
            run(executor, NumericProfile.GPU_IEEE_BITS, vectors, input, sizes);
            if (options.nativeDraft()) {
                run(executor, NumericProfile.GPU_NATIVE_DRAFT, vectors, input, sizes);
            }
            var storage = executor.storageTelemetry();
            if (storage.bufferAllocations() != 2 || storage.reusedDispatches() == 0) {
                throw new IllegalStateException("Bounded slices did not reuse the completed native slot: " + storage);
            }
            System.out.println("nativeRangeChoice storage=" + storage);
        }
    }

    private static void run(VulkanWorldgenExecutor executor, NumericProfile profile,
                            List<Vector> vectors, int[] input, Iterable<Integer> sizes) {
        var shader = shader(profile);
        // Pin the ordinary optimized/inlined control, independently of ambient properties.
        var request = new VulkanWorldgenExecutor.RawRequest(shader, input, INPUT_STRIDE,
                OUTPUT_STRIDE, vectors.size(), 0, 0, 0, false, false, "");
        for (int size : sizes) {
            executor.resetTelemetry();
            var result = executor.executeRawBatched(request, size);
            if (result.elementCount() != vectors.size()
                    || result.outputWordsPerElement() != OUTPUT_STRIDE
                    || !result.device().equals(executor.device())) {
                throw new IllegalStateException("Incomplete or inconsistent device result provenance");
            }
            int[] output = result.outputWords();
            int mismatches = mismatches(vectors, output);
            var telemetry = executor.telemetry();
            long dispatches = (vectors.size() + size - 1L) / size;
            if (telemetry.dispatches() != dispatches || telemetry.elements() != vectors.size()) {
                throw new IllegalStateException("Incomplete actual-device dispatch/element counts: " + telemetry);
            }
            System.out.println("nativeRangeChoice profile=" + profile
                    + " qualification=UNQUALIFIED_STAGED_PARENT_ONLY"
                    + " verdict=" + (mismatches == 0 ? "DEVICE_PASS" : "DEVICE_FAIL")
                    + " device=" + result.device()
                    + " comparedElements=" + vectors.size() + " comparedWords=" + output.length
                    + " mismatchedElements=" + mismatches + " sliceLimit=" + size
                    + " tailElements=" + (vectors.size() % size)
                    + " shaderSha256=" + result.shaderHash() + " spirvSha256=" + result.spirvHash()
                    + " telemetry=" + telemetry);
            if (mismatches != 0) {
                throw new IllegalStateException(profile + " staged RangeChoice mismatches=" + mismatches);
            }
        }
    }

    static List<Vector> vectors() {
        long[] selectors = {
                bits(+0.0), bits(-0.0), bits(Double.MIN_VALUE), bits(-Double.MIN_VALUE),
                bits(Double.MIN_NORMAL), bits(-Double.MIN_NORMAL),
                bits(Math.nextDown(-60.0)), bits(-60.0), bits(Math.nextUp(-60.0)),
                bits(-61.0), bits(-1.0), bits(1.0), bits(320.0),
                bits(Math.nextDown(321.0)), bits(321.0), bits(Math.nextUp(321.0)), bits(322.0),
                bits(Double.MAX_VALUE), bits(-Double.MAX_VALUE),
                bits(Double.POSITIVE_INFINITY), bits(Double.NEGATIVE_INFINITY),
                QNAN, 0x7ff0000000000001L, 0xfff8123456789abcL
        };
        long[][] branches = {
                {bits(-0.0), bits(+0.0)}, {bits(1.25), bits(-2.5)},
                {bits(-Double.MIN_VALUE), bits(Double.MIN_VALUE)},
                {bits(Double.MAX_VALUE), bits(-Double.MAX_VALUE)},
                {0x7ff0000000000001L, bits(42.0)},
                {bits(42.0), bits(Double.POSITIVE_INFINITY)},
                {bits(Double.NEGATIVE_INFINITY), QNAN},
                {0xfff8123456789abcL, bits(Double.NEGATIVE_INFINITY)}
        };
        var result = new ArrayList<Vector>();
        for (long selector : selectors) {
            for (long[] branch : branches) result.add(new Vector(selector, branch[0], branch[1]));
        }
        if (result.isEmpty() || result.size() > MAX_CASES) {
            throw new IllegalStateException("Fixture count exceeds bounded smoke envelope");
        }
        return List.copyOf(result);
    }

    static int[] inputWords(List<Vector> vectors) {
        if (vectors.isEmpty() || vectors.size() > MAX_CASES) {
            throw new IllegalArgumentException("Fixture count must be in [1, " + MAX_CASES + "]");
        }
        int[] words = new int[vectors.size() * INPUT_STRIDE];
        for (int i = 0; i < vectors.size(); i++) {
            int base = i * INPUT_STRIDE;
            // Distinct coordinates survive slicing; no absolute-index pointer rebasing needed.
            words[base] = i - 96;
            words[base + 1] = 67;
            words[base + 2] = 523 - i;
            Vector vector = vectors.get(i);
            put(words, base + 4, vector.selector());
            put(words, base + 6, vector.whenIn());
            put(words, base + 8, vector.whenOut());
        }
        return words;
    }

    /** Independent Java comparisons and classification, with lazy stage reads. */
    static int[] reference(Vector vector) {
        double selector = Double.longBitsToDouble(vector.selector());
        boolean failed = !Double.isFinite(selector);
        if (failed) selector = Double.NaN;
        long selected = selector >= -60.0 && selector < 321.0 ? vector.whenIn() : vector.whenOut();
        boolean finite = Double.isFinite(Double.longBitsToDouble(selected));
        long parent = finite ? selected : QNAN;
        failed |= !finite;
        int[] words = new int[OUTPUT_STRIDE];
        put(words, 0, failed ? QNAN : parent);
        put(words, 2, parent);
        words[4] = failed ? 1 : 0;
        words[5] = finite ? 1 : 0;
        return words;
    }

    static int mismatches(List<Vector> vectors, int[] output) {
        if (vectors.isEmpty() || output.length != vectors.size() * OUTPUT_STRIDE) {
            throw new IllegalArgumentException("Missing or malformed comparison coverage");
        }
        int mismatches = 0;
        for (int i = 0; i < vectors.size(); i++) {
            int[] expected = reference(vectors.get(i));
            boolean differs = false;
            for (int word = 0; word < OUTPUT_STRIDE; word++) {
                int actual = output[i * OUTPUT_STRIDE + word];
                if (actual != expected[word]) {
                    if (mismatches < 8) System.err.printf(
                            "range mismatch element=%d vector=%s word=%d expected=%08x actual=%08x%n",
                            i, vectors.get(i), word, expected[word], actual);
                    differs = true;
                }
            }
            if (differs) mismatches++;
        }
        return mismatches;
    }

    private static long bits(double value) { return Double.doubleToRawLongBits(value); }

    private static void put(int[] words, int offset, long carrier) {
        words[offset] = (int) carrier;
        words[offset + 1] = (int) (carrier >>> 32);
    }

    static WorldgenShaderCompiler.Shader shader(NumericProfile profile) {
        if (profile != NumericProfile.GPU_IEEE_BITS && profile != NumericProfile.GPU_NATIVE_DRAFT) {
            throw new IllegalArgumentException("Unsupported smoke profile: " + profile);
        }
        String source = """
                #version 450
                layout(local_size_x=64, local_size_y=1, local_size_z=1) in;
                layout(std430, binding=0) readonly buffer Inputs { uint inputBits[]; };
                layout(std430, binding=1) writeonly buffer Outputs { uint outputBits[]; };
                layout(push_constant) uniform Dispatch {
                    uint count; uint defaultStateId; uint airStateId; uint invalidStateId;
                } dispatch;
                bool wg_failed;
                """ + new IntegerIeeeEmitter().emit(NumericProfile.GPU_IEEE_BITS) + """
                int wg_i32_from_bits(uint bits) {
                    if ((bits & 0x80000000u) == 0u) return int(bits);
                    if (bits == 0x80000000u) return -2147483647 - 1;
                    return -int((~bits) + 1u);
                }
                ivec3 wg_point(uint index) {
                    uint base = index * 10u;
                    return ivec3(wg_i32_from_bits(inputBits[base]),
                                 wg_i32_from_bits(inputBits[base + 1u]),
                                 wg_i32_from_bits(inputBits[base + 2u]));
                }
                uvec2 wg_stage_density_value(uint slot, ivec3 point) {
                    uint index = gl_GlobalInvocationID.x;
                    ivec3 original = wg_point(index);
                    if (point.x != original.x || point.y != original.y || point.z != original.z) {
                        wg_failed = true; return wg_fp64_qnan();
                    }
                    uint valueBase = index * 10u + 4u + slot * 2u;
                    uvec2 value = uvec2(inputBits[valueBase], inputBits[valueBase + 1u]);
                    if (!wg_fp64_finite(value)) {
                        wg_failed = true; return wg_fp64_qnan();
                    }
                    return value;
                }
                uvec2 wg_node_75(ivec3 point) { return wg_stage_density_value(2u, point); }
                uvec2 wg_node_686(ivec3 point) { return wg_stage_density_value(0u, point); }
                uvec2 wg_node_700(ivec3 point) { return wg_stage_density_value(1u, point); }
                uvec2 wg_node_685(ivec3 point) {
                    uvec2 selector = wg_node_686(point);
                    if (wg_fp64_less_equal(uvec2(0x0u, 0xc04e0000u), selector)
                            && wg_fp64_less(selector, uvec2(0x0u, 0x40741000u))) return wg_node_700(point);
                    return wg_node_75(point);
                }
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uvec2 parent = wg_node_685(wg_point(index));
                    bool finite = wg_fp64_finite(parent);
                    uvec2 value = parent;
                    if (wg_failed || !finite) value = wg_fp64_qnan();
                    uint base = index * 6u;
                    outputBits[base] = value.x;
                    outputBits[base + 1u] = value.y;
                    outputBits[base + 2u] = parent.x;
                    outputBits[base + 3u] = parent.y;
                    outputBits[base + 4u] = wg_failed ? 1u : 0u;
                    outputBits[base + 5u] = finite ? 1u : 0u;
                }
                """;
        if (profile == NumericProfile.GPU_NATIVE_DRAFT) source = NativeDraftMath.rewrite(source);
        // Execution uses the existing shaderc + SpirvNumericContract validation path.
        return new WorldgenShaderCompiler.Shader(source, "staged-range-choice-smoke-v1",
                profile, LOCAL_SIZE);
    }
}
