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
    private final ServerPlayerIntelStore persistence;
    private World registeredWorld;

    ClientViewCache() {
        this(ServerPlayerIntelStore.consumeConfigured());
    }

    ClientViewCache(ServerPlayerIntelStore persistence) {
        this.persistence = persistence == null ? ServerPlayerIntelStore.consumeConfigured() : persistence;
        for (Map.Entry<String, ServerPlayerIntelStore.PlayerIntel> entry : this.persistence.load().entrySet()) {
            String playerId = entry.getKey();
            ServerPlayerIntelStore.PlayerIntel intel = entry.getValue();
            if (!realPlayerId(playerId) || intel == null) continue;
            if (!intel.viewedSystemId().isBlank()) viewByPlayer.put(playerId, intel.viewedSystemId());
            knownSystemsByPlayer.put(playerId, new LinkedHashSet<>(intel.knownSystemIds()));
        }
    }

    void setHome(World world, String playerId) {
        if (world == null || !realPlayerId(playerId)) return;
        world.ensurePlayerHome(playerId, WorldNetAccess.usesPrimaryHome(playerId));
        String home = world.playerHomeSystemId(playerId);
        Set<String> known = knownSystems(playerId);
        boolean changed = known.add(home);
        changed |= known.removeIf(systemId -> !globalSystemExists(world, systemId));
        String existing = viewByPlayer.get(playerId);
        if (existing == null || existing.isBlank() || !known.contains(existing) || !globalSystemExists(world, existing)) {
            changed |= !home.equals(viewByPlayer.put(playerId, home));
        } else {
            changed |= known.add(existing);
        }
        publish(world);
        if (changed) persist();
    }

    void remove(String playerId) {
        boolean changed = viewByPlayer.remove(playerId) != null;
        changed |= revisionByPlayer.remove(playerId) != null;
        changed |= knownSystemsByPlayer.remove(playerId) != null;
        changed |= observedSystemsByPlayer.remove(playerId) != null;
        publish(null);
        if (changed) persist();
    }

    void removeSystems(Set<String> systemIds) {
        if (systemIds == null || systemIds.isEmpty()) return;
        boolean changed = viewByPlayer.values().removeIf(systemIds::contains);
        for (Set<String> known : knownSystemsByPlayer.values()) changed |= known.removeAll(systemIds);
        for (Map<String, GalaxyMapSystem> observed : observedSystemsByPlayer.values()) {
            for (String systemId : systemIds) observed.remove(systemId);
        }
        publish(null);
        if (changed) persist();
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
        if (existing != null && !existing.contains("WAIT") && globalSystemExists(world, existing)) {
            boolean changed = knownSystems(playerId).add(existing);
            publish(world);
            if (changed) persist();
            return existing;
        }
        String home = world.playerHomeSystemId(playerId);
        boolean changed = !home.equals(viewByPlayer.put(playerId, home));
        changed |= knownSystems(playerId).add(home);
        publish(world);
        if (changed) persist();
        return home;
    }

    boolean requestView(World world, String playerId, String systemId, long revision) {
        if (!realPlayerId(playerId) || revision < 0 || !globalSystemExists(world, systemId)) return false;
        Set<String> known = knownSystems(playerId);
        if (!known.contains(systemId) && !playerHasAssetsInSystem(world, playerId, systemId)) return false;
        boolean changed = known.add(systemId);
        changed |= !systemId.equals(viewByPlayer.put(playerId, systemId));
        revisionByPlayer.put(playerId, revision);
        publish(world);
        if (changed) persist();
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
            if (discoverCurrent(world, playerId)) persist();
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
        if (known.add(viewed)) persist();
        Map<String, GalaxyMapSystem> observed = observedSystems(playerId);

        List<GalaxyMapSystem> systems = new ArrayList<>();
        Set<String> included = new LinkedHashSet<>();
        for (GalaxyMapSystem current : authoritative.systems()) {
            if (current == null || !known.contains(current.id())) continue;
            boolean active = current.id().equals(viewed);
            GalaxyMapSystem projected;
            if (active) {
                GalaxyMapSystem observation = visibleObservation(world, playerId, current);
                if (observation != null) observed.put(current.id(), observation);
                GalaxyMapSystem lastObserved = observation == null ? observed.get(current.id()) : observation;
                projected = withActive(lastObserved == null ? undiscoveredDetails(current) : lastObserved, true);
            } else {
                GalaxyMapSystem lastObserved = observed.get(current.id());
                projected = withActive(lastObserved == null ? undiscoveredDetails(current) : lastObserved, false);
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
            boolean changed = false;
            if (realPlayerId(playerId) && !world.activeSystemId().contains("WAIT")) {
                changed |= !world.activeSystemId().equals(viewByPlayer.put(playerId, world.activeSystemId()));
                changed |= discoverCurrent(world, playerId);
            }
            publish(world);
            if (changed) persist();
        } finally {
            world.activateSystem(old);
        }
    }

    private boolean discoverCurrent(World world, String playerId) {
        if (world == null || !realPlayerId(playerId)) return false;
        String active = world.activeSystemId();
        if (active == null || active.isBlank() || active.contains("WAIT")) return false;
        boolean changed = knownSystems(playerId).add(active);
        VisibilityRules.Frame visibility = VisibilityRules.frame(world, playerId);
        if (visibility.sensors().isEmpty()) return changed;

        GalaxyMapSnapshot authoritative = world.authoritativeGalaxyMapSnapshot();
        if (authoritative != null && authoritative.systems() != null) {
            for (GalaxyMapSystem system : authoritative.systems()) {
                if (system == null || !active.equals(system.id())) continue;
                GalaxyMapSystem observation = visibleObservation(world, playerId, system);
                if (observation != null) observedSystems(playerId).put(active, observation);
                break;
            }
        }
        for (WormholeGate gate : world.wormholes) {
            if (gate != null && gate.toSystemId != null && !gate.toSystemId.isBlank()
                    && visibility.pointVisible(gate.x, gate.y)) {
                changed |= knownSystems(playerId).add(gate.toSystemId);
            }
        }
        return changed;
    }

    private GalaxyMapSystem visibleObservation(World world, String playerId, GalaxyMapSystem system) {
        if (world == null || system == null || playerId == null || playerId.isBlank()) return null;
        String old = world.activeSystemId();
        try {
            world.activateSystem(system.id());
            if (!system.id().equals(world.activeSystemId())) return null;
            VisibilityRules.Frame visibility = VisibilityRules.frame(world, playerId);
            if (visibility.sensors().isEmpty()) return null;

            int ships = 0;
            int bases = 0;
            int resources = 0;
            int localShips = 0;
            int localBases = 0;
            for (Unit unit : world.units.values()) {
                if (unit == null || unit.hp <= 0) continue;
                if (playerId.equals(unit.playerId)) localShips++;
                if (visibility.unitVisible(unit)) ships++;
            }
            for (Base base : world.bases.values()) {
                if (base == null || base.hp <= 0) continue;
                if (playerId.equals(base.playerId)) localBases++;
                if (visibility.baseVisible(base)) bases++;
            }
            for (ResourceNode node : world.resources) {
                if (node != null && node.active && visibility.pointVisible(node.x, node.y)) resources++;
            }
            return new GalaxyMapSystem(system.id(), system.name(), system.templateId(), system.lifetime(),
                    ships, bases, resources, localShips, localBases, false, system.home(), system.special(),
                    system.controllerId(), system.controllerName(), system.controlStatus(), system.captureProgress(),
                    system.controlColorRgb());
        } finally {
            world.activateSystem(old);
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

    private void persist() {
        Map<String, ServerPlayerIntelStore.PlayerIntel> state = new LinkedHashMap<>();
        Set<String> playerIds = new LinkedHashSet<>(knownSystemsByPlayer.keySet());
        playerIds.addAll(viewByPlayer.keySet());
        for (String playerId : playerIds) {
            if (!realPlayerId(playerId)) continue;
            state.put(playerId, new ServerPlayerIntelStore.PlayerIntel(
                    viewByPlayer.getOrDefault(playerId, ""), knownSystemsByPlayer.getOrDefault(playerId, Set.of())));
        }
        persistence.save(state);
    }

    private void publish(World world) {
        if (world != null) registeredWorld = world;
        if (registeredWorld != null) ViewedSystemRegistry.replace(registeredWorld, viewByPlayer.values());
    }

    private boolean realPlayerId(String id) {
        return id != null && !id.isBlank() && !"WAIT".equals(id) && !NpcRules.isNpcFaction(id);
    }
}
