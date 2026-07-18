package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Owns one legitimate worker-replacement request per organized NPC faction.
 *
 * Demand and pending work are galaxy-wide. Planning runs from the preferred
 * home-system authority, but the producer deterministically fails over to a
 * capable remote station when the home no longer has one.
 */
final class NpcWorkerProductionSystem {
    private NpcWorkerProductionSystem() { }

    static void update(World world, NpcFaction faction) {
        if (world == null || faction == null || !faction.enabled()
                || faction.behavior() != NpcBehavior.FACTION
                || faction.maxWorkers() <= 0) return;

        String homeSystemId = NpcFactionRuntime.homeSystemIdFor(faction);
        if (!homeSystemId.equals(world.activeSystemId())) return;

        WorkerSnapshot snapshot = inspect(world, faction);
        if (snapshot.livingWorkers >= faction.maxWorkers()) {
            if (snapshot.pending != null) cancelPending(world, faction, snapshot.pending);
            return;
        }

        if (snapshot.pending != null) {
            fundPending(world, faction, snapshot.pending);
            return;
        }

        String workerTypeId = firstWorkerType(faction);
        if (workerTypeId.isBlank()) return;
        Producer producer = selectProducer(world, faction, workerTypeId, homeSystemId);
        if (producer == null) return;

        String previousSystemId = world.activeSystemId();
        String previousStatus = world.status;
        PendingWorkerJob pending = null;
        try {
            world.activateSystem(producer.systemId);
            Base base = world.bases.get(producer.baseId);
            if (!validProducer(world, faction, base, workerTypeId)) return;

            ShipType worker = Rules.ship(workerTypeId);
            ProductionJob job = new ProductionJob(
                    "P" + base.nextProductionJobId++,
                    ProductionJobKind.SHIP,
                    workerTypeId,
                    worker.buildTimeSeconds,
                    worker.buildTimeSeconds,
                    false,
                    "");
            job.blockedReason = ProductionSystem.WAITING_FOR_RESOURCES;
            base.productionQueue.add(job);
            pending = new PendingWorkerJob(
                    producer.systemId, base.id, job.id, workerTypeId, false);
            AiDevLog.add(world, faction,
                    "queued worker recovery: " + worker.name + " at "
                            + producer.systemId + "/" + base.type().name);
            world.saveActiveSystem();
        } finally {
            if (previousSystemId != null && !previousSystemId.isBlank()) {
                world.activateSystem(previousSystemId);
            }
            world.status = previousStatus;
        }
        if (pending != null) fundPending(world, faction, pending);
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
                ScanResult result = scanCurrent(
                        world, faction, workerTypes, world.activeSystemId());
                livingWorkers += result.livingWorkers;
                pending = result.pending;
            } else {
                for (GalaxyMapSystem system : map.systems()) {
                    if (system == null || system.id() == null || system.id().isBlank()) continue;
                    world.activateSystem(system.id());
                    ScanResult result = scanCurrent(
                            world, faction, workerTypes, system.id());
                    livingWorkers += result.livingWorkers;
                    if (pending == null && result.pending != null) pending = result.pending;
                }
            }
        } finally {
            if (previousSystemId != null && !previousSystemId.isBlank()) {
                world.activateSystem(previousSystemId);
            }
            world.status = previousStatus;
        }
        return new WorkerSnapshot(livingWorkers, pending);
    }

    private static ScanResult scanCurrent(World world, NpcFaction faction,
                                          Set<String> workerTypes,
                                          String systemId) {
        int livingWorkers = 0;
        PendingWorkerJob pending = null;
        for (Unit unit : world.units.values()) {
            if (!faction.id().equals(unit.playerId) || unit.hp <= 0) continue;
            if (!unit.type().harvestKinds.isEmpty()
                    && (workerTypes.isEmpty()
                    || workerTypes.contains(unit.shipTypeId))) livingWorkers++;
        }
        for (Base base : world.bases.values()) {
            if (!faction.id().equals(base.playerId) || base.hp <= 0) continue;
            for (ProductionJob job : base.productionQueue) {
                if (job.kind != ProductionJobKind.SHIP
                        || !workerTypes.contains(job.itemId)) continue;
                pending = new PendingWorkerJob(systemId, base.id, job.id,
                        job.itemId, job.resourcesReserved);
                return new ScanResult(livingWorkers, pending);
            }
        }
        return new ScanResult(livingWorkers, pending);
    }

    private static Producer selectProducer(World world, NpcFaction faction,
                                           String workerTypeId,
                                           String homeSystemId) {
        String previousSystemId = world.activeSystemId();
        String previousStatus = world.status;
        List<Producer> candidates = new ArrayList<>();
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        try {
            if (map != null && map.systems() != null) {
                for (GalaxyMapSystem system : map.systems()) {
                    if (system == null || system.id() == null || system.id().isBlank()) continue;
                    world.activateSystem(system.id());
                    for (Base base : world.bases.values()) {
                        if (!validProducer(world, faction, base, workerTypeId)) continue;
                        candidates.add(new Producer(
                                system.id(),
                                base.id,
                                homeSystemId.equals(system.id()),
                                StationFuelRules.isOperational(base),
                                base.productionQueue.size()));
                    }
                }
            }
        } finally {
            if (previousSystemId != null && !previousSystemId.isBlank()) {
                world.activateSystem(previousSystemId);
            }
            world.status = previousStatus;
        }
        return candidates.stream()
                .min(Comparator
                        .comparing((Producer producer) -> !producer.home)
                        .thenComparing(producer -> !producer.operational)
                        .thenComparingInt(producer -> producer.queueSize)
                        .thenComparing(producer -> producer.systemId)
                        .thenComparing(producer -> producer.baseId))
                .orElse(null);
    }

    private static boolean validProducer(World world, NpcFaction faction,
                                         Base base, String workerTypeId) {
        return base != null
                && faction.id().equals(base.playerId)
                && base.hp > 0
                && base.type().buildableShips.contains(workerTypeId)
                && ResearchRules.shipUnlocked(world, faction.id(), workerTypeId);
    }

    private static void fundPending(World world, NpcFaction faction,
                                    PendingWorkerJob pending) {
        if (pending == null || pending.funded) return;
        String previousSystemId = world.activeSystemId();
        String previousStatus = world.status;
        try {
            world.activateSystem(pending.systemId);
            Base base = world.bases.get(pending.baseId);
            ProductionJob job = ProductionSystem.findJob(base, pending.jobId);
            if (base == null || job == null
                    || job.kind != ProductionJobKind.SHIP
                    || job.resourcesReserved
                    || !pending.workerTypeId.equals(job.itemId)) return;

            ShipType worker = Rules.ship(job.itemId);
            if (worker == null || !NpcResourceBudget.canAfford(
                    world, faction, NpcBudgetCategory.WORKER_RECOVERY,
                    worker.buildCost)) return;
            if (!NpcResourceBudget.spend(
                    world, faction, NpcBudgetCategory.WORKER_RECOVERY,
                    worker.buildCost)) return;

            job.resourcesReserved = true;
            job.blockedReason = "";
            AiDevLog.add(world, faction,
                    "funded worker recovery: " + worker.name + " at "
                            + pending.systemId + "/" + base.type().name);
            ProductionQueueScheduler.update(world, 0.0);
            world.saveActiveSystem();
        } finally {
            if (previousSystemId != null && !previousSystemId.isBlank()) {
                world.activateSystem(previousSystemId);
            }
            world.status = previousStatus;
        }
    }

    private static void cancelPending(World world, NpcFaction faction,
                                      PendingWorkerJob pending) {
        String previousSystemId = world.activeSystemId();
        String previousStatus = world.status;
        try {
            world.activateSystem(pending.systemId);
            Base base = world.bases.get(pending.baseId);
            ProductionJob job = ProductionSystem.findJob(base, pending.jobId);
            if (base == null || job == null
                    || job.kind != ProductionJobKind.SHIP
                    || !pending.workerTypeId.equals(job.itemId)) return;
            if (ProductionSystem.cancel(world, faction.id(), base.id, job.id)) {
                AiDevLog.add(world, faction,
                        "cancelled excess worker recovery: "
                                + Rules.ship(job.itemId).name);
            }
            world.saveActiveSystem();
        } finally {
            if (previousSystemId != null && !previousSystemId.isBlank()) {
                world.activateSystem(previousSystemId);
            }
            world.status = previousStatus;
        }
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
    private record PendingWorkerJob(String systemId, String baseId,
                                    String jobId, String workerTypeId,
                                    boolean funded) { }
    private record Producer(String systemId, String baseId,
                            boolean home, boolean operational,
                            int queueSize) { }
}
