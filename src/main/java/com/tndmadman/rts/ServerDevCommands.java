package com.tndmadman.rts;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Trusted local-console developer and recovery commands. */
final class ServerDevCommands {
    private static final int MAX_MASS_TARGETS = 250;
    private static final int MAX_SPAWN_COUNT = 50;
    private static final double MAX_RESOURCE_AMOUNT = 100_000.0;
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withLocale(Locale.ROOT).withZone(ZoneOffset.UTC);

    private ServerDevCommands() { }

    static List<String> execute(HeadlessGameServer host, List<String> supplied) {
        List<String> args = supplied == null ? List.of() : supplied;
        if (args.isEmpty() || "status".equalsIgnoreCase(args.get(0))) return status(host);
        String group = args.get(0).toLowerCase(Locale.ROOT);
        if ("mode".equals(group)) return mode(host, args);
        if (!host.network.runtimeDevEnabled()) {
            return List.of("Runtime developer mode is disabled. Use 'dev mode on' from the local server console.");
        }
        return switch (group) {
            case "access" -> access(host, args);
            case "freebuild" -> freebuild(host, args);
            case "resource", "resources" -> resources(host, args);
            case "research" -> researchMutation(host, args);
            case "ai" -> ai(host, args);
            case "timers" -> timers(host, args);
            case "faction" -> faction(host, args);
            case "production" -> production(host, args);
            case "asset" -> asset(host, args);
            case "player" -> player(host, args);
            case "spawn" -> spawn(host, args);
            case "trigger" -> legacyTrigger(host, args);
            case "remove" -> legacyRemove(host, args);
            case "reset" -> legacyReset(host, args);
            default -> List.of("Usage: dev <status|mode|access|freebuild|resource|research|ai|timers|faction|production|asset|player|spawn> ...");
        };
    }

    static List<String> researchInspect(HeadlessGameServer host, List<String> supplied) {
        List<String> args = supplied == null ? List.of() : supplied;
        if (args.isEmpty() || "topics".equalsIgnoreCase(args.get(0))) {
            if (args.size() > 1) return List.of("Usage: research topics");
            ArrayList<String> lines = new ArrayList<>();
            for (ResearchTopic topic : ResearchRules.all()) {
                lines.add(topic.id + " | " + topic.name + " | " + fmt(topic.timeSeconds) + "s | requires "
                        + (topic.requires.isEmpty() ? "none" : String.join(",", topic.requires)) + " | " + topic.unlockLabel());
            }
            return cap(lines, "research topics");
        }
        String action = args.get(0).toLowerCase(Locale.ROOT);
        if ("topic".equals(action) && args.size() == 2) {
            ResearchTopic topic = ResearchRules.topic(args.get(1));
            if (topic == null) return List.of("Unknown research topic: " + args.get(1));
            return List.of(topic.id + " | " + topic.name, topic.description,
                    "Stations: " + (topic.stationTypes.isEmpty() ? "none" : String.join(", ", topic.stationTypes)),
                    "Requires: " + (topic.requires.isEmpty() ? "none" : String.join(", ", topic.requires)),
                    "Cost: " + Rules.formatCost(topic.requiredResources), "Time: " + fmt(topic.timeSeconds) + "s", topic.unlockLabel());
        }
        if (!List.of("status", "completed", "queued", "available", "blocked").contains(action) || args.size() != 2) {
            return List.of("Usage: research <topics|topic <topic>|status|completed|queued|available|blocked <player>>");
        }
        String playerId = resolvePlayer(host.network, args.get(1));
        if (playerId.isBlank()) return List.of("Unknown player session: " + args.get(1));
        Set<String> completed = host.world.completedResearch.getOrDefault(playerId, Set.of());
        Map<String,ProductionJob> queued = queuedResearch(host.world, playerId);
        ArrayList<String> lines = new ArrayList<>();
        for (ResearchTopic topic : ResearchRules.all()) {
            boolean done = completed.contains(topic.id);
            ProductionJob job = queued.get(topic.id);
            String missing = ResearchRules.missingPrerequisite(host.world, playerId, topic);
            boolean available = !done && job == null && missing.isBlank();
            boolean blocked = !done && job == null && !missing.isBlank();
            if ("completed".equals(action) && !done) continue;
            if ("queued".equals(action) && job == null) continue;
            if ("available".equals(action) && !available) continue;
            if ("blocked".equals(action) && !blocked) continue;
            if ("status".equals(action) || done || job != null || available || blocked) {
                String state = done ? "completed" : job != null ? "queued " + fmt(Math.max(0, job.remaining)) + "s"
                        : available ? "available" : "blocked by " + missing;
                lines.add(topic.id + " | " + topic.name + " | " + state);
            }
        }
        if ("status".equals(action)) lines.add(0, playerId + " | completed " + completed.size() + " | queued " + queued.size());
        return cap(lines, "research entries");
    }

    static List<String> diagnostics(HeadlessGameServer host, String command, List<String> supplied) {
        List<String> args = supplied == null ? List.of() : supplied;
        return switch (command) {
            case "threads" -> threads(args);
            case "memory" -> memory(args);
            case "gc-status" -> gcStatus(args);
            case "dump" -> dump(host, args);
            case "tell" -> tell(host, args);
            case "notice" -> notice(host, args);
            case "observations" -> observations(host, args);
            default -> List.of("Unknown diagnostics command: " + command);
        };
    }

    private static List<String> status(HeadlessGameServer host) {
        PeerNetwork network = host.network;
        int granted = network.runtimeDevAccessCount();
        int freebuild = network.runtimeFreeBuildCount();
        return List.of(
                "Runtime developer mode: " + (network.runtimeDevEnabled() ? "enabled" : "disabled")
                        + " | startup " + (network.serverConfig().devMode ? "enabled" : "disabled"),
                "Remote developer grants: " + granted + " | free-build players " + freebuild,
                "AI: " + (host.world.aiDevSettings.pauseAi ? "paused" : "running") + " | speed " + (host.world.aiDevSettings.fastAi ? "fast" : "normal"),
                "Combat: players frozen " + host.world.aiDevSettings.freezePlayerUnits + " | NPC combat frozen " + host.world.aiDevSettings.freezeNpcCombat,
                "Rules: attacks disabled " + host.world.aiDevSettings.disableAttacks + " | economy disabled " + host.world.aiDevSettings.disableEconomy,
                "Production timers: " + (DevTimerSettings.disabled(host.world) ? "disabled" : "enabled"),
                "Difficulty: " + host.world.aiDevSettings.difficultyPreset().name().toLowerCase(Locale.ROOT) + " / " + host.world.aiDevSettings.difficultyPreset().label);
    }

    private static List<String> mode(HeadlessGameServer host, List<String> args) {
        if (args.size() == 1 || args.size() == 2 && "status".equalsIgnoreCase(args.get(1))) return status(host);
        if (args.size() == 2 && "on".equalsIgnoreCase(args.get(1))) {
            if (host.network.runtimeDevEnabled()) return List.of("Runtime developer mode is already enabled.");
            host.network.setRuntimeDevEnabled(true);
            host.network.serverJournal().add("DEV_MODE", "server", "enabled");
            return List.of("Runtime developer mode enabled for this process. It will reset to the startup setting after restart.");
        }
        if (args.size() >= 2 && "off".equalsIgnoreCase(args.get(1))) {
            if (host.network.runtimeDevAccessCount() > 0 && (args.size() != 3 || !"confirm".equalsIgnoreCase(args.get(2)))) {
                return List.of("Remote developer access is active. Use 'dev mode off confirm' to revoke all grants and disable developer controls.");
            }
            host.network.setRuntimeDevEnabled(false);
            host.network.serverJournal().add("DEV_MODE", "server", "disabled and reset");
            return List.of("Runtime developer mode disabled. Remote grants and free-build were revoked; AI and timer state were reset.");
        }
        return List.of("Usage: dev mode <status|on|off [confirm]>");
    }

