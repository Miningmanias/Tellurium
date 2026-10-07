// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.worldgen;

import dev.tellurium.semantic.snapshot.BeardifierSnapshot;
import dev.tellurium.semantic.snapshot.StructureBlendSnapshot;
import java.util.Set;

/**
 * Emits the immutable, outer Minecraft Beardifier contribution.
 *
 * <p>The loader capture stores the Beardifier pieces and junctions as plain
 * integer records.  The kernel is the same float table Minecraft builds at
 * class initialization; it is emitted as raw FP32 carriers so the software
 * FP64 route does not depend on a device exponential or native float
 * conversion.  The runtime uses this as a separate density-combine stage so
 * large captured router shaders do not inherit the kernel table.</p>
 */
public final class BeardifierEmitter {
    private static final int KERNEL_RADIUS = 12;
    private static final int KERNEL_SIZE = KERNEL_RADIUS * 2;
    private static final int KERNEL_AREA = KERNEL_SIZE * KERNEL_SIZE;
    private static final int KERNEL_WORDS = KERNEL_SIZE * KERNEL_AREA;

    private BeardifierEmitter() { }

    public static Set<String> helperRoots() {
        return Set.of("wg_fp64_add", "wg_fp64_sub", "wg_fp64_mul", "wg_fp64_div", "wg_fp64_sqrt",
                "wg_fp64_max", "wg_fp64_min", "wg_fp64_negate", "wg_fp64_from_int", "wg_fp64_from_fp32",
                "wg_fp64_finite", "wg_fp64_qnan", "wg_u64_sub", "wg_u64_shr");
    }

    public static boolean hasData(StructureBlendSnapshot blend) {
        if (blend == null || blend.beardifier() == null) return false;
        BeardifierSnapshot beardifier = blend.beardifier();
        return !beardifier.pieces().isEmpty() || !beardifier.junctions().isEmpty();
    }

    /** Emits helper functions and the fixed entry point {@code wg_beardifier}. */
    public static String source(StructureBlendSnapshot blend) {
        StringBuilder source = new StringBuilder(220_000);
        BeardifierSnapshot beardifier = blend == null ? BeardifierSnapshot.empty() : blend.beardifier();
        // The helper function remains present for a stable shader shape even
        // when the captured list is empty, so its referenced table must also
        // remain declared in that case.
        appendKernel(source);
        source.append("""
                uvec2 wg_beard_clamped_map(uvec2 value) {
                    uvec2 fraction = wg_fp64_div(value, uvec2(0u, 0x40180000u));
                    fraction = wg_fp64_max(uvec2(0u), wg_fp64_min(uvec2(0u, 0x3ff00000u), fraction));
                    return wg_fp64_add(uvec2(0u, 0x3ff00000u),
                            wg_fp64_mul(fraction, uvec2(0u, 0xbff00000u)));
                }

                uvec2 wg_beard_fast_inv_sqrt(uvec2 value) {
                    // value is non-negative for the captured Beardifier path,
                    // so Java's signed long shift is the same raw operation.
                    uvec2 estimateBits = wg_u64_sub(uvec2(0xc7b537aau, 0x5fe6eb50u),
                            wg_u64_shr(value, 1u));
                    uvec2 estimate = estimateBits;
                    uvec2 halfValue = wg_fp64_mul(value, uvec2(0u, 0x3fe00000u));
                    uvec2 correction = wg_fp64_sub(uvec2(0u, 0x3ff80000u),
                            wg_fp64_mul(wg_fp64_mul(halfValue, estimate), estimate));
                    return wg_fp64_mul(estimate, correction);
                }

                uvec2 wg_beard_bury(int x, int y, int z, bool halfX, bool halfY, bool halfZ) {
                    uvec2 fx = wg_fp64_from_int(x);
                    uvec2 fy = wg_fp64_from_int(y);
                    uvec2 fz = wg_fp64_from_int(z);
                    if (halfX) fx = wg_fp64_mul(fx, uvec2(0u, 0x3fe00000u));
                    if (halfY) fy = wg_fp64_mul(fy, uvec2(0u, 0x3fe00000u));
                    if (halfZ) fz = wg_fp64_mul(fz, uvec2(0u, 0x3fe00000u));
                    uvec2 distance = wg_fp64_sqrt(wg_fp64_add(wg_fp64_add(
                            wg_fp64_mul(fx, fx), wg_fp64_mul(fy, fy)), wg_fp64_mul(fz, fz)));
                    return wg_beard_clamped_map(distance);
                }

                uvec2 wg_beard(int x, int y, int z, int groundDelta) {
                    int kernelX = x + 12;
                    int kernelY = y + 12;
                    int kernelZ = z + 12;
                    if (kernelX < 0 || kernelX >= 24 || kernelY < 0 || kernelY >= 24
                            || kernelZ < 0 || kernelZ >= 24) return uvec2(0u);
                    uvec2 halfValue = wg_fp64_add(wg_fp64_from_int(groundDelta),
                            uvec2(0u, 0x3fe00000u));
                    uvec2 distanceSquared = wg_fp64_add(wg_fp64_add(
                            wg_fp64_mul(wg_fp64_from_int(x), wg_fp64_from_int(x)),
                            wg_fp64_mul(halfValue, halfValue)),
                            wg_fp64_mul(wg_fp64_from_int(z), wg_fp64_from_int(z)));
                    uvec2 inverse = wg_beard_fast_inv_sqrt(
                            wg_fp64_mul(distanceSquared, uvec2(0u, 0x3fe00000u)));
                    uvec2 contribution = wg_fp64_div(wg_fp64_mul(
                            wg_fp64_negate(halfValue), inverse), uvec2(0u, 0x40000000u));
                    uint kernelBits = wg_beard_kernel[kernelZ * 576 + kernelX * 24 + kernelY];
                    return wg_fp64_mul(contribution, wg_fp64_from_fp32(kernelBits));
                }

                uvec2 wg_beardifier(ivec3 point) {
                    uvec2 result = uvec2(0u);
                """);
        for (BeardifierSnapshot.Rigid rigid : beardifier.pieces()) {
            appendRigid(source, rigid);
        }
        for (BeardifierSnapshot.Junction junction : beardifier.junctions()) {
            source.append("    result = wg_fp64_add(result, wg_fp64_mul(wg_beard(")
                    .append("point.x - ").append(junction.sourceX()).append(", ")
                    .append("point.y - ").append(junction.sourceGroundY()).append(", ")
                    .append("point.z - ").append(junction.sourceZ()).append(", ")
                    .append("point.y - ").append(junction.sourceGroundY()).append("), ")
                    .append("uvec2(0x9999999au, 0x3fd99999u)));\n");
        }
        source.append("    return result;\n}\n");
        return source.toString();
    }

