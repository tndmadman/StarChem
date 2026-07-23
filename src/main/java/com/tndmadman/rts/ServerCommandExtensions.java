package com.tndmadman.rts;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Implements the extended command families without expanding the headless server's anonymous target. */
final class ServerCommandExtensions {
    private static final int OUTPUT_LIMIT = 50;
    private static final Set<ModerationKind> BAN_KINDS = Set.of(
            ModerationKind.PLAYER_BAN, ModerationKind.IP_BAN, ModerationKind.DEVICE_BAN);

    private ServerCommandExtensions() { }

    static List<String> execute(ServerCommandDispatcher.Target target, String command, List<String> args) {
        HeadlessGameServer host = host(target);
        if (host == null || host.network == null) return List.of("Extended server command context is unavailable.");
        return switch (command) {
            case "whitelist" -> whitelist(host.network, args);
            case "kick" -> kick(host.network, args);
            case "kicks" -> listModeration(host.network, ModerationKind.KICK);
            case "unkick" -> removeModeration(host.network, args, ModerationKind.KICK, "kick");
            case "ban" -> ban(host.network, args);
            case "bans" -> bans(host.network, args);
            case "unban" -> removeModeration(host.network, args, null, "ban");
            case "pause" -> pause(host.network, args);
            case "prune-systems" -> prune(host, target, args);
            case "health" -> health(host, args);
            case "activity" -> activity(host, args);
            case "factions" -> factions(host.world, args, false);
            case "faction" -> factions(host.world, args, true);
            case "production" -> production(host.world, args);
            case "assets" -> assets(host.world, args, false);
            case "asset" -> assets(host.world, args, true);
            case "research" -> ServerDevCommands.researchInspect(host, args);
            case "observations" -> observations(host.network, args);
            case "tell", "notice", "threads", "memory", "gc-status", "dump" ->
                    ServerDevCommands.diagnostics(host, command, args);
            default -> List.of("Unknown extended command: " + command);
        };
    }

    static void auditCommand(ServerCommandDispatcher.Target target, String command, List<String> args) {
        HeadlessGameServer host = host(target);
        if (host == null || host.network == null) return;
        String detail;
        if ("say".equals(command) || "tell".equals(command) || "notice".equals(command) || "motd".equals(command)) detail = "content redacted";
        else if ("ban".equals(command) || "kick".equals(command)) detail = "moderation arguments redacted";
        else detail = args == null || args.isEmpty() ? "" : String.join(" ", args);
        host.network.serverJournal().add("ADMIN", command, detail);
    }

    private static List<String> whitelist(PeerNetwork network, List<String> args) {
        ServerModerationState state = network.serverModeration();
        if (args == null || args.isEmpty() || "status".equalsIgnoreCase(args.get(0))) {
            return List.of("Whitelist: " + (state.whitelistEnabled() ? "enabled" : "disabled"),
                    "Entries: " + state.whitelist().size());
        }
        String action = args.get(0).toLowerCase(Locale.ROOT);
        if (("on".equals(action) || "off".equals(action)) && args.size() == 1) {
            return persist(network, state.withWhitelistEnabled("on".equals(action)),
                    "Whitelist " + ("on".equals(action) ? "enabled." : "disabled."));
        }
        if ("list".equals(action) && args.size() == 1) {
            if (state.whitelist().isEmpty()) return List.of("Whitelist is empty.");
            ArrayList<String> lines = new ArrayList<>(state.whitelist());
            lines.sort(String::compareTo);
            return List.copyOf(lines);
        }
        if ("add-connected".equals(action) && args.size() == 1) {
            ServerModerationState updated = state;
            for (PersistentPlayerSession session : network.persistentPlayerSessions()) {
                if (session != null && network.serverSessionConnected(session.playerId())) {
                    updated = updated.addWhitelist(session.playerId(), session.name());
                }
            }
            return persist(network, updated, "Added connected players to the whitelist.");
        }
        if (("add".equals(action) || "remove".equals(action)) && args.size() == 2) {
            PersistentPlayerSession session = resolve(network, args.get(1));
            ServerModerationState updated = "add".equals(action)
                    ? state.addWhitelist(session == null ? "" : session.playerId(), session == null ? args.get(1) : session.name())
                    : state.removeWhitelist(session == null ? args.get(1) : session.playerId())
                        .removeWhitelist(session == null ? args.get(1) : session.name());
            return persist(network, updated, "Whitelist entry " + ("add".equals(action) ? "added." : "removed."));
        }
        return List.of("Usage: whitelist <status|on|off|list|add <player-or-name>|remove <player-or-name>|add-connected>");
    }

