// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.fast;

import dev.worldgennext.semantic.program.ProgramNode;
import dev.worldgennext.semantic.program.WorldgenProgram;
import dev.worldgennext.semantic.snapshot.WorldgenSnapshot;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Developer diagnostic: shape of a captured router (DAG sizes, node kinds, marker modes). */
public final class GraphStatistics {
    private GraphStatistics() {}

    public static String describe(WorldgenSnapshot snapshot) {
        StringBuilder out = new StringBuilder();
        var roots = snapshot.router().roots();
        Set<ProgramNode> all = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<String, Integer> kinds = new TreeMap<>();
        for (var entry : roots.entrySet()) {
            Set<ProgramNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            var stack = new ArrayDeque<ProgramNode>();
            stack.push(entry.getValue());
            while (!stack.isEmpty()) {
                ProgramNode node = stack.pop();
                if (!seen.add(node)) continue;
                for (ProgramNode child : node.children()) stack.push(child);
            }
            Set<String> fingerprints = new HashSet<>();
            for (ProgramNode node : seen) fingerprints.add(WorldgenProgram.nodeFingerprint(node));
            out.append(String.format("root %-34s identityNodes=%5d semanticNodes=%5d%n",
                    entry.getKey(), seen.size(), fingerprints.size()));
            all.addAll(seen);
        }
        Set<String> allFingerprints = new HashSet<>();
        for (ProgramNode node : all) {
            allFingerprints.add(WorldgenProgram.nodeFingerprint(node));
            String kind = node.getClass().getSimpleName();
            if (node instanceof ProgramNode.Marker marker) kind += ":" + marker.marker() + ":" + marker.cacheMode();
            else if (node instanceof ProgramNode.Unary || node instanceof ProgramNode.Binary || node instanceof ProgramNode.Ap2
                    || node instanceof ProgramNode.Select || node instanceof ProgramNode.Input) kind += ":" + node.operation();
            kinds.merge(kind + ":" + node.type() + ":" + node.domain(), 1, Integer::sum);
        }
        out.append("all identityNodes=").append(all.size()).append(" semanticNodes=").append(allFingerprints.size()).append('\n');
        kinds.forEach((k, v) -> out.append(String.format("  %6d %s%n", v, k)));
        // Nesting patterns that the fused route must treat explicitly.
        int interpolatedInInterpolated = 0, interpolatedInFlat = 0, flatInInterpolated = 0;
        for (ProgramNode node : all) {
            boolean isInterp = node instanceof ProgramNode.Interpolated;
            boolean isFlat = node instanceof ProgramNode.Marker m && m.cacheMode().toUpperCase().contains("FLAT");
            if (!isInterp && !isFlat) continue;
            Set<ProgramNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            var stack = new ArrayDeque<ProgramNode>(node.children());
            while (!stack.isEmpty()) {
                ProgramNode child = stack.pop();
                if (!seen.add(child)) continue;
                if (child instanceof ProgramNode.Interpolated) {
                    if (isInterp) interpolatedInInterpolated++;
                    else interpolatedInFlat++;
                }
                if (isInterp && child instanceof ProgramNode.Marker m && m.cacheMode().toUpperCase().contains("FLAT")) flatInInterpolated++;
                stack.addAll(child.children());
            }
        }
        out.append("interpolatedInInterpolated=").append(interpolatedInInterpolated)
                .append(" interpolatedInFlat=").append(interpolatedInFlat)
                .append(" flatInInterpolated=").append(flatInInterpolated).append('\n');
        var settings = snapshot.generatorSettings();
        out.append("settings minY=").append(settings.minY()).append(" height=").append(settings.height())
                .append(" logicalHeight=").append(settings.logicalHeight()).append(" sea=").append(settings.seaLevel())
                .append(" cell=").append(settings.cellWidth()).append('x').append(settings.cellHeight())
                .append(" aquifers=").append(settings.aquifersEnabled()).append(" ores=").append(settings.oresEnabled())
                .append(" defaultBlock=").append(settings.defaultBlock().canonical())
                .append(" defaultFluid=").append(settings.defaultFluid().canonical()).append('\n');
        var random = snapshot.randomState();
        out.append("aquiferRandom=").append(random.aquiferRandom()).append(" oreRandom=").append(random.oreRandom()).append('\n');
        return out.toString();
    }
}
