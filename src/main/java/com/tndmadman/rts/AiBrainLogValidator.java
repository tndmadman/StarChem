package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class AiBrainLogValidator {
    private AiBrainLogValidator() { }

    public static void main(String[] args) throws Exception {
        validateOrThrow();
        System.out.println("StarChem AI brain log validation passed.");
    }

    static void validateOrThrow() {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("starchem-ai-brainlog-");
            AiBrainLog.resetForTests(directory);

            PlayerRegistry.reset("WAIT", "AI Brain Log Validator", 0x50BEFF);
            World world = new World("AI Brain Log Validator",
                    Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                    StarSystems.CORSAIR_SYSTEM_ID,
                    false);
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
            AiBrainLog.setEnabled(false);
            AiBrainLog.closeForTests();

            List<Path> files = jsonlFiles(directory);
            require(files.size() >= 2,
                    "re-enabling the brain log did not start a new session file");
            require(!files.isEmpty(), "brain log did not create a JSONL file");
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
            for (String line : text.lines().toList()) {
                if (line.isBlank()) continue;
                require(line.startsWith("{") && line.endsWith("}"),
                        "brain log emitted a partial JSONL record");
                require(line.contains("\"schema\":1"),
                        "brain log record omitted schema version");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("brain log validator could not use its temp directory", ex);
        } finally {
            AiBrainLog.closeForTests();
            deleteTree(directory);
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
                    .sorted()
                    .forEach(files::add);
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
}
