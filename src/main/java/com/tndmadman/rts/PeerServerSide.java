package com.tndmadman.rts;

import java.net.InetAddress;
import java.util.*;

final class PeerServerSide {
    private static final long SNAPSHOT_MS = 100, TIMEOUT_MS = 4000;
    private static final double MAX_DEV_RESOURCE_AMOUNT = 100_000.0;
    final World world;
    final Config config;
    final PeerTransport transport;
    final ClientViewCache views = new ClientViewCache();
    private final Map<String, ServerPeer> peers = new LinkedHashMap<>();
    private final Map<String, String> peerNames = new LinkedHashMap<>();
    private int nextPlayer = 1;
    private long sequence = 1, lastSnapshot;

    PeerServerSide(Config config, World world, PeerTransport transport) {
        this.config = config;
        this.world = world;
        this.transport = transport;
    }

    String statusLine() {
        return "HOST " + world.systemName() + " UDP " + transport.localPort() + " | clients " + peers.size() + " | pending " + transport.pendingCount() + (config.devMode ? " | dev host" : "");
    }

    void updateWorlds(double dt) {
        String old = world.activeSystemId();
        String[] systems = allKnownSystems();
        ResourceNetDebug.serverUpdateSystems(world, systems, dt);
        for (String systemId : systems) {
            world.activateSystem(systemId);
            world.updateCurrentSystem(dt);
        }
        world.activateSystem(old);
    }

    private String[] allKnownSystems() {
        Set<String> out = new LinkedHashSet<>();
        GalaxyMapSnapshot snapshot = world.galaxyMapSnapshot();
        if (snapshot != null && !snapshot.empty()) {
            for (GalaxyMapSystem system : snapshot.systems()) {
                if (system == null || system.id() == null || system.id().isBlank()) continue;
                out.add(system.id());
            }
        }
        if (out.isEmpty()) out.add(world.activeSystemId());
        out.removeIf(systemId -> systemId == null || systemId.isBlank() || systemId.contains("WAIT"));
        return out.toArray(new String[0]);
    }

    void tick(long now) {
        removeTimedOut(now);
        if (now - lastSnapshot >= SNAPSHOT_MS) { broadcastNow(); lastSnapshot = now; }
    }

    void handle(String message, NetPacket packet) { PeerServerPackets.handle(this, message, packet); }
    void broadcastNow() { sequence = PeerSyncBatch.send(world, views, PeerSyncTargets.array(peers.values()), sequence, transport::send); }
    void sendInitial(ServerPeer peer) { sequence = PeerSyncBatch.sendInitial(world, views, peer, sequence, transport::send); }
    void sendInitialTo(String endpoint) { sendInitial(peers.get(endpoint)); }
    void change(String playerId, Runnable action) { views.applyChange(world, playerId, action); }
    String ownerId(String endpoint, String fallback) { ServerPeer peer = peers.get(endpoint); return peer == null ? fallback : peer.playerId(); }
    boolean owns(String endpoint, String playerId) { ServerPeer peer = peers.get(endpoint); return peer != null && playerId != null && playerId.equals(peer.playerId()); }
    boolean devAllowed(String endpoint, String playerId) {
        ServerPeer peer = peers.get(endpoint);
        return config.devMode && peer != null && playerId != null && playerId.equals(peer.playerId()) && peer.devFreeBuild();
    }
    boolean localDevAllowed(String playerId) { return config.devMode && playerId != null && !playerId.isBlank() && !"WAIT".equals(playerId); }
    void touch(String endpoint) { ServerPeer p = peers.get(endpoint); if (p != null) peers.put(endpoint, new ServerPeer(p.playerId(), p.address(), p.port(), System.currentTimeMillis(), p.devFreeBuild())); }

    void join(String endpoint, InetAddress address, int port, String name, boolean requestedDev) {
        String cleanName = Config.clean(name);
        ServerPeer old = peers.get(endpoint);
        if (old != null) {
            String existingName = peerNames.getOrDefault(endpoint, cleanName);
            transport.reliable(welcome(old.playerId(), existingName, colorFor(peers.size()), old.devFreeBuild()), address, port);
            return;
        }
        if (nameInUse(cleanName)) {
            transport.reliable(joinDenied("Name already in use: " + cleanName), address, port);
            return;
        }
        String id = "P" + nextPlayer++;
        int rgb = colorFor(peers.size() + 1);
        boolean devAllowed = config.devMode && requestedDev;
        ServerPeer peer = new ServerPeer(id, address, port, System.currentTimeMillis(), devAllowed);
        peers.put(endpoint, peer);
        peerNames.put(endpoint, cleanName);
        PlayerRegistry.register(id, cleanName, rgb, false);
        world.setDevFreeBuild(id, devAllowed);
        WorldNetAccess.addPeerGroup(world, id);
        views.setHome(world, id);
        transport.reliable(welcome(id, cleanName, rgb, devAllowed), address, port);
        transport.reliable(envMessage(), address, port);
        sendInitial(peer);
    }

    void applyDevFreeCrafting(String playerId, boolean enabled) {
        change(playerId, () -> {
            world.setDevFreeBuild(playerId, enabled);
            world.status = "Dev free crafting " + (enabled ? "enabled" : "disabled") + " for " + playerId + ".";
        });
        broadcastNow();
    }

