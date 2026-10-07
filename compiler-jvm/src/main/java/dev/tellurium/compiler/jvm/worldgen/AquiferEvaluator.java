// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.jvm.worldgen;

import dev.tellurium.semantic.material.AquiferProgram;
import dev.tellurium.semantic.program.MarkerContext;
import dev.tellurium.semantic.program.WorldgenProgram;
import dev.tellurium.semantic.snapshot.GeneratorSettingsSnapshot;
import dev.tellurium.semantic.snapshot.PositionalRandomFactorySnapshot;
import dev.tellurium.semantic.snapshot.StructureBlendSnapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Ordered aquifer candidate evaluator for the CPU baseline. */
public final class AquiferEvaluator {
    // Minecraft 1.21.1's DimensionType.WAY_BELOW_MIN_Y sentinel.  This is a
    // real fluid-level value, not Integer.MIN_VALUE; pressure calculations
    // intentionally use it as an ordinary level.
    private static final int WAY_BELOW_MIN_Y = -32512;
    // OverworldBiomeBuilder uses float literals promoted to double. Preserve
    // those exact binary values at the deep-dark branch boundary.
    private static final double DEEP_DARK_EROSION_THRESHOLD = -0.22499999403953552D;
    private static final double DEEP_DARK_DEPTH_THRESHOLD = 0.8999999761581421D;
    public record Result(boolean fluid, String state, double level, boolean scheduleFluidUpdate,
                         boolean noAquiferUsesDefault, boolean aquiferCandidate) {
        public Result(boolean fluid, String state, double level, boolean scheduleFluidUpdate) {
            this(fluid, state, level, scheduleFluidUpdate, false, fluid);
        }
        public Result(boolean fluid, String state, double level, boolean scheduleFluidUpdate,
                      boolean noAquiferUsesDefault) {
            this(fluid, state, level, scheduleFluidUpdate, noAquiferUsesDefault,
                    !noAquiferUsesDefault);
        }
        public Result { if (state == null || state.isBlank()) throw new IllegalArgumentException("Fluid state required"); }

        /** Compatibility alias for the pre-v0.2 name. */
        public boolean barrier() { return scheduleFluidUpdate; }
    }

    /** One-point nearest-three/pressure trace for device mismatch diagnosis. */
    public record DecisionTrace(int firstX, int firstY, int firstZ, int firstDistance,
                                int secondX, int secondY, int secondZ, int secondDistance,
                                int thirdX, int thirdY, int thirdZ, int thirdDistance,
                                String firstAt, String secondAt, String thirdAt,
                                double similarity12, double similarity13, double similarity23,
                                double pressure12Base, double pressure12,
                                double pressure13Base, double pressure13,
                                double pressure23Base, double pressure23,
                                String decision) {}

    /**
     * Immutable cell input for a device-side aquifer decision.  This is a
     * status capture, not a block answer: the shader still selects the
     * nearest three cells and evaluates pressure for each requested block.
     */
    public record CapturedCandidate(int x, int y, int z, int level, String state, boolean fluid) {
        public CapturedCandidate {
            if (state == null || state.isBlank()) throw new IllegalArgumentException("Captured aquifer state required");
        }
    }

    /** Request-local immutable-input caches; no mutable state is shared between chunks. */
    public static final class EvaluationContext {
        private final Map<CellKey, Long> centers = new HashMap<>();
        private final Map<Long, FluidStatus> statuses = new HashMap<>();
        private final Map<ColumnKey, Integer> preliminarySurfaces = new HashMap<>();
        private final Map<ValueKey, Double> values = new HashMap<>();
        private final WorldgenInterpreter interpreter = new WorldgenInterpreter();
        private final MarkerContext markers;

        public EvaluationContext() {
            this(StructureBlendSnapshot.empty());
        }

        /** Request-owned marker/blend inputs used by every aquifer root lookup. */
        public EvaluationContext(StructureBlendSnapshot structureBlend) {
            this(structureBlend, null);
        }

