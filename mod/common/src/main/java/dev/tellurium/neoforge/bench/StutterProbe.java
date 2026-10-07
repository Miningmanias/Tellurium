// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.bench;

import dev.tellurium.neoforge.loader.Loader;
import net.minecraft.server.MinecraftServer;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

/**
 * Developer measurement of smoothness during a {@link PlayerTour}: how late server ticks come and, in a client,
 * how long frames take.  Averages hide what a player notices; this reports the tail.
 *
 * <p>A server tick is due every 50 ms, so the time from the end of one tick to the end of the next is 50 ms
 * unless a tick overran; the excess is the hitch.  Frame times are read from the client by name about every
 * two milliseconds (the client keeps the duration of its last frame); without a client there are none.</p>
 */
final class StutterProbe {
    private static final int CAPACITY = 1 << 16;

    private final long[] tickGaps = new long[CAPACITY];
    private volatile int ticks;
    private volatile long lastTickEnd;
    private final long[] frames = new long[1 << 20];
    private volatile int frameCount;
    private volatile boolean running = true;
    private final long gcMillisAtStart = gcMillis();
    private final long gcCountAtStart = gcCount();
    private final long startedNanos = System.nanoTime();

    /** What the server thread was doing while a tick was late, sampled every 10 ms: place -> samples. */
    private final java.util.Map<String, Integer> lateStacks = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.List<Long> gcPauses = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    StutterProbe(MinecraftServer server) {
        Thread serverThread = server.getRunningThread();
        Thread watchdog = new Thread(() -> watch(serverThread), "tellurium-bench-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean instanceof javax.management.NotificationEmitter emitter) {
                emitter.addNotificationListener((notification, handback) -> {
                    if (!running || !notification.getType().equals("com.sun.management.gc.notification")) return;
                    var info = com.sun.management.GarbageCollectionNotificationInfo.from((javax.management.openmbean.CompositeData) notification.getUserData());
                    // Concurrent phases are reported too; only the collections that stop the world count as pauses.
                    if (!info.getGcName().contains("Concurrent")) gcPauses.add(info.getGcInfo().getDuration());
                }, null, null);
            }
        }
        Loader.onServerTickEnd(this::tickEnded);
        Thread sampler = new Thread(this::sampleFrames, "tellurium-bench-frames");
        sampler.setDaemon(true);
        sampler.start();
    }

    private void tickEnded(MinecraftServer server) {
        if (!running) return;
        long now = System.nanoTime();
        long last = lastTickEnd;
        lastTickEnd = now;
        int index = ticks;
        if (last != 0 && index < CAPACITY) {
            tickGaps[index] = now - last;
            ticks = index + 1;
        }
    }

    private void watch(Thread serverThread) {
        try {
            while (running) {
                Thread.sleep(10);
                long last = lastTickEnd;
                // A tick ends at most 50 ms after the one before unless it overran; past 80 ms it is late.
                if (last == 0 || System.nanoTime() - last < 80_000_000L) continue;
                StackTraceElement[] stack = serverThread.getStackTrace();
                if (System.nanoTime() - last > 450_000_000L && dumps < 3 && System.nanoTime() - lastDump > 2_000_000_000L) {
                    dumps++;
                    lastDump = System.nanoTime();
                    dumpThreads(System.nanoTime() - last);
                }
                StringBuilder place = new StringBuilder();
                int named = 0;
                for (StackTraceElement frame : stack) {
                    String type = frame.getClassName();
                    if (type.startsWith("java.") || type.startsWith("jdk.") || type.startsWith("sun.") || type.startsWith("it.unimi.")
                            || type.startsWith("com.google.") || type.startsWith("org.spongepowered.")) continue;
                    if (named++ > 0) place.append(" < ");
                    place.append(type, type.lastIndexOf('.') + 1, type.length()).append('.').append(frame.getMethodName());
                    if (named == 9) break;
                }
                if (named == 0 && stack.length > 0) place.append(stack[0].getClassName()).append('.').append(stack[0].getMethodName());
                lateStacks.merge(place.toString(), 1, Integer::sum);
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        }
    }

    private int dumps;
    private long lastDump;

    /** What every thread is doing while the server thread has been stuck for a while: grouped by where they are. */
    private static void dumpThreads(long stuckNanos) {
        java.util.Map<String, Integer> places = new java.util.TreeMap<>();
        for (var entry : Thread.getAllStackTraces().entrySet()) {
            String name = entry.getKey().getName().replaceAll("[0-9]+", "#");
            StringBuilder place = new StringBuilder(name).append(" [").append(entry.getKey().getState()).append("]: ");
            int named = 0;
            for (StackTraceElement frame : entry.getValue()) {
                String type = frame.getClassName();
                if (type.startsWith("java.") || type.startsWith("jdk.") || type.startsWith("sun.") || type.startsWith("it.unimi.")) continue;
                if (named++ > 0) place.append(" < ");
                place.append(type, type.lastIndexOf('.') + 1, type.length()).append('.').append(frame.getMethodName());
                if (named == 7) break;
            }
            if (named > 0) places.merge(place.toString(), 1, Integer::sum);
        }
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "Tellurium player tour: server thread stuck for %.0f ms; threads:", stuckNanos / 1e6));
        places.forEach((place, count) -> out.append(String.format(Locale.ROOT, "%n    %2dx %s", count, place)));
        org.slf4j.LoggerFactory.getLogger("tellurium-bench").info(out.toString());
    }

    private void sampleFrames() {
        try {
            Class<?> type = Class.forName("net.minecraft.client.Minecraft");
            Object client = type.getMethod("getInstance").invoke(null);
            Method frameTime = type.getMethod("getFrameTimeNs");
            long previous = -1;
            while (running) {
                long value = (long) frameTime.invoke(client);
                // The same value twice running is nearly always the same frame still being the last one.
                if (value != previous && value > 0 && frameCount < frames.length) {
                    frames[frameCount] = value;
                    frameCount++;
                    previous = value;
                }
                Thread.sleep(2);
            }
        } catch (ReflectiveOperationException | RuntimeException noClient) {
            // a dedicated server, or a client without this method: no frame figures
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops measuring and gives the report line. */
    String finish() {
        running = false;
        double seconds = (System.nanoTime() - startedNanos) / 1e9;
        long[] gaps = Arrays.copyOf(tickGaps, ticks);
        Arrays.sort(gaps);
        StringBuilder out = new StringBuilder();
        if (gaps.length > 0) {
            out.append(String.format(Locale.ROOT, "server ticks %,d in %.0f s: gap median %.0f ms, 99%% %.0f ms, longest %.0f ms; %d over 75 ms, %d over 150 ms",
                    gaps.length, seconds, ms(gaps[gaps.length / 2]), ms(gaps[(int) (gaps.length * 0.99)]), ms(gaps[gaps.length - 1]),
                    over(gaps, 75), over(gaps, 150)));
        }
        long[] seen = Arrays.copyOf(frames, frameCount);
        Arrays.sort(seen);
        if (seen.length > 100) {
            out.append(String.format(Locale.ROOT, "; client frames sampled %,d: median %.1f ms, 99%% %.1f ms, longest %.0f ms; %d over 33 ms, %d over 100 ms",
                    seen.length, ms(seen[seen.length / 2]), ms(seen[(int) (seen.length * 0.99)]), ms(seen[seen.length - 1]),
                    over(seen, 33), over(seen, 100)));
        }
        out.append(String.format(Locale.ROOT, "; garbage collection %d ms in %d collections", gcMillis() - gcMillisAtStart, gcCount() - gcCountAtStart));
        long[] pauses;
        synchronized (gcPauses) { pauses = gcPauses.stream().mapToLong(Long::longValue).sorted().toArray(); }
        if (pauses.length > 0) {
            int long20 = 0;
            for (long pause : pauses) if (pause > 20) long20++;
            out.append(String.format(Locale.ROOT, " (pauses: median %d ms, longest %d ms, %d over 20 ms)", pauses[pauses.length / 2], pauses[pauses.length - 1], long20));
        }
        var places = new java.util.ArrayList<>(lateStacks.entrySet());
        places.sort((a, b) -> b.getValue() - a.getValue());
        int total = places.stream().mapToInt(java.util.Map.Entry::getValue).sum();
        out.append(String.format(Locale.ROOT, "%n  server thread while a tick was late (%d ms sampled):", total * 10));
        for (int i = 0; i < Math.min(12, places.size()); i++) {
            out.append(String.format(Locale.ROOT, "%n    %5d ms  %s", places.get(i).getValue() * 10, places.get(i).getKey()));
        }
        return out.toString();
    }

    private static double ms(long nanos) {
        return nanos / 1e6;
    }

    private static int over(long[] sorted, int millis) {
        int count = 0;
        for (int i = sorted.length - 1; i >= 0 && sorted[i] > millis * 1_000_000L; i--) count++;
        return count;
    }

    private static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) total += Math.max(0, bean.getCollectionTime());
        return total;
    }

    private static long gcCount() {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) total += Math.max(0, bean.getCollectionCount());
        return total;
    }
}
