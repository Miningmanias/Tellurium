// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan.production;

import java.util.concurrent.Callable;

/** Single external queue owner; callers cannot submit concurrently through this seam. */
public final class QueueOwner {
    private final Object lock = new Object();
    public <T> T submit(Callable<T> operation) throws Exception { synchronized (lock) { return operation.call(); } }
    public void submit(Runnable operation) { synchronized (lock) { operation.run(); } }
}