    void applyDevHangarResource(String playerId, String baseId, Material material, double amount) {
        if (baseId == null || baseId.isBlank() || material == null || amount <= 0 || Double.isNaN(amount) || Double.isInfinite(amount)) return;
        double safeAmount = Math.min(amount, MAX_DEV_RESOURCE_AMOUNT);
        change(playerId, () -> {
            Base base = world.bases.get(baseId);
            if (base == null || !playerId.equals(base.playerId)) return;
            HangarStore.add(base.inventory, material, safeAmount);
            world.status = "Dev added " + (int)safeAmount + " " + material.label + " to " + base.id + " hangar.";
        });
        broadcastNow();
    }

    void applyDevAiCommand(String playerId, String command) {
        if (command == null || command.isBlank()) return;
        change(playerId, () -> applyAiCommand(command));
        broadcastNow();
    }

    void removePeer(String endpoint) {
        peerNames.remove(endpoint);
        ServerPeer peer = peers.remove(endpoint);
        if (peer == null) return;
        String playerId = peer.playerId();
        world.setDevFreeBuild(playerId, false);
        PlayerRegistry.remove(playerId);
        views.remove(playerId);
        Set<String> deletedSystems = world.removePlayerAndPruneEmptySystems(playerId);
        views.removeSystems(deletedSystems);
        sendDeletedSystems(deletedSystems);
        if (!deletedSystems.isEmpty()) world.status = "Removed " + deletedSystems.size() + " abandoned system(s) after " + playerId + " left.";
        broadcastNow();
    }

    private void applyAiCommand(String command) {
        switch (command) {
            case "togglePauseAi" -> AiDevSettings.pauseAi = !AiDevSettings.pauseAi;
            case "stepAi" -> AiDevSettings.stepAi = true;
            case "toggleFastAi" -> AiDevSettings.fastAi = !AiDevSettings.fastAi;
            case "toggleFreezePlayerUnits" -> AiDevSettings.freezePlayerUnits = !AiDevSettings.freezePlayerUnits;
            case "toggleFreezeNpcCombat" -> AiDevSettings.freezeNpcCombat = !AiDevSettings.freezeNpcCombat;
            case "toggleDisableAttacks" -> AiDevSettings.disableAttacks = !AiDevSettings.disableAttacks;
            case "toggleDisableEconomy" -> AiDevSettings.disableEconomy = !AiDevSettings.disableEconomy;
            case "togglePreset" -> AiDevSettings.togglePreset();
            case "spawnCorsairs" -> AiDevCommands.spawnCorsairs(world);
            case "killCorsairs" -> AiDevCommands.killCorsairs(world);
            case "resetCorsairs" -> AiDevCommands.resetCorsairs(world);
            case "giveCorsairResources" -> AiDevCommands.giveCorsairResources(world);
            case "givePlayerResources" -> AiDevCommands.givePlayerResources(world);
            case "spawnLootField" -> AiDevCommands.spawnLootField(world);
            case "spawnAttackWave" -> AiDevCommands.spawnAttackWave(world);
            case "forceRaid" -> AiDevCommands.forceRaid(world);
            case "forceStation" -> AiDevCommands.forceStation(world);
            case "forceResearch" -> AiDevCommands.forceResearch(world);
            case "forceCraft" -> AiDevCommands.forceCraft(world);
            default -> { }
        }
    }

    private void sendDeletedSystems(Set<String> deletedSystems) {
        if (deletedSystems == null || deletedSystems.isEmpty() || peers.isEmpty()) return;
        String message = "SYSDEL|" + String.join(";", deletedSystems);
        for (ServerPeer peer : peers.values()) transport.reliable(message, peer.address(), peer.port());
    }

    private boolean nameInUse(String name) {
        String wanted = normalizedName(name);
        if (wanted.equals(normalizedName(config.playerName))) return true;
        for (String peerName : peerNames.values()) if (wanted.equals(normalizedName(peerName))) return true;
        return false;
    }

    private String normalizedName(String name) { return Config.clean(name).toLowerCase(Locale.ROOT); }
    private String joinDenied(String message) { return "JOIN_DENIED|" + packetPart(message); }
    private String packetPart(String value) { return value == null ? "" : value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim(); }
    boolean requestedDev(String[] parts) { return parts.length > 2 && flag(parts[2]); }
    String endpoint(InetAddress address, int port) { return address.getHostAddress() + ':' + port; }
    private void removeTimedOut(long now) { for (String ep : new ArrayList<>(peers.keySet())) if (now - peers.get(ep).lastSeen() > TIMEOUT_MS) removePeer(ep); }
    private String envMessage() { return "ENV|" + world.systemId() + "|" + world.systemSeed() + "|" + Calc.round(world.systemTime()); }
    private String welcome(String id, String name, int rgb, boolean devAllowed) { return "WELCOME|" + id + "|" + Config.clean(name) + "|" + rgb + "|" + world.systemId() + "|" + world.systemSeed() + "|" + Calc.round(world.systemTime()) + "|DEV|" + (devAllowed ? "1" : "0"); }
    private int colorFor(int i) { int[] c = {0x50BEFF,0xFF5F55,0x7DFF7A,0xFFE066,0xC77DFF,0xFF9F1C}; return c[Math.floorMod(i, c.length)]; }
    private boolean flag(String value) { return "1".equals(value) || "true".equalsIgnoreCase(value) || "DEV".equalsIgnoreCase(value) || "YES".equalsIgnoreCase(value); }
}
