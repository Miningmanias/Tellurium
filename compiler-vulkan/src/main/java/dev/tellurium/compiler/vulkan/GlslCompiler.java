// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan;

import dev.tellurium.semantic.*;
import java.util.IdentityHashMap;
import java.util.Locale;

/** Complete Vulkan GLSL450 compute emitter for the finite synthetic expression language. */
public final class GlslCompiler {
    public GlslCompiler() {}

    /**
     * Invalid samples write a canonical quiet NaN. Consumers MUST reject every non-finite
     * value; valid outputs are finite. The device/runtime must qualify IEEE preservation,
     * rounding and denormals, rather than assuming FP64 support implies exact behavior.
     */
    public String emit(DensityExpression expression) {
        ExpressionValidation.validate(expression);
        Emitter emitter = new Emitter();
        String root = emitter.node(expression);
        return HEADER + "// expression-sha256: " + ExpressionIdentity.hash(expression) + "\n"
                + emitter.functions + """
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.sampleCount) return;
                    wg_failed = false;
                    ivec3 point = points[index].xyz;
                """ + "    precise double result = " + root + "(point);\n"
                + "    values[index] = wg_failed ? packDouble2x32(uvec2(0u, 0x7ff80000u)) : result;\n}\n";
    }

    private static final String HEADER = """
            #version 450
            #extension GL_ARB_gpu_shader_fp64 : require
            // Tellurium synthetic ABI v1. No Minecraft noise/material support implied.
            layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;
            layout(std430, binding = 0) readonly buffer InputPoints { ivec4 points[]; };
            layout(std430, binding = 1) writeonly buffer OutputValues { double values[]; };
            layout(push_constant) uniform Dispatch { uint sampleCount; } dispatch;
            bool wg_failed = false;

            double wg_checked(double value) {
                if (isnan(value) || isinf(value)) {
                    wg_failed = true;
                    return 0.0LF;
                }
                return value;
            }

            double wg_lerp(double fraction, double low, double high) {
                precise double difference = high - low;
                difference = wg_checked(difference);
                precise double scaled = fraction * difference;
                scaled = wg_checked(scaled);
                precise double result = low + scaled;
                return wg_checked(result);
            }

            bool wg_cell_axis(int coordinate, int size, out int low, out int high, out double fraction) {
                int quotient = coordinate / size;
                // Derive Java's truncating remainder; GLSL % has different signed behavior.
                int remainder = coordinate - quotient * size;
                if (remainder < 0) remainder += size;
                // Check before subtracting/adding so no signed overflow can reach the shader.
                if (coordinate < (-2147483647 - 1) + remainder) {
                    wg_failed = true;
                    return false;
                }
                low = coordinate - remainder;
                if (low > 2147483647 - size) {
                    wg_failed = true;
                    return false;
                }
                high = low + size;
                precise double computedFraction = double(remainder) / double(size);
                fraction = computedFraction;
                return true;
            }

            """;

    private static final class Emitter {
        private final IdentityHashMap<DensityExpression, String> names = new IdentityHashMap<>();
        private final StringBuilder functions = new StringBuilder();

        private String node(DensityExpression expression) {
            String previous = names.get(expression);
            if (previous != null) return previous;
            String body = switch (expression) {
                case DensityExpression.Constant n -> "    return " + literal(n.value()) + ";\n";
                case DensityExpression.Coordinate n -> "    return double(point." + n.axis().name().toLowerCase(Locale.ROOT) + ");\n";
                case DensityExpression.Add n -> arithmetic(node(n.left()), node(n.right()), "+");
                case DensityExpression.Multiply n -> arithmetic(node(n.left()), node(n.right()), "*");
                case DensityExpression.RangeChoice n -> {
                    String input = node(n.input()), in = node(n.whenIn()), out = node(n.whenOut());
                    yield "    precise double selector = " + input + "(point);\n"
                            + "    if (wg_failed) return 0.0LF;\n"
                            + "    if (selector >= " + literal(n.minInclusive()) + " && selector < " + literal(n.maxExclusive()) + ") {\n"
                            + "        return " + in + "(point);\n    } else {\n        return " + out + "(point);\n    }\n";
                }
                case DensityExpression.Interpolated n -> boundary(node(n.child()), n.geometry());
            };
            String name = "wg_node_" + names.size();
            names.put(expression, name);
            functions.append("double ").append(name).append("(ivec3 point) {\n").append(body).append("}\n\n");
            return name;
        }

        private static String arithmetic(String left, String right, String operator) {
            return "    precise double left = " + left + "(point);\n"
                    + "    if (wg_failed) return 0.0LF;\n"
                    + "    precise double right = " + right + "(point);\n"
                    + "    precise double result = left " + operator + " right;\n"
                    + "    return wg_checked(result);\n";
        }

        private static String boundary(String child, CellGeometry geometry) {
            StringBuilder body = new StringBuilder("    int x0, x1, y0, y1, z0, z1;\n    double fx, fy, fz;\n");
            body.append("    if (!wg_cell_axis(point.x, ").append(geometry.width()).append(", x0, x1, fx)) return 0.0LF;\n")
                    .append("    if (!wg_cell_axis(point.y, ").append(geometry.height()).append(", y0, y1, fy)) return 0.0LF;\n")
                    .append("    if (!wg_cell_axis(point.z, ").append(geometry.width()).append(", z0, z1, fz)) return 0.0LF;\n");
            for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) {
                body.append("    precise double c").append(x).append(y).append(z).append(" = ").append(child)
                        .append("(ivec3(x").append(x).append(", y").append(y).append(", z").append(z).append("));\n");
            }
            return body + """
                        precise double y00 = wg_lerp(fy, c000, c010);
                        precise double y01 = wg_lerp(fy, c001, c011);
                        precise double y10 = wg_lerp(fy, c100, c110);
                        precise double y11 = wg_lerp(fy, c101, c111);
                        precise double xz0 = wg_lerp(fx, y00, y10);
                        precise double xz1 = wg_lerp(fx, y01, y11);
                        return wg_lerp(fz, xz0, xz1);
                    """;
        }
    }

    private static String literal(double value) {
        long bits = Double.doubleToRawLongBits(value);
        return String.format(Locale.ROOT, "packDouble2x32(uvec2(0x%08xu, 0x%08xu))", (int) bits, (int) (bits >>> 32));
    }
}
