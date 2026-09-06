package com.tndmadman.rts;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small bounded worker pool for read-only jobs that are joined before the authoritative world mutates again.
 * Deliberately not a general simulation executor: mutable World ownership stays deterministic.
 */
final class PerformanceWorkers {
    private static final int THREADS = Math.max(2, Math.min(6,
            Math.max(2, Runtime.getRuntime().availableProcessors() / 4)));
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(THREADS, new ThreadFactory() {
        @Override public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "starchem-perf-" + NEXT_ID.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        }
    });

    private PerformanceWorkers() { }

    static int threadCount() { return THREADS; }

    static <T> Future<T> submit(Callable<T> task) {
        return EXECUTOR.submit(task);
    }

    static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for performance worker.", interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Performance worker failed.", cause);
        }
    }
}
