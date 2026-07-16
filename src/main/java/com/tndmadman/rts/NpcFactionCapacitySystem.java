package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Computes authoritative galaxy-wide organized-faction capacity.
 *
 * Living assets, queued production, persistent station construction, and an
 * expedition foothold that already owns a paid package all count toward their
 * configured limits. Local systems may execute production, but they must check
 * this snapshot immediately before committing resources.
 */
final class NpcFactionCapacitySystem {
    private NpcFactionCapacitySystem() { }

    static synchronized NpcFactionCapacitySnapshot snapshot(World world, NpcFaction faction) {
        if (world == null || faction == null) return NpcFactionCapacitySnapshot.EMPTY;

        String previousSystemId = world.activeSystemId();
        String previousStatus = world.status;
        int stations = 0;
        int queuedStations = 0;
        int combat = 0;
        int support = 0;
        int industry = 0;
        int workers = 0;
        Map<String, Integer> shipTypes = new LinkedHashMap<>();
        Set<String> workerTypes = faction.workerTypeSet();
        Set<String> supportTypes = faction.supportTypeSet();

        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        try {
            if (map == null || map.systems() == null || map.systems().isEmpty()) {
                Counts counts = countCurrent(world, faction, workerTypes, supportTypes);
                stations += counts.stations;
                queuedStations += counts.queuedStations;
                combat += counts.combat;
                support += counts.support;
                industry += counts.industry;
                workers += counts.workers;
                merge(shipTypes, counts.shipTypes);
            } else {
                for (GalaxyMapSystem system : map.systems()) {
                    if (system == null || system.id() == null || system.id().isBlank()) continue;
                    world.activateSystem(system.id());
                    Counts counts = countCurrent(world, faction, workerTypes, supportTypes);
                    stations += counts.stations;
                    queuedStations += counts.queuedStations;
                    combat += counts.combat;
                    support += counts.support;
                    industry += counts.industry;
                    workers += counts.workers;
                    merge(shipTypes, counts.shipTypes);
                }
            }
        } finally {
            if (previousSystemId != null && !previousSystemId.isBlank()) {
                world.activateSystem(previousSystemId);
            }
            world.status = previousStatus;
        }

        int constructionPlans = NpcStationConstructionSystem.activePlanCount(world, faction);
        int expeditionFootholds = committedExpeditionFootholds(world, faction);
        return new NpcFactionCapacitySnapshot(
                stations,
                queuedStations,
                constructionPlans,
                expeditionFootholds,
                combat,
                support,
                industry,
                workers,
                Map.copyOf(shipTypes));
    }

    private static Counts countCurrent(World world, NpcFaction faction,
                                       Set<String> workerTypes, Set<String> supportTypes) {
        int stations = 0;
        int queuedStations = 0;
        int combat = 0;
        int support = 0;
        int industry = 0;
        int workers = 0;
        Map<String, Integer> shipTypes = new LinkedHashMap<>();

        for (Base base : world.bases.values()) {
            if (!faction.id().equals(base.playerId) || base.hp <= 0) continue;
            stations++;
            for (ProductionJob job : base.productionQueue) {
                if (job.kind == ProductionJobKind.STATION_PACKAGE) {
                    queuedStations++;
                    continue;
                }
                if (job.kind != ProductionJobKind.SHIP) continue;
                ShipType ship = Rules.ship(job.itemId);
                if (ship == null) continue;
                shipTypes.merge(ship.id, 1, Integer::sum);
                if (WeaponRules.armed(ship)) combat++;
                if (supportTypes.contains(ship.id) || ship.baseBuilder) support++;
                if (faction.industryUnitTypes().contains(ship.id)) industry++;
                if (!ship.harvestKinds.isEmpty()
                        && (workerTypes.isEmpty() || workerTypes.contains(ship.id))) workers++;
            }
        }

        for (Unit unit : world.units.values()) {
            if (!faction.id().equals(unit.playerId) || unit.hp <= 0) continue;
            ShipType ship = unit.type();
            shipTypes.merge(unit.shipTypeId, 1, Integer::sum);
            if (WeaponRules.armed(ship)) combat++;
            if (supportTypes.contains(unit.shipTypeId) || ship.baseBuilder) support++;
            if (faction.industryUnitTypes().contains(unit.shipTypeId)) industry++;
            if (!ship.harvestKinds.isEmpty()
                    && (workerTypes.isEmpty() || workerTypes.contains(unit.shipTypeId))) workers++;
        }

        return new Counts(stations, queuedStations, combat, support,
                industry, workers, shipTypes);
    }

    private static int committedExpeditionFootholds(World world, NpcFaction faction) {
        NpcExpeditionSnapshot snapshot = NpcExpeditionSystem.snapshot(world, faction);
        if (!snapshot.active()) return 0;
        return switch (snapshot.state()) {
            case ASSEMBLING, LAUNCHING, TRAVELLING -> 1;
            default -> 0;
        };
    }

    private static void merge(Map<String, Integer> target, Map<String, Integer> source) {
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    private record Counts(int stations, int queuedStations, int combat,
                          int support, int industry, int workers,
                          Map<String, Integer> shipTypes) { }
}

record NpcFactionCapacitySnapshot(int livingStations,
                                  int queuedStationPackages,
                                  int activeConstructionPlans,
                                  int committedExpeditionFootholds,
                                  int combat,
                                  int support,
                                  int industry,
                                  int workers,
                                  Map<String, Integer> shipTypes) {
    static final NpcFactionCapacitySnapshot EMPTY = new NpcFactionCapacitySnapshot(
            0, 0, 0, 0, 0, 0, 0, 0, Map.of());

    int stationCommitments() {
        return livingStations + queuedStationPackages
                + activeConstructionPlans + committedExpeditionFootholds;
    }

    int shipTypeCount(String shipTypeId) {
        if (shipTypeId == null || shipTypeId.isBlank()) return 0;
        return shipTypes.getOrDefault(shipTypeId, 0);
    }

    boolean hasStationType(World world, NpcFaction faction, String typeId) {
        if (world == null || faction == null || typeId == null || typeId.isBlank()) return false;
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        try {
            if (map != null && map.systems() != null) {
                for (GalaxyMapSystem system : map.systems()) {
                    if (system == null || system.id() == null || system.id().isBlank()) continue;
                    world.activateSystem(system.id());
                    for (Base base : world.bases.values()) {
                        if (faction.id().equals(base.playerId) && base.hp > 0
                                && typeId.equals(base.typeId)) return true;
                    }
                }
            }
            return false;
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = previousStatus;
        }
    }
}