    private static List<String> kick(PeerNetwork network, List<String> args) {
        if (args == null || args.isEmpty()) return List.of("Usage: kick <player> [duration] [reason]");
        PersistentPlayerSession session = resolve(network, args.get(0));
        if (session == null) return List.of("Unknown player session: " + args.get(0));
        long now = System.currentTimeMillis();
        long expiry = now + 15 * 60_000L;
        int reasonStart = 1;
        if (args.size() >= 2) {
            try { expiry = ServerModeration.parseModerationExpiry(args.get(1), now); reasonStart = 2; }
            catch (IllegalArgumentException ignored) { }
        }
        if (expiry == ServerModeration.PERMANENT) return List.of("Use ban for permanent removal.");
        String reason = join(args, reasonStart);
        ModerationEntry entry = new ModerationEntry("", ModerationKind.KICK, session.playerId(), session.name(),
                session.playerId(), now, expiry, reason);
        ServerModerationState updated = network.serverModeration().add(entry);
        List<String> saved = persist(network, updated, "Kicked " + session.playerId() + " for " + ServerModeration.duration(expiry, now) + ".");
        if (!saved.get(0).startsWith("Could not")) network.disconnectModeratedPlayer(session.playerId(), "Kicked", reason);
        return saved;
    }

    private static List<String> ban(PeerNetwork network, List<String> args) {
        if (args == null || args.size() < 2) {
            return List.of("Usage: ban [player|ip|device|mac] <target> <duration|permanent> [--include-stale] [reason]");
        }
        String type = "player";
        int index = 0;
        if (List.of("player", "ip", "device", "mac").contains(args.get(0).toLowerCase(Locale.ROOT))) {
            type = args.get(0).toLowerCase(Locale.ROOT);
            index = 1;
        }
        if (args.size() <= index + 1) return List.of("Usage: ban [player|ip|device|mac] <target> <duration|permanent> [--include-stale] [reason]");
        String selector = args.get(index);
        long now = System.currentTimeMillis();
        long expiry;
        try { expiry = ServerModeration.parseModerationExpiry(args.get(index + 1), now); }
        catch (IllegalArgumentException ex) { return List.of(ex.getMessage()); }
        int reasonStart = index + 2;
        boolean includeStale = "player".equals(type) && args.size() > reasonStart
                && "--include-stale".equalsIgnoreCase(args.get(reasonStart));
        if (includeStale) reasonStart++;
        String reason = join(args, reasonStart);
        PersistentPlayerSession session = resolve(network, selector);
        ServerPlayerObservationStore.PlayerObservation observation = network.playerObservation(selector);
        ServerPlayerObservationStore.ModerationSignals observationSignals =
                network.playerObservationSignals(selector, includeStale);
        ServerModerationState updated = network.serverModeration();
        ArrayList<String> scopes = new ArrayList<>();
        String playerId = session != null ? session.playerId() : observation == null ? "" : observation.playerId();
        String playerName = session != null ? session.name() : observation != null ? observation.playerName() : ("player".equals(type) ? selector : "");

        if ("player".equals(type)) {
            updated = updated.add(new ModerationEntry("", ModerationKind.PLAYER_BAN, playerId, playerName,
                    playerId.isBlank() ? selector : playerId, now, expiry, reason));
            scopes.add("player");
            LinkedHashSet<String> capturedIps = new LinkedHashSet<>();
            LinkedHashSet<String> capturedDevices = new LinkedHashSet<>();
            if (session != null) {
                InetAddress address = network.serverPlayerAddress(session.playerId());
                String ip = address == null ? "" : address.getHostAddress();
                if (!ip.isBlank()) capturedIps.add(ip);
                String device = network.serverPlayerDeviceId(session.playerId());
                if (ServerDeviceIdentity.valid(device)) capturedDevices.add(device);
            }
            capturedIps.addAll(observationSignals.ips());
            capturedDevices.addAll(observationSignals.devices());
            for (String ip : capturedIps) {
                String normalized = IpBanMatcher.normalize(ip);
                if (normalized.isBlank()) continue;
                updated = updated.add(new ModerationEntry("", ModerationKind.IP_BAN, playerId, playerName, normalized, now, expiry, reason));
                scopes.add("IP " + normalized);
            }
            for (String device : capturedDevices) if (ServerDeviceIdentity.valid(device)) {
                updated = updated.add(new ModerationEntry("", ModerationKind.DEVICE_BAN, playerId, playerName, device, now, expiry, reason));
                scopes.add("device " + ServerDeviceIdentity.mask(device));
            }
        } else if ("ip".equals(type)) {
            String raw = session == null ? selector : addressText(network.serverPlayerAddress(session.playerId()));
            String ip = IpBanMatcher.normalize(raw);
            if (ip.isBlank()) return List.of("IP target must be a numeric IPv4/IPv6 address, CIDR, or connected player.");
            updated = updated.add(new ModerationEntry("", ModerationKind.IP_BAN, playerId, playerName, ip, now, expiry, reason));
            scopes.add("IP " + ip);
        } else {
            String device = session == null ? selector : network.serverPlayerDeviceId(session.playerId());
            if (!ServerDeviceIdentity.valid(device)) return List.of("No valid client device ID is available for that target.");
            updated = updated.add(new ModerationEntry("", ModerationKind.DEVICE_BAN, playerId, playerName, device, now, expiry, reason));
            scopes.add("device " + ServerDeviceIdentity.mask(device));
            if ("mac".equals(type)) scopes.add("MAC unavailable across routed networks; device ID used");
        }

        String resultMessage = "Ban added: " + String.join(", ", scopes) + " | "
                + ServerModeration.duration(expiry, now) + ".";
        if ("player".equals(type) && !includeStale && observationSignals.staleCount() > 0) {
            resultMessage += " Excluded " + observationSignals.staleCount() + " stale retained signal"
                    + (observationSignals.staleCount() == 1 ? "." : "s; use --include-stale to include them.");
        }
        List<String> result = persist(network, updated, resultMessage);
        if (session != null && !result.get(0).startsWith("Could not")) network.disconnectModeratedPlayer(session.playerId(), "Banned", reason);
        return result;
    }

