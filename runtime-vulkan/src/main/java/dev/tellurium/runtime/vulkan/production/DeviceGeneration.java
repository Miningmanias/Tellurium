// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

public record DeviceGeneration(long value) {
    public DeviceGeneration { if (value < 0) throw new IllegalArgumentException("Negative device generation"); }
    public DeviceGeneration next() { return new DeviceGeneration(Math.addExact(value, 1)); }
}
