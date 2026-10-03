// SPDX-License-Identifier: MIT
package dev.worldgennext.engine.worldgen;

import dev.worldgennext.engine.ExecutionRoute;
import java.util.Objects;

/** Fixed pre-admission route policy; it never learns from a failed result. */
public final class StaticRoutePolicy {
    public enum Mode { CPU_ONLY, GPU_REQUIRED, AUTO_SUPPORTED }
    private final Mode mode;
    public StaticRoutePolicy(Mode mode) { this.mode = Objects.requireNonNull(mode); }
    public Mode mode() { return mode; }
    public ExecutionRoute choose(boolean cpuSupported, boolean gpuSupported) {
        return switch (mode) {
            case CPU_ONLY -> { if (!cpuSupported) throw new UnsupportedOperationException("CPU_ONLY route is unsupported"); yield ExecutionRoute.CPU_PLANNED; }
            case GPU_REQUIRED -> { if (!gpuSupported) throw new UnsupportedOperationException("GPU_REQUIRED route is unsupported"); yield ExecutionRoute.GPU; }
            case AUTO_SUPPORTED -> { if (gpuSupported) yield ExecutionRoute.GPU; if (cpuSupported) yield ExecutionRoute.CPU_PLANNED; throw new UnsupportedOperationException("No exact route is supported"); }
        };
    }
}