    private static List<String> observations(PeerNetwork network, List<String> args) {
        if (args == null || args.isEmpty()) return network.playerObservationLines("");
        if (args.size() == 1) {
            String action = args.get(0).toLowerCase(Locale.ROOT);
            if ("prune".equals(action)) return List.of(network.prunePlayerObservations().message());
            return network.playerObservationLines(args.get(0));
        }
        String action = args.get(0).toLowerCase(Locale.ROOT);
        if ("delete".equals(action) && args.size() == 2) {
            return List.of(network.deletePlayerObservations(args.get(1)).message());
        }
        if ("clear".equals(action) && args.size() == 2 && "confirm".equalsIgnoreCase(args.get(1))) {
            return List.of(network.clearPlayerObservations().message());
        }
        if ("clear".equals(action)) {
            return List.of("Clearing all observations is destructive. Use: observations clear confirm");
        }
        return List.of("Usage: observations [player]|delete <player>|prune|clear confirm");
    }

    private static List<String> bans(PeerNetwork network, List<String> args) {
        ModerationKind filter = null;
        if (args != null && !args.isEmpty()) {
            if (args.size() != 1) return List.of("Usage: bans [player|ip|device]");
            String requested = args.get(0).toLowerCase(Locale.ROOT);
            if (!List.of("all", "player", "ip", "device", "mac").contains(requested)) {
                return List.of("Usage: bans [all|player|ip|device]");
            }
            filter = switch (requested) {
                case "player" -> ModerationKind.PLAYER_BAN;
                case "ip" -> ModerationKind.IP_BAN;
                case "device", "mac" -> ModerationKind.DEVICE_BAN;
                default -> null;
            };
        }
        ArrayList<ModerationEntry> entries = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (ModerationEntry entry : network.serverModeration().active(null, now)) {
            if (entry.kind() == ModerationKind.KICK) continue;
            if (filter == null || entry.kind() == filter) entries.add(entry);
        }
        if (entries.isEmpty()) return List.of("No active bans.");
        ArrayList<String> lines = new ArrayList<>();
        for (ModerationEntry entry : entries) lines.add(entry.label(now));
        return List.copyOf(lines);
    }

