from pathlib import Path

ROOT = Path('.')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one match, found {count}')
    return text.replace(old, new, 1)


def replace_range(text: str, start_marker: str, end_marker: str, replacement: str, label: str) -> str:
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f'{label}: start marker not found')
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f'{label}: end marker not found')
    return text[:start] + replacement + text[end:]


brain_path = ROOT / 'src/main/java/com/tndmadman/rts/AiBrainLog.java'
brain = brain_path.read_text(encoding='utf-8')

old_fields = '''    private static final Map<World, WorldMemory> MEMORIES = new WeakHashMap<>();
    private static Path logDirectory = DEFAULT_LOG_DIRECTORY;
    private static boolean devModeRequested;
    private static BufferedWriter writer;
    private static Path currentFile;
    private static String sessionId = "";
    private static int part;
    private static long bytesWritten;
    private static long sequence;
    private static long lastFlushNanos;
    private static String lastError = "";
'''
new_fields = '''    private static final int DEFAULT_QUEUE_CAPACITY = 8192;
    private static final long WRITER_DRAIN_MILLIS = 2000L;
    private static final Map<World, WorldMemory> MEMORIES = new WeakHashMap<>();
    private static Path logDirectory = DEFAULT_LOG_DIRECTORY;
    private static int queueCapacity = DEFAULT_QUEUE_CAPACITY;
    private static boolean devModeRequested;
    private static AiBrainLogAsyncWriter asyncWriter;
    private static volatile String lastError = "";
'''
brain = replace_once(brain, old_fields, new_fields, 'fields')

start_lifecycle = '    /** Enables logging only for the lifetime of an authoritative developer session. */\n'
end_lifecycle = '    /**\n     * Called by the authoritative galaxy director after one system simulation.\n'
new_lifecycle = '''    /** Enables logging only for the lifetime of an authoritative developer session. */
    static synchronized void setEnabled(boolean enabled) {
        if (enabled) {
            if (devModeRequested) return;
            devModeRequested = true;
            lastError = "";
            return;
        }
        if (!devModeRequested && asyncWriter == null) return;
        devModeRequested = false;
        stopWriter(WRITER_DRAIN_MILLIS);
        MEMORIES.clear();
        queueCapacity = DEFAULT_QUEUE_CAPACITY;
        lastError = "";
    }

    static synchronized boolean recording() {
        AiBrainLogAsyncWriter active = asyncWriter;
        return active != null && active.recording();
    }

    static synchronized String status() {
        if (!lastError.isBlank()) return "ERROR: " + lastError;
        AiBrainLogAsyncWriter active = asyncWriter;
        if (active != null && active.currentFile() != null) {
            return "REC " + active.currentFile().toAbsolutePath().normalize()
                    + " | queued " + active.queueDepth();
        }
        return devModeRequested ? "waiting for authoritative AI tick" : "off";
    }

    static synchronized Path currentFile() {
        AiBrainLogAsyncWriter active = asyncWriter;
        return active == null ? null : active.currentFile();
    }

'''
brain = replace_range(brain, start_lifecycle, end_lifecycle, new_lifecycle, 'lifecycle')
brain = replace_once(brain, '            flushIfDue();\n', '', 'observe flush')
brain = replace_once(brain, '''        if (writer == null) {
            if (world == null || !ensureOpen(world)) return;
        }
        write(world, safe(source), safe(category), "", safe(message), Map.of());
        flushIfDue();
''', '''        if (asyncWriter == null || !asyncWriter.accepting()) {
            if (world == null || !ensureOpen(world)) return;
        }
        write(world, safe(source), safe(category), "", safe(message), Map.of());
''', 'event writer')

new_writer_bridge = '''    private static boolean ensureOpen(World world) {
        AiBrainLogAsyncWriter active = asyncWriter;
        if (active != null && active.accepting()) return true;
        if (!lastError.isBlank()) return false;
        try {
            AiBrainLogAsyncWriter.Entry context = AiBrainLogAsyncWriter.Entry.capture(
                    world, "BRAIN", "session_context", "", "", Map.of());
            active = new AiBrainLogAsyncWriter(logDirectory, queueCapacity,
                    AiBrainLog::recordWriterError);
            asyncWriter = active;
            active.start(context);
            return true;
        } catch (RuntimeException ex) {
            fail("could not start async log: " + compactException(ex));
            return false;
        }
    }

    private static void write(World world, String source, String category,
                              String entity, String message,
                              Map<String, ?> data) {
        AiBrainLogAsyncWriter active = asyncWriter;
        if (active == null || !active.accepting()) return;
        active.offer(AiBrainLogAsyncWriter.Entry.capture(world, source, category,
                entity, message, data));
    }

    private static void shutdown() {
        synchronized (AiBrainLog.class) {
            devModeRequested = false;
            stopWriter(WRITER_DRAIN_MILLIS);
        }
    }

    private static void stopWriter(long timeoutMillis) {
        AiBrainLogAsyncWriter active = asyncWriter;
        asyncWriter = null;
        if (active != null) active.stopAndDrain(timeoutMillis);
    }

    private static void recordWriterError(String message) {
        lastError = safe(message);
    }

    private static void fail(String message) {
        lastError = safe(message);
        stopWriter(250L);
    }

'''
brain = replace_range(brain,
        '    private static boolean ensureOpen(World world) {\n',
        '    private static String compactException(Throwable ex) {\n',
        new_writer_bridge,
        'writer bridge')

