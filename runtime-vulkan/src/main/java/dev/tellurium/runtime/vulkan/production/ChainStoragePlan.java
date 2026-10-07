// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

/** Bounded high-water capacities shared by ordinary dispatches and resident chains. */
record ChainStoragePlan(long[] capacities) {
    ChainStoragePlan {
        capacities = capacities.clone();
    }

    @Override
    public long[] capacities() { return capacities.clone(); }

    static ChainStoragePlan fit(long[] requested, long[] previous, long availableBudget) {
        if (requested == null || previous == null || requested.length < 2 || availableBudget <= 0) {
            throw new IllegalArgumentException("Invalid Vulkan chain-storage geometry or budget");
        }
        long requestedBytes = 0;
        for (long bytes : requested) {
            if (bytes <= 0 || bytes > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid requested Vulkan chain buffer capacity");
            }
            requestedBytes = Math.addExact(requestedBytes, bytes);
        }
        if (requestedBytes > availableBudget) {
            throw new IllegalArgumentException("Vulkan chain-storage budget exceeded: requested="
                    + requestedBytes + ", available=" + availableBudget);
        }
        long[] capacities = new long[Math.max(requested.length, previous.length)];
        long retainedBytes = 0;
        for (int index = 0; index < capacities.length; index++) {
            long old = index < previous.length ? previous[index] : 0;
            if (old < 0 || old > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid previous Vulkan chain buffer capacity");
            }
            capacities[index] = Math.max(old, index < requested.length ? requested[index] : 0);
            retainedBytes = Math.addExact(retainedBytes, capacities[index]);
        }
        // Including unused chain slots prevents a two-buffer request from
        // hiding a retained third buffer outside its native admission ceiling.
        return new ChainStoragePlan(retainedBytes <= availableBudget ? capacities : requested);
    }
}
