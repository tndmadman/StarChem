package com.tndmadman.rts;

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
            require(AiBrainLog.awaitIdleForTests(3_000),
                    "async writer did not drain startup rows before backpressure validation");

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
