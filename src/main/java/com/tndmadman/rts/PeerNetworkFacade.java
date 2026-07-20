package com.tndmadman.rts;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class PeerNetwork implements CommandSink {
    private final Config config;
    private final PeerTransport transport;
    private final PeerServerSide server;
    private final PeerClientSide client;
    private final PerfStats perfStats;
    private final Set<ConnectionId> motdDelivered = new LinkedHashSet<>();
    private final Set<ConnectionId> admissionRecorded = new LinkedHashSet<>();
    private final Map<ConnectionId, String> deviceByConnection = new LinkedHashMap<>();
    private final Map<String, String> deviceByPlayer = new LinkedHashMap<>();
    private final Set<String> retainedModerationPlayers = new LinkedHashSet<>();
    private final ServerModerationStore moderationStore;
    private final ServerPlayerObservationStore observationStore;
    private final ServerEventJournal journal;
    private final Set<String> runtimeDevAccess = new LinkedHashSet<>();
    private final Set<String> runtimeFreeBuild = new LinkedHashSet<>();
    private ServerAccessPolicy accessPolicy;
    private ServerModerationState moderation;
    private boolean runtimeDevEnabled;
    private boolean simulationPaused;
    private String simulationPauseReason = "";

    private PeerNetwork(Config config, PeerTransport transport, PeerServerSide server, PeerClientSide client,
                        PerfStats perfStats, ServerAccessPolicy accessPolicy) {
        this.config = config;
        this.transport = transport;
        this.server = server;
        this.client = client;
        this.perfStats = perfStats;
        this.accessPolicy = accessPolicy == null ? ServerAccessPolicy.open() : accessPolicy;
        this.moderationStore = new ServerModerationStore(config == null ? null : config.saveDir,
                config == null ? "server" : config.saveName);
        this.observationStore = new ServerPlayerObservationStore(config == null ? null : config.saveDir,
                config == null ? "server" : config.saveName);
        this.journal = new ServerEventJournal(config == null ? null : config.saveDir,
                config == null ? "server" : config.saveName);
        this.moderation = server == null ? ServerModerationState.open() : moderationStore.load();
        this.runtimeDevEnabled = config != null && config.devMode;
    }

    static PeerNetwork start(Config config, World world) throws IOException {
        return start(config, world, List.of(), ServerAccessPolicy.open());
    }

    static PeerNetwork start(Config config, World world, List<PersistentPlayerSession> restoredSessions) throws IOException {
        return start(config, world, restoredSessions, ServerAccessPolicy.open());
    }

    static PeerNetwork start(Config config, World world, List<PersistentPlayerSession> restoredSessions,
                             ServerAccessPolicy accessPolicy) throws IOException {
        if (!config.hostMode && !config.clientMode()) {
            PlayerRegistry.reset("SOLO", config.playerName, 0x50BEFF);
            world.setDevFreeBuild("SOLO", config.devMode);
            ResourceNetDebug.registerClientWorld(world);
            if (config.devMode) world.status = "Solo dev mode enabled.";
            return null;
        }
        if (config.clientMode() && config.serverAddress.isUnresolved()) {
            throw new IOException("Could not resolve server host: " + config.serverAddress.getHostString());
        }
        try {
            MultiplayerCompatibility.local();
        } catch (IllegalStateException ex) {
            throw new IOException("Multiplayer compatibility setup failed: " + ex.getMessage(), ex);
        }
        PerfStats perfStats = new PerfStats();
        PeerTransport transport = config.hostMode
                ? PeerTransport.server(config, perfStats)
                : PeerTransport.client(config, perfStats);
        PeerServerSide server = null;
        PeerClientSide client = null;
        if (config.hostMode) {
            PlayerRegistry.reset("SOLO", config.playerName, 0x50BEFF);
            world.setDevFreeBuild("SOLO", config.devMode);
            world.status = "Hosting " + world.systemName() + " TCP " + transport.localPort() + (config.devMode ? " with dev mode enabled" : "");
            ResourceNetDebug.registerServerWorld(world);
            server = new PeerServerSide(config, world, transport, restoredSessions);
        } else {
            PlayerRegistry.reset("WAIT", config.playerName, 0x50BEFF);
            world.setDevFreeBuild("WAIT", false);
            world.status = "Joining " + config.serverAddress;
            ResourceNetDebug.registerClientWorld(world);
            client = new PeerClientSide(config, world, transport);
        }
        PeerNetwork network = new PeerNetwork(config, transport, server, client, perfStats, accessPolicy);
        network.refreshModerationRetention();
        transport.start();
        return network;
    }

    String statusLine() { return server != null ? server.statusLine() : client.statusLine(); }
    String localPlayerId() { return client != null ? client.localPlayerId() : "SOLO"; }
    boolean connectionFailed() { return client != null && client.connectionFailed(); }
    String failureMessage() { return client != null ? client.failureMessage() : "Connection failed."; }
    PerfSnapshot perfSnapshot() { return transport.perfSnapshot(); }
    boolean clientMode() { return client != null; }
    boolean clientReconnecting() { return client != null && client.reconnecting(); }
    boolean clientConnected() { return client != null && client.connectedState(); }
    boolean clientReady() { return client == null || client.readyState(); }
    ClientConnectionProgress clientConnectionProgress() {
        return client == null
                ? new ClientConnectionProgress(ConnectionPhase.READY, "READY", "Local authoritative session.", 4, 4, 0)
                : client.connectionProgress();
    }
    long clientSnapshotSequence() { return client == null ? 0 : client.lastSnapshotSequence(); }
    String clientViewedSystemId() { return client == null ? "" : client.viewedSystemId(); }
    long clientPendingViewRevision() { return client == null ? 0 : client.pendingViewRevision(); }
    boolean clientViewSwitchPending() { return client != null && client.viewSwitchPending(); }
    boolean serverCertificateTrustRequired() { return client != null && client.serverCertificateTrustRequired(); }
    String serverCertificateTrustPrompt() { return client == null ? "" : client.serverCertificateTrustPrompt(); }
    boolean trustChangedServerCertificate() { return client != null && client.trustChangedServerCertificate(); }
    void forceClientDisconnectForTest() { if (client != null) transport.forceDisconnectClientForTest(); }
    ConnectionId clientConnectionId() { return transport.clientConnectionId(); }
    ConnectionId connectionIdForPlayer(String playerId) { return server == null ? ConnectionId.NONE : server.connectionIdForPlayer(playerId); }
    ConnectionDiagnostics connectionDiagnostics(ConnectionId id) { return transport.diagnostics(id); }
    int serverPeerCount() { return server == null ? 0 : server.peerCount(); }
    boolean serverSessionConnected(String playerId) { return server != null && server.sessionConnected(playerId); }
    List<PersistentPlayerSession> persistentPlayerSessions() { return server == null ? List.of() : server.persistentSessions(); }
    void forceServerResourceCorrectionForTest() { if (server != null) server.forceResourceCorrectionForTest(); }

    Config serverConfig() { return config; }
    ServerEventJournal serverJournal() { return journal; }
    ServerModerationState serverModeration() { return moderation == null ? ServerModerationState.open() : moderation; }
    boolean simulationPaused() { return simulationPaused; }
    String simulationPauseReason() { return simulationPauseReason; }

    void setSimulationPaused(boolean paused, String reason) {
        simulationPaused = paused;
        simulationPauseReason = paused ? packetPart(reason) : "";
        journal.add(paused ? "PAUSE" : "RESUME", "simulation", simulationPauseReason);
    }

    String saveServerModeration(ServerModerationState state) {
        ServerModerationState safe = state == null ? ServerModerationState.open() : state.activeOnly(System.currentTimeMillis());
        try {
            moderationStore.save(safe);
            moderation = safe;
            refreshModerationRetention();
            return null;
        } catch (IOException ex) {
            return "Could not save server moderation settings: " + ex.getMessage();
        }
    }

    void refreshModerationRetention() {
        if (server == null) return;
        long now = System.currentTimeMillis();
        ServerModerationState active = serverModeration().activeOnly(now);
        if (!active.equals(moderation)) {
            moderation = active;
            try { moderationStore.save(active); }
            catch (IOException ex) { System.err.println("Could not prune expired moderation entries: " + ex.getMessage()); }
        }
        LinkedHashSet<String> shouldRetain = new LinkedHashSet<>();
        for (ModerationEntry entry : active.active(null, now)) {
            if (!entry.playerId().isBlank()) shouldRetain.add(entry.playerId());
        }
        for (String playerId : shouldRetain) PeerServerAdminBridge.retain(server, playerId);
        for (String playerId : new ArrayList<>(retainedModerationPlayers)) {
            if (!shouldRetain.contains(playerId)) PeerServerAdminBridge.release(server, playerId);
        }
        retainedModerationPlayers.clear();
        retainedModerationPlayers.addAll(shouldRetain);
    }

    InetAddress serverPlayerAddress(String playerId) {
        return server == null ? null : PeerServerAdminBridge.address(server, playerId);
    }

    String serverPlayerDeviceId(String playerId) {
        return playerId == null ? "" : deviceByPlayer.getOrDefault(playerId, "");
    }

    void disconnectModeratedPlayer(String playerId, String action, String reason) {
        if (server == null || playerId == null || playerId.isBlank()) return;
        ConnectionId connectionId = server.connectionIdForPlayer(playerId);
        String notice = packetPart(action) + (reason == null || reason.isBlank() ? "." : ": " + packetPart(reason));
        if (connectionId.valid()) {
            transport.sendOrdered("SERVER_NOTICE|" + notice, connectionId);
            motdDelivered.remove(connectionId);
            admissionRecorded.remove(connectionId);
            server.removePeer(connectionId);
        }
        PeerServerAdminBridge.retain(server, playerId);
        retainedModerationPlayers.add(playerId);
        journal.add(action == null ? "MODERATION" : action.toUpperCase(Locale.ROOT), playerId, packetPart(reason));
    }

    void notifyDeletedSystems(Set<String> deletedSystems) {
        if (server == null || deletedSystems == null || deletedSystems.isEmpty()) return;
        PeerServerAdminBridge.sendDeleted(server, deletedSystems);
    }

    ServerAccessPolicy serverAccessPolicy() { return accessPolicy; }

    void configureServerPolicy(ServerAccessPolicy policy) {
        accessPolicy = policy == null ? ServerAccessPolicy.open() : policy;
    }

    int broadcastServerNotice(String message) {
        if (server == null || message == null || message.isBlank()) return 0;
        String clean = packetPart(message);
        if (clean.length() > ServerAccessPolicy.MAX_TEXT) clean = clean.substring(0, ServerAccessPolicy.MAX_TEXT);
        Set<ConnectionId> recipients = new LinkedHashSet<>();
        for (PersistentPlayerSession session : server.persistentSessions()) {
            if (session == null) continue;
            ConnectionId connectionId = server.connectionIdForPlayer(session.playerId());
            if (connectionId.valid()) recipients.add(connectionId);
        }
        for (ConnectionId connectionId : recipients) transport.sendOrdered("SERVER_NOTICE|" + clean, connectionId);
        return recipients.size();
    }

    boolean disconnectServerPlayer(String playerId) {
        if (server == null || playerId == null || playerId.isBlank()) return false;
        ConnectionId connectionId = server.connectionIdForPlayer(playerId);
        if (!connectionId.valid()) return false;
        motdDelivered.remove(connectionId);
        admissionRecorded.remove(connectionId);
        journal.add("DISCONNECT", playerId, "temporary operator disconnect");
        server.removePeer(connectionId);
        return true;
    }

    int resyncServerPlayer(String playerId) {
        if (server == null || playerId == null || playerId.isBlank()) return 0;
        ConnectionId connectionId = server.connectionIdForPlayer(playerId);
        if (!connectionId.valid()) return 0;
        server.sendInitialTo(connectionId);
        journal.add("RESYNC", playerId, "authoritative state resent");
        return 1;
    }

    int resyncAllServerPlayers() {
        if (server == null) return 0;
        int sent = 0;
        for (PersistentPlayerSession session : server.persistentSessions()) {
            if (session == null) continue;
            ConnectionId connectionId = server.connectionIdForPlayer(session.playerId());
            if (!connectionId.valid()) continue;
            server.sendInitialTo(connectionId);
            sent++;
        }
        journal.add("RESYNC", "all", "authoritative state resent to " + sent + " clients");
        return sent;
    }

    void forceServerResourceCorrection() {
        if (server != null) {
            server.forceResourceCorrectionForTest();
            journal.add("RESYNC", "resources", "full correction requested");
        }
    }

    void updateServerWorlds(double dt) {
        if (server == null || simulationPaused) return;
        long started = System.nanoTime();
        server.updateWorlds(dt);
        perfStats.recordServerUpdate(System.nanoTime() - started);
    }

    boolean devToolsAllowed() { return server != null ? runtimeDevEnabled : client != null && client.devToolsAllowed(); }
    boolean runtimeDevEnabled() { return runtimeDevEnabled; }
    int runtimeDevAccessCount() { return runtimeDevAccess.size(); }
    int runtimeFreeBuildCount() { return runtimeFreeBuild.size(); }
    boolean runtimeDevAccessGranted(String playerId) {
        if (playerId == null) return false;
        if (runtimeDevAccess.contains(playerId)) return true;
        if (server != null) for (DevPeerAccess peer : server.devAccessPeers()) if (playerId.equals(peer.playerId()) && peer.authorized()) return true;
        return false;
    }
    boolean runtimeFreeBuildEnabled(String playerId) {
        return playerId != null && (runtimeFreeBuild.contains(playerId) || server != null && server.world.devFreeBuildFor(playerId));
    }
    List<DevPeerAccess> devAccessPeers() { return server == null ? List.of() : server.devAccessPeers(); }
    World devSettingsWorld(World fallback) { return server == null ? fallback : server.world; }

    void setRuntimeDevEnabled(boolean enabled) {
        runtimeDevEnabled = enabled;
        if (enabled || server == null) return;
        revokeAllRuntimeDevAccess();
        for (String playerId : new ArrayList<>(runtimeFreeBuild)) setServerFreeBuild(playerId, false);
        runtimeFreeBuild.clear();
        server.world.aiDevSettings.resetToDefaults();
        DevTimerSettings.configure(server.world, config.disableProductionTimers);
    }

    void setRemoteDevAccess(String playerId, boolean enabled) {
        if (server == null || playerId == null || playerId.isBlank()) return;
        if (enabled && !runtimeDevEnabled) return;
        if (enabled) runtimeDevAccess.add(playerId); else runtimeDevAccess.remove(playerId);
        if (config.devMode) server.setDevAccess(playerId, enabled);
        else PeerServerAdminBridge.setDevAccess(server, playerId, enabled);
        if (enabled && !runtimeFreeBuild.contains(playerId)) server.applyDevFreeCrafting(playerId, false);
        if (!enabled && runtimeFreeBuild.remove(playerId)) server.applyDevFreeCrafting(playerId, false);
        journal.add("DEV_ACCESS", playerId, enabled ? "granted" : "revoked");
    }

    int revokeAllRuntimeDevAccess() {
        if (server == null) return 0;
        LinkedHashSet<String> players = new LinkedHashSet<>(runtimeDevAccess);
        for (DevPeerAccess peer : server.devAccessPeers()) if (peer.authorized()) players.add(peer.playerId());
        for (String playerId : players) setRemoteDevAccess(playerId, false);
        PeerServerAdminBridge.revokeAllDev(server);
        runtimeDevAccess.clear();
        return players.size();
    }

    void setServerFreeBuild(String playerId, boolean enabled) {
        if (server == null || playerId == null || playerId.isBlank()) return;
        if (enabled) runtimeFreeBuild.add(playerId); else runtimeFreeBuild.remove(playerId);
        server.applyDevFreeCrafting(playerId, enabled);
        journal.add("DEV_FREEBUILD", playerId, enabled ? "enabled" : "disabled");
    }

    void devSetFreeCrafting(String playerId, boolean enabled) {
        if (server != null) {
            if (runtimeDevEnabled && runtimeAuthorized(playerId, server.connectionIdForPlayer(playerId))) setServerFreeBuild(playerId, enabled);
        } else if (client != null) client.devSetFreeCrafting(playerId, enabled);
    }

    void devAddHangarResource(String playerId, String baseId, Material material, double amount) {
        if (server != null) {
            if (runtimeDevEnabled && runtimeAuthorized(playerId, server.connectionIdForPlayer(playerId))) server.applyDevHangarResource(playerId, baseId, material, amount);
        } else if (client != null) client.devAddHangarResource(playerId, baseId, material, amount);
    }

    void devAiCommand(String playerId, String command) {
        if (server != null) {
            if (runtimeDevEnabled && ("SOLO".equals(playerId) || runtimeAuthorized(playerId, server.connectionIdForPlayer(playerId)))) server.applyDevAiCommand(playerId, command);
        } else if (client != null) client.devAiCommand(playerId, command);
    }

    boolean sendServerNotice(String playerId, String message) {
        if (server == null || playerId == null || message == null || message.isBlank()) return false;
        ConnectionId connectionId = server.connectionIdForPlayer(playerId);
        if (!connectionId.valid()) return false;
        String clean = packetPart(message);
        if (clean.length() > ServerAccessPolicy.MAX_TEXT) clean = clean.substring(0, ServerAccessPolicy.MAX_TEXT);
        transport.sendOrdered("SERVER_NOTICE|" + clean, connectionId);
        journal.add("NOTICE", playerId, "targeted content redacted");
        return true;
    }

    List<String> playerObservationLines(String selector) { return observationStore.lines(selector); }
    ServerPlayerObservationStore.PlayerObservation playerObservation(String selector) { return observationStore.find(selector); }

    void tick() {
        long started = System.nanoTime();
        try {
            long now = System.currentTimeMillis();
            NetPacket packet;
            while ((packet = transport.poll()) != null) {
                if (!transport.accepts(packet)) continue;
                if (transport.isDisconnectEvent(packet)) {
                    String playerId = server == null ? "" : server.ownerId(packet.connectionId(), "");
                    motdDelivered.remove(packet.connectionId());
                    admissionRecorded.remove(packet.connectionId());
                    deviceByConnection.remove(packet.connectionId());
                    if (server != null) server.connectionClosed(packet);
                    if (!playerId.isBlank()) journal.add("LEAVE", playerId, "connection closed");
                    continue;
                }
                try {
                    if (server != null) captureDevice(packet.message(), packet.connectionId());
                    String message = transport.processInbound(packet);
                    if (message == null) continue;
                    if (server != null) {
                        if (rejectAdmission(message, packet)) continue;
                        if (handleRuntimeDevMessage(message, packet.connectionId())) continue;
                        server.handle(message, packet);
                        bindDeviceToOwner(packet.connectionId());
                        recordAdmission(message, packet.connectionId());
                        deliverMotdAfterAdmission(message, packet.connectionId());
                    } else client.handle(packet, message);
                } catch (RuntimeException ex) {
                    transport.recordMalformedPacket();
                    if (client != null) client.rejectPacket(ex);
                    else {
                        journal.add("ERROR", "network", "Rejected malformed TCP frame: " + ex.getClass().getSimpleName());
                        System.err.println("Rejected malformed TCP frame: " + ex.getClass().getSimpleName());
                    }
                }
            }
            if (server != null) {
                refreshModerationRetention();
                server.tick(now);
                refreshModerationRetention();
            } else client.tick(now);
        } finally {
            perfStats.recordNetwork(System.nanoTime() - started);
        }
    }

    void shutdown() {
        if (client != null) {
            client.shutdown();
            client.world.aiDevSettings.resetToDefaults();
            DevTimerSettings.configure(client.world, false);
        }
        if (server != null) {
            server.world.aiDevSettings.resetToDefaults();
            DevTimerSettings.configure(server.world, false);
        }
        transport.shutdown();
    }

    @Override public void move(MoveCommand c) { if (server != null) serverCommand(() -> AUnitMove.apply(server.world, c), c.playerId()); else client.move(c); }
    @Override public void work(HarvestCommand c) { if (server != null) serverCommand(() -> AUnitWork.apply(server.world, c), c.playerId()); else client.work(c); }
    @Override public void attack(AttackCommand c) { if (server != null) serverCommand(() -> AUnitAttack.apply(server.world, c), c.playerId()); else client.attack(c); }
    @Override public void order(UnitOrderCommand c) { if (server != null) serverCommand(() -> AUnitOrder.apply(server.world, c), c.playerId()); else client.order(c); }
    @Override public void respawn(String playerId) { if (server != null) { WorldNetAccess.respawnPlayer(server.world, playerId); server.broadcastNow(); } else client.respawn(playerId); }
    @Override public void build(String playerId, String baseId, String shipTypeId) { if (server != null) serverCommand(() -> { if (CommandAuth.base(server.world, playerId, baseId)) server.world.buildShip(baseId, shipTypeId); }, playerId); else client.build(playerId, baseId, shipTypeId); }
    @Override public void basePackage(String playerId, String mode, String baseOrUnitId, String packageType) { if (server != null) serverCommand(() -> { if (CommandAuth.pack(server.world, playerId, mode, baseOrUnitId)) AUnitPack.apply(server.world, mode, baseOrUnitId, packageType); }, playerId); else client.basePackage(playerId, mode, baseOrUnitId, packageType); }
    @Override public void production(String playerId, String action, String baseId, String value, String extra) { if (server != null) serverCommand(() -> { if (CommandAuth.base(server.world, playerId, baseId)) ProductionCommands.apply(server.world, playerId, action, baseId, value, extra); }, playerId); else client.production(playerId, action, baseId, value, extra); }
    void viewSystem(String playerId, String targetSystemId) {
        if (server != null) {
            ConnectionId connectionId = server.connectionIdForPlayer(playerId);
            if (connectionId.valid()) server.requestView(connectionId, playerId, targetSystemId, 0);
            else server.world.viewGalaxySystem(targetSystemId);
        } else client.viewSystem(playerId, targetSystemId);
    }
    void jump(String playerId, double x, double y) { if (client != null) client.jump(playerId, x, y); }
    void jump(String playerId, String targetSystemId, double x, double y) { viewSystem(playerId, targetSystemId); }
    void wormholeTouch(String playerId) { if (server != null) serverCommand(server.world::transferTouchingShips, playerId); else client.wormholeTouch(playerId); }
    void wormholeTouch(WormholeTouchRequest request) { if (request == null || !request.valid()) return; if (server != null) serverCommand(() -> server.world.transferTouchingShips(request.playerId()), request.playerId()); else client.wormholeTouch(request); }

    private boolean runtimeAuthorized(String playerId, ConnectionId connectionId) {
        if (!runtimeDevEnabled || playerId == null || playerId.isBlank()) return false;
        return runtimeDevAccess.contains(playerId) || server != null && server.devAllowed(connectionId, playerId);
    }

    private boolean handleRuntimeDevMessage(String message, ConnectionId connectionId) {
        if (server == null || message == null || connectionId == null || !connectionId.valid()) return false;
        if (!(message.startsWith("DEVFREE|") || message.startsWith("DEVHANGAR|") || message.startsWith("DEVAI|"))) return false;
        String[] parts = message.split("\\|", -1);
        String playerId = parts.length > 1 ? parts[1] : "";
        if (!server.owns(connectionId, playerId) || !runtimeAuthorized(playerId, connectionId)) {
            journal.add("DEV_DENIED", playerId, parts.length == 0 ? "unknown" : parts[0]);
            return true;
        }
        try {
            if ("DEVFREE".equals(parts[0]) && parts.length >= 3) {
                setServerFreeBuild(playerId, devFlag(parts[2]));
            } else if ("DEVHANGAR".equals(parts[0]) && parts.length >= 5) {
                Material material = Material.valueOf(parts[3]);
                double amount = Double.parseDouble(parts[4]);
                if (Double.isFinite(amount) && amount > 0) server.applyDevHangarResource(playerId, parts[2], material, amount);
            } else if ("DEVAI".equals(parts[0]) && parts.length >= 3) {
                server.applyDevAiCommand(playerId, parts[2]);
            }
        } catch (RuntimeException ex) {
            journal.add("DEV_ERROR", playerId, parts[0]);
        }
        return true;
    }

    private boolean devFlag(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "DEV".equalsIgnoreCase(value) || "YES".equalsIgnoreCase(value);
    }

    private boolean rejectAdmission(String message, NetPacket packet) {
        if (server == null || packet == null || !admissionMessage(message)) return false;
        ConnectionId connectionId = packet.connectionId();
        String[] parts = message.split("\\|", -1);
        boolean join = joinMessage(message);
        String requestedName = join && parts.length > 1 ? Config.clean(parts[1]) : "";
        String requestedPlayerId = !join && parts.length > 1 ? parts[1].trim() : "";
        PersistentPlayerSession existing = join ? sessionByName(requestedName) : sessionById(requestedPlayerId);
        String playerId = existing == null ? requestedPlayerId : existing.playerId();
        String playerName = existing == null ? requestedName : existing.name();
        String deviceId = deviceByConnection.getOrDefault(connectionId, deviceMarker(parts));
        long now = System.currentTimeMillis();

        ModerationEntry blocked = serverModeration().blocked(playerId, playerName, packet.address(), deviceId, now);
        if (blocked != null) {
            String reason = blocked.kind() == ModerationKind.KICK ? "Temporarily kicked" : "Banned";
            if (!blocked.reason().isBlank()) reason += ": " + blocked.reason();
            reason += " (" + ServerModeration.duration(blocked.expiresAt(), now) + ")";
            rejectIdentity(join, connectionId, reason);
            if (!playerId.isBlank()) PeerServerAdminBridge.retain(server, playerId);
            journal.add("ADMISSION_DENIED", playerId.isBlank() ? playerName : playerId,
                    blocked.kind().name().toLowerCase(Locale.ROOT));
            return true;
        }

        ServerModerationState state = serverModeration();
        if (!state.whitelisted(playerId, playerName)) {
            rejectIdentity(join, connectionId, "Server whitelist does not include this identity.");
            journal.add("ADMISSION_DENIED", playerId.isBlank() ? playerName : playerId, "whitelist");
            return true;
        }

        boolean newIdentity = join && existing == null;
        ServerAccessPolicy policy = accessPolicy == null ? ServerAccessPolicy.open() : accessPolicy;
        String reason = "";
        if (newIdentity && policy.maintenance()) reason = policy.maintenanceMessage();
        else if (newIdentity && policy.maxSlots() > 0 && server.persistentSessions().size() >= policy.maxSlots()) {
            reason = "Server player slots are full (" + policy.maxSlots() + ").";
        }
        if (reason.isBlank()) return false;
        rejectIdentity(true, connectionId, reason);
        journal.add("ADMISSION_DENIED", playerName, policy.maintenance() ? "maintenance" : "slots");
        return true;
    }

    private void rejectIdentity(boolean join, ConnectionId connectionId, String reason) {
        String clean = packetPart(reason);
        transport.sendOrdered((join ? "JOIN_DENIED|" : "SESSION_DENIED|") + clean, connectionId);
        motdDelivered.remove(connectionId);
        admissionRecorded.remove(connectionId);
        deviceByConnection.remove(connectionId);
    }

    private void deliverMotdAfterAdmission(String message, ConnectionId connectionId) {
        if (server == null || !admissionMessage(message) || connectionId == null || !connectionId.valid()) return;
        String playerId = server.ownerId(connectionId, "");
        String motd = accessPolicy == null ? "" : accessPolicy.motd();
        if (playerId.isBlank() || motd.isBlank() || !motdDelivered.add(connectionId)) return;
        transport.sendOrdered("SERVER_NOTICE|MOTD: " + packetPart(motd), connectionId);
    }

    private void recordAdmission(String message, ConnectionId connectionId) {
        if (!admissionMessage(message) || connectionId == null || !connectionId.valid()
                || admissionRecorded.contains(connectionId)) return;
        String playerId = server.ownerId(connectionId, "");
        if (playerId.isBlank()) return;
        PersistentPlayerSession session = sessionById(playerId);
        String device = deviceByConnection.getOrDefault(connectionId, deviceByPlayer.getOrDefault(playerId, ""));
        observationStore.record(playerId, session == null ? playerId : session.name(), serverPlayerAddress(playerId), device);
        for (DevPeerAccess peer : server.devAccessPeers()) if (playerId.equals(peer.playerId()) && peer.authorized()) {
            runtimeDevAccess.add(playerId);
            if (server.world.devFreeBuildFor(playerId)) runtimeFreeBuild.add(playerId);
        }
        if (runtimeDevEnabled && runtimeDevAccess.contains(playerId)) {
            if (config.devMode) server.setDevAccess(playerId, true);
            else PeerServerAdminBridge.setDevAccess(server, playerId, true);
            server.applyDevFreeCrafting(playerId, runtimeFreeBuild.contains(playerId));
        }
        journal.add(joinMessage(message) ? "JOIN" : "RECONNECT", playerId, "accepted");
        admissionRecorded.add(connectionId);
    }

    private void captureDevice(String message, ConnectionId connectionId) {
        if (!admissionMessage(message) || connectionId == null || !connectionId.valid()) return;
        String device = deviceMarker(message.split("\\|", -1));
        if (ServerDeviceIdentity.valid(device)) deviceByConnection.put(connectionId, device);
    }

    private void bindDeviceToOwner(ConnectionId connectionId) {
        if (connectionId == null || !connectionId.valid()) return;
        String playerId = server.ownerId(connectionId, "");
        String device = deviceByConnection.getOrDefault(connectionId, "");
        if (!playerId.isBlank() && ServerDeviceIdentity.valid(device)) deviceByPlayer.put(playerId, device);
    }

    private String deviceMarker(String[] parts) {
        if (parts == null) return "";
        for (int i = 0; i + 1 < parts.length; i++) {
            if ("DEVICE".equals(parts[i]) && ServerDeviceIdentity.valid(parts[i + 1])) return parts[i + 1];
        }
        return "";
    }

    private PersistentPlayerSession sessionByName(String name) {
        String wanted = Config.clean(name).toLowerCase(Locale.ROOT);
        for (PersistentPlayerSession session : server.persistentSessions()) {
            if (session != null && Config.clean(session.name()).toLowerCase(Locale.ROOT).equals(wanted)) return session;
        }
        return null;
    }

    private PersistentPlayerSession sessionById(String playerId) {
        if (playerId == null) return null;
        for (PersistentPlayerSession session : server.persistentSessions()) {
            if (session != null && session.playerId().equalsIgnoreCase(playerId)) return session;
        }
        return null;
    }

    private boolean joinMessage(String message) {
        return message != null && (message.startsWith("JOIN|") || message.startsWith("JOIN_V1|"));
    }

    private boolean admissionMessage(String message) {
        return message != null && (joinMessage(message) || message.startsWith("RESUME|") || message.startsWith("RESUME_V1|"));
    }

    private String packetPart(String value) {
        return value == null ? "" : value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private void serverCommand(Runnable action, String playerId) {
        server.change(playerId, action);
        server.broadcastNow();
    }
}
