package com.tndmadman.rts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
    private final ServerAdminStore adminStore;
    private final ServerBackupAdmin backupAdmin;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();
    private final int startupAutosaveSeconds;
    private int runtimeAutosaveSeconds;
    private ServerAccessPolicy accessPolicy;
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

    private HeadlessGameServer(World world, PeerNetwork network, Config config, ServerSaveStore saves,
                               ServerAdminStore adminStore, ServerBackupAdmin backupAdmin,
                               ServerAccessPolicy accessPolicy) {
        this.world = world;
        this.network = network;
        this.config = config;
        this.saves = saves;
        this.adminStore = adminStore;
        this.backupAdmin = backupAdmin;
        this.accessPolicy = accessPolicy == null ? ServerAccessPolicy.open() : accessPolicy;
        this.startupAutosaveSeconds = Math.max(0, config.autosaveSeconds);
        this.runtimeAutosaveSeconds = startupAutosaveSeconds;
        scheduleNextAutosave();
    }

    static HeadlessGameServer start(Config config) throws IOException {
        if (config == null || !config.dedicatedServerMode()) {
            throw new IllegalArgumentException("HeadlessGameServer requires dedicated server configuration.");
        }
        GalaxyRuntimeOptions.configure(config);
        ServerSaveStore saves = new ServerSaveStore(config.saveDir, config.saveName, config.backupCount);
        ServerAdminStore adminStore = new ServerAdminStore(config.saveDir, config.saveName);
        ServerBackupAdmin backupAdmin = new ServerBackupAdmin(config.saveDir, config.saveName, config.backupCount);
        ServerAccessPolicy accessPolicy = adminStore.load();
        Optional<World> loaded = config.newWorld ? Optional.empty() : saves.load(config);
        World world = loaded.orElseGet(() -> new World(config.playerName, config.disabledNpcFactionIds, config.systemId, false));
        if (loaded.isPresent()) System.out.println("Loaded server save '" + config.saveName + "'.");
        else System.out.println(config.newWorld ? "Starting a new server world by request." : "No server save found; starting a new world.");
        DevTimerSettings.configure(world, config.disableProductionTimers);
        PeerNetwork network = PeerNetwork.start(config, world, saves.loadedPlayerSessions(), accessPolicy);
        if (network == null) throw new IOException("Dedicated server network did not start.");
        return new HeadlessGameServer(world, network, config, saves, adminStore, backupAdmin, accessPolicy);
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
            @Override public List<String> leaderboard(int limit) { return leaderboardLines(limit); }
            @Override public List<String> player(String selector, String section) { return playerDetailLines(selector, section); }
            @Override public List<String> sessions(String filter) { return sessionLines(filter); }
            @Override public List<String> uptime() { return uptimeLines(); }
            @Override public List<String> performance(String scope) { return performanceLines(scope); }
            @Override public List<String> systems(String filter, String value) { return systemLines(filter, value); }
            @Override public List<String> system(String selector) { return systemDetailLines(selector); }
            @Override public List<String> connection(String selector) { return connectionLines(selector); }
            @Override public List<String> resync(String selector) { return resyncLines(selector); }
            @Override public List<String> serverInfo(String scope) { return serverInfoLines(scope); }
            @Override public List<String> saveInfo() { return saveInfoLines(); }
            @Override public List<String> autosave(List<String> args) { return autosaveCommand(args); }
            @Override public List<String> backups(List<String> args) { return backupCommand(args); }
            @Override public List<String> maintenance(List<String> args) { return maintenanceCommand(args); }
            @Override public List<String> slots(List<String> args) { return slotsCommand(args); }
            @Override public List<String> motd(List<String> args) { return motdCommand(args); }
            @Override public String announce(String message) { return announceNow(message); }
            @Override public String scheduleShutdown(long delaySeconds, String reason) { return HeadlessGameServer.this.scheduleShutdown(delaySeconds, reason); }
            @Override public String cancelShutdown() { return HeadlessGameServer.this.cancelShutdown(); }
            @Override public String shutdownStatus() { return HeadlessGameServer.this.shutdownStatus(); }
            @Override public String disconnect(String selector, String reason) { return disconnectPlayer(selector, reason); }
            @Override public List<String> developer(List<String> args) { return developerCommand(args); }
            @Override public boolean save() { return saveNow("manual-console"); }
            @Override public void stop() { HeadlessGameServer.this.stop(); }
            @Override public boolean running() { return HeadlessGameServer.this.running(); }
            @Override public Object extensionContext() { return HeadlessGameServer.this; }
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

    boolean running() { return !stopped.get(); }
    String statusLine() { return stopped.get() ? "SERVER STOPPED" : network.statusLine(); }

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
        String autosave = runtimeAutosaveSeconds <= 0 ? "autosave disabled" : "autosave every " + runtimeAutosaveSeconds + "s";
        String shutdown = shutdownDeadlineNanos == NO_SHUTDOWN ? "" : " | shutdown " + shutdownRemainingSeconds() + "s";
        String maintenance = accessPolicy.maintenance() ? " | maintenance" : "";
        String slots = accessPolicy.maxSlots() <= 0 ? "" : " | slots " + sortedSessions().size() + "/" + accessPolicy.maxSlots();
        return statusLine() + " | save " + config.saveName + " | " + autosave + maintenance + slots + shutdown;
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

    private List<String> leaderboardLines(int limit) {
        List<LeaderboardEntry> entries = new ArrayList<>(GlobalLeaderboard.aggregate(world, authoritativeSystemIds()));
        entries.sort(Comparator.comparingInt(LeaderboardEntry::score).reversed().thenComparing(LeaderboardEntry::playerId));
        if (entries.isEmpty()) return List.of("Leaderboard is empty.");
        List<String> lines = new ArrayList<>();
        int rank = 1;
        for (LeaderboardEntry entry : entries) {
            if (rank > limit) break;
            PersistentPlayerSession session = sessionById(entry.playerId());
            String name = session == null ? PlayerRegistry.name(entry.playerId()) : session.name();
            lines.add(rank + ". " + name + " (" + entry.playerId() + ") | score " + entry.score()
                    + " | units " + entry.units() + " | bases " + entry.bases());
            rank++;
        }
        return List.copyOf(lines);
    }

    private List<String> playerDetailLines(String selector, String section) {
        String playerId = resolvePlayerId(selector);
        if (playerId.isBlank()) return List.of("Unknown player session: " + selector);
        PersistentPlayerSession session = sessionById(playerId);
        String name = session == null ? PlayerRegistry.name(playerId) : session.name();
        LeaderboardEntry score = leaderboardEntry(playerId);
        String home = world.playerHomeSystemId(playerId);
        List<GalaxyMapSystem> controlled = controlledSystems(playerId);
        List<String> research = new ArrayList<>(world.completedResearch.getOrDefault(playerId, Set.of()));
        research.sort(String::compareTo);
        boolean connected = network.serverSessionConnected(playerId);
        if ("assets".equals(section)) {
            return List.of(name + " (" + playerId + ")", "Home: " + home,
                    "Assets: units " + score.units() + " | bases " + score.bases() + " | live " + world.hasLiveAssets(playerId),
                    "Score: " + score.score());
        }
        if ("research".equals(section)) {
            List<String> lines = new ArrayList<>();
            lines.add(name + " (" + playerId + ") | completed research " + research.size());
            lines.add(research.isEmpty() ? "Research: none" : "Research: " + String.join(", ", research));
            return List.copyOf(lines);
        }
        if ("systems".equals(section)) {
            List<String> lines = new ArrayList<>();
            lines.add(name + " (" + playerId + ") | home " + home);
            if (controlled.isEmpty()) lines.add("Controlled systems: none");
            else for (GalaxyMapSystem system : controlled) lines.add(systemSummary(system));
            return List.copyOf(lines);
        }
        return List.of(
                name + " (" + playerId + ") | " + (connected ? "connected" : "retained"),
                "Home: " + home + " | controlled systems " + controlled.size(),
                "Assets: units " + score.units() + " | bases " + score.bases() + " | score " + score.score(),
                "Research completed: " + research.size(),
                "Developer server: " + (config.devMode ? "enabled" : "disabled"));
    }

    private List<String> sessionLines(String filter) {
        List<String> lines = new ArrayList<>();
        for (PersistentPlayerSession session : sortedSessions()) {
            boolean connected = network.serverSessionConnected(session.playerId());
            if ("connected".equals(filter) && !connected) continue;
            if ("retained".equals(filter) && connected) continue;
            ConnectionId connectionId = network.connectionIdForPlayer(session.playerId());
            String detail;
            if (connected) {
                ConnectionDiagnostics diagnostics = network.connectionDiagnostics(connectionId);
                detail = "connection " + connectionId + " | queued " + diagnostics.queuedFrames() + " frames / "
                        + humanBytes(diagnostics.queuedBytes());
            } else detail = "retained | reconnect eligible";
            lines.add(session.playerId() + " | " + session.name() + " | " + detail);
        }
        return lines.isEmpty() ? List.of("No sessions matched that filter.") : List.copyOf(lines);
    }

    private List<String> uptimeLines() {
        long uptimeSeconds = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000_000L);
        String autosave = runtimeAutosaveSeconds <= 0
                ? "disabled"
                : formatDuration(Math.max(0, (nextAutosaveNanos - System.nanoTime() + 999_999_999L) / 1_000_000_000L));
        List<String> lines = new ArrayList<>();
        lines.add("Started: " + UTC_TIME.format(startedAt));
        lines.add("Uptime: " + formatDuration(uptimeSeconds));
        lines.add("Saves: manual " + manualSaveCount + " | autosave " + autosaveCount);
        lines.add("Next autosave: " + autosave);
        if (lastSuccessfulSaveAt != null) lines.add("Last save: " + UTC_TIME.format(lastSuccessfulSaveAt) + " | " + lastSuccessfulSaveReason);
        return List.copyOf(lines);
    }

    private List<String> performanceLines(String scope) {
        PerfSnapshot perf = network.perfSnapshot();
        List<String> lines = new ArrayList<>();
        if (!"network".equals(scope)) {
            lines.add(String.format(Locale.ROOT, "Simulation: avg %.3f ms | max %.3f ms", perf.serverUpdateAvgMs(), perf.serverUpdateMaxMs()));
            lines.add(String.format(Locale.ROOT, "Frame/update: %.1f fps | avg %.3f ms | max %.3f ms", perf.fps(), perf.updateAvgMs(), perf.updateMaxMs()));
        }
        if (!"simulation".equals(scope)) {
            lines.add(String.format(Locale.ROOT, "Network: avg %.3f ms | max %.3f ms", perf.networkAvgMs(), perf.networkMaxMs()));
            lines.add(String.format(Locale.ROOT, "Traffic: sent %s/s | received %s/s | snapshots %.1f/s",
                    humanBytes(perf.bytesSentPerSecond()), humanBytes(perf.bytesReceivedPerSecond()), perf.snapshotsSentPerSecond()));
            lines.add("Connections: " + perf.activeConnections() + " | queued " + perf.queuedFrames() + " frames / " + humanBytes(perf.queuedBytes()));
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
        return lines.isEmpty() ? List.of("No galaxy systems matched that filter.") : List.copyOf(lines);
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

    private List<String> resyncLines(String selector) {
        if ("all".equalsIgnoreCase(selector)) {
            int count = network.resyncAllServerPlayers();
            return List.of("Resent authoritative state to " + count + " connected client" + (count == 1 ? "" : "s") + ".");
        }
        if ("resources".equalsIgnoreCase(selector)) {
            network.forceServerResourceCorrection();
            return List.of("A full resource correction will be sent on the next network update.");
        }
        String playerId = resolvePlayerId(selector);
        if (playerId.isBlank()) return List.of("Unknown player session: " + selector);
        int count = network.resyncServerPlayer(playerId);
        return count == 0 ? List.of(playerId + " is not connected.") : List.of("Resent authoritative state to " + playerId + ".");
    }

    private List<String> serverInfoLines(String scope) {
        List<String> lines = new ArrayList<>();
        if (!"tls".equals(scope) && !"compatibility".equals(scope)) {
            lines.add("Server: " + config.playerName + " | TCP " + config.port + " | galaxy copies " + config.galaxyCopies);
            lines.add("Build: " + BuildInfo.display());
            lines.add("Save: " + currentSavePath().toAbsolutePath().normalize());
            lines.add("Admin policy: " + adminStore.path().toAbsolutePath().normalize());
        }
        if (!"tls".equals(scope)) {
            MultiplayerCompatibility.Descriptor descriptor = MultiplayerCompatibility.local();
            lines.add("Compatibility: protocol " + descriptor.protocolVersion() + " | rules " + descriptor.rulesVersion());
            lines.add("Application: " + descriptor.applicationVersion() + " | commit " + descriptor.buildCommit());
            lines.add("Config fingerprint: " + descriptor.configHash());
        }
        if (!"compatibility".equals(scope)) {
            Path tls = (config.saveDir == null ? Path.of("saves") : config.saveDir)
                    .resolve(Config.cleanSaveName(config.saveName) + "-tls.p12");
            lines.add("TLS identity: " + tls.toAbsolutePath().normalize() + " | " + fileState(tls));
            lines.add("TLS certificate SHA-256: " + ServerBackupAdmin.tlsFingerprint(config));
        }
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
        lines.add("Administration: " + adminStore.path().toAbsolutePath().normalize() + " | " + fileState(adminStore.path()));
        if (lastSuccessfulSaveAt == null) lines.add("Last save this process: none");
        else lines.add("Last save this process: " + UTC_TIME.format(lastSuccessfulSaveAt) + " | " + lastSuccessfulSaveReason);
        return List.copyOf(lines);
    }

    private List<String> autosaveCommand(List<String> args) {
        if (args == null || args.isEmpty() || "status".equalsIgnoreCase(args.get(0))) {
            if (args != null && args.size() > 1) return List.of("Usage: autosave <status|set <duration>|on|off|reset>");
            String next = runtimeAutosaveSeconds <= 0 ? "disabled" : formatDuration(Math.max(0,
                    (nextAutosaveNanos - System.nanoTime() + 999_999_999L) / 1_000_000_000L));
            return List.of("Autosave: " + (runtimeAutosaveSeconds <= 0 ? "disabled" : "every " + formatDuration(runtimeAutosaveSeconds)),
                    "Startup interval: " + (startupAutosaveSeconds <= 0 ? "disabled" : formatDuration(startupAutosaveSeconds)),
                    "Next autosave: " + next);
        }
        String action = args.get(0).toLowerCase(Locale.ROOT);
        if ("set".equals(action) && args.size() == 2) {
            try { runtimeAutosaveSeconds = Math.toIntExact(ServerCommandDispatcher.parseDurationSeconds(args.get(1))); }
            catch (RuntimeException ex) { return List.of(ex.getMessage(), "Usage: autosave set <duration>"); }
            scheduleNextAutosave();
            return List.of("Autosave interval set to " + formatDuration(runtimeAutosaveSeconds) + " for this process.");
        }
        if ("off".equals(action) && args.size() == 1) {
            runtimeAutosaveSeconds = 0;
            scheduleNextAutosave();
            return List.of("Autosave disabled for this process.");
        }
        if ("on".equals(action) && args.size() == 1) {
            runtimeAutosaveSeconds = startupAutosaveSeconds > 0 ? startupAutosaveSeconds : 60;
            scheduleNextAutosave();
            return List.of("Autosave enabled every " + formatDuration(runtimeAutosaveSeconds) + ".");
        }
        if ("reset".equals(action) && args.size() == 1) {
            runtimeAutosaveSeconds = startupAutosaveSeconds;
            scheduleNextAutosave();
            return List.of("Autosave restored to the startup setting: "
                    + (runtimeAutosaveSeconds <= 0 ? "disabled" : formatDuration(runtimeAutosaveSeconds)) + ".");
        }
        return List.of("Usage: autosave <status|set <duration>|on|off|reset>");
    }

    private List<String> backupCommand(List<String> args) {
        if (args == null || args.isEmpty() || "list".equalsIgnoreCase(args.get(0))) {
            if (args != null && args.size() > 1) return List.of("Usage: backups list");
            return backupAdmin.list();
        }
        String action = args.get(0).toLowerCase(Locale.ROOT);
        if ("create".equals(action)) {
            if (args.size() > 2) return List.of("Usage: backups create [label]");
            if (!saveNow("backup-source")) return List.of("Backup source save failed.");
            return List.of(backupAdmin.create(args.size() == 2 ? args.get(1) : "manual"));
        }
        if ("verify".equals(action) && args.size() == 2) return List.of(backupAdmin.verifySelector(args.get(1)));
        if ("prune".equals(action) && args.size() == 1) return List.of(backupAdmin.prune());
        return List.of("Usage: backups <list|create [label]|verify <current|previous|filename>|prune>");
    }

    private List<String> maintenanceCommand(List<String> args) {
        if (args == null || args.isEmpty() || "status".equalsIgnoreCase(args.get(0))) {
            if (args != null && args.size() > 1) return List.of("Usage: maintenance status");
            return List.of("Maintenance: " + (accessPolicy.maintenance() ? "enabled" : "disabled"),
                    "Reason: " + (accessPolicy.maintenanceReason().isBlank() ? "none" : accessPolicy.maintenanceReason()));
        }
        String action = args.get(0).toLowerCase(Locale.ROOT);
        if ("on".equals(action)) {
            ServerAccessPolicy updated = accessPolicy.withMaintenance(true, join(args, 1));
            String result = applyAccessPolicy(updated);
            if (result != null) return List.of(result);
            String notice = "Server maintenance mode enabled" + (updated.maintenanceReason().isBlank() ? "." : ": " + updated.maintenanceReason());
            network.broadcastServerNotice(notice);
            return List.of(notice + " Existing and retained sessions may continue or reconnect.");
        }
        if ("off".equals(action) && args.size() == 1) {
            String result = applyAccessPolicy(accessPolicy.withMaintenance(false, ""));
            if (result != null) return List.of(result);
            network.broadcastServerNotice("Server maintenance mode disabled; new players may join.");
            return List.of("Maintenance mode disabled.");
        }
        return List.of("Usage: maintenance <status|on [reason]|off>");
    }

    private List<String> slotsCommand(List<String> args) {
        if (args == null || args.isEmpty() || "status".equalsIgnoreCase(args.get(0))) {
            if (args != null && args.size() > 1) return List.of("Usage: slots");
            int connected = network.serverPeerCount();
            return List.of("Slots: " + connected + " connected | " + sortedSessions().size() + " total sessions | "
                    + (accessPolicy.maxSlots() <= 0 ? "unlimited" : accessPolicy.maxSlots() + " maximum"));
        }
        String action = args.get(0).toLowerCase(Locale.ROOT);
        int slots;
        if ("unlimited".equals(action) && args.size() == 1) slots = 0;
        else if ("set".equals(action) && args.size() == 2) {
            try { slots = Integer.parseInt(args.get(1)); }
            catch (NumberFormatException ex) { return List.of("Slot count is not numeric."); }
            if (slots < 1 || slots > ServerAccessPolicy.MAX_SLOTS) return List.of("Slot count must be between 1 and " + ServerAccessPolicy.MAX_SLOTS + ".");
        } else return List.of("Usage: slots [set <count>|unlimited]");
        String result = applyAccessPolicy(accessPolicy.withMaxSlots(slots));
        if (result != null) return List.of(result);
        return List.of(slots <= 0 ? "Player slots are unlimited." : "Player-session limit set to " + slots + ". Existing sessions were not disconnected.");
    }

    private List<String> motdCommand(List<String> args) {
        if (args == null || args.isEmpty() || "show".equalsIgnoreCase(args.get(0))) {
            if (args != null && args.size() > 1) return List.of("Usage: motd show");
            return List.of(accessPolicy.motd().isBlank() ? "MOTD is not set." : "MOTD: " + accessPolicy.motd());
        }
        String action = args.get(0).toLowerCase(Locale.ROOT);
        if ("set".equals(action) && args.size() >= 2) {
            ServerAccessPolicy updated = accessPolicy.withMotd(join(args, 1));
            if (updated.motd().isBlank()) return List.of("MOTD may not be empty; use 'motd clear'.");
            String result = applyAccessPolicy(updated);
            return List.of(result == null ? "MOTD updated." : result);
        }
        if ("clear".equals(action) && args.size() == 1) {
            String result = applyAccessPolicy(accessPolicy.withMotd(""));
            return List.of(result == null ? "MOTD cleared." : result);
        }
        if ("send".equals(action) && args.size() == 1) {
            if (accessPolicy.motd().isBlank()) return List.of("MOTD is not set.");
            int recipients = network.broadcastServerNotice("MOTD: " + accessPolicy.motd());
            return List.of("MOTD sent to " + recipients + " connected client" + (recipients == 1 ? "" : "s") + ".");
        }
        return List.of("Usage: motd <show|set <message>|clear|send>");
    }

    private String applyAccessPolicy(ServerAccessPolicy updated) {
        try {
            adminStore.save(updated);
            accessPolicy = updated;
            network.configureServerPolicy(updated);
            return null;
        } catch (IOException ex) {
            return "Could not save server administration settings: " + ex.getMessage();
        }
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
        return ServerDevCommands.execute(this, args);
    }

    private List<String> developerStatusLines() {
        return List.of(
                "Developer host: enabled",
                "AI: " + (world.aiDevSettings.pauseAi ? "paused" : "running") + " | speed " + (world.aiDevSettings.fastAi ? "fast" : "normal"),
                "Combat: player units frozen " + world.aiDevSettings.freezePlayerUnits + " | NPC combat frozen " + world.aiDevSettings.freezeNpcCombat,
                "Rules: attacks disabled " + world.aiDevSettings.disableAttacks + " | economy disabled " + world.aiDevSettings.disableEconomy,
                "Production timers: " + (config.disableProductionTimers ? "startup-disabled; runtime state may differ" : "startup-enabled"),
                "Difficulty: " + world.aiDevSettings.difficultyPreset().label);
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
            world.aiDevSettings.stepAi = true;
            return List.of("AI will advance one step.");
        }
        if (args.size() != 3) return List.of("Usage: dev ai <pause on|off|speed normal|fast|step>");
        String action = args.get(1).toLowerCase(Locale.ROOT);
        String value = args.get(2).toLowerCase(Locale.ROOT);
        if ("pause".equals(action)) {
            Boolean enabled = onOff(value);
            if (enabled == null) return List.of("Usage: dev ai pause <on|off>");
            world.aiDevSettings.pauseAi = enabled;
            if (!enabled) world.aiDevSettings.stepAi = false;
            return List.of("AI pause " + (enabled ? "enabled" : "disabled") + ".");
        }
        if ("speed".equals(action) && ("normal".equals(value) || "fast".equals(value))) {
            world.aiDevSettings.fastAi = "fast".equals(value);
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

    private String shutdownReasonSuffix() { return shutdownReason.isBlank() ? "." : ": " + shutdownReason; }

    private void autosaveIfDue() {
        if (runtimeAutosaveSeconds <= 0 || System.nanoTime() < nextAutosaveNanos) return;
        saveNow("autosave");
        scheduleNextAutosave();
    }

    private void scheduleNextAutosave() {
        nextAutosaveNanos = runtimeAutosaveSeconds <= 0
                ? Long.MAX_VALUE
                : System.nanoTime() + runtimeAutosaveSeconds * 1_000_000_000L;
    }

    boolean saveForAdmin(String reason) { return saveNow(reason); }

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

    private LeaderboardEntry leaderboardEntry(String playerId) {
        for (LeaderboardEntry entry : GlobalLeaderboard.aggregate(world, authoritativeSystemIds())) {
            if (entry != null && entry.playerId().equals(playerId)) return entry;
        }
        return new LeaderboardEntry(playerId, 0, 0, 0);
    }

    private String[] authoritativeSystemIds() {
        GalaxyMapSnapshot snapshot = world.authoritativeGalaxyMapSnapshot();
        if (snapshot == null || snapshot.systems() == null) return new String[]{world.activeSystemId()};
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(world.activeSystemId());
        for (GalaxyMapSystem system : snapshot.systems()) if (system != null && !blank(system.id())) ids.add(system.id());
        return ids.toArray(String[]::new);
    }

    private List<GalaxyMapSystem> controlledSystems(String playerId) {
        GalaxyMapSnapshot snapshot = world.authoritativeGalaxyMapSnapshot();
        if (snapshot == null || snapshot.systems() == null) return List.of();
        List<GalaxyMapSystem> out = new ArrayList<>();
        for (GalaxyMapSystem system : snapshot.systems()) {
            if (system != null && playerId.equalsIgnoreCase(system.controllerId())) out.add(system);
        }
        out.sort(Comparator.comparing(GalaxyMapSystem::id));
        return List.copyOf(out);
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
        return clean.length() <= ServerAccessPolicy.MAX_TEXT ? clean : clean.substring(0, ServerAccessPolicy.MAX_TEXT);
    }

    private String join(List<String> args, int from) {
        if (args == null || from >= args.size()) return "";
        return cleanNotice(String.join(" ", args.subList(Math.max(0, from), args.size())));
    }

    private Boolean onOff(String value) {
        if ("on".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value) || "1".equals(value)) return true;
        if ("off".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value) || "0".equals(value)) return false;
        return null;
    }

    private String percent(double value) { return String.format(Locale.ROOT, "%.1f%%", Math.max(0, Math.min(1, value)) * 100.0); }

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

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
