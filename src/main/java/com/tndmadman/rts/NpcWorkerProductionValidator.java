package com.tndmadman.rts;

import java.util.List;
import java.util.Set;

public final class NpcWorkerProductionValidator {
    private static final double EPSILON = 0.001;

    private NpcWorkerProductionValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem NPC worker production validation passed.");
    }

    static void validateOrThrow() {
        validateQueuedWorkerRecovery();
        validateRequiresProductionStation();
        validateGalaxyWideWorkerCap();
    }

    private static void validateQueuedWorkerRecovery() {
        Fixture fixture = fixture("Queued Worker Recovery");
        require(!fixture.faction.replaceWorkers(),
                "Corsair legacy free-worker replacement is still enabled");
        clearFactionMaterials(fixture);

        int initialWorkers = workerCount(fixture.world, fixture.faction);
        require(initialWorkers == 2,
                "forced Corsair spawn did not begin with the configured two workers");

        runRecovery(fixture.world);
        require(workerCount(fixture.world, fixture.faction) == initialWorkers,
                "worker recovery created a ship immediately");
        ProductionJob job = pendingWorkerJob(fixture.world, fixture.faction);
        require(job != null, "worker shortage did not create a production demand");
        require(ProductionSystem.waitingForResources(job),
                "unfunded worker demand was not waiting for resources");

        for (int i = 0; i < 20; i++) runRecovery(fixture.world);
        require(pendingWorkerJobCount(fixture.world, fixture.faction) == 1,
                "repeated recovery updates duplicated the worker production request");
        require(workerCount(fixture.world, fixture.faction) == initialWorkers,
                "worker appeared while the production request was unfunded");

        ShipType worker = Rules.ship(job.itemId);
        addCosts(fixture.home, worker.buildCost);
        runRecovery(fixture.world);
        require(job.resourcesReserved,
                "worker-recovery budget did not fund the waiting production job");
        require(!ProductionSystem.waitingForResources(job),
                "funded worker job remained marked as waiting for resources");
        require(!canAfford(fixture.home, worker.buildCost),
                "worker build materials were not deducted when the job was funded");
        require(workerCount(fixture.world, fixture.faction) == initialWorkers,
                "funding the worker job bypassed production time");

        double beforeCompletion = Math.max(0.1, worker.buildTimeSeconds - 0.5);
        ProductionQueueScheduler.update(fixture.world, beforeCompletion);
        require(workerCount(fixture.world, fixture.faction) == initialWorkers,
                "worker completed before its configured build time");
        require(pendingWorkerJob(fixture.world, fixture.faction) != null,
                "worker job left the queue before build time elapsed");

        ProductionQueueScheduler.update(fixture.world, 0.6);
        require(workerCount(fixture.world, fixture.faction) == fixture.faction.maxWorkers(),
                "funded worker job did not produce the missing worker");
        require(pendingWorkerJob(fixture.world, fixture.faction) == null,
                "completed worker job remained in the production queue");

        for (int i = 0; i < 30; i++) {
            runRecovery(fixture.world);
            ProductionQueueScheduler.update(fixture.world, 1.0);
        }
        require(workerCount(fixture.world, fixture.faction) == fixture.faction.maxWorkers(),
                "worker recovery produced ships above the configured cap");
        require(pendingWorkerJobCount(fixture.world, fixture.faction) == 0,
                "worker recovery queued another job after reaching the cap");
    }

    private static void validateRequiresProductionStation() {
        Fixture fixture = fixture("Worker Station Requirement");
        clearFactionMaterials(fixture);
        fixture.world.bases.values().removeIf(base -> fixture.faction.id().equals(base.playerId));
        String labId = fixture.faction.id() + ":WORKER_LAB";
        Base laboratory = new Base(labId, fixture.faction.id(), "laboratory",
                fixture.world.width * 0.5, fixture.world.height * 0.5);
        laboratory.inventory.put(Material.FUEL, 20.0);
        fixture.world.bases.put(labId, laboratory);
        for (Material material : Material.values()) laboratory.inventory.put(material, 1000.0);

        runRecovery(fixture.world);
        require(pendingWorkerJob(fixture.world, fixture.faction) == null,
                "worker recovery queued at a station that cannot build the worker type");
        require(workerCount(fixture.world, fixture.faction) == 2,
                "worker recovery bypassed the station build-capability requirement");
    }

    private static void validateGalaxyWideWorkerCap() {
        Fixture fixture = fixture("Galaxy Worker Cap");
        clearFactionMaterials(fixture);
        String remoteSystem = "red_dwarf";
        fixture.world.activateSystem(remoteSystem);
        Unit remoteWorker = new Unit(fixture.faction.id(), 98_001, firstWorkerType(fixture.faction),
                fixture.world.width * 0.5, fixture.world.height * 0.5);
        fixture.world.units.put(remoteWorker.key(), remoteWorker);
        fixture.world.saveActiveSystem();
        fixture.world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);

        require(workerCount(fixture.world, fixture.faction) == fixture.faction.maxWorkers(),
                "remote worker was not included in the galaxy-wide worker count");
        runRecovery(fixture.world);
        require(pendingWorkerJob(fixture.world, fixture.faction) == null,
                "Corsair Den queued a replacement despite the remote worker satisfying the cap");
    }

    private static void runRecovery(World world) {
        NpcCollapseSystem.removeShipsWithoutStations(world);
    }

    private static Fixture fixture(String name) {
        PlayerRegistry.reset("WAIT", name, 0x50BEFF);
        World world = new World(name,
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.DEFAULT_SYSTEM_ID,
                false);
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        AiDevCommands.spawnCorsairs(world);
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        NpcFaction faction = corsairs();
        Base home = firstBase(world, faction.id());
        return new Fixture(world, faction, home);
    }

    private static NpcFaction corsairs() {
        for (NpcFaction faction : NpcRules.factions()) {
            if (Config.CORSAIRS_ID.equals(faction.id())) return faction;
        }
        throw new IllegalStateException("Corsair faction is not configured");
    }

    private static Base firstBase(World world, String factionId) {
        for (Base base : world.bases.values()) {
            if (factionId.equals(base.playerId) && base.hp > 0) return base;
        }
        throw new IllegalStateException("Corsair home station is missing");
    }

    private static int workerCount(World world, NpcFaction faction) {
        int count = 0;
        String previous = world.activeSystemId();
        String previousStatus = world.status;
        GalaxyMapSnapshot map = world.authoritativeGalaxyMapSnapshot();
        try {
            for (GalaxyMapSystem system : map.systems()) {
                world.activateSystem(system.id());
                for (Unit unit : world.units.values()) {
                    if (faction.id().equals(unit.playerId) && unit.hp > 0
                            && faction.workerTypeSet().contains(unit.shipTypeId)
                            && !unit.type().harvestKinds.isEmpty()) count++;
                }
            }
        } finally {
            world.activateSystem(previous);
            world.status = previousStatus;
        }
        return count;
    }

    private static ProductionJob pendingWorkerJob(World world, NpcFaction faction) {
        for (Base base : world.bases.values()) {
            if (!faction.id().equals(base.playerId) || base.hp <= 0) continue;
            for (ProductionJob job : base.productionQueue) {
                if (job.kind == ProductionJobKind.SHIP
                        && faction.workerTypeSet().contains(job.itemId)) return job;
            }
        }
        return null;
    }

    private static int pendingWorkerJobCount(World world, NpcFaction faction) {
        int count = 0;
        for (Base base : world.bases.values()) {
            if (!faction.id().equals(base.playerId) || base.hp <= 0) continue;
            for (ProductionJob job : base.productionQueue) {
                if (job.kind == ProductionJobKind.SHIP
                        && faction.workerTypeSet().contains(job.itemId)) count++;
            }
        }
        return count;
    }

    private static String firstWorkerType(NpcFaction faction) {
        for (String type : faction.workerUnitTypes()) {
            if (Rules.SHIPS.containsKey(type)) return type;
        }
        throw new IllegalStateException("Corsair worker type is not configured");
    }

    private static void clearFactionMaterials(Fixture fixture) {
        for (Base base : fixture.world.bases.values()) {
            if (fixture.faction.id().equals(base.playerId)) base.inventory.clear();
        }
    }

    private static void addCosts(Base base, List<Cost> costs) {
        for (Cost cost : costs) HangarStore.add(base.inventory, cost.material(), cost.amount());
    }

    private static boolean canAfford(Base base, List<Cost> costs) {
        for (Cost cost : costs) {
            if (base.inventory.getOrDefault(cost.material(), 0.0) + EPSILON < cost.amount()) return false;
        }
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Fixture(World world, NpcFaction faction, Base home) { }
}
