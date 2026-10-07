// SPDX-License-Identifier: MIT
package dev.tellurium.compiler.jvm.worldgen;

import dev.tellurium.semantic.program.WorldgenProgram;
import java.util.Objects;

/** Compiles an immutable typed program by prebinding one root to a scalar interpreter. */
public final class WorldgenCpuCompiler {
    public CompiledWorldgenProgram compile(WorldgenProgram program, String root) {
        Objects.requireNonNull(program, "program");
        if (program.root(root) == null) throw new IllegalArgumentException("Unknown root: " + root);
        return new CompiledWorldgenProgram(program, root);
    }
}