    private static List<String> listModeration(PeerNetwork network, ModerationKind kind) {
        long now = System.currentTimeMillis();
        List<ModerationEntry> entries = network.serverModeration().active(kind, now);
        if (entries.isEmpty()) return List.of("No active " + kind.name().toLowerCase(Locale.ROOT) + " entries.");
        ArrayList<String> lines = new ArrayList<>();
        for (ModerationEntry entry : entries) lines.add(entry.label(now));
        return List.copyOf(lines);
    }

    private static List<String> removeModeration(PeerNetwork network, List<String> args, ModerationKind kind, String label) {
        if (args == null || args.size() != 1) return List.of("Usage: un" + label + " <entry-id|player|name|target>");
        String selector = args.get(0);
        Set<ModerationKind> allowedKinds = kind == null ? BAN_KINDS : Set.of(kind);
        ModerationRemoval removal = resolveModerationRemoval(network.serverModeration(), selector, allowedKinds);
        if (removal.removedCount() == 0) return List.of("No matching " + label + " entry.");
        List<String> result = persist(network, removal.state(), moderationRemovalMessage(removal, label, selector));
        if (!result.get(0).startsWith("Could not")) network.refreshModerationRetention();
        return result;
    }

    static ModerationRemoval resolveModerationRemoval(ServerModerationState state, String selector,
                                                       Set<ModerationKind> allowedKinds) {
        ServerModerationState safe = state == null ? ServerModerationState.open() : state;
        ModerationRemoval exact = safe.removeById(selector, allowedKinds);
        return exact.removedCount() > 0 ? exact : safe.removeBySelector(selector, allowedKinds);
    }

    static String moderationRemovalMessage(ModerationRemoval removal, String label, String selector) {
        int count = removal == null ? 0 : removal.removedCount();
        String safeLabel = label == null || label.isBlank() ? "moderation" : ServerModeration.clean(label);
        if (removal != null && removal.exactId()) {
            return "Removed " + safeLabel + " entry " + ServerModeration.clean(selector) + ".";
        }
        return "Removed " + count + " " + safeLabel + (count == 1 ? " entry" : " entries")
                + " by selector; selector removal may affect multiple records.";
    }

    private static List<String> pause(PeerNetwork network, List<String> args) {
        if (args == null || args.isEmpty() || "status".equalsIgnoreCase(args.get(0))) {
            return List.of("Simulation: " + (network.simulationPaused() ? "paused" : "running"),
                    "Reason: " + (network.simulationPauseReason().isBlank() ? "none" : network.simulationPauseReason()));
        }
        String action = args.get(0).toLowerCase(Locale.ROOT);
        if ("on".equals(action)) {
            network.setSimulationPaused(true, join(args, 1));
            network.broadcastServerNotice("Server simulation paused" + suffix(join(args, 1)));
            return List.of("Simulation paused; console, networking, saves, and shutdown scheduling remain active.");
        }
        if ("off".equals(action) && args.size() == 1) {
            network.setSimulationPaused(false, "");
            network.broadcastServerNotice("Server simulation resumed.");
            return List.of("Simulation resumed.");
        }
        return List.of("Usage: pause <status|on [reason]|off>");
    }

    private static List<String> prune(HeadlessGameServer host, ServerCommandDispatcher.Target target, List<String> args) {
        List<GalaxyMapSystem> candidates = pruneCandidates(host.world);
        if (args == null || args.isEmpty() || "preview".equalsIgnoreCase(args.get(0))) {
            ArrayList<String> lines = new ArrayList<>();
            lines.add("Eligible abandoned dynamic systems: " + candidates.size());
            for (GalaxyMapSystem system : candidates) lines.add(system.id() + " | " + system.name() + " | resources " + system.resources());
            if (candidates.isEmpty()) lines.add("No systems are currently eligible.");
            else lines.add("Run 'prune-systems run confirm' to create a backup and prune.");
            return List.copyOf(lines);
        }
        if (args.size() != 2 || !"run".equalsIgnoreCase(args.get(0)) || !"confirm".equalsIgnoreCase(args.get(1))) {
            return List.of("Usage: prune-systems <preview|run confirm>");
        }
        if (candidates.isEmpty()) return List.of("No abandoned dynamic systems are eligible.");

        Config config = host.network.serverConfig();
        ServerBackupAdmin backupAdmin = new ServerBackupAdmin(config.saveDir, config.saveName, config.backupCount);
        ServerPruneTransaction.Result result = ServerPruneTransaction.run(new ServerPruneTransaction.Operations() {
            @Override public boolean save(String reason) {
                return host.saveForAdmin(reason);
            }

            @Override public ServerBackupAdmin.BackupCreation createBackup(String label) {
                return backupAdmin.createVerified(label);
            }

            @Override public Set<String> prune() {
                return host.world.pruneEmptyDynamicSystems();
            }

            @Override public ServerBackupAdmin.Verification verifyCurrent() {
                return backupAdmin.verifyCurrent();
            }

            @Override public String restoreCurrent(Path backup) {
                return backupAdmin.restoreCurrent(backup);
            }

            @Override public void enterRecovery(String reason) {
                host.enterRecoveryRequired(reason);
            }

            @Override public void publish(Set<String> deleted) {
                host.network.notifyDeletedSystems(deleted);
                host.network.resyncAllServerPlayers();
                host.network.serverJournal().add("PRUNE", "systems", "deleted " + deleted.size());
            }
        });
        return result.lines();
    }

