package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        validateQueueBlocker();
        validateInTransitRecognition();
        validateReachableRemoteUsesDemandLogistics();
        validateMissingDepartureGate();
        validateInaccessibleRemoteSource();
        validateCourierConfigurationBlocker();
        validateUnauthorizedRedaction();
        validateStaleStateRedaction();
        validateForeignInventoryIsNotExposed();
        validateResearchLockedIntermediate();
        validateRecipeCycleBounded();
        validateDeepDependencyBounded();
    }

    private static void validateMissingInputsAndNoMutation() {
        World world = world("Issue 387 Missing Inputs");
        String playerId = "ISSUE_387_LOCAL";
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        ProductionJob job = waitingProspector(world, target);

        EnumMap<Material, Double> inventoryBefore = new EnumMap<>(target.inventory);
        List<String> queueBefore = queueIds(target);
        ProductionCausalAnalyzer.Analysis analysis = ProductionDiagnosticService.analyze(world, target, job);

        require(analysis.blocked(), "waiting production job was not reported blocked");
        require(hasCause(analysis.causes(), ProductionCausalAnalyzer.CauseType.MISSING_INPUTS),
                "missing material cause was not reported");
        require(inventoryBefore.equals(target.inventory), "diagnostics mutated station inventory");
        require(queueBefore.equals(queueIds(target)), "diagnostics mutated the production queue");
    }

    private static void validateQueueBlocker() {
        World world = world("Issue 387 Queue");
        String playerId = "ISSUE_387_QUEUE";
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        ProductionJob blocked = waitingProspector(world, target);
        ShipType ship = Rules.ship("prospector");
        ProductionJob earlier = new ProductionJob("P386", ProductionJobKind.SHIP, ship.id,
                ship.buildTimeSeconds, ship.buildTimeSeconds, true, "");
        earlier.loadoutId = WeaponRules.defaultLoadout(ship.id).id();
        target.productionQueue.add(0, earlier);

        ProductionCausalAnalyzer.Analysis analysis = ProductionDiagnosticService.analyze(world, target, blocked);
        require(hasCause(analysis.causes(), ProductionCausalAnalyzer.CauseType.QUEUE_WAIT),
                "queue position was not represented as a causal blocker");
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

        ProductionCausalAnalyzer.Analysis analysis = ProductionDiagnosticService.analyze(world, target, job);
        require(hasCause(analysis.causes(), ProductionCausalAnalyzer.CauseType.IN_TRANSIT),
                "in-transit material was not represented in the causal tree");
    }

    private static void validateReachableRemoteUsesDemandLogistics() {
        World world = world("Issue 387 Demand Logistics");
        String playerId = "ISSUE_387_ROUTE";
        String targetSystemId = world.activeSystemId();
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        ProductionJob job = waitingProspector(world, target);
        Cost first = ProductionSystem.costFor(world, job).get(0);

        String remoteSystemId = reachableOtherSystemWithGate(world, playerId, targetSystemId);
        require(!remoteSystemId.isBlank(), "validator could not find reachable remote stock with a departure gate");
        world.activateSystem(remoteSystemId);
        Base remote = base(world, playerId + ":DEMAND_SOURCE", playerId, "shipyard", 320, 320);
        remote.inventory.put(first.material(), first.amount() * 2);
        world.activateSystem(targetSystemId);

        ProductionCausalAnalyzer.Analysis analysis = ProductionDiagnosticService.analyze(world, target, job);
        require(!hasCause(analysis.causes(), ProductionCausalAnalyzer.CauseType.NO_ROUTE),
                "reachable production-demand stock was incorrectly blocked on a standing route");
        require(!hasCause(analysis.causes(), ProductionCausalAnalyzer.CauseType.NO_ELIGIBLE_TRANSPORT),
                "reachable production-demand stock incorrectly required an idle standing-route hauler");
        require(hasCause(analysis.causes(), ProductionCausalAnalyzer.CauseType.NO_LOCAL_SOURCE),
                "reachable remote production-demand stock was not represented in diagnostics");
        require(analysis.renderText().contains("no standing route or assigned hauler is required"),
                "diagnostics did not explain production-demand logistics authority");
    }

    private static void validateMissingDepartureGate() {
        World world = world("Issue 387 Missing Departure Gate");
        String playerId = "ISSUE_387_GATE";
        String targetSystemId = world.activeSystemId();
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        ProductionJob job = waitingProspector(world, target);
        Cost first = ProductionSystem.costFor(world, job).get(0);

        String remoteSystemId = reachableOtherSystemWithGate(world, playerId, targetSystemId);
        require(!remoteSystemId.isBlank(), "validator could not find a routed remote source for gate test");
        world.activateSystem(remoteSystemId);
        Base remote = base(world, playerId + ":GATE_SOURCE", playerId, "shipyard", 330, 330);
        remote.inventory.put(first.material(), first.amount() * 2);
        // Remove the departure gate from the authoritative source-system state, not only
        // the transient active-world list. The reciprocal destination gate remains, so
        // the logical topology is still reachable while dispatch from the source fails.
        for (WorldSystemState state : world.policySystemStates()) {
            if (state != null && remoteSystemId.equals(state.id)) {
                state.wormholes.clear();
                break;
            }
        }
        world.activateSystem(targetSystemId);

        ProductionCausalAnalyzer.Analysis analysis = ProductionDiagnosticService.analyze(world, target, job);
        require(hasCause(analysis.causes(), ProductionCausalAnalyzer.CauseType.NO_ROUTE),
                "missing production departure gate was not classified as NO_ROUTE");
        require(!hasAction(analysis.causes(), ProductionCausalAnalyzer.ActionType.CREATE_ROUTE),
                "production-demand gate failure incorrectly offered a standing-route mutation");
    }

    private static void validateInaccessibleRemoteSource() {
        World world = world("Issue 387 Inaccessible Topology");
        String playerId = "ISSUE_387_TOPOLOGY";
        String targetSystemId = world.activeSystemId();
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        ProductionJob job = waitingProspector(world, target);
        Cost first = ProductionSystem.costFor(world, job).get(0);

        String remoteSystemId = unreachableOtherSystem(world, playerId, targetSystemId);
        ProductionCausalAnalyzer.Analysis analysis;
        if (!remoteSystemId.isBlank()) {
            world.activateSystem(remoteSystemId);
            Base remote = base(world, playerId + ":ISOLATED_SOURCE", playerId, "shipyard", 320, 320);
            remote.inventory.put(first.material(), first.amount() * 2);
            world.activateSystem(targetSystemId);
            analysis = ProductionDiagnosticService.analyze(world, target, job);
        } else {
            ProductionCausalAnalyzer.Cause raw = new ProductionCausalAnalyzer.Cause(
                    ProductionCausalAnalyzer.CauseType.NO_ROUTE, "synthetic remote source", Map.of(
                    "material", first.material().name(),
                    "sourceSystemId", "ISSUE_387_ISOLATED_SYSTEM",
                    "sourceStationId", "ISSUE_387_ISOLATED_STATION",
                    "destSystemId", targetSystemId,
                    "destStationId", target.id,
                    "available", Double.toString(first.amount() * 2)), List.of(), List.of());
            analysis = ProductionDiagnosticService.normalizeForTest(world, target,
                    new ProductionCausalAnalyzer.Analysis(job.id, target.id, true,
                            "synthetic inaccessible source", List.of(raw)));
        }
        require(hasCause(analysis.causes(), ProductionCausalAnalyzer.CauseType.INACCESSIBLE_TOPOLOGY),
                "unreachable owned remote stock was not reported as inaccessible topology");
    }

    private static void validateCourierConfigurationBlocker() {
        World world = world("Issue 387 Courier Configuration");
        String playerId = "ISSUE_387_COURIER";
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        ProductionCausalAnalyzer.Cause raw = new ProductionCausalAnalyzer.Cause(
                ProductionCausalAnalyzer.CauseType.NO_COURIER,
                "raw courier configuration blocker",
                Map.of("playerId", playerId, "courierType", LogisticsSystem.SHUTTLE_TYPE),
                List.of(), List.of());
        ProductionCausalAnalyzer.Analysis analysis = ProductionDiagnosticService.normalizeForTest(
                world, target, new ProductionCausalAnalyzer.Analysis("P387", target.id, true,
                        "courier unavailable", List.of(raw)));
        require(hasCause(analysis.causes(), ProductionCausalAnalyzer.CauseType.NO_ELIGIBLE_TRANSPORT),
                "missing production courier hull was not classified as no eligible transport");
        require(!hasCause(analysis.causes(), ProductionCausalAnalyzer.CauseType.NO_COURIER),
                "internal courier classification leaked through canonical diagnostics");
    }

    private static void validateUnauthorizedRedaction() {
        World world = world("Issue 387 Unauthorized");
        String owner = "ISSUE_387_OWNER";
        String viewer = "ISSUE_387_VIEWER";
        Base target = base(world, owner + ":SECRET_STATION", owner, "shipyard", 100, 100);
        ProductionJob job = waitingProspector(world, target);
        Cost first = ProductionSystem.costFor(world, job).get(0);
        target.inventory.put(first.material(), 12_345.0);

        ProductionCausalAnalyzer.Analysis analysis = ProductionDiagnosticService.analyzeForPlayer(
                world, target, job, viewer);
        require(hasCause(analysis.causes(), ProductionCausalAnalyzer.CauseType.UNAUTHORIZED),
                "foreign production diagnostics were not owner-gated");
        require(!analysis.renderText().contains("12,345") && !analysis.renderText().contains("12345"),
                "unauthorized diagnostics exposed foreign inventory values");
    }

    private static void validateStaleStateRedaction() {
        World world = world("Issue 387 Stale State");
        String playerId = "ISSUE_387_STALE";
        Base oldTarget = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        ProductionJob oldJob = waitingProspector(world, oldTarget);
        Base replacement = new Base(oldTarget.id, playerId, "shipyard", 110, 110);
        world.bases.put(replacement.id, replacement);

        ProductionCausalAnalyzer.Analysis analysis = ProductionDiagnosticService.analyzeForPlayer(
                world, oldTarget, oldJob, playerId);
        require(hasCause(analysis.causes(), ProductionCausalAnalyzer.CauseType.STALE_STATE),
                "stale client production objects were not rejected before analysis");
    }

    private static void validateForeignInventoryIsNotExposed() {
        World world = world("Issue 387 Fog Boundary");
        String playerId = "ISSUE_387_FOW";
        String enemyId = "ISSUE_387_ENEMY";
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        ProductionJob job = waitingProspector(world, target);
        Cost first = ProductionSystem.costFor(world, job).get(0);
        Base enemy = base(world, "TOP_SECRET_ENEMY_DEPOT", enemyId, "shipyard", 140, 140);
        enemy.inventory.put(first.material(), first.amount() * 100);

        ProductionCausalAnalyzer.Analysis analysis = ProductionDiagnosticService.analyzeForPlayer(
                world, target, job, playerId);
        require(!analysis.renderText().contains(enemy.id),
                "owner diagnostics leaked a foreign station identifier");
        require(hasCause(analysis.causes(), ProductionCausalAnalyzer.CauseType.NO_LOCAL_SOURCE),
                "foreign stock was incorrectly treated as player-owned supply");
    }

    private static void validateResearchLockedIntermediate() {
        World world = world("Issue 387 Research Chain");
        String playerId = "ISSUE_387_RESEARCH";
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        Material[] materials = Material.values();
        require(materials.length >= 2, "material catalog is too small for synthetic research-chain validation");
        Material output = materials[0];
        Material input = materials[1];
        CraftableItem recipe = recipe("issue387_research", target, output,
                List.of(new Cost(input, 1)), List.of("ISSUE_387_REQUIRED_RESEARCH"));
        Map<Material,List<CraftableItem>> recipes = Map.of(output, List.of(recipe));

        ProductionCausalAnalyzer.Cause cause = ProductionCausalAnalyzer.analyzeMaterialForTest(
                world, target, output, 1, recipes);
        require(hasCause(List.of(cause), ProductionCausalAnalyzer.CauseType.RESEARCH_LOCKED),
                "research-locked intermediate was not represented in the dependency tree");
        require(!hasAction(List.of(cause), ProductionCausalAnalyzer.ActionType.ENQUEUE_INTERMEDIATE),
                "research-locked intermediate incorrectly exposed a direct enqueue recovery action");
    }

    private static void validateRecipeCycleBounded() {
        World world = world("Issue 387 Cycle");
        String playerId = "ISSUE_387_CYCLE";
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        Material[] materials = Material.values();
        require(materials.length >= 2, "material catalog is too small for cycle validation");
        Material a = materials[0];
        Material b = materials[1];
        Map<Material,List<CraftableItem>> recipes = new LinkedHashMap<>();
        recipes.put(a, List.of(recipe("issue387_cycle_a", target, a,
                List.of(new Cost(b, 1)), List.of())));
        recipes.put(b, List.of(recipe("issue387_cycle_b", target, b,
                List.of(new Cost(a, 1)), List.of())));

        ProductionCausalAnalyzer.Cause cause = ProductionCausalAnalyzer.analyzeMaterialForTest(
                world, target, a, 1, recipes);
        require(hasCause(List.of(cause), ProductionCausalAnalyzer.CauseType.RECIPE_CYCLE),
                "recipe cycle was not detected");
        require(maxDepth(List.of(cause)) <= ProductionCausalAnalyzer.dependencyDepthLimitForTest() + 4,
                "cycle analysis exceeded the bounded causal depth");
    }

    private static void validateDeepDependencyBounded() {
        World world = world("Issue 387 Deep Chain");
        String playerId = "ISSUE_387_DEPTH";
        Base target = base(world, playerId + ":B1", playerId, "shipyard", 100, 100);
        Material[] materials = Material.values();
        int limit = ProductionCausalAnalyzer.dependencyDepthLimitForTest();
        require(materials.length >= limit + 2,
                "material catalog is too small to exercise the mandatory deep-chain depth bound");

        Map<Material,List<CraftableItem>> recipes = new LinkedHashMap<>();
        for (int i = 0; i < limit + 1; i++) {
            recipes.put(materials[i], List.of(recipe("issue387_depth_" + i, target, materials[i],
                    List.of(new Cost(materials[i + 1], 1)), List.of())));
        }
        ProductionCausalAnalyzer.Cause cause = ProductionCausalAnalyzer.analyzeMaterialForTest(
                world, target, materials[0], 1, recipes);
        require(hasCause(List.of(cause), ProductionCausalAnalyzer.CauseType.DEPTH_LIMIT),
                "deep recipe chain did not terminate with a depth-limit cause");
        require(maxDepth(List.of(cause)) <= limit + 4,
                "deep dependency analysis exceeded its bounded tree depth");
    }

    private static CraftableItem recipe(String id, Base station, Material output,
                                        List<Cost> inputs, List<String> research) {
        return new CraftableItem(id, id, "Synthetic issue #387 validator recipe", "validator", "#FFFFFF",
                CraftingCategory.MATERIALS, List.of(station.typeId), research, inputs, output, 1, 1);
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

    private static boolean hasAction(List<ProductionCausalAnalyzer.Cause> causes,
                                     ProductionCausalAnalyzer.ActionType type) {
        for (ProductionCausalAnalyzer.Cause cause : causes) {
            for (ProductionCausalAnalyzer.RecoveryAction action : cause.actions()) {
                if (action.type() == type) return true;
            }
            if (hasAction(cause.children(), type)) return true;
        }
        return false;
    }

    private static int maxDepth(List<ProductionCausalAnalyzer.Cause> causes) {
        int max = 0;
        for (ProductionCausalAnalyzer.Cause cause : causes) {
            max = Math.max(max, 1 + maxDepth(cause.children()));
        }
        return max;
    }

    private static List<String> queueIds(Base base) {
        List<String> ids = new ArrayList<>();
        for (ProductionJob job : base.productionQueue) ids.add(job.id);
        return List.copyOf(ids);
    }

    private static String reachableOtherSystemWithGate(World world, String playerId, String activeSystemId) {
        String original = world.activeSystemId();
        try {
            for (GalaxyMapSystem system : world.authoritativeGalaxyMapSnapshot().systems()) {
                if (system == null || system.id() == null || system.id().isBlank()
                        || system.id().equals(activeSystemId)) continue;
                List<String> path = LogisticsRouteSystem.pathForTest(world, playerId, system.id(), activeSystemId);
                if (path.size() < 2) continue;
                world.activateSystem(system.id());
                String nextHop = path.get(1);
                for (WormholeGate gate : world.wormholes) {
                    if (gate != null && nextHop.equals(gate.remoteSystemId)) return system.id();
                }
                world.activateSystem(activeSystemId);
            }
            return "";
        } finally {
            if (!world.activeSystemId().equals(original)) world.activateSystem(original);
        }
    }

    private static String unreachableOtherSystem(World world, String playerId, String activeSystemId) {
        for (GalaxyMapSystem system : world.authoritativeGalaxyMapSnapshot().systems()) {
            if (system == null || system.id() == null || system.id().isBlank()
                    || system.id().equals(activeSystemId)) continue;
            if (LogisticsRouteSystem.pathForTest(world, playerId, system.id(), activeSystemId).size() < 2) {
                return system.id();
            }
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
