// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.worldgen;

import dev.tellurium.semantic.program.ProgramNode;
import dev.tellurium.semantic.program.ValueType;
import dev.tellurium.semantic.program.WorldgenProgram;
import dev.tellurium.semantic.snapshot.PositionalRandomFactorySnapshot;
import java.util.Objects;

/**
 * Emits the captured six-material OreVeinifier rule as a device-side stage.
 *
 * <p>Root functions are supplied by the enclosing worldgen emitter so their
 * node interning remains shared with the density graph. The returned source
 * contains only the ore functions; no state or material decision is computed
 * on the host.</p>
 */
public final class OreVeinEmitter {
    public record Emission(String source, String entryPoint) {
        public Emission {
            Objects.requireNonNull(source, "source");
            if (entryPoint != null && entryPoint.isBlank()) {
                throw new IllegalArgumentException("Ore entry point cannot be blank");
            }
        }
    }

    @FunctionalInterface
    public interface RootResolver {
        String resolve(WorldgenProgram program, String rootName);
    }

    @FunctionalInterface
    public interface FunctionAllocator {
        int next();
    }

    /**
     * Emit the optional ore stage, or return {@code null} when ore is
     * disabled. Function IDs come from the enclosing compiler to avoid name
     * collisions with ordinary graph nodes.
     */
    public Emission emit(WorldgenProgram program, WorldgenShaderCompiler.MaterialOptions material,
                         RootResolver roots, FunctionAllocator functionIds) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(functionIds, "functionIds");
        if (!material.oreProgram().enabled()) return new Emission("", null);
        if (material.oreProgram().materials().size() != 6) {
            throw new IllegalArgumentException("The device ore stage requires six material entries");
        }
        String toggle = roots.resolve(program, material.oreProgram().toggleNode());
        String ridged = roots.resolve(program, material.oreProgram().ridgedNode());
        String gap = roots.resolve(program, material.oreProgram().gapNode());
        int positionSuffix = functionIds.next();
        String position = "wg_ore_position_" + positionSuffix;
        StringBuilder functions = new StringBuilder();
        functions.append("uvec2 ").append(position).append("(ivec3 point) {\n")
                .append("    uint xProduct = wg_i32_mul(wg_i32_to_bits(point.x), ")
                .append(rawUInt(3129871)).append(");\n")
                .append("    uvec2 xTerm = wg_i64_from_i32(wg_i32_from_bits(xProduct));\n")
                .append("    uvec2 zTerm = wg_i64_mul_lo(wg_i64_from_i32(point.z), ")
                .append(u64Literal(116129781L)).append(");\n")
                .append("    uvec2 base = xTerm ^ zTerm ^ wg_i64_from_i32(point.y);\n")
                .append("    uvec2 value = wg_i64_add(wg_i64_mul_lo(wg_i64_mul_lo(base, base), ")
                .append(u64Literal(42317861L)).append("), wg_i64_mul_lo(base, ")
                .append(u64Literal(11L)).append("));\n")
                .append("    return wg_i64_shr16(value);\n}\n");

        String random = "wg_ore_random_" + functionIds.next();
        if (material.oreRandom().algorithm() == PositionalRandomFactorySnapshot.Algorithm.LEGACY) {
            functions.append("uint ").append(random).append("(inout uvec2 state) {\n")
                    .append("    state = wg_u64_add(wg_i64_mul_lo(state, ")
                    .append(u64Literal(0x5DEECE66DL)).append("), ")
                    .append(u64Literal(0xBL)).append(") & uvec2(0xffffffffu, 0x0000ffffu);\n")
                    .append("    uint bits = wg_u64_shr(state, 24u).x;\n")
                    .append("    return wg_fp32_mul(wg_fp32_from_int(int(bits)), ")
                    .append(fp32Literal(5.9604645E-8F)).append(");\n}\n");
        } else {
            functions.append("uint ").append(random).append("(inout uvec2 stateLo, inout uvec2 stateHi) {\n")
                    .append("    uvec2 value = wg_xoroshiro128pp_next(stateLo, stateHi);\n")
                    .append("    uint bits = wg_u64_shr(value, 40u).x;\n")
                    .append("    return wg_fp32_mul(wg_fp32_from_int(int(bits)), ")
                    .append(fp32Literal(5.9604645E-8F)).append(");\n}\n");
        }

