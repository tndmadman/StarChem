package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ClientViewCache {
    private final Map<String, String> viewByPlayer = new LinkedHashMap<>();
    private final Map<String, Long> revisionByPlayer = new LinkedHashMap<>();
    private final Map<String, Set<String>> knownSystemsByPlayer = new LinkedHashMap<>();
    private final Map<String, Map<String, GalaxyMapSystem>> observedSystemsByPlayer = new LinkedHashMap<>();
    private World registeredWorld;

    void setHome(World world, String playerId) {
        if (!realPlayerId(playerId)) return;
        world.ensurePlayerHome(playerId, WorldNetAccess.usesPrimaryHome(playerId));
        String home = world.playerHomeSystemId(playerId);
        viewByPlayer.put(playerId, home);
        knownSystems(playerId).add(home);
        publish(world);
    }

    void remove(String playerId) {
        viewByPlayer.remove(playerId);
        revisionByPlayer.remove(playerId);
        knownSystemsByPlayer.remove(playerId);
        observedSystemsByPlayer.remove(playerId);
        publish(null);
    }

    void removeSystems(Set<String> systemIds) {
        if (systemIds == null || systemIds.isEmpty()) return;
        viewByPlayer.values().removeIf(systemIds::contains);
        for (Set<String> known : knownSystemsByPlayer.values()) known.removeAll(systemIds);
        for (Map<String, GalaxyMapSystem> observed : observedSystemsByPlayer.values()) {
            for (String systemId : systemIds) observed.remove(systemId);
        }
        publish(null);
    }

    String[] systems(World world) {
        Set<String> out = new LinkedHashSet<>(viewByPlayer.values());
        for (PlayerInfo player : PlayerRegistry.snapshotPlayers()) {
            if (realPlayerId(player.id())) out.add(world.playerHomeSystemId(player.id()));
        }
        out.add(world.activeSystemId());
        out.removeIf(systemId -> systemId == null || systemId.isBlank() || systemId.contains("WAIT"));
        return out.toArray(new String[0]);
    }

    String view(World world, String playerId) {
        if (!realPlayerId(playerId)) return world.activeSystemId();
        String existing = viewByPlayer.get(playerId);
        if (existing != null && !existing.contains("WAIT")) {
            knownSystems(playerId).add(existing);
            publish(world);
            return existing;
        }
        String home = world.playerHomeSystemId(playerId);
        viewByPlayer.put(playerId, home);
        knownSystems(playerId).add(home);
        publish(world);
        return home;
    }

    boolean requestView(World world, String playerId, String systemId, long revision) {
        if (!realPlayerId(playerId) || revision < 0 || !globalSystemExists(world, systemId)) return false;
        Set<String> known = knownSystems(playerId);
        if (!known.contains(systemId) && !playerHasAssetsInSystem(world, playerId, systemId)) return false;
        known.add(systemId);
        viewByPlayer.put(playerId, systemId);
        revisionByPlayer.put(playerId, revision);
        publish(world);
        return true;
    }

    void setViewRevision(String playerId, long revision) {
        if (!realPlayerId(playerId) || revision < 0) return;
        revisionByPlayer.put(playerId, revision);
    }

    long viewRevision(String playerId) {
        return playerId == null ? 0 : revisionByPlayer.getOrDefault(playerId, 0L);
    }

    Snapshot makeSnapshot(World world, String playerId, long sequence) {
        String old = world.activeSystemId();
        try {
            world.activateSystem(view(world, playerId));
            discoverCurrent(world, playerId);
            return FogSnapshotFilter.forPlayer(world, playerId, WorldNetAccess.snapshot(world, sequence));
        } finally {
            world.activateSystem(old);
        }
    }

    GalaxyMapSnapshot galaxySnapshot(World world, String playerId) {
        if (world == null || !realPlayerId(playerId)) return new GalaxyMapSnapshot("", List.of(), List.of());
        GalaxyMapSnapshot authoritative = world.authoritativeGalaxyMapSnapshot();
        if (authoritative == null || authoritative.empty()) return authoritative;
        String viewed = view(world, playerId);
        Set<String> known = knownSystems(playerId);
        known.add(viewed);
        Map<String, GalaxyMapSystem> observed = observedSystems(playerId);
        boolean viewingWithSensors = playerHasAssetsInSystem(world, playerId, viewed);

        List<GalaxyMapSystem> systems = new ArrayList<>();
        Set<String> included = new LinkedHashSet<>();
        for (GalaxyMapSystem current : authoritative.systems()) {
            if (current == null || !known.contains(current.id())) continue;
            boolean active = current.id().equals(viewed);
            GalaxyMapSystem projected;
            if (active && viewingWithSensors) {
                observed.put(current.id(), current);
                projected = withActive(current, true);
            } else {
                GalaxyMapSystem lastObserved = observed.get(current.id());
                projected = withActive(lastObserved == null ? undiscoveredDetails(current) : lastObserved, active);
            }
            systems.add(projected);
            included.add(projected.id());
        }

        List<GalaxyMapLink> links = new ArrayList<>();
        if (authoritative.links() != null) {
            for (GalaxyMapLink link : authoritative.links()) {
                if (link != null && included.contains(link.fromSystemId()) && included.contains(link.toSystemId())) links.add(link);
            }
        }
        return new GalaxyMapSnapshot(viewed, List.copyOf(systems), List.copyOf(links));
    }

    void applyChange(World world, String playerId, Runnable change) {
        String old = world.activeSystemId();
        try {
            world.activateSystem(view(world, playerId));
            change.run();
            world.saveActiveSystem();
            if (realPlayerId(playerId) && !world.activeSystemId().contains("WAIT")) {
                viewByPlayer.put(playerId, world.activeSystemId());
                discoverCurrent(world, playerId);
            }
            publish(world);
        } finally {
            world.activateSystem(old);
        }
    }

    private void discoverCurrent(World world, String playerId) {
        if (world == null || !realPlayerId(playerId)) return;
        String active = world.activeSystemId();
        if (active == null || active.isBlank() || active.contains("WAIT")) return;
        knownSystems(playerId).add(active);
        VisibilityRules.Frame visibility = VisibilityRules.frame(world, playerId);
        if (visibility.sensors().isEmpty()) return;

        GalaxyMapSnapshot authoritative = world.authoritativeGalaxyMapSnapshot();
        if (authoritative != null && authoritative.systems() != null) {
            for (GalaxyMapSystem system : authoritative.systems()) {
                if (system != null && active.equals(system.id())) {
                    observedSystems(playerId).put(active, system);
                    break;
                }
            }
        }
        for (WormholeGate gate : world.wormholes) {
            if (gate != null && gate.toSystemId != null && !gate.toSystemId.isBlank()
                    && visibility.pointVisible(gate.x, gate.y)) {
                knownSystems(playerId).add(gate.toSystemId);
            }
        }
    }

    private Set<String> knownSystems(String playerId) {
        return knownSystemsByPlayer.computeIfAbsent(playerId, ignored -> new LinkedHashSet<>());
    }

    private Map<String, GalaxyMapSystem> observedSystems(String playerId) {
        return observedSystemsByPlayer.computeIfAbsent(playerId, ignored -> new LinkedHashMap<>());
    }

    private boolean playerHasAssetsInSystem(World world, String playerId, String systemId) {
        if (world == null || playerId == null || playerId.isBlank() || !globalSystemExists(world, systemId)) return false;
        String old = world.activeSystemId();
        try {
            world.activateSystem(systemId);
            for (Unit unit : world.units.values()) if (playerId.equals(unit.playerId) && unit.hp > 0) return true;
            for (Base base : world.bases.values()) if (playerId.equals(base.playerId) && base.hp > 0) return true;
            return false;
        } finally {
            world.activateSystem(old);
        }
    }

    private boolean globalSystemExists(World world, String systemId) {
        if (world == null || systemId == null || systemId.isBlank() || systemId.contains("WAIT")) return false;
        GalaxyMapSnapshot snapshot = world.authoritativeGalaxyMapSnapshot();
        if (snapshot == null || snapshot.empty()) return systemId.equals(world.activeSystemId());
        for (GalaxyMapSystem system : snapshot.systems()) {
            if (system != null && systemId.equals(system.id())) return true;
        }
        return false;
    }

    private GalaxyMapSystem undiscoveredDetails(GalaxyMapSystem system) {
        return new GalaxyMapSystem(system.id(), system.name(), system.templateId(), system.lifetime(),
                0, 0, 0, 0, 0, false, system.home(), system.special(), "", "Unknown",
                system.home() ? SystemControlStatus.PROTECTED : SystemControlStatus.NEUTRAL, 0, 0x8A96A3);
    }

    private GalaxyMapSystem withActive(GalaxyMapSystem system, boolean active) {
        return new GalaxyMapSystem(system.id(), system.name(), system.templateId(), system.lifetime(),
                system.ships(), system.bases(), system.resources(), system.localShips(), system.localBases(), active,
                system.home(), system.special(), system.controllerId(), system.controllerName(), system.controlStatus(),
                system.captureProgress(), system.controlColorRgb());
    }

    private void publish(World world) {
        if (world != null) registeredWorld = world;
        if (registeredWorld != null) ViewedSystemRegistry.replace(registeredWorld, viewByPlayer.values());
    }

    private boolean realPlayerId(String id) {
        return id != null && !id.isBlank() && !"WAIT".equals(id) && !NpcRules.isNpcFaction(id);
    }
}
