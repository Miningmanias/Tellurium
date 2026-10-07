// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.threading;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Threads for the chunk system's scheduling mailboxes ("worldgen" and
 * "sorter"), so that their messages do not queue behind generation steps on
 * the worldgen worker pool.  A mailbox runs on at most one thread at a time
 * (its own scheduled flag guarantees that), so a cached pool gives each active
 * mailbox a thread and lets idle ones go.
 */
public final class MailboxThreads {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellurium.parallelMailboxThreads", "true"));
    /** Messages a scheduling mailbox handles per dispatch. */
    public static final int BATCH = Math.max(1, Integer.getInteger("tellurium.parallelMailboxBatch", 64));

    private static final AtomicInteger IDS = new AtomicInteger();
    private static final ExecutorService POOL = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 30L, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "tellurium-mailbox-" + IDS.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });

    private MailboxThreads() {}

    public static void execute(Runnable mailbox) {
        POOL.execute(mailbox);
    }
}
