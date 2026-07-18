package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

final class HeadlessGameServer {
    private static final int MAX_CONSOLE_COMMANDS_PER_TICK = 32;
    private static final long NO_SHUTDOWN = Long.MAX_VALUE;
    private static final long[] SHUTDOWN_NOTICE_SECONDS = {600, 300, 60, 30, 10, 5, 4, 3, 2, 1};
    private static final DateTimeFormatter UTC_TIME = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    final World world;
    final PeerNetwork network;
    private final Config config;
    private final ServerSaveStore saves;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();
    private long nextAutosaveNanos;
    private long autosaveCount;
    private long manualSaveCount;
    private Instant lastSuccessfulSaveAt;
    private String lastSuccessfulSaveReason = "none";
    private long shutdownDeadlineNanos = NO_SHUTDOWN;
    private long lastShutdownNoticeSeconds = Long.MAX_VALUE;
    private String shutdownReason = "";
    private ServerConsole console;
    private ServerCommandDispatcher consoleCommands;

    private HeadlessGameServer(World world, PeerNetwork network, Config config, ServerSaveStore saves) {
        this.world = world;
        this.network = network;
        this.config = config;
        this.saves = saves;
        scheduleNextAutosave();
    }

    static HeadlessGameServer start(Config config) throws IOException {
        if (config == null || !config.dedicatedServerMode()) {
            throw new IllegalArgumentException("HeadlessGameServer requires dedicated server configuration.");
        }
        GalaxyRuntimeOptions.configure(config);
        ServerSaveStore saves = new ServerSaveStore(config.saveDir, config.saveName, config.backupCount);
        Optional<World> loaded = config.newWorld ? Optional.empty() : saves.load(config);
        World world = loaded.orElseGet(() -> new World(config.playerName, config.disabledNpcFactionIds, config.systemId, false));
        if (loaded.isPresent()) System.out.println("Loaded server save '" + config.saveName + "'.");
        else System.out.println(config.newWorld ? "Starting a new server world by request." : "No server save found; starting a new world.");
        DevTimerSettings.configure(world, config.disableProductionTimers);
        PeerNetwork network = PeerNetwork.start(config, world, saves.loadedPlayerSessions());
        if (network == null) throw new IOException("Dedicated server network did not start.");
        return new HeadlessGameServer(world, network, config, saves);
    }

    void attachConsole(ServerConsole console) {
        if (console == null) return;
        if (stopped.get()) {
            console.close();
            return;
        }
        ServerConsole previous = this.console;
        if (previous != null) previous.close();
        this.console = console;
        this.consoleCommands = new ServerCommandDispatcher(new ServerCommandDispatcher.Target() {
            @Override public String status() { return consoleStatusLine(); }
            @Override public List<String> players() { return playerStatusLines(); }
            @Override public List<String> uptime() { return uptimeLines(); }
            @Override public List<String> performance(String scope) { return performanceLines(scope); }
            @Override public List<String> systems(String filter, String value) { return systemLines(filter, value); }
            @Override public List<String> system(String selector) { return systemDetailLines(selector); }
            @Override public List<String> connection(String selector) { return connectionLines(selector); }
            @Override public List<String> saveInfo() { return saveInfoLines(); }
            @Override public String announce(String message) { return announceNow(message); }
            @Override public String scheduleShutdown(long delaySeconds, String reason) { return HeadlessGameServer.this.scheduleShutdown(delaySeconds, reason); }
            @Override public String cancelShutdown() { return HeadlessGameServer.this.cancelShutdown(); }
            @Override public String shutdownStatus() { return HeadlessGameServer.this.shutdownStatus(); }
            @Override public String disconnect(String selector, String reason) { return disconnectPlayer(selector, reason); }
            @Override public List<String> developer(List<String> args) { return developerCommand(args); }
            @Override public boolean save() { return saveNow("manual-console"); }
            @Override public void stop() { HeadlessGameServer.this.stop(); }
            @Override public boolean running() { return HeadlessGameServer.this.running(); }
        }, System.out, System.err);
    }