    private static List<String> access(HeadlessGameServer host, List<String> args) {
        if (args.size() == 1 || args.size() == 2 && "list".equalsIgnoreCase(args.get(1))) {
            ArrayList<String> lines = new ArrayList<>();
            for (DevPeerAccess peer : host.network.devAccessPeers()) {
                boolean granted = host.network.runtimeDevAccessGranted(peer.playerId());
                lines.add(peer.playerId() + " | " + peer.name() + " | requested " + peer.requested()
                        + " | granted " + granted + " | freebuild " + host.network.runtimeFreeBuildEnabled(peer.playerId())
                        + " | local " + peer.local());
            }
            return lines.isEmpty() ? List.of("No connected developer candidates.") : List.copyOf(lines);
        }
        String action = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "";
        if ("requests".equals(action) && args.size() == 2) {
            ArrayList<String> lines = new ArrayList<>();
            for (DevPeerAccess peer : host.network.devAccessPeers()) if (peer.requested()) {
                lines.add(peer.playerId() + " | " + peer.name() + " | granted " + host.network.runtimeDevAccessGranted(peer.playerId()));
            }
            return lines.isEmpty() ? List.of("No connected players requested developer access.") : List.copyOf(lines);
        }
        if ("revoke-all".equals(action) && args.size() == 2) {
            int count = host.network.revokeAllRuntimeDevAccess();
            return List.of("Revoked developer access from " + count + " player" + (count == 1 ? "" : "s") + ".");
        }
        if (("grant".equals(action) || "revoke".equals(action)) && args.size() == 3) {
            String playerId = resolvePlayer(host.network, args.get(2));
            if (playerId.isBlank()) return List.of("Unknown player session: " + args.get(2));
            if (!host.network.serverSessionConnected(playerId)) return List.of(playerId + " is not connected.");
            boolean enabled = "grant".equals(action);
            host.network.setRemoteDevAccess(playerId, enabled);
            return List.of("Developer access " + (enabled ? "granted to " : "revoked from ") + playerId + ". Free-build is managed separately.");
        }
        return List.of("Usage: dev access <list|requests|grant <player>|revoke <player>|revoke-all>");
    }

    private static List<String> freebuild(HeadlessGameServer host, List<String> args) {
        if (args.size() == 3 && "status".equalsIgnoreCase(args.get(1))) {
            String playerId = resolvePlayer(host.network, args.get(2));
            if (playerId.isBlank()) return List.of("Unknown player session: " + args.get(2));
            return List.of("Free-build for " + playerId + ": " + (host.network.runtimeFreeBuildEnabled(playerId) ? "enabled" : "disabled"));
        }
        if (args.size() == 3) {
            String playerId = resolvePlayer(host.network, args.get(1));
            Boolean enabled = flag(args.get(2));
            if (playerId.isBlank()) return List.of("Unknown player session: " + args.get(1));
            if (enabled == null) return List.of("Usage: dev freebuild <player> <on|off>");
            host.network.setServerFreeBuild(playerId, enabled);
            return List.of("Free-build " + (enabled ? "enabled for " : "disabled for ") + playerId + ".");
        }
        return List.of("Usage: dev freebuild <status <player>|player <on|off>>");
    }

    private static List<String> resources(HeadlessGameServer host, List<String> args) {
        if (args.size() == 1 || args.size() == 2 && "list".equalsIgnoreCase(args.get(1))) {
            ArrayList<String> lines = new ArrayList<>();
            for (Material material : Material.values()) lines.add(material.name().toLowerCase(Locale.ROOT) + " | " + material.label);
            return cap(lines, "materials");
        }
        String action = args.get(1).toLowerCase(Locale.ROOT);
        if ("inspect".equals(action) && args.size() == 3) {
            LocatedBase located = findBase(host.world, args.get(2));
            if (located == null) return List.of("Unknown base: " + args.get(2));
            ArrayList<String> lines = new ArrayList<>();
            lines.add(located.systemId + " | " + located.base.id + " | owner " + located.base.playerId);
            for (Material material : Material.values()) {
                double amount = located.base.inventory.getOrDefault(material, 0.0);
                if (amount > 0) lines.add(material.name().toLowerCase(Locale.ROOT) + " = " + fmt(amount));
            }
            return lines.size() == 1 ? List.of(lines.get(0), "Inventory is empty.") : List.copyOf(lines);
        }
        if (List.of("add", "remove", "set").contains(action) && args.size() == 6) {
            String playerId = resolvePlayer(host.network, args.get(2));
            Material material = material(args.get(4));
            Double amount = amount(args.get(5));
            if (playerId.isBlank()) return List.of("Unknown player session: " + args.get(2));
            if (material == null) return List.of("Unknown material: " + args.get(4));
            if (amount == null) return List.of("Amount must be finite and between 0 and " + (long)MAX_RESOURCE_AMOUNT + ".");
            String target = args.get(3);
            int changed = mutatePlayerBases(host.world, playerId, target, base -> {
                double current = base.inventory.getOrDefault(material, 0.0);
                double next = switch (action) {
                    case "add" -> Math.min(MAX_RESOURCE_AMOUNT, current + amount);
                    case "remove" -> Math.max(0, current - amount);
                    default -> amount;
                };
                setInventory(base.inventory, material, next);
            });
            if (changed == 0) return List.of("No matching owned base: " + target);
            changed(host, "RESOURCE", playerId, action + " " + material.name() + " " + amount + " at " + target);
            return List.of("Updated " + material.label + " on " + changed + " base" + (changed == 1 ? "" : "s") + ".");
        }
        if ("add-all".equals(action) && args.size() == 5) {
            String playerId = resolvePlayer(host.network, args.get(2));
            Double amount = amount(args.get(4));
            if (playerId.isBlank()) return List.of("Unknown player session: " + args.get(2));
            if (amount == null) return List.of("Amount must be finite and between 0 and " + (long)MAX_RESOURCE_AMOUNT + ".");
            int changed = mutatePlayerBases(host.world, playerId, args.get(3), base -> {
                for (Material material : Material.values()) {
                    setInventory(base.inventory, material, Math.min(MAX_RESOURCE_AMOUNT,
                            base.inventory.getOrDefault(material, 0.0) + amount));
                }
            });
            if (changed == 0) return List.of("No matching owned base: " + args.get(3));
            changed(host, "RESOURCE", playerId, "add-all " + amount + " at " + args.get(3));
            return List.of("Added " + fmt(amount) + " of every material to " + changed + " base" + (changed == 1 ? "" : "s") + ".");
        }
        if ("fill".equals(action) && (args.size() == 4 || args.size() == 5)) {
            String playerId = resolvePlayer(host.network, args.get(2));
            double fill = args.size() == 5 ? parseAmountOr(args.get(4), -1) : 1_000;
            if (playerId.isBlank()) return List.of("Unknown player session: " + args.get(2));
            if (fill < 0) return List.of("Fill amount is invalid.");
            int changed = mutatePlayerBases(host.world, playerId, args.get(3), base -> {
                for (Material material : Material.values()) setInventory(base.inventory, material, fill);
            });
            if (changed == 0) return List.of("No matching owned base: " + args.get(3));
            changed(host, "RESOURCE", playerId, "fill " + fill + " at " + args.get(3));
            return List.of("Set every material to " + fmt(fill) + " on " + changed + " base" + (changed == 1 ? "" : "s") + ".");
        }
        if ("clear".equals(action) && args.size() == 5 && "confirm".equalsIgnoreCase(args.get(4))) {
            String playerId = resolvePlayer(host.network, args.get(2));
            if (playerId.isBlank()) return List.of("Unknown player session: " + args.get(2));
            int changed = mutatePlayerBases(host.world, playerId, args.get(3), base -> base.inventory.clear());
            if (changed == 0) return List.of("No matching owned base: " + args.get(3));
            changed(host, "RESOURCE", playerId, "cleared " + args.get(3));
            return List.of("Cleared inventory on " + changed + " base" + (changed == 1 ? "" : "s") + ".");
        }
        return List.of("Usage: dev resource <list|inspect <base>|add|remove|set <player> <base|all-bases> <material> <amount>|add-all <player> <base|all-bases> <amount>|fill <player> <base|all-bases> [amount]|clear <player> <base|all-bases> confirm>");
    }

