package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.Map;

final class NpcGalaxyDirector {
    private static final double EXPANSION_COOLDOWN_SECONDS = 150.0;
    private final Map<String, Double> cooldowns = new LinkedHashMap<>();

    void update(World world, double dt) {
        if (world == null || dt <= 0) return;
        cooldowns.replaceAll((key, value) -> Math.max(0, value - dt));
        GalaxyMapSnapshot snapshot = world.authoritativeGalaxyMapSnapshot();
        if (snapshot == null || snapshot.empty()) return;
        GalaxyMapSystem current = system(snapshot, world.activeSystemId());
        if (current == null || !current.staticSystem() || current.home()) return;

        for (NpcFaction faction : NpcRules.factions()) {
            if (!faction.enabled() || faction.behavior() != NpcBehavior.FACTION) continue;
            NpcStrategicState strategy = NpcStrategicDirector.update(world, faction, dt);
            if (strategy != NpcStrategicState.EXPAND) continue;
            if (!NpcFactionRuntime.homeSystemIdFor(faction).equals(current.id())) continue;
            if (!faction.id().equals(current.controllerId())) continue;
            if (!NpcResourceBudget.canLaunchExpansion(world, faction)) continue;
            String key = current.id() + "|" + faction.id();
            if (cooldowns.getOrDefault(key, 0.0) > 0) continue;
            GalaxyMapSystem target = target(snapshot, current.id(), faction.id());
            if (target == null) continue;
            int fleetSize = Math.max(2, faction.raidFleetSize());
            if (world.launchNpcExpedition(faction.id(), target.id(), fleetSize)) {
                cooldowns.put(key, EXPANSION_COOLDOWN_SECONDS);
                world.status = faction.name() + " launched an expedition toward " + target.name() + ".";
                return;
            }
        }
    }

    private GalaxyMapSystem target(GalaxyMapSnapshot snapshot, String fromId, String factionId) {
        GalaxyMapSystem fallback = null;
        for (GalaxyMapLink link : snapshot.links()) {
            String candidateId = fromId.equals(link.fromSystemId()) ? link.toSystemId()
                    : fromId.equals(link.toSystemId()) ? link.fromSystemId() : "";
            if (candidateId.isBlank()) continue;
            GalaxyMapSystem candidate = system(snapshot, candidateId);
            if (candidate == null || !candidate.staticSystem() || candidate.home()) continue;
            if (factionId.equals(candidate.controllerId())) continue;
            if (!NpcSystemScope.allowsExpansion(candidate.id(), factionId)) continue;
            if (candidate.controlStatus() == SystemControlStatus.NEUTRAL) return candidate;
            if (fallback == null) fallback = candidate;
        }
        return fallback;
    }

    private GalaxyMapSystem system(GalaxyMapSnapshot snapshot, String id) {
        if (snapshot == null || snapshot.systems() == null || id == null) return null;
        for (GalaxyMapSystem system : snapshot.systems()) if (system != null && id.equals(system.id())) return system;
        return null;
    }
}