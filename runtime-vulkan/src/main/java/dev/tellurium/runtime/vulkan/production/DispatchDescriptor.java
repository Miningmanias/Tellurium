// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

public record DispatchDescriptor(String pipelineKey, int elementCount, int localSize, long inputBytes, long outputBytes) {
    public DispatchDescriptor { if (pipelineKey == null || pipelineKey.isBlank() || elementCount <= 0 || localSize <= 0 || inputBytes < 0 || outputBytes < 0) throw new IllegalArgumentException("Invalid dispatch"); }
    public int workgroups() {
        return (int) (((long) elementCount + localSize - 1L) / localSize);
    }
}
