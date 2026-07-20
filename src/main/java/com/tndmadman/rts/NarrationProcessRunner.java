package com.tndmadman.rts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

final class NarrationProcessRunner {
    static final int MAX_CAPTURE_BYTES = 64 * 1024;
    private static final int MAX_CAPTURE_LINES = 512;
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final ThreadPoolExecutor IO_EXECUTOR = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), runnable -> {
        Thread thread = new Thread(runnable,
                "StarChem Narration Process I/O-" + THREAD_IDS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    private NarrationProcessRunner() { }

    static ExitResult runDiscarding(ProcessBuilder builder, int timeoutSeconds)
            throws IOException, InterruptedException {
        requireTimeout(timeoutSeconds);
        builder.redirectErrorStream(false);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                terminateTree(process);
                return new ExitResult(-1, true);
            }
            return new ExitResult(process.exitValue(), false);
        } catch (InterruptedException ex) {
            terminateTree(process);
            Thread.currentThread().interrupt();
            throw ex;
        } finally {
            closeStreams(process);
        }
    }

    static CaptureResult runAndRead(ProcessBuilder builder, int timeoutSeconds)
            throws IOException, InterruptedException {
        requireTimeout(timeoutSeconds);
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
        Process process = builder.start();
        Future<CapturedOutput> outputFuture;
        try {
            outputFuture = IO_EXECUTOR.submit(() -> drain(process.getInputStream()));
        } catch (RejectedExecutionException ex) {
            terminateTree(process);
            closeStreams(process);
            throw new IOException("Narration process output queue is full.", ex);
        }

        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                terminateTree(process);
                closeStreams(process);
                CapturedOutput captured = awaitOutput(outputFuture);
                return new CaptureResult(lines(captured.bytes()), -1, true, captured.truncated());
            }
            int exitCode = process.exitValue();
            CapturedOutput captured = awaitOutput(outputFuture);
            return new CaptureResult(lines(captured.bytes()), exitCode, false, captured.truncated());
        } catch (InterruptedException ex) {
            terminateTree(process);
            closeStreams(process);
            outputFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw ex;
        } finally {
            closeStreams(process);
        }
    }

    private static CapturedOutput drain(InputStream input) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(8_192, MAX_CAPTURE_BYTES));
        boolean truncated = false;
        byte[] buffer = new byte[8_192];
        try (InputStream stream = input) {
            int read;
            while ((read = stream.read(buffer)) != -1) {
                int remaining = MAX_CAPTURE_BYTES - captured.size();
                if (remaining > 0) captured.write(buffer, 0, Math.min(remaining, read));
                if (read > remaining) truncated = true;
            }
        } catch (IOException ignored) {
            // Timeout cleanup closes the stream. Return any diagnostic output already captured.
        }
        return new CapturedOutput(captured.toByteArray(), truncated);
    }

    private static CapturedOutput awaitOutput(Future<CapturedOutput> future) throws InterruptedException {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            return CapturedOutput.EMPTY;
        } catch (ExecutionException ex) {
            return CapturedOutput.EMPTY;
        }
    }

    private static List<String> lines(byte[] bytes) {
        if (bytes.length == 0) return List.of();
        List<String> lines = new ArrayList<>();
        String text = new String(bytes, StandardCharsets.UTF_8);
        for (String line : text.split("\\R", -1)) {
            String clean = line.trim();
            if (!clean.isBlank()) lines.add(clean);
            if (lines.size() >= MAX_CAPTURE_LINES) break;
        }
        return List.copyOf(lines);
    }

    private static void terminateTree(Process process) throws InterruptedException {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (int i = descendants.size() - 1; i >= 0; i--) descendants.get(i).destroy();
        process.destroy();
        if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
            for (int i = descendants.size() - 1; i >= 0; i--) {
                ProcessHandle descendant = descendants.get(i);
                if (descendant.isAlive()) descendant.destroyForcibly();
            }
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
        }
        for (ProcessHandle descendant : descendants) {
            if (descendant.isAlive()) descendant.destroyForcibly();
        }
    }

    private static void closeStreams(Process process) {
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
    }

    private static void requireTimeout(int timeoutSeconds) {
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("timeoutSeconds must be positive");
    }

    record ExitResult(int exitCode, boolean timedOut) { }

    record CaptureResult(List<String> lines, int exitCode, boolean timedOut, boolean truncated) { }

    private record CapturedOutput(byte[] bytes, boolean truncated) {
        private static final CapturedOutput EMPTY = new CapturedOutput(new byte[0], false);
    }
}
