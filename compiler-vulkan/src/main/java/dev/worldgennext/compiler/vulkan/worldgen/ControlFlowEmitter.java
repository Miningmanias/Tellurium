// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan.worldgen;

import dev.worldgennext.semantic.program.ProgramNode;

/** Emits explicit ordered GLSL control regions. */
public final class ControlFlowEmitter {
    public String emitSelect(String selector, String whenTrue, String whenFalse) {
        return "if (" + selector + ") {\n    " + whenTrue + "\n} else {\n    " + whenFalse + "\n}";
    }
    public String describe(ProgramNode node) { return node.operation() + ":" + node.domain() + ":" + node.type(); }
}