    private static void appendRigid(StringBuilder source, BeardifierSnapshot.Rigid rigid) {
        int groundY = Math.addExact(rigid.minY(), rigid.groundLevelDelta());
        source.append("    {\n")
                .append("        int horizontalX = max(0, max(").append(rigid.minX())
                .append(" - point.x, point.x - ").append(rigid.maxX()).append("));\n")
                .append("        int horizontalZ = max(0, max(").append(rigid.minZ())
                .append(" - point.z, point.z - ").append(rigid.maxZ()).append("));\n")
                .append("        int verticalDelta = point.y - ").append(groundY).append(";\n");
        switch (rigid.adjustment()) {
            case NONE -> {
                source.append("        // NONE contributes no Beardifier value.\n");
            }
            case BURY -> source.append("        int verticalDistance = verticalDelta;\n")
                    .append("        result = wg_fp64_add(result, wg_beard_bury(horizontalX, verticalDistance, horizontalZ, false, true, false));\n");
            case BEARD_THIN -> source.append("        int verticalDistance = verticalDelta;\n")
                    .append("        result = wg_fp64_add(result, wg_fp64_mul(wg_beard(horizontalX, verticalDistance, horizontalZ, verticalDelta), uvec2(0x9999999au, 0x3fe99999u)));\n");
            case BEARD_BOX -> source.append("        int verticalDistance = max(0, max(")
                    .append(groundY).append(" - point.y, point.y - ").append(rigid.maxY()).append("));\n")
                    .append("        result = wg_fp64_add(result, wg_fp64_mul(wg_beard(horizontalX, verticalDistance, horizontalZ, verticalDelta), uvec2(0x9999999au, 0x3fe99999u)));\n");
            case ENCAPSULATE -> source.append("        int verticalDistance = max(0, max(")
                    .append(rigid.minY()).append(" - point.y, point.y - ").append(rigid.maxY()).append("));\n")
                    .append("        result = wg_fp64_add(result, wg_fp64_mul(wg_beard_bury(horizontalX, verticalDistance, horizontalZ, true, true, true), uvec2(0x9999999au, 0x3fe99999u)));\n");
        }
        source.append("    }\n");
    }

    private static void appendKernel(StringBuilder source) {
        source.append("const uint wg_beard_kernel[").append(KERNEL_WORDS).append("] = uint[")
                .append(KERNEL_WORDS).append("](\n");
        for (int kernelZ = 0; kernelZ < KERNEL_SIZE; kernelZ++) {
            for (int kernelX = 0; kernelX < KERNEL_SIZE; kernelX++) {
                for (int kernelY = 0; kernelY < KERNEL_SIZE; kernelY++) {
                    int x = kernelX - KERNEL_RADIUS;
                    int y = kernelY - KERNEL_RADIUS;
                    int z = kernelZ - KERNEL_RADIUS;
                    double distanceSquared = x * (double) x + (y + 0.5) * (y + 0.5) + z * (double) z;
                    int bits = Float.floatToRawIntBits((float) Math.pow(Math.E, -distanceSquared / 16.0));
                    source.append("0x").append(Integer.toUnsignedString(bits, 16)).append('u');
                    if (kernelZ != KERNEL_SIZE - 1 || kernelX != KERNEL_SIZE - 1 || kernelY != KERNEL_SIZE - 1) {
                        source.append(',');
                    }
                    if (kernelY == KERNEL_SIZE - 1) source.append('\n');
                    else source.append(' ');
                }
            }
        }
        source.append(");\n");
    }
}