    private static List<String> researchMutation(HeadlessGameServer host, List<String> args) {
        if (args.size() < 2) return List.of("Usage: dev research <grant|grant-all|complete-queued|revoke|reset> ...");
        String action = args.get(1).toLowerCase(Locale.ROOT);
        if ("grant".equals(action) && (args.size() == 4 || args.size() == 5)) {
            String playerId = resolvePlayer(host.network, args.get(2));
            ResearchTopic topic = ResearchRules.topic(args.get(3));
            boolean prerequisites = args.size() == 5 && "with-prerequisites".equalsIgnoreCase(args.get(4));
            if (playerId.isBlank()) return List.of("Unknown player session: " + args.get(2));
            if (topic == null) return List.of("Unknown research topic: " + args.get(3));
            if (host.world.hasResearch(playerId, topic.id)) return List.of(topic.name + " is already completed for " + playerId + ".");
            LinkedHashSet<String> grant = new LinkedHashSet<>();
            String error = collectPrerequisites(host.world, playerId, topic, prerequisites, grant, new HashSet<>());
            if (error != null) return List.of(error);
            grant.add(topic.id);
            for (String topicId : grant) completeResearchAndRemoveQueues(host.world, playerId, topicId, true);
            changed(host, "RESEARCH", playerId, "granted " + String.join(",", grant));
            return List.of("Granted " + grant.size() + " research topic" + (grant.size() == 1 ? "" : "s") + " to " + playerId + ": " + String.join(", ", grant));
        }
        if ("grant-all".equals(action) && args.size() == 4 && "confirm".equalsIgnoreCase(args.get(3))) {
            String playerId = resolvePlayer(host.network, args.get(2));
            if (playerId.isBlank()) return List.of("Unknown player session: " + args.get(2));
            int count = 0;
            for (ResearchTopic topic : ResearchRules.all()) if (!host.world.hasResearch(playerId, topic.id)) {
                completeResearchAndRemoveQueues(host.world, playerId, topic.id, true);
                count++;
            }
            changed(host, "RESEARCH", playerId, "grant-all " + count);
            return List.of("Granted all research to " + playerId + " (" + count + " newly completed).");
        }
        if ("complete-queued".equals(action) && args.size() == 4) {
            String playerId = resolvePlayer(host.network, args.get(2));
            if (playerId.isBlank()) return List.of("Unknown player session: " + args.get(2));
            ProductionJob job = queuedResearch(host.world, playerId).get(args.get(3));
            if (job == null) return List.of("That research topic is not queued for " + playerId + ".");
            job.remaining = 0;
            finishProductionQueues(host.world, playerId, null, job.id);
            changed(host, "RESEARCH", playerId, "completed queued " + args.get(3));
            return List.of("Completed queued research " + args.get(3) + " for " + playerId + ".");
        }
        if ("revoke".equals(action) && (args.size() == 4 || args.size() == 5)) {
            String playerId = resolvePlayer(host.network, args.get(2));
            ResearchTopic topic = ResearchRules.topic(args.get(3));
            boolean cascade = args.size() == 5 && "cascade".equalsIgnoreCase(args.get(4));
            if (playerId.isBlank()) return List.of("Unknown player session: " + args.get(2));
            if (topic == null) return List.of("Unknown research topic: " + args.get(3));
            Set<String> completed = new LinkedHashSet<>(host.world.completedResearch.getOrDefault(playerId, Set.of()));
            if (!completed.contains(topic.id)) return List.of(topic.name + " is not completed for " + playerId + ".");
            LinkedHashSet<String> remove = dependentTopics(completed, topic.id);
            if (remove.size() > 1 && !cascade) return List.of("Dependent completed research exists: " + String.join(", ", remove) + ". Use 'cascade'.");
            if (!cascade) remove = new LinkedHashSet<>(List.of(topic.id));
            completed.removeAll(remove);
            if (completed.isEmpty()) host.world.completedResearch.remove(playerId);
            else host.world.completedResearch.put(playerId, completed);
            removeQueuedResearch(host.world, playerId, remove, true);
            changed(host, "RESEARCH", playerId, "revoked " + String.join(",", remove));
            return List.of("Revoked research from " + playerId + ": " + String.join(", ", remove));
        }
        if ("reset".equals(action) && args.size() == 4 && "confirm".equalsIgnoreCase(args.get(3))) {
            String playerId = resolvePlayer(host.network, args.get(2));
            if (playerId.isBlank()) return List.of("Unknown player session: " + args.get(2));
            int completed = host.world.completedResearch.getOrDefault(playerId, Set.of()).size();
            host.world.completedResearch.remove(playerId);
            int queued = removeQueuedResearch(host.world, playerId, null, true);
            changed(host, "RESEARCH", playerId, "reset completed=" + completed + " queued=" + queued);
            return List.of("Reset research for " + playerId + ": removed " + completed + " completed topics and " + queued + " queued jobs.");
        }
        return List.of("Usage: dev research <grant <player> <topic> [with-prerequisites]|grant-all <player> confirm|complete-queued <player> <topic>|revoke <player> <topic> [cascade]|reset <player> confirm>");
    }

    private static List<String> ai(HeadlessGameServer host, List<String> args) {
        if (args.size() == 2 && "step".equalsIgnoreCase(args.get(1))) {
            host.world.aiDevSettings.stepAi = true;
            return List.of("AI will advance one step.");
        }
        if (args.size() == 2 && "snapshot".equalsIgnoreCase(args.get(1))) {
            String snapshot = AiDevSnapshot.copySnapshot(host.world);
            Path path = writeText(host, "ai-snapshot", snapshot);
            return List.of(path == null ? "Could not write AI snapshot." : "Wrote AI snapshot: " + path.toAbsolutePath().normalize());
        }
        if (args.size() == 2 && "reload".equalsIgnoreCase(args.get(1))) {
            AiDevCommands.hotReload(host.world);
            return List.of(host.world.status);
        }
        if (args.size() >= 2 && "preset".equalsIgnoreCase(args.get(1))) {
            if (args.size() == 2 || args.size() == 3 && "list".equalsIgnoreCase(args.get(2))) {
                ArrayList<String> lines = new ArrayList<>();
                for (NpcDifficultyPreset preset : NpcDifficultyPreset.values()) lines.add(preset.name().toLowerCase(Locale.ROOT) + " | " + preset.label);
                return List.copyOf(lines);
            }
            if (args.size() == 3) {
                NpcDifficultyPreset wanted = preset(args.get(2));
                if (wanted == null) return List.of("Unknown preset: " + args.get(2));
                host.world.aiDevSettings.setDifficultyPreset(wanted);
                return List.of("AI preset set to " + host.world.aiDevSettings.difficultyPreset().name().toLowerCase(Locale.ROOT) + ".");
            }
        }
        if (args.size() != 3) return List.of("Usage: dev ai <pause on|off|speed normal|fast|freeze-players on|off|freeze-npc-combat on|off|attacks on|off|economy on|off|preset list|preset <id>|step|snapshot|reload>");
        String action = args.get(1).toLowerCase(Locale.ROOT);
        String value = args.get(2).toLowerCase(Locale.ROOT);
        Boolean enabled = flag(value);
        switch (action) {
            case "pause" -> {
                if (enabled == null) break;
                host.world.aiDevSettings.pauseAi = enabled;
                if (!enabled) host.world.aiDevSettings.stepAi = false;
                return List.of("AI pause " + state(enabled) + ".");
            }
            case "speed" -> {
                if (!List.of("normal", "fast").contains(value)) break;
                host.world.aiDevSettings.fastAi = "fast".equals(value);
                return List.of("AI speed set to " + value + ".");
            }
            case "freeze-players" -> {
                if (enabled == null) break;
                host.world.aiDevSettings.freezePlayerUnits = enabled;
                return List.of("Player-unit freeze " + state(enabled) + ".");
            }
            case "freeze-npc-combat" -> {
                if (enabled == null) break;
                host.world.aiDevSettings.freezeNpcCombat = enabled;
                return List.of("NPC combat freeze " + state(enabled) + ".");
            }
            case "attacks" -> {
                if (enabled == null) break;
                host.world.aiDevSettings.disableAttacks = !enabled;
                return List.of("AI attacks " + state(enabled) + ".");
            }
            case "economy" -> {
                if (enabled == null) break;
                host.world.aiDevSettings.disableEconomy = !enabled;
                return List.of("AI economy " + state(enabled) + ".");
            }
            default -> { }
        }
        return List.of("Usage: dev ai <pause on|off|speed normal|fast|freeze-players on|off|freeze-npc-combat on|off|attacks on|off|economy on|off|preset list|preset <id>|step|snapshot|reload>");
    }

    private static List<String> timers(HeadlessGameServer host, List<String> args) {
        if (args.size() == 2 && "status".equalsIgnoreCase(args.get(1))) return List.of("Production timers: " + (DevTimerSettings.disabled(host.world) ? "disabled" : "enabled"));
        if (args.size() != 2) return List.of("Usage: dev timers <status|on|off>");
        Boolean enabled = flag(args.get(1));
        if (enabled == null) return List.of("Usage: dev timers <status|on|off>");
        DevTimerSettings.configure(host.world, !enabled);
        return List.of("Production timers " + state(enabled) + ".");
    }