new_test_hooks = '''    /** Test hook; production code never changes the configured directory. */
    static synchronized void resetForTests(Path directory) {
        resetForTests(directory, DEFAULT_QUEUE_CAPACITY);
    }

    static synchronized void resetForTests(Path directory, int capacity) {
        devModeRequested = false;
        stopWriter(250L);
        MEMORIES.clear();
        logDirectory = directory;
        queueCapacity = Math.max(2, capacity);
        devModeRequested = true;
        lastError = "";
    }

    static synchronized void closeForTests() {
        devModeRequested = false;
        stopWriter(WRITER_DRAIN_MILLIS);
        MEMORIES.clear();
        logDirectory = DEFAULT_LOG_DIRECTORY;
        queueCapacity = DEFAULT_QUEUE_CAPACITY;
        lastError = "";
    }

    static boolean awaitIdleForTests(long timeoutMillis) {
        AiBrainLogAsyncWriter active;
        synchronized (AiBrainLog.class) { active = asyncWriter; }
        return active == null || active.awaitIdle(timeoutMillis);
    }

    static synchronized void pauseWriterForTests(boolean paused) {
        if (asyncWriter != null) asyncWriter.pauseForTests(paused);
    }

    static synchronized int queueDepthForTests() {
        return asyncWriter == null ? 0 : asyncWriter.queueDepth();
    }

    static synchronized long droppedRecordsForTests() {
        return asyncWriter == null ? 0 : asyncWriter.droppedCount();
    }

'''
brain = replace_range(brain,
        '    /** Test hook; production code never changes the configured directory. */\n',
        '    private static final class WorldMemory {\n',
        new_test_hooks,
        'test hooks')

brain_path.write_text(brain, encoding='utf-8')

writer_source = r'''package com.tndmadman.rts;

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
        this.queue = new ArrayBlockingQueue<>(Math.max(2, capacity));
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
        if (queue.offer(entry)) return true;

        if (entry.lowPriority()) {
            for (Entry queued : queue) {
                if (!queued.sameCoalesceKey(entry)) continue;
                if (queue.remove(queued) && queue.offer(entry)) {
                    droppedRecords.incrementAndGet();
                    return true;
                }
            }
            droppedRecords.incrementAndGet();
            return false;
        }

        for (Entry queued : queue) {
            if (!queued.lowPriority()) continue;
            if (queue.remove(queued) && queue.offer(entry)) {
                droppedRecords.incrementAndGet();
                return true;
            }
        }
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
                mapOf("records", dropped, "queueCapacity", queue.remainingCapacity() + queue.size())), true);
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
                copy.put(String.valueOf(entry.getKey()), freeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }

        private static Object freeze(Object value) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    copy.put(String.valueOf(entry.getKey()), freeze(entry.getValue()));
                }
                return Collections.unmodifiableMap(copy);
            }
            if (value instanceof Iterable<?> iterable) {
                List<Object> copy = new ArrayList<>();
                for (Object item : iterable) copy.add(freeze(item));
                return List.copyOf(copy);
            }
            return value;
        }

        private static String safe(String value) { return value == null ? "" : value; }
        private static double round(double value, int places) {
            if (!Double.isFinite(value)) return 0.0;
            double scale = Math.pow(10.0, Math.max(0, places));
            return Math.round(value * scale) / scale;
        }
    }
}
'''
(ROOT / 'src/main/java/com/tndmadman/rts/AiBrainLogAsyncWriter.java').write_text(writer_source, encoding='utf-8')