        String name = "wg_ore_" + functionIds.next();
        var ids = material.oreStateIds();
        String zero = "uvec2(0u)";
        String edgeLow = fp64Literal(0.0d);
        String edgeHigh = fp64Literal(20.0d);
        String edgeOutLow = fp64Literal(-0.2d);
        String edgeOutHigh = fp64Literal(0.0d);
        String threshold = fp64Literal((double) 0.4F);
        String richnessLow = fp64Literal((double) 0.4F);
        String richnessHigh = fp64Literal((double) 0.6F);
        String richnessOutLow = fp64Literal((double) 0.1F);
        String richnessOutHigh = fp64Literal((double) 0.3F);
        String gapThreshold = fp64Literal((double) -0.3F);
        String randomThreshold = fp32Literal(0.7F);
        String rareThreshold = fp32Literal(0.02F);
        functions.append("uvec2 ").append(name).append("(ivec3 point) {\n")
                .append("    uvec2 toggle = ").append(toggle).append(";\n")
                .append("    bool copper = wg_fp64_less(").append(zero).append(", toggle);\n")
                .append("    uvec2 magnitude = wg_fp64_abs(toggle);\n")
                .append("    int minY = copper ? 0 : -60, maxY = copper ? 50 : -8;\n")
                .append("    int distanceToEdge = min(maxY - point.y, point.y - minY);\n")
                .append("    uvec2 edgeFraction = wg_fp64_div(wg_fp64_sub(wg_fp64_from_int(distanceToEdge), ")
                .append(edgeLow).append("), wg_fp64_sub(").append(edgeHigh).append(", ").append(edgeLow).append("));\n")
                .append("    edgeFraction = wg_fp64_max(").append(zero).append(", wg_fp64_min(")
                .append(fp64Literal(1.0d)).append(", edgeFraction));\n")
                .append("    uvec2 edgeCorrection = wg_fp64_add(").append(edgeOutLow)
                .append(", wg_fp64_mul(edgeFraction, wg_fp64_sub(").append(edgeOutHigh).append(", ")
                .append(edgeOutLow).append(")));\n")
                .append("    if (distanceToEdge < 0 || wg_fp64_less(wg_fp64_add(magnitude, edgeCorrection), ")
                .append(threshold).append(")) return uvec2(0u, dispatch.airStateId);\n");
        if (material.oreRandom().algorithm() == PositionalRandomFactorySnapshot.Algorithm.LEGACY) {
            functions.append("    uvec2 randomState = (").append(position).append("(point) ^ ")
                    .append(u64Literal(material.oreRandom().seed())).append(" ^ ")
                    .append(u64Literal(0x5DEECE66DL)).append(") & uvec2(0xffffffffu, 0x0000ffffu);\n")
                    .append("    uint firstRandom = ").append(random).append("(randomState);\n");
        } else {
            functions.append("    uvec2 randomStateLo = ").append(position).append("(point) ^ ")
                    .append(u64Literal(material.oreRandom().seedLo())).append(";\n")
                    .append("    uvec2 randomStateHi = ").append(u64Literal(material.oreRandom().seedHi())).append(";\n");
            // The factory can be nonzero while positional XOR produces the
            // forbidden zero state. Minecraft normalizes it when constructing
            // the per-position Xoroshiro128PlusPlus, before the first draw.
            if (material.oreRandom().seedHi() == 0L) {
                functions.append("    if (all(equal(randomStateLo, uvec2(0u)))) {\n")
                        .append("        randomStateLo = ").append(u64Literal(-7046029254386353131L)).append(";\n")
                        .append("        randomStateHi = ").append(u64Literal(7640891576956012809L)).append(";\n")
                        .append("    }\n");
            }
            functions.append("    uint firstRandom = ").append(random).append("(randomStateLo, randomStateHi);\n");
        }
        functions.append("    if (wg_fp32_less(").append(randomThreshold).append(", firstRandom)) return uvec2(0u, dispatch.airStateId);\n")
                .append("    uvec2 ridged = ").append(ridged).append(";\n")
                .append("    if (wg_fp64_less_equal(").append(zero).append(", ridged)) return uvec2(0u, dispatch.airStateId);\n")
                .append("    uvec2 richnessFraction = wg_fp64_div(wg_fp64_sub(magnitude, ").append(richnessLow)
                .append("), wg_fp64_sub(").append(richnessHigh).append(", ").append(richnessLow).append("));\n")
                .append("    richnessFraction = wg_fp64_max(").append(zero).append(", wg_fp64_min(")
                .append(fp64Literal(1.0d)).append(", richnessFraction));\n")
                .append("    uvec2 richness = wg_fp64_add(").append(richnessOutLow)
                .append(", wg_fp64_mul(richnessFraction, wg_fp64_sub(").append(richnessOutHigh).append(", ")
                .append(richnessOutLow).append(")));\n");
        if (material.oreRandom().algorithm() == PositionalRandomFactorySnapshot.Algorithm.LEGACY) {
            functions.append("    uint richnessRandom = ").append(random).append("(randomState);\n")
                    .append("    if (wg_fp64_less(wg_fp64_from_fp32(richnessRandom), richness)) {\n")
                    .append("        uvec2 gap = ").append(gap).append(";\n")
                    .append("        if (wg_fp64_less(").append(gapThreshold).append(", gap)) {\n")
                    .append("            uint rareRandom = ").append(random).append("(randomState);\n");
        } else {
            functions.append("    uint richnessRandom = ").append(random).append("(randomStateLo, randomStateHi);\n")
                    .append("    if (wg_fp64_less(wg_fp64_from_fp32(richnessRandom), richness)) {\n")
                    .append("        uvec2 gap = ").append(gap).append(";\n")
                    .append("        if (wg_fp64_less(").append(gapThreshold).append(", gap)) {\n")
                    .append("            uint rareRandom = ").append(random).append("(randomStateLo, randomStateHi);\n");
        }
        // Vanilla tests nextFloat() < CHANCE_OF_RAW_ORE_BLOCK.  Keeping the
        // random value on the left is important; reversing these operands
        // makes almost every eligible vein block raw ore.
        functions.append("            if (wg_fp32_less(rareRandom, ").append(rareThreshold).append(")) {\n")
                .append("                return uvec2(1u, copper ? ").append(rawUInt(ids.get(1))).append(" : ")
                .append(rawUInt(ids.get(4))).append(");\n")
                .append("            }\n")
                .append("            return uvec2(1u, copper ? ").append(rawUInt(ids.get(0))).append(" : ")
                .append(rawUInt(ids.get(3))).append(");\n")
                .append("        }\n")
                .append("    }\n")
                .append("    return uvec2(1u, copper ? ").append(rawUInt(ids.get(2))).append(" : ")
                .append(rawUInt(ids.get(5))).append(");\n")
                .append("}\n");
        return new Emission(functions.toString(), name);
    }

    private static String rawUInt(int bits) {
        return "0x" + Integer.toUnsignedString(bits, 16) + "u";
    }

    private static String u64Literal(long value) {
        return "uvec2(" + rawUInt((int) value) + ", " + rawUInt((int) (value >>> 32)) + ")";
    }

    private static String fp32Literal(float value) {
        return rawUInt(Float.floatToRawIntBits(value));
    }

    private static String fp64Literal(double value) {
        long bits = Double.doubleToRawLongBits(value);
        return "uvec2(" + rawUInt((int) bits) + ", " + rawUInt((int) (bits >>> 32)) + ")";
    }
}
