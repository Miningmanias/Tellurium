// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.jvm.worldgen;

import dev.tellurium.semantic.program.MarkerContext;
import dev.tellurium.semantic.program.WorldgenProgram;
import java.util.Map;
import java.util.Objects;

/** Prebound typed program; it does not claim bytecode generation. */
public final class CompiledWorldgenProgram {
    private final WorldgenProgram program;
    private final String root;
    private final WorldgenInterpreter interpreter;
    CompiledWorldgenProgram(WorldgenProgram program, String root) { this.program = Objects.requireNonNull(program); this.root = Objects.requireNonNull(root); interpreter = new WorldgenInterpreter(); }
    public WorldgenProgram program() { return program; }
    public String root() { return root; }
    public Object evaluate(Map<String, Object> inputs, MarkerContext markers) { return interpreter.evaluate(program, root, inputs, markers); }
    public double evaluateDouble(Map<String, Object> inputs, MarkerContext markers) { return interpreter.evaluateDouble(program, root, inputs, markers); }
    public double evaluateAt(int x, int y, int z, MarkerContext markers) { return interpreter.evaluateAt(program, root, x, y, z, markers); }
    public double evaluateAt(long worldSeed, int x, int y, int z, MarkerContext markers) { return interpreter.evaluateAt(program, root, worldSeed, x, y, z, markers); }
    public double evaluateAtCaptured(long worldSeed, int x, int y, int z, MarkerContext markers) {
        return interpreter.evaluateAtCaptured(program, root, worldSeed, x, y, z, markers);
    }
    public double evaluateAtCapturedDirect(long worldSeed, int x, int y, int z, MarkerContext markers) {
        return interpreter.evaluateAtCapturedDirect(program, root, worldSeed, x, y, z, markers);
    }
}
