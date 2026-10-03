// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.jvm.worldgen;

import dev.worldgennext.semantic.material.OreVeinProgram;
import dev.worldgennext.semantic.program.MarkerContext;
import dev.worldgennext.semantic.program.WorldgenProgram;
import dev.worldgennext.semantic.snapshot.PositionalRandomFactorySnapshot;

/** Deterministic ore/filler branch ordering; no positive-density shortcut is used. */
public final class OreVeinEvaluator {
    /*
     * A captured router evaluation is request-local, but the interpreter's
     * boundary flags are thread-local.  Reusing one interpreter therefore
     * keeps the evaluator allocation-free at the per-block seam without
     * sharing evaluation state between concurrent requests.
     */
    private final WorldgenInterpreter interpreter = new WorldgenInterpreter();

    public record Result(boolean ore, String state, String branch) {
        public Result { if (state == null || state.isBlank() || branch == null || branch.isBlank()) throw new IllegalArgumentException("Ore result fields required"); }
    }
    public Result evaluate(OreVeinProgram program, long seed, int x, int y, int z, double density) {
        if (!program.enabled() || program.materials().isEmpty() || density <= 0) return new Result(false, "minecraft:stone", "default");
        long hash = new PositionalRandom(seed).at(x, y, z, program.randomSalt());
        int selector = (int) Long.remainderUnsigned(hash, 100);
        if (selector >= 85) return new Result(true, program.materials().get(0), "raw_block");
        if (selector >= 55) return new Result(true, program.materials().get(Math.min(1, program.materials().size() - 1)), "ore");
        if (selector >= 35) return new Result(true, program.materials().get(program.materials().size() - 1), "filler");
        return new Result(false, "minecraft:stone", "default");
    }

    /** Exact 1.21.1 OreVeinifier rule using captured router roots and positional RNG state. */
    public Result evaluate(OreVeinProgram program, WorldgenProgram router,
                           PositionalRandomFactorySnapshot randomFactory, long worldSeed, int x, int y, int z,
                           MarkerContext markers) {
        if (!program.enabled() || program.materials().size() < 6) return new Result(false, "minecraft:air", "default");
        // OreVeinifier is invoked with NoiseChunk's interpolation context,
        // unlike aquifer helper roots which receive SinglePointContext.  The
        // captured route therefore retains any NoiseInterpolator wrappers.
        double toggle = interpreter.evaluateAtCaptured(router, program.toggleNode(), worldSeed, x, y, z, markers);
        boolean copper = toggle > 0.0;
        double magnitude = Math.abs(toggle);
        int minY = copper ? 0 : -60;
        int maxY = copper ? 50 : -8;
        int distanceToEdge = Math.min(maxY - y, y - minY);
        double edgeCorrection = clampedMap(distanceToEdge, 0.0, 20.0, -0.2, 0.0);
        if (distanceToEdge < 0 || magnitude + edgeCorrection < 0.4F) return new Result(false, "minecraft:air", "default");

        var random = PositionalRandom.at(randomFactory, x, y, z);
        if (random.nextFloat() > 0.7F) return new Result(false, "minecraft:air", "none");
        double ridged = interpreter.evaluateAtCaptured(router, program.ridgedNode(), worldSeed, x, y, z, markers);
        if (ridged >= 0.0) return new Result(false, "minecraft:air", "none");
        double richness = clampedMap(magnitude, 0.4F, 0.6F, 0.1F, 0.3F);
        // OreVeinifier uses Java's short-circuit && here.  In particular, a
        // failed richness draw must not evaluate the gap sampler or consume
        // any state hidden behind a captured auxiliary root.
        if (random.nextFloat() < richness) {
            double gap = interpreter.evaluateAtCaptured(router, program.gapNode(), worldSeed, x, y, z, markers);
            if (gap > -0.3F) {
                if (random.nextFloat() < 0.02F) return new Result(true, copper ? program.materials().get(1) : program.materials().get(4), "raw_block");
                return new Result(true, copper ? program.materials().get(0) : program.materials().get(3), "ore");
            }
        }
        return new Result(true, copper ? program.materials().get(2) : program.materials().get(5), "filler");
    }

    private static double clampedMap(double value, double inMin, double inMax, double outMin, double outMax) {
        double fraction = (value - inMin) / (inMax - inMin);
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        return outMin + fraction * (outMax - outMin);
    }
}
