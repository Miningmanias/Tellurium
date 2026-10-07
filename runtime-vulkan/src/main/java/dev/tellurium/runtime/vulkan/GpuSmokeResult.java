// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

public record GpuSmokeResult(DeviceCapabilities device, int submittedSamples, int comparedSamples,
                             int mismatches, String shaderSha256, String spirvSha256, Fp64Profile profile) {
    public GpuSmokeResult(DeviceCapabilities device, int submittedSamples, int comparedSamples,
                          int mismatches, String shaderSha256, String spirvSha256) {
        this(device, submittedSamples, comparedSamples, mismatches, shaderSha256, spirvSha256, Fp64Profile.STRICT);
    }
    public GpuSmokeResult {
        if (device == null || shaderSha256 == null || spirvSha256 == null || profile == null) throw new IllegalArgumentException("Missing smoke evidence");
        if (submittedSamples < 1 || comparedSamples < 0 || comparedSamples > submittedSamples || mismatches < 0 || mismatches > comparedSamples)
            throw new IllegalArgumentException("Invalid comparison counts");
    }
    public boolean passed() { return device.supported(profile) && comparedSamples == submittedSamples && mismatches == 0; }
}
