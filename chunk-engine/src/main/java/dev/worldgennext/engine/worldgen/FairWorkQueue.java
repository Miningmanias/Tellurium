// SPDX-License-Identifier: MIT
package dev.worldgennext.engine.worldgen;

import java.util.ArrayList;
import java.util.List;

/** Bounded priority FIFO with deterministic sequence tie-breaking and ageing. */
public final class FairWorkQueue {
    private record Item(GenerationRequest request, long sequence, long enqueuedNanos) {}
    private final int capacity;
    private static final long AGE_QUANTUM_NANOS = 100_000_000L;
    private final List<Item> items = new ArrayList<>();
    private long sequence;
    public FairWorkQueue(int capacity) { if (capacity <= 0) throw new IllegalArgumentException("Queue capacity must be positive"); this.capacity = capacity; }
    public synchronized boolean offer(GenerationRequest request) { if (items.size() >= capacity) return false; items.add(new Item(request, sequence++, System.nanoTime())); return true; }
    public synchronized boolean remove(GenerationRequest request) {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).request().equals(request)) {
                items.remove(index);
                return true;
            }
        }
        return false;
    }
    public synchronized GenerationRequest poll() {
        if (items.isEmpty()) return null;
        long now = System.nanoTime();
        int selectedIndex = 0;
        Item selected = items.getFirst();
        for (int index = 1; index < items.size(); index++) {
            Item candidate = items.get(index);
            long candidatePriority = effectivePriority(candidate, now);
            long selectedPriority = effectivePriority(selected, now);
            if (candidatePriority > selectedPriority
                    || candidatePriority == selectedPriority && candidate.sequence() < selected.sequence()) {
                selected = candidate;
                selectedIndex = index;
            }
        }
        items.remove(selectedIndex);
        return selected.request();
    }
    public synchronized int size() { return items.size(); }
    public int capacity() { return capacity; }
    public synchronized boolean isEmpty() { return items.isEmpty(); }
    private static long effectivePriority(Item item, long now) {
        long age = Math.max(0L, now - item.enqueuedNanos()) / AGE_QUANTUM_NANOS;
        return Math.min(Integer.MAX_VALUE, (long) item.request().priority() + age);
    }
}