        /** Same request context with the finite NoiseChunk FlatCache domain. */
        public EvaluationContext(StructureBlendSnapshot structureBlend,
                                 MarkerContext.FlatCacheBounds flatCacheBounds) {
            markers = new MarkerContext(0, structureBlend, flatCacheBounds);
        }
    }

    private record CellKey(int x, int y, int z) {}
    private record ColumnKey(int x, int z) {}
    private record ValueKey(String root, int x, int y, int z, boolean interpolated) {}
    public Result evaluate(AquiferProgram program, long seed, int x, int y, int z, double density) {
        Objects.requireNonNull(program, "program");
        if (density > 0) return new Result(false, "minecraft:air", Double.NEGATIVE_INFINITY,
                false, false, false);
        if (!program.enabled()) {
            // NoiseChunk's disabled aquifer still delegates to the global
            // fluid picker.  It is not an unconditional dry/air path: the
            // configured default fluid fills below its level, with the
            // vanilla lava band below -54.
            int globalLavaLevel = (int) Math.min((long) AquiferProgram.VANILLA_GLOBAL_LAVA_LEVEL,
                    (long) Math.floor(program.waterLevel()));
            boolean lava = y < globalLavaLevel;
            double level = lava ? globalLavaLevel : program.waterLevel();
            boolean fluid = y < level;
            return new Result(fluid, fluid ? (lava ? program.lavaState() : program.waterState()) : "minecraft:air",
                    level, false, false, true);
        }
        long hash = new PositionalRandom(seed).at(Math.floorDiv(x, program.cellSize()), Math.floorDiv(y, program.cellSize()), Math.floorDiv(z, program.cellSize()), 0xA91F1E2L);
        boolean lava = y < program.lavaLevel() && (hash & 7) == 0;
        double level = lava ? program.lavaLevel() : program.waterLevel();
        boolean fluid = y < level;
        return new Result(fluid, fluid ? (lava ? program.lavaState() : program.waterState()) : "minecraft:air",
                level, (hash & 15) == 0, false, true);
    }

    /**
     * Captures every cell status that can be selected by a rectangular block
     * request.  Router evaluation and fluid-status construction happen here,
     * while the final nearest-three/pressure/material decision remains
     * device-side.  The bounds are inclusive and normally describe one
     * chunk's logical storage range.
     */
    public java.util.List<CapturedCandidate> captureDeviceCandidates(
            AquiferProgram program, WorldgenProgram router,
            PositionalRandomFactorySnapshot randomFactory, GeneratorSettingsSnapshot settings,
            long seed, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        return captureDeviceCandidates(program, router, randomFactory, settings, seed,
                minX, maxX, minY, maxY, minZ, maxZ, new EvaluationContext());
    }

