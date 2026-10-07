// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.vulkan.worldgen;

import dev.tellurium.semantic.program.WorldgenProgram;
import dev.tellurium.semantic.program.ValueType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Conservative stage planner: explicit boundaries, no fusion or speculative skipping. */
public final class StagePlanner {
    public List<StageLayout> plan(WorldgenProgram program, int elementCount) {
        if (elementCount <= 0) throw new IllegalArgumentException("Element count must be positive");
        Objects.requireNonNull(program, "program");
        var finalDensity = program.root("finalDensity");
        if (finalDensity == null) {
            throw new IllegalArgumentException("A Vulkan stage plan requires the finalDensity root");
        }
        if (finalDensity.type() != ValueType.FP32 && finalDensity.type() != ValueType.FP64) {
            throw new IllegalArgumentException("A Vulkan stage plan requires an FP32 or FP64 finalDensity root");
        }
        var stages = new ArrayList<StageLayout>(); int offset = 0;
        for (String name : List.of("density", "aquifer", "ore", "material", "metadata")) {
            var stage = new StageLayout(name, offset, offset, elementCount, name.equals("material") ? 4 : 8);
            stages.add(stage); offset = Math.addExact(offset, stage.outputBytes());
        }
        return List.copyOf(stages);
    }
}
