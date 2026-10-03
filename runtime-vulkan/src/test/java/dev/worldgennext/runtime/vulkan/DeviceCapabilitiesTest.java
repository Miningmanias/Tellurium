// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DeviceCapabilitiesTest {
    private static DeviceCapabilities gpu(int type) { return new DeviceCapabilities("fixture", (1 << 22) | (2 << 12), 7, type, 0, true, true, true, true); }

    @Test void rejectsCpuEvenWithEveryFeature() {
        assertFalse(gpu(4).supported());
        assertThrows(IllegalStateException.class, () -> DeviceCapabilities.select(List.of(gpu(4))));
    }
    @Test void rejectsUnknownDeviceClass() { assertFalse(gpu(0).supported()); }
    @Test void capabilityQualifierRejectsCpuForIntegerCarrierProfile() {
        var decision = new dev.worldgennext.runtime.vulkan.production.CapabilityQualifier()
                .qualify(gpu(4), dev.worldgennext.semantic.program.NumericProfile.GPU_IEEE_BITS);
        assertFalse(decision.supported());
        assertTrue(decision.reason().contains("physical GPU"));
    }
    @Test void normalRangeDiagnosticDoesNotQualifyStrictFp64() {
        var device = new DeviceCapabilities("normal-only", (1 << 22) | (2 << 12), 1, 2, 0, true, true, false, true);
        assertFalse(device.supported());
        assertTrue(device.supported(Fp64Profile.NORMAL_RANGE_DIAGNOSTIC));
        assertThrows(IllegalStateException.class, () -> DeviceCapabilities.select(List.of(device)));
        assertEquals(0, DeviceCapabilities.select(List.of(device), Fp64Profile.NORMAL_RANGE_DIAGNOSTIC));
        assertFalse(new GpuSmokeResult(device, 1, 1, 0, "shader", "spirv").passed());
        assertTrue(new GpuSmokeResult(device, 1, 1, 0, "shader", "spirv", Fp64Profile.NORMAL_RANGE_DIAGNOSTIC).passed());
        assertFalse(gpu(4).supported(Fp64Profile.NORMAL_RANGE_DIAGNOSTIC));
    }
    @Test void prefersDiscreteAndKeepsFirstTie() {
        assertEquals(1, DeviceCapabilities.select(List.of(gpu(1), gpu(2), gpu(2))));
        assertEquals(1, DeviceCapabilities.select(List.of(gpu(4), gpu(1))));
    }
    @Test void eachMissingCapabilityFailsClosed() {
        for (int missing = 0; missing < 6; missing++) {
            var candidate = new DeviceCapabilities("fixture", missing == 0 ? 1 << 22 : (1 << 22) | (2 << 12), 0, 2,
                    missing == 1 ? -1 : 0, missing != 2, missing != 3, missing != 4, missing != 5);
            assertFalse(candidate.supported(), "missing index " + missing);
            assertEquals(1, candidate.unsupportedReasons().size());
        }
    }
    @Test void missingOrEmptyDevicesCannotPass() {
        assertThrows(IllegalStateException.class, () -> DeviceCapabilities.select(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DeviceCapabilities("", 0, 0, 0, 0, false, false, false, false));
    }
    @Test void resultRequiresFullCoverageAndNoMismatches() {
        assertTrue(new GpuSmokeResult(gpu(2), 65, 65, 0, "shader", "spirv").passed());
        assertFalse(new GpuSmokeResult(gpu(2), 65, 64, 0, "shader", "spirv").passed());
        assertFalse(new GpuSmokeResult(gpu(2), 65, 65, 1, "shader", "spirv").passed());
        assertFalse(new GpuSmokeResult(gpu(4), 65, 65, 0, "shader", "spirv").passed());
        assertThrows(IllegalArgumentException.class, () -> new GpuSmokeResult(gpu(2), 0, 0, 0, "shader", "spirv"));
        assertThrows(IllegalArgumentException.class, () -> new GpuSmokeResult(gpu(2), 1, 2, 0, "shader", "spirv"));
    }
}
