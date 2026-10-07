// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

import java.util.ArrayDeque;
import java.util.Optional;

public final class SubmissionRing<T> {
    private final int capacity; private final ArrayDeque<T> entries = new ArrayDeque<>();
    public SubmissionRing(int capacity) { if (capacity <= 0) throw new IllegalArgumentException("Submission capacity must be positive"); this.capacity = capacity; }
    public synchronized boolean offer(T value) { if (entries.size() >= capacity) return false; entries.addLast(java.util.Objects.requireNonNull(value)); return true; }
    public synchronized Optional<T> poll() { return Optional.ofNullable(entries.pollFirst()); }
    public synchronized int size() { return entries.size(); }
    public int capacity() { return capacity; }
}