    private static List<String> faction(HeadlessGameServer host, List<String> args) {
        if (args.size() == 1 || args.size() == 2 && "list".equalsIgnoreCase(args.get(1))) {
            ArrayList<String> lines = new ArrayList<>();
            for (NpcFaction faction : NpcRules.factions()) lines.add(faction.id() + " | " + faction.name() + " | " + faction.behavior() + " | " + (faction.enabled() ? "enabled" : "disabled"));
            return List.copyOf(lines);
        }
        if (args.size() < 3) return List.of("Usage: dev faction <faction> <status|spawn [system]|remove confirm|reset confirm|give-resources <material|all> <amount>|force <raid|station|research|craft>>");
        NpcFaction faction = faction(args.get(1));
        if (faction == null) return List.of("Unknown NPC faction: " + args.get(1));
        String action = args.get(2).toLowerCase(Locale.ROOT);
        if ("status".equals(action) && args.size() == 3) return factionStatus(host.world, faction);
        if ("spawn".equals(action) && (args.size() == 3 || args.size() == 4)) {
            String previous = host.world.activeSystemId();
            try {
                if (args.size() == 4) {
                    String system = resolveSystem(host.world, args.get(3));
                    if (system.isBlank()) return List.of("Unknown galaxy system: " + args.get(3));
                    host.world.activateSystem(system);
                }
                boolean spawned = NpcFactionSpawner.spawn(host.world, faction, NpcSpawnReason.FORCED);
                if (spawned) host.world.saveActiveSystem();
                changed(host, "FACTION", faction.id(), spawned ? "spawned" : "spawn skipped");
                return List.of(spawned ? "Spawned " + faction.name() + "." : faction.name() + " already has active assets or could not spawn.");
            } finally { restore(host.world, previous); }
        }
        if ("remove".equals(action) && args.size() == 4 && "confirm".equalsIgnoreCase(args.get(3))) {
            String backup = backup(host, "pre-faction-remove");
            if (!backup.startsWith("Created backup")) return List.of(backup, "Nothing was removed.");
            int removed = removeFaction(host.world, faction);
            changed(host, "FACTION", faction.id(), "removed " + removed);
            return List.of(backup, "Removed " + removed + " assets belonging to " + faction.name() + ".");
        }
        if ("reset".equals(action) && args.size() == 4 && "confirm".equalsIgnoreCase(args.get(3))) {
            String backup = backup(host, "pre-faction-reset");
            if (!backup.startsWith("Created backup")) return List.of(backup, "Nothing was reset.");
            int removed = removeFaction(host.world, faction);
            boolean spawned = NpcFactionSpawner.spawn(host.world, faction, NpcSpawnReason.FORCED);
            changed(host, "FACTION", faction.id(), "reset removed=" + removed + " spawned=" + spawned);
            return List.of(backup, "Reset " + faction.name() + ": removed " + removed + " assets; spawn " + (spawned ? "completed" : "not created") + ".");
        }
        if ("give-resources".equals(action) && args.size() == 5) {
            Double value = amount(args.get(4));
            if (value == null) return List.of("Resource amount is invalid.");
            Material selected = "all".equalsIgnoreCase(args.get(3)) ? null : material(args.get(3));
            if (selected == null && !"all".equalsIgnoreCase(args.get(3))) return List.of("Unknown material: " + args.get(3));
            int bases = mutateFactionBases(host.world, faction.id(), base -> {
                if (selected == null) for (Material material : Material.values()) HangarStore.add(base.inventory, material, value);
                else HangarStore.add(base.inventory, selected, value);
            });
            changed(host, "FACTION", faction.id(), "resources bases=" + bases);
            return List.of("Added resources to " + bases + " " + faction.name() + " base" + (bases == 1 ? "" : "s") + ".");
        }
        if ("force".equals(action) && args.size() == 4) return forceFaction(host, faction, args.get(3));
        return List.of("Usage: dev faction <faction> <status|spawn [system]|remove confirm|reset confirm|give-resources <material|all> <amount>|force <raid|station|research|craft>>");
    }

    private static List<String> production(HeadlessGameServer host, List<String> args) {
        if (args.size() < 2) return List.of("Usage: dev production <finish|fund|cancel|move|clear> ...");
        String action = args.get(1).toLowerCase(Locale.ROOT);
        if (List.of("finish", "fund", "cancel").contains(action) && args.size() == 4) {
            LocatedBase located = findBase(host.world, args.get(2));
            if (located == null) return List.of("Unknown base: " + args.get(2));
            ProductionJob job = ProductionSystem.findJob(located.base, args.get(3));
            if (job == null) return List.of("Unknown production job: " + args.get(3));
            boolean result;
            String previous = host.world.activeSystemId();
            try {
                host.world.activateSystem(located.systemId);
                result = switch (action) {
                    case "finish" -> { job.remaining = 0; ProductionSystem.update(host.world, 0); yield !located.base.productionQueue.contains(job); }
                    case "fund" -> ProductionSystem.fundWaitingJob(host.world, located.base, job.id);
                    default -> ProductionSystem.cancel(host.world, located.base.playerId, located.base.id, job.id);
                };
                host.world.saveActiveSystem();
            } finally { restore(host.world, previous); }
            if (!result) return List.of("Production operation was rejected: " + host.world.status);
            changed(host, "PRODUCTION", located.base.id, action + " " + job.id);
            return List.of("Production " + action + " completed for " + located.base.id + "/" + job.id + ".");
        }
        if ("move".equals(action) && args.size() == 5) {
            LocatedBase located = findBase(host.world, args.get(2));
            if (located == null) return List.of("Unknown base: " + args.get(2));
            int position;
            try { position = Integer.parseInt(args.get(4)); } catch (NumberFormatException ex) { return List.of("Queue position is not numeric."); }
            int current = indexOf(located.base, args.get(3));
            if (current < 0) return List.of("Unknown production job: " + args.get(3));
            int target = Math.max(1, Math.min(located.base.productionQueue.size(), position)) - 1;
            if (current == 0 || target == 0) return List.of("The active job cannot be reordered.");
            boolean moved = ProductionSystem.move(host.world, located.base.playerId, located.base.id, args.get(3), target - current);
            if (!moved) return List.of("Production move rejected: " + host.world.status);
            changed(host, "PRODUCTION", located.base.id, "move " + args.get(3) + " to " + position);
            return List.of(host.world.status);
        }
        if ("clear".equals(action) && args.size() == 4 && "confirm".equalsIgnoreCase(args.get(3))) {
            LocatedBase located = findBase(host.world, args.get(2));
            if (located == null) return List.of("Unknown base: " + args.get(2));
            String backup = backup(host, "pre-production-clear");
            if (!backup.startsWith("Created backup")) return List.of(backup, "Queue was not changed.");
            int count = 0;
            while (!located.base.productionQueue.isEmpty() && count < MAX_MASS_TARGETS) {
                ProductionJob job = located.base.productionQueue.get(located.base.productionQueue.size() - 1);
                if (!ProductionSystem.cancel(host.world, located.base.playerId, located.base.id, job.id)) break;
                count++;
            }
            changed(host, "PRODUCTION", located.base.id, "cleared " + count);
            return List.of(backup, "Cancelled and refunded " + count + " production job" + (count == 1 ? "" : "s") + ".");
        }
        return List.of("Usage: dev production <finish <base> <job>|fund <base> <job>|cancel <base> <job>|move <base> <job> <position>|clear <base> confirm>");
    }

    private static List<String> asset(HeadlessGameServer host, List<String> args) {
        if (args.size() < 3) return List.of("Usage: dev asset <heal|destroy|move> <asset> ...");
        String action = args.get(1).toLowerCase(Locale.ROOT);
        LocatedAsset located = findAsset(host.world, args.get(2));
        if (located == null) return List.of("Unknown asset: " + args.get(2));
        if ("heal".equals(action) && args.size() == 3) {
            if (located.unit != null) { located.unit.hp = located.unit.type().maxHp; located.unit.shield = located.unit.type().maxShield; }
            else { located.base.hp = located.base.type().maxHp; located.base.shield = located.base.type().maxShield; }
            saveLocated(host.world, located.systemId);
            changed(host, "ASSET", args.get(2), "healed");
            return List.of("Healed " + args.get(2) + " to full HP and shields.");
        }
        if ("destroy".equals(action) && args.size() == 4 && "confirm".equalsIgnoreCase(args.get(3))) {
            String backup = backup(host, "pre-asset-destroy");
            if (!backup.startsWith("Created backup")) return List.of(backup, "Asset was not destroyed.");
            String previous = host.world.activeSystemId();
            try {
                host.world.activateSystem(located.systemId);
                if (located.unit != null) { host.world.units.remove(located.unit.key()); host.world.explodeUnit(located.unit); }
                else { host.world.bases.remove(located.base.id); host.world.explodeBase(located.base); }
                host.world.saveActiveSystem();
            } finally { restore(host.world, previous); }
            changed(host, "ASSET", args.get(2), "destroyed");
            return List.of(backup, "Destroyed " + args.get(2) + ".");
        }
        if ("move".equals(action) && args.size() == 4) {
            if (located.unit == null) return List.of("Individual base relocation is not supported; use 'dev player relocate'.");
            String target = resolveSystem(host.world, args.get(3));
            if (target.isBlank()) return List.of("Unknown galaxy system: " + args.get(3));
            if (target.equals(located.systemId)) return List.of("Asset is already in " + target + ".");
            String previous = host.world.activeSystemId();
            try {
                host.world.activateSystem(located.systemId);
                host.world.units.remove(located.unit.key());
                host.world.saveActiveSystem();
                host.world.activateSystem(target);
                located.unit.x = host.world.width / 2.0;
                located.unit.y = host.world.height / 2.0;
                located.unit.clearOrder();
                host.world.units.put(located.unit.key(), located.unit);
                host.world.saveActiveSystem();
            } finally { restore(host.world, previous); }
            changed(host, "ASSET", args.get(2), "moved " + located.systemId + " -> " + target);
            return List.of("Moved " + args.get(2) + " to " + target + ".");
        }
        return List.of("Usage: dev asset <heal <asset>|destroy <asset> confirm|move <ship> <system>>");
    }

