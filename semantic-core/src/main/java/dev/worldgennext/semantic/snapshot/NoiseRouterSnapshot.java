// SPDX-License-Identifier: MIT
package dev.worldgennext.semantic.snapshot;

import dev.worldgennext.semantic.program.ProgramNode;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Exactly named router roots; missing roots remain an explicit lowering error. */
public final class NoiseRouterSnapshot {
    public static final java.util.List<String> ROOT_NAMES = dev.worldgennext.semantic.program.WorldgenProgram.ROUTER_ROOTS;
    private final Map<String, ProgramNode> roots;
    public NoiseRouterSnapshot(Map<String, ProgramNode> roots) {
        var copy = new TreeMap<String, ProgramNode>();
        if (roots != null) roots.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) throw new IllegalArgumentException("Invalid router root");
            copy.put(key, value);
        });
        this.roots = Collections.unmodifiableMap(copy);
    }
    public Map<String, ProgramNode> roots() { return roots; }
    public ProgramNode root(String name) { return roots.get(name); }
    /**
     * A captured router is a closed contract, not a bag of roots.  Treat an
     * otherwise complete map with an unknown extra root as incomplete so a
     * loader cannot silently drop a newly introduced Minecraft root while
     * still admitting the snapshot to a candidate route.
     */
    public boolean complete() { return roots.keySet().equals(Set.copyOf(ROOT_NAMES)); }
    public static NoiseRouterSnapshot empty() { return new NoiseRouterSnapshot(Map.of()); }
}
