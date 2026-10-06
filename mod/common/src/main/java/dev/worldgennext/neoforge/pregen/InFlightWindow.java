// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.pregen;

/** How many chunks the pregenerator asks for at once. */
final class InFlightWindow {
    private InFlightWindow() {}

    /**
     * The configured window, reduced on small heaps.  Every chunk in flight keeps its neighbourhood loaded;
     * measured on the reference host, 1,024 in flight held 4,000-6,000 chunks and 4-8 GB.  The rule here
     * allows one chunk in flight per 12 MB of heap (not fewer than 32), which leaves a 16 GB heap at the
     * configured default.  It is a rule of thumb, not a measured limit.
     */
    static int size(int configured, long maxHeapBytes) {
        long byHeap = (maxHeapBytes >> 20) / 12;
        return (int) Math.min(configured, Math.max(32, byHeap));
    }
}
