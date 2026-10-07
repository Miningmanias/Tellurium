// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.worldgen;

import dev.tellurium.semantic.program.InterpolationGeometry;
import dev.tellurium.semantic.program.EvaluationDomain;
import dev.tellurium.semantic.program.NumericProfile;
import dev.tellurium.semantic.program.ProgramNode;
import dev.tellurium.semantic.program.ValueType;
import dev.tellurium.semantic.program.WorldgenProgram;
import dev.tellurium.semantic.material.OreVeinProgram;
import dev.tellurium.semantic.snapshot.EndIslandParameters;
import dev.tellurium.semantic.snapshot.BlendedNoiseParameters;
import dev.tellurium.semantic.snapshot.PositionalRandomFactorySnapshot;
import dev.tellurium.semantic.snapshot.StructureBlendSnapshot;
import dev.tellurium.semantic.snapshot.WorldgenSnapshot;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Emits the conservative, integer-carrier worldgen compute ABI.
 *
 * <p>This compiler intentionally admits a small, auditable subset of the
 * semantic IR. A node which is not implemented is rejected at compile time;
 * it is never replaced by a constant result or by an implicit native numeric
 * operation. That distinction is important because the captured Minecraft
 * graph currently contains FP64 and seeded noise nodes that still require
 * their independently qualified emitters.</p>
 */
public final class WorldgenShaderCompiler {
    /** Integer-carrier type of the barrier root consumed by the aquifer stage. */
    public enum BarrierType { FP32, FP64 }

    /** Dense output layout emitted by the shader entry point. */
    public enum OutputMode {
        /** One result-local block-state ID per invocation. */
        STATE_ONLY,
        /** A state ID followed by the device-produced fluid-update mark. */
        STATE_AND_FLUID_MARK
    }

    /**
     * Declares how mutable Minecraft marker boundaries are handled by the
     * emitted program.  The default generic-program route remains fail-closed
     * because a shader invocation has no NoiseChunk interpolation cursor or
     * cache lifetime.  A loader snapshot can explicitly opt into direct-point
     * replay: at that boundary Minecraft's raw DensityFunctions.Marker is a
     * transparent wrapper and the actual NoiseChunk cache objects are created
     * only by the later game-owned wrapping step.
     */
    public enum MarkerPolicy {
        REJECT_UNSTAGED,
        DIRECT_POINT_REPLAY
    }

    public record Shader(String source, String programHash, NumericProfile profile, int localSize,
                         Map<String, BlendedNoiseParameters> blendedNoiseFunctions,
                         Map<String, ProgramNode> nodeFunctions) {
        public Shader(String source, String programHash, NumericProfile profile, int localSize) {
            this(source, programHash, profile, localSize, Map.of(), Map.of());
        }

        public Shader(String source, String programHash, NumericProfile profile, int localSize,
                      Map<String, BlendedNoiseParameters> blendedNoiseFunctions) {
            this(source, programHash, profile, localSize, blendedNoiseFunctions, Map.of());
        }

        public Shader {
            if (source == null || source.isBlank() || programHash == null || programHash.isBlank()
                    || profile == null || localSize <= 0) {
                throw new IllegalArgumentException("Invalid shader");
            }
            var copiedBlendedNoiseFunctions = new TreeMap<String, BlendedNoiseParameters>();
            if (blendedNoiseFunctions != null) {
                blendedNoiseFunctions.forEach((name, parameters) -> {
                    if (name == null || name.isBlank() || parameters == null) {
                        throw new IllegalArgumentException("Invalid blended-noise shader metadata");
                    }
                    copiedBlendedNoiseFunctions.put(name, parameters);
                });
            }
            blendedNoiseFunctions = Map.copyOf(copiedBlendedNoiseFunctions);
            var copiedNodeFunctions = new TreeMap<String, ProgramNode>();
            if (nodeFunctions != null) {
                nodeFunctions.forEach((name, node) -> {
                    if (name == null || name.isBlank() || node == null) {
                        throw new IllegalArgumentException("Invalid emitted node metadata");
                    }
                    copiedNodeFunctions.put(name, node);
                });
            }
            nodeFunctions = Map.copyOf(copiedNodeFunctions);
        }
    }

    /** Explicit captured inputs for the optional device-side material stages. */
    public record MaterialOptions(OreVeinProgram oreProgram,
                                  PositionalRandomFactorySnapshot oreRandom,
                                  List<Integer> oreStateIds,
                                  AquiferEmitter.Options aquifer) {
        public MaterialOptions(OreVeinProgram oreProgram,
                                PositionalRandomFactorySnapshot oreRandom,
                                List<Integer> oreStateIds) {
            this(oreProgram, oreRandom, oreStateIds, AquiferEmitter.Options.disabled());
        }

        public MaterialOptions {
            Objects.requireNonNull(oreProgram, "oreProgram");
            oreStateIds = List.copyOf(oreStateIds == null ? List.of() : oreStateIds);
            Objects.requireNonNull(aquifer, "aquifer");
            if (!oreProgram.enabled()) {
                if (oreRandom != null || !oreStateIds.isEmpty()) {
                    throw new IllegalArgumentException("Disabled ore stage cannot carry RNG or state IDs");
                }
            } else {
                Objects.requireNonNull(oreRandom, "oreRandom");
                if (oreProgram.materials().size() != 6) {
                    throw new IllegalArgumentException("The device ore stage requires the six captured vanilla materials");
                }
                if (oreProgram.materials().size() != oreStateIds.size()
                        || oreStateIds.stream().anyMatch(value -> value == null || value < 0)) {
                    throw new IllegalArgumentException("Ore material IDs must match the captured material list");
                }
            }
        }

        public static MaterialOptions disabled() {
            return new MaterialOptions(OreVeinProgram.disabled(), null, List.of(), AquiferEmitter.Options.disabled());
        }
    }

    /**
     * Compile an admitted program into the bounded dense-result ABI.
     *
     * <p>Only {@link NumericProfile#GPU_IEEE_BITS} is a shader profile at this
     * stage. JAVA_REFERENCE is the independent CPU oracle, while
     * NATIVE_QUALIFIED is reserved for a separately qualified native path.</p>
     */
    public Shader emit(WorldgenProgram program, NumericProfile profile, int localSize) {
        return emit(program, profile, localSize, StructureBlendSnapshot.empty());
    }

    /**
     * Lower a complete immutable loader capture without allowing live
     * Minecraft objects to cross the compiler boundary.  Registry IDs and
     * material mappings remain explicit runtime inputs; this overload only
     * binds the captured router/blend program and its identity.
     */
    public Shader emitSnapshot(WorldgenSnapshot snapshot, NumericProfile profile, int localSize) {
        return emitSnapshot(snapshot, profile, localSize, MaterialOptions.disabled(), MarkerPolicy.DIRECT_POINT_REPLAY);
    }

    /**
     * Snapshot overload for a bounded replay with explicit device material
     * options.  A partial router is rejected before shader emission so a
     * missing root cannot silently turn into a host-side fallback.
     */
    public Shader emitSnapshot(WorldgenSnapshot snapshot, NumericProfile profile, int localSize,
                               MaterialOptions material) {
        return emitSnapshot(snapshot, profile, localSize, material, MarkerPolicy.DIRECT_POINT_REPLAY);
    }

    /**
     * Snapshot lowering with an explicit marker boundary policy.  The direct
     * point policy is the only admitted policy for the current dense ABI; a
     * future staged NoiseChunk emitter must introduce a separate policy rather
     * than silently changing cache scope.
     */
    public Shader emitSnapshot(WorldgenSnapshot snapshot, NumericProfile profile, int localSize,
                               MaterialOptions material, MarkerPolicy markerPolicy) {
        return emitSnapshot(snapshot, profile, localSize, material, markerPolicy, OutputMode.STATE_ONLY);
    }