    private static List<String> player(HeadlessGameServer host, List<String> args) {
        if (args.size() < 3) return List.of("Usage: dev player <heal-all|relocate|respawn> <player> ...");
        String action = args.get(1).toLowerCase(Locale.ROOT);
        String playerId = resolvePlayer(host.network, args.get(2));
        if (playerId.isBlank()) return List.of("Unknown player session: " + args.get(2));
        if ("heal-all".equals(action) && args.size() == 3) {
            int[] count = new int[2];
            visitSystems(host.world, (system, world) -> {
                for (Unit unit : world.units.values()) if (playerId.equals(unit.playerId)) { unit.hp = unit.type().maxHp; unit.shield = unit.type().maxShield; count[0]++; }
                for (Base base : world.bases.values()) if (playerId.equals(base.playerId)) { base.hp = base.type().maxHp; base.shield = base.type().maxShield; count[1]++; }
                return true;
            });
            changed(host, "PLAYER", playerId, "heal-all units=" + count[0] + " bases=" + count[1]);
            return List.of("Healed " + count[0] + " ships and " + count[1] + " bases for " + playerId + ".");
        }
        if ("relocate".equals(action) && args.size() == 4) {
            String system = resolveSystem(host.world, args.get(3));
            if (system.isBlank()) return List.of("Unknown galaxy system: " + args.get(3));
            String backup = backup(host, "pre-player-relocate");
            if (!backup.startsWith("Created backup")) return List.of(backup, "Assets were not moved.");
            host.world.movePlayerAssetsToSystem(playerId, system);
            changed(host, "PLAYER", playerId, "relocated to " + system);
            return List.of(backup, "Moved all assets for " + playerId + " to " + system + ".");
        }
        if ("respawn".equals(action) && args.size() == 3) {
            if (host.world.hasLiveAssets(playerId)) return List.of(playerId + " still has live assets. Use repair or relocation instead.");
            int slot = Math.max(0, host.network.persistentPlayerSessions().indexOf(session(host.network, playerId)));
            host.world.spawnPlayerGroup(playerId, slot);
            changed(host, "PLAYER", playerId, "respawned starter group");
            return List.of("Respawned a starter base and ship for " + playerId + ".");
        }
        return List.of("Usage: dev player <heal-all <player>|relocate <player> <system>|respawn <player>>");
    }

    private static List<String> spawn(HeadlessGameServer host, List<String> args) {
        if (args.size() == 2 && List.of("corsairs", "loot", "wave").contains(args.get(1).toLowerCase(Locale.ROOT))) {
            return switch (args.get(1).toLowerCase(Locale.ROOT)) {
                case "corsairs" -> faction(host, List.of("faction", Config.CORSAIRS_ID, "spawn"));
                case "loot" -> { AiDevCommands.spawnLootField(host.world); changed(host, "SPAWN", "loot", "field"); yield List.of(host.world.status); }
                default -> { AiDevCommands.spawnAttackWave(host.world); changed(host, "SPAWN", "wave", "attack"); yield List.of(host.world.status); }
            };
        }
        if (args.size() < 4) return List.of("Usage: dev spawn <ship <player> <ship-type> [count] [system]|base <player> <base-type> <system> [x] [y]|loot|wave>");
        String type = args.get(1).toLowerCase(Locale.ROOT);
        String playerId = resolvePlayer(host.network, args.get(2));
        if (playerId.isBlank()) return List.of("Unknown player session: " + args.get(2));
        if ("ship".equals(type)) {
            ShipType ship = Rules.findShip(args.get(3));
            if (ship == null) return List.of("Unknown ship type: " + args.get(3));
            int count = 1;
            String systemSelector = host.world.playerHomeSystemId(playerId);
            if (args.size() >= 5) {
                try { count = Integer.parseInt(args.get(4)); }
                catch (NumberFormatException ex) { systemSelector = args.get(4); }
            }
            if (args.size() >= 6) systemSelector = args.get(5);
            if (count < 1 || count > MAX_SPAWN_COUNT) return List.of("Ship count must be between 1 and " + MAX_SPAWN_COUNT + ".");
            String system = resolveSystem(host.world, systemSelector);
            if (system.isBlank()) return List.of("Unknown galaxy system: " + systemSelector);
            String previous = host.world.activeSystemId();
            try {
                host.world.activateSystem(system);
                int next = nextUnit(host.world, playerId);
                for (int i = 0; i < count; i++) {
                    double angle = i * Math.PI * 2 / Math.max(1, count);
                    Unit unit = new Unit(playerId, next++, ship.id, host.world.width / 2.0 + Math.cos(angle) * 120,
                            host.world.height / 2.0 + Math.sin(angle) * 120);
                    host.world.units.put(unit.key(), unit);
                }
                host.world.saveActiveSystem();
            } finally { restore(host.world, previous); }
            changed(host, "SPAWN", playerId, count + " " + ship.id + " in " + system);
            return List.of("Spawned " + count + " " + ship.name + " ship" + (count == 1 ? "" : "s") + " for " + playerId + " in " + system + ".");
        }
        if ("base".equals(type)) {
            if (args.size() < 5) return List.of("Usage: dev spawn base <player> <base-type> <system> [x] [y]");
            BaseType baseType = Rules.findBase(args.get(3));
            if (baseType == null) return List.of("Unknown base type: " + args.get(3));
            String system = resolveSystem(host.world, args.get(4));
            if (system.isBlank()) return List.of("Unknown galaxy system: " + args.get(4));
            double x = args.size() >= 6 ? coordinate(args.get(5), Double.NaN) : Double.NaN;
            double y = args.size() >= 7 ? coordinate(args.get(6), Double.NaN) : Double.NaN;
            String previous = host.world.activeSystemId();
            String id;
            try {
                host.world.activateSystem(system);
                if (!Double.isFinite(x)) x = host.world.width / 2.0;
                if (!Double.isFinite(y)) y = host.world.height / 2.0;
                x = Math.max(100, Math.min(host.world.width - 100, x));
                y = Math.max(100, Math.min(host.world.height - 100, y));
                id = playerId + ":B" + nextBase(host.world, playerId);
                host.world.bases.put(id, new Base(id, playerId, baseType.id, x, y));
                host.world.saveActiveSystem();
            } finally { restore(host.world, previous); }
            changed(host, "SPAWN", playerId, "base " + baseType.id + " in " + system);
            return List.of("Spawned " + baseType.name + " " + id + " for " + playerId + " in " + system + ".");
        }
        return List.of("Usage: dev spawn <ship <player> <ship-type> [count] [system]|base <player> <base-type> <system> [x] [y]|loot|wave>");
    }

    private static List<String> legacyTrigger(HeadlessGameServer host, List<String> args) {
        if (args.size() != 2) return List.of("Usage: dev trigger <raid|station|research|craft>");
        return forceFaction(host, faction(Config.CORSAIRS_ID), args.get(1));
    }

    private static List<String> legacyRemove(HeadlessGameServer host, List<String> args) {
        if (args.size() != 2 || !"corsairs".equalsIgnoreCase(args.get(1))) return List.of("Usage: dev remove corsairs");
        return faction(host, List.of("faction", Config.CORSAIRS_ID, "remove", "confirm"));
    }

    private static List<String> legacyReset(HeadlessGameServer host, List<String> args) {
        if (args.size() != 2 || !"corsairs".equalsIgnoreCase(args.get(1))) return List.of("Usage: dev reset corsairs");
        return faction(host, List.of("faction", Config.CORSAIRS_ID, "reset", "confirm"));
    }

