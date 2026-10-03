// SPDX-License-Identifier: MIT
package dev.worldgennext.frontend.mc1211;

import dev.worldgennext.semantic.program.ProgramNode;
import dev.worldgennext.semantic.snapshot.NoiseRouterSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class NoiseRouterLowerer {
    public record Result(NoiseRouterSnapshot snapshot, LoweringDiagnostics diagnostics) { public boolean supported() { return snapshot != null && !diagnostics.hasErrors(); } }
    private final DensityNodeLowerer nodeLowerer = new DensityNodeLowerer();
    public Result lower(Map<String, SourceNodeSnapshot> roots) {
        var diagnostics = new LoweringDiagnostics(); var output = new LinkedHashMap<String, ProgramNode>();
        if (roots == null) { diagnostics.error("router", "MISSING_ROUTER", "router roots are missing"); return new Result(null, diagnostics); }
        for (String name : roots.keySet()) {
            if (!Set.copyOf(NoiseRouterSnapshot.ROOT_NAMES).contains(name)) {
                diagnostics.error(name == null || name.isBlank() ? "router" : name,
                        "UNKNOWN_ROOT", "router root is not part of the pinned 1.21.1 NoiseRouter contract");
            }
        }
        for (String name : NoiseRouterSnapshot.ROOT_NAMES) { var source = roots.get(name); if (source == null) { diagnostics.error(name, "MISSING_ROOT", "required router root is missing"); continue; } var lowered = nodeLowerer.lower(source); if (lowered.supported()) output.put(name, lowered.node()); else diagnostics.error(name, "ROOT_UNSUPPORTED", lowered.diagnostics().summary()); }
        return new Result(new NoiseRouterSnapshot(output), diagnostics);
    }
}
