// SPDX-License-Identifier: MIT
package dev.worldgennext.neoforge.threading;

import dev.worldgennext.neoforge.version.Version;

import dev.worldgennext.neoforge.loader.Names;

import com.mojang.serialization.Codec;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.SingleValuePalette;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Deferred palette encoding for chunks that are being unloaded.
 *
 * <p>ChunkSerializer.write runs on the server thread and is dominated by
 * PalettedContainer packing.  While a collector is active on the calling
 * thread, each section's block-state and biome encoding is replaced by a
 * placeholder tag; {@link #resolve} later encodes them on a worker and swaps
 * the real tags in before the chunk tag is handed to the IO worker.
 * Everything else (block entities, light, ticks, structures) is still
 * captured on the server thread.</p>
 */
public final class AsyncSectionEncoding {
    public static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("worldgennext.asyncChunkSave", "true"));
    // The collector is only ever active on the server thread (ChunkMap.save); a plain field with an owner
    // check replaces a ThreadLocal that ChunkSerializer.write would query some fifty times per chunk.
    private static volatile Thread collectorOwner;
    private static Deferred activeCollector;

    /**
     * Encoding and compression run on their own small pool rather than the worldgen pool: unloads arrive
     * in bursts of thousands, and queued behind them generation steps would wait for seconds.
     */
    private static final ThreadPoolExecutor SAVE_POOL = savePool();
    /** Above this many queued saves, further ones go to the worldgen pool, which bounds the backlog. */
    private static final int MAX_QUEUED = Integer.getInteger("worldgennext.asyncChunkSaveQueue", 4096);

    private static ThreadPoolExecutor savePool() {
        int threads = Integer.getInteger("worldgennext.asyncChunkSaveThreads",
                Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        AtomicInteger ids = new AtomicInteger();
        var pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new PriorityBlockingQueue<>(), task -> {
                    Thread thread = new Thread(task, "worldgennext-save-" + ids.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static final AtomicLong SEQUENCE = new AtomicLong();

    public static String status() {
        return "savePool{queued=" + SAVE_POOL.getQueue().size() + ", active=" + SAVE_POOL.getActiveCount()
                + ", completed=" + SAVE_POOL.getCompletedTaskCount() + "}";
    }

    /** One deferred chunk encoding.  Runs once; a chunk that is requested again is moved to the front. */
    public static final class Task implements Runnable, Comparable<Task> {
        private final Runnable work;
        private final long sequence = SEQUENCE.incrementAndGet();
        private volatile boolean urgent;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final CompletableFuture<Void> done = new CompletableFuture<>();

        private Task(Runnable work) { this.work = work; }

        public CompletableFuture<Void> done() { return done; }

        /**
         * Completes once the finished tag was handed to the IO worker (or the encoding failed).  From then
         * on a read of this chunk is answered from the IO worker's pending write, so readers wait for this
         * and not for the physical write, which may sit behind thousands of others.
         */
        public final CompletableFuture<Void> handedOff = new CompletableFuture<>();

        @Override
        public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            try {
                work.run();
                done.complete(null);
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        }

        /** The chunk is being read back while its save is still queued: encode it next. */
        public void expedite() {
            if (claimed.get() || urgent) return;
            if (SAVE_POOL.getQueue().remove(this)) {
                urgent = true;
                SAVE_POOL.execute(this);
            }
        }

        @Override
        public int compareTo(Task other) {
            if (urgent != other.urgent) return urgent ? -1 : 1;
            return Long.compare(sequence, other.sequence);
        }
    }

    /**
     * Queues deferred chunk encoding.  When the save pool's queue is full the task goes to the worldgen
     * pool instead: saving then competes with generation, which bounds the backlog without making the
     * server thread encode.
     */
    public static Task submit(Runnable work) {
        Task task = new Task(work);
        if (SAVE_POOL.getQueue().size() >= MAX_QUEUED) Util.backgroundExecutor().execute(task);
        else SAVE_POOL.execute(task);
        return task;
    }

    /** Placeholders in the order ChunkSerializer.write created them, with their encoders. */
    public static final class Deferred {
        private final List<Tag> placeholders = new ArrayList<>(48);
        private final List<Supplier<Tag>> encoders = new ArrayList<>(48);
        /** Deferred values stored directly in the chunk tag (ticks, post-processing lists, structures). */
        private final List<Tag> topPlaceholders = new ArrayList<>(4);
        private final List<Supplier<Tag>> topEncoders = new ArrayList<>(4);
        public boolean isEmpty() { return placeholders.isEmpty() && topPlaceholders.isEmpty(); }
    }

    private AsyncSectionEncoding() {}

    public static Deferred begin() {
        Deferred collector = new Deferred();
        activeCollector = collector;
        collectorOwner = Thread.currentThread();
        return collector;
    }

    public static void end() {
        collectorOwner = null;
        activeCollector = null;
    }

    /** Non-null only between begin() and end() on the thread that called begin(). */
    public static Deferred collector() {
        return collectorOwner == Thread.currentThread() ? activeCollector : null;
    }

    /** Registers a deferred encoding and returns the placeholder that stands in for it. */
    public static Tag defer(Deferred collector, Supplier<Tag> encoder) {
        CompoundTag placeholder = new CompoundTag();
        collector.placeholders.add(placeholder);
        collector.encoders.add(encoder);
        return placeholder;
    }

    /**
     * Registers a deferred top-level value of the chunk tag.  Only for data that nothing touches once the
     * chunk has left the world: the unload path this is used on has already detached the chunk.
     */
    public static <T extends Tag> T deferTop(Deferred collector, T placeholder, Supplier<Tag> encoder) {
        collector.topPlaceholders.add(placeholder);
        collector.topEncoders.add(encoder);
        return placeholder;
    }

    /** Replaces every placeholder in the chunk tag and its sections; throws if any encoding fails or is not located. */
    public static void resolve(CompoundTag chunkTag, Deferred collector) {
        int top = collector.topPlaceholders.size();
        if (top > 0) {
            int found = 0;
            for (String key : new ArrayList<>(Version.keys(chunkTag))) {
                Tag current = chunkTag.get(key);
                for (int i = 0; i < top; i++) {
                    if (collector.topPlaceholders.get(i) == current) {
                        chunkTag.put(key, collector.topEncoders.get(i).get());
                        found++;
                        break;
                    }
                }
            }
            if (found != top) {
                throw new IllegalStateException("Deferred chunk values were not all located in the chunk tag: " + found + " of " + top);
            }
        }
        ListTag sections = Version.compounds(chunkTag, "sections");
        int total = collector.placeholders.size();
        boolean[] done = new boolean[total];
        int next = 0, resolved = 0;
        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = Version.compoundAt(sections, i);
            for (String key : new String[]{"block_states", "biomes"}) {
                Tag current = section.get(key);
                if (current == null) continue;
                int index = -1;
                if (next < total && collector.placeholders.get(next) == current) {
                    index = next; // the usual case: same order as they were created
                } else {
                    for (int k = 0; k < total; k++) if (!done[k] && collector.placeholders.get(k) == current) { index = k; break; }
                }
                if (index >= 0) {
                    section.put(key, collector.encoders.get(index).get());
                    done[index] = true;
                    resolved++;
                    if (index == next) next++;
                }
            }
        }
        if (resolved != total) {
            throw new IllegalStateException("Deferred section encodings were not all located in the chunk tag: "
                    + resolved + " of " + total);
        }
    }

    // ---------------------------------------------------------------- single-valued block containers
    private static final Field DATA = dataField();
    private static volatile Method paletteAccessor;
    /** The codec's own output for a container holding only this state; the result depends on nothing else. */
    private static final ConcurrentHashMap<BlockState, Tag> UNIFORM = new ConcurrentHashMap<>();

    private static Field dataField() {
        try {
            Field field = Names.declaredField(PalettedContainer.class, "data");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    private static Object uniformValue(PalettedContainer<?> container) {
        if (DATA == null) return null;
        try {
            Object data = DATA.get(container);
            Method accessor = paletteAccessor;
            if (accessor == null) {
                accessor = Names.declaredMethod(data.getClass(), "palette");
                accessor.setAccessible(true);
                paletteAccessor = accessor;
            }
            Object palette = accessor.invoke(data);
            return palette instanceof SingleValuePalette<?> single ? single.valueFor(0) : null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /**
     * Encodes one section container.  PalettedContainer.pack unpacks all 4096 entries even when the
     * container holds a single block state (every all-air section); for those, the encoding is a
     * function of that one state, so the codec's result is computed once per state and copied.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Tag encode(Codec codec, Object value) {
        if (value instanceof PalettedContainer<?> container && uniformValue(container) instanceof BlockState state) {
            Tag template = UNIFORM.get(state);
            if (template == null) {
                template = (Tag) codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow();
                UNIFORM.putIfAbsent(state, template.copy());
                return template;
            }
            return template.copy();
        }
        return (Tag) codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow();
    }
}
