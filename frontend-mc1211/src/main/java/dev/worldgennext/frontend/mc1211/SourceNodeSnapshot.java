// SPDX-License-Identifier: MIT
package dev.worldgennext.frontend.mc1211;

import dev.worldgennext.semantic.program.EvaluationDomain;
import dev.worldgennext.semantic.program.ProgramNode;
import dev.worldgennext.semantic.program.ValueType;
import dev.worldgennext.semantic.snapshot.NoiseParameters;
import dev.worldgennext.semantic.snapshot.BlendedNoiseParameters;
import dev.worldgennext.semantic.snapshot.EndIslandParameters;
import java.util.List;
import java.util.Map;

/** Pure captured source node. It contains no mapped Minecraft object or reflection handle. */
public record SourceNodeSnapshot(String kind, String sourcePath, ValueType type, EvaluationDomain domain,
                                 Map<String, String> parameters, List<SourceNodeSnapshot> children,
                                 NoiseParameters capturedNoise, ProgramNode.SplineNode capturedSpline,
                                 BlendedNoiseParameters capturedBlendedNoise,
                                 EndIslandParameters capturedEndIsland) {
    public SourceNodeSnapshot(String kind, String sourcePath, ValueType type, EvaluationDomain domain,
                              Map<String, String> parameters, List<SourceNodeSnapshot> children) {
        this(kind, sourcePath, type, domain, parameters, children, null, null, null, null);
    }
    public SourceNodeSnapshot(String kind, String sourcePath, ValueType type, EvaluationDomain domain,
                              Map<String, String> parameters, List<SourceNodeSnapshot> children,
                              NoiseParameters capturedNoise) {
        this(kind, sourcePath, type, domain, parameters, children, capturedNoise, null, null, null);
    }
    public SourceNodeSnapshot(String kind, String sourcePath, ValueType type, EvaluationDomain domain,
                              Map<String, String> parameters, List<SourceNodeSnapshot> children,
                              NoiseParameters capturedNoise, ProgramNode.SplineNode capturedSpline) {
        this(kind, sourcePath, type, domain, parameters, children, capturedNoise, capturedSpline, null, null);
    }
    public SourceNodeSnapshot(String kind, String sourcePath, ValueType type, EvaluationDomain domain,
                              Map<String, String> parameters, List<SourceNodeSnapshot> children,
                              NoiseParameters capturedNoise, ProgramNode.SplineNode capturedSpline,
                              BlendedNoiseParameters capturedBlendedNoise) {
        this(kind, sourcePath, type, domain, parameters, children, capturedNoise, capturedSpline, capturedBlendedNoise, null);
    }
    public SourceNodeSnapshot {
        if (kind == null || kind.isBlank() || sourcePath == null || sourcePath.isBlank()) throw new IllegalArgumentException("Source node identity required");
        if (type == null || domain == null) throw new NullPointerException("type/domain");
        parameters = Map.copyOf(parameters == null ? Map.of() : parameters); children = List.copyOf(children == null ? List.of() : children);
    }
    public static SourceNodeSnapshot constant(String path, ValueType type, String value) { return new SourceNodeSnapshot("constant", path, type, EvaluationDomain.WORLD, Map.of("value", value), List.of()); }
}
