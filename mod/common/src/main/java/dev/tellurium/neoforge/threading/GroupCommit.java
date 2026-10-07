// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.threading;

import java.io.IOException;

/**
 * Group commit for synchronous chunk writes.
 *
 * <p>With {@code sync-chunk-writes} on, vanilla opens every region file with
 * DSYNC: each chunk write (and each header write) returns only once it is on
 * the disk, and the save's future completes right after.  That is one device
 * flush per chunk, and at a few thousand saves per second the IO worker spends
 * its time in them.</p>
 *
 * <p>Here the file is opened without DSYNC.  The IO worker writes a batch of
 * chunks, forces every file it touched to the disk once, and only then
 * completes the futures of the saves in that batch.  A save still is not
 * reported done before its data and the header that points at it are durable;
 * what changes is that several saves share one flush.  A batch ends when the
 * worker has nothing left to write, after {@link #MAX_WRITES} saves, or
 * {@link #MAX_NANOS} after its first save, and before the worker closes.</p>
 *
 * <p>Nothing changes when synchronous writes are off.</p>
 */
public final class GroupCommit {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellurium.asyncGroupCommit", "true"));
    public static final int MAX_WRITES = Math.max(1, Integer.getInteger("tellurium.asyncGroupCommitWrites", 256));
    public static final long MAX_NANOS = 50_000_000L;

    /** Implemented by RegionFile. */
    public interface File {
        /** Writes a deferred header and forces the file if anything was written since the last force. */
        void tellurium$commit() throws IOException;
    }

    private GroupCommit() {}
}
