// SPDX-License-Identifier: MIT
package dev.tellurium.engine;

/** Chooses from supported exact routes using caller-supplied finish-time estimates. */
public final class RoutingPolicy {
    private RoutingPolicy() {}
    public record Estimate(long cpuNanos, long gpuQueueNanos, long gpuExecutionNanos, long gpuMaterializationNanos) {
        public Estimate {
            if (cpuNanos < 0 || gpuQueueNanos < 0 || gpuExecutionNanos < 0 || gpuMaterializationNanos < 0)
                throw new IllegalArgumentException("Negative service estimate");
        }
        public long gpuFinishNanos() { return saturatedAdd(saturatedAdd(gpuQueueNanos, gpuExecutionNanos), gpuMaterializationNanos); }
    }
    public static ExecutionRoute choose(Estimate estimate, boolean cpuSupported, boolean gpuSupported, boolean gpuRequired) {
        java.util.Objects.requireNonNull(estimate, "estimate");
        if (gpuRequired) {
            if (!gpuSupported) throw new UnsupportedOperationException("GPU required but unsupported");
            return ExecutionRoute.GPU;
        }
        if (!cpuSupported && !gpuSupported) throw new UnsupportedOperationException("No supported exact route");
        if (!gpuSupported) return ExecutionRoute.CPU_PLANNED;
        if (!cpuSupported) return ExecutionRoute.GPU;
        return estimate.cpuNanos() <= estimate.gpuFinishNanos() ? ExecutionRoute.CPU_PLANNED : ExecutionRoute.GPU;
    }
    private static long saturatedAdd(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
}