    void tick(double dt) {
        drainConsoleCommands();
        if (stopped.get()) return;
        processScheduledShutdown();
        if (stopped.get()) return;
        network.updateServerWorlds(dt);
        network.tick();
        autosaveIfDue();
    }

    boolean running() {
        return !stopped.get();
    }

    String statusLine() {
        return stopped.get() ? "SERVER STOPPED" : network.statusLine();
    }

    void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        shutdownDeadlineNanos = NO_SHUTDOWN;
        ServerConsole activeConsole = console;
        console = null;
        consoleCommands = null;
        if (activeConsole != null) activeConsole.close();
        saveNow("shutdown");
        network.shutdown();
        System.out.println("Dedicated server stopped.");
    }

    private void drainConsoleCommands() {
        ServerConsole activeConsole = console;
        ServerCommandDispatcher dispatcher = consoleCommands;
        if (activeConsole == null || dispatcher == null) return;
        for (int i = 0; i < MAX_CONSOLE_COMMANDS_PER_TICK; i++) {
            String command = activeConsole.poll();
            if (command == null) return;
            dispatcher.execute(command);
            if (stopped.get()) return;
        }
    }

    private String consoleStatusLine() {
        String autosave = config.autosaveSeconds <= 0
                ? "autosave disabled"
                : "autosave every " + config.autosaveSeconds + "s";
        String shutdown = shutdownDeadlineNanos == NO_SHUTDOWN ? "" : " | shutdown " + shutdownRemainingSeconds() + "s";
        return statusLine() + " | save " + config.saveName + " | " + autosave + shutdown;
    }

    private List<String> playerStatusLines() {
        List<PersistentPlayerSession> sessions = sortedSessions();
        List<String> lines = new ArrayList<>();
        for (PersistentPlayerSession session : sessions) {
            String state = network.serverSessionConnected(session.playerId()) ? "connected" : "retained";
            lines.add(session.playerId() + " | " + session.name() + " | " + state);
        }
        return List.copyOf(lines);
    }

    private List<String> uptimeLines() {
        long uptimeSeconds = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000_000L);
        String autosave = config.autosaveSeconds <= 0
                ? "disabled"
                : formatDuration(Math.max(0, (nextAutosaveNanos - System.nanoTime() + 999_999_999L) / 1_000_000_000L));
        List<String> lines = new ArrayList<>();
        lines.add("Started: " + UTC_TIME.format(startedAt));
        lines.add("Uptime: " + formatDuration(uptimeSeconds));
        lines.add("Saves: manual " + manualSaveCount + " | autosave " + autosaveCount);
        lines.add("Next autosave: " + autosave);
        if (lastSuccessfulSaveAt != null) {
            lines.add("Last save: " + UTC_TIME.format(lastSuccessfulSaveAt) + " | " + lastSuccessfulSaveReason);
        }
        return List.copyOf(lines);
    }

    private List<String> performanceLines(String scope) {
        PerfSnapshot perf = network.perfSnapshot();
        List<String> lines = new ArrayList<>();
        if (!"network".equals(scope)) {
            lines.add(String.format(Locale.ROOT, "Simulation: avg %.3f ms | max %.3f ms",
                    perf.serverUpdateAvgMs(), perf.serverUpdateMaxMs()));
            lines.add(String.format(Locale.ROOT, "Frame/update: %.1f fps | avg %.3f ms | max %.3f ms",
                    perf.fps(), perf.updateAvgMs(), perf.updateMaxMs()));
        }
        if (!"simulation".equals(scope)) {
            lines.add(String.format(Locale.ROOT, "Network: avg %.3f ms | max %.3f ms",
                    perf.networkAvgMs(), perf.networkMaxMs()));
            lines.add(String.format(Locale.ROOT, "Traffic: sent %s/s | received %s/s | snapshots %.1f/s",
                    humanBytes(perf.bytesSentPerSecond()), humanBytes(perf.bytesReceivedPerSecond()), perf.snapshotsSentPerSecond()));
            lines.add("Connections: " + perf.activeConnections() + " | queued " + perf.queuedFrames()
                    + " frames / " + humanBytes(perf.queuedBytes()));
            lines.add(String.format(Locale.ROOT,
                    "Network events/s: rejected %.2f | slow-close %.2f | overflow %.2f | malformed %.2f | coalesced %.2f",
                    perf.rejectedConnectionsPerSecond(), perf.slowConnectionClosesPerSecond(),
                    perf.inboundOverflowsPerSecond(), perf.malformedPacketsPerSecond(), perf.coalescedSnapshotsPerSecond()));
        }
        return List.copyOf(lines);
    }

    private List<String> systemLines(String filter, String value) {
        GalaxyMapSnapshot snapshot = world.authoritativeGalaxyMapSnapshot();
        if (snapshot == null || snapshot.empty()) return List.of("No authoritative galaxy systems are available.");
        String wanted = value == null ? "" : value.trim();
        String resolvedPlayerId = "player".equals(filter) ? resolvePlayerId(wanted) : "";
        List<GalaxyMapSystem> systems = new ArrayList<>(snapshot.systems());
        systems.sort(Comparator.comparing(GalaxyMapSystem::id));
        List<String> lines = new ArrayList<>();
        for (GalaxyMapSystem system : systems) {
            if (system == null) continue;
            if ("active".equals(filter) && !system.active()) continue;
            if ("controlled".equals(filter) && blank(system.controllerId())) continue;
            if ("player".equals(filter)) {
                boolean matchesId = !resolvedPlayerId.isBlank() && resolvedPlayerId.equalsIgnoreCase(system.controllerId());
                boolean matchesName = !wanted.isBlank() && wanted.equalsIgnoreCase(system.controllerName());
                if (!matchesId && !matchesName) continue;
            }
            lines.add(systemSummary(system));
        }
        if (lines.isEmpty()) return List.of("No galaxy systems matched that filter.");
        return List.copyOf(lines);
    }

    private List<String> systemDetailLines(String selector) {
        GalaxyMapSnapshot snapshot = world.authoritativeGalaxyMapSnapshot();
        GalaxyMapSystem system = findSystem(snapshot, selector);
        if (system == null) return List.of("Unknown galaxy system: " + selector);
        List<String> links = new ArrayList<>();
        if (snapshot.links() != null) {
            for (GalaxyMapLink link : snapshot.links()) {
                if (link == null) continue;
                if (system.id().equals(link.fromSystemId())) links.add(link.toSystemId());
                else if (system.id().equals(link.toSystemId())) links.add(link.fromSystemId());
            }
        }
        links.sort(String::compareTo);
        String controller = blank(system.controllerId()) ? "neutral" : system.controllerName() + " (" + system.controllerId() + ")";
        return List.of(
                system.name() + " (" + system.id() + ")",
                "Template: " + system.templateId() + " | lifetime " + system.lifetime(),
                "Objects: ships " + system.ships() + " | bases " + system.bases() + " | resources " + system.resources(),
                "Local: ships " + system.localShips() + " | bases " + system.localBases(),
                "Control: " + controller + " | " + system.controlStatus() + " | " + percent(system.captureProgress()),
                "Flags: active " + system.active() + " | home " + system.home() + " | special " + system.special(),
                "Links: " + (links.isEmpty() ? "none" : String.join(", ", links)));
    }

    private List<String> connectionLines(String selector) {
        String playerId = resolvePlayerId(selector);
        if (playerId.isBlank()) return List.of("Unknown player session: " + selector);
        PersistentPlayerSession session = sessionById(playerId);
        boolean connected = network.serverSessionConnected(playerId);
        ConnectionId connectionId = network.connectionIdForPlayer(playerId);
        ConnectionDiagnostics diagnostics = network.connectionDiagnostics(connectionId);
        List<String> lines = new ArrayList<>();
        lines.add(playerId + " | " + (session == null ? playerId : session.name()) + " | " + (connected ? "connected" : "retained"));
        lines.add("Connection: " + connectionId + " | open " + diagnostics.open());
        lines.add("Outbound: " + diagnostics.queuedFrames() + " frames | " + humanBytes(diagnostics.queuedBytes())
                + " | coalesced snapshots " + diagnostics.coalescedSnapshots());
        return List.copyOf(lines);
    }

    private List<String> saveInfoLines() {
        Path current = currentSavePath();
        Path previous = previousSavePath();
        List<String> lines = new ArrayList<>();
        lines.add("Save: " + current.toAbsolutePath().normalize());
        lines.add("Current: " + fileState(current));
        lines.add("Previous fallback: " + fileState(previous));
        lines.add("Backup retention: " + config.backupCount);
        lines.add("Format: " + ServerSaveStore.SAVE_FORMAT_VERSION + " | name " + config.saveName);
        if (lastSuccessfulSaveAt == null) lines.add("Last save this process: none");
        else lines.add("Last save this process: " + UTC_TIME.format(lastSuccessfulSaveAt) + " | " + lastSuccessfulSaveReason);
        return List.copyOf(lines);
    }

    private String announceNow(String message) {
        String notice = cleanNotice(message);
        if (notice.isBlank()) return "Server notice was empty.";
        int recipients = network.broadcastServerNotice(notice);
        world.status = "SERVER: " + notice;
        System.out.println(world.status);
        return "Server notice sent to " + recipients + " connected client" + (recipients == 1 ? "" : "s") + ".";
    }

    private String scheduleShutdown(long delaySeconds, String reason) {
        if (delaySeconds < 1) return "Shutdown delay must be at least one second.";
        shutdownDeadlineNanos = System.nanoTime() + delaySeconds * 1_000_000_000L;
        lastShutdownNoticeSeconds = Long.MAX_VALUE;
        shutdownReason = cleanNotice(reason);
        String message = "Server shutdown scheduled in " + formatDuration(delaySeconds) + shutdownReasonSuffix();
        network.broadcastServerNotice(message);
        System.out.println(message);
        return message;
    }

    private String cancelShutdown() {
        if (shutdownDeadlineNanos == NO_SHUTDOWN) return "No shutdown is scheduled.";
        shutdownDeadlineNanos = NO_SHUTDOWN;
        lastShutdownNoticeSeconds = Long.MAX_VALUE;
        shutdownReason = "";
        network.broadcastServerNotice("Scheduled server shutdown was cancelled.");
        return "Scheduled server shutdown cancelled.";
    }

    private String shutdownStatus() {
        if (shutdownDeadlineNanos == NO_SHUTDOWN) return "No shutdown is scheduled.";
        return "Shutdown in " + formatDuration(shutdownRemainingSeconds()) + shutdownReasonSuffix();
    }

    private String disconnectPlayer(String selector, String reason) {
        String playerId = resolvePlayerId(selector);
        if (playerId.isBlank()) return "Unknown player session: " + selector;
        if (!network.serverSessionConnected(playerId)) return playerId + " is not currently connected; its session is retained.";
        boolean disconnected = network.disconnectServerPlayer(playerId);
        if (!disconnected) return "Could not disconnect " + playerId + ".";
        String cleanReason = cleanNotice(reason);
        return "Disconnected " + playerId + (cleanReason.isBlank() ? "." : ": " + cleanReason)
                + " The session remains eligible for reconnect.";
    }

    private List<String> developerCommand(List<String> args) {
        if (!config.devMode) return List.of("Developer console commands require the server to start with --dev.");
        if (args == null || args.isEmpty() || "status".equalsIgnoreCase(args.get(0))) return developerStatusLines();
        String group = args.get(0).toLowerCase(Locale.ROOT);
        if ("access".equals(group)) return developerAccess(args);
        if ("ai".equals(group)) return developerAi(args);
        if ("timers".equals(group)) return developerTimers(args);
        if ("trigger".equals(group)) return developerTrigger(args);
        if ("spawn".equals(group)) return developerSpawn(args);
        if ("remove".equals(group)) return developerRemove(args);
        if ("reset".equals(group)) return developerReset(args);
        return List.of("Usage: dev <status|access|ai|timers|trigger|spawn|remove|reset> ...");
    }

    private List<String> developerStatusLines() {
        return List.of(
                "Developer host: enabled",
                "AI: " + (AiDevSettings.pauseAi ? "paused" : "running") + " | speed " + (AiDevSettings.fastAi ? "fast" : "normal"),
                "Combat: player units frozen " + AiDevSettings.freezePlayerUnits + " | NPC combat frozen " + AiDevSettings.freezeNpcCombat,
                "Rules: attacks disabled " + AiDevSettings.disableAttacks + " | economy disabled " + AiDevSettings.disableEconomy,
                "Production timers: " + (config.disableProductionTimers ? "startup-disabled; runtime state may differ" : "startup-enabled"),
                "Difficulty: " + NpcDifficultyPreset.current().label);
    }

    private List<String> developerAccess(List<String> args) {
        if (args.size() != 3 || !("grant".equalsIgnoreCase(args.get(1)) || "revoke".equalsIgnoreCase(args.get(1)))) {
            return List.of("Usage: dev access <grant|revoke> <player-id-or-name>");
        }
        String playerId = resolvePlayerId(args.get(2));
        if (playerId.isBlank()) return List.of("Unknown player session: " + args.get(2));
        if (!network.serverSessionConnected(playerId)) return List.of(playerId + " is not connected.");
        boolean enabled = "grant".equalsIgnoreCase(args.get(1));
        network.setRemoteDevAccess(playerId, enabled);
        return List.of("Developer access " + (enabled ? "granted to " : "revoked from ") + playerId + ".");
    }

    private List<String> developerAi(List<String> args) {
        if (args.size() == 2 && "step".equalsIgnoreCase(args.get(1))) {
            AiDevSettings.stepAi = true;
            return List.of("AI will advance one step.");
        }
        if (args.size() != 3) return List.of("Usage: dev ai <pause on|off|speed normal|fast|step>");
        String action = args.get(1).toLowerCase(Locale.ROOT);
        String value = args.get(2).toLowerCase(Locale.ROOT);
        if ("pause".equals(action)) {
            Boolean enabled = onOff(value);
            if (enabled == null) return List.of("Usage: dev ai pause <on|off>");
            AiDevSettings.pauseAi = enabled;
            if (!enabled) AiDevSettings.stepAi = false;
            return List.of("AI pause " + (enabled ? "enabled" : "disabled") + ".");
        }
        if ("speed".equals(action) && ("normal".equals(value) || "fast".equals(value))) {
            AiDevSettings.fastAi = "fast".equals(value);
            return List.of("AI speed set to " + value + ".");
        }
        return List.of("Usage: dev ai <pause on|off|speed normal|fast|step>");
    }

    private List<String> developerTimers(List<String> args) {
        if (args.size() != 2) return List.of("Usage: dev timers <on|off>");
        Boolean enabled = onOff(args.get(1));
        if (enabled == null) return List.of("Usage: dev timers <on|off>");
        DevTimerSettings.configure(world, !enabled);
        return List.of("Production timers " + (enabled ? "enabled" : "disabled") + ".");
    }

    private List<String> developerTrigger(List<String> args) {
        if (args.size() != 2) return List.of("Usage: dev trigger <raid|station|research|craft>");
        String command = switch (args.get(1).toLowerCase(Locale.ROOT)) {
            case "raid" -> "forceRaid";
            case "station" -> "forceStation";
            case "research" -> "forceResearch";
            case "craft" -> "forceCraft";
            default -> "";
        };
        if (command.isBlank()) return List.of("Usage: dev trigger <raid|station|research|craft>");
        network.devAiCommand("SOLO", command);
        return List.of("Developer trigger executed: " + args.get(1).toLowerCase(Locale.ROOT) + ".");
    }

    private List<String> developerSpawn(List<String> args) {
        if (args.size() != 2) return List.of("Usage: dev spawn <corsairs|loot|wave>");
        String command = switch (args.get(1).toLowerCase(Locale.ROOT)) {
            case "corsairs" -> "spawnCorsairs";
            case "loot" -> "spawnLootField";
            case "wave" -> "spawnAttackWave";
            default -> "";
        };
        if (command.isBlank()) return List.of("Usage: dev spawn <corsairs|loot|wave>");
        network.devAiCommand("SOLO", command);
        return List.of("Developer spawn executed: " + args.get(1).toLowerCase(Locale.ROOT) + ".");
    }

    private List<String> developerRemove(List<String> args) {
        if (args.size() != 2 || !"corsairs".equalsIgnoreCase(args.get(1))) return List.of("Usage: dev remove corsairs");
        network.devAiCommand("SOLO", "killCorsairs");
        return List.of("Corsair units removed.");
    }

    private List<String> developerReset(List<String> args) {
        if (args.size() != 2 || !"corsairs".equalsIgnoreCase(args.get(1))) return List.of("Usage: dev reset corsairs");
        network.devAiCommand("SOLO", "resetCorsairs");
        return List.of("Corsair state reset.");
    }

    private void processScheduledShutdown() {
        if (shutdownDeadlineNanos == NO_SHUTDOWN) return;
        long remaining = shutdownRemainingSeconds();
        if (remaining <= 0) {
            network.broadcastServerNotice("Server is shutting down now" + shutdownReasonSuffix());
            stop();
            return;
        }
        if (remaining < lastShutdownNoticeSeconds && shutdownNotice(remaining)) {
            lastShutdownNoticeSeconds = remaining;
            String notice = "Server shutdown in " + formatDuration(remaining) + shutdownReasonSuffix();
            network.broadcastServerNotice(notice);
            System.out.println(notice);
        }
    }

    private boolean shutdownNotice(long seconds) {
        for (long threshold : SHUTDOWN_NOTICE_SECONDS) if (seconds == threshold) return true;
        return false;
    }

    private long shutdownRemainingSeconds() {
        if (shutdownDeadlineNanos == NO_SHUTDOWN) return -1;
        long nanos = shutdownDeadlineNanos - System.nanoTime();
        return nanos <= 0 ? 0 : (nanos + 999_999_999L) / 1_000_000_000L;
    }

    private String shutdownReasonSuffix() {
        return shutdownReason.isBlank() ? "." : ": " + shutdownReason;
    }

    private void autosaveIfDue() {
        if (config.autosaveSeconds <= 0 || System.nanoTime() < nextAutosaveNanos) return;
        saveNow("autosave");
        scheduleNextAutosave();
    }

    private void scheduleNextAutosave() {
        nextAutosaveNanos = config.autosaveSeconds <= 0
                ? Long.MAX_VALUE
                : System.nanoTime() + config.autosaveSeconds * 1_000_000_000L;
    }

    private boolean saveNow(String reason) {
        try {
            saves.save(world, config, reason, network.persistentPlayerSessions());
            lastSuccessfulSaveAt = Instant.now();
            lastSuccessfulSaveReason = reason;
            if ("autosave".equals(reason)) autosaveCount++;
            else if ("manual-console".equals(reason)) manualSaveCount++;
            if (!"autosave".equals(reason)) System.out.println("Server save completed (" + reason + ").");
            return true;
        } catch (IOException ex) {
            System.err.println("Server save failed (" + reason + "): " + ex.getMessage());
            return false;
        }
    }

    private List<PersistentPlayerSession> sortedSessions() {
        List<PersistentPlayerSession> sessions = new ArrayList<>(network.persistentPlayerSessions());
        sessions.sort(Comparator.comparing(PersistentPlayerSession::playerId));
        return sessions;
    }

    private PersistentPlayerSession sessionById(String playerId) {
        for (PersistentPlayerSession session : network.persistentPlayerSessions()) {
            if (session != null && session.playerId().equalsIgnoreCase(playerId)) return session;
        }
        return null;
    }

    private String resolvePlayerId(String selector) {
        if (selector == null || selector.isBlank()) return "";
        String wanted = selector.trim();
        for (PersistentPlayerSession session : network.persistentPlayerSessions()) {
            if (session == null) continue;
            if (wanted.equalsIgnoreCase(session.playerId()) || wanted.equalsIgnoreCase(session.name())) return session.playerId();
        }
        return "";
    }

    private GalaxyMapSystem findSystem(GalaxyMapSnapshot snapshot, String selector) {
        if (snapshot == null || snapshot.empty() || selector == null || selector.isBlank()) return null;
        for (GalaxyMapSystem system : snapshot.systems()) {
            if (system != null && (selector.equalsIgnoreCase(system.id()) || selector.equalsIgnoreCase(system.name()))) return system;
        }
        return null;
    }

    private String systemSummary(GalaxyMapSystem system) {
        String controller = blank(system.controllerId()) ? "neutral" : system.controllerName() + " (" + system.controllerId() + ")";
        return system.id() + " | " + system.name() + " | ships " + system.ships() + " | bases " + system.bases()
                + " | resources " + system.resources() + " | " + controller;
    }

    private Path currentSavePath() {
        Path dir = config.saveDir == null ? Path.of("saves") : config.saveDir;
        return dir.resolve(Config.cleanSaveName(config.saveName) + "-current.starchem-save");
    }

    private Path previousSavePath() {
        Path dir = config.saveDir == null ? Path.of("saves") : config.saveDir;
        return dir.resolve(Config.cleanSaveName(config.saveName) + "-previous.starchem-save");
    }

    private String fileState(Path path) {
        try {
            if (!Files.exists(path)) return "missing";
            return humanBytes(Files.size(path)) + " | modified " + UTC_TIME.format(Files.getLastModifiedTime(path).toInstant());
        } catch (IOException ex) {
            return "unavailable: " + ex.getMessage();
        }
    }

    private String cleanNotice(String value) {
        if (value == null) return "";
        String clean = value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= 512 ? clean : clean.substring(0, 512);
    }

    private Boolean onOff(String value) {
        if ("on".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value) || "1".equals(value)) return true;
        if ("off".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value) || "0".equals(value)) return false;
        return null;
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", Math.max(0, Math.min(1, value)) * 100.0);
    }

    private String humanBytes(double bytes) {
        double safe = Math.max(0, bytes);
        if (safe < 1024) return String.format(Locale.ROOT, "%.0f B", safe);
        if (safe < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", safe / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", safe / (1024.0 * 1024.0));
    }

    private String formatDuration(long seconds) {
        long safe = Math.max(0, seconds);
        long days = safe / 86_400;
        long hours = safe % 86_400 / 3_600;
        long minutes = safe % 3_600 / 60;
        long remainder = safe % 60;
        if (days > 0) return days + "d " + hours + "h " + minutes + "m " + remainder + "s";
        if (hours > 0) return hours + "h " + minutes + "m " + remainder + "s";
        if (minutes > 0) return minutes + "m " + remainder + "s";
        return remainder + "s";
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
