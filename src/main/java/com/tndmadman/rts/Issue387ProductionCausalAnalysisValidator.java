package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;

/** Regression coverage for issue #387 production causal analysis. */
public final class Issue387ProductionCausalAnalysisValidator {
    private Issue387ProductionCausalAnalysisValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem issue #387 production causal analysis validation passed.");
    }

    static void validateOrThrow() {
        validateMissingInputsAndNoMutation();
        validateInTransitRecognition();
        validateUnroutedRemoteSource();
    }

    private static void validateMissingInputsAndNoMutation() {
        World world = world("Issue 387 Missing Inputs");
        String playerId = "ISSUE_387_LOCAL";
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        ProductionJob job = waitingProspector(world, target);

        EnumMap<Material, Double> inventoryBefore = new EnumMap<>(target.inventory);
        List<String> queueBefore = queueIds(target);
        ProductionCausalAnalyzer.Analysis analysis = ProductionCausalAnalyzer.analyze(world, target, job);

        require(analysis.blocked(), "waiting production job was not reported blocked");
        require(hasCause(analysis.causes(), ProductionCausalAnalyzer.CauseType.MISSING_INPUTS),
                "missing material cause was not reported");
        require(inventoryBefore.equals(target.inventory), "analyzer mutated station inventory");
        require(queueBefore.equals(queueIds(target)), "analyzer mutated the production queue");
    }

    private static void validateInTransitRecognition() {
        World world = world("Issue 387 Transit");
        String playerId = "ISSUE_387_TRANSIT";
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        ProductionJob job = waitingProspector(world, target);
        Cost first = ProductionSystem.costFor(world, job).get(0);

        Unit shuttle = new Unit(playerId, 9001, LogisticsSystem.SHUTTLE_TYPE, 160, 100);
        shuttle.logisticsTargetBaseId = target.id;
        shuttle.addCargo(first.material(), Math.max(1, first.amount() * 0.5));
        world.units.put(shuttle.key(), shuttle);

        ProductionCausalAnalyzer.Analysis analysis = ProductionCausalAnalyzer.analyze(world, target, job);
        require(hasCause(analysis.causes(), ProductionCausalAnalyzer.CauseType.IN_TRANSIT),
                "in-transit material was not represented in the causal tree");
    }

    private static void validateUnroutedRemoteSource() {
        World world = world("Issue 387 No Route");
        String playerId = "ISSUE_387_ROUTE";
        String targetSystemId = world.activeSystemId();
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        ProductionJob job = waitingProspector(world, target);
        Cost first = ProductionSystem.costFor(world, job).get(0);

        String remoteSystemId = otherSystem(world, targetSystemId);
        require(!remoteSystemId.isBlank(), "validator could not find another galaxy system");
        world.activateSystem(remoteSystemId);
        Base remote = base(world, playerId + ":REMOTE", playerId, "shipyard", 320, 320);
        remote.inventory.put(first.material(), first.amount() * 2);
        world.activateSystem(targetSystemId);
        LogisticsRouteSystem.clear(world);

        ProductionCausalAnalyzer.Analysis analysis = ProductionCausalAnalyzer.analyze(world, target, job);
        require(hasCause(analysis.causes(), ProductionCausalAnalyzer.CauseType.NO_ROUTE),
                "owned remote stock without a route was not reported as NO_ROUTE");
    }

    private static ProductionJob waitingProspector(World world, Base target) {
        ShipType ship = Rules.ship("prospector");
        ShipLoadoutDefinition loadout = WeaponRules.defaultLoadout(ship.id);
        ProductionJob job = new ProductionJob("P387", ProductionJobKind.SHIP, ship.id,
                ship.buildTimeSeconds, ship.buildTimeSeconds, false, "");
        job.loadoutId = loadout.id();
        job.blockedReason = ProductionSystem.WAITING_FOR_RESOURCES;
        target.productionQueue.add(job);
        return job;
    }

    private static boolean hasCause(List<ProductionCausalAnalyzer.Cause> causes,
                                    ProductionCausalAnalyzer.CauseType type) {
        for (ProductionCausalAnalyzer.Cause cause : causes) {
            if (cause.type() == type || hasCause(cause.children(), type)) return true;
        }
        return false;
    }

    private static List<String> queueIds(Base base) {
        List<String> ids = new ArrayList<>();
        for (ProductionJob job : base.productionQueue) ids.add(job.id);
        return List.copyOf(ids);
    }

    private static String otherSystem(World world, String activeSystemId) {
        for (GalaxyMapSystem system : world.authoritativeGalaxyMapSnapshot().systems()) {
            if (system != null && system.id() != null && !system.id().isBlank()
                    && !system.id().equals(activeSystemId)) return system.id();
        }
        return "";
    }

    private static World world(String name) {
        PlayerRegistry.reset("SOLO", name, 0x50BEFF);
        return new World(name, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
    }

    private static Base base(World world, String id, String playerId,
                             String typeId, double x, double y) {
        Base base = new Base(id, playerId, typeId, x, y);
        world.bases.put(id, base);
        return base;
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new IllegalStateException("Issue #387 causal-analysis validation failed: " + message);
        }
    }
}