    /** Same capture with an explicit request-owned cache for a larger staged request. */
    public java.util.List<CapturedCandidate> captureDeviceCandidates(
            AquiferProgram program, WorldgenProgram router,
            PositionalRandomFactorySnapshot randomFactory, GeneratorSettingsSnapshot settings,
            long seed, int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
            EvaluationContext context) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(router, "router");
        Objects.requireNonNull(randomFactory, "randomFactory");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(context, "context");
        if (!program.enabled()) return java.util.List.of();
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Aquifer capture bounds must be ordered");
        }

        int minCellX = Math.floorDiv(Math.subtractExact(minX, 5), 16);
        int maxCellX = Math.addExact(Math.floorDiv(Math.subtractExact(maxX, 5), 16), 1);
        int minCellY = Math.addExact(Math.floorDiv(Math.addExact(minY, 1), 12), -1);
        int maxCellY = Math.addExact(Math.floorDiv(Math.addExact(maxY, 1), 12), 1);
        int minCellZ = Math.floorDiv(Math.subtractExact(minZ, 5), 16);
        int maxCellZ = Math.addExact(Math.floorDiv(Math.subtractExact(maxZ, 5), 16), 1);

        var result = new java.util.ArrayList<CapturedCandidate>();
        for (int cellX = minCellX; ; cellX++) {
            for (int cellY = minCellY; ; cellY++) {
                for (int cellZ = minCellZ; ; cellZ++) {
                    long packed = cellCenter(randomFactory, cellX, cellY, cellZ, context);
                    FluidStatus status = aquiferStatus(program, router, randomFactory, settings, seed, packed, context);
                    String state = status.state();
                    result.add(new CapturedCandidate(unpackX(packed), unpackY(packed), unpackZ(packed),
                            status.level(), state, !isAir(state)));
                    if (cellZ == maxCellZ) break;
                }
                if (cellY == maxCellY) break;
            }
            if (cellX == maxCellX) break;
        }
        return java.util.List.copyOf(result);
    }

    /**
     * Replays the 1.21.1 NoiseBasedAquifer decision with captured router roots. The
     * location cache is intentionally request-local and deterministic; a repeated cell
     * lookup produces the same immutable status without sharing mutable game state.
     */
    public Result evaluate(AquiferProgram program, WorldgenProgram router,
                           PositionalRandomFactorySnapshot randomFactory, GeneratorSettingsSnapshot settings,
                           long seed, int x, int y, int z, double density) {
        return evaluate(program, router, randomFactory, settings, seed, x, y, z, density, new EvaluationContext());
    }

    /** Same exact route with a request-owned cache for all blocks in one chunk. */
    public Result evaluate(AquiferProgram program, WorldgenProgram router,
                           PositionalRandomFactorySnapshot randomFactory, GeneratorSettingsSnapshot settings,
                           long seed, int x, int y, int z, double density, EvaluationContext context) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(settings, "settings");
        if (density > 0.0) return new Result(false, "minecraft:air", Double.NEGATIVE_INFINITY,
                false, false, false);
        if (!program.enabled()) {
            // Aquifer.createDisabled() still calls the global FluidPicker for
            // every non-solid density. Preserve that default-fluid behavior
            // in the same result ABI used by the enabled path.
            FluidStatus global = globalFluid(program, settings, y);
            String state = global.at(y);
            return new Result(!isAir(state), state, global.level(), false, false, true);
        }
        if (context == null) throw new IllegalArgumentException("Aquifer evaluation context is missing");
        FluidStatus global = globalFluid(program, settings, y);
        if (isLava(global.at(y))) {
            return new Result(true, program.lavaState(), global.level(), false, false, true);
        }

        int gridX = Math.floorDiv(x - 5, 16);
        int gridY = Math.floorDiv(y + 1, 12);
        int gridZ = Math.floorDiv(z - 5, 16);
        int firstDistance = Integer.MAX_VALUE, secondDistance = Integer.MAX_VALUE, thirdDistance = Integer.MAX_VALUE;
        long first = 0L, second = 0L, third = 0L;
        for (int dx = 0; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = 0; dz <= 1; dz++) {
            int cellX = gridX + dx, cellY = gridY + dy, cellZ = gridZ + dz;
            long point = cellCenter(randomFactory, cellX, cellY, cellZ, context);
            int pointX = unpackX(point), pointY = unpackY(point), pointZ = unpackZ(point);
            int deltaX = pointX - x, deltaY = pointY - y, deltaZ = pointZ - z;
            int distance = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
            if (firstDistance >= distance) {
                third = second; second = first; first = blockPos(pointX, pointY, pointZ);
                thirdDistance = secondDistance; secondDistance = firstDistance; firstDistance = distance;
            } else if (secondDistance >= distance) {
                third = second; second = blockPos(pointX, pointY, pointZ);
                thirdDistance = secondDistance; secondDistance = distance;
            } else if (thirdDistance >= distance) {
                third = blockPos(pointX, pointY, pointZ); thirdDistance = distance;
            }
        }

        FluidStatus firstStatus = aquiferStatus(program, router, randomFactory, settings, seed, first, context);
        double similarity12 = similarity(firstDistance, secondDistance);
        String state = firstStatus.at(y);
        if (similarity12 <= 0.0) {
            return new Result(!isAir(state), state, firstStatus.level(), similarity12 >= similarity(100, 144), false, true);
        }
        if (isWater(state) && isLava(globalFluid(program, settings, y - 1).at(y - 1))) {
            return new Result(true, state, firstStatus.level(), true, false, true);
        }

        FluidStatus secondStatus = aquiferStatus(program, router, randomFactory, settings, seed, second, context);
        double pressure12Base = calculatePressure(router, settings, seed, x, y, z,
                firstStatus, secondStatus, context);
        double pressure12 = similarity12 * pressure12Base;
        if (density + pressure12 > 0.0) {
            return new Result(false, "minecraft:air", firstStatus.level(), false, true, false);
        }
        FluidStatus thirdStatus = aquiferStatus(program, router, randomFactory, settings, seed, third, context);
        double similarity13 = similarity(firstDistance, thirdDistance);
        if (similarity13 > 0.0) {
            double pressure13Base = calculatePressure(router, settings, seed, x, y, z, firstStatus, thirdStatus, context);
            double pressure13 = similarity12 * similarity13 * pressure13Base;
            if (density + pressure13 > 0.0) {
                return new Result(false, "minecraft:air", firstStatus.level(), false, true, false);
            }
        }
        double similarity23 = similarity(secondDistance, thirdDistance);
        if (similarity23 > 0.0) {
            double pressure23Base = calculatePressure(router, settings, seed, x, y, z, secondStatus, thirdStatus, context);
            double pressure23 = similarity12 * similarity23 * pressure23Base;
            if (density + pressure23 > 0.0) {
                return new Result(false, "minecraft:air", firstStatus.level(), false, true, false);
            }
        }
        return new Result(!isAir(state), state, firstStatus.level(), true, false, true);
    }

    /**
     * Reports the exact scalar branch inputs for one enabled aquifer lookup.
     * This intentionally shares the evaluator's private pressure helper so
     * the trace cannot drift from the CPU oracle while the GPU ABI is being
     * brought to parity.
     */
    public DecisionTrace trace(AquiferProgram program, WorldgenProgram router,
                               PositionalRandomFactorySnapshot randomFactory, GeneratorSettingsSnapshot settings,
                               long seed, int x, int y, int z, double density, EvaluationContext context) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(router, "router");
        Objects.requireNonNull(randomFactory, "randomFactory");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(context, "context");
        if (!program.enabled() || density > 0.0) {
            return new DecisionTrace(0, 0, 0, -1, 0, 0, 0, -1, 0, 0, 0, -1,
                    "minecraft:air", "minecraft:air", "minecraft:air",
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, "shortcut");
        }
        int gridX = Math.floorDiv(x - 5, 16);
        int gridY = Math.floorDiv(y + 1, 12);
        int gridZ = Math.floorDiv(z - 5, 16);
        int firstDistance = Integer.MAX_VALUE, secondDistance = Integer.MAX_VALUE, thirdDistance = Integer.MAX_VALUE;
        long first = 0L, second = 0L, third = 0L;
        for (int dx = 0; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = 0; dz <= 1; dz++) {
            long point = cellCenter(randomFactory, gridX + dx, gridY + dy, gridZ + dz, context);
            int pointX = unpackX(point), pointY = unpackY(point), pointZ = unpackZ(point);
            int deltaX = pointX - x, deltaY = pointY - y, deltaZ = pointZ - z;
            int distance = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
            if (firstDistance >= distance) {
                third = second; second = first; first = blockPos(pointX, pointY, pointZ);
                thirdDistance = secondDistance; secondDistance = firstDistance; firstDistance = distance;
            } else if (secondDistance >= distance) {
                third = second; second = blockPos(pointX, pointY, pointZ);
                thirdDistance = secondDistance; secondDistance = distance;
            } else if (thirdDistance >= distance) {
                third = blockPos(pointX, pointY, pointZ); thirdDistance = distance;
            }
        }
        FluidStatus firstStatus = aquiferStatus(program, router, randomFactory, settings, seed, first, context);
        FluidStatus secondStatus = aquiferStatus(program, router, randomFactory, settings, seed, second, context);
        FluidStatus thirdStatus = aquiferStatus(program, router, randomFactory, settings, seed, third, context);
        double similarity12 = similarity(firstDistance, secondDistance);
        double similarity13 = similarity(firstDistance, thirdDistance);
        double similarity23 = similarity(secondDistance, thirdDistance);
        double pressure12Base = Double.NaN, pressure12 = Double.NaN;
        double pressure13Base = Double.NaN, pressure13 = Double.NaN;
        double pressure23Base = Double.NaN, pressure23 = Double.NaN;
        String decision;
        if (similarity12 <= 0.0) {
            decision = "similarity12";
        } else {
            pressure12Base = calculatePressure(router, settings, seed, x, y, z,
                    firstStatus, secondStatus, context);
            pressure12 = similarity12 * pressure12Base;
            if (density + pressure12 > 0.0) {
                decision = "pressure12";
            } else if (similarity13 > 0.0) {
                pressure13Base = calculatePressure(router, settings, seed, x, y, z,
                        firstStatus, thirdStatus, context);
                pressure13 = similarity12 * similarity13 * pressure13Base;
                if (density + pressure13 > 0.0) {
                    decision = "pressure13";
                } else if (similarity23 > 0.0) {
                    pressure23Base = calculatePressure(router, settings, seed, x, y, z,
                            secondStatus, thirdStatus, context);
                    pressure23 = similarity12 * similarity23 * pressure23Base;
                    decision = density + pressure23 > 0.0 ? "pressure23" : "candidate";
                } else {
                    decision = "candidate";
                }
            } else if (similarity23 > 0.0) {
                pressure23Base = calculatePressure(router, settings, seed, x, y, z,
                        secondStatus, thirdStatus, context);
                pressure23 = similarity12 * similarity23 * pressure23Base;
                decision = density + pressure23 > 0.0 ? "pressure23" : "candidate";
            } else {
                decision = "candidate";
            }
        }
        return new DecisionTrace(unpackX(first), unpackY(first), unpackZ(first), firstDistance,
                unpackX(second), unpackY(second), unpackZ(second), secondDistance,
                unpackX(third), unpackY(third), unpackZ(third), thirdDistance,
                firstStatus.at(y), secondStatus.at(y), thirdStatus.at(y),
                similarity12, similarity13, similarity23,
                pressure12Base, pressure12, pressure13Base, pressure13,
                pressure23Base, pressure23, decision);
    }

    private static double calculatePressure(WorldgenProgram router, GeneratorSettingsSnapshot settings, long seed,
                                            int x, int y, int z, FluidStatus first, FluidStatus second,
                                            EvaluationContext context) {
        String firstState = first.at(y), secondState = second.at(y);
        if ((isLava(firstState) && isWater(secondState)) || (isWater(firstState) && isLava(secondState))) return 2.0;
        int difference = Math.abs(first.level() - second.level());
        if (difference == 0) return 0.0;
        double midpoint = 0.5 * (first.level() + second.level());
        double offset = y + 0.5 - midpoint;
        double halfDifference = difference / 2.0;
        double d = halfDifference - Math.abs(offset);
        double pressure;
        if (offset > 0.0) {
            double adjusted = d;
            pressure = adjusted > 0.0 ? adjusted / 1.5 : adjusted / 2.5;
        } else {
            double adjusted = 3.0 + d;
            pressure = adjusted > 0.0 ? adjusted / 3.0 : adjusted / 10.0;
        }
        double barrier = 0.0;
        // Match Minecraft's !(d10 < -2) && !(d10 > 2) form.  Unlike the
        // equivalent-looking inclusive comparison, it deliberately admits
        // NaN to the barrier sampler, preserving the source branch shape.
        if (!(pressure < -2.0) && !(pressure > 2.0)) {
            // calculatePressure is called by NoiseBasedAquifer with the live
            // NoiseChunk context.  Its barrier root therefore observes the
            // block-density interpolation wrapper, unlike the preliminary
            // surface and randomized-fluid roots, which explicitly receive
            // SinglePointContext.  Keep the two boundaries distinct.
            barrier = evaluate(router, "barrierNoise", seed, x, y, z, context, true);
        }
        return 2.0 * (barrier + pressure);
    }

    private static FluidStatus aquiferStatus(AquiferProgram program, WorldgenProgram router, PositionalRandomFactorySnapshot randomFactory,
                                              GeneratorSettingsSnapshot settings, long seed, long packed,
                                              EvaluationContext context) {
        FluidStatus cached = context.statuses.get(packed);
        if (cached != null) return cached;
        int x = unpackX(packed), y = unpackY(packed), z = unpackZ(packed);
        int surface = Integer.MAX_VALUE;
        int upper = y + 12, lower = y - 12;
        boolean neighboringSurface = false;
        int[][] offsets = {{0, 0}, {-2, -1}, {-1, -1}, {0, -1}, {1, -1}, {-3, 0}, {-2, 0}, {-1, 0}, {1, 0}, {-2, 1}, {-1, 1}, {0, 1}, {1, 1}};
        for (int[] offset : offsets) {
            int sampleX = x + offset[0] * 16, sampleZ = z + offset[1] * 16;
            int preliminary = preliminarySurface(router, settings, seed, sampleX, sampleZ, context);
            // Keep Java's int overflow here.  Minecraft computes j1 + 8
            // directly; the wrapped value is observable in the early global
            // fluid return for an all-air preliminary column.
            int surfacePlus = preliminary + 8;
            boolean center = offset[0] == 0 && offset[1] == 0;
            if (center && lower > surfacePlus) {
                FluidStatus result = globalFluid(program, settings, y);
                // NoiseBasedAquifer caches every computed FluidStatus,
                // including the center-column global-fluid shortcut. Keeping
                // this early result in the request-local cache preserves that
                // identity when the same cell is selected again later.
                context.statuses.put(packed, result);
                return result;
            }
            boolean aboveSurface = upper > surfacePlus;
            if (aboveSurface || center) {
                FluidStatus nearby = globalFluid(program, settings, surfacePlus);
                if (!isAir(nearby.at(surfacePlus))) {
                    if (center) neighboringSurface = true;
                    if (aboveSurface) {
                        context.statuses.put(packed, nearby);
                        return nearby;
                    }
                }
            }
            surface = Math.min(surface, preliminary);
        }
        int level = computeSurfaceLevel(router, settings, seed, x, y, z, globalFluid(program, settings, y), surface, neighboringSurface, context);
        String fluid = globalFluid(program, settings, y).state();
        // NoiseBasedAquifer compares the selected fluid state with
        // Blocks.LAVA.defaultBlockState(), not merely with the lava block
        // type. A flowing/custom lava state must still be eligible for the
        // captured default-lava replacement.
        if (level <= -10 && level != WAY_BELOW_MIN_Y
                && !Objects.equals(fluid, program.lavaState())) {
            double lava = evaluate(router, "lava", seed, Math.floorDiv(x, 64), Math.floorDiv(y, 40), Math.floorDiv(z, 64), context);
            fluid = selectFluidType(fluid, level, lava, program.lavaState());
        }
        FluidStatus result = new FluidStatus(level, fluid);
        context.statuses.put(packed, result);
        return result;
    }

    /** Exact 1.21.1 {@code NoiseBasedAquifer.computeFluidType} threshold. */
    static String selectFluidType(String currentState, int surfaceLevel, double lavaNoise, String lavaState) {
        if (currentState == null || lavaState == null) throw new IllegalArgumentException("Aquifer fluid states are required");
        if (surfaceLevel <= -10 && surfaceLevel != WAY_BELOW_MIN_Y
                // Minecraft compares against Blocks.LAVA.defaultBlockState(),
                // not merely the lava block type. A flowing lava state is a
                // valid replacement input and must not be treated as the
                // already-selected default lava state.
                && !Objects.equals(currentState, lavaState) && Math.abs(lavaNoise) > 0.3) {
            return lavaState;
        }
        return currentState;
    }

    private static int preliminarySurface(WorldgenProgram router, GeneratorSettingsSnapshot settings, long seed, int x, int z,
                                          EvaluationContext context) {
        ColumnKey key = new ColumnKey(x, z);
        Integer cached = context.preliminarySurfaces.get(key);
        if (cached != null) return cached;
        int snappedX = Math.floorDiv(x, 4) * 4, snappedZ = Math.floorDiv(z, 4) * 4;
        // Keep the version-pinned descending scan, but perform the loop
        // control in long arithmetic.  The equivalent int decrement wraps at
        // Integer.MIN_VALUE and can turn a malformed extreme-world request
        // into a non-terminating aquifer lookup.
        // NoiseChunk.preliminarySurfaceLevel scans the configured NoiseSettings
        // height, not the full block-storage allocation. A dimension can keep
        // extra storage above its logical generation height; sampling that
        // padding changes the aquifer's surface level and therefore its fluid
        // decision.
        for (long scanY = Math.addExact(settings.minY(), settings.logicalHeight());
             scanY >= settings.minY(); scanY -= settings.cellHeight()) {
            int y = Math.toIntExact(scanY);
            if (evaluate(router, "initialDensityWithoutJaggedness", seed, snappedX, y, snappedZ, context) > 0.390625) {
                context.preliminarySurfaces.put(key, y);
                return y;
            }
        }
        context.preliminarySurfaces.put(key, Integer.MAX_VALUE);
        return Integer.MAX_VALUE;
    }

    private static int computeSurfaceLevel(WorldgenProgram router, GeneratorSettingsSnapshot settings, long seed,
                                           int x, int y, int z, FluidStatus global, int preliminary, boolean neighboringSurface,
                                           EvaluationContext context) {
        double floodedness;
        double depth;
        if (evaluate(router, "erosion", seed, x, y, z, context) < DEEP_DARK_EROSION_THRESHOLD
                && evaluate(router, "depth", seed, x, y, z, context) > DEEP_DARK_DEPTH_THRESHOLD) {
            floodedness = -1.0;
            depth = -1.0;
        } else {
            int delta = preliminary + 8 - y;
            double mapInput = neighboringSurface ? clampedMap(delta, 0.0, 64.0, 1.0, 0.0) : 0.0;
            double floodNoise = Math.max(-1.0, Math.min(1.0, evaluate(router, "fluidLevelFloodedness", seed, x, y, z, context)));
            double d4 = map(mapInput, 1.0, 0.0, -0.3, 0.8);
            double d5 = map(mapInput, 1.0, 0.0, -0.8, 0.4);
            depth = floodNoise - d4;
            floodedness = floodNoise - d5;
        }
        if (depth > 0.0) return global.level();
        if (floodedness > 0.0) {
            int sampleX = Math.floorDiv(x, 16), sampleY = Math.floorDiv(y, 40), sampleZ = Math.floorDiv(z, 16);
            double spread = evaluate(router, "fluidLevelSpread", seed, sampleX, sampleY, sampleZ, context);
            return randomizedFluidSurfaceLevel(preliminary, sampleY, spread);
        }
        return WAY_BELOW_MIN_Y;
    }

    /** Exact 1.21.1 randomized surface cap: local preliminary surface wins over the global level. */
    static int randomizedFluidSurfaceLevel(int preliminary, int sampleY, double spread) {
        int randomized = (int) Math.floor((spread * 10.0) / 3.0) * 3;
        // Minecraft caps the randomized level by the lowest preliminary
        // surface sampled for this aquifer cell, not by the global fluid
        // picker level. The distinction is observable where local terrain is
        // below sea level.
        return Math.min(preliminary, sampleY * 40 + 20 + randomized);
    }

    private static double evaluate(WorldgenProgram router, String root, long seed, int x, int y, int z,
                                   EvaluationContext context) {
        return evaluate(router, root, seed, x, y, z, context, false);
    }

    private static double evaluate(WorldgenProgram router, String root, long seed, int x, int y, int z,
                                   EvaluationContext context, boolean interpolated) {
        return context.values.computeIfAbsent(new ValueKey(root, x, y, z, interpolated), key -> interpolated
                ? context.interpreter.evaluateAtCaptured(router, root, seed, x, y, z, context.markers)
                : context.interpreter.evaluateAtCapturedRaw(router, root, seed, x, y, z, context.markers));
    }

    private static FluidStatus globalFluid(AquiferProgram program, GeneratorSettingsSnapshot settings, int y) {
        return y < Math.min(AquiferProgram.VANILLA_GLOBAL_LAVA_LEVEL, settings.seaLevel())
                ? new FluidStatus(AquiferProgram.VANILLA_GLOBAL_LAVA_LEVEL, program.lavaState())
                : new FluidStatus(settings.seaLevel(), settings.defaultFluid().canonical());
    }

    private static long cellCenter(PositionalRandomFactorySnapshot randomFactory,
                                   int cellX, int cellY, int cellZ, EvaluationContext context) {
        return context.centers.computeIfAbsent(new CellKey(cellX, cellY, cellZ), key -> {
            var random = PositionalRandom.at(randomFactory, key.x(), key.y(), key.z());
            int pointX = Math.addExact(Math.multiplyExact(key.x(), 16), random.nextInt(10));
            int pointY = Math.addExact(Math.multiplyExact(key.y(), 12), random.nextInt(9));
            int pointZ = Math.addExact(Math.multiplyExact(key.z(), 16), random.nextInt(10));
            return blockPos(pointX, pointY, pointZ);
        });
    }
    private static double similarity(int first, int second) { return 1.0 - (double) Math.abs(second - first) / 25.0; }
    private static double clampedMap(double value, double inMin, double inMax, double outMin, double outMax) {
        double t = Math.max(0.0, Math.min(1.0, (value - inMin) / (inMax - inMin)));
        return outMin + t * (outMax - outMin);
    }
    private static double map(double value, double inMin, double inMax, double outMin, double outMax) {
        return outMin + ((value - inMin) / (inMax - inMin)) * (outMax - outMin);
    }
    private static boolean isAir(String state) { return state == null || "minecraft:air".equals(blockName(state)); }
    private static boolean isLava(String state) { return "minecraft:lava".equals(blockName(state)); }
    private static boolean isWater(String state) { return "minecraft:water".equals(blockName(state)); }
    private static String blockName(String state) {
        if (state == null) return "";
        int properties = state.indexOf('[');
        return properties < 0 ? state : state.substring(0, properties);
    }
    private static long blockPos(int x, int y, int z) { return ((long) (x & 0x3ffffff) << 38) | ((long) (z & 0x3ffffff) << 12) | (y & 0xfffL); }
    private static int unpackX(long packed) { int x = (int) (packed >> 38); return x << 6 >> 6; }
    private static int unpackY(long packed) { int y = (int) (packed & 0xfffL); return y << 20 >> 20; }
    private static int unpackZ(long packed) { int z = (int) ((packed >> 12) & 0x3ffffffL); return z << 6 >> 6; }

    private record FluidStatus(int level, String state) {
        private String at(int y) { return y < level ? state : "minecraft:air"; }
    }
}
