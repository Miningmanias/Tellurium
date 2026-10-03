// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan;

import java.util.List;

/** A native-independent snapshot: inspecting this record never loads Vulkan. */
public record DeviceCapabilities(String name, int apiVersion, int driverVersion,
                                 int deviceType, int computeQueueFamily,
                                 boolean float64, boolean preserveSignedZeroInfNan64,
                                 boolean preserveDenorm64, boolean roundingRte64) {
    public DeviceCapabilities {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Device name required");
    }

    public List<String> unsupportedReasons() {
        return unsupportedReasons(Fp64Profile.STRICT);
    }

    public List<String> unsupportedReasons(Fp64Profile profile) {
        java.util.Objects.requireNonNull(profile, "profile");
        var reasons = new java.util.ArrayList<String>();
        if (deviceType != 1 && deviceType != 2 && deviceType != 3) reasons.add("Physical GPU required; CPU/unknown Vulkan device rejected");
        if (Integer.compareUnsigned(apiVersion, (1 << 22) | (2 << 12)) < 0) reasons.add("Vulkan 1.2 required");
        if (computeQueueFamily < 0) reasons.add("No compute queue");
        if (!float64) reasons.add("shaderFloat64 unavailable");
        if (!preserveSignedZeroInfNan64) reasons.add("FP64 signed-zero/Inf/NaN preservation unavailable");
        if (profile == Fp64Profile.STRICT && !preserveDenorm64) reasons.add("FP64 denorm preservation unavailable");
        if (!roundingRte64) reasons.add("FP64 round-to-nearest-even unavailable");
        return List.copyOf(reasons);
    }

    public boolean supported() { return unsupportedReasons().isEmpty(); }
    public boolean supported(Fp64Profile profile) { return unsupportedReasons(profile).isEmpty(); }

    /** Prefer discrete then integrated devices; preserves enumeration order within a class. */
    public static int select(List<DeviceCapabilities> candidates) {
        return select(candidates, Fp64Profile.STRICT);
    }

    public static int select(List<DeviceCapabilities> candidates, Fp64Profile profile) {
        int selected = -1;
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).supported(profile) && (selected < 0 || score(candidates.get(i)) > score(candidates.get(selected)))) selected = i;
        }
        if (selected < 0) throw new IllegalStateException("No qualified Vulkan device: " + candidates);
        return selected;
    }

    private static int score(DeviceCapabilities value) {
        return value.deviceType == 2 ? 3 : value.deviceType == 1 ? 2 : 1;
    }
}
