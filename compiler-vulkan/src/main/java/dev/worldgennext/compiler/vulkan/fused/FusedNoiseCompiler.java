// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan.fused;

import dev.worldgennext.semantic.program.ProgramNode;
import dev.worldgennext.semantic.program.ValueType;
import dev.worldgennext.semantic.snapshot.GeneratorSettingsSnapshot;
import dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiles a captured Minecraft 1.21.1 noise router plus the NoiseChunk
 * material rule (aquifer, ore veins, default block) into one GLSL source with
 * five batched compute kernels selected by preprocessor define:
 *
 * <ul>
 *   <li>K_COLUMN: FlatCache tables per chunk (quart columns at y=0).</li>
 *   <li>K_CORNER: every interpolated child at every cell corner.</li>
 *   <li>K_AQUIFER: aquifer cell centres and fluid statuses.</li>
 *   <li>K_BLOCK: per-block final density, aquifer, ore and material.</li>
 *   <li>K_HEIGHT: worldgen heightmaps from the block output.</li>
 * </ul>
 *
 * <p>Semantics follow vanilla's NoiseChunk wrapping.  Evaluation modes:
 * COLUMN (FlatCache fill at a SinglePointContext), CORNER (interpolator slice
 * fill), CELL (final density inside CacheAllInCell: interpolators use lerp3,
 * X then Y then Z), VALUE (ore and barrier with the NoiseChunk context:
 * interpolators return the Y, X, Z ordered running value) and POINT (aquifer
 * helpers with a SinglePointContext: interpolators are transparent and
 * FlatCache only substitutes inside the chunk's quart range).  Unsupported
 * graph shapes fail closed with {@link UnsupportedFusedProgramException}.</p>
 */
public final class FusedNoiseCompiler {
    public static final String VERSION = "worldgennext-fused-noise-v1";

    public enum Mode { COLUMN, XZ, CORNER, CELL, VALUE, POINT, POINT_IN, POINT_OUT }

    /** Palette indices of the states the material rule can produce, plus fluid predicates. */
    public record MaterialPalette(int air, int defaultBlock, int defaultFluid, int lava,
                                  int copperOre, int rawCopperBlock, int granite,
                                  int ironOre, int rawIronBlock, int tuff,
                                  boolean defaultFluidIsWater, boolean defaultFluidIsLava,
                                  boolean defaultFluidIsLavaDefaultState, boolean defaultFluidIsAir,
                                  int size, int[] paletteFlags) {
        public static final int FLAG_HAS_FLUID = 1, FLAG_BLOCKS_MOTION = 2, FLAG_NOT_AIR = 4;
        public MaterialPalette {
            Objects.requireNonNull(paletteFlags, "paletteFlags");
            paletteFlags = paletteFlags.clone();
            if (paletteFlags.length != size) throw new IllegalArgumentException("Palette flags must match palette size");
        }
    }

    /**
     * Which aquifer blocks are marked for a fluid update after generation.  Minecraft changed the rule in
     * 1.21.2: before, every fluid block between aquifers that are about equally near; since, only where the
     * aquifers concerned differ in level or fluid, which also looks at the fourth nearest aquifer.
     */
    public enum FluidUpdates { BETWEEN_AQUIFERS, WHERE_NEIGHBOURS_DIFFER }

    public record Request(Map<String, ProgramNode> roots, GeneratorSettingsSnapshot settings,
                          PositionalRandomFactorySnapshot aquiferRandom, PositionalRandomFactorySnapshot oreRandom,
                          MaterialPalette palette, float[] beardKernel, SurfaceProgram surface, FluidUpdates fluidUpdates) {
        public Request(Map<String, ProgramNode> roots, GeneratorSettingsSnapshot settings,
                       PositionalRandomFactorySnapshot aquiferRandom, PositionalRandomFactorySnapshot oreRandom,
                       MaterialPalette palette, float[] beardKernel, SurfaceProgram surface) {
            this(roots, settings, aquiferRandom, oreRandom, palette, beardKernel, surface, FluidUpdates.BETWEEN_AQUIFERS);
        }

        public Request {
            Objects.requireNonNull(roots, "roots");
            Objects.requireNonNull(fluidUpdates, "fluidUpdates");
            Objects.requireNonNull(settings, "settings");
            Objects.requireNonNull(palette, "palette");
            Objects.requireNonNull(beardKernel, "beardKernel");
            if (beardKernel.length != 24 * 24 * 24) throw new IllegalArgumentException("Beardifier kernel must have 13824 entries");
            beardKernel = beardKernel.clone();
        }
    }

    /** Fixed per-chunk geometry shared by host and kernels. */
    /** Quart columns an aquifer cell of one chunk can sample: [chunkQuart - 16, chunkQuart + 14] per axis. */
    public static final int PRELIM_WINDOW = 31;
    public static final int PRELIM_ORIGIN = -16;

    public record Geometry(int minY, int storageHeight, int genHeight, int cellWidth, int cellHeight,
                           int cellsXZ, int cellsY, int minCellY, int columnsPerAxis,
                           int aquiferCellsY, int aquiferMinGridYOffset) {
        public int cornersPerAxisXZ() { return cellsXZ + 1; }
        public int cornersY() { return cellsY + 1; }
        public int cornerCount() { return cornersPerAxisXZ() * cornersPerAxisXZ() * cornersY(); }
        public int columnCount() { return columnsPerAxis * columnsPerAxis; }
        public int blockCount() { return 256 * storageHeight; }
        public int aquiferCellCount() { return 9 * aquiferCellsY; }
    }

    public record Compiled(String source, double[] doubleTable, int[] permTable, int flatChannels,
                           int interpolatedChannels, Geometry geometry, String fingerprint,
                           List<ProgramNode> flatChildren, List<ProgramNode> interpolatedChildren,
                           String structureFingerprint, int xzChannels, int surfaceHeader, String surfaceFingerprint) {
        /** uints of biome ids uploaded per chunk for the surface stage. */
        public int biomeWordsPerChunk() { return FusedSurfaceKernels.biomeWordsPerChunk(geometry); }
        public int biomeQuartHeight() { return geometry.storageHeight() / 4; }
    }

    /** Soft surface flags: a chunk is discarded only when both are set (see FusedSurfaceKernels). */
    public static final int SURF_HEIGHT_CHANGED = FusedSurfaceKernels.SURF_HEIGHT_CHANGED;
    public static final int SURF_STEEP_USED = FusedSurfaceKernels.SURF_STEEP_USED;
    public static final int BAIL_MASK = 0xFF;

    public static final class UnsupportedFusedProgramException extends RuntimeException {
        public UnsupportedFusedProgramException(String message) { super(message); }
    }

    public Compiled compile(Request request) {
        return new Emitter(request).emit();
    }

    static String library() {
        try (InputStream in = FusedNoiseCompiler.class.getResourceAsStream("fused_lib.glsl")) {
            if (in == null) throw new IllegalStateException("fused_lib.glsl resource missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read fused_lib.glsl", failure);
        }
    }

    // ------------------------------------------------------------------
    private static final class Block {
        final Block parent;
        final StringBuilder code = new StringBuilder();
        final Map<ProgramNode, String> values = new IdentityHashMap<>();
        final String indent;
        Block(Block parent, String indent) { this.parent = parent; this.indent = indent; }
        String lookup(ProgramNode node) {
            for (Block b = this; b != null; b = b.parent) {
                String v = b.values.get(node);
                if (v != null) return v;
            }
            return null;
        }
        void line(String text) { code.append(indent).append(text).append('\n'); }
        Block child() { return new Block(this, indent + "    "); }
    }

    private static final class Emitter {
        private final Request request;
        private final GeneratorSettingsSnapshot settings;
        private final FusedTables tables = new FusedTables();
        private final Map<ProgramNode, Integer> flatIds = new IdentityHashMap<>();
        private final List<ProgramNode.Marker> flats = new ArrayList<>();
        private final Map<ProgramNode, Integer> interpIds = new IdentityHashMap<>();
        private final List<ProgramNode.Interpolated> interps = new ArrayList<>();
        private final StringBuilder functions = new StringBuilder();
        private int counter;
        private final Geometry geometry;
        private int beardKernelIndex;

        Emitter(Request request) {
            this.request = request;
            this.settings = request.settings();
            int w = settings.cellWidth(), h = settings.cellHeight();
            if (w <= 0 || h <= 0 || 16 % w != 0) throw new UnsupportedFusedProgramException("Unsupported cell geometry " + w + "x" + h);
            if (Integer.bitCount(w) != 1 || Integer.bitCount(h) != 1) throw new UnsupportedFusedProgramException("Cell sizes must be powers of two");
            int genHeight = settings.logicalHeight();
            if (genHeight % h != 0 || settings.minY() % h != 0) throw new UnsupportedFusedProgramException("Generation height is not cell aligned");
            if (genHeight > settings.height()) throw new UnsupportedFusedProgramException("Generation height exceeds storage");
            int cellsXZ = 16 / w;
            int cellsY = genHeight / h;
            int minCellY = Math.floorDiv(settings.minY(), h);
            int columns = (cellsXZ * w) / 4 + 1;
            int minGridY = Math.floorDiv(settings.minY(), 12) - 1;
            int maxGridY = Math.floorDiv(settings.minY() + genHeight, 12) + 1;
            this.geometry = new Geometry(settings.minY(), settings.height(), genHeight, w, h, cellsXZ, cellsY,
                    minCellY, columns, maxGridY - minGridY + 1, minGridY);
        }

        Compiled emit() {
            Map<String, ProgramNode> roots = request.roots();
            for (String name : List.of("finalDensity", "initialDensityWithoutJaggedness", "barrierNoise",
                    "fluidLevelFloodedness", "fluidLevelSpread", "lava", "erosion", "depth",
                    "veinToggle", "veinRidged", "veinGap")) {
                if (!roots.containsKey(name)) throw new UnsupportedFusedProgramException("Router root missing: " + name);
            }
            double[] kernel = new double[request.beardKernel().length];
            for (int i = 0; i < kernel.length; i++) kernel[i] = request.beardKernel()[i];
            beardKernelIndex = tables.raw(kernel);

            // Root functions.  Order: emit consumers first so flat/interp channels are discovered.
            emitFunction("root_final_cell", roots.get("finalDensity"), Mode.CELL);
            emitFunction("root_toggle_value", roots.get("veinToggle"), Mode.VALUE);
            emitFunction("root_ridged_value", roots.get("veinRidged"), Mode.VALUE);
            emitFunction("root_gap_value", roots.get("veinGap"), Mode.VALUE);
            emitFunction("root_barrier_value", roots.get("barrierNoise"), Mode.VALUE);
            emitPrelimScan(roots.get("initialDensityWithoutJaggedness"));
            emitFunction("root_erosion_point", roots.get("erosion"), Mode.POINT);
            emitFunction("root_depth_point", roots.get("depth"), Mode.POINT);
            emitFunction("root_flood_point", roots.get("fluidLevelFloodedness"), Mode.POINT);
            emitFunction("root_spread_point", roots.get("fluidLevelSpread"), Mode.POINT);
            emitFunction("root_lava_point", roots.get("lava"), Mode.POINT);
            // Y-independent subgraphs hoisted out of the block kernel: one value per block column.
            xzHoisting = false;
            for (int i = 0; i < xzNodes.size(); i++) {
                emitFunction("xz_" + i + "_col", xzNodes.get(i), Mode.XZ);
            }
            // Interpolated children (corner fill) may discover more flat channels.
            for (int k = 0; k < interps.size(); k++) {
                emitFunction("interp_" + k + "_corner", interps.get(k).child(), Mode.CORNER);
            }
            // Flat children at quart columns; a flat child may reference another flat (transparent inline).
            for (int f = 0; f < flats.size(); f++) {
                emitFunction("flat_" + f + "_column", flats.get(f).child(), Mode.COLUMN);
            }

            int surfaceHeader = request.surface() == null ? 0 : tables.surface(request.surface());
            String source = header() + FusedNoiseCompiler.library() + "\n" + accessors()
                    + FusedKernels.prelude(geometry, request, beardKernelIndex) + FusedSurfaceKernels.constants(geometry) + functions
                    + FusedKernels.kernels(flats.size(), interps.size(), xzNodes.size(), request.fluidUpdates()) + FusedSurfaceKernels.BODY;
            String fingerprint = sha256(source);
            List<ProgramNode> flatChildren = new ArrayList<>();
            for (ProgramNode.Marker flat : flats) flatChildren.add(flat.child());
            List<ProgramNode> interpChildren = new ArrayList<>();
            for (ProgramNode.Interpolated interp : interps) interpChildren.add(interp.child());
            return new Compiled(source, tables.doubleTable(), tables.permTable(), flats.size(), interps.size(),
                    geometry, fingerprint, List.copyOf(flatChildren), List.copyOf(interpChildren),
                    structureFingerprint(source), xzNodes.size(), surfaceHeader,
                    request.surface() == null ? null : sha256(request.surface().identity()));
        }

        private String header() {
            return "#version 460\n"
                    + "#extension GL_ARB_gpu_shader_int64 : require\n"
                    + "// " + VERSION + "\n"
                    + "#define SPLINE_COORDS " + maxSplineCoordinates + "\n"
                    + "layout(local_size_x = 64) in;\n"
                    + "struct ChunkInfo { int chunkX; int chunkZ; int beardOffset; int pieceCount; int junctionCount; int pad0; int pad1; int pad2; };\n"
                    + "layout(std430, binding = 0) readonly buffer Chunks { ChunkInfo chunks[]; };\n"
                    + "layout(std430, binding = 1) readonly buffer Perm { uint perm[]; };\n"
                    + "layout(std430, binding = 2) readonly buffer DTab { double dtab[]; };\n"
                    + "layout(std430, binding = 3) buffer Columns { double columns[]; };\n"
                    + "layout(std430, binding = 4) buffer Corners { double corners[]; };\n"
                    + "layout(std430, binding = 5) buffer Aquifer { int aquifer[]; };\n"
                    + "layout(std430, binding = 6) buffer Output { uint blocks[]; };\n"
                    + "layout(std430, binding = 7) readonly buffer Beard { int beard[]; };\n"
                    + "layout(std430, binding = 8) buffer Flags { uint flags[]; };\n"
                    + "layout(std430, binding = 9) buffer Heights { int heights[]; };\n"
                    + "layout(std430, binding = 10) buffer XzCache { double xzcache[]; };\n"
                    + "layout(std430, binding = 11) readonly buffer PrelimIndex { int prelimIndex[]; };\n"
                    + "layout(std430, binding = 12) readonly buffer PrelimCols { int prelimCols[]; };\n"
                    + "layout(std430, binding = 13) buffer PrelimOut { int prelimOut[]; };\n"
                    + "layout(std430, binding = 14) readonly buffer Biomes { uint biomes[]; };\n"
                    + "layout(std430, binding = 15) buffer SurfTmp { int surfTmp[]; };\n"
                    + "layout(push_constant) uniform Push { uint chunkCount; uint prelimCount; } pc;\n";
        }

        private String accessors() {
            Geometry g = geometry;
            int nx = g.cornersPerAxisXZ(), ny = g.cornersY(), cols = g.columnsPerAxis();
            int interpCount = Math.max(1, interps.size()), flatCount = Math.max(1, flats.size());
            StringBuilder out = new StringBuilder();
            out.append("const int FLATS = ").append(flatCount).append(";\n");
            out.append("const int XZS = ").append(Math.max(1, xzNodes.size())).append(";\n");
            out.append("double xzv(int c, ivec3 p) {\n")
                    .append("    return xzcache[(int(gChunk) * XZS + c) * 256 + (((p.z - gBaseZ) << 4) | (p.x - gBaseX))];\n}\n");
            out.append("const int INTERPS = ").append(interpCount).append(";\n");
            out.append("const int COLS = ").append(cols).append(";\n");
            out.append("const int CNX = ").append(nx).append(";\n");
            out.append("const int CNY = ").append(ny).append(";\n");
            out.append("const int CELL_W = ").append(g.cellWidth()).append(";\n");
            out.append("const int CELL_H = ").append(g.cellHeight()).append(";\n");
            out.append("const int MIN_CELL_Y = ").append(g.minCellY()).append(";\n");
            out.append("const double INV_CELL_W = ").append(dlit(1.0 / g.cellWidth())).append(";\n");
            out.append("const double INV_CELL_H = ").append(dlit(1.0 / g.cellHeight())).append(";\n");
            out.append("double colv(int f, ivec3 p) {\n")
                    .append("    int qx = (p.x >> 2) - gFirstQX; int qz = (p.z >> 2) - gFirstQZ;\n")
                    .append("    return columns[((int(gChunk) * FLATS + f) * COLS + qx) * COLS + qz];\n}\n");
            out.append("bool flatInBounds(ivec3 p) {\n")
                    .append("    int qx = (p.x >> 2) - gFirstQX; int qz = (p.z >> 2) - gFirstQZ;\n")
                    .append("    return qx >= 0 && qz >= 0 && qx < COLS && qz < COLS;\n}\n");
            out.append("double cv(int k, int ix, int iy, int iz) {\n")
                    .append("    return corners[(((int(gChunk) * INTERPS + k) * CNY + iy) * CNX + ix) * CNX + iz];\n}\n");
            // lerp3 (X, Y, Z) used inside CacheAllInCell final-density fill.
            out.append("precise double interpCell(int k, ivec3 p) {\n")
                    .append("    int lx = p.x - gBaseX, lz = p.z - gBaseZ;\n")
                    .append("    int ix = lx / CELL_W, iz = lz / CELL_W;\n")
                    .append("    int cy = p.y >> ").append(Integer.numberOfTrailingZeros(g.cellHeight())).append(";\n")
                    .append("    int iy = cy - MIN_CELL_Y;\n")
                    .append("    precise double dx = double(lx - ix * CELL_W) * INV_CELL_W;\n")
                    .append("    precise double dy = double(p.y - cy * CELL_H) * INV_CELL_H;\n")
                    .append("    precise double dz = double(lz - iz * CELL_W) * INV_CELL_W;\n")
                    .append("    double n000 = cv(k, ix, iy, iz), n100 = cv(k, ix + 1, iy, iz);\n")
                    .append("    double n010 = cv(k, ix, iy + 1, iz), n110 = cv(k, ix + 1, iy + 1, iz);\n")
                    .append("    double n001 = cv(k, ix, iy, iz + 1), n101 = cv(k, ix + 1, iy, iz + 1);\n")
                    .append("    double n011 = cv(k, ix, iy + 1, iz + 1), n111 = cv(k, ix + 1, iy + 1, iz + 1);\n")
                    .append("    precise double a = lerpd(dy, lerpd(dx, n000, n100), lerpd(dx, n010, n110));\n")
                    .append("    precise double b = lerpd(dy, lerpd(dx, n001, n101), lerpd(dx, n011, n111));\n")
                    .append("    return lerpd(dz, a, b);\n}\n");
            // Running interpolator value (Y, then X, then Z) for ore/barrier consumers.
            out.append("precise double interpValue(int k, ivec3 p) {\n")
                    .append("    int lx = p.x - gBaseX, lz = p.z - gBaseZ;\n")
                    .append("    int ix = lx / CELL_W, iz = lz / CELL_W;\n")
                    .append("    int cy = p.y >> ").append(Integer.numberOfTrailingZeros(g.cellHeight())).append(";\n")
                    .append("    int iy = cy - MIN_CELL_Y;\n")
                    .append("    precise double dx = double(lx - ix * CELL_W) * INV_CELL_W;\n")
                    .append("    precise double dy = double(p.y - cy * CELL_H) * INV_CELL_H;\n")
                    .append("    precise double dz = double(lz - iz * CELL_W) * INV_CELL_W;\n")
                    .append("    double n000 = cv(k, ix, iy, iz), n100 = cv(k, ix + 1, iy, iz);\n")
                    .append("    double n010 = cv(k, ix, iy + 1, iz), n110 = cv(k, ix + 1, iy + 1, iz);\n")
                    .append("    double n001 = cv(k, ix, iy, iz + 1), n101 = cv(k, ix + 1, iy, iz + 1);\n")
                    .append("    double n011 = cv(k, ix, iy + 1, iz + 1), n111 = cv(k, ix + 1, iy + 1, iz + 1);\n")
                    .append("    precise double xz00 = lerpd(dy, n000, n010), xz10 = lerpd(dy, n100, n110);\n")
                    .append("    precise double xz01 = lerpd(dy, n001, n011), xz11 = lerpd(dy, n101, n111);\n")
                    .append("    precise double z0 = lerpd(dx, xz00, xz10), z1 = lerpd(dx, xz01, xz11);\n")
                    .append("    return lerpd(dz, z0, z1);\n}\n");
            return out.toString();
        }

        private void emitFunction(String name, ProgramNode root, Mode mode) {
            String guard = switch (mode) {
                case COLUMN -> "defined(K_COLUMN)";
                case XZ -> "defined(K_XZ)";
                case CORNER -> "defined(K_CORNER)";
                case CELL, VALUE -> "defined(K_BLOCK)";
                case POINT, POINT_IN, POINT_OUT -> "defined(K_AQUIFER)";
            };
            functions.append("#if ").append(guard).append("\n");
            functions.append("precise double ").append(name).append("(ivec3 p) {\n");
            if (mode == Mode.POINT) {
                // Whether p lies in the chunk's FlatCache range is the same for every
                // FlatCache node, so decide once and share subexpressions per variant.
                Block in = new Block(null, "        ");
                String inResult = value(root, in, Mode.POINT_IN);
                Block out = new Block(null, "        ");
                String outResult = value(root, out, Mode.POINT_OUT);
                functions.append("    if (flatInBounds(p)) {\n").append(in.code).append("        return ").append(inResult)
                        .append(";\n    } else {\n").append(out.code).append("        return ").append(outResult).append(";\n    }\n}\n");
            } else {
                Block body = new Block(null, "    ");
                String result = value(root, body, mode);
                functions.append(body.code).append("    return ").append(result).append(";\n}\n");
            }
            functions.append("#endif\n");
        }

        private String fresh(Block block, String expression) {
            String name = "v" + (counter++);
            block.line("precise double " + name + " = " + expression + ";");
            return name;
        }

        private boolean xzHoisting = true;
        private final Map<ProgramNode, Integer> xzIds = new IdentityHashMap<>();
        private final List<ProgramNode> xzNodes = new ArrayList<>();
        private final Map<ProgramNode, Boolean> blockYDependence = new IdentityHashMap<>();
        private final Map<ProgramNode, Boolean> expensiveNodes = new IdentityHashMap<>();

        private int xzId(ProgramNode node) {
            Integer id = xzIds.get(node);
            if (id != null) return id;
            id = xzNodes.size();
            xzNodes.add(node);
            xzIds.put(node, id);
            return id;
        }

        /** Block-context Y dependence: interpolators and the beardifier vary with Y; a FlatCache is a column lookup. */
        private boolean blockYDependent(ProgramNode node) {
            Boolean known = blockYDependence.get(node);
            if (known != null) return known;
            boolean result;
            if (node instanceof ProgramNode.Interpolated) result = true;
            else if (node instanceof ProgramNode.Marker marker && isFlat(marker)) result = false;
            else if (node.children().isEmpty() || node instanceof ProgramNode.ShiftedNoise
                    || node instanceof ProgramNode.WeirdScaledSampler) result = yDependent(node) || childrenBlockYDependent(node);
            else result = childrenBlockYDependent(node);
            blockYDependence.put(node, result);
            return result;
        }

        private boolean childrenBlockYDependent(ProgramNode node) {
            for (ProgramNode child : node.children()) if (blockYDependent(child)) return true;
            return false;
        }

        /** True when the subgraph contains noise or spline work worth one evaluation per column. */
        private boolean expensive(ProgramNode node) {
            Boolean known = expensiveNodes.get(node);
            if (known != null) return known;
            boolean result;
            if (node instanceof ProgramNode.Marker marker && isFlat(marker)) result = false;
            else if (node instanceof ProgramNode.Noise || node instanceof ProgramNode.ShiftedNoise
                    || node instanceof ProgramNode.Shift || node instanceof ProgramNode.Spline
                    || node instanceof ProgramNode.EndIsland || node instanceof ProgramNode.BlendedNoise
                    || node instanceof ProgramNode.WeirdScaledSampler) result = true;
            else {
                result = false;
                for (ProgramNode child : node.children()) result |= expensive(child);
            }
            expensiveNodes.put(node, result);
            return result;
        }

        private static boolean isFlat(ProgramNode.Marker marker) {
            return marker.cacheMode().toUpperCase(java.util.Locale.ROOT).replace("_", "").equals("FLATCACHE");
        }

        /** When non-null, Y-independent nodes are emitted here (once per column scan) instead of in the Y loop. */
        private Block hoistBlock;
        private Block loopBlock;

        private boolean inLoop(Block block) {
            for (Block b = block; b != null; b = b.parent) if (b == loopBlock) return true;
            return false;
        }
        private final Map<ProgramNode, Boolean> yDependence = new IdentityHashMap<>();

        private boolean yDependent(ProgramNode node) { return FusedNoiseCompiler.yDependent(node, yDependence); }

        /**
         * NoiseChunk.computePreliminarySurfaceLevel as one function: Y-independent
         * subgraphs are evaluated once per column, the descending Y scan only
         * re-evaluates nodes that depend on Y.  Values are pure, so hoisting
         * changes cost only.
         */
        /**
         * The preliminary surface of a quart column is shared by every aquifer
         * cell and chunk that samples it, so it is computed once per batch in
         * K_PRELIM.  That is only valid when the value does not depend on which
         * chunk asks: inside a chunk's FlatCache range vanilla substitutes the
         * table value computed at y=0, outside it evaluates the child at the
         * scan Y, and the two agree exactly when every reachable FlatCache
         * child ignores Y (the column X/Z are already quart aligned).
         */
        private void emitPrelimScan(ProgramNode root) {
            // Preliminary surfaces are read by the aquifer and by the surface rules.  A generator with neither on
            // the GPU never asks K_PRELIM for a column, so the sharing condition does not apply to it.
            if (settings.aquifersEnabled() || request.surface() != null) {
                requireYIndependentFlats(root, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
            }
            functions.append("#if defined(K_PRELIM)\n");
            functions.append("int prelim_scan(int sx, int sz) {\n    ivec3 p = ivec3(sx, 0, sz);\n    {\n");
            emitPrelimVariant(root, Mode.POINT_OUT);
            functions.append("    }\n    return 2147483647;\n}\n#endif\n");
        }

        private void requireYIndependentFlats(ProgramNode node, java.util.Set<ProgramNode> seen) {
            if (!seen.add(node)) return;
            if (node instanceof ProgramNode.Marker marker && isFlat(marker) && yDependent(marker.child())) {
                throw new UnsupportedFusedProgramException(
                        "initialDensityWithoutJaggedness reads a FlatCache whose child depends on Y; shared preliminary surfaces are not exact");
            }
            for (ProgramNode child : node.children()) requireYIndependentFlats(child, seen);
        }

        private void emitPrelimVariant(ProgramNode root, Mode mode) {
            Block outer = new Block(null, "        ");
            Block loop = new Block(outer, "            ");
            hoistBlock = outer;
            loopBlock = loop;
            String result;
            try {
                result = value(root, loop, mode);
            } finally {
                hoistBlock = null;
                loopBlock = null;
            }
            functions.append(outer.code);
            functions.append("        for (int y = MIN_Y + GEN_HEIGHT; y >= MIN_Y; y -= CELL_H) {\n            p.y = y;\n").append(loop.code);
            functions.append("            if (").append(result).append(" > 0.390625lf) return y;\n        }\n");
        }

        private String value(ProgramNode node, Block block, Mode mode) {
            String existing = block.lookup(node);
            if (existing != null) return existing;
            if (xzHoisting && (mode == Mode.CELL || mode == Mode.VALUE) && expensive(node) && !blockYDependent(node)) {
                String name = fresh(block, "xzv(" + xzId(node) + ", p)");
                block.values.put(node, name);
                return name;
            }
            if (hoistBlock != null && inLoop(block) && !yDependent(node)) {
                existing = hoistBlock.lookup(node);
                if (existing != null) return existing;
                return value(node, hoistBlock, mode);
            }
            if (node.type() != ValueType.FP64) {
                throw new UnsupportedFusedProgramException("Only FP64 router nodes are supported outside splines: "
                        + node.operation() + " " + node.type());
            }
            String result = switch (node) {
                case ProgramNode.Constant c -> dlit(((Number) c.value()).doubleValue());
                case ProgramNode.Input input -> switch (input.name()) {
                    case "x", "worldX" -> "double(p.x)";
                    case "y", "worldY" -> "double(p.y)";
                    case "z", "worldZ" -> "double(p.z)";
                    default -> throw new UnsupportedFusedProgramException("Unsupported input " + input.name());
                };
                case ProgramNode.Noise noise -> fresh(block, "normalNoise(" + normal(noise.parameters()) + ", double(p.x) * "
                        + dlit(noise.xzScale()) + ", double(p.y) * " + dlit(noise.yScale()) + ", double(p.z) * "
                        + dlit(noise.xzScale()) + ")");
                case ProgramNode.ShiftedNoise noise -> {
                    String sx = value(noise.shiftX(), block, mode);
                    String sy = value(noise.shiftY(), block, mode);
                    String sz = value(noise.shiftZ(), block, mode);
                    yield fresh(block, "normalNoise(" + normal(noise.parameters()) + ", double(p.x) * " + dlit(noise.xzScale())
                            + " + " + sx + ", double(p.y) * " + dlit(noise.yScale()) + " + " + sy + ", double(p.z) * "
                            + dlit(noise.xzScale()) + " + " + sz + ")");
                }
                case ProgramNode.Shift shift -> {
                    String s = dlit(shift.scale());
                    String args = switch (shift.axis()) {
                        case "XYZ" -> "double(p.x) * " + s + ", double(p.y) * " + s + ", double(p.z) * " + s;
                        case "X0Z" -> "double(p.x) * " + s + ", 0.0lf, double(p.z) * " + s;
                        case "ZX0" -> "double(p.z) * " + s + ", double(p.x) * " + s + ", 0.0lf";
                        default -> throw new UnsupportedFusedProgramException("Unknown shift axis " + shift.axis());
                    };
                    yield fresh(block, "normalNoise(" + normal(shift.parameters()) + ", " + args + ") * 4.0lf");
                }
                case ProgramNode.BlendedNoise blended -> fresh(block, "blendedNoise(" + tables.blended(blended.parameters()) + ", p.x, p.y, p.z)");
                case ProgramNode.EndIsland end -> fresh(block, "endIsland(" + tables.endIsland(end.parameters()) + ", p.x, p.z)");
                case ProgramNode.WeirdScaledSampler sampler -> {
                    String input = value(sampler.input(), block, mode);
                    String rarity = fresh(block, (sampler.rarityMapper().equals("TYPE1") ? "rarity3D(" : "rarity2D(") + input + ")");
                    yield fresh(block, rarity + " * abs(normalNoise(" + normal(sampler.parameters()) + ", jdiv(double(p.x), " + rarity
                            + "), jdiv(double(p.y), " + rarity + "), jdiv(double(p.z), " + rarity + ")))");
                }
                case ProgramNode.BlendDensity blend -> value(blend.child(), block, mode);
                case ProgramNode.BlendAlpha ignored -> "1.0lf";
                case ProgramNode.BlendOffset ignored -> "0.0lf";
                case ProgramNode.Beardifier ignored -> fresh(block, "beardifier(p)");
                case ProgramNode.Spline spline -> splineCall(spline, block, mode);
                case ProgramNode.Unary unary -> unary(unary, block, mode);
                case ProgramNode.Binary binary -> binary(binary, block, mode);
                case ProgramNode.Ap2 ap2 -> ap2(ap2, block, mode);
                case ProgramNode.Select select -> {
                    String selector = value(select.selector(), block, mode);
                    yield branch(block, "(" + selector + " != 0.0lf)", select.whenTrue(), select.whenFalse(), mode);
                }
                case ProgramNode.RangeChoice range -> {
                    String input = value(range.input(), block, mode);
                    yield branch(block, "(" + input + " >= " + dlit(range.minInclusive()) + " && " + input + " < "
                            + dlit(range.maxExclusive()) + ")", range.whenInRange(), range.whenOutOfRange(), mode);
                }
                case ProgramNode.Interpolated interpolated -> interpolated(interpolated, block, mode);
                case ProgramNode.Marker marker -> marker(marker, block, mode);
            };
            block.values.put(node, result);
            return result;
        }

        private String branch(Block block, String condition, ProgramNode whenTrue, ProgramNode whenFalse, Mode mode) {
            String name = "v" + (counter++);
            block.line("precise double " + name + ";");
            block.line("if " + condition + " {");
            Block t = block.child();
            String tv = value(whenTrue, t, mode);
            t.line(name + " = " + tv + ";");
            block.code.append(t.code);
            block.line("} else {");
            Block f = block.child();
            String fv = value(whenFalse, f, mode);
            f.line(name + " = " + fv + ";");
            block.code.append(f.code);
            block.line("}");
            return name;
        }

        private String unary(ProgramNode.Unary unary, Block block, Mode mode) {
            String v = value(unary.child(), block, mode);
            return switch (unary.operation()) {
                case "negate", "-" -> fresh(block, "-" + v);
                case "abs" -> fresh(block, "abs(" + v + ")");
                case "square" -> fresh(block, v + " * " + v);
                case "cube" -> fresh(block, v + " * " + v + " * " + v);
                case "floor" -> fresh(block, "floor(" + v + ")");
                case "sqrt" -> fresh(block, "jsqrt(" + v + ")");
                case "half_negative" -> fresh(block, "(" + v + " > 0.0lf ? " + v + " : " + v + " * 0.5lf)");
                case "quarter_negative" -> fresh(block, "(" + v + " > 0.0lf ? " + v + " : " + v + " * 0.25lf)");
                case "squeeze" -> {
                    String c = fresh(block, "mclamp(" + v + ", -1.0lf, 1.0lf)");
                    yield fresh(block, c + " * 0.5lf - jdiv(" + c + " * " + c + " * " + c + ", 24.0lf)");
                }
                default -> throw new UnsupportedFusedProgramException("Unknown unary " + unary.operation());
            };
        }

        private String binary(ProgramNode.Binary binary, Block block, Mode mode) {
            String a = value(binary.left(), block, mode);
            String b = value(binary.right(), block, mode);
            return switch (binary.operation()) {
                case "+", "add" -> fresh(block, a + " + " + b);
                case "-", "subtract" -> fresh(block, a + " - " + b);
                case "*", "multiply" -> fresh(block, a + " * " + b);
                case "/", "divide" -> fresh(block, "jdiv(" + a + ", " + b + ")");
                case "min" -> fresh(block, "jmin(" + a + ", " + b + ")");
                case "max" -> fresh(block, "jmax(" + a + ", " + b + ")");
                default -> throw new UnsupportedFusedProgramException("Unsupported binary " + binary.operation());
            };
        }

        private String ap2(ProgramNode.Ap2 ap2, Block block, Mode mode) {
            String left = value(ap2.left(), block, mode);
            if (ap2.operation().equals("add")) {
                String right = value(ap2.right(), block, mode);
                return fresh(block, left + " + " + right);
            }
            String name = "v" + (counter++);
            block.line("precise double " + name + ";");
            String shortcut = switch (ap2.operation()) {
                case "multiply" -> left + " == 0.0lf";
                case "min" -> left + " < " + dlit(ap2.rightMinValue());
                case "max" -> left + " > " + dlit(ap2.rightMaxValue());
                default -> throw new UnsupportedFusedProgramException("Unknown Ap2 " + ap2.operation());
            };
            String shortValue = ap2.operation().equals("multiply") ? "0.0lf" : left;
            block.line("if (" + shortcut + ") {");
            block.line("    " + name + " = " + shortValue + ";");
            block.line("} else {");
            Block other = block.child();
            String right = value(ap2.right(), other, mode);
            String combined = switch (ap2.operation()) {
                case "multiply" -> left + " * " + right;
                case "min" -> "jmin(" + left + ", " + right + ")";
                default -> "jmax(" + left + ", " + right + ")";
            };
            other.line(name + " = " + combined + ";");
            block.code.append(other.code);
            block.line("}");
            return name;
        }

        private String interpolated(ProgramNode.Interpolated node, Block block, Mode mode) {
            if (node.geometry().horizontalCell() != settings.cellWidth() || node.geometry().verticalCell() != settings.cellHeight()) {
                throw new UnsupportedFusedProgramException("Interpolation geometry differs from the noise cell");
            }
            return switch (mode) {
                case CELL -> fresh(block, "interpCell(" + interpId(node) + ", p)");
                case VALUE -> fresh(block, "interpValue(" + interpId(node) + ", p)");
                case POINT_IN, POINT_OUT, COLUMN -> value(node.child(), block, mode);
                case POINT -> throw new AssertionError("POINT is split before emission");
                case CORNER, XZ -> throw new UnsupportedFusedProgramException("Nested interpolation is not supported");
            };
        }

        private String marker(ProgramNode.Marker marker, Block block, Mode mode) {
            String cacheMode = marker.cacheMode().toUpperCase(java.util.Locale.ROOT).replace("_", "");
            boolean flat = cacheMode.equals("FLATCACHE");
            if (!flat) {
                // CacheOnce, Cache2D, CacheAllInCell and transparent markers are value caches of a pure function.
                if (!(cacheMode.equals("ONCE") || cacheMode.equals("CACHEONCE") || cacheMode.equals("CACHE2D")
                        || cacheMode.equals("CACHEALLINCELL") || cacheMode.equals("ALLINCELL")
                        || cacheMode.equals("NONE") || cacheMode.equals("TRANSPARENT"))) {
                    throw new UnsupportedFusedProgramException("Unknown marker cache mode " + marker.cacheMode());
                }
                return value(marker.child(), block, mode);
            }
            return switch (mode) {
                case COLUMN, POINT_OUT -> value(marker.child(), block, mode);
                case CORNER, XZ, CELL, VALUE, POINT_IN -> fresh(block, "colv(" + flatId(marker) + ", p)");
                case POINT -> throw new AssertionError("POINT is split before emission");
            };
        }

        /**
         * Spline trees are emitted as one GLSL function per multipoint node,
         * taking the spline's distinct coordinates (evaluated once by the
         * caller, then narrowed to float exactly like Spline.Coordinate) as
         * arguments.  Only the selected interval's children are evaluated.
         */
        private int maxSplineCoordinates = 1;

        private String splineCall(ProgramNode.Spline spline, Block block, Mode mode) {
            if (spline.spline() instanceof ProgramNode.SplineConstant constant) {
                return fresh(block, "double(" + flit(constant.value()) + ")");
            }
            // Distinct coordinates by semantic identity: equal subgraphs yield equal values.
            List<ProgramNode> coordinates = new ArrayList<>();
            Map<String, Integer> byFingerprint = new java.util.HashMap<>();
            Map<ProgramNode, Integer> indexOf = new IdentityHashMap<>();
            collectCoordinates(spline.spline(), coordinates, byFingerprint, indexOf);
            if (coordinates.size() > 64) throw new UnsupportedFusedProgramException("Spline has more than 64 coordinates");
            maxSplineCoordinates = Math.max(maxSplineCoordinates, coordinates.size());
            String array = "cs" + (counter++);
            block.line("float " + array + "[SPLINE_COORDS];");
            for (int i = 0; i < coordinates.size(); i++) {
                String v = value(coordinates.get(i), block, mode);
                block.line(array + "[" + i + "] = float(" + v + ");");
            }
            int record = tables.spline((ProgramNode.SplineMultipoint) spline.spline(), coordinates, indexOf);
            return fresh(block, "double(splineEval(" + record + ", " + array + "))");
        }

        private void collectCoordinates(ProgramNode.SplineNode node, List<ProgramNode> out,
                                        Map<String, Integer> byFingerprint, Map<ProgramNode, Integer> indexOf) {
            if (node instanceof ProgramNode.SplineMultipoint mp) {
                if (!indexOf.containsKey(mp.coordinate())) {
                    String fingerprint = dev.worldgennext.semantic.program.WorldgenProgram.nodeFingerprint(mp.coordinate());
                    Integer index = byFingerprint.get(fingerprint);
                    if (index == null) {
                        index = out.size();
                        out.add(mp.coordinate());
                        byFingerprint.put(fingerprint, index);
                    }
                    indexOf.put(mp.coordinate(), index);
                }
                for (ProgramNode.SplineNode child : mp.values()) collectCoordinates(child, out, byFingerprint, indexOf);
            }
        }

        private int normal(dev.worldgennext.semantic.snapshot.NoiseParameters parameters) {
            if (parameters.captured() == null) throw new UnsupportedFusedProgramException("Noise tables were not captured: " + parameters.key());
            return tables.normal(parameters.captured());
        }

        private int flatId(ProgramNode.Marker marker) {
            Integer id = flatIds.get(marker);
            if (id != null) return id;
            id = flats.size();
            flats.add(marker);
            flatIds.put(marker, id);
            return id;
        }

        private int interpId(ProgramNode.Interpolated node) {
            Integer id = interpIds.get(node);
            if (id != null) return id;
            id = interps.size();
            interps.add(node);
            interpIds.put(node, id);
            return id;
        }
    }

    /**
     * True unless the captured function provably ignores the block Y when evaluated at a single
     * point of an empty-blender NoiseChunk (caches and interpolators are transparent or column keyed).
     */
    public static boolean dependsOnY(ProgramNode root) {
        return yDependent(root, new IdentityHashMap<>());
    }

    /**
     * True when evaluating the function at a single point gives the same value with and without
     * NoiseChunk's cache wrappers.  CacheOnce, CacheAllInCell and interpolators are transparent for a
     * single-point context; FlatCache returns its child at Y 0 of the quart column and Cache2D returns
     * the child's value from an earlier Y of the same column, so both must wrap a child that ignores Y.
     * Callers must also ensure the point is quart aligned in X and Z and the blender is empty.
     */
    public static boolean pointValueIgnoresCaches(ProgramNode root) {
        Map<ProgramNode, Boolean> yDependence = new IdentityHashMap<>();
        java.util.Set<ProgramNode> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<ProgramNode> pending = new java.util.ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            ProgramNode node = pending.poll();
            if (!seen.add(node)) continue;
            if (node instanceof ProgramNode.Marker marker) {
                String mode = marker.cacheMode().toUpperCase(java.util.Locale.ROOT).replace("_", "");
                switch (mode) {
                    case "FLATCACHE", "CACHE2D" -> {
                        if (yDependent(marker.child(), yDependence)) return false;
                    }
                    case "ONCE", "CACHEONCE", "CACHEALLINCELL", "ALLINCELL", "NONE", "TRANSPARENT" -> { }
                    default -> { return false; }
                }
            }
            if (node instanceof ProgramNode.BlendDensity || node instanceof ProgramNode.Beardifier) return false;
            pending.addAll(node.children());
        }
        return true;
    }

    /** Conservative: true unless the node's value provably ignores the block Y in POINT mode. */
    private static boolean yDependent(ProgramNode node, Map<ProgramNode, Boolean> yDependence) {
        Boolean known = yDependence.get(node);
        if (known != null) return known;
        boolean result = switch (node) {
            case ProgramNode.Constant ignored -> false;
            case ProgramNode.Input input -> input.name().equals("y") || input.name().equals("worldY");
            case ProgramNode.Noise noise -> noise.yScale() != 0.0;
            case ProgramNode.ShiftedNoise noise -> noise.yScale() != 0.0 || yDependent(noise.shiftX(), yDependence)
                    || yDependent(noise.shiftY(), yDependence) || yDependent(noise.shiftZ(), yDependence);
            case ProgramNode.Shift shift -> shift.axis().equals("XYZ");
            case ProgramNode.EndIsland ignored -> false;
            case ProgramNode.BlendAlpha ignored -> false;
            case ProgramNode.BlendOffset ignored -> false;
            case ProgramNode.BlendedNoise ignored -> true;
            case ProgramNode.WeirdScaledSampler ignored -> true;
            case ProgramNode.Beardifier ignored -> true;
            default -> {
                boolean any = false;
                for (ProgramNode child : node.children()) any |= yDependent(child, yDependence);
                yield any;
            }
        };
        yDependence.put(node, result);
        return result;
    }

    /**
     * Identity of the generated kernel code without world-seed constants: the
     * positional-random seeds are the only seed-derived values in the source
     * (noise tables live in buffers).  Two worlds of the same stack and
     * settings share this value regardless of seed.
     */
    static String structureFingerprint(String source) {
        StringBuilder kept = new StringBuilder(source.length());
        for (String line : source.split("\n")) {
            if (line.startsWith("const int64_t AQ_") || line.startsWith("const int64_t ORE_")) continue;
            kept.append(line).append('\n');
        }
        return sha256(kept.toString());
    }

    static String dlit(double value) {
        if (!Double.isFinite(value)) {
            return "bitsd(int64_t(0x" + Long.toHexString(Double.doubleToRawLongBits(value)) + "ul))";
        }
        // Double.toString is the shortest round-trip decimal; glslang parses it correctly rounded.
        String text = Double.toString(value);
        return (text.startsWith("-") ? "(" + text + "lf)" : text + "lf");
    }

    static String flit(float value) {
        return "intBitsToFloat(" + Float.floatToRawIntBits(value) + ")";
    }

    static String sha256(String text) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
