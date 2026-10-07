// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.threading;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Region files whose 8 KiB header in memory is newer than the one on disk.
 *
 * <p>RegionFile.write rewrites the whole header after every chunk.  At
 * generation rates that second write per chunk is a third of the IO thread's
 * work.  The header is instead written after at most {@link #MAX_WRITES}
 * chunk writes or {@link #MAX_NANOS} since the first deferred one, when the
 * IO worker runs out of pending writes, and before flush and close.  Chunk
 * data is on disk as before; a header that is behind only matters if the
 * process dies inside that window, in which case the chunks written in it
 * are not referenced and are generated again.  Reads use the in-memory
 * header and are unaffected.</p>
 */
public final class DeferredRegionHeaders {
    public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tellurium.regionHeaderBatch", "true"));
    public static final int MAX_WRITES = 64;
    public static final long MAX_NANOS = 50_000_000L;

    public interface Holder {
        void tellurium$flushHeader() throws IOException;
    }

    private static final Set<Holder> DIRTY = ConcurrentHashMap.newKeySet();

    private DeferredRegionHeaders() {}

    public static void markDirty(Holder file) { DIRTY.add(file); }

    public static void clean(Holder file) { DIRTY.remove(file); }

    public static void flushAll() {
        if (DIRTY.isEmpty()) return;
        for (Holder file : DIRTY.toArray(Holder[]::new)) {
            try {
                file.tellurium$flushHeader();
            } catch (IOException failure) {
                // the next write to this file, or its flush/close, tries again and reports
            }
        }
    }
}
