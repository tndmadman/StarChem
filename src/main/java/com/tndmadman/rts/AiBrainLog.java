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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Structured, persistent AI journal used only by authoritative simulations
 * while the AI developer panel is active.
 *
 * The format is JSON Lines: every line is a complete event and remains useful
 * if the process is terminated before the writer closes. The journal records
 * immediate AI decisions through AiDevLog, plus periodic entity deltas and
 * strategic checkpoints from every simulated system.
 */
final class AiBrainLog {
    private static final int SCHEMA_VERSION = 1;
    private static final long ROTATE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_LOG_FILES = 24;
    private static final long FLUSH_NANOS = 2_000_000_000L;
    private static final double DELTA_SECONDS = 1.0;
    private static final double POSITION_SECONDS = 5.0;
    private static final double CHECKPOINT_SECONDS = 10.0;
    private static final double MATERIAL_EPSILON = 0.05;
    private static final Path DEFAULT_LOG_DIRECTORY = Path.of("logs", "ai-brain");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private static final Map<World, WorldMemory> MEMORIES = new WeakHashMap<>();
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

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(AiBrainLog::shutdown,
                "starchem-ai-brainlog-shutdown"));
    }

    private AiBrainLog() { }

    /** Enables logging only for the lifetime of an authoritative developer session. */
    static synchronized void setEnabled(boolean enabled) {
        if (devModeRequested == enabled) return;
        if (!enabled) {
            devModeRequested = false;
            shutdown();
            MEMORIES.clear();
            currentFile = null;
            sessionId = "";
            part = 0;
            bytesWritten = 0;
            sequence = 0;
            lastError = "";
            return;
        }
        devModeRequested = true;
        lastError = "";
    }

    static synchronized boolean recording() {
        return writer != null;
    }

    static synchronized String status() {
        if (!lastError.isBlank()) return "ERROR: " + lastError;
        if (writer != null && currentFile != null) {
            return "REC " + currentFile.toAbsolutePath().normalize();
        }
        return devModeRequested ? "waiting for authoritative AI tick" : "off";
    }

    static synchronized Path currentFile() {
        return currentFile;
    }

    /**
     * Called by the authoritative galaxy director after one system simulation.
     * Remote clients never execute that director, so they cannot create a
     * misleading second journal from synchronized view data.
     */
    static synchronized void observe(World world) {
        if (!devModeRequested || world == null || !lastError.isBlank()) return;
        if (!ensureOpen(world)) return;
        try {
            String systemId = safe(world.activeSystemId());
            WorldMemory worldMemory = MEMORIES.computeIfAbsent(world,
                    ignored -> new WorldMemory());
            SystemMemory memory = worldMemory.systems.computeIfAbsent(systemId,
                    ignored -> new SystemMemory());
            double gameTime = finite(world.systemTime(), 0.0);

            if (memory.initialized && gameTime + 0.001 < memory.lastGameTime) {
                write(world, "BRAIN", "system_time_reset", "",
                        "System time moved backward; resetting delta baseline.",
                        mapOf("previous", memory.lastGameTime, "current", gameTime));
                memory.clear();
            }
            memory.lastGameTime = gameTime;

            if (!memory.initialized || gameTime + 0.001 >= memory.nextDeltaTime) {
                captureEntityDeltas(world, memory);
                captureFactionState(world, memory);
                captureWorldStatus(world, memory);
                memory.nextDeltaTime = gameTime + DELTA_SECONDS;
                memory.initialized = true;
            }
            if (gameTime + 0.001 >= memory.nextPositionTime) {
                captureMovingPositions(world, memory);
                captureResourceDeltas(world, memory);
                memory.nextPositionTime = gameTime + POSITION_SECONDS;
            }
            if (gameTime + 0.001 >= memory.nextCheckpointTime) {
                checkpoint(world);
                memory.nextCheckpointTime = gameTime + CHECKPOINT_SECONDS;
            }
            flushIfDue();
        } catch (RuntimeException ex) {
            fail("capture failed: " + compactException(ex));
        }
    }

    static synchronized void event(World world, NpcFaction faction,
                                   String category, String message) {
        event(world, faction == null ? "AI" : faction.id(), category, message);
    }

    static synchronized void event(World world, String source,
                                   String category, String message) {
        if (!devModeRequested || !lastError.isBlank()) return;
        if (writer == null) {
            if (world == null || !ensureOpen(world)) return;
        }
        write(world, safe(source), safe(category), "", safe(message), Map.of());
        flushIfDue();
    }

    private static void captureEntityDeltas(World world, SystemMemory memory) {
        Map<String, UnitView> currentUnits = new LinkedHashMap<>();
        for (Unit unit : world.units.values()) {
            UnitView next = UnitView.from(unit);
            currentUnits.put(unit.key(), next);
            UnitView previous = memory.units.get(unit.key());
            if (previous == null) {
                write(world, unit.playerId, "unit_spawn", unit.key(),
                        unit.shipTypeId + " entered the system", next.data());
                continue;
            }
            if (!previous.intentEquals(next)) {
                write(world, unit.playerId, "unit_intent", unit.key(),
                        "Unit order or assignment changed",
                        mapOf("before", previous.intentData(), "after", next.intentData()));
            }
            if (Math.abs(previous.hp - next.hp) > 0.1
                    || Math.abs(previous.shield - next.shield) > 0.1) {
                write(world, unit.playerId, "unit_health", unit.key(),
                        "Unit health changed",
                        mapOf("hpBefore", previous.hp, "hpAfter", next.hp,
                                "shieldBefore", previous.shield, "shieldAfter", next.shield));
            }
            Map<String, Object> cargoDelta = materialDelta(previous.cargo, next.cargo);
            if (!cargoDelta.isEmpty()) {
                write(world, unit.playerId, "unit_cargo", unit.key(),
                        "Unit cargo changed", cargoDelta);
            }
        }
        for (Map.Entry<String, UnitView> entry : memory.units.entrySet()) {
            if (!currentUnits.containsKey(entry.getKey())) {
                write(world, entry.getValue().owner, "unit_removed", entry.getKey(),
                        "Unit left the system or was destroyed", entry.getValue().data());
            }
        }
        memory.units = currentUnits;

        Map<String, BaseView> currentBases = new LinkedHashMap<>();
        for (Base base : world.bases.values()) {
            BaseView next = BaseView.from(base);
            currentBases.put(base.id, next);
            BaseView previous = memory.bases.get(base.id);
            if (previous == null) {
                write(world, base.playerId, "base_spawn", base.id,
                        base.typeId + " entered the system", next.data());
                continue;
            }
            if (Math.abs(previous.hp - next.hp) > 0.1
                    || Math.abs(previous.shield - next.shield) > 0.1) {
                write(world, base.playerId, "base_health", base.id,
                        "Station health changed",
                        mapOf("hpBefore", previous.hp, "hpAfter", next.hp,
                                "shieldBefore", previous.shield, "shieldAfter", next.shield));
            }
            Map<String, Object> inventoryDelta = materialDelta(
                    previous.inventory, next.inventory);
            if (!inventoryDelta.isEmpty()) {
                write(world, base.playerId, "base_inventory", base.id,
                        "Station inventory changed", inventoryDelta);
            }
            if (!previous.queue.equals(next.queue)) {
                write(world, base.playerId, "production_queue", base.id,
                        "Production queue changed",
                        mapOf("before", previous.queue, "after", next.queue));
            }
            if (!Objects.equals(previous.logistics, next.logistics)) {
                write(world, base.playerId, "base_logistics", base.id,
                        "Station logistics status changed",
                        mapOf("before", previous.logistics, "after", next.logistics));
            }
        }
        for (Map.Entry<String, BaseView> entry : memory.bases.entrySet()) {
            if (!currentBases.containsKey(entry.getKey())) {
                write(world, entry.getValue().owner, "base_removed", entry.getKey(),
                        "Station left the system or was destroyed", entry.getValue().data());
            }
        }
        memory.bases = currentBases;
    }

    private static void captureMovingPositions(World world, SystemMemory memory) {
        for (Unit unit : world.units.values()) {
            if (unit.task != UnitTask.MOVE
                    && unit.task != UnitTask.RETURN_TO_STATION
                    && unit.task != UnitTask.ATTACK) continue;
            UnitView current = UnitView.from(unit);
            UnitView previous = memory.lastPositionLog.get(unit.key());
            if (previous == null
                    || Calc.distance(previous.x, previous.y, current.x, current.y) >= 5.0
                    || !previous.intentEquals(current)) {
                write(world, unit.playerId, "unit_position", unit.key(),
                        "Moving unit position checkpoint",
                        mapOf("x", current.x, "y", current.y,
                                "targetX", current.targetX, "targetY", current.targetY,
                                "task", current.task, "order", current.orderType,
                                "attackTarget", current.attackTarget));
                memory.lastPositionLog.put(unit.key(), current);
            }
        }
        memory.lastPositionLog.keySet().removeIf(key -> !world.units.containsKey(key));
    }

    private static void captureResourceDeltas(World world, SystemMemory memory) {
        Map<Integer, ResourceView> current = new LinkedHashMap<>();
        for (ResourceNode node : world.resources) {
            ResourceView next = ResourceView.from(node);
            current.put(node.id, next);
            ResourceView previous = memory.resources.get(node.id);
            if (previous == null) {
                write(world, "WORLD", "resource_seen", Integer.toString(node.id),
                        "Resource node observed", next.data());
            } else if (previous.active != next.active
                    || Math.abs(previous.amount - next.amount) >= 5.0) {
                write(world, "WORLD", "resource_change", Integer.toString(node.id),
                        "Resource node changed",
                        mapOf("material", next.material,
                                "activeBefore", previous.active, "activeAfter", next.active,
                                "amountBefore", previous.amount, "amountAfter", next.amount));
            }
        }
        for (Map.Entry<Integer, ResourceView> entry : memory.resources.entrySet()) {
            if (!current.containsKey(entry.getKey())) {
                write(world, "WORLD", "resource_removed",
                        Integer.toString(entry.getKey()),
                        "Resource node disappeared", entry.getValue().data());
            }
        }
        memory.resources = current;
    }

    private static void captureFactionState(World world, SystemMemory memory) {
        for (NpcFaction faction : NpcRules.factions()) {
            if (!faction.enabled() || faction.behavior() != NpcBehavior.FACTION) continue;
            String home = NpcFactionRuntime.homeSystemIdFor(faction);
            if (!safe(world.activeSystemId()).equals(home)) continue;
            String summary = String.join(" | ", AiDevSnapshot.summary(world, faction));
            String previous = memory.factionSummary.put(faction.id(), summary);
            if (!Objects.equals(previous, summary)) {
                write(world, faction.id(), "faction_state", faction.id(),
                        "Strategic checkpoint changed",
                        mapOf("previous", previous == null ? "" : previous,
                                "current", summary));
            }
        }
    }

    private static void captureWorldStatus(World world, SystemMemory memory) {
        String status = safe(world.status);
        if (!Objects.equals(memory.lastStatus, status)) {
            write(world, "WORLD", "world_status", "", "World status changed",
                    mapOf("before", memory.lastStatus, "after", status));
            memory.lastStatus = status;
        }
        String research = researchSignature(world);
        if (!Objects.equals(memory.lastResearch, research)) {
            write(world, "WORLD", "research_state", "",
                    "Completed research changed",
                    mapOf("before", memory.lastResearch, "after", research));
            memory.lastResearch = research;
        }
        String devSettings = devSettingsSignature();
        if (!Objects.equals(memory.lastDevSettings, devSettings)) {
            write(world, "DEV", "dev_settings", "",
                    "AI developer settings changed",
                    mapOf("before", memory.lastDevSettings, "after", devSettings));
            memory.lastDevSettings = devSettings;
        }
    }

    private static String devSettingsSignature() {
        return "pauseAi=" + AiDevSettings.pauseAi
                + ",stepAi=" + AiDevSettings.stepAi
                + ",fastAi=" + AiDevSettings.fastAi
                + ",freezePlayerUnits=" + AiDevSettings.freezePlayerUnits
                + ",freezeNpcCombat=" + AiDevSettings.freezeNpcCombat
                + ",disableAttacks=" + AiDevSettings.disableAttacks
                + ",disableEconomy=" + AiDevSettings.disableEconomy
                + ",difficulty=" + NpcDifficultyPreset.current().name();
    }

    private static void checkpoint(World world) {
        Map<String, Integer> unitsByOwner = new LinkedHashMap<>();
        Map<String, Integer> basesByOwner = new LinkedHashMap<>();
        for (Unit unit : world.units.values()) {
            unitsByOwner.merge(unit.playerId, 1, Integer::sum);
        }
        for (Base base : world.bases.values()) {
            basesByOwner.merge(base.playerId, 1, Integer::sum);
        }
        write(world, "BRAIN", "system_checkpoint", safe(world.activeSystemId()),
                "Periodic system checkpoint",
                mapOf("controller", safe(world.activeSystemControllerId()),
                        "units", unitsByOwner,
                        "bases", basesByOwner,
                        "resources", world.resources.size(),
                        "activeResources", activeResourceCount(world),
                        "shots", world.shots.size(),
                        "items", world.items.size(),
                        "wormholes", world.wormholes.size(),
                        "devSettings", devSettingsSignature(),
                        "status", safe(world.status)));
    }

    private static int activeResourceCount(World world) {
        int count = 0;
        for (ResourceNode node : world.resources) if (node.active && node.amount > 0.05) count++;
        return count;
    }

    private static String researchSignature(World world) {
        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : world.completedResearch.entrySet()) {
            List<String> topics = new ArrayList<>(entry.getValue());
            topics.sort(String::compareTo);
            rows.add(entry.getKey() + "=" + String.join(",", topics));
        }
        rows.sort(String::compareTo);
        return String.join(";", rows);
    }

    private static boolean ensureOpen(World world) {
        if (writer != null) return true;
        if (!lastError.isBlank()) return false;
        try {
            Files.createDirectories(logDirectory);
            pruneOldFiles();
            sessionId = FILE_TIME.format(Instant.now())
                    + "-p" + ProcessHandle.current().pid()
                    + "-" + Long.toUnsignedString(System.nanoTime(), 36);
            part = 0;
            sequence = 0;
            openNextPart();
            write(world, "BRAIN", "session_start", "",
                    "Authoritative AI brain log started",
                    mapOf("schema", SCHEMA_VERSION,
                            "world", world.localPlayerName,
                            "system", safe(world.activeSystemId()),
                            "seed", world.systemSeed(),
                            "difficulty", NpcDifficultyPreset.current().label,
                            "directory", logDirectory.toAbsolutePath().normalize().toString()));
            flushNow();
            return true;
        } catch (IOException | RuntimeException ex) {
            fail("could not open log: " + compactException(ex));
            return false;
        }
    }

    private static void openNextPart() throws IOException {
        closeWriterOnly();
        part++;
        String name = "starchem-ai-" + sessionId
                + "-part" + String.format(Locale.ROOT, "%03d", part) + ".jsonl";
        currentFile = logDirectory.resolve(name);
        writer = Files.newBufferedWriter(currentFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        bytesWritten = 0;
        lastFlushNanos = System.nanoTime();
    }

    private static void rotate(World world) throws IOException {
        long previousBytes = bytesWritten;
        int previousPart = part;
        flushNow();
        openNextPart();
        Map<String, Object> row = baseRow(world, "BRAIN", "session_continue", "",
                "AI brain log rotated");
        row.put("data", mapOf("previousPart", previousPart,
                "previousBytes", previousBytes, "newPart", part));
        writeRow(row);
        pruneOldFiles();
    }

    private static void write(World world, String source, String category,
                              String entity, String message,
                              Map<String, ?> data) {
        if (writer == null) return;
        try {
            Map<String, Object> row = baseRow(world, source, category, entity, message);
            row.put("data", data == null ? Map.of() : data);
            int bytes = encodedBytes(row);
            if (bytesWritten > 0 && bytesWritten + bytes > ROTATE_BYTES) {
                rotate(world);
                row = baseRow(world, source, category, entity, message);
                row.put("data", data == null ? Map.of() : data);
            }
            writeRow(row);
        } catch (IOException | RuntimeException ex) {
            fail("write failed: " + compactException(ex));
        }
    }

    private static Map<String, Object> baseRow(World world, String source,
                                                String category, String entity,
                                                String message) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("schema", SCHEMA_VERSION);
        row.put("seq", ++sequence);
        row.put("utc", Instant.now().toString());
        row.put("session", sessionId);
        row.put("part", part);
        row.put("source", safe(source));
        row.put("category", safe(category));
        row.put("entity", safe(entity));
        row.put("message", safe(message));
        if (world != null) {
            row.put("world", safe(world.localPlayerName));
            row.put("system", safe(world.activeSystemId()));
            row.put("systemName", safe(world.systemName()));
            row.put("gameTime", round(world.systemTime(), 3));
            row.put("seed", world.systemSeed());
        }
        return row;
    }

    private static int encodedBytes(Map<String, Object> row) {
        return (json(row) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8).length;
    }

    private static void writeRow(Map<String, Object> row) throws IOException {
        if (writer == null) return;
        String encoded = json(row) + System.lineSeparator();
        writer.write(encoded);
        bytesWritten += encoded.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void flushIfDue() {
        if (writer == null) return;
        if (System.nanoTime() - lastFlushNanos < FLUSH_NANOS) return;
        flushNow();
    }

    private static void flushNow() {
        if (writer == null) return;
        try {
            writer.flush();
            lastFlushNanos = System.nanoTime();
        } catch (IOException ex) {
            fail("flush failed: " + compactException(ex));
        }
    }

    private static void pruneOldFiles() {
        try {
            if (!Files.isDirectory(logDirectory)) return;
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                    logDirectory, "starchem-ai-*.jsonl")) {
                for (Path path : stream) if (Files.isRegularFile(path)) files.add(path);
            }
            files.sort(Comparator.comparingLong(AiBrainLog::modifiedTime).reversed());
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

    private static void shutdown() {
        synchronized (AiBrainLog.class) {
            if (writer != null) {
                write(null, "BRAIN", "session_end", "",
                        "AI brain log closed", mapOf("lines", sequence));
                flushNow();
            }
            closeWriterOnly();
        }
    }

    private static void closeWriterOnly() {
        if (writer == null) return;
        try { writer.close(); }
        catch (IOException ignored) { }
        writer = null;
    }

    private static void fail(String message) {
        lastError = safe(message);
        closeWriterOnly();
    }

    private static String compactException(Throwable ex) {
        if (ex == null) return "unknown error";
        String message = ex.getMessage();
        return ex.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static Map<String, Object> materialDelta(Map<String, Double> before,
                                                     Map<String, Double> after) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        Map<String, Object> delta = new LinkedHashMap<>();
        for (String key : keys) {
            double oldValue = before.getOrDefault(key, 0.0);
            double newValue = after.getOrDefault(key, 0.0);
            double change = newValue - oldValue;
            if (Math.abs(change) <= MATERIAL_EPSILON) continue;
            delta.put(key, mapOf("before", round(oldValue, 2),
                    "after", round(newValue, 2), "delta", round(change, 2)));
        }
        return delta;
    }

    private static Map<String, Double> materials(EnumMap<Material, Double> source) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (Material material : Material.values()) {
            double amount = source.getOrDefault(material, 0.0);
            if (Math.abs(amount) > MATERIAL_EPSILON) {
                result.put(material.name(), round(amount, 2));
            }
        }
        return result;
    }

    private static List<String> queue(Base base) {
        List<String> rows = new ArrayList<>();
        for (ProductionJob job : base.productionQueue) {
            rows.add(job.id + ":" + job.kind + ":" + job.itemId
                    + ":remaining=" + round(job.remaining, 2)
                    + ":reserved=" + job.resourcesReserved
                    + ":builder=" + safe(job.reservedUnitKey)
                    + ":blocked=" + safe(job.blockedReason));
        }
        return List.copyOf(rows);
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

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private static double round(double value, int places) {
        if (!Double.isFinite(value)) return 0.0;
        double scale = Math.pow(10.0, Math.max(0, places));
        return Math.round(value * scale) / scale;
    }

    /** Test hook; production code never changes the configured directory. */
    static synchronized void resetForTests(Path directory) {
        shutdown();
        MEMORIES.clear();
        logDirectory = directory;
        devModeRequested = true;
        currentFile = null;
        sessionId = "";
        part = 0;
        bytesWritten = 0;
        sequence = 0;
        lastError = "";
    }

    static synchronized void closeForTests() {
        shutdown();
        devModeRequested = false;
        MEMORIES.clear();
        logDirectory = DEFAULT_LOG_DIRECTORY;
        currentFile = null;
        sessionId = "";
        part = 0;
        bytesWritten = 0;
        sequence = 0;
        lastError = "";
    }

    private static final class WorldMemory {
        final Map<String, SystemMemory> systems = new LinkedHashMap<>();
    }

    private static final class SystemMemory {
        boolean initialized;
        double lastGameTime;
        double nextDeltaTime;
        double nextPositionTime;
        double nextCheckpointTime;
        Map<String, UnitView> units = new LinkedHashMap<>();
        Map<String, BaseView> bases = new LinkedHashMap<>();
        Map<Integer, ResourceView> resources = new LinkedHashMap<>();
        final Map<String, UnitView> lastPositionLog = new LinkedHashMap<>();
        final Map<String, String> factionSummary = new LinkedHashMap<>();
        String lastStatus = "";
        String lastResearch = "";
        String lastDevSettings = "";

        void clear() {
            initialized = false;
            nextDeltaTime = 0;
            nextPositionTime = 0;
            nextCheckpointTime = 0;
            units.clear();
            bases.clear();
            resources.clear();
            lastPositionLog.clear();
            factionSummary.clear();
            lastStatus = "";
            lastResearch = "";
            lastDevSettings = "";
        }
    }

    private record UnitView(String owner, int unitId, String type,
                            double x, double y, double targetX, double targetY,
                            double hp, double shield, String task,
                            String orderType, String orderTarget,
                            String attackTarget, int resourceId,
                            String packageType, String logisticsBase,
                            String logisticsRequest, Map<String, Double> cargo) {
        static UnitView from(Unit unit) {
            return new UnitView(unit.playerId, unit.unitId, unit.shipTypeId,
                    round(unit.x, 2), round(unit.y, 2),
                    round(unit.targetX, 2), round(unit.targetY, 2),
                    round(unit.hp, 2), round(unit.shield, 2),
                    unit.task.name(), unit.orderType.name(), safe(unit.orderTarget),
                    safe(unit.attackTarget), unit.automationResourceId,
                    safe(unit.basePackageType), safe(unit.logisticsTargetBaseId),
                    safe(unit.logisticsRequestId), materials(unit.inventory));
        }

        boolean intentEquals(UnitView other) {
            return other != null
                    && task.equals(other.task)
                    && orderType.equals(other.orderType)
                    && orderTarget.equals(other.orderTarget)
                    && attackTarget.equals(other.attackTarget)
                    && resourceId == other.resourceId
                    && packageType.equals(other.packageType)
                    && logisticsBase.equals(other.logisticsBase)
                    && logisticsRequest.equals(other.logisticsRequest)
                    && Math.abs(targetX - other.targetX) < 0.1
                    && Math.abs(targetY - other.targetY) < 0.1;
        }

        Map<String, Object> intentData() {
            return mapOf("task", task, "order", orderType,
                    "orderTarget", orderTarget, "attackTarget", attackTarget,
                    "resourceId", resourceId, "package", packageType,
                    "logisticsBase", logisticsBase,
                    "logisticsRequest", logisticsRequest,
                    "targetX", targetX, "targetY", targetY);
        }

        Map<String, Object> data() {
            return mapOf("owner", owner, "unitId", unitId, "type", type,
                    "x", x, "y", y, "targetX", targetX, "targetY", targetY,
                    "hp", hp, "shield", shield, "intent", intentData(),
                    "cargo", cargo);
        }
    }

    private record BaseView(String owner, String type, double x, double y,
                            double hp, double shield,
                            Map<String, Double> inventory,
                            List<String> queue, String logistics) {
        static BaseView from(Base base) {
            return new BaseView(base.playerId, base.typeId,
                    round(base.x, 2), round(base.y, 2),
                    round(base.hp, 2), round(base.shield, 2),
                    materials(base.inventory), AiBrainLog.queue(base), safe(base.logisticsStatus));
        }

        Map<String, Object> data() {
            return mapOf("owner", owner, "type", type, "x", x, "y", y,
                    "hp", hp, "shield", shield, "inventory", inventory,
                    "queue", queue, "logistics", logistics);
        }
    }

    private record ResourceView(String material, boolean active,
                                double amount, double x, double y) {
        static ResourceView from(ResourceNode node) {
            return new ResourceView(node.material.name(), node.active,
                    round(node.amount, 2), round(node.x, 2), round(node.y, 2));
        }

        Map<String, Object> data() {
            return mapOf("material", material, "active", active,
                    "amount", amount, "x", x, "y", y);
        }
    }
}