    private static List<String> forceFaction(HeadlessGameServer host, NpcFaction faction, String requested) {
        if (faction == null) return List.of("NPC faction is not configured.");
        String action = requested == null ? "" : requested.toLowerCase(Locale.ROOT);
        if ("raid".equals(action)) {
            int[] count = {0};
            visitSystems(host.world, (system, world) -> {
                String target = firstEnemyTarget(world, faction.id());
                if (target.isBlank()) return false;
                for (Unit unit : world.units.values()) if (faction.id().equals(unit.playerId) && WeaponRules.armed(unit)) {
                    unit.attack(target); count[0]++;
                }
                return count[0] > 0;
            });
            changed(host, "FACTION", faction.id(), "forced raid ships=" + count[0]);
            return List.of("Ordered " + count[0] + " " + faction.name() + " combat ships to attack.");
        }
        if ("station".equals(action)) {
            String home = NpcFactionRuntime.homeSystemIdFor(faction);
            String system = resolveSystem(host.world, home);
            if (system.isBlank()) return List.of("Faction home system is unavailable: " + home);
            String stationType = !faction.stationPackageTypes().isEmpty() ? faction.stationPackageTypes().get(0) : faction.baseType();
            BaseType type = Rules.findBase(stationType);
            if (type == null) return List.of("No valid station type is configured for " + faction.name() + ".");
            String previous = host.world.activeSystemId();
            String id;
            try {
                host.world.activateSystem(system);
                Point2D point = host.world.npcSpawnPoint(faction.id(), faction.spawnPadding());
                id = faction.id() + ":B" + nextBase(host.world, faction.id());
                host.world.bases.put(id, new Base(id, faction.id(), type.id, point.getX(), point.getY()));
                host.world.saveActiveSystem();
            } finally { restore(host.world, previous); }
            changed(host, "FACTION", faction.id(), "forced station " + id);
            return List.of("Created " + type.name + " " + id + " for " + faction.name() + " in " + system + ".");
        }
        if ("research".equals(action)) {
            for (String topicId : faction.researchTopicIds()) if (!host.world.hasResearch(faction.id(), topicId)) {
                host.world.completeResearch(faction.id(), topicId);
                changed(host, "FACTION", faction.id(), "research " + topicId);
                return List.of("Completed " + topicId + " for " + faction.name() + ".");
            }
            return List.of(faction.name() + " already has all configured research.");
        }
        if ("craft".equals(action)) {
            LocatedBase base = firstFactionBase(host.world, faction.id());
            if (base == null) return List.of("No base exists for " + faction.name() + ".");
            String previous = host.world.activeSystemId();
            try {
                host.world.activateSystem(base.systemId);
                CraftableItem item = null;
                for (String id : faction.craftableItemIds()) if ((item = CraftingRules.item(id)) != null) break;
                if (item != null) HangarStore.add(base.base.inventory, item.outputMaterial, item.outputAmount);
                else HangarStore.add(base.base.inventory, Material.FUEL, 100);
                host.world.saveActiveSystem();
            } finally { restore(host.world, previous); }
            changed(host, "FACTION", faction.id(), "forced craft");
            return List.of("Forced one configured craft output for " + faction.name() + ".");
        }
        return List.of("Usage: dev faction <faction> force <raid|station|research|craft>");
    }

    private static List<String> factionStatus(World world, NpcFaction faction) {
        int[] totals = new int[2];
        visitSystems(world, (system, active) -> {
            for (Unit unit : active.units.values()) if (faction.id().equals(unit.playerId) && unit.hp > 0) totals[0]++;
            for (Base base : active.bases.values()) if (faction.id().equals(base.playerId) && base.hp > 0) totals[1]++;
            return false;
        });
        NpcStrategicSnapshot strategic = NpcStrategicDirector.snapshot(world, faction);
        return List.of(faction.id() + " | " + faction.name() + " | " + faction.behavior(),
                "Assets: ships " + totals[0] + " | bases " + totals[1],
                "Limits: fleet " + faction.targetFleetSize() + " | workers " + faction.maxWorkers() + " | stations " + faction.maxStations(),
                "Strategic: " + String.valueOf(strategic));
    }

    private static int removeFaction(World world, NpcFaction faction) {
        int[] removed = {0};
        visitSystems(world, (system, active) -> {
            int before = active.units.size() + active.bases.size();
            active.units.values().removeIf(unit -> faction.id().equals(unit.playerId));
            active.bases.values().removeIf(base -> faction.id().equals(base.playerId));
            removed[0] += before - active.units.size() - active.bases.size();
            return true;
        });
        world.resetOrganizedNpcFactionState(faction, NpcFactionResetReason.DEV_RESET);
        NpcStrategicDirector.onDefeated(world, faction);
        return removed[0];
    }

    private static int mutateFactionBases(World world, String factionId, BaseMutation mutation) {
        int[] count = {0};
        visitSystems(world, (system, active) -> {
            boolean changed = false;
            for (Base base : active.bases.values()) if (factionId.equals(base.playerId)) {
                mutation.apply(base); count[0]++; changed = true;
            }
            return changed;
        });
        return count[0];
    }

    private static int mutatePlayerBases(World world, String playerId, String target, BaseMutation mutation) {
        boolean all = "all-bases".equalsIgnoreCase(target);
        int[] count = {0};
        visitSystems(world, (system, active) -> {
            boolean changed = false;
            for (Base base : active.bases.values()) {
                if (!playerId.equals(base.playerId) || !all && !base.id.equalsIgnoreCase(target)) continue;
                if (count[0] >= MAX_MASS_TARGETS) break;
                mutation.apply(base); count[0]++; changed = true;
            }
            return changed;
        });
        return count[0];
    }

    private static void changed(HeadlessGameServer host, String type, String subject, String detail) {
        host.network.forceServerResourceCorrection();
        host.network.resyncAllServerPlayers();
        host.network.serverJournal().add(type, subject, detail);
    }

    private static String backup(HeadlessGameServer host, String label) {
        if (!host.saveForAdmin("admin-" + label)) return "Pre-operation save failed.";
        Config config = host.network.serverConfig();
        return new ServerBackupAdmin(config.saveDir, config.saveName, config.backupCount).create(label);
    }

    private static String collectPrerequisites(World world, String playerId, ResearchTopic topic, boolean include,
                                               LinkedHashSet<String> out, Set<String> visiting) {
        if (!visiting.add(topic.id)) return "Research prerequisite cycle detected at " + topic.id + ".";
        for (String requirement : topic.requires) {
            if (world.hasResearch(playerId, requirement)) continue;
            if (!include) return topic.name + " requires " + requirement + ". Add 'with-prerequisites'.";
            ResearchTopic required = ResearchRules.topic(requirement);
            if (required == null) return "Missing configured prerequisite: " + requirement;
            String error = collectPrerequisites(world, playerId, required, true, out, visiting);
            if (error != null) return error;
            out.add(required.id);
        }
        visiting.remove(topic.id);
        return null;
    }

    private static LinkedHashSet<String> dependentTopics(Set<String> completed, String root) {
        LinkedHashSet<String> remove = new LinkedHashSet<>();
        remove.add(root);
        boolean changed;
        do {
            changed = false;
            for (ResearchTopic topic : ResearchRules.all()) if (completed.contains(topic.id) && !remove.contains(topic.id)) {
                for (String requirement : topic.requires) if (remove.contains(requirement)) { remove.add(topic.id); changed = true; break; }
            }
        } while (changed);
        ArrayList<String> ordered = new ArrayList<>(remove);
        ordered.sort(Comparator.comparingInt((String id) -> dependencyDepth(id)).reversed());
        return new LinkedHashSet<>(ordered);
    }

    private static int dependencyDepth(String topicId) {
        ResearchTopic topic = ResearchRules.topic(topicId);
        if (topic == null || topic.requires.isEmpty()) return 0;
        int max = 0;
        for (String parent : topic.requires) max = Math.max(max, 1 + dependencyDepth(parent));
        return max;
    }

    private static void completeResearchAndRemoveQueues(World world, String playerId, String topicId, boolean refund) {
        removeQueuedResearch(world, playerId, Set.of(topicId), refund);
        world.completeResearch(playerId, topicId);
    }

    private static int removeQueuedResearch(World world, String playerId, Set<String> topics, boolean refund) {
        int[] count = {0};
        visitSystems(world, (system, active) -> {
            boolean changed = false;
            for (Base base : active.bases.values()) if (playerId.equals(base.playerId)) {
                for (int i = base.productionQueue.size() - 1; i >= 0; i--) {
                    ProductionJob job = base.productionQueue.get(i);
                    if (job.kind != ProductionJobKind.RESEARCH || topics != null && !topics.contains(job.itemId)) continue;
                    if (refund && job.resourcesReserved) refundResearch(base, job.itemId);
                    active.logisticsSystem.cancelJob(base, job.id);
                    base.productionQueue.remove(i); count[0]++; changed = true;
                }
            }
            return changed;
        });
        return count[0];
    }

    private static void refundResearch(Base base, String topicId) {
        ResearchTopic topic = ResearchRules.topic(topicId);
        if (topic == null) return;
        for (Cost cost : topic.requiredResources) HangarStore.add(base.inventory, cost.material(), cost.amount());
    }

