package com.tndmadman.rts;

import java.util.Comparator;
import java.util.Set;

/**
 * Owns legitimate worker replacement for organized NPC factions.
 *
 * Worker demand is galaxy-wide, but production is intentionally owned by the
 * configured faction home so every expedition cannot independently rebuild to
 * the faction-wide worker cap. Jobs use the normal station production queue and
 * remain blocked until the worker-recovery budget can fund them.
 */
final class NpcWorkerProductionSystem {
    private NpcWorkerProductionSystem() { }

    static void update(World world, NpcFaction faction) {
        if (world == null || faction == null || !faction.enabled()
                || faction.behavior() != NpcBehavior.FACTION || faction.maxWorkers() <= 0) return;

        String homeSystemId = NpcFactionRuntime.homeSystemIdFor(faction);
        if (!homeSystemId.equals(world.activeSystemId())) return;

        WorkerSnapshot snapshot = inspect(world, faction);
        if (snapshot.livingWorkers >= faction.maxWorkers()) return;

        // Only one replacement request may be outstanding at a time. A funded
        // job continues to count as recovery work until the ship actually exits
        // the production queue.
        if (snapshot.pending != null) {
            fundPending(world, faction, snapshot.pending);
            return;
        }

        String workerTypeId = firstWorkerType(faction);
        if (workerTypeId.isBlank()) return;
        Base base = productionBase(world, faction, workerTypeId);
        if (base == null) return;

        ProductionJob job = new ProductionJob(
                "P" + base.nextProductionJobId++,
                ProductionJobKind.SHIP,
                workerTypeId,
                Rules.ship(workerTypeId).buildTimeSeconds,
                Rules.ship(workerTypeId).buildTimeSeconds,
                false,
                "");
        job.blockedReason = ProductionSystem.WAITING_FOR_RESOURCES;
        base.productionQueue.add(job);
        AiDevLog.add(world, faction,
                "queued worker recovery: " + Rules.ship(workerTypeId).name
                        + " at " + base.type().name);

        fundPending(world, faction,
                new PendingWorkerJob(homeSystemId, base.id, job.id, workerTypeId, false));
    }

    private static WorkerSnapshot inspect(World world, NpcFaction faction) {
        int livingWorkers = 0;
        PendingWorkerJob pending = null;
        String previousSystemId = world.activeSystemId();
        String previousStatus = world.status;
        Set<String> workerTypes = faction.workerTypeSet();
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        try {
            if (map == null || map.systems() == null || map.systems().isEmpty()) {
                ScanResult result = scanCurrent(world, faction, workerTypes, world.activeSystemId());
                livingWorkers += result.livingWorkers;
                pending = result.pending;
            } else {
                for (GalaxyMapSystem system : map.systems()) {
                    if (system == null || system.id() == null || system.id().isBlank()) continue;
                    world.activateSystem(system.id());
                    ScanResult result = scanCurrent(world, faction, workerTypes, system.id());
                    livingWorkers += result.livingWorkers;
                    if (pending == null && result.pending != null) pending = result.pending;
                }
            }
        } finally {
            if (previousSystemId != null && !previousSystemId.isBlank()) world.activateSystem(previousSystemId);
            world.status = previousStatus;
        }
        return new WorkerSnapshot(livingWorkers, pending);
    }

    private static ScanResult scanCurrent(World world, NpcFaction faction, Set<String> workerTypes,
                                          String systemId) {
        int livingWorkers = 0;
        PendingWorkerJob pending = null;
        for (Unit unit : world.units.values()) {
            if (!faction.id().equals(unit.playerId) || unit.hp <= 0) continue;
            if (!unit.type().harvestKinds.isEmpty()
                    && (workerTypes.isEmpty() || workerTypes.contains(unit.shipTypeId))) livingWorkers++;
        }
        for (Base base : world.bases.values()) {
            if (!faction.id().equals(base.playerId) || base.hp <= 0) continue;
            for (ProductionJob job : base.productionQueue) {
                if (job.kind != ProductionJobKind.SHIP || !workerTypes.contains(job.itemId)) continue;
                pending = new PendingWorkerJob(systemId, base.id, job.id, job.itemId, job.resourcesReserved);
                return new ScanResult(livingWorkers, pending);
            }
        }
        return new ScanResult(livingWorkers, pending);
    }

    private static void fundPending(World world, NpcFaction faction, PendingWorkerJob pending) {
        if (pending == null || pending.funded) return;
        String previousSystemId = world.activeSystemId();
        String previousStatus = world.status;
        try {
            world.activateSystem(pending.systemId);
            Base base = world.bases.get(pending.baseId);
            ProductionJob job = ProductionSystem.findJob(base, pending.jobId);
            if (base == null || job == null || job.kind != ProductionJobKind.SHIP
                    || job.resourcesReserved || !pending.workerTypeId.equals(job.itemId)) return;

            ShipType worker = Rules.ship(job.itemId);
            if (worker == null || !NpcResourceBudget.canAfford(
                    world, faction, NpcBudgetCategory.WORKER_RECOVERY, worker.buildCost)) return;
            if (!NpcResourceBudget.spend(
                    world, faction, NpcBudgetCategory.WORKER_RECOVERY, worker.buildCost)) return;

            job.resourcesReserved = true;
            job.blockedReason = "";
            AiDevLog.add(world, faction,
                    "funded worker recovery: " + worker.name + " at " + base.type().name);
            ProductionQueueScheduler.update(world, 0.0);
        } finally {
            if (previousSystemId != null && !previousSystemId.isBlank()) world.activateSystem(previousSystemId);
            world.status = previousStatus;
        }
    }

    private static Base productionBase(World world, NpcFaction faction, String workerTypeId) {
        return world.bases.values().stream()
                .filter(base -> faction.id().equals(base.playerId) && base.hp > 0)
                .filter(base -> base.type().buildableShips.contains(workerTypeId))
                .filter(base -> ResearchRules.shipUnlocked(world, faction.id(), workerTypeId))
                .min(Comparator.comparingInt((Base base) -> base.productionQueue.size())
                        .thenComparing(base -> base.id))
                .orElse(null);
    }

    private static String firstWorkerType(NpcFaction faction) {
        for (String workerTypeId : faction.workerUnitTypes()) {
            if (!Rules.SHIPS.containsKey(workerTypeId)) continue;
            if (!Rules.ship(workerTypeId).harvestKinds.isEmpty()) return workerTypeId;
        }
        return "";
    }

    private record WorkerSnapshot(int livingWorkers, PendingWorkerJob pending) { }
    private record ScanResult(int livingWorkers, PendingWorkerJob pending) { }
    private record PendingWorkerJob(String systemId, String baseId, String jobId,
                                    String workerTypeId, boolean funded) { }
}
