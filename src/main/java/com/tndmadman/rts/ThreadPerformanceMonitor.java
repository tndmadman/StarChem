package com.tndmadman.rts;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight dev-only sampler for CPU consumed by every live Java thread.
 * Samples ThreadMXBean counters only when the performance overlay asks for data.
 */
final class ThreadPerformanceMonitor {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final Map<Long, Long> PREVIOUS_CPU_NANOS = new HashMap<>();
    private static long previousSampleWallNanos;

    private ThreadPerformanceMonitor() { }

    static synchronized ThreadSnapshot snapshot() {
        if (!THREADS.isThreadCpuTimeSupported()) {
            return new ThreadSnapshot(false, "Thread CPU timing unsupported by this JVM", 0.0, 0.0, List.of());
        }
        if (!THREADS.isThreadCpuTimeEnabled()) {
            try {
                THREADS.setThreadCpuTimeEnabled(true);
            } catch (SecurityException | UnsupportedOperationException ex) {
                return new ThreadSnapshot(false, "Thread CPU timing unavailable: " + ex.getClass().getSimpleName(),
                        0.0, 0.0, List.of());
            }
        }

        long now = System.nanoTime();
        long elapsedNanos = previousSampleWallNanos <= 0 ? 0L : Math.max(1L, now - previousSampleWallNanos);
        long[] ids = THREADS.getAllThreadIds();
        ThreadInfo[] infos = THREADS.getThreadInfo(ids, 0);
        Set<Long> aliveIds = new HashSet<>(ids.length * 2);
        List<ThreadSample> samples = new ArrayList<>(ids.length);
        long totalDeltaCpuNanos = 0L;

        for (int i = 0; i < ids.length; i++) {
            long id = ids[i];
            ThreadInfo info = infos[i];
            if (info == null) continue;
            aliveIds.add(id);

            long cpuNanos = THREADS.getThreadCpuTime(id);
            if (cpuNanos < 0) cpuNanos = 0L;
            Long previous = PREVIOUS_CPU_NANOS.put(id, cpuNanos);
            long deltaCpuNanos = previous == null || elapsedNanos <= 0
                    ? 0L : Math.max(0L, cpuNanos - previous);
            totalDeltaCpuNanos += deltaCpuNanos;

            double corePercent = elapsedNanos <= 0 ? 0.0 : deltaCpuNanos * 100.0 / elapsedNanos;
            samples.add(new ThreadSample(id, info.getThreadName(), info.getThreadState(),
                    nanosToMs(deltaCpuNanos), corePercent, nanosToMs(cpuNanos)));
        }

        PREVIOUS_CPU_NANOS.keySet().retainAll(aliveIds);
        previousSampleWallNanos = now;
        samples.sort(Comparator.comparingDouble(ThreadSample::sampleCpuMs).reversed()
                .thenComparing(ThreadSample::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparingLong(ThreadSample::id));

        double elapsedMs = nanosToMs(elapsedNanos);
        double totalCpuMs = nanosToMs(totalDeltaCpuNanos);
        return new ThreadSnapshot(true, "", elapsedMs, totalCpuMs, List.copyOf(samples));
    }

    static String compactState(Thread.State state) {
        if (state == null) return "?";
        return switch (state) {
            case NEW -> "NEW";
            case RUNNABLE -> "RUN";
            case BLOCKED -> "BLOCK";
            case WAITING -> "WAIT";
            case TIMED_WAITING -> "TWAIT";
            case TERMINATED -> "DONE";
        };
    }

    static String compactName(String name, int maxChars) {
        String value = name == null || name.isBlank() ? "<unnamed>" : name;
        if (value.length() <= maxChars) return value;
        if (maxChars <= 3) return value.substring(0, Math.max(0, maxChars));
        return value.substring(0, maxChars - 3) + "...";
    }

    private static double nanosToMs(long nanos) {
        return nanos / 1_000_000.0;
    }

    record ThreadSnapshot(boolean supported, String message, double sampleWindowMs,
                          double totalCpuMs, List<ThreadSample> threads) {
        double equivalentCores() {
            return sampleWindowMs <= 0.0 ? 0.0 : totalCpuMs / sampleWindowMs;
        }

        String summary() {
            if (!supported) return message;
            return String.format(Locale.ROOT, "Java threads %d | sample %.0f ms | CPU %.2f ms | %.2f cores",
                    threads.size(), sampleWindowMs, totalCpuMs, equivalentCores());
        }
    }

    record ThreadSample(long id, String name, Thread.State state,
                        double sampleCpuMs, double corePercent, double totalCpuMs) { }
}