    private static Map<String,ProductionJob> queuedResearch(World world, String playerId) {
        LinkedHashMap<String,ProductionJob> out = new LinkedHashMap<>();
        visitSystems(world, (system, active) -> {
            for (Base base : active.bases.values()) if (playerId.equals(base.playerId)) {
                for (ProductionJob job : base.productionQueue) if (job.kind == ProductionJobKind.RESEARCH) out.putIfAbsent(job.itemId, job);
            }
            return false;
        });
        return out;
    }

    private static void finishProductionQueues(World world, String playerId, String baseId, String jobId) {
        visitSystems(world, (system, active) -> {
            boolean changed = false;
            for (Base base : active.bases.values()) {
                if (playerId != null && !playerId.equals(base.playerId) || baseId != null && !baseId.equals(base.id)) continue;
                for (ProductionJob job : base.productionQueue) if (job.id.equals(jobId)) { job.remaining = 0; changed = true; }
            }
            if (changed) ProductionSystem.update(active, 0);
            return changed;
        });
    }

    private static List<String> threads(List<String> args) {
        if (!args.isEmpty()) return List.of("Usage: threads");
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] info = bean.dumpAllThreads(false, false);
        ArrayList<String> lines = new ArrayList<>();
        lines.add("Threads: live " + bean.getThreadCount() + " | daemon " + bean.getDaemonThreadCount() + " | peak " + bean.getPeakThreadCount());
        for (ThreadInfo thread : info) if (thread != null) lines.add(thread.getThreadId() + " | " + thread.getThreadState() + " | " + thread.getThreadName());
        return cap(lines, "threads");
    }

    private static List<String> memory(List<String> args) {
        if (!args.isEmpty()) return List.of("Usage: memory");
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = bean.getHeapMemoryUsage();
        MemoryUsage non = bean.getNonHeapMemoryUsage();
        return List.of("Heap: used " + bytes(heap.getUsed()) + " | committed " + bytes(heap.getCommitted()) + " | max " + bytes(heap.getMax()),
                "Non-heap: used " + bytes(non.getUsed()) + " | committed " + bytes(non.getCommitted()) + " | max " + bytes(non.getMax()),
                "Pending finalization: " + bean.getObjectPendingFinalizationCount());
    }

    private static List<String> gcStatus(List<String> args) {
        if (!args.isEmpty()) return List.of("Usage: gc-status");
        ArrayList<String> lines = new ArrayList<>();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            lines.add(bean.getName() + " | collections " + bean.getCollectionCount() + " | time " + bean.getCollectionTime() + "ms");
        }
        lines.add("Forced garbage collection is intentionally not exposed.");
        return List.copyOf(lines);
    }

    private static List<String> tell(HeadlessGameServer host, List<String> args) {
        if (args.size() < 2) return List.of("Usage: tell <player> <message>");
        String playerId = resolvePlayer(host.network, args.get(0));
        if (playerId.isBlank()) return List.of("Unknown player session: " + args.get(0));
        String message = clean(String.join(" ", args.subList(1, args.size())));
        if (message.isBlank()) return List.of("Message is empty.");
        boolean sent = host.network.sendServerNotice(playerId, message);
        return List.of(sent ? "Notice sent to " + playerId + "." : playerId + " is not connected.");
    }

    private static List<String> notice(HeadlessGameServer host, List<String> args) {
        if (args.size() < 2) return List.of("Usage: notice <all <message>|system <system> <message>>");
        String scope = args.get(0).toLowerCase(Locale.ROOT);
        if ("all".equals(scope)) {
            String message = clean(String.join(" ", args.subList(1, args.size())));
            int count = host.network.broadcastServerNotice(message);
            return List.of("Notice sent to " + count + " connected client" + (count == 1 ? "" : "s") + ".");
        }
        if ("system".equals(scope) && args.size() >= 3) {
            String system = resolveSystem(host.world, args.get(1));
            if (system.isBlank()) return List.of("Unknown galaxy system: " + args.get(1));
            String message = clean(String.join(" ", args.subList(2, args.size())));
            int count = 0;
            for (PersistentPlayerSession session : host.network.persistentPlayerSessions()) {
                if (!host.network.serverSessionConnected(session.playerId())) continue;
                if (system.equalsIgnoreCase(host.world.playerHomeSystemId(session.playerId())) || playerHasAssetsIn(host.world, session.playerId(), system)) {
                    if (host.network.sendServerNotice(session.playerId(), message)) count++;
                }
            }
            return List.of("System notice sent to " + count + " connected player" + (count == 1 ? "" : "s") + " associated with " + system + ".");
        }
        return List.of("Usage: notice <all <message>|system <system> <message>>");
    }

    private static List<String> observations(HeadlessGameServer host, List<String> args) {
        if (args.size() > 1) return List.of("Usage: observations [player]");
        return host.network.playerObservationLines(args.isEmpty() ? "" : args.get(0));
    }

    private static List<String> dump(HeadlessGameServer host, List<String> args) {
        if (args.size() < 2 || args.size() > 3 || !List.of("player", "system").contains(args.get(0).toLowerCase(Locale.ROOT))) {
            return List.of("Usage: dump <player|system> <selector> [filename]");
        }
        String type = args.get(0).toLowerCase(Locale.ROOT);
        String selector = args.get(1);
        Map<String,Object> root = new LinkedHashMap<>();
        root.put("generatedAt", Instant.now().toString());
        root.put("build", BuildInfo.display());
        if ("player".equals(type)) {
            String playerId = resolvePlayer(host.network, selector);
            if (playerId.isBlank()) return List.of("Unknown player session: " + selector);
            root.put("playerId", playerId);
            PersistentPlayerSession session = session(host.network, playerId);
            root.put("name", session == null ? PlayerRegistry.name(playerId) : session.name());
            root.put("homeSystem", host.world.playerHomeSystemId(playerId));
            root.put("completedResearch", new ArrayList<>(host.world.completedResearch.getOrDefault(playerId, Set.of())));
            root.put("assets", capturePlayerAssets(host.world, playerId));
        } else {
            String system = resolveSystem(host.world, selector);
            if (system.isBlank()) return List.of("Unknown galaxy system: " + selector);
            root.putAll(captureSystem(host.world, system));
        }
        String filename = args.size() == 3 ? safeFilename(args.get(2)) : type + "-" + safeFilename(selector) + "-" + FILE_STAMP.format(Instant.now()) + ".json";
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".json")) filename += ".json";
        Config config = host.network.serverConfig();
        Path path = config.saveDir.resolve("admin-dumps").resolve(filename).normalize();
        if (!path.startsWith(config.saveDir.resolve("admin-dumps").normalize())) return List.of("Invalid dump filename.");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, MiniJson.stringify(root) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            host.network.serverJournal().add("DUMP", type, selector);
            return List.of("Wrote sanitized " + type + " dump: " + path.toAbsolutePath().normalize());
        } catch (IOException ex) {
            return List.of("Could not write dump: " + ex.getMessage());
        }
    }

    private static List<Object> capturePlayerAssets(World world, String playerId) {
        ArrayList<Object> rows = new ArrayList<>();
        visitSystems(world, (system, active) -> {
            for (Unit unit : active.units.values()) if (playerId.equals(unit.playerId)) rows.add(Map.ofEntries(
                    Map.entry("system", system), Map.entry("kind", "ship"), Map.entry("id", unit.key()),
                    Map.entry("type", unit.shipTypeId), Map.entry("loadout", unit.loadoutId),
                    Map.entry("hp", unit.hp), Map.entry("shield", unit.shield),
                    Map.entry("x", unit.x), Map.entry("y", unit.y)));
            for (Base base : active.bases.values()) if (playerId.equals(base.playerId)) rows.add(Map.of(
                    "system", system, "kind", "base", "id", base.id, "type", base.typeId,
                    "hp", base.hp, "shield", base.shield, "x", base.x, "y", base.y,
                    "queue", base.productionQueue.size(), "inventory", new LinkedHashMap<>(base.inventory)));
            return false;
        });
        return rows;
    }

    private static Map<String,Object> captureSystem(World world, String systemId) {
        LinkedHashMap<String,Object> root = new LinkedHashMap<>();
        String previous = world.activeSystemId();
        try {
            world.activateSystem(systemId);
            root.put("systemId", systemId);
            root.put("systemName", world.systemName());
            root.put("systemTime", world.systemTime());
            root.put("ships", world.units.values().stream().map(unit -> Map.of("id", unit.key(), "owner", unit.playerId, "type", unit.shipTypeId,
                    "loadout", unit.loadoutId, "hp", unit.hp, "shield", unit.shield)).toList());
            root.put("bases", world.bases.values().stream().map(base -> Map.of("id", base.id, "owner", base.playerId, "type", base.typeId, "hp", base.hp, "shield", base.shield, "queue", base.productionQueue.size())).toList());
            root.put("resourceNodes", world.resources.size());
            root.put("worldItems", world.items.size());
        } finally { restore(world, previous); }
        return root;
    }

    private static Path writeText(HeadlessGameServer host, String prefix, String text) {
        Config config = host.network.serverConfig();
        Path path = config.saveDir.resolve("admin-dumps").resolve(prefix + "-" + FILE_STAMP.format(Instant.now()) + ".txt");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text == null ? "" : text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return path;
        } catch (IOException ex) { return null; }
    }

    private static LocatedBase findBase(World world, String selector) {
        if (selector == null || selector.isBlank()) return null;
        final LocatedBase[] found = {null};
        visitSystems(world, (system, active) -> {
            Base base = active.bases.get(selector);
            if (base == null) for (Base candidate : active.bases.values()) if (candidate.id.equalsIgnoreCase(selector)) { base = candidate; break; }
            if (base != null) found[0] = new LocatedBase(system, base);
            return false;
        });
        return found[0];
    }

    private static LocatedBase firstFactionBase(World world, String factionId) {
        final LocatedBase[] found = {null};
        visitSystems(world, (system, active) -> {
            for (Base base : active.bases.values()) if (factionId.equals(base.playerId)) { found[0] = new LocatedBase(system, base); break; }
            return false;
        });
        return found[0];
    }

    private static LocatedAsset findAsset(World world, String selector) {
        if (selector == null || selector.isBlank()) return null;
        final LocatedAsset[] found = {null};
        visitSystems(world, (system, active) -> {
            for (Unit unit : active.units.values()) if (unit.key().equalsIgnoreCase(selector)) { found[0] = new LocatedAsset(system, unit, null); return false; }
            for (Base base : active.bases.values()) if (base.id.equalsIgnoreCase(selector)) { found[0] = new LocatedAsset(system, null, base); return false; }
            return false;
        });
        return found[0];
    }

    private static void saveLocated(World world, String systemId) {
        String previous = world.activeSystemId();
        try { world.activateSystem(systemId); world.saveActiveSystem(); }
        finally { restore(world, previous); }
    }

    private static boolean playerHasAssetsIn(World world, String playerId, String systemId) {
        String previous = world.activeSystemId();
        try {
            world.activateSystem(systemId);
            for (Unit unit : world.units.values()) if (playerId.equals(unit.playerId)) return true;
            for (Base base : world.bases.values()) if (playerId.equals(base.playerId)) return true;
            return false;
        } finally { restore(world, previous); }
    }

    private static void visitSystems(World world, SystemVisitor visitor) {
        String previous = world.activeSystemId();
        try {
            for (String system : systemIds(world)) {
                world.activateSystem(system);
                boolean changed = visitor.visit(system, world);
                if (changed) world.saveActiveSystem();
            }
        } finally { restore(world, previous); }
    }

    private static String[] systemIds(World world) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (world.activeSystemId() != null) ids.add(world.activeSystemId());
        GalaxyMapSnapshot snapshot = world.authoritativeGalaxyMapSnapshot();
        if (snapshot != null && snapshot.systems() != null) for (GalaxyMapSystem system : snapshot.systems()) {
            if (system != null && system.id() != null && !system.id().isBlank() && !system.id().contains("WAIT")) ids.add(system.id());
        }
        return ids.toArray(String[]::new);
    }

    private static String resolveSystem(World world, String selector) {
        if (selector == null || selector.isBlank()) return "";
        GalaxyMapSnapshot snapshot = world.authoritativeGalaxyMapSnapshot();
        if (snapshot != null) for (GalaxyMapSystem system : snapshot.systems()) if (system != null
                && (system.id().equalsIgnoreCase(selector) || system.name().equalsIgnoreCase(selector))) return system.id();
        return "";
    }

    private static String resolvePlayer(PeerNetwork network, String selector) {
        PersistentPlayerSession session = session(network, selector);
        return session == null ? "" : session.playerId();
    }

    private static PersistentPlayerSession session(PeerNetwork network, String selector) {
        if (selector == null) return null;
        for (PersistentPlayerSession session : network.persistentPlayerSessions()) if (session != null
                && (session.playerId().equalsIgnoreCase(selector) || session.name().equalsIgnoreCase(selector))) return session;
        return null;
    }

    private static NpcFaction faction(String selector) {
        if (selector == null) return null;
        for (NpcFaction faction : NpcRules.factions()) if (faction.id().equalsIgnoreCase(selector) || faction.name().equalsIgnoreCase(selector)) return faction;
        return null;
    }

    private static NpcDifficultyPreset preset(String selector) {
        if (selector == null) return null;
        String wanted = selector.trim().replace('-', '_').replace(' ', '_');
        for (NpcDifficultyPreset preset : NpcDifficultyPreset.values()) if (preset.name().equalsIgnoreCase(wanted) || preset.label.equalsIgnoreCase(selector)) return preset;
        return null;
    }

    private static String firstEnemyTarget(World world, String factionId) {
        for (Base base : world.bases.values()) if (!factionId.equals(base.playerId) && !NpcRules.isNpcFaction(base.playerId)) return CombatTarget.base(base);
        for (Unit unit : world.units.values()) if (!factionId.equals(unit.playerId) && !NpcRules.isNpcFaction(unit.playerId)) return CombatTarget.unit(unit);
        return "";
    }

    private static int nextUnit(World world, String playerId) {
        int max = 0;
        for (Unit unit : world.units.values()) if (playerId.equals(unit.playerId)) max = Math.max(max, unit.unitId);
        return max + 1;
    }

    private static int nextBase(World world, String playerId) {
        int max = 0;
        for (Base base : world.bases.values()) if (playerId.equals(base.playerId)) {
            String marker = playerId + ":B";
            if (base.id.startsWith(marker)) try { max = Math.max(max, Integer.parseInt(base.id.substring(marker.length()))); } catch (NumberFormatException ignored) { }
        }
        return max + 1;
    }

    private static int indexOf(Base base, String jobId) {
        for (int i = 0; i < base.productionQueue.size(); i++) if (base.productionQueue.get(i).id.equals(jobId)) return i;
        return -1;
    }

    private static void setInventory(EnumMap<Material,Double> inventory, Material material, double value) {
        if (value <= 0) inventory.remove(material); else inventory.put(material, value);
    }

    private static Material material(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try { return Material.valueOf(normalized); } catch (IllegalArgumentException ex) { return null; }
    }

    private static Double amount(String value) {
        double parsed = parseAmountOr(value, Double.NaN);
        return Double.isFinite(parsed) && parsed >= 0 && parsed <= MAX_RESOURCE_AMOUNT ? parsed : null;
    }

    private static double parseAmountOr(String value, double fallback) {
        try { double parsed = Double.parseDouble(value); return Double.isFinite(parsed) ? parsed : fallback; }
        catch (RuntimeException ex) { return fallback; }
    }

    private static double coordinate(String value, double fallback) { return parseAmountOr(value, fallback); }
    private static Boolean flag(String value) { if ("on".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value) || "1".equals(value)) return true; if ("off".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value) || "0".equals(value)) return false; return null; }
    private static String state(boolean enabled) { return enabled ? "enabled" : "disabled"; }
    private static String fmt(double value) { return String.format(Locale.ROOT, "%.1f", Math.max(0, value)); }
    private static String clean(String value) { return ServerModeration.clean(value); }
    private static String bytes(long value) { if (value < 0) return "unknown"; double safe = value; if (safe < 1024) return String.format(Locale.ROOT, "%.0f B", safe); if (safe < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", safe / 1024); if (safe < 1024 * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", safe / 1024 / 1024); return String.format(Locale.ROOT, "%.1f GB", safe / 1024 / 1024 / 1024); }
    private static String safeFilename(String value) { String clean = value == null ? "dump" : value.trim().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", ""); if (clean.isBlank()) clean = "dump"; return clean.length() <= 80 ? clean : clean.substring(0, 80); }
    private static List<String> cap(List<String> rows, String label) { if (rows.isEmpty()) return List.of("No matching " + label + "."); if (rows.size() <= 100) return List.copyOf(rows); ArrayList<String> out = new ArrayList<>(rows.subList(0, 100)); out.add("... " + (rows.size() - 100) + " more omitted."); return List.copyOf(out); }
    private static void restore(World world, String systemId) { if (systemId != null && !systemId.isBlank()) world.activateSystem(systemId); }

    @FunctionalInterface private interface BaseMutation { void apply(Base base); }
    @FunctionalInterface private interface SystemVisitor { boolean visit(String systemId, World world); }
    private record LocatedBase(String systemId, Base base) { }
    private record LocatedAsset(String systemId, Unit unit, Base base) { }
}
