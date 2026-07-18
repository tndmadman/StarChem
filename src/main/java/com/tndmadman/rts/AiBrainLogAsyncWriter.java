package com.tndmadman.rts;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Owns all AI brain-log encoding and filesystem work off the simulation thread. */
final class AiBrainLogAsyncWriter {
    private static final int SCHEMA_VERSION = 1;
    private static final long ROTATE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_LOG_FILES = 24;
    private static final long FLUSH_NANOS = 2_000_000_000L;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final Path directory;
    private final ArrayBlockingQueue<Entry> queue;
    private final int queueCapacity;
    private final int highPriorityReserve;
    private final Consumer<String> errorSink;
    private final AtomicLong droppedRecords = new AtomicLong();
    private final Thread worker;

    private volatile boolean accepting = true;
    private volatile boolean stopRequested;
    private volatile boolean forceStop;
    private volatile boolean pausedForTests;
    private volatile boolean inFlight;
    private volatile boolean running;
    private volatile boolean fileOpen;
    private volatile Path currentFile;

    private Entry sessionContext;
    private BufferedWriter writer;
    private String sessionId = "";
    private int part;
    private long bytesWritten;
    private long sequence;
    private long lastFlushNanos;

    AiBrainLogAsyncWriter(Path directory, int capacity, Consumer<String> errorSink) {
        this.directory = directory;
        this.queueCapacity = Math.max(2, capacity);
        this.highPriorityReserve = Math.max(1, queueCapacity / 4);
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.errorSink = errorSink == null ? ignored -> { } : errorSink;
        this.worker = new Thread(this::run, "starchem-ai-brainlog-writer");
        this.worker.setDaemon(true);
    }

    void start(Entry context) {
        sessionContext = context == null ? Entry.empty() : context;
        worker.start();
    }

    boolean accepting() { return accepting; }
    boolean recording() { return running && fileOpen; }
    Path currentFile() { return currentFile; }
    int queueDepth() { return queue.size(); }
    long droppedCount() { return droppedRecords.get(); }

    boolean offer(Entry entry) {
        if (!accepting || entry == null) return false;
        if (entry.lowPriority() && queue.size() >= queueCapacity - highPriorityReserve) {
            droppedRecords.incrementAndGet();
            return false;
        }
        if (queue.offer(entry)) return true;
        droppedRecords.incrementAndGet();
        return false;
    }

    void stopAndDrain(long timeoutMillis) {
        accepting = false;
        stopRequested = true;
        worker.interrupt();
        long bounded = Math.max(0, timeoutMillis);
        long firstWait = Math.max(0, bounded - 250L);
        join(firstWait);
        if (!worker.isAlive()) return;
        forceStop = true;
        queue.clear();
        worker.interrupt();
        join(Math.min(250L, bounded));
    }