    /**
     * Snapshot lowering with an explicit dense output layout.  The metadata
     * layout remains a uint storage buffer so the executor's raw ABI can
     * validate it without introducing a second descriptor contract.
     */
    public Shader emitSnapshot(WorldgenSnapshot snapshot, NumericProfile profile, int localSize,
                               MaterialOptions material, MarkerPolicy markerPolicy,
                               OutputMode outputMode) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(outputMode, "outputMode");
        if (!snapshot.router().complete()) {
            throw new IllegalArgumentException("Worldgen snapshot must contain all 15 router roots before Vulkan lowering");
        }
        WorldgenProgram program = WorldgenProgram.builder()
                .version("tellurium-captured-v2")
                .numericProfile(profile)
                .roots(snapshot.router().roots())
                .build();
        return emit(program, profile, localSize, snapshot.structureBlend(), material, markerPolicy, outputMode);
    }

    /**
     * Emits the standalone GPU stage that adds Minecraft's outer Beardifier
     * contribution to a captured FP64 density carrier. The immutable kernel
     * table stays in this one stage instead of being copied into every staged
     * router module.
     */
    public Shader emitBeardifierStage(StructureBlendSnapshot structureBlend,
                                      NumericProfile profile, int localSize,
                                      boolean combineDensity) {
        Objects.requireNonNull(structureBlend, "structureBlend");
        Objects.requireNonNull(profile, "profile");
        if (profile != NumericProfile.GPU_IEEE_BITS && profile != NumericProfile.GPU_NATIVE_DRAFT) {
            throw new IllegalArgumentException("Beardifier stage requires a GPU numeric profile");
        }
        if (localSize <= 0) throw new IllegalArgumentException("Local size must be positive");
        String source = header(localSize).replace("uint outputStateIds[]", "uint outputBits[]")
                + IntegerIeeeEmitter.helperSource(BeardifierEmitter.helperRoots())
                + BeardifierEmitter.source(structureBlend)
                + beardifierMain(combineDensity);
        if (profile == NumericProfile.GPU_NATIVE_DRAFT) source = NativeDraftMath.rewrite(source);
        SpirvNumericContract.require(source, profile);
        return new Shader(source, "beardifier-stage-v2/" + structureBlend.identity()
                + "/" + combineDensity, profile, localSize);
    }

    /**
     * Compile a program with an immutable structure/blending capture embedded
     * in the shader.  This overload is intentionally explicit: the current
     * dense-result ABI has no mutable structure table binding, so a nonempty
     * capture is a bounded replay fixture rather than an implicit production
     * upload path.
     */
    public Shader emit(WorldgenProgram program, NumericProfile profile, int localSize,
                       StructureBlendSnapshot structureBlend) {
        return emit(program, profile, localSize, structureBlend, MaterialOptions.disabled());
    }

    /**
     * Compile the dense ABI with explicit device-side material extensions.
     * Captured aquifer candidates and the ore rule are optional, immutable
     * inputs; missing candidates are never inferred from density.
     */
    public Shader emit(WorldgenProgram program, NumericProfile profile, int localSize,
                       StructureBlendSnapshot structureBlend, MaterialOptions material) {
        return emit(program, profile, localSize, structureBlend, material, MarkerPolicy.REJECT_UNSTAGED);
    }

    /** Compile with an explicit marker boundary policy. */
    public Shader emit(WorldgenProgram program, NumericProfile profile, int localSize,
                       StructureBlendSnapshot structureBlend, MaterialOptions material,
                       MarkerPolicy markerPolicy) {
        return emit(program, profile, localSize, structureBlend, material, markerPolicy, OutputMode.STATE_ONLY);
    }

    /** Compile with an explicit dense output layout. */
    public Shader emit(WorldgenProgram program, NumericProfile profile, int localSize,
                       StructureBlendSnapshot structureBlend, MaterialOptions material,
                       MarkerPolicy markerPolicy, OutputMode outputMode) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(structureBlend, "structureBlend");
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(markerPolicy, "markerPolicy");
        Objects.requireNonNull(outputMode, "outputMode");
        if (localSize <= 0) throw new IllegalArgumentException("Local size must be positive");
        if (profile != NumericProfile.GPU_IEEE_BITS) {
            throw new UnsupportedOperationException(
                    "Worldgen Vulkan emission requires GPU_IEEE_BITS; requested " + profile);
        }
        ProgramNode root = program.root("finalDensity");
        if (root == null) throw new IllegalArgumentException("Program is missing finalDensity root");
        if (root.type() != ValueType.FP32 && root.type() != ValueType.FP64) {
            throw unsupported(root, "the dense-result ABI currently accepts an FP32 or FP64 finalDensity root");
        }

        Emitter emitter = new Emitter(structureBlend, markerPolicy);
        String rootFunction = emitter.emitNode(root);
        String oreFunction = emitter.emitOre(program, material);
        String barrierFunction = null;
        BarrierType barrierType = null;
        if (material.aquifer().enabled()) {
            ProgramNode barrierRoot = program.root("barrierNoise");
            if (barrierRoot == null) {
                throw new IllegalArgumentException("Enabled aquifer requires a captured barrierNoise root");
            }
            if (barrierRoot.type() != ValueType.FP32 && barrierRoot.type() != ValueType.FP64) {
                throw unsupported(barrierRoot, "aquifer barrierNoise must be FP32 or FP64");
            }
            barrierFunction = emitter.emitNode(barrierRoot);
            barrierType = barrierRoot.type() == ValueType.FP64 ? BarrierType.FP64 : BarrierType.FP32;
        }
        boolean externalAquiferBarrier = material.aquifer().enabled()
                && Boolean.parseBoolean(System.getProperty(
                "tellurium.gpuCandidate.exactAquiferBarrierInput", "false"));
        String aquiferBarrierExpression = externalAquiferBarrier
                ? barrierType == BarrierType.FP64
                        ? barrierFunction + "(point)"
                        : "wg_fp64_from_fp32(" + barrierFunction + "(point))"
                : null;
        AquiferEmitter.Emission aquifer = new AquiferEmitter().emit(
                material.aquifer(), emitter.nextFragmentId(), barrierFunction, barrierType,
                externalAquiferBarrier);
        String source = header(localSize)
                + IntegerIeeeEmitter.source()
                + integerHelpers()
                + new MaterialEmitter().emit()
                + emitter.noiseHelpers()
                + emitter.functions()
                + aquifer.source()
                + main(rootFunction, root.type(), oreFunction, aquifer.entryPoint(),
                aquiferBarrierExpression, outputMode);
        SpirvNumericContract.require(source, profile);
        return new Shader(source, program.fingerprint(), profile, localSize,
                emitter.blendedNoiseFunctions(), emitter.nodeFunctions());
    }

    private static String header(int localSize) {
        return """
                #version 450
                // Tellurium typed worldgen ABI v2 profile=GPU_IEEE_BITS.
                // Each input element contains x, y, z and a reserved seed word as raw 32-bit carriers.
                layout(local_size_x=%d, local_size_y=1, local_size_z=1) in;
                layout(std430, binding=0) readonly buffer Inputs { uint inputBits[]; };
                layout(std430, binding=1) writeonly buffer Outputs { uint outputStateIds[]; };
                layout(push_constant) uniform Dispatch {
                    uint count;
                    uint defaultStateId;
                    uint airStateId;
                    uint invalidStateId;
                } dispatch;
                bool wg_failed;

                int wg_i32_from_bits(uint bits) {
                    if ((bits & 0x80000000u) == 0u) return int(bits);
                    if (bits == 0x80000000u) return -2147483647 - 1;
                    return -int((~bits) + 1u);
                }
                uint wg_i32_to_bits(int value) {
                    if (value >= 0) return uint(value);
                    // -(value + 1) is abs(value) - 1, so complementing it
                    // directly yields the two's-complement bit carrier. The
                    // extra +1 would turn every negative coordinate into
                    // value + 1 and shift captured-noise permutation lookups.
                    return ~uint(-(value + 1));
                }

                ivec3 wg_point(uint index) {
                    uint base = index * 4u;
                    return ivec3(wg_i32_from_bits(inputBits[base]),
                            wg_i32_from_bits(inputBits[base + 1u]),
                            wg_i32_from_bits(inputBits[base + 2u]));
                }

                """.formatted(localSize);
    }

    private static String integerHelpers() {
        return """
                uint wg_i32_add(uint left, uint right) { return left + right; }
                uint wg_i32_sub(uint left, uint right) { return left - right; }
                uint wg_i32_mul(uint left, uint right) { return left * right; }
                uint wg_i32_negate(uint value) { return 0u - value; }
                uint wg_i32_abs(uint value) {
                    int signedValue = wg_i32_from_bits(value);
                    if (signedValue >= 0) return value;
                    return wg_i32_negate(value);
                }

                uvec2 wg_i64_from_i32(int value) {
                    return uvec2(wg_i32_to_bits(value), value < 0 ? 0xffffffffu : 0u);
                }
                uvec2 wg_i64_mul_lo(uvec2 left, uvec2 right) {
                    uvec4 product = wg_u128_from_u64_product(left, right);
                    return uvec2(product.x, product.y);
                }
                uvec2 wg_i64_shr16(uvec2 value) {
                    return uvec2((value.x >> 16u) | (value.y << 16u),
                            (value.y >> 16u) | ((value.y & 0x80000000u) != 0u ? 0xffff0000u : 0u));
                }

                uint wg_i32_floor_div(uint leftBits, uint rightBits) {
                    if (rightBits == 0u) { wg_failed = true; return 0u; }
                    // GLSL signed remainder is undefined for negative operands.
                    // Work entirely in unsigned magnitudes, including MIN_VALUE;
                    // carrier wrap preserves Java's MIN_VALUE / -1 result.
                    uint leftMagnitude = wg_i32_abs(leftBits), rightMagnitude = wg_i32_abs(rightBits);
                    uint quotientMagnitude = leftMagnitude / rightMagnitude;
                    uint remainderMagnitude = leftMagnitude - quotientMagnitude * rightMagnitude;
                    bool oppositeSigns = ((leftBits ^ rightBits) & 0x80000000u) != 0u;
                    uint quotient = oppositeSigns ? 0u - quotientMagnitude : quotientMagnitude;
                    if (remainderMagnitude != 0u && oppositeSigns) quotient--;
                    return quotient;
                }
                uint wg_i32_floor_mod(uint leftBits, uint rightBits) {
                    if (rightBits == 0u) { wg_failed = true; return 0u; }
                    return leftBits - wg_i32_floor_div(leftBits, rightBits) * rightBits;
                }

                bool wg_cell_axis(int coordinate, int cellSize, out int low, out int high, out uint fraction) {
                    int minimum = -2147483647 - 1, maximum = 2147483647;
                    if (cellSize <= 0) { wg_failed = true; low = 0; high = 0; fraction = 0u; return false; }
                    int cell = wg_i32_from_bits(wg_i32_floor_div(wg_i32_to_bits(coordinate), uint(cellSize)));
                    if (cell > maximum / cellSize || cell < minimum / cellSize) {
                        wg_failed = true; low = 0; high = 0; fraction = 0u; return false;
                    }
                    low = cell * cellSize;
                    if (low > maximum - cellSize) {
                        wg_failed = true; high = 0; fraction = 0u; return false;
                    }
                    high = low + cellSize;
                    int remainder = coordinate - low;
                    fraction = wg_fp32_div(wg_fp32_from_int(remainder), wg_fp32_from_int(cellSize));
                    return true;
                }

                bool wg_cell_axis64(int coordinate, int cellSize, out int low, out int high, out uvec2 fraction) {
                    int minimum = -2147483647 - 1, maximum = 2147483647;
                    if (cellSize <= 0) { wg_failed = true; low = 0; high = 0; fraction = uvec2(0u); return false; }
                    int cell = wg_i32_from_bits(wg_i32_floor_div(wg_i32_to_bits(coordinate), uint(cellSize)));
                    if (cell > maximum / cellSize || cell < minimum / cellSize) {
                        wg_failed = true; low = 0; high = 0; fraction = uvec2(0u); return false;
                    }
                    low = cell * cellSize;
                    if (low > maximum - cellSize) {
                        wg_failed = true; high = 0; fraction = uvec2(0u); return false;
                    }
                    high = low + cellSize;
                    int remainder = coordinate - low;
                    fraction = wg_fp64_div(wg_fp64_from_int(remainder), wg_fp64_from_int(cellSize));
                    return true;
                }

                uint wg_fp32_lerp(uint left, uint right, uint fraction) {
                    return wg_fp32_add(left, wg_fp32_mul(fraction, wg_fp32_sub(right, left)));
                }

                uint wg_fp32_half_negative(uint value) {
                    return wg_fp32_less(0u, value) ? value : wg_fp32_mul(value, 0x3f000000u);
                }
                uint wg_fp32_quarter_negative(uint value) {
                    return wg_fp32_less(0u, value) ? value : wg_fp32_mul(value, 0x3e800000u);
                }
                uint wg_fp32_squeeze(uint value) {
                    uint clamped = wg_fp32_max(0xbf800000u, wg_fp32_min(0x3f800000u, value));
                    uint cubic = wg_fp32_mul(wg_fp32_mul(clamped, clamped), clamped);
                    return wg_fp32_sub(wg_fp32_mul(clamped, 0x3f000000u), wg_fp32_mul(cubic, 0x3d2aaaabu));
                }
                uvec2 wg_fp64_half_negative(uvec2 value) {
                    return wg_fp64_positive(value) ? value : wg_fp64_mul(value, uvec2(0u, 0x3fe00000u));
                }
                uvec2 wg_fp64_quarter_negative(uvec2 value) {
                    return wg_fp64_positive(value) ? value : wg_fp64_mul(value, uvec2(0u, 0x3fd00000u));
                }
                uvec2 wg_fp64_squeeze(uvec2 value) {
                    uvec2 clamped = wg_fp64_max(uvec2(0u, 0xbff00000u), wg_fp64_min(uvec2(0u, 0x3ff00000u), value));
                    uvec2 cubic = wg_fp64_mul(wg_fp64_mul(clamped, clamped), clamped);
                    return wg_fp64_sub(wg_fp64_mul(clamped, uvec2(0u, 0x3fe00000u)),
                            wg_fp64_mul(cubic, uvec2(0x55555555u, 0x3fa55555u)));
                }
                """;
    }

    private static String main(String rootFunction, ValueType rootType, String oreFunction,
                               String aquiferFunction, String aquiferBarrierExpression,
                               OutputMode outputMode) {
        boolean fp64 = rootType == ValueType.FP64;
        String densityType = fp64 ? "uvec2" : "uint";
        String evaluation = rootFunction + "(point)";
        String finite = fp64 ? "!wg_fp64_finite(density)" : "!wg_fp32_finite(density)";
        String material = fp64 ? "wg_material_decide64" : "wg_material_decide";
        String ore = oreFunction == null ? "uvec2(0u, dispatch.defaultStateId)" : oreFunction + "(point)";
        String aquifer = aquiferFunction == null ? "uvec4(0u)" : aquiferFunction + "(point, "
                + (fp64 ? "density" : "wg_fp64_from_fp32(density)")
                + (aquiferBarrierExpression == null ? ")" : ", " + aquiferBarrierExpression + ")");
        String failure = outputMode == OutputMode.STATE_AND_FLUID_MARK
                ? "outputStateIds[index * 2u] = dispatch.invalidStateId;\n"
                        + "                        outputStateIds[index * 2u + 1u] = 0u;"
                : "outputStateIds[index] = dispatch.invalidStateId;";
        String success = outputMode == OutputMode.STATE_AND_FLUID_MARK
                ? ("uint materialState = %s(density, aquiferResult.x != 0u, aquiferResult.y,\n"
                        + "                                ore.x != 0u, ore.y, dispatch.defaultStateId, dispatch.airStateId,\n"
                        + "                                aquiferResult.z != 0u);\n"
                        + "                        outputStateIds[index * 2u] = materialState;\n"
                        + "                        outputStateIds[index * 2u + 1u] = aquiferResult.w;"
                        ).formatted(material)
                : ("outputStateIds[index] = %s(density, aquiferResult.x != 0u, aquiferResult.y,\n"
                        + "                                ore.x != 0u, ore.y, dispatch.defaultStateId, dispatch.airStateId,\n"
                        + "                                aquiferResult.z != 0u);"
                        ).formatted(material);
        return """

                // bounds check is mandatory: out-of-range invocations return before any output write.
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if(index>=dispatch.count)return;
                    wg_failed = false;
                    ivec3 point = wg_point(index);
                    %s density = %s;
                    if (wg_failed || %s) {
                        %s
                    } else {
                        uvec4 aquiferResult = %s;
                        uvec2 ore = %s;
                        if (wg_failed) {
                            %s
                        } else {
                            %s
                        }
                    }
                }
                """.formatted(densityType, evaluation, finite, failure, aquifer, ore, failure, success);
    }

    private static String beardifierMain(boolean combineDensity) {
        int inputWords = combineDensity ? 6 : 4;
        String value = combineDensity
                ? "wg_fp64_add(uvec2(inputBits[inputBase + 4u], inputBits[inputBase + 5u]), wg_beardifier(point))"
                : "wg_beardifier(point)";
        return """

                // bounds check is mandatory: out-of-range invocations return before any output write.
                void main() {
                    uint index = gl_GlobalInvocationID.x;
                    if (index >= dispatch.count) return;
                    wg_failed = false;
                    uint inputBase = index * %du;
                    ivec3 point = ivec3(wg_i32_from_bits(inputBits[inputBase]),
                            wg_i32_from_bits(inputBits[inputBase + 1u]),
                            wg_i32_from_bits(inputBits[inputBase + 2u]));
                    uvec2 value = %s;
                    if (wg_failed || !wg_fp64_finite(value)) value = wg_fp64_qnan();
                    uint outputBase = index * 2u;
                    outputBits[outputBase] = value.x;
                    outputBits[outputBase + 1u] = value.y;
                }
                """.formatted(inputWords, value);
    }

    private static UnsupportedOperationException unsupported(ProgramNode node, String reason) {
        return new UnsupportedOperationException(
                "GPU_IEEE_BITS cannot emit " + node.operation() + " (" + node.type() + "): " + reason);
    }

    private static String rawUInt(int bits) {
        return "0x" + Integer.toUnsignedString(bits, 16) + "u";
    }

    private static String u64Literal(long value) {
        return "uvec2(" + rawUInt((int) value) + ", " + rawUInt((int) (value >>> 32)) + ")";
    }

    private static String fp32Literal(Object value) {
        float number = ((Number) value).floatValue();
        return rawUInt(Float.floatToRawIntBits(number));
    }

    private static String fp64Literal(Object value) {
        long bits = Double.doubleToRawLongBits(((Number) value).doubleValue());
        return "uvec2(" + rawUInt((int) bits) + ", " + rawUInt((int) (bits >>> 32)) + ")";
    }

    private static String operation(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static String fp32Unary(String operation, String child) {
        return switch (operation(operation)) {
            case "negate", "-" -> "wg_fp32_negate(" + child + ")";
            case "abs" -> "wg_fp32_abs(" + child + ")";
            case "square" -> "wg_fp32_mul(" + child + ", " + child + ")";
            case "cube" -> "wg_fp32_mul(wg_fp32_mul(" + child + ", " + child + "), " + child + ")";
            case "floor" -> "wg_fp32_floor(" + child + ")";
            case "sqrt" -> "wg_fp32_sqrt(" + child + ")";
            case "half_negative" -> "wg_fp32_half_negative(" + child + ")";
            case "quarter_negative" -> "wg_fp32_quarter_negative(" + child + ")";
            case "squeeze" -> "wg_fp32_squeeze(" + child + ")";
            default -> throw new UnsupportedOperationException(
                    "GPU_IEEE_BITS cannot emit FP32 unary operation " + operation);
        };
    }

    private static String fp32Binary(String operation, String left, String right) {
        return switch (operation(operation)) {
            case "+", "add" -> "wg_fp32_add(" + left + ", " + right + ")";
            case "-", "subtract" -> "wg_fp32_sub(" + left + ", " + right + ")";
            case "*", "multiply" -> "wg_fp32_mul(" + left + ", " + right + ")";
            case "/", "divide" -> "wg_fp32_div(" + left + ", " + right + ")";
            case "min" -> "wg_fp32_min(" + left + ", " + right + ")";
            case "max" -> "wg_fp32_max(" + left + ", " + right + ")";
            default -> throw new UnsupportedOperationException(
                    "GPU_IEEE_BITS cannot emit FP32 binary operation " + operation);
        };
    }

    private static String fp64Unary(String operation, String child) {
        return switch (operation(operation)) {
            case "negate", "-" -> "wg_fp64_negate(" + child + ")";
            case "abs" -> "wg_fp64_abs(" + child + ")";
            case "square" -> "wg_fp64_mul(" + child + ", " + child + ")";
            case "cube" -> "wg_fp64_mul(wg_fp64_mul(" + child + ", " + child + "), " + child + ")";
            case "floor" -> "wg_fp64_floor(" + child + ")";
            case "sqrt" -> "wg_fp64_sqrt(" + child + ")";
            case "half_negative" -> "wg_fp64_half_negative(" + child + ")";
            case "quarter_negative" -> "wg_fp64_quarter_negative(" + child + ")";
            case "squeeze" -> "wg_fp64_squeeze(" + child + ")";
            default -> throw new UnsupportedOperationException(
                    "GPU_IEEE_BITS cannot emit FP64 unary operation " + operation);
        };
    }

    private static String fp64Binary(String operation, String left, String right) {
        return switch (operation(operation)) {
            case "+", "add" -> "wg_fp64_add(" + left + ", " + right + ")";
            case "-", "subtract" -> "wg_fp64_sub(" + left + ", " + right + ")";
            case "*", "multiply" -> "wg_fp64_mul(" + left + ", " + right + ")";
            case "/", "divide" -> "wg_fp64_div(" + left + ", " + right + ")";
            case "min" -> "wg_fp64_min(" + left + ", " + right + ")";
            case "max" -> "wg_fp64_max(" + left + ", " + right + ")";
            default -> throw new UnsupportedOperationException(
                    "GPU_IEEE_BITS cannot emit FP64 binary operation " + operation);
        };
    }

    private static final class Emitter {
        private final Map<ProgramNode, String> names = new IdentityHashMap<>();
        private final Map<String, ProgramNode> nodesByName = new LinkedHashMap<>();
        private final Map<String, BlendedNoiseParameters> blendedNoiseFunctions = new TreeMap<>();
        private final Map<ProgramNode.SplineNode, String> splineNames = new IdentityHashMap<>();
        private final Map<EndIslandParameters, String> endIslandNames = new java.util.HashMap<>();
        private final StringBuilder functions = new StringBuilder();
        private final NoiseEmitter noiseEmitter = new NoiseEmitter();
        private final StructureBlendSnapshot structureBlend;
        private final MarkerPolicy markerPolicy;
        private String blendDensityFunction;
        private String blendHeightFunction;
        private boolean beardifierHelpersRequired;
        private int nextFunction;

        private Emitter(StructureBlendSnapshot structureBlend, MarkerPolicy markerPolicy) {
            this.structureBlend = Objects.requireNonNull(structureBlend, "structureBlend");
            this.markerPolicy = Objects.requireNonNull(markerPolicy, "markerPolicy");
        }

        String functions() { return functions.toString(); }
        String noiseHelpers() {
            return noiseEmitter.source() + (beardifierHelpersRequired ? BeardifierEmitter.source(structureBlend) : "");
        }
        int nextFragmentId() { return nextFunction++; }

        Map<String, BlendedNoiseParameters> blendedNoiseFunctions() {
            return Map.copyOf(blendedNoiseFunctions);
        }

        Map<String, ProgramNode> nodeFunctions() {
            return Map.copyOf(nodesByName);
        }

        /** Emit the captured six-material OreVeinifier branch after density. */
        String emitOre(WorldgenProgram program, MaterialOptions material) {
            OreVeinEmitter.Emission emitted = new OreVeinEmitter().emit(
                    program, material, this::emitFp64Root, () -> nextFunction++);
            if (emitted.entryPoint() == null) return null;
            functions.append(emitted.source());
            return emitted.entryPoint();
        }

        private String emitFp64Root(WorldgenProgram program, String rootName) {
            ProgramNode root = program.root(rootName);
            if (root == null) throw new IllegalArgumentException("Program is missing ore root " + rootName);
            if (root.type() != ValueType.FP32 && root.type() != ValueType.FP64) {
                throw unsupported(root, "ore roots must be FP32 or FP64");
            }
            String function = emitNode(root) + "(point)";
            return root.type() == ValueType.FP64 ? function : "wg_fp64_from_fp32(" + function + ")";
        }

        String emitNode(ProgramNode node) {
            Objects.requireNonNull(node, "node");
            String existing = names.get(node);
            if (existing != null) return existing;
            validateNode(node);
            String name = "wg_node_" + nextFunction++;
            String body = switch (node) {
                case ProgramNode.Constant constant -> emitConstant(constant);
                case ProgramNode.Input input -> emitInput(input);
                case ProgramNode.Unary unary -> emitUnary(unary);
                case ProgramNode.Binary binary -> emitBinary(binary);
                case ProgramNode.Ap2 ap2 -> emitAp2(ap2);
                case ProgramNode.Select select -> emitSelect(select);
                case ProgramNode.RangeChoice range -> emitRange(range);
                case ProgramNode.Interpolated interpolated -> emitInterpolation(interpolated);
                case ProgramNode.Marker marker -> emitMarker(marker);
                case ProgramNode.Noise noise -> emitNoise(noise);
                case ProgramNode.ShiftedNoise shiftedNoise -> emitShiftedNoise(shiftedNoise);
                case ProgramNode.Shift shift -> emitShift(shift);
                case ProgramNode.Spline spline -> emitSpline(spline);
                case ProgramNode.EndIsland endIsland -> emitEndIsland(endIsland);
                case ProgramNode.WeirdScaledSampler weirdScaled -> emitWeirdScaledSampler(weirdScaled);
                case ProgramNode.BlendedNoise blendedNoise -> emitBlendedNoise(blendedNoise);
                case ProgramNode.BlendDensity blendDensity -> emitBlendDensity(blendDensity);
                case ProgramNode.BlendAlpha blendAlpha -> emitBlendAlpha(blendAlpha);
                case ProgramNode.BlendOffset blendOffset -> emitBlendOffset(blendOffset);
                case ProgramNode.Beardifier beardifier -> emitBeardifier(beardifier);
                default -> throw unsupported(node, "the node kind has no admitted integer-carrier emitter");
            };
            names.put(node, name);
            nodesByName.put(name, node);
            functions.append(glslReturnType(node.type())).append(' ').append(name).append("(ivec3 point) {\n")
                    .append(body).append("\n}\n");
            return name;
        }

        private void validateNode(ProgramNode node) {
            if (node.type() == ValueType.INT64) {
                throw unsupported(node, "INT64 two-limb integer operations are not yet admitted");
            }
            if (node.type() != ValueType.FP32 && node.type() != ValueType.FP64 && node.type() != ValueType.INT32
                    && node.type() != ValueType.BOOLEAN) {
                throw unsupported(node, "only FP32, FP64, INT32 and BOOLEAN values cross the current ABI");
            }
            if (node instanceof ProgramNode.Noise noise && noise.parameters().captured() == null) {
                throw unsupported(node, "parameter-only noise has no captured seed-expanded tables");
            }
            if (node instanceof ProgramNode.ShiftedNoise shiftedNoise && shiftedNoise.parameters().captured() == null) {
                throw unsupported(node, "parameter-only shifted noise has no captured seed-expanded tables");
            }
            if (node instanceof ProgramNode.Shift shift && shift.parameters().captured() == null) {
                throw unsupported(node, "parameter-only shift has no captured seed-expanded tables");
            }
            if (node instanceof ProgramNode.WeirdScaledSampler weirdScaled
                    && weirdScaled.parameters().captured() == null) {
                throw unsupported(node, "parameter-only weird-scaled noise has no captured seed-expanded tables");
            }
            if (node instanceof ProgramNode.BlendedNoise blendedNoise
                    && blendedNoise.parameters() == null) {
                throw unsupported(node, "blended noise has no captured seed-expanded tables");
            }
            if (node instanceof ProgramNode.BlendDensity
                    && structureBlend.densitySamples().isEmpty()
                    && structureBlend.directDensitySamples().isEmpty()) {
                // Blender.EMPTY is an identity transform.  Keeping this
                // explicit lets ordinary captured routers compile without
                // pretending they have structure data.
            }
        }

        private static String glslReturnType(ValueType type) {
            return switch (type) {
                case BOOLEAN -> "bool";
                case FP64 -> "uvec2";
                default -> "uint";
            };
        }

        private static String emitConstant(ProgramNode.Constant constant) {
            return switch (constant.type()) {
                case FP32 -> "return " + fp32Literal(constant.value()) + ";";
                case FP64 -> "return " + fp64Literal(constant.value()) + ";";
                case INT32 -> "return " + rawUInt(((Number) constant.value()).intValue()) + ";";
                case BOOLEAN -> "return " + constant.value() + ";";
                default -> throw unsupported(constant, "constant type is outside the current shader ABI");
            };
        }

        private String emitNoise(ProgramNode.Noise noise) {
            return "return " + noiseEmitter.emit(noise.parameters().captured(),
                    noise.xzScale(), noise.yScale(), noise.type()) + ";";
        }

        private String emitShiftedNoise(ProgramNode.ShiftedNoise noise) {
            String shiftX = fp64Expression(noise.shiftX(), emitNode(noise.shiftX()) + "(point)");
            String shiftY = fp64Expression(noise.shiftY(), emitNode(noise.shiftY()) + "(point)");
            String shiftZ = fp64Expression(noise.shiftZ(), emitNode(noise.shiftZ()) + "(point)");
            String x = "wg_fp64_add(wg_fp64_mul(wg_fp64_from_int(point.x), "
                    + fp64Literal(noise.xzScale()) + "), " + shiftX + ")";
            String y = "wg_fp64_add(wg_fp64_mul(wg_fp64_from_int(point.y), "
                    + fp64Literal(noise.yScale()) + "), " + shiftY + ")";
            String z = "wg_fp64_add(wg_fp64_mul(wg_fp64_from_int(point.z), "
                    + fp64Literal(noise.xzScale()) + "), " + shiftZ + ")";
            return "return " + noiseEmitter.convert(noiseEmitter.emitRaw(noise.parameters().captured(), x, y, z), noise.type()) + ";";
        }

        private String emitShift(ProgramNode.Shift shift) {
            String scale = fp64Literal(shift.scale());
            String x = "wg_fp64_mul(wg_fp64_from_int(point.x), " + scale + ")";
            String y = "wg_fp64_mul(wg_fp64_from_int(point.y), " + scale + ")";
            String z = "wg_fp64_mul(wg_fp64_from_int(point.z), " + scale + ")";
            String zero = "uvec2(0u)";
            String sampleX, sampleY, sampleZ;
            switch (shift.axis()) {
                case "X0Z" -> { sampleX = x; sampleY = zero; sampleZ = z; }
                case "XYZ" -> { sampleX = x; sampleY = y; sampleZ = z; }
                case "ZX0" -> { sampleX = z; sampleY = x; sampleZ = zero; }
                default -> throw unsupported(shift, "unknown shift axis");
            }
            String raw = noiseEmitter.emitRaw(shift.parameters().captured(), sampleX, sampleY, sampleZ);
            String scaled = "wg_fp64_mul(" + raw + ", " + fp64Literal(4.0) + ")";
            return "return " + noiseEmitter.convert(scaled, shift.type()) + ";";
        }

        private String emitWeirdScaledSampler(ProgramNode.WeirdScaledSampler sampler) {
            String inputFunction = emitNode(sampler.input());
            String input = sampler.input().type() == ValueType.FP64
                    ? inputFunction + "(point)"
                    : "wg_fp64_from_fp32(" + inputFunction + "(point))";
            StringBuilder body = new StringBuilder("uvec2 selector = ").append(input).append(";\n")
                    .append("uvec2 rarity;\n");
            if (sampler.rarityMapper().equals("TYPE1")) {
                body.append("if (wg_fp64_less(selector, ").append(fp64Literal(-0.5)).append(") ) rarity = ")
                        .append(fp64Literal(0.75)).append(";\n")
                        .append("else if (wg_fp64_less(selector, ").append(fp64Literal(0.0)).append(") ) rarity = ")
                        .append(fp64Literal(1.0)).append(";\n")
                        .append("else if (wg_fp64_less(selector, ").append(fp64Literal(0.5)).append(") ) rarity = ")
                        .append(fp64Literal(1.5)).append(";\n")
                        .append("else rarity = ").append(fp64Literal(2.0)).append(";\n");
            } else {
                body.append("if (wg_fp64_less(selector, ").append(fp64Literal(-0.75)).append(") ) rarity = ")
                        .append(fp64Literal(0.5)).append(";\n")
                        .append("else if (wg_fp64_less(selector, ").append(fp64Literal(-0.5)).append(") ) rarity = ")
                        .append(fp64Literal(0.75)).append(";\n")
                        .append("else if (wg_fp64_less(selector, ").append(fp64Literal(0.5)).append(") ) rarity = ")
                        .append(fp64Literal(1.0)).append(";\n")
                        .append("else if (wg_fp64_less(selector, ").append(fp64Literal(0.75)).append(") ) rarity = ")
                        .append(fp64Literal(2.0)).append(";\n")
                        .append("else rarity = ").append(fp64Literal(3.0)).append(";\n");
            }
            String x = "wg_fp64_div(wg_fp64_from_int(point.x), rarity)";
            String y = "wg_fp64_div(wg_fp64_from_int(point.y), rarity)";
            String z = "wg_fp64_div(wg_fp64_from_int(point.z), rarity)";
            String sampled = noiseEmitter.emitRaw(sampler.parameters().captured(), x, y, z);
            String value = "wg_fp64_mul(rarity, wg_fp64_abs(" + sampled + "))";
            body.append("return ").append(noiseEmitter.convert(value, sampler.type())).append(';');
            return body.toString();
        }

        private String emitBlendedNoise(ProgramNode.BlendedNoise noise) {
            String function = noiseEmitter.emitBlendedFunction(noise.parameters());
            blendedNoiseFunctions.put(function, noise.parameters());
            return "return " + noiseEmitter.convert(function + "(point)", noise.type()) + ";";
        }

        private String emitBlendDensity(ProgramNode.BlendDensity blend) {
            String childFunction = emitNode(blend.child());
            String current = "uvec2 current = " + (blend.type() == ValueType.FP64
                    ? childFunction + "(point)"
                    : "wg_fp64_from_fp32(" + childFunction + "(point))") + ";\n";
            if (structureBlend.densitySamples().isEmpty() && structureBlend.directDensitySamples().isEmpty()) {
                return current + "return " + (blend.type() == ValueType.FP64 ? "current" : "wg_fp64_to_fp32(current)" ) + ";";
            }
            String blended = emitBlendDensityFunction();
            String value = blended + "(point, current)";
            return current + "return " + (blend.type() == ValueType.FP64 ? value : "wg_fp64_to_fp32(" + value + ")") + ";";
        }

        private String emitBlendAlpha(ProgramNode.BlendAlpha blend) {
            String helper = emitBlendHeightFunction();
            String value = helper + "(point).xy";
            return "return " + (blend.type() == ValueType.FP64 ? value : "wg_fp64_to_fp32(" + value + ")") + ";";
        }

        private String emitBeardifier(ProgramNode.Beardifier beardifier) {
            // Empty captured structures have an exact positive-zero contribution,
            // not an unsupported-node fallback. Nonempty snapshots execute the
            // same integer-only helper as the outer density combine stage.
            if (!BeardifierEmitter.hasData(structureBlend)) {
                return beardifier.type() == ValueType.FP64 ? "return uvec2(0u);" : "return 0u;";
            }
            beardifierHelpersRequired = true;
            return beardifier.type() == ValueType.FP64 ? "return wg_beardifier(point);"
                    : "return wg_fp64_to_fp32(wg_beardifier(point));";
        }

        private String emitBlendOffset(ProgramNode.BlendOffset blend) {
            String helper = emitBlendHeightFunction();
            String value = helper + "(point).zw";
            return "return " + (blend.type() == ValueType.FP64 ? value : "wg_fp64_to_fp32(" + value + ")") + ";";
        }

        /** Emit the immutable height blend table used by BlendAlpha/BlendOffset. */
        private String emitBlendHeightFunction() {
            if (blendHeightFunction != null) return blendHeightFunction;
            final int maximumSamples = 4096;
            if (structureBlend.heightSamples().size() > maximumSamples
                    || structureBlend.directHeightSamples().size() > maximumSamples) {
                throw new UnsupportedOperationException(
                        "GPU_IEEE_BITS height-blend capture exceeds embedded sample bound " + maximumSamples);
            }
            int suffix = nextFunction++;
            String function = "wg_blend_height_" + suffix;
            var direct = structureBlend.directHeightSamples();
            var weighted = structureBlend.heightSamples();
            functions.append("uvec2 wg_blend_height_offset_").append(suffix).append("(uvec2 height);\n")
                    .append("uvec4 ").append(function).append("(ivec3 point) {\n")
                    .append("    int quartX = wg_i32_from_bits(wg_i32_floor_div(wg_i32_to_bits(point.x), 4u));\n")
                    .append("    int quartZ = wg_i32_from_bits(wg_i32_floor_div(wg_i32_to_bits(point.z), 4u));\n")
                    .append("    int sectionX = wg_i32_from_bits(wg_i32_floor_div(wg_i32_to_bits(quartX), 4u));\n")
                    .append("    int sectionZ = wg_i32_from_bits(wg_i32_floor_div(wg_i32_to_bits(quartZ), 4u));\n")
                    .append("    int localX = wg_i32_from_bits(wg_i32_floor_mod(wg_i32_to_bits(quartX), 4u));\n")
                    .append("    int localZ = wg_i32_from_bits(wg_i32_floor_mod(wg_i32_to_bits(quartZ), 4u));\n");
            appendDirectHeightLookup(function, direct, "sectionX", "sectionZ", "localX", "localZ");
            functions.append("    if (localX == 0 && localZ == 0) {\n");
            appendDirectHeightLookup(function, direct, "sectionX - 1", "sectionZ - 1", "4", "4", "        ");
            functions.append("    }\n    if (localX == 0) {\n");
            appendDirectHeightLookup(function, direct, "sectionX - 1", "sectionZ", "4", "localZ", "        ");
            functions.append("    }\n    if (localZ == 0) {\n");
            appendDirectHeightLookup(function, direct, "sectionX", "sectionZ - 1", "localX", "4", "        ");
            functions.append("    }\n");

            if (weighted.isEmpty()) {
                functions.append("    return uvec4(").append(fp64Literal(1.0)).append(", ")
                        .append(fp64Literal(0.0)).append(");\n}\n");
                blendHeightFunction = function;
                return function;
            }
            String countName = "wg_blend_height_count_" + suffix;
            functions.append("    const int ").append(countName).append(" = ").append(weighted.size()).append(";\n")
                    .append("    const int wg_blend_height_x_").append(suffix).append("[").append(weighted.size()).append("] = int[")
                    .append(weighted.size()).append("](\n        ");
            appendIntList(weighted.stream().map(StructureBlendSnapshot.HeightSample::quartX).toList());
            functions.append("\n    );\n    const int wg_blend_height_z_").append(suffix).append("[").append(weighted.size()).append("] = int[")
                    .append(weighted.size()).append("](\n        ");
            appendIntList(weighted.stream().map(StructureBlendSnapshot.HeightSample::quartZ).toList());
            functions.append("\n    );\n    const uvec2 wg_blend_height_value_").append(suffix).append("[").append(weighted.size()).append("] = uvec2[")
                    .append(weighted.size()).append("](\n        ");
            for (int index = 0; index < weighted.size(); index++) {
                if (index > 0) functions.append(", ");
                functions.append(fp64Literal(weighted.get(index).height()));
            }
            functions.append("\n    );\n    uvec2 weighted = uvec2(0u), weights = uvec2(0u), closest = ")
                    .append(fp64Literal(Double.POSITIVE_INFINITY)).append(";\n    bool found = false;\n")
                    .append("    for (int index = 0; index < ").append(countName).append("; index++) {\n")
                    .append("        uvec2 dx = wg_fp64_from_int(quartX - wg_blend_height_x_").append(suffix).append("[index]);\n")
                    .append("        uvec2 dz = wg_fp64_from_int(quartZ - wg_blend_height_z_").append(suffix).append("[index]);\n")
                    .append("        uvec2 distance = wg_fp64_sqrt(wg_fp64_add(wg_fp64_mul(dx, dx), wg_fp64_mul(dz, dz)));\n")
                    .append("        if (wg_fp64_less_equal(distance, ").append(fp64Literal(27.0)).append(")) {\n")
                    .append("            if (wg_fp64_zero(distance)) return uvec4(").append(fp64Literal(0.0)).append(", wg_blend_height_offset_").append(suffix).append("(wg_blend_height_value_").append(suffix).append("[index]));\n")
                    .append("            if (wg_fp64_less(distance, closest)) closest = distance;\n")
                    .append("            uvec2 distanceSquared = wg_fp64_mul(distance, distance);\n")
                    .append("            uvec2 weight = wg_fp64_div(").append(fp64Literal(1.0)).append(", wg_fp64_mul(distanceSquared, distanceSquared));\n")
                    .append("            weighted = wg_fp64_add(weighted, wg_fp64_mul(wg_blend_height_value_").append(suffix).append("[index], weight));\n")
                    .append("            weights = wg_fp64_add(weights, weight);\n")
                    .append("            found = true;\n        }\n    }\n")
                    .append("    if (!found) return uvec4(").append(fp64Literal(1.0)).append(", ").append(fp64Literal(0.0)).append(");\n")
                    .append("    uvec2 oldHeight = wg_fp64_div(weighted, weights);\n")
                    .append("    uvec2 blend = wg_fp64_max(uvec2(0u), wg_fp64_min(").append(fp64Literal(1.0)).append(", wg_fp64_div(closest, ").append(fp64Literal(28.0)).append(")));\n")
                    .append("    uvec2 blendSquared = wg_fp64_mul(blend, blend);\n")
                    .append("    blend = wg_fp64_sub(wg_fp64_mul(").append(fp64Literal(3.0)).append(", blendSquared), wg_fp64_mul(wg_fp64_mul(").append(fp64Literal(2.0)).append(", blendSquared), blend));\n")
                    .append("    return uvec4(blend, wg_blend_height_offset_").append(suffix).append("(oldHeight));\n}\n")
                    .append("uvec2 wg_blend_height_offset_").append(suffix).append("(uvec2 height) {\n")
                    .append("    uvec2 shifted = wg_fp64_add(height, ").append(fp64Literal(0.5)).append(");\n")
                    .append("    uvec2 modulo = wg_fp64_sub(shifted, wg_fp64_mul(wg_fp64_floor(wg_fp64_div(shifted, ").append(fp64Literal(8.0)).append(")), ").append(fp64Literal(8.0)).append("));\n")
                    .append("    uvec2 numerator = wg_fp64_add(wg_fp64_sub(wg_fp64_mul(").append(fp64Literal(32.0)).append(", wg_fp64_sub(shifted, ").append(fp64Literal(128.0)).append(")), wg_fp64_mul(wg_fp64_mul(").append(fp64Literal(3.0)).append(", wg_fp64_sub(shifted, ").append(fp64Literal(120.0)).append(")), modulo)), wg_fp64_mul(").append(fp64Literal(3.0)).append(", wg_fp64_mul(modulo, modulo)));\n")
                    .append("    uvec2 denominator = wg_fp64_mul(").append(fp64Literal(128.0)).append(", wg_fp64_sub(").append(fp64Literal(32.0)).append(", wg_fp64_mul(").append(fp64Literal(3.0)).append(", modulo)));\n")
                    .append("    return wg_fp64_div(numerator, denominator);\n}\n");
            blendHeightFunction = function;
            return function;
        }

        private void appendDirectHeightLookup(String function,
                                               java.util.List<StructureBlendSnapshot.DirectHeightSample> samples,
                                               String sectionX, String sectionZ, String localX, String localZ) {
            appendDirectHeightLookup(function, samples, sectionX, sectionZ, localX, localZ, "    ");
        }

        private void appendDirectHeightLookup(String function,
                                               java.util.List<StructureBlendSnapshot.DirectHeightSample> samples,
                                               String sectionX, String sectionZ, String localX, String localZ,
                                               String indent) {
            for (var sample : samples) {
                functions.append(indent).append("if (").append(sectionX).append(" == ").append(sample.sectionX())
                        .append(" && ").append(sectionZ).append(" == ").append(sample.sectionZ())
                        .append(" && ").append(localX).append(" == ").append(sample.localX())
                        .append(" && ").append(localZ).append(" == ").append(sample.localZ()).append(") return uvec4(")
                        .append(fp64Literal(0.0)).append(", ").append(fp64Literal(heightToOffset(sample.height()))).append(");\n");
            }
        }

        private void appendIntList(java.util.List<Integer> values) {
            for (int index = 0; index < values.size(); index++) {
                if (index > 0) functions.append(", ");
                functions.append(values.get(index));
            }
        }

        private static double heightToOffset(double height) {
            double shifted = height + 0.5;
            double modulo = shifted % 8.0;
            if (modulo < 0.0) modulo += 8.0;
            return (32.0 * (shifted - 128.0) - 3.0 * (shifted - 120.0) * modulo
                    + 3.0 * modulo * modulo) / (128.0 * (32.0 - 3.0 * modulo));
        }

        /** Emit a bounded, immutable replay table for Blender's density path. */
        private String emitBlendDensityFunction() {
            if (blendDensityFunction != null) return blendDensityFunction;
            final int maximumSamples = 4096;
            if (structureBlend.densitySamples().size() > maximumSamples
                    || structureBlend.directDensitySamples().size() > maximumSamples) {
                throw new UnsupportedOperationException(
                        "GPU_IEEE_BITS structure blend capture exceeds embedded sample bound " + maximumSamples);
            }
            int suffix = nextFunction++;
            String function = "wg_blend_density_" + suffix;
            var weighted = structureBlend.densitySamples();
            var direct = structureBlend.directDensitySamples();
            var legacyDirect = direct.isEmpty() ? weighted : java.util.List.<StructureBlendSnapshot.DensitySample>of();

            functions.append("uvec2 ").append(function).append("(ivec3 point, uvec2 current) {\n")
                    .append("    int quartX = wg_i32_from_bits(wg_i32_floor_div(wg_i32_to_bits(point.x), 4u));\n")
                    .append("    int cellY = point.y / 8;\n")
                    .append("    int quartZ = wg_i32_from_bits(wg_i32_floor_div(wg_i32_to_bits(point.z), 4u));\n");
            for (var sample : legacyDirect) {
                functions.append("    if (quartX == ").append(sample.quartX())
                        .append(" && cellY == ").append(sample.cellY())
                        .append(" && quartZ == ").append(sample.quartZ()).append(") return ")
                        .append(fp64Literal(sample.density())).append(";\n");
            }
            if (!direct.isEmpty()) {
                functions.append("    int sectionX = wg_i32_from_bits(wg_i32_floor_div(wg_i32_to_bits(quartX), 4u));\n")
                        .append("    int sectionZ = wg_i32_from_bits(wg_i32_floor_div(wg_i32_to_bits(quartZ), 4u));\n")
                        .append("    int localX = wg_i32_from_bits(wg_i32_floor_mod(wg_i32_to_bits(quartX), 4u));\n")
                        .append("    int localZ = wg_i32_from_bits(wg_i32_floor_mod(wg_i32_to_bits(quartZ), 4u));\n");
                appendDirectLookup(function, direct, "sectionX", "sectionZ", "localX", "localZ");
                functions.append("    if (localX == 0 && localZ == 0) {\n");
                appendDirectLookup(function, direct, "sectionX - 1", "sectionZ - 1", "4", "4", "        ");
                functions.append("    }\n    if (localX == 0) {\n");
                appendDirectLookup(function, direct, "sectionX - 1", "sectionZ", "4", "localZ", "        ");
                functions.append("    }\n    if (localZ == 0) {\n");
                appendDirectLookup(function, direct, "sectionX", "sectionZ - 1", "localX", "4", "        ");
                functions.append("    }\n");
            }
            String countName = "wg_blend_count_" + suffix;
            int count = Math.max(1, weighted.size());
            functions.append("    const int ").append(countName).append(" = ").append(weighted.size()).append(";\n");
            if (!weighted.isEmpty()) {
                functions.append("    const int wg_blend_x_").append(suffix).append("[").append(count).append("] = int[").append(count).append("](");
                for (int index = 0; index < count; index++) {
                    if (index > 0) functions.append(',');
                    functions.append(weighted.get(index).quartX());
                }
                functions.append(");\n    const int wg_blend_y_").append(suffix).append("[").append(count).append("] = int[").append(count).append("](");
                for (int index = 0; index < count; index++) {
                    if (index > 0) functions.append(',');
                    functions.append(weighted.get(index).cellY());
                }
                functions.append(");\n    const int wg_blend_z_").append(suffix).append("[").append(count).append("] = int[").append(count).append("](");
                for (int index = 0; index < count; index++) {
                    if (index > 0) functions.append(',');
                    functions.append(weighted.get(index).quartZ());
                }
                functions.append(");\n    const uvec2 wg_blend_value_").append(suffix).append("[").append(count).append("] = uvec2[").append(count).append("](");
                for (int index = 0; index < count; index++) {
                    if (index > 0) functions.append(',');
                    functions.append(fp64Literal(weighted.get(index).density()));
                }
                functions.append(");\n");
            }
            functions.append("    uvec2 weighted = uvec2(0u), weights = uvec2(0u), closest = ")
                    .append(fp64Literal(Double.POSITIVE_INFINITY)).append(";\n")
                    .append("    bool found = false;\n");
            if (!weighted.isEmpty()) {
                functions.append("    for (int index = 0; index < ").append(countName).append("; index++) {\n")
                        .append("        if (wg_blend_y_").append(suffix).append("[index] < cellY - 1 || ")
                        .append("wg_blend_y_").append(suffix).append("[index] > cellY) continue;\n")
                        .append("        int deltaY = (cellY - wg_blend_y_").append(suffix).append("[index]) * 2;\n")
                        .append("        uvec2 dx = wg_fp64_from_int(quartX - wg_blend_x_").append(suffix).append("[index]);\n")
                        .append("        uvec2 dy = wg_fp64_from_int(deltaY);\n")
                        .append("        uvec2 dz = wg_fp64_from_int(quartZ - wg_blend_z_").append(suffix).append("[index]);\n")
                        .append("        uvec2 distance = wg_fp64_sqrt(wg_fp64_add(wg_fp64_add(wg_fp64_mul(dx, dx), wg_fp64_mul(dy, dy)), wg_fp64_mul(dz, dz)));\n")
                        .append("        if (wg_fp64_less_equal(distance, uvec2(0u, 0x40000000u))) {\n")
                        .append("            if (wg_fp64_zero(distance)) return wg_blend_value_").append(suffix).append("[index];\n")
                        .append("            if (wg_fp64_less(distance, closest)) closest = distance;\n")
                        .append("            uvec2 distanceSquared = wg_fp64_mul(distance, distance);\n")
                        .append("            uvec2 weight = wg_fp64_div(uvec2(0u, 0x3ff00000u), wg_fp64_mul(distanceSquared, distanceSquared));\n")
                        .append("            weighted = wg_fp64_add(weighted, wg_fp64_mul(wg_blend_value_").append(suffix).append("[index], weight));\n")
                        .append("            weights = wg_fp64_add(weights, weight);\n")
                        .append("            found = true;\n")
                        .append("        }\n    }\n");
            }
            functions.append("    if (!found) return current;\n")
                    .append("    uvec2 oldDensity = wg_fp64_div(weighted, weights);\n")
                    .append("    uvec2 blend = wg_fp64_max(uvec2(0u), wg_fp64_min(uvec2(0u, 0x3ff00000u), wg_fp64_div(closest, uvec2(0u, 0x40080000u))));\n")
                    .append("    return wg_fp64_add(oldDensity, wg_fp64_mul(blend, wg_fp64_sub(current, oldDensity)));\n}\n");
            blendDensityFunction = function;
            return function;
        }

        private void appendDirectLookup(String function,
                                        java.util.List<StructureBlendSnapshot.DirectDensitySample> samples,
                                        String sectionX, String sectionZ, String localX, String localZ) {
            appendDirectLookup(function, samples, sectionX, sectionZ, localX, localZ, "    ");
        }

        private void appendDirectLookup(String function,
                                        java.util.List<StructureBlendSnapshot.DirectDensitySample> samples,
                                        String sectionX, String sectionZ, String localX, String localZ,
                                        String indent) {
            for (var sample : samples) {
                functions.append(indent).append("if (").append(sectionX).append(" == ").append(sample.sectionX())
                        .append(" && ").append(sectionZ).append(" == ").append(sample.sectionZ())
                        .append(" && ").append(localX).append(" == ").append(sample.localX())
                        .append(" && cellY == ").append(sample.cellY())
                        .append(" && ").append(localZ).append(" == ").append(sample.localZ()).append(") return ")
                        .append(fp64Literal(sample.density())).append(";\n");
            }
        }

        private String emitSpline(ProgramNode.Spline spline) {
            String function = emitSplineNode(spline.spline(), spline.domain());
            String value = spline.type() == ValueType.FP64
                    ? "wg_fp64_from_fp32(" + function + "(point))"
                    : function + "(point)";
            return "return " + value + ";";
        }

        /**
         * Emit Minecraft's float cubic-spline arithmetic as a separate raw
         * FP32 function.  Spline coordinates and knot values are float in the
         * pinned game implementation even when the density-function boundary
         * is represented as FP64, so converting the coordinate before the
         * branch is part of the semantic contract.
         */
        private String emitSplineNode(ProgramNode.SplineNode node, EvaluationDomain domain) {
            String existing = splineNames.get(node);
            if (existing != null) return existing;
            String name = "wg_spline_" + nextFunction++;
            String body;
            if (node instanceof ProgramNode.SplineConstant constant) {
                body = "return " + fp32Literal(constant.value()) + ";";
            } else {
                ProgramNode.SplineMultipoint multipoint = (ProgramNode.SplineMultipoint) node;
                String coordinateFunction = emitNode(multipoint.coordinate());
                String coordinate = multipoint.coordinate().type() == ValueType.FP64
                        ? "wg_fp64_to_fp32(" + coordinateFunction + "(point))"
                        : coordinateFunction + "(point)";
                int knotCount = multipoint.locations().size();
                if (knotCount == 1) {
                    // A single knot still evaluates its captured coordinate and
                    // nested value, then linearly extends on either side. It is
                    // not a constant unless the derivative is signed zero.
                    String value = splineValue(multipoint.values().getFirst(), "point", domain);
                    body = "uint coordinate = " + coordinate + ";\nuint value = " + value
                            + ";\nreturn " + splineExtend("value", fp32Literal(multipoint.locations().getFirst()),
                            fp32Literal(multipoint.derivatives().getFirst()), "coordinate") + ";";
                    functions.append("uint ").append(name).append("(ivec3 point) {\n")
                            .append(body).append("\n}\n");
                    nodesByName.put(name, new ProgramNode.Spline(node, ValueType.FP32, domain));
                    splineNames.put(node, name);
                    return name;
                }
                StringBuilder code = new StringBuilder("uint coordinate = ").append(coordinate).append(";\n");
                // Keep the raw IEEE-754 carriers, but express the repeated
                // cubic arithmetic once.  The old emitter expanded a full
                // copy of this block for every interval; large captured
                // Overworld splines then caused the NVIDIA pipeline compiler
                // to spend minutes optimizing equivalent integer code.
                code.append("uint locations[").append(knotCount).append("] = uint[](");
                for (int index = 0; index < knotCount; index++) {
                    if (index > 0) code.append(", ");
                    code.append(fp32Literal(multipoint.locations().get(index)));
                }
                code.append(");\n");
                code.append("uint derivatives[").append(knotCount).append("] = uint[](");
                for (int index = 0; index < knotCount; index++) {
                    if (index > 0) code.append(", ");
                    code.append(fp32Literal(multipoint.derivatives().get(index)));
                }
                code.append(");\n");
                code.append("uint values[").append(knotCount).append("];\n");
                for (int index = 0; index < knotCount; index++) {
                    code.append("values[").append(index).append("] = ")
                            .append(splineValue(multipoint.values().get(index), "point", domain))
                            .append(";\n");
                }
                code.append("if (wg_fp32_less(coordinate, locations[0])) return ")
                        .append(splineExtend("values[0]", "locations[0]", "derivatives[0]", "coordinate"))
                        .append(";\n");
                code.append("for (int segment = 0; segment < ")
                        .append(knotCount - 1).append("; segment++) {\n")
                        .append("    if (wg_fp32_less(coordinate, locations[segment + 1])) {\n")
                        .append("        uint location0 = locations[segment], location1 = locations[segment + 1];\n")
                        .append("        uint value0 = values[segment], value1 = values[segment + 1];\n")
                        .append("        uint derivative0 = derivatives[segment], derivative1 = derivatives[segment + 1];\n")
                        .append("        uint width = wg_fp32_sub(location1, location0);\n")
                        .append("        uint t = wg_fp32_div(wg_fp32_sub(coordinate, location0), width);\n")
                        .append("        uint delta = wg_fp32_sub(value1, value0);\n")
                        .append("        uint a = wg_fp32_sub(wg_fp32_mul(derivative0, width), delta);\n")
                        .append("        uint b = wg_fp32_add(wg_fp32_negate(wg_fp32_mul(derivative1, width)), delta);\n")
                        .append("        uint base = wg_fp32_lerp(value0, value1, t);\n")
                        .append("        uint correction = wg_fp32_mul(wg_fp32_mul(t, wg_fp32_sub(0x3f800000u, t)), wg_fp32_lerp(a, b, t));\n")
                        .append("        return wg_fp32_add(base, correction);\n")
                        .append("    }\n")
                        .append("}\n");
                int last = knotCount - 1;
                code.append("return ").append(splineExtend("values[" + last + "]", "locations[" + last + "]",
                        "derivatives[" + last + "]", "coordinate")).append(';');
                body = code.toString();
            }
            functions.append("uint ").append(name).append("(ivec3 point) {\n")
                    .append(body).append("\n}\n");
            nodesByName.put(name, new ProgramNode.Spline(node, ValueType.FP32, domain));
            splineNames.put(node, name);
            return name;
        }

        private String splineValue(ProgramNode.SplineNode node, String point, EvaluationDomain domain) {
            return emitSplineNode(node, domain) + "(" + point + ")";
        }

        private static String splineExtend(String value, String location, String derivative, String coordinate) {
            return "wg_fp32_zero(" + derivative + ") ? " + value
                    + " : wg_fp32_add(" + value + ", wg_fp32_mul(" + derivative
                    + ", wg_fp32_sub(" + coordinate + ", " + location + ")))";
        }

        private String emitEndIsland(ProgramNode.EndIsland endIsland) {
            String function = emitEndIslandFunction(endIsland.parameters());
            String value = endIsland.type() == ValueType.FP64
                    ? function + "(point)"
                    : "wg_fp64_to_fp32(" + function + "(point))";
            return "return " + value + ";";
        }

        /**
         * Emit the captured 1.21.1 End-island function. The simplex sampler
         * stays binary64, while the height construction deliberately follows
         * Minecraft's float intermediates and its int32 square overflow.
         */
        private String emitEndIslandFunction(EndIslandParameters parameters) {
            String existing = endIslandNames.get(parameters);
            if (existing != null) return existing;
            int suffix = nextFunction++;
            String permutation = "wg_end_perm_" + suffix;
            functions.append("const uint ").append(permutation).append("[256] = uint[256](");
            for (int index = 0; index < parameters.permutation().size(); index++) {
                if (index > 0) functions.append(',');
                functions.append(parameters.permutation().get(index)).append('u');
            }
            functions.append(");\n");

            String gradient = "wg_end_gradient_" + suffix;
            functions.append("uvec2 ").append(gradient).append("(uint hash, uvec2 x, uvec2 y) {\n")
                    .append("    switch (hash % 12u) {\n")
                    .append("        case 0u: return wg_fp64_add(x, y);\n")
                    .append("        case 1u: return wg_fp64_add(wg_fp64_negate(x), y);\n")
                    .append("        case 2u: return wg_fp64_add(x, wg_fp64_negate(y));\n")
                    .append("        case 3u: return wg_fp64_add(wg_fp64_negate(x), wg_fp64_negate(y));\n")
                    .append("        case 4u: return x;\n")
                    .append("        case 5u: return wg_fp64_negate(x);\n")
                    .append("        case 6u: return x;\n")
                    .append("        case 7u: return wg_fp64_negate(x);\n")
                    .append("        case 8u: return y;\n")
                    .append("        case 9u: return wg_fp64_negate(y);\n")
                    .append("        case 10u: return y;\n")
                    .append("        default: return wg_fp64_negate(y);\n")
                    .append("    }\n}\n");

            String corner = "wg_end_corner_" + suffix;
            functions.append("uvec2 ").append(corner).append("(uint hash, uvec2 x, uvec2 y) {\n")
                    .append("    uvec2 attenuation = wg_fp64_sub(wg_fp64_sub(uvec2(0u, 0x3fe00000u),\n")
                    .append("            wg_fp64_mul(x, x)), wg_fp64_mul(y, y));\n")
                    .append("    if (wg_fp64_less(attenuation, uvec2(0u))) return uvec2(0u);\n")
                    .append("    uvec2 squared = wg_fp64_mul(attenuation, attenuation);\n")
                    .append("    return wg_fp64_mul(wg_fp64_mul(squared, squared), ")
                    .append(gradient).append("(hash, x, y));\n}\n");

            String simplex = "wg_end_simplex_" + suffix;
            double f2 = 0.5 * (Math.sqrt(3.0) - 1.0);
            double g2 = (3.0 - Math.sqrt(3.0)) / 6.0;
            functions.append("uvec2 ").append(simplex).append("(uvec2 x, uvec2 y) {\n")
                    .append("    uvec2 skew = wg_fp64_mul(wg_fp64_add(x, y), ").append(fp64Literal(f2)).append(");\n")
                    .append("    int i = wg_fp64_floor_to_i32(wg_fp64_add(x, skew));\n")
                    .append("    int j = wg_fp64_floor_to_i32(wg_fp64_add(y, skew));\n")
                    .append("    uvec2 unskew = wg_fp64_mul(wg_fp64_from_int(i + j), ").append(fp64Literal(g2)).append(");\n")
                    .append("    uvec2 x0 = wg_fp64_sub(x, wg_fp64_sub(wg_fp64_from_int(i), unskew));\n")
                    .append("    uvec2 y0 = wg_fp64_sub(y, wg_fp64_sub(wg_fp64_from_int(j), unskew));\n")
                    .append("    bool xGreater = wg_fp64_less(y0, x0);\n")
                    .append("    int k = xGreater ? 1 : 0, l = xGreater ? 0 : 1;\n")
                    .append("    uvec2 x1 = wg_fp64_add(wg_fp64_sub(x0, wg_fp64_from_int(k)), ").append(fp64Literal(g2)).append(");\n")
                    .append("    uvec2 y1 = wg_fp64_add(wg_fp64_sub(y0, wg_fp64_from_int(l)), ").append(fp64Literal(g2)).append(");\n")
                    .append("    uvec2 x2 = wg_fp64_add(wg_fp64_sub(x0, uvec2(0u, 0x3ff00000u)), wg_fp64_mul(uvec2(0u, 0x40000000u), ").append(fp64Literal(g2)).append("));\n")
                    .append("    uvec2 y2 = wg_fp64_add(wg_fp64_sub(y0, uvec2(0u, 0x3ff00000u)), wg_fp64_mul(uvec2(0u, 0x40000000u), ").append(fp64Literal(g2)).append("));\n")
                    .append("    uint ik = uint(i) & 255u, jk = uint(j) & 255u;\n")
                    .append("    uint h0 = ").append(permutation).append("[(ik + ").append(permutation).append("[jk]) & 255u] % 12u;\n")
                    .append("    uint h1 = ").append(permutation).append("[(ik + uint(k) + ").append(permutation).append("[(jk + uint(l)) & 255u]) & 255u] % 12u;\n")
                    .append("    uint h2 = ").append(permutation).append("[(ik + 1u + ").append(permutation).append("[(jk + 1u) & 255u]) & 255u] % 12u;\n")
                    .append("    uvec2 n0 = ").append(corner).append("(h0, x0, y0);\n")
                    .append("    uvec2 n1 = ").append(corner).append("(h1, x1, y1);\n")
                    .append("    uvec2 n2 = ").append(corner).append("(h2, x2, y2);\n")
                    .append("    return wg_fp64_mul(uvec2(0u, 0x40518000u), wg_fp64_add(wg_fp64_add(n0, n1), n2));\n}\n");

            String name = "wg_end_island_" + suffix;
            String threshold = fp64Literal((double) (float) -0.9f);
            functions.append("uvec2 ").append(name).append("(ivec3 point) {\n")
                    .append("    int x = point.x / 8, z = point.z / 8;\n")
                    // GLSL signed % is undefined for negative operands. Java's
                    // remainder is the residual of truncation toward zero.
                    .append("    int gridX = x / 2, gridZ = z / 2, remainderX = x - gridX * 2, remainderZ = z - gridZ * 2;\n")
                    .append("    uint xBits = wg_i32_to_bits(x), zBits = wg_i32_to_bits(z);\n")
                    .append("    uint distanceBits = wg_i32_add(wg_i32_mul(xBits, xBits), wg_i32_mul(zBits, zBits));\n")
                    // Java's Mth.sqrt receives the overflowing negative int
                    // as a float.  Math.sqrt produces a positive NaN, but
                    // the following Java subtraction makes the height's raw
                    // binary32 result a negative quiet NaN.  Preserve that
                    // observable bit pattern before the later Math.max calls
                    // can canonicalise it.
                    .append("    if ((distanceBits & 0x80000000u) != 0u) return uvec2(0u, 0xfff80000u);\n")
                    .append("    uint height = wg_fp32_sub(0x42c80000u, wg_fp32_mul(wg_fp32_sqrt(wg_fp32_from_int(wg_i32_from_bits(distanceBits))), 0x41000000u));\n")
                    .append("    height = wg_fp32_max(0xc2c80000u, wg_fp32_min(0x42a00000u, height));\n")
                    .append("    for (int islandOffsetX = -12; islandOffsetX <= 12; islandOffsetX++) {\n")
                    .append("        for (int islandOffsetZ = -12; islandOffsetZ <= 12; islandOffsetZ++) {\n")
                    .append("            int islandX = gridX + islandOffsetX, islandZ = gridZ + islandOffsetZ;\n")
                    .append("            uvec2 islandX64 = wg_fp64_from_int(islandX), islandZ64 = wg_fp64_from_int(islandZ);\n")
                    .append("            uvec2 radiusSquared = wg_fp64_add(wg_fp64_mul(islandX64, islandX64), wg_fp64_mul(islandZ64, islandZ64));\n")
                    .append("            if (wg_fp64_less(uvec2(0u, 0x40b00000u), radiusSquared)\n")
                    .append("                    && wg_fp64_less(").append(simplex).append("(islandX64, islandZ64), ").append(threshold).append(") ) {\n")
                    .append("                uint absX = wg_fp32_abs(wg_fp32_from_int(islandX));\n")
                    .append("                uint absZ = wg_fp32_abs(wg_fp32_from_int(islandZ));\n")
                    .append("                uint scaleProduct = wg_fp32_add(wg_fp32_mul(absX, 0x4556f000u), wg_fp32_mul(absZ, 0x43130000u));\n")
                    .append("                uint quotient = wg_fp32_floor(wg_fp32_div(scaleProduct, 0x41500000u));\n")
                    .append("                uint islandScale = wg_fp32_add(wg_fp32_sub(scaleProduct, wg_fp32_mul(quotient, 0x41500000u)), 0x41100000u);\n")
                    .append("                uint localX = wg_fp32_from_int(remainderX - islandOffsetX * 2);\n")
                    .append("                uint localZ = wg_fp32_from_int(remainderZ - islandOffsetZ * 2);\n")
                    .append("                uint localDistance = wg_fp32_sqrt(wg_fp32_add(wg_fp32_mul(localX, localX), wg_fp32_mul(localZ, localZ)));\n")
                    .append("                uint islandHeight = wg_fp32_sub(0x42c80000u, wg_fp32_mul(localDistance, islandScale));\n")
                    .append("                islandHeight = wg_fp32_max(0xc2c80000u, wg_fp32_min(0x42a00000u, islandHeight));\n")
                    .append("                height = wg_fp32_max(height, islandHeight);\n")
                    .append("            }\n")
                    .append("        }\n")
                    .append("    }\n")
                    .append("    return wg_fp64_div(wg_fp64_sub(wg_fp64_from_fp32(height), uvec2(0u, 0x40200000u)), uvec2(0u, 0x40600000u));\n}\n");
            endIslandNames.put(parameters, name);
            return name;
        }

        private static String fp64Expression(ProgramNode node, String expression) {
            return node.type() == ValueType.FP32 ? "wg_fp64_from_fp32(" + expression + ")" : expression;
        }

        private static String emitInput(ProgramNode.Input input) {
            String name = input.name().toLowerCase(Locale.ROOT);
            // A density-stage carrier is a compiler-owned placeholder.  The
            // runtime replaces this function body with a raw input-buffer
            // lookup after emitting the tiny parent-only shader.  Keeping the
            // placeholder typed here lets the normal emitter validate all of
            // the parent operator semantics instead of introducing a second
            // ad-hoc GLSL emitter in the runtime.
            if (name.startsWith("carrier") && name.substring("carrier".length()).matches("[0-9]+")) {
                return switch (input.type()) {
                    case FP32 -> "return wg_fp32_from_int(point.x);";
                    case FP64 -> "return wg_fp64_from_int(point.x);";
                    case INT32 -> "return wg_i32_to_bits(point.x);";
                    case BOOLEAN -> "return point.x != 0;";
                    default -> throw unsupported(input,
                            "carrier inputs currently support FP32, FP64, INT32 or BOOLEAN only");
                };
            }
            String coordinate = switch (name) {
                case "x", "worldx", "blockx" -> "point.x";
                case "y", "worldy", "blocky" -> "point.y";
                case "z", "worldz", "blockz" -> "point.z";
                default -> null;
            };
            if (coordinate == null) {
                throw unsupported(input, "input '" + input.name() + "' is not represented by the four-word coordinate ABI");
            }
            return switch (input.type()) {
                case FP32 -> "return wg_fp32_from_int(" + coordinate + ");";
                case FP64 -> "return wg_fp64_from_int(" + coordinate + ");";
                case INT32 -> "return wg_i32_to_bits(" + coordinate + ");";
                default -> throw unsupported(input, "coordinate inputs currently support FP32, FP64 or INT32 only");
            };
        }

        private String emitUnary(ProgramNode.Unary unary) {
            String child = emitNode(unary.child()) + "(point)";
            if (unary.type() == ValueType.FP32) return "return " + fp32Unary(unary.operation(), child) + ";";
            if (unary.type() == ValueType.FP64) return "return " + fp64Unary(unary.operation(), child) + ";";
            if (unary.type() == ValueType.INT32) {
                return switch (operation(unary.operation())) {
                    case "negate", "-" -> "return wg_i32_negate(" + child + ");";
                    case "abs" -> "return wg_i32_abs(" + child + ");";
                    default -> throw unsupported(unary, "INT32 unary operation is not implemented");
                };
            }
            throw unsupported(unary, "unary BOOLEAN operation is not implemented");
        }

        private String emitBinary(ProgramNode.Binary binary) {
            String operation = operation(binary.operation());
            String left = emitNode(binary.left()) + "(point)";
            if (binary.type() == ValueType.BOOLEAN) {
                if (operation.equals("and")) {
                    String right = emitNode(binary.right()) + "(point)";
                    return "bool left = " + left + "; if (!left) return false; return " + right + ";";
                }
                if (operation.equals("or")) {
                    String right = emitNode(binary.right()) + "(point)";
                    return "bool left = " + left + "; if (left) return true; return " + right + ";";
                }
                throw unsupported(binary, "unknown BOOLEAN binary operation");
            }
            String right = emitNode(binary.right()) + "(point)";
            if (binary.type() == ValueType.FP32) {
                return "return " + fp32Binary(operation, left, right) + ";";
            }
            if (binary.type() == ValueType.FP64) {
                return "return " + fp64Binary(operation, left, right) + ";";
            }
            if (binary.type() == ValueType.INT32) {
                String result = switch (operation) {
                    case "+", "add" -> "wg_i32_add(" + left + ", " + right + ")";
                    case "-", "subtract" -> "wg_i32_sub(" + left + ", " + right + ")";
                    case "*", "multiply" -> "wg_i32_mul(" + left + ", " + right + ")";
                    case "floor_div" -> "wg_i32_floor_div(" + left + ", " + right + ")";
                    case "floor_mod" -> "wg_i32_floor_mod(" + left + ", " + right + ")";
                    default -> throw unsupported(binary, "INT32 binary operation is not implemented");
                };
                return "return " + result + ";";
            }
            throw unsupported(binary, "binary type is outside the current shader ABI");
        }

        private String emitAp2(ProgramNode.Ap2 ap2) {
            String leftFunction = emitNode(ap2.left());
            String rightFunction = emitNode(ap2.right());
            if (ap2.type() == ValueType.FP32) {
                String left = leftFunction + "(point)";
                String right = rightFunction + "(point)";
                return switch (ap2.operation()) {
                    case "add" -> "uint left = " + left + "; return wg_fp32_add(left, " + right + ");";
                    case "multiply" -> "uint left = " + left + "; if (wg_fp32_equal(left, 0u)) return 0u; return wg_fp32_mul(left, " + right + ");";
                    case "min" -> "uint left = " + left + "; if (wg_fp32_less(left, "
                            + fp32Literal((float) ap2.rightMinValue()) + ")) return left; return wg_fp32_min(left, " + right + ");";
                    case "max" -> "uint left = " + left + "; if (wg_fp32_less("
                            + fp32Literal((float) ap2.rightMaxValue()) + ", left)) return left; return wg_fp32_max(left, " + right + ");";
                    default -> throw unsupported(ap2, "unknown Ap2 operation");
                };
            }
            if (ap2.type() == ValueType.FP64) {
                String left = leftFunction + "(point)";
                String right = rightFunction + "(point)";
                return switch (ap2.operation()) {
                    case "add" -> "uvec2 left = " + left + "; return wg_fp64_add(left, " + right + ");";
                    case "multiply" -> "uvec2 left = " + left + "; if (wg_fp64_equal(left, uvec2(0u))) return uvec2(0u); return wg_fp64_mul(left, " + right + ");";
                    case "min" -> "uvec2 left = " + left + "; if (wg_fp64_less(left, "
                            + fp64Literal(ap2.rightMinValue()) + ")) return left; return wg_fp64_min(left, " + right + ");";
                    case "max" -> "uvec2 left = " + left + "; if (wg_fp64_less("
                            + fp64Literal(ap2.rightMaxValue()) + ", left)) return left; return wg_fp64_max(left, " + right + ");";
                    default -> throw unsupported(ap2, "unknown Ap2 operation");
                };
            }
            throw unsupported(ap2, "Ap2 requires an FP32 or FP64 carrier");
        }

        private String emitSelect(ProgramNode.Select select) {
            String selector = emitNode(select.selector()) + "(point)";
            String whenTrue = emitNode(select.whenTrue());
            String whenFalse = emitNode(select.whenFalse());
            String condition;
            if (select.selector().type() == ValueType.BOOLEAN) {
                condition = selector;
            } else if (select.selector().type() == ValueType.FP32) {
                condition = "wg_fp32_nonzero(" + selector + ")";
            } else if (select.selector().type() == ValueType.FP64) {
                condition = "wg_fp64_nonzero(" + selector + ")";
            } else {
                throw unsupported(select, "selector must be BOOLEAN or FP32 for GPU_IEEE_BITS");
            }
            return "if (" + condition + ") return " + whenTrue + "(point); return " + whenFalse + "(point);";
        }

        private String emitRange(ProgramNode.RangeChoice range) {
            if (range.input().type() != ValueType.FP32 && range.input().type() != ValueType.FP64) {
                throw unsupported(range, "range selector requires an FP32 carrier");
            }
            String input = emitNode(range.input()) + "(point)";
            String inRange = emitNode(range.whenInRange());
            String outOfRange = emitNode(range.whenOutOfRange());
            boolean fp64 = range.input().type() == ValueType.FP64;
            String minimum = fp64 ? fp64Literal(range.minInclusive()) : fp32Literal((float) range.minInclusive());
            String maximum = fp64 ? fp64Literal(range.maxExclusive()) : fp32Literal((float) range.maxExclusive());
            String condition = fp64
                    ? "wg_fp64_less_equal(" + minimum + ", selector) && wg_fp64_less(selector, " + maximum + ")"
                    : "wg_fp32_less_equal(" + minimum + ", selector) && wg_fp32_less(selector, " + maximum + ")";
            return (fp64 ? "uvec2 selector = " : "uint selector = ") + input + "; if (" + condition
                    + ") return " + inRange + "(point); return " + outOfRange + "(point);";
        }

        private String emitInterpolation(ProgramNode.Interpolated interpolated) {
            if (interpolated.type() != ValueType.FP32 && interpolated.type() != ValueType.FP64) {
                throw unsupported(interpolated, "interpolation requires an FP32 or FP64 carrier");
            }
            InterpolationGeometry geometry = interpolated.geometry();
            String child = emitNode(interpolated.child());
            int horizontal = geometry.horizontalCell();
            int vertical = geometry.verticalCell();
            boolean fp64 = interpolated.type() == ValueType.FP64;
            String zero = fp64 ? "uvec2(0u)" : "0u";
            String fractionType = fp64 ? "uvec2" : "uint";
            String cellAxis = fp64 ? "wg_cell_axis64" : "wg_cell_axis";
            StringBuilder body = new StringBuilder();
            body.append("int x0, x1, y0, y1, z0, z1; ").append(fractionType).append(" tx, ty, tz;\n")
                    .append("if (!").append(cellAxis).append("(point.x, ").append(horizontal).append(", x0, x1, tx)) return ").append(zero).append(";\n")
                    .append("if (!").append(cellAxis).append("(point.y, ").append(vertical).append(", y0, y1, ty)) return ").append(zero).append(";\n")
                    .append("if (!").append(cellAxis).append("(point.z, ").append(horizontal).append(", z0, z1, tz)) return ").append(zero).append(";\n")
                    .append("ivec3 p000 = point, p001 = point, p010 = point, p011 = point, p100 = point, p101 = point, p110 = point, p111 = point;\n")
                    .append("p000.x=x0; p000.y=y0; p000.z=z0; p001.x=x0; p001.y=y0; p001.z=z1;\n")
                    .append("p010.x=x0; p010.y=y1; p010.z=z0; p011.x=x0; p011.y=y1; p011.z=z1;\n")
                    .append("p100.x=x1; p100.y=y0; p100.z=z0; p101.x=x1; p101.y=y0; p101.z=z1;\n")
                    .append("p110.x=x1; p110.y=y1; p110.z=z0; p111.x=x1; p111.y=y1; p111.z=z1;\n")
                    .append(glslReturnType(interpolated.type())).append(" v000=").append(child).append("(p000), v001=").append(child).append("(p001), ")
                    .append("v010=").append(child).append("(p010), v011=").append(child).append("(p011),\n")
                    .append("v100=").append(child).append("(p100), v101=").append(child).append("(p101), ")
                    .append("v110=").append(child).append("(p110), v111=").append(child).append("(p111);\n");
            if (markerPolicy == MarkerPolicy.DIRECT_POINT_REPLAY) {
                // Minecraft's NoiseInterpolator delegates to Mth.lerp3, which
                // evaluates X, then Y, then Z.  The ordinary typed language
                // retains its historical Y-X-Z order below; captured loader
                // graphs must use the game order because boundary decisions
                // can observe the exact intermediate rounding.
                body.append(glslReturnType(interpolated.type())).append(" x00=")
                        .append(lerp(interpolated.type(), "v000", "v100", "tx"))
                        .append(", x01=").append(lerp(interpolated.type(), "v001", "v101", "tx"))
                        .append(", x10=").append(lerp(interpolated.type(), "v010", "v110", "tx"))
                        .append(", x11=").append(lerp(interpolated.type(), "v011", "v111", "tx")).append(";\n")
                        .append(glslReturnType(interpolated.type())).append(" interpY0=")
                        .append(lerp(interpolated.type(), "x00", "x10", "ty"))
                        .append(", interpY1=").append(lerp(interpolated.type(), "x01", "x11", "ty")).append(";\n")
                        .append("return ").append(lerp(interpolated.type(), "interpY0", "interpY1", "tz")).append(';');
            } else {
                body.append(glslReturnType(interpolated.type())).append(" x00=")
                        .append(lerp(interpolated.type(), "v000", "v010", "ty"))
                        .append(", x01=").append(lerp(interpolated.type(), "v001", "v011", "ty")).append(";\n")
                        .append(glslReturnType(interpolated.type())).append(" x10=")
                        .append(lerp(interpolated.type(), "v100", "v110", "ty"))
                        .append(", x11=").append(lerp(interpolated.type(), "v101", "v111", "ty")).append(";\n")
                        .append("return ").append(lerp(interpolated.type(),
                                lerp(interpolated.type(), "x00", "x10", "tx"),
                                lerp(interpolated.type(), "x01", "x11", "tx"), "tz")).append(';');
            }
            return body.toString();
        }

        private String emitMarker(ProgramNode.Marker marker) {
            String mode = marker.cacheMode().toUpperCase(Locale.ROOT);
            if ((mode.equals("NONE") || mode.equals("TRANSPARENT"))) {
                return "return " + emitNode(marker.child()) + "(point);";
            }
            if (markerPolicy != MarkerPolicy.DIRECT_POINT_REPLAY) {
                throw unsupported(marker, "cache mode " + marker.cacheMode() + " changes evaluation identity and needs a staged cache emitter");
            }
            String child = emitNode(marker.child());
            String explanation = switch (mode) {
                case "CACHE_ONCE", "ONCE" ->
                        "CacheOnce is a direct-point boundary here; no NoiseChunk counter crosses the ABI.";
                case "CACHE_ALL_IN_CELL", "ALL_IN_CELL" ->
                        "CacheAllInCell is a direct-point boundary here; cell fill state stays game-owned.";
                case "CACHE2D", "CACHE_2D" ->
                        "Cache2D is a direct-point boundary here; raw Marker.compute does not alter coordinates.";
                case "FLATCACHE", "FLAT_CACHE" ->
                        "FlatCache is a direct-point boundary here; NoiseChunk precomputation is not present.";
                default -> throw unsupported(marker, "unknown marker cache mode " + marker.cacheMode());
            };
            return "/* Tellurium marker " + mode + ": " + explanation + " */\n"
                    + "return " + child + "(point);";
        }

        private static String lerp(ValueType type, String left, String right, String fraction) {
            String helper = type == ValueType.FP64 ? "wg_fp64_lerp" : "wg_fp32_lerp";
            return helper + "(" + left + ", " + right + ", " + fraction + ")";
        }
    }
}
