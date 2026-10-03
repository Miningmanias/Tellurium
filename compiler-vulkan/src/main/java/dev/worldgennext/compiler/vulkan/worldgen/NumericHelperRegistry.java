// SPDX-License-Identifier: MIT
package dev.worldgennext.compiler.vulkan.worldgen;

import dev.worldgennext.semantic.program.NumericProfile;
import java.util.LinkedHashMap;
import java.util.Map;

public final class NumericHelperRegistry {
    private final Map<String, String> helpers = new LinkedHashMap<>();
    public NumericHelperRegistry(NumericProfile profile) { if (profile == NumericProfile.GPU_IEEE_BITS) helpers.put("fp32", IntegerIeeeEmitter.source()); }
    public void register(String name, String source) { if (name == null || name.isBlank() || source == null || source.isBlank()) throw new IllegalArgumentException("Invalid helper"); helpers.put(name, source); }
    public Map<String, String> helpers() { return Map.copyOf(helpers); }
    public String source() { return String.join("\n", helpers.values()); }
}