    boolean awaitIdle(long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        while (System.nanoTime() < deadline) {
            if (queue.isEmpty() && !inFlight && droppedRecords.get() == 0) return true;
            try { Thread.sleep(2L); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); return false; }
        }
        return queue.isEmpty() && !inFlight && droppedRecords.get() == 0;
    }

    void pauseForTests(boolean paused) {
        pausedForTests = paused;
        if (!paused) worker.interrupt();
    }

    private void join(long millis) {
        if (millis <= 0) return;
        try { worker.join(millis); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private void run() {
        running = true;
        try {
            Files.createDirectories(directory);
            pruneOldFiles();
            sessionId = FILE_TIME.format(Instant.now())
                    + "-p" + ProcessHandle.current().pid()
                    + "-" + Long.toUnsignedString(System.nanoTime(), 36);
            openNextPart();
            writeEntry(sessionContext.derived("BRAIN", "session_start", "",
                    "Authoritative AI brain log started",
                    mapOf("schema", SCHEMA_VERSION,
                            "directory", directory.toAbsolutePath().normalize().toString())), false);
            flushNow();

            while (!forceStop) {
                if (pausedForTests) {
                    sleepWhilePaused();
                    continue;
                }
                Entry entry = poll();
                if (entry != null) {
                    inFlight = true;
                    emitBackpressure(entry);
                    writeEntry(entry, true);
                    inFlight = false;
                } else if (droppedRecords.get() > 0) {
                    inFlight = true;
                    emitBackpressure(sessionContext);
                    inFlight = false;
                }
                flushIfDue();
                if (stopRequested && queue.isEmpty() && !inFlight) break;
            }

            if (!forceStop && writer != null) {
                emitBackpressure(sessionContext);
                writeEntry(sessionContext.derived("BRAIN", "session_end", "",
                        "AI brain log closed", mapOf("lines", sequence)), true);
                flushNow();
            }
        } catch (IOException | RuntimeException ex) {
            errorSink.accept("writer failed: " + compactException(ex));
        } finally {
            inFlight = false;
            accepting = false;
            closeWriter();
            running = false;
        }
    }

    private Entry poll() {
        try { return queue.poll(100L, TimeUnit.MILLISECONDS); }
        catch (InterruptedException ex) {
            if (forceStop) return null;
            return queue.poll();
        }
    }

    private void sleepWhilePaused() {
        try { Thread.sleep(5L); }
        catch (InterruptedException ignored) { }
    }

    private void emitBackpressure(Entry context) throws IOException {
        long dropped = droppedRecords.getAndSet(0);
        if (dropped <= 0 || writer == null) return;
        writeEntry(context.derived("BRAIN", "logger_backpressure", "",
                "AI brain log dropped or coalesced records to protect simulation latency",
                mapOf("records", dropped, "queueCapacity", queueCapacity)), true);
    }

    private void writeEntry(Entry entry, boolean allowRotate) throws IOException {
        if (writer == null || entry == null) return;
        String encoded = encode(entry, sequence + 1, part);
        int encodedBytes = encoded.getBytes(StandardCharsets.UTF_8).length;
        if (allowRotate && bytesWritten > 0 && bytesWritten + encodedBytes > ROTATE_BYTES) {
            rotate(entry);
            encoded = encode(entry, sequence + 1, part);
            encodedBytes = encoded.getBytes(StandardCharsets.UTF_8).length;
        }
        sequence++;
        writer.write(encoded);
        bytesWritten += encodedBytes;
    }

    private String encode(Entry entry, long rowSequence, int rowPart) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("schema", SCHEMA_VERSION);
        row.put("seq", rowSequence);
        row.put("utc", Instant.now().toString());
        row.put("session", sessionId);
        row.put("part", rowPart);
        row.put("source", entry.source());
        row.put("category", entry.category());
        row.put("entity", entry.entity());
        row.put("message", entry.message());
        if (!entry.worldName().isBlank()) row.put("world", entry.worldName());
        if (!entry.systemId().isBlank()) {
            row.put("system", entry.systemId());
            row.put("systemName", entry.systemName());
            row.put("gameTime", entry.gameTime());
            row.put("seed", entry.seed());
        }
        row.put("data", entry.data());
        return json(row) + System.lineSeparator();
    }

    private void rotate(Entry context) throws IOException {
        long previousBytes = bytesWritten;
        int previousPart = part;
        flushNow();
        openNextPart();
        writeEntry(context.derived("BRAIN", "session_continue", "",
                "AI brain log rotated",
                mapOf("previousPart", previousPart, "previousBytes", previousBytes,
                        "newPart", part)), false);
        pruneOldFiles();
    }

    private void openNextPart() throws IOException {
        closeWriter();
        part++;
        String name = "starchem-ai-" + sessionId
                + "-part" + String.format(Locale.ROOT, "%03d", part) + ".jsonl";
        currentFile = directory.resolve(name);
        writer = Files.newBufferedWriter(currentFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        fileOpen = true;
        bytesWritten = 0;
        lastFlushNanos = System.nanoTime();
    }

    private void flushIfDue() throws IOException {
        if (writer == null || System.nanoTime() - lastFlushNanos < FLUSH_NANOS) return;
        flushNow();
    }

    private void flushNow() throws IOException {
        if (writer == null) return;
        writer.flush();
        lastFlushNanos = System.nanoTime();
    }

    private void closeWriter() {
        BufferedWriter active = writer;
        writer = null;
        fileOpen = false;
        if (active == null) return;
        try { active.close(); }
        catch (IOException ignored) { }
    }

    private void pruneOldFiles() {
        try {
            if (!Files.isDirectory(directory)) return;
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                    directory, "starchem-ai-*.jsonl")) {
                for (Path path : stream) if (Files.isRegularFile(path)) files.add(path);
            }
            files.sort(Comparator.comparingLong(AiBrainLogAsyncWriter::modifiedTime).reversed());
            for (int i = MAX_LOG_FILES; i < files.size(); i++) {
                try { Files.deleteIfExists(files.get(i)); }
                catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    private static long modifiedTime(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ex) { return 0L; }
    }

    private static String compactException(Throwable ex) {
        if (ex == null) return "unknown error";
        String message = ex.getMessage();
        return ex.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return quote(text);
        if (value instanceof Boolean bool) return bool.toString();
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            return Double.isFinite(numeric) ? number.toString() : "null";
        }
        if (value instanceof Enum<?> enumeration) return quote(enumeration.name());
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(quote(String.valueOf(entry.getKey())))
                        .append(':').append(json(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (Object item : iterable) {
                if (!first) out.append(',');
                first = false;
                out.append(json(item));
            }
            return out.append(']').toString();
        }
        return quote(String.valueOf(value));
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text == null ? 4 : text.length() + 8);
        out.append('"');
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
                        else out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    static record Entry(String source, String category, String entity, String message,
                        String worldName, String systemId, String systemName,
                        double gameTime, long seed, Map<String, Object> data,
                        boolean lowPriority) {
        static Entry capture(World world, String source, String category,
                             String entity, String message, Map<String, ?> data) {
            String checkedCategory = safe(category);
            return new Entry(safe(source), checkedCategory, safe(entity), safe(message),
                    world == null ? "" : safe(world.localPlayerName),
                    world == null ? "" : safe(world.activeSystemId()),
                    world == null ? "" : safe(world.systemName()),
                    world == null ? 0.0 : round(world.systemTime(), 3),
                    world == null ? 0L : world.systemSeed(),
                    freezeMap(data), lowPriority(checkedCategory));
        }

        static Entry empty() {
            return new Entry("BRAIN", "", "", "", "", "", "", 0.0, 0L,
                    Map.of(), false);
        }

        Entry derived(String nextSource, String nextCategory, String nextEntity,
                      String nextMessage, Map<String, ?> nextData) {
            return new Entry(safe(nextSource), safe(nextCategory), safe(nextEntity),
                    safe(nextMessage), worldName, systemId, systemName, gameTime, seed,
                    freezeMap(nextData), lowPriority(nextCategory));
        }

        boolean sameCoalesceKey(Entry other) {
            return other != null && lowPriority && other.lowPriority
                    && category.equals(other.category)
                    && entity.equals(other.entity)
                    && systemId.equals(other.systemId);
        }

        private static boolean lowPriority(String category) {
            return "unit_position".equals(category) || "system_checkpoint".equals(category);
        }

        private static Map<String, Object> freezeMap(Map<String, ?> source) {
            if (source == null || source.isEmpty()) return Map.of();
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, ?> entry : source.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return Collections.unmodifiableMap(copy);
        }

        private static String safe(String value) { return value == null ? "" : value; }
        private static double round(double value, int places) {
            if (!Double.isFinite(value)) return 0.0;
            double scale = Math.pow(10.0, Math.max(0, places));
            return Math.round(value * scale) / scale;
        }
    }
}
