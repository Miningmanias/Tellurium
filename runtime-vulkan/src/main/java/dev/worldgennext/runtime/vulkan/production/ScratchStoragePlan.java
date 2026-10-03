// SPDX-License-Identifier: MIT
package dev.worldgennext.runtime.vulkan.production;

/** Pure bounded-capacity policy for the serialized, fence-completed dispatch slot. */
record ScratchStoragePlan(long inputBytes, long outputBytes) {
    static ScratchStoragePlan fit(long requestedInput, long requestedOutput,
                                  long previousInput, long previousOutput, long availableBudget) {
        if (requestedInput <= 0 || requestedOutput <= 0
                || requestedInput > Integer.MAX_VALUE || requestedOutput > Integer.MAX_VALUE
                || previousInput < 0 || previousOutput < 0
                || previousInput > Integer.MAX_VALUE || previousOutput > Integer.MAX_VALUE
                || availableBudget <= 0) {
            throw new IllegalArgumentException("Invalid Vulkan scratch-storage geometry or budget");
        }
        long requested = Math.addExact(requestedInput, requestedOutput);
        if (requested > availableBudget) {
            throw new IllegalArgumentException("Vulkan scratch-storage budget exceeded: requested="
                    + requested + ", available=" + availableBudget);
        }
        long input = Math.max(requestedInput, previousInput);
        long output = Math.max(requestedOutput, previousOutput);
        // Opposite-shaped requests must not combine their high-water marks
        // into a retained allocation larger than the configured ceiling.
        if (Math.addExact(input, output) > availableBudget) {
            input = requestedInput;
            output = requestedOutput;
        }
        return new ScratchStoragePlan(input, output);
    }
}