    private static List<GalaxyMapSystem> pruneCandidates(World world) {
        GalaxyMapSnapshot snapshot = world.authoritativeGalaxyMapSnapshot();
        if (snapshot == null || snapshot.systems() == null) return List.of();
        ArrayList<GalaxyMapSystem> out = new ArrayList<>();
        for (GalaxyMapSystem system : snapshot.systems()) {
            if (system == null || system.active() || system.staticSystem()) continue;
            if (system.ships() == 0 && system.bases() == 0) out.add(system);
        }
        out.sort(Comparator.comparing(GalaxyMapSystem::id));
        return List.copyOf(out);
    }

    private static List<String> health(HeadlessGameServer host, List<String> args) {
        List<String> safeArgs = args == null ? List.of() : args;
        String scope = safeArgs.isEmpty() ? "all" : safeArgs.get(0).toLowerCase(Locale.ROOT);
        if (!List.of("all", "disk", "network", "simulation").contains(scope) || safeArgs.size() > 1) {
            return List.of("Usage: health [disk|network|simulation]");
        }
        ArrayList<String> lines = new ArrayList<>();
        Runtime runtime = Runtime.getRuntime();
        if ("all".equals(scope)) {
            long used = runtime.totalMemory() - runtime.freeMemory();
            lines.add("Heap: used " + bytes(used) + " | committed " + bytes(runtime.totalMemory()) + " | max " + bytes(runtime.maxMemory()));
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            lines.add("Threads: live " + threads.getThreadCount() + " | daemon " + threads.getDaemonThreadCount() + " | peak " + threads.getPeakThreadCount());
            lines.add("JVM uptime: " + ServerModeration.duration(System.currentTimeMillis() + ManagementFactory.getRuntimeMXBean().getUptime(), System.currentTimeMillis()));
        }
        Config config = host.network.serverConfig();
        if ("all".equals(scope) || "disk".equals(scope)) {
            Path dir = config.saveDir == null ? Path.of("saves") : config.saveDir;
            try {
                Files.createDirectories(dir);
                FileStore store = Files.getFileStore(dir.toAbsolutePath());
                long usable = store.getUsableSpace();
                long total = store.getTotalSpace();
                String usableText = usable >= Long.MAX_VALUE / 2 ? "unknown" : bytes(usable);
                String totalText = total >= Long.MAX_VALUE / 2 ? "unknown" : bytes(total);
                lines.add("Disk: usable " + usableText + " | total " + totalText);
            } catch (IOException ex) { lines.add("Disk: unavailable: " + ex.getMessage()); }
            Path save = dir.resolve(Config.cleanSaveName(config.saveName) + "-current.starchem-save");
            try { lines.add("Current save: " + (Files.isRegularFile(save) ? bytes(Files.size(save)) + " | " + Files.getLastModifiedTime(save).toInstant() : "missing")); }
            catch (IOException ex) { lines.add("Current save: unavailable"); }
        }
        PerfSnapshot perf = host.network.perfSnapshot();
        if ("all".equals(scope) || "network".equals(scope)) {
            lines.add(String.format(Locale.ROOT, "Network: avg %.3f ms | max %.3f ms | connections %d", perf.networkAvgMs(), perf.networkMaxMs(), perf.activeConnections()));
            lines.add("Outbound queue: " + perf.queuedFrames() + " frames | " + bytes(perf.queuedBytes()));
            lines.add(String.format(Locale.ROOT, "Errors/s: rejected %.2f | overflow %.2f | malformed %.2f | slow-close %.2f",
                    perf.rejectedConnectionsPerSecond(), perf.inboundOverflowsPerSecond(), perf.malformedPacketsPerSecond(), perf.slowConnectionClosesPerSecond()));
        }
        if ("all".equals(scope) || "simulation".equals(scope)) {
            lines.add(String.format(Locale.ROOT, "Simulation: %s | avg %.3f ms | max %.3f ms",
                    host.network.simulationPaused() ? "paused" : "running", perf.serverUpdateAvgMs(), perf.serverUpdateMaxMs()));
        }
        return List.copyOf(lines);
    }

