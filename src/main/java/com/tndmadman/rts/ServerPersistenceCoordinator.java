package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Serializes bounded save and backup I/O away from the authoritative simulation thread. */
final class ServerPersistenceCoordinator {
    static final int DEFAULT_QUEUE_CAPACITY = 8;
    private static final Map<String, ServerPersistenceCoordinator> INSTANCES = new HashMap<>();

    @FunctionalInterface
    interface IoJob {
        void run() throws Exception;
    }

    record Submission(long id, String label, boolean accepted, boolean coalesced,
                      CompletableFuture<Void> completion) {
        static Submission rejected(String label) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RejectedExecutionException("Persistence queue is full."));
            return new Submission(0, label == null ? "persistence" : label, false, false, failed);
        }
    }

    private final String identity;
    private final ThreadPoolExecutor executor;
    private final AtomicLong nextJobId = new AtomicLong(1);
    private final AtomicInteger activeJobs = new AtomicInteger();
    private CompletableFuture<Void> autosaveOutstanding;
    private volatile String activeLabel = "";
    private volatile String lastFailure = "";

    static synchronized ServerPersistenceCoordinator forSave(Path saveDir, String saveName) {
        Path dir = saveDir == null ? Path.of("saves") : saveDir;
        String cleanName = Config.cleanSaveName(saveName);
        String key = dir.toAbsolutePath().normalize() + "\u0000" + cleanName;
        return INSTANCES.computeIfAbsent(key,
                ignored -> new ServerPersistenceCoordinator(cleanName, DEFAULT_QUEUE_CAPACITY));
    }

    ServerPersistenceCoordinator(String identity, int queueCapacity) {
        this.identity = identity == null || identity.isBlank() ? "server" : identity;
        int capacity = Math.max(1, queueCapacity);
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "starchem-persistence-" + this.identity);
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), threads, new ThreadPoolExecutor.AbortPolicy());
    }

    synchronized Submission submit(String label, boolean coalesceAutosave, IoJob job) {
        Objects.requireNonNull(job, "Persistence job is required.");
        String safeLabel = label == null || label.isBlank() ? "persistence" : label;
        if (coalesceAutosave && autosaveOutstanding != null && !autosaveOutstanding.isDone()) {
            return new Submission(0, safeLabel, true, true, autosaveOutstanding);
        }

        long id = nextJobId.getAndIncrement();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        if (coalesceAutosave) autosaveOutstanding = completion;
        try {
            executor.execute(() -> runJob(id, safeLabel, coalesceAutosave, completion, job));
            return new Submission(id, safeLabel, true, false, completion);
        } catch (RejectedExecutionException ex) {
            if (coalesceAutosave && autosaveOutstanding == completion) autosaveOutstanding = null;
            completion.completeExceptionally(ex);
            return Submission.rejected(safeLabel);
        }
    }

    private void runJob(long id, String label, boolean coalesceAutosave,
                        CompletableFuture<Void> completion, IoJob job) {
        activeJobs.incrementAndGet();
        activeLabel = label;
        try {
            job.run();
            lastFailure = "";
            completion.complete(null);
        } catch (Throwable ex) {
            lastFailure = describe(ex);
            completion.completeExceptionally(ex);
            System.err.println("Persistence job " + id + " failed (" + label + "): " + lastFailure);
        } finally {
            activeLabel = "";
            activeJobs.decrementAndGet();
            if (coalesceAutosave) {
                synchronized (this) {
                    if (autosaveOutstanding == completion) autosaveOutstanding = null;
                }
            }
        }
    }

    void await(Submission submission) throws IOException {
        if (submission == null || !submission.accepted()) {
            throw new IOException("Persistence queue is full.");
        }
        try {
            submission.completion().get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for persistence job " + submission.id() + ".", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Persistence job " + submission.id() + " failed: " + describe(cause), cause);
        }
    }

    int activeJobs() { return activeJobs.get(); }
    int queuedJobs() { return executor.getQueue().size(); }
    String activeLabel() { return activeLabel; }
    String lastFailure() { return lastFailure; }

    void shutdownForValidation() {
        executor.shutdownNow();
    }

    private static String describe(Throwable ex) {
        if (ex == null) return "unknown failure";
        String message = ex.getMessage();
        return ex.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
