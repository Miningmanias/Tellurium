// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan.production;

import dev.worldgennext.runtime.vulkan.DeviceCapabilities;
import dev.worldgennext.semantic.execution.CapabilityDecision;
import dev.worldgennext.semantic.program.NumericProfile;
import java.util.ArrayList;
import java.util.Objects;

/** Qualification distinguishes integer-carrier execution from the v0.1 native FP64 smoke. */
public final class CapabilityQualifier {
    public CapabilityDecision qualify(DeviceCapabilities capabilities, NumericProfile profile) {
        Objects.requireNonNull(profile, "profile");
        var reasons = new ArrayList<String>();
        if (capabilities == null) reasons.add("missing device capabilities");
        else {
            if (capabilities.computeQueueFamily() < 0) reasons.add("no compute queue");
            if (Integer.compareUnsigned(capabilities.apiVersion(), (1 << 22) | (2 << 12)) < 0) reasons.add("Vulkan 1.2 required");
            if (capabilities.deviceType() != 1 && capabilities.deviceType() != 2 && capabilities.deviceType() != 3) reasons.add("physical GPU required");
            if (profile != NumericProfile.GPU_IEEE_BITS && !capabilities.supported()) reasons.addAll(capabilities.unsupportedReasons());
        }
        return reasons.isEmpty() ? CapabilityDecision.supported("VULKAN_" + profile.name(), profile)
                : CapabilityDecision.unsupported("VULKAN_" + profile.name(), String.join("; ", reasons), profile);
    }
}
