// SPDX-License-Identifier: MIT
package dev.tellurium.oracle.minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Bounded fresh-process harness with explicit separate working directories. */
public final class ProcessHarness {
    public record Result(int exitCode, boolean timedOut, String stdout, String stderr) {}
    public Result run(List<String> command, Path workingDirectory, Duration timeout) throws IOException, InterruptedException {
        if (command == null || command.isEmpty() || workingDirectory == null) throw new IllegalArgumentException("Invalid process harness request");
        if (!java.nio.file.Files.isDirectory(workingDirectory)) throw new IllegalArgumentException("Working directory must exist: " + workingDirectory);
        long timeoutMillis = timeoutMillis(timeout);
        Process process = new ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(false).start();
        ExecutorService readers = Executors.newFixedThreadPool(2);
        Future<byte[]> stdout = readers.submit(process.getInputStream()::readAllBytes);
        Future<byte[]> stderr = readers.submit(process.getErrorStream()::readAllBytes);
        boolean finished;
        try {
            finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                destroyProcessTree(process);
            }
            // The readers run while the process is alive, so a verbose server
            // cannot deadlock the process before the timeout.  After exit the
            // streams must still be fully drained before the result is used.
            String out = text(stdout);
            String err = text(stderr);
            return new Result(finished ? process.exitValue() : -1, !finished, out, err);
        } finally {
            readers.shutdownNow();
        }
    }

    /**
     * Stops the launched process and descendants without allowing a timed-out
     * server child to keep the harness's pipe readers alive.  ProcessHandle
     * descendants are refreshed while the graceful deadline is open because a
     * launcher can create a child after the first snapshot.
     */
    private static void destroyProcessTree(Process process) throws InterruptedException {
        Map<Long, ProcessHandle> handles = new LinkedHashMap<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (true) {
            handles.put(process.pid(), process.toHandle());
            process.descendants().forEach(child -> handles.put(child.pid(), child));
            handles.values().forEach(handle -> {
                if (handle.pid() != process.pid() && handle.isAlive()) handle.destroy();
            });
            if (process.isAlive()) process.destroy();
            if (handles.values().stream().noneMatch(ProcessHandle::isAlive)) return;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            process.waitFor(Math.min(TimeUnit.MILLISECONDS.toNanos(50), remaining), TimeUnit.NANOSECONDS);
        }
        handles.values().forEach(handle -> {
            if (handle.isAlive()) handle.destroyForcibly();
        });
        if (process.isAlive()) process.destroyForcibly();
    }

    private static String text(Future<byte[]> output) throws IOException, InterruptedException {
        try {
            return new String(output.get(2, TimeUnit.SECONDS), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.util.concurrent.TimeoutException failure) {
            output.cancel(true);
            return "<output-drain-timeout>";
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Process output reader failed", cause);
        }
    }

    static long timeoutMillis(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Positive process timeout required");
        }
        try {
            // Keep a positive deadline when callers use nanosecond precision;
            // an overflow is rejected before a child process is started.
            return Math.max(1L, timeout.toMillis());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Process timeout is too large", overflow);
        }
    }
}
