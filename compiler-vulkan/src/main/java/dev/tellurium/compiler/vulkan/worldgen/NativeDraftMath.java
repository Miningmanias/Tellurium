// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.worldgen;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Prototype hardware arithmetic over the existing bit-carrier ABI.
 * No denormal/rounding qualification is implied. Integer RNG and table indexing
 * remain unchanged, and precise intermediates prohibit fused multiply-add.
 */
public final class NativeDraftMath {
    private NativeDraftMath() { }

    public static String rewrite(String source) {
        return rewrite(source, true, true);
    }

    /** Experimental selective lowering used by native-draft diagnostics. */
    public static String rewrite(String source, boolean rewriteFp32, boolean rewriteFp64) {
        return rewrite(source, rewriteFp32, rewriteFp64, "");
    }

    /** Selectively retain one FP64 carrier operation for a bounded A/B probe. */
    public static String rewrite(String source, boolean rewriteFp32, boolean rewriteFp64,
                                 String disabledFp64Operation) {
        Map<String, String> bodies = new LinkedHashMap<>();
        for (String precision : new String[]{"32", "64"}) {
            if ((precision.equals("32") && !rewriteFp32)
                    || (precision.equals("64") && !rewriteFp64)) continue;
            String type = precision.equals("64") ? "double" : "float";
            String unpack = precision.equals("64") ? "packDouble2x32" : "uintBitsToFloat";
            String pack = precision.equals("64") ? "unpackDouble2x32" : "floatBitsToUint";
            String prefix = "wg_fp" + precision + "_";
            String[] names = {"add", "sub", "mul", "div"};
            String[] operators = {"+", "-", "*", "/"};
            for (int i = 0; i < names.length; i++) {
                if (precision.equals("64") && names[i].equals(disabledFp64Operation)) continue;
                bodies.put(prefix + names[i], "precise " + type + " value = " + unpack
                        + "(left) " + operators[i] + " " + unpack + "(right); return " + pack + "(value);");
            }
            bodies.put(prefix + "from_int", "return " + pack + "(" + type + "(value));");
            bodies.put(prefix + "floor", "return " + pack + "(floor(" + unpack + "(bits)));");
            bodies.put(prefix + "sqrt", "precise " + type + " value = sqrt(" + unpack
                    + "(bits)); return " + pack + "(value);");
        }
        if (rewriteFp64) {
            // Keep draft decisions in the same hardware numeric domain as
            // draft arithmetic. The target driver mis-selects a staged range
            // parent at a zero carrier with the inlined integer comparison
            // closure; this does not change the GPU_IEEE_BITS emitter.
            bodies.put("wg_fp64_equal", "return packDouble2x32(left) == packDouble2x32(right);");
            bodies.put("wg_fp64_less", "return packDouble2x32(left) < packDouble2x32(right);");
            bodies.put("wg_fp64_less_equal", "return packDouble2x32(left) <= packDouble2x32(right);");
            bodies.put("wg_fp64_from_fp32", "return unpackDouble2x32(double(uintBitsToFloat(bits)));");
            bodies.put("wg_fp64_to_fp32", "return floatBitsToUint(float(packDouble2x32(bits)));");
            bodies.put("wg_fp64_floor_to_i32", "double value = floor(packDouble2x32(bits)); "
                    + "if (isnan(value) || isinf(value) || value < -2147483648.0lf || value > 2147483647.0lf) "
                    + "{ wg_failed = true; return 0; } return int(value);");
        }
        for (var entry : bodies.entrySet()) source = replaceBody(source, entry.getKey(), entry.getValue());
        String marker = "// GPU_NATIVE_DRAFT: hardware arithmetic, unqualified subnormal behavior "
                + "fp32=" + rewriteFp32 + " fp64=" + rewriteFp64
                + " disabledFp64=" + (disabledFp64Operation.isEmpty() ? "none" : disabledFp64Operation);
        return source.replace("#version 450", "#version 450\n#extension GL_ARB_gpu_shader_fp64 : require\n" + marker);
    }

    private static String replaceBody(String source, String name, String replacement) {
        var match = Pattern.compile("(?m)^\\s*(?:uint|uvec2|int|bool)\\s+" + Pattern.quote(name)
                + "\\([^;{}]*\\)\\s*\\{").matcher(source);
        if (!match.find()) return source;
        int open = match.end() - 1, depth = 1, end = open + 1;
        for (; end < source.length() && depth > 0; end++) {
            char ch = source.charAt(end);
            if (ch == '{') depth++;
            else if (ch == '}') depth--;
        }
        if (depth != 0) throw new IllegalArgumentException("Unclosed shader function " + name);
        return source.substring(0, open + 1) + " " + replacement + " }" + source.substring(end);
    }
}
