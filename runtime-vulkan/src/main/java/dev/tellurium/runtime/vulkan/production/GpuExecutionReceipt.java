// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

import dev.tellurium.semantic.execution.ExecutionReceipt;

public record GpuExecutionReceipt(ExecutionReceipt execution, DispatchDescriptor dispatch, String shaderHash, String spirvHash) {
    public GpuExecutionReceipt {
        if (execution == null || dispatch == null || shaderHash == null || shaderHash.isBlank()
                || spirvHash == null || spirvHash.isBlank()) {
            throw new IllegalArgumentException("Incomplete GPU receipt");
        }
        if (!execution.backend().toLowerCase(java.util.Locale.ROOT).startsWith("gpu")
                || !execution.hasCompiledArtifactProvenance()) {
            throw new IllegalArgumentException("GPU receipt requires a GPU execution with compiled artifacts");
        }
        if (!shaderHash.equals(execution.shaderHash()) || !spirvHash.equals(execution.spirvHash())) {
            throw new IllegalArgumentException("GPU receipt artifact provenance does not match execution receipt");
        }
    }
}