    private static List<String> activity(HeadlessGameServer host, List<String> args) {
        PeerNetwork network = host.network;
        if (args == null || args.isEmpty()) return network.serverJournal().lines(25, "", "");
        String action = args.get(0).toLowerCase(Locale.ROOT);
        if ("clear".equals(action) && args.size() == 1) {
            network.serverJournal().clear();
            return List.of("Activity journal cleared.");
        }
        if ("last".equals(action) && args.size() == 2) {
            try { return network.serverJournal().lines(Integer.parseInt(args.get(1)), "", ""); }
            catch (NumberFormatException ex) { return List.of("Activity count is not numeric."); }
        }
        if ("type".equals(action) && args.size() == 2) return network.serverJournal().lines(100, args.get(1), "");
        if ("player".equals(action) && args.size() == 2) return network.serverJournal().lines(100, "", args.get(1));
        if ("export".equals(action) && args.size() == 2) {
            Path directory = network.serverConfig().saveDir.resolve("admin-dumps");
            Path target = directory.resolve(Path.of(args.get(1)).getFileName().toString()).normalize();
            if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".log")) target = target.resolveSibling(target.getFileName() + ".log");
            try {
                Files.createDirectories(directory);
                network.serverJournal().export(target);
                return List.of("Activity journal exported to " + target.toAbsolutePath().normalize());
            } catch (IOException ex) { return List.of("Could not export activity journal: " + ex.getMessage()); }
        }
        return List.of("Usage: activity [last <count>|player <player>|type <type>|clear|export <filename>]");
    }

    private static List<String> factions(World world, List<String> args, boolean single) {
        List<String> safeArgs = args == null ? List.of() : args;
        String selector = single ? (safeArgs.isEmpty() ? "" : safeArgs.get(0)) : "";
        if (single && (selector.isBlank() || safeArgs.size() != 1)) return List.of("Usage: faction <id-or-name>");
        if (!single && (safeArgs.size() > 1 || safeArgs.size() == 1 && !"npc".equalsIgnoreCase(safeArgs.get(0)))) {
            return List.of("Usage: factions [npc]");
        }
        LinkedHashMap<String,int[]> totals = new LinkedHashMap<>();
        String previous = world.activeSystemId();
        try {
            for (String systemId : systemIds(world)) {
                world.activateSystem(systemId);
                for (Unit unit : world.units.values()) if (unit.hp > 0 && NpcRules.isNpcFaction(unit.playerId)) totals.computeIfAbsent(unit.playerId, ignored -> new int[2])[0]++;
                for (Base base : world.bases.values()) if (base.hp > 0 && NpcRules.isNpcFaction(base.playerId)) totals.computeIfAbsent(base.playerId, ignored -> new int[2])[1]++;
            }
        } finally { if (previous != null && !previous.isBlank()) world.activateSystem(previous); }
        Map<String,Object> runtime = world.captureServerSaveRuntime();
        Object factionState = runtime.get("npcFactions");
        ArrayList<String> lines = new ArrayList<>();
        for (NpcFaction faction : NpcRules.factions()) {
            if (faction == null) continue;
            if (single && !(faction.id().equalsIgnoreCase(selector) || faction.name().equalsIgnoreCase(selector))) continue;
            int[] count = totals.getOrDefault(faction.id(), new int[2]);
            lines.add(faction.id() + " | " + faction.name() + " | " + (faction.enabled() ? "enabled" : "disabled")
                    + " | " + faction.behavior() + " | ships " + count[0] + " | bases " + count[1]);
            if (single) {
                lines.add("Targets: fleet " + faction.targetFleetSize() + " | workers " + faction.maxWorkers()
                        + " | stations " + faction.maxStations() + " | industry " + faction.maxIndustryUnits());
                lines.add("Runtime: " + summarizeValue(factionState, faction.id()));
            }
        }
        if (!single) lines.add("Runtime faction records: " + sizeOf(factionState));
        return lines.isEmpty() ? List.of("No NPC faction matched.") : List.copyOf(lines);
    }

    private static List<String> production(World world, List<String> args) {
        String mode = args == null || args.isEmpty() ? "summary" : args.get(0).toLowerCase(Locale.ROOT);
        String selector = args != null && args.size() > 1 ? args.get(1) : "";
        if (!List.of("summary", "player", "system", "base", "stalled").contains(mode)
                || ((List.of("player", "system", "base").contains(mode)) && selector.isBlank())
                || (args != null && args.size() > 2)) {
            return List.of("Usage: production <summary|player <player>|system <system>|base <base-id>|stalled>");
        }
        ArrayList<String> rows = new ArrayList<>();
        int[] totals = new int[2];
        String previous = world.activeSystemId();
        try {
            for (String systemId : systemIds(world)) {
                world.activateSystem(systemId);
                if ("system".equals(mode) && !systemId.equalsIgnoreCase(selector) && !world.systemName().equalsIgnoreCase(selector)) continue;
                for (Base base : world.bases.values()) {
                    if ("player".equals(mode) && !base.playerId.equalsIgnoreCase(resolvePlayerId(world, selector))) continue;
                    if ("base".equals(mode) && !base.id.equalsIgnoreCase(selector)) continue;
                    for (ProductionJob job : base.productionQueue) {
                        totals[0]++;
                        boolean stalled = job.blockedReason != null && !job.blockedReason.isBlank();
                        if (stalled) totals[1]++;
                        if ("summary".equals(mode)) continue;
                        if ("stalled".equals(mode) && !stalled) continue;
                        rows.add(systemId + " | " + base.id + " | " + base.playerId + " | " + job.kind + " " + job.itemId
                                + " | remaining " + String.format(Locale.ROOT, "%.1fs", Math.max(0, job.remaining))
                                + (stalled ? " | blocked " + ServerModeration.clean(job.blockedReason) : ""));
                    }
                }
            }
        } finally { if (previous != null && !previous.isBlank()) world.activateSystem(previous); }
        if ("summary".equals(mode)) return List.of("Production jobs: " + totals[0] + " | stalled " + totals[1]);
        return cap(rows, "production jobs");
    }

    private static List<String> assets(World world, List<String> args, boolean single) {
        String mode;
        String selector;
        String kind = "all";
        if (single) {
            if (args == null || args.size() != 1) return List.of("Usage: asset <unit-or-base-id>");
            mode = "asset";
            selector = args.get(0);
        } else {
            if (args == null || args.size() < 2 || args.size() > 3) return List.of("Usage: assets <player|system> <selector> [ships|bases]");
            mode = args.get(0).toLowerCase(Locale.ROOT);
            selector = args.get(1);
            if (args.size() == 3) kind = args.get(2).toLowerCase(Locale.ROOT);
            if (!List.of("player", "system").contains(mode) || !List.of("all", "ships", "bases").contains(kind)) {
                return List.of("Usage: assets <player|system> <selector> [ships|bases]");
            }
        }
        String playerId = "player".equals(mode) ? resolvePlayerId(world, selector) : "";
        ArrayList<String> rows = new ArrayList<>();
        String previous = world.activeSystemId();
        try {
            for (String systemId : systemIds(world)) {
                world.activateSystem(systemId);
                if ("system".equals(mode) && !systemId.equalsIgnoreCase(selector) && !world.systemName().equalsIgnoreCase(selector)) continue;
                if (!"bases".equals(kind)) {
                    for (Unit unit : world.units.values()) {
                        if ("player".equals(mode) && !unit.playerId.equalsIgnoreCase(playerId)) continue;
                        if ("asset".equals(mode) && !(unit.key().equalsIgnoreCase(selector) || String.valueOf(unit.unitId).equals(selector))) continue;
                        rows.add(systemId + " | ship " + unit.key() + " | owner " + unit.playerId + " | type " + unit.shipTypeId
                                + " | hp " + fmt(unit.hp) + " | shield " + fmt(unit.shield) + " | task " + unit.task
                                + " | order " + unit.orderType + " | cargo " + fmt(sum(unit.inventory)));
                    }
                }
                if (!"ships".equals(kind)) {
                    for (Base base : world.bases.values()) {
                        if ("player".equals(mode) && !base.playerId.equalsIgnoreCase(playerId)) continue;
                        if ("asset".equals(mode) && !base.id.equalsIgnoreCase(selector)) continue;
                        rows.add(systemId + " | base " + base.id + " | owner " + base.playerId + " | type " + base.typeId
                                + " | hp " + fmt(base.hp) + " | shield " + fmt(base.shield) + " | queue " + base.productionQueue.size()
                                + " | inventory " + fmt(sum(base.inventory)));
                    }
                }
            }
        } finally { if (previous != null && !previous.isBlank()) world.activateSystem(previous); }
        return cap(rows, "assets");
    }

    private static List<String> persist(PeerNetwork network, ServerModerationState state, String success) {
        String error = network.saveServerModeration(state);
        if (error != null) return List.of(error);
        network.serverJournal().add("MODERATION", "server", success);
        return List.of(success);
    }

    private static PersistentPlayerSession resolve(PeerNetwork network, String selector) {
        if (selector == null) return null;
        for (PersistentPlayerSession session : network.persistentPlayerSessions()) {
            if (session != null && (session.playerId().equalsIgnoreCase(selector) || session.name().equalsIgnoreCase(selector))) return session;
        }
        return null;
    }

    private static String resolvePlayerId(World world, String selector) {
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) {
            if (player.id().equalsIgnoreCase(selector) || player.name().equalsIgnoreCase(selector)) return player.id();
        }
        return selector;
    }

    private static HeadlessGameServer host(ServerCommandDispatcher.Target target) {
        if (target == null) return null;
        Object context = target.extensionContext();
        if (context instanceof HeadlessGameServer host) return host;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!HeadlessGameServer.class.isAssignableFrom(field.getType())) continue;
                try { field.setAccessible(true); return (HeadlessGameServer)field.get(target); }
                catch (ReflectiveOperationException ignored) { }
            }
        }
        return null;
    }

    private static String[] systemIds(World world) {
        GalaxyMapSnapshot snapshot = world.authoritativeGalaxyMapSnapshot();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(world.activeSystemId());
        if (snapshot != null && snapshot.systems() != null) for (GalaxyMapSystem system : snapshot.systems()) if (system != null) ids.add(system.id());
        ids.removeIf(value -> value == null || value.isBlank() || value.contains("WAIT"));
        return ids.toArray(String[]::new);
    }

    private static String summarizeValue(Object value, String selector) {
        if (value instanceof Map<?,?> map) {
            for (Map.Entry<?,?> entry : map.entrySet()) if (String.valueOf(entry.getKey()).equalsIgnoreCase(selector)) return ServerModeration.clean(String.valueOf(entry.getValue()));
            return "map entries " + map.size();
        }
        if (value instanceof List<?> list) return "list entries " + list.size();
        return ServerModeration.clean(String.valueOf(value));
    }
    private static int sizeOf(Object value) { return value instanceof Map<?,?> map ? map.size() : value instanceof List<?> list ? list.size() : value == null ? 0 : 1; }
    private static String join(List<String> args, int from) { return args == null || from >= args.size() ? "" : ServerModeration.clean(String.join(" ", args.subList(from, args.size()))); }
    private static String suffix(String reason) { return reason == null || reason.isBlank() ? "." : ": " + reason; }
    private static String addressText(InetAddress address) { return address == null ? "" : address.getHostAddress(); }
    private static String fmt(double value) { return String.format(Locale.ROOT, "%.1f", Math.max(0, value)); }
    private static double sum(Map<?,? extends Number> values) { double total = 0; if (values != null) for (Number value : values.values()) if (value != null) total += value.doubleValue(); return total; }
    private static String bytes(double value) { double safe = Math.max(0, value); if (safe < 1024) return String.format(Locale.ROOT, "%.0f B", safe); if (safe < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", safe / 1024); if (safe < 1024 * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", safe / 1024 / 1024); return String.format(Locale.ROOT, "%.1f GB", safe / 1024 / 1024 / 1024); }
    private static List<String> cap(List<String> rows, String label) { if (rows.isEmpty()) return List.of("No matching " + label + "."); if (rows.size() <= OUTPUT_LIMIT) return List.copyOf(rows); ArrayList<String> out = new ArrayList<>(rows.subList(0, OUTPUT_LIMIT)); out.add("... " + (rows.size() - OUTPUT_LIMIT) + " more omitted."); return List.copyOf(out); }
}