validator_source = r'''package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AiBrainLogValidator {
    private static final Pattern SESSION = Pattern.compile("\\\"session\\\":\\\"([^\\\"]+)\\\"");
    private static final Pattern SEQUENCE = Pattern.compile("\\\"seq\\\":([0-9]+)");

    private AiBrainLogValidator() { }

    public static void main(String[] args) throws Exception {
        validateOrThrow();
        System.out.println("StarChem AI brain log validation passed.");
    }

    static void validateOrThrow() {
        validateContentAndLifecycle();
        validateBoundedBackpressure();
        validateOpenFailureIsolation();
    }

    private static void validateContentAndLifecycle() {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("starchem-ai-brainlog-");
            AiBrainLog.resetForTests(directory);

            PlayerRegistry.reset("WAIT", "AI Brain Log Validator", 0x50BEFF);
            World world = new World("AI Brain Log Validator",
                    Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                    StarSystems.CORSAIR_SYSTEM_ID, false);
            world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
            world.units.clear();
            world.bases.clear();
            world.resources.clear();

            NpcFaction faction = corsairs();
            Base base = new Base(faction.id() + ":B1", faction.id(), "outpost",
                    world.width * 0.5, world.height * 0.5);
            world.bases.put(base.id, base);
            Unit unit = new Unit(faction.id(), 98_001, "station_builder",
                    base.x + 100, base.y);
            world.units.put(unit.key(), unit);

            AiBrainLog.observe(world);
            AiDevLog.add(world, faction, "validator strategic decision");

            world.systemTime += 1.1;
            unit.basePackageType = "shipyard";
            unit.issueMove(unit.x + 500, unit.y + 100);
            unit.inventory.put(Material.IRON, 25.0);
            base.inventory.put(Material.COPPER, 40.0);
            world.status = "validator status change";
            AiBrainLog.observe(world);
            require(AiBrainLog.awaitIdleForTests(5_000),
                    "async brain log did not drain normal records");

            AiBrainLog.setEnabled(false);
            require(!AiBrainLog.recording() && "off".equals(AiBrainLog.status()),
                    "brain log did not stop when the developer session ended");
            long disabledBytes = totalBytes(directory);
            world.systemTime += 1.1;
            AiDevLog.add(world, faction, "event while logging is disabled");
            AiBrainLog.observe(world);
            require(totalBytes(directory) == disabledBytes,
                    "brain log wrote data after it was disabled");

            AiBrainLog.setEnabled(true);
            world.systemTime += 1.1;
            AiBrainLog.observe(world);
            require(await(AiBrainLog::recording, 3_000),
                    "re-enabled async writer did not open a session");
            AiBrainLog.setEnabled(false);
            AiBrainLog.closeForTests();

            List<Path> files = jsonlFiles(directory);
            require(files.size() >= 2,
                    "re-enabling the brain log did not start a new session file");
            String text = readAll(files);
            require(text.contains("\"category\":\"session_start\""),
                    "brain log omitted its session header");
            require(text.contains("\"category\":\"unit_spawn\""),
                    "brain log omitted initial unit state");
            require(text.contains("\"category\":\"base_spawn\""),
                    "brain log omitted initial station state");
            require(text.contains("\"category\":\"ai_event\""),
                    "brain log did not mirror AI decisions");
            require(text.contains("\"category\":\"unit_intent\""),
                    "brain log omitted an order/package transition");
            require(text.contains("\"category\":\"unit_cargo\""),
                    "brain log omitted unit cargo deltas");
            require(text.contains("\"category\":\"base_inventory\""),
                    "brain log omitted station inventory deltas");
            require(text.contains("\"category\":\"world_status\""),
                    "brain log omitted world-status context");
            require(text.contains("\"category\":\"session_end\""),
                    "brain log did not close its session cleanly");
            validateCompleteOrderedJsonl(text);
        } catch (IOException ex) {
            throw new IllegalStateException("brain log validator could not use its temp directory", ex);
        } finally {
            AiBrainLog.closeForTests();
            deleteTree(directory);
        }
    }

    private static void validateBoundedBackpressure() {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("starchem-ai-backpressure-");
            AiBrainLog.resetForTests(directory, 8);
            PlayerRegistry.reset("WAIT", "AI Backpressure Validator", 0x50BEFF);
            World world = new World("AI Backpressure Validator", Set.of(),
                    StarSystems.CORSAIR_SYSTEM_ID, false);
            world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
            AiBrainLog.observe(world);
            require(await(AiBrainLog::recording, 3_000),
                    "async writer did not open for backpressure validation");

            AiBrainLog.pauseWriterForTests(true);
            for (int i = 0; i < 500; i++) {
                AiBrainLog.event(world, "BENCH", "unit_position", "low-priority-" + i);
            }
            require(AiBrainLog.queueDepthForTests() <= 8,
                    "brain log queue exceeded its configured bound");
            require(AiBrainLog.droppedRecordsForTests() > 0,
                    "saturated queue did not record dropped or coalesced rows");
            AiBrainLog.event(world, "BENCH", "critical_event", "critical-row");

            AiBrainLog.pauseWriterForTests(false);
            require(AiBrainLog.awaitIdleForTests(5_000),
                    "brain log did not recover after queue backpressure");
            AiBrainLog.setEnabled(false);

            String text = readAll(jsonlFiles(directory));
            require(text.contains("\"category\":\"logger_backpressure\""),
                    "brain log omitted its backpressure warning");
            require(text.contains("\"category\":\"critical_event\""),
                    "high-priority event was lost behind low-priority queue pressure");
            validateCompleteOrderedJsonl(text);
        } catch (IOException ex) {
            throw new IllegalStateException("backpressure validator could not use its temp directory", ex);
        } finally {
            AiBrainLog.closeForTests();
            deleteTree(directory);
        }
    }

    private static void validateOpenFailureIsolation() {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("starchem-ai-open-failure-");
            Path blockingFile = directory.resolve("not-a-directory");
            Files.writeString(blockingFile, "blocked");
            AiBrainLog.resetForTests(blockingFile.resolve("child"));
            PlayerRegistry.reset("WAIT", "AI Failure Validator", 0x50BEFF);
            World world = new World("AI Failure Validator", Set.of(),
                    StarSystems.CORSAIR_SYSTEM_ID, false);
            AiBrainLog.observe(world);
            require(await(() -> AiBrainLog.status().startsWith("ERROR:"), 3_000),
                    "async writer failure was not isolated and reported");
            AiBrainLog.setEnabled(false);
            require("off".equals(AiBrainLog.status()),
                    "disabling after an async writer failure did not recover cleanly");
        } catch (IOException ex) {
            throw new IllegalStateException("failure validator could not prepare its path", ex);
        } finally {
            AiBrainLog.closeForTests();
            deleteTree(directory);
        }
    }

    private static void validateCompleteOrderedJsonl(String text) {
        Map<String, Long> lastBySession = new HashMap<>();
        for (String line : text.lines().toList()) {
            if (line.isBlank()) continue;
            require(line.startsWith("{") && line.endsWith("}"),
                    "brain log emitted a partial JSONL record");
            require(line.contains("\"schema\":1"),
                    "brain log record omitted schema version");
            Matcher session = SESSION.matcher(line);
            Matcher sequence = SEQUENCE.matcher(line);
            require(session.find() && sequence.find(),
                    "brain log record omitted ordering metadata");
            long current = Long.parseLong(sequence.group(1));
            long previous = lastBySession.getOrDefault(session.group(1), 0L);
            require(current == previous + 1,
                    "brain log sequence was not contiguous within a session");
            lastBySession.put(session.group(1), current);
        }
    }

    private static NpcFaction corsairs() {
        for (NpcFaction faction : NpcRules.factions()) {
            if (Config.CORSAIRS_ID.equals(faction.id())) return faction;
        }
        throw new IllegalStateException("Corsair faction is not configured");
    }

    private static List<Path> jsonlFiles(Path directory) throws IOException {
        List<Path> files = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) return files;
        try (var stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .sorted().forEach(files::add);
        }
        return files;
    }

    private static long totalBytes(Path directory) throws IOException {
        long total = 0;
        for (Path path : jsonlFiles(directory)) total += Files.size(path);
        return total;
    }

    private static String readAll(List<Path> files) throws IOException {
        StringBuilder out = new StringBuilder();
        for (Path path : files) out.append(Files.readString(path));
        return out.toString();
    }

    private static boolean await(Check check, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) return true;
            try { Thread.sleep(5L); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); return false; }
        }
        return check.ok();
    }

    private static void deleteTree(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @FunctionalInterface
    private interface Check { boolean ok(); }
}
'''
(ROOT / 'src/main/java/com/tndmadman/rts/AiBrainLogValidator.java').write_text(validator_source, encoding='utf-8')

docs_path = ROOT / 'docs/AI_BRAIN_LOG.md'
docs = docs_path.read_text(encoding='utf-8')
docs = replace_once(docs,
'''- Each segment rotates at approximately 16 MiB.
- Up to 24 recent `.jsonl` files are retained.
- The writer flushes at least every two seconds and on clean shutdown.
- Logging errors are shown in the AI developer panel and never propagate into the simulation.
''',
'''- Each segment rotates at approximately 16 MiB.
- Up to 24 recent `.jsonl` files are retained.
- JSON encoding, file writes, rotation, pruning, and flushes run on a dedicated daemon writer thread.
- The simulation submits immutable records through a bounded non-blocking queue.
- Under sustained pressure, position/checkpoint rows may be coalesced or dropped and one `logger_backpressure` record reports the loss.
- The writer flushes at least every two seconds and drains for a bounded period on clean shutdown.
- Logging errors are shown in the AI developer panel and never propagate into the simulation.
''', 'docs async section')
docs_path.write_text(docs, encoding='utf-8')

print('Phase 4 async logger repair applied.')
