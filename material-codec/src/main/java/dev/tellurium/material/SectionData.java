// SPDX-License-Identifier: MIT
package dev.tellurium.material;

public interface SectionData {
    int BLOCK_COUNT = 4096;
    int blockStateId(int index);
    int nonAirCount();
    long logicalChecksum();

    /**
     * The registry-local state ID that represents air for this section.  The original
     * fixture codec uses zero; chunk-result sections may carry a different explicit ID.
     */
    default int airStateId() { return 0; }
}
