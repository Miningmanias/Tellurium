// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.jvm.worldgen;

import dev.worldgennext.semantic.program.WorldgenProgram;
import java.util.Objects;

/** Compiles an immutable typed program by prebinding one root to a scalar interpreter. */
public final class WorldgenCpuCompiler {
    public CompiledWorldgenProgram compile(WorldgenProgram program, String root) {
        Objects.requireNonNull(program, "program");
        if (program.root(root) == null) throw new IllegalArgumentException("Unknown root: " + root);
        return new CompiledWorldgenProgram(program, root);
    }
}
