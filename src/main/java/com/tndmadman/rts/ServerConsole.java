package com.tndmadman.rts;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads dedicated-server console input without mutating authoritative state from the reader thread. */
final class ServerConsole implements AutoCloseable {
    static final int DEFAULT_QUEUE_CAPACITY = 128;
    static final int MAX_LINE_LENGTH = 4096;

    private final BufferedReader reader;
    private final PrintStream errors;
    private final ArrayBlockingQueue<String> pending;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private Thread readerThread;

    private ServerConsole(InputStream input, PrintStream errors, int queueCapacity) {
        this.reader = input == null ? null : new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.errors = errors == null ? System.err : errors;
        this.pending = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
    }

    static ServerConsole start(InputStream input, PrintStream errors) {
        ServerConsole console = new ServerConsole(input, errors, DEFAULT_QUEUE_CAPACITY);
        console.startReader();
        return console;
    }

    static ServerConsole detached(int queueCapacity, PrintStream errors) {
        return new ServerConsole(null, errors, queueCapacity);
    }

    boolean submit(String line) {
        if (!accepting.get() || line == null) return false;
        String command = line.strip();
        if (command.isEmpty()) return true;
        if (command.length() > MAX_LINE_LENGTH) {
            errors.println("Console command rejected: input exceeded " + MAX_LINE_LENGTH + " characters.");
            return false;
        }
        if (pending.offer(command)) return true;
        errors.println("Console command rejected: pending command queue is full.");
        return false;
    }

    String poll() {
        return pending.poll();
    }

    int pendingCount() {
        return pending.size();
    }

    boolean acceptingInput() {
        return accepting.get();
    }

    @Override public void close() {
        accepting.set(false);
        Thread thread = readerThread;
        if (thread != null) thread.interrupt();
        pending.clear();
    }

    private void startReader() {
        if (reader == null) return;
        readerThread = new Thread(this::readLoop, "starchem-server-console");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        try {
            while (accepting.get()) {
                String line = reader.readLine();
                if (line == null) break;
                submit(line);
            }
        } catch (IOException ex) {
            if (accepting.get()) errors.println("Server console input failed: " + ex.getMessage());
        } finally {
            accepting.set(false);
        }
    }
}
