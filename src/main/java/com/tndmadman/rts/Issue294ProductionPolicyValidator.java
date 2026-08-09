package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Regression coverage for issue #294 standing production policies and templates. */
public final class Issue294ProductionPolicyValidator {
    private static final String PLAYER = "POLICY_TEST";

    private Issue294ProductionPolicyValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem issue #294 production policy validation passed.");
    }

    static void validateOrThrow() {
        validateMaintainStockAndNoDuplicateJobs();
        validateFleetReplacementAndGalaxyWideCounting();
        validateReserveProtectionAndManualCancellation();
        validateGalaxyWideNetworkReserveAndCompetingPolicies();
        validateBlockedResearchAndPolicyManagement();
        validateRepeatAndStarterTemplates();
        validateTemplatesAndStatusReplication();
        validatePolicyPersistenceAndJobProvenance();
        validatePlannerRootAndLogisticsSupplyNoDuplication();
        validateLogisticsRequestPersistence();
        validateMalformedAndBoundedCommands();
        validateLargePolicyPerformanceAndBounds();
        Issue294ProductionPolicyRecoveryValidator.validateOrThrow();
    }

    private static void validateMaintainStockAndNoDuplicateJobs() {
        World world = world("stock");
        Base plant = base(world, PLAYER + ":M1", PLAYER, "manufacturing", 400, 400);
        fill(plant);
        plant.inventory.remove(Material.FUEL);
        String spec = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK,
                ProductionJobKind.CRAFTABLE, "fuel", "", 100, 2, 80, 2, 0,
                Map.of(), Map.of());
        require(ProductionCommands.apply(world, PLAYER, "POLICY", plant.id,
                ProductionPolicySystem.COMMAND_CREATE, spec), "maintain-stock policy creation failed");

        ProductionPolicySystem.update(world, 0.6);
        require(plant.productionQueue.size() == 2,
                "100 Fuel target should queue exactly two 50-unit Fuel batches");
        require(ProductionPolicySystem.viewsForBase(world, plant).get(0).jobIds().size() == 2,
                "policy did not own both visible queue jobs");

        ProductionPolicySystem.update(world, 0.6);
        require(plant.productionQueue.size() == 2,
                "repeated evaluation duplicated jobs already represented by queued final output");

        ProductionQueueScheduler.update(world, Double.MAX_VALUE);
        require(plant.productionQueue.isEmpty(), "policy Fuel jobs did not complete");
        require(plant.inventory.getOrDefault(Material.FUEL, 0.0) >= 99.9,
                "completed policy output did not reach target stock");
        ProductionPolicySystem.update(world, 0.6);
        require(plant.productionQueue.isEmpty(), "satisfied stock target kept producing");
        require(ProductionPolicySystem.viewsForBase(world, plant).get(0).status()
                        == ProductionPolicySystem.PolicyStatus.SATISFIED,
                "satisfied stock policy did not report SATISFIED");

        plant.inventory.put(Material.FUEL, 20.0);
        ProductionPolicySystem.update(world, 0.6);
        require(plant.productionQueue.size() == 2,
                "consuming stock below target did not queue replacement batches");
    }

    private static void validateFleetReplacementAndGalaxyWideCounting() {
        World world = world("fleet");
        Base yard = base(world, PLAYER + ":Y1", PLAYER, "shipyard", 450, 450);
        fill(yard);

        String remoteSystem = firstOtherSystem(world);
        require(!remoteSystem.isBlank(), "fleet validator requires a second galaxy system");
        String home = world.activeSystemId();
        world.activateSystem(remoteSystem);
        Unit remoteProspector = new Unit(PLAYER, 7001, "prospector", 500, 500);
        world.units.put(remoteProspector.key(), remoteProspector);
        world.saveActiveSystem();
        world.activateSystem(home);

        String spec = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_FLEET,
                ProductionJobKind.SHIP, "prospector", WeaponRules.defaultLoadoutId("prospector"),
                1, 1, 80, 2, 0, Map.of(), Map.of());
        require(ProductionCommands.apply(world, PLAYER, "POLICY", yard.id,
                ProductionPolicySystem.COMMAND_CREATE, spec), "maintain-fleet policy creation failed");
        ProductionPolicySystem.update(world, 0.6);
        require(yard.productionQueue.isEmpty(),
                "fleet policy ignored a living matching hull in another star system");

        world.activateSystem(remoteSystem);
        world.units.remove(remoteProspector.key());
        world.saveActiveSystem();
        world.activateSystem(home);
        ProductionPolicySystem.update(world, 0.6);
        require(yard.productionQueue.size() == 1,
                "destroying the only fleet member did not queue one replacement");
        require(ProductionPolicySystem.jobLabel(world, yard, yard.productionQueue.get(0).id).startsWith("AUTO PP"),
                "fleet replacement queue job was not visibly policy-owned");
    }

    private static void validateReserveProtectionAndManualCancellation() {
        World reserveWorld = world("reserve");
        Base plant = base(reserveWorld, PLAYER + ":M1", PLAYER, "manufacturing", 400, 400);
        fill(plant);
        plant.inventory.remove(Material.FUEL);
        CraftableItem fuel = CraftingRules.item("fuel");
        require(fuel != null, "Fuel recipe missing");
        double hydrogenCost = cost(fuel.requiredResources, Material.HYDROGEN);
        plant.inventory.put(Material.HYDROGEN, hydrogenCost + 20);
        EnumMap<Material,Double> stationReserve = new EnumMap<>(Material.class);
        stationReserve.put(Material.HYDROGEN, 25.0);
        String reserveSpec = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK,
                ProductionJobKind.CRAFTABLE, "fuel", "", 50, 1, 90, 1, 0,
                stationReserve, Map.of());
        require(ProductionCommands.apply(reserveWorld, PLAYER, "POLICY", plant.id,
                ProductionPolicySystem.COMMAND_CREATE, reserveSpec), "reserve policy creation failed");
        ProductionPolicySystem.update(reserveWorld, 0.6);
        require(plant.productionQueue.isEmpty(), "policy spent through its station reserve floor");
        require(ProductionPolicySystem.viewsForBase(reserveWorld, plant).get(0).status()
                        == ProductionPolicySystem.PolicyStatus.RESERVE_PROTECTED,
                "reserve-blocked policy did not report RESERVE_PROTECTED");

        World cancelWorld = world("cancel");
        Base cancelPlant = base(cancelWorld, PLAYER + ":M1", PLAYER, "manufacturing", 400, 400);
        fill(cancelPlant);
        cancelPlant.inventory.remove(Material.FUEL);
        String spec = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK,
                ProductionJobKind.CRAFTABLE, "fuel", "", 50, 1, 60, 1, 0,
                Map.of(), Map.of());
        require(ProductionCommands.apply(cancelWorld, PLAYER, "POLICY", cancelPlant.id,
                ProductionPolicySystem.COMMAND_CREATE, spec), "cancellation policy creation failed");
        ProductionPolicySystem.update(cancelWorld, 0.6);
        require(cancelPlant.productionQueue.size() == 1, "policy did not create cancellable automatic work");
        String jobId = cancelPlant.productionQueue.get(0).id;
        require(ProductionSystem.cancel(cancelWorld, PLAYER, cancelPlant.id, jobId),
                "manual automatic-job cancellation failed");
        ProductionPolicySystem.PolicyView paused = ProductionPolicySystem.viewsForBase(cancelWorld, cancelPlant).get(0);
        require(!paused.enabled() && paused.status() == ProductionPolicySystem.PolicyStatus.PAUSED,
                "manual cancellation did not pause owning policy");
        ProductionPolicySystem.update(cancelWorld, 1.0);
        require(cancelPlant.productionQueue.isEmpty(),
                "manually cancelled policy immediately recreated its job");
    }

    private static void validateGalaxyWideNetworkReserveAndCompetingPolicies() {
        CraftableItem fuel = CraftingRules.item("fuel");
        require(fuel != null, "Fuel recipe missing for network reserve validator");
        double hydrogenCost = cost(fuel.requiredResources, Material.HYDROGEN);

        World galaxyWorld = world("galaxy-reserve");
        Base local = base(galaxyWorld, PLAYER + ":M1", PLAYER, "manufacturing", 300, 300);
        setRecipeInputs(local, fuel);
        String home = galaxyWorld.activeSystemId();
        String remoteSystem = firstOtherSystem(galaxyWorld);
        require(!remoteSystem.isBlank(), "network reserve validator requires a remote galaxy system");
        galaxyWorld.activateSystem(remoteSystem);
        Base remote = base(galaxyWorld, PLAYER + ":REMOTE", PLAYER, "manufacturing", 500, 500);
        remote.inventory.put(Material.HYDROGEN, 30.0);
        galaxyWorld.saveActiveSystem();
        galaxyWorld.activateSystem(home);

        EnumMap<Material,Double> networkReserve = new EnumMap<>(Material.class);
        networkReserve.put(Material.HYDROGEN, 20.0);
        String galaxySpec = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK, ProductionJobKind.CRAFTABLE,
                "fuel", "", 50, 1, 90, 1, 0, Map.of(), networkReserve);
        require(ProductionCommands.apply(galaxyWorld, PLAYER, "POLICY", local.id,
                ProductionPolicySystem.COMMAND_CREATE, galaxySpec), "galaxy reserve policy creation failed");
        ProductionPolicySystem.update(galaxyWorld, 0.6);
        require(local.productionQueue.size() == 1,
                "network reserve ignored inventory held at an owned station in another system");
        require(hydrogenCost > 0, "Fuel recipe unexpectedly has no hydrogen cost");

        World competing = world("competing-reserve");
        Base first = base(competing, PLAYER + ":M1", PLAYER, "manufacturing", 250, 300);
        Base second = base(competing, PLAYER + ":M2", PLAYER, "manufacturing", 750, 300);
        setRecipeInputs(first, fuel);
        setRecipeInputs(second, fuel);
        EnumMap<Material,Double> floor = new EnumMap<>(Material.class);
        floor.put(Material.HYDROGEN, 25.0);
        String firstSpec = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK, ProductionJobKind.CRAFTABLE,
                "fuel", "", 50, 1, 90, 1, 0, Map.of(), floor);
        String secondSpec = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK, ProductionJobKind.CRAFTABLE,
                "fuel", "", 50, 1, 10, 1, 0, Map.of(), floor);
        require(ProductionCommands.apply(competing, PLAYER, "POLICY", first.id,
                ProductionPolicySystem.COMMAND_CREATE, firstSpec), "first competing policy creation failed");
        require(ProductionCommands.apply(competing, PLAYER, "POLICY", second.id,
                ProductionPolicySystem.COMMAND_CREATE, secondSpec), "second competing policy creation failed");
        ProductionPolicySystem.update(competing, 0.6);
        require(first.productionQueue.size() == 1,
                "higher-priority competing policy did not receive the available reserve headroom");
        require(second.productionQueue.isEmpty(),
                "lower-priority competing policy spent through shared network reserve in the same evaluation");
        require(ProductionPolicySystem.viewsForBase(competing, second).get(0).status()
                        == ProductionPolicySystem.PolicyStatus.RESERVE_PROTECTED,
                "lower-priority competing policy did not report reserve protection");
    }

    private static void validateBlockedResearchAndPolicyManagement() {
        World researchWorld = world("research-block");
        Base researchPlant = base(researchWorld, PLAYER + ":M1", PLAYER, "manufacturing", 400, 400);
        fill(researchPlant);
        researchPlant.inventory.remove(Material.TITANIUM_ALLOY);
        String locked = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK, ProductionJobKind.CRAFTABLE,
                "titanium_alloy", "", 12, 1, 70, 1, 0, Map.of(), Map.of());
        require(ProductionCommands.apply(researchWorld, PLAYER, "POLICY", researchPlant.id,
                ProductionPolicySystem.COMMAND_CREATE, locked), "blocked-research policy creation failed");
        ProductionPolicySystem.update(researchWorld, 0.6);
        ProductionPolicySystem.PolicyView blocked = ProductionPolicySystem.viewsForBase(researchWorld, researchPlant).get(0);
        require(blocked.status() == ProductionPolicySystem.PolicyStatus.BLOCKED_RESEARCH,
                "locked craftable policy did not report BLOCKED_RESEARCH");
        require(researchPlant.productionQueue.isEmpty(), "blocked-research policy queued production anyway");

        World management = world("management");
        Base plant = base(management, PLAYER + ":M1", PLAYER, "manufacturing", 400, 400);
        fill(plant);
        plant.inventory.remove(Material.FUEL);
        plant.inventory.remove(Material.STEEL_PLATE);
        String fuelSpec = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK, ProductionJobKind.CRAFTABLE,
                "fuel", "", 50, 1, 20, 1, 0, Map.of(), Map.of());
        String steelSpec = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK, ProductionJobKind.CRAFTABLE,
                "steel_plate", "", 20, 1, 80, 1, 0, Map.of(), Map.of());
        require(ProductionCommands.apply(management, PLAYER, "POLICY", plant.id,
                ProductionPolicySystem.COMMAND_CREATE, fuelSpec), "fuel management policy creation failed");
        require(ProductionCommands.apply(management, PLAYER, "POLICY", plant.id,
                ProductionPolicySystem.COMMAND_CREATE, steelSpec), "steel management policy creation failed");
        List<ProductionPolicySystem.PolicyView> initial = ProductionPolicySystem.viewsForBase(management, plant);
        ProductionPolicySystem.PolicyView fuelPolicy = findPolicy(initial, "fuel");
        ProductionPolicySystem.PolicyView steelPolicy = findPolicy(initial, "steel_plate");
        require(fuelPolicy != null && steelPolicy != null && initial.get(0).id().equals(steelPolicy.id()),
                "policy priority ordering is not deterministic");
        require(ProductionCommands.apply(management, PLAYER, "POLICY", plant.id,
                ProductionPolicySystem.COMMAND_MOVE_UP, fuelPolicy.id()), "policy move-up failed");
        require(ProductionPolicySystem.viewsForBase(management, plant).get(0).id().equals(fuelPolicy.id()),
                "policy move-up did not reorder evaluation priority");
        require(ProductionCommands.apply(management, PLAYER, "POLICY", plant.id,
                ProductionPolicySystem.COMMAND_TOGGLE, fuelPolicy.id() + "~0"), "policy pause failed");
        require(!findPolicy(ProductionPolicySystem.viewsForBase(management, plant), "fuel").enabled(),
                "policy pause state was not retained");
        require(ProductionCommands.apply(management, PLAYER, "POLICY", plant.id,
                ProductionPolicySystem.COMMAND_TOGGLE, fuelPolicy.id() + "~1"), "policy resume failed");

        ProductionPolicySystem.update(management, 0.6);
        ProductionPolicySystem.PolicyView activeFuel = findPolicy(ProductionPolicySystem.viewsForBase(management, plant), "fuel");
        require(activeFuel != null && !activeFuel.jobIds().isEmpty(), "fuel policy did not create edit-detach fixture job");
        String oldJobId = activeFuel.jobIds().get(0);
        String edit = ProductionPolicyWire.encodeSpec(activeFuel.id(),
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK, ProductionJobKind.CRAFTABLE,
                "copper_wiring", "", 25, 1, activeFuel.priority(), 1, 0, Map.of(), Map.of());
        require(ProductionCommands.apply(management, PLAYER, "POLICY", plant.id,
                ProductionPolicySystem.COMMAND_UPDATE, edit), "policy definition edit failed");
        require(ProductionSystem.findJob(plant, oldJobId) != null,
                "editing a policy silently deleted its existing queue job");
        require(ProductionPolicySystem.jobLabel(management, plant, oldJobId).isBlank(),
                "edited policy incorrectly retained provenance on old-definition queue work");
        require(findPolicy(ProductionPolicySystem.viewsForBase(management, plant), "copper_wiring") != null,
                "policy edit did not replace the configured output");

        require(ProductionCommands.apply(management, PLAYER, "POLICY", plant.id,
                ProductionPolicySystem.COMMAND_DELETE, steelPolicy.id()), "policy deletion failed");
        require(findPolicy(ProductionPolicySystem.viewsForBase(management, plant), "steel_plate") == null,
                "deleted policy remained visible");
    }

    private static void validateRepeatAndStarterTemplates() {
        World repeatWorld = world("repeat");
        Base plant = base(repeatWorld, PLAYER + ":M1", PLAYER, "manufacturing", 400, 400);
        fill(plant);
        String repeatSpec = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.REPEAT, ProductionJobKind.CRAFTABLE,
                "fuel", "", 0, 1, 60, 1, 2, Map.of(), Map.of());
        require(ProductionCommands.apply(repeatWorld, PLAYER, "POLICY", plant.id,
                ProductionPolicySystem.COMMAND_CREATE, repeatSpec), "repeat policy creation failed");
        ProductionPolicySystem.update(repeatWorld, 0.6);
        require(plant.productionQueue.size() == 1, "repeat policy did not queue its first batch");
        ProductionSystem.update(repeatWorld, 1000);
        ProductionPolicySystem.update(repeatWorld, 0.6);
        require(plant.productionQueue.size() == 1,
                "repeat policy did not queue the next batch after completion");
        ProductionSystem.update(repeatWorld, 1000);
        ProductionPolicySystem.update(repeatWorld, 0.6);
        ProductionPolicySystem.PolicyView repeat = ProductionPolicySystem.viewsForBase(repeatWorld, plant).get(0);
        require(plant.productionQueue.isEmpty() && repeat.completedBatches() == 2
                        && repeat.status() == ProductionPolicySystem.PolicyStatus.SATISFIED,
                "repeat policy did not stop at its configured maximum batch count");

        World starterWorld = world("starter");
        Base manufacturing = base(starterWorld, PLAYER + ":M1", PLAYER, "manufacturing", 300, 300);
        fill(manufacturing);
        List<ProductionPolicyStarterTemplates.StarterView> starterViews =
                ProductionPolicyStarterTemplates.viewsFor(manufacturing);
        require(starterViews.stream().anyMatch(v -> v.id().equals(ProductionPolicyStarterTemplates.BASIC_FUEL_COMPONENTS)),
                "manufacturing station did not expose Basic Fuel & Components starter template");
        require(starterViews.stream().anyMatch(v -> v.id().equals(ProductionPolicyStarterTemplates.MANUFACTURING_INPUTS)),
                "manufacturing station did not expose Manufacturing Inputs starter template");
        require(ProductionCommands.apply(starterWorld, PLAYER, "POLICY", manufacturing.id,
                        ProductionPolicyStarterTemplates.COMMAND_APPLY,
                        ProductionPolicyStarterTemplates.BASIC_FUEL_COMPONENTS),
                "basic starter template application failed");
        List<ProductionPolicySystem.PolicyView> firstApply = ProductionPolicySystem.viewsForBase(starterWorld, manufacturing);
        require(firstApply.size() == 4, "basic starter template did not create its four independent policies");
        Set<String> firstIds = new HashSet<>();
        for (ProductionPolicySystem.PolicyView view : firstApply) firstIds.add(view.id());
        require(ProductionCommands.apply(starterWorld, PLAYER, "POLICY", manufacturing.id,
                        ProductionPolicyStarterTemplates.COMMAND_APPLY,
                        ProductionPolicyStarterTemplates.BASIC_FUEL_COMPONENTS),
                "second starter template application failed");
        List<ProductionPolicySystem.PolicyView> secondApply = ProductionPolicySystem.viewsForBase(starterWorld, manufacturing);
        require(secondApply.size() == 8, "reapplying starter template did not create a second independent policy set");
        int newIds = 0;
        for (ProductionPolicySystem.PolicyView view : secondApply) if (!firstIds.contains(view.id())) newIds++;
        require(newIds == 4, "starter template reapplication reused live policy IDs");

        Base yard = base(starterWorld, PLAYER + ":Y1", PLAYER, "shipyard", 700, 300);
        fill(yard);
        List<ProductionPolicyStarterTemplates.StarterView> yardStarters = ProductionPolicyStarterTemplates.viewsFor(yard);
        require(yardStarters.stream().anyMatch(v -> v.id().equals(ProductionPolicyStarterTemplates.MINING_REPLACEMENT)),
                "shipyard did not expose Mining Replacement starter template");
        require(yardStarters.stream().anyMatch(v -> v.id().equals(ProductionPolicyStarterTemplates.COMBAT_REPLACEMENT)),
                "shipyard did not expose Combat Replacement starter template");
    }

    private static void validateTemplatesAndStatusReplication() {
        World world = world("template");
        Base first = base(world, PLAYER + ":M1", PLAYER, "manufacturing", 300, 300);
        Base second = base(world, PLAYER + ":M2", PLAYER, "manufacturing", 700, 300);
        fill(first);
        fill(second);
        String spec = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK,
                ProductionJobKind.CRAFTABLE, "fuel", "", 150, 2, 75, 2, 0,
                Map.of(), Map.of());
        require(ProductionCommands.apply(world, PLAYER, "POLICY", first.id,
                ProductionPolicySystem.COMMAND_CREATE, spec), "template source policy creation failed");
        require(ProductionCommands.apply(world, PLAYER, "POLICY", first.id,
                ProductionPolicySystem.COMMAND_TEMPLATE_SAVE, "Basic Fuel"), "template save failed");
        List<ProductionPolicySystem.TemplateView> templates = ProductionPolicySystem.templateViews(world, first);
        require(templates.size() == 1 && templates.get(0).entryCount() == 1,
                "saved template did not contain source policy");
        require(ProductionCommands.apply(world, PLAYER, "POLICY", second.id,
                ProductionPolicySystem.COMMAND_TEMPLATE_APPLY, templates.get(0).id()), "template application failed");
        List<ProductionPolicySystem.PolicyView> firstPolicies = ProductionPolicySystem.viewsForBase(world, first);
        List<ProductionPolicySystem.PolicyView> secondPolicies = ProductionPolicySystem.viewsForBase(world, second);
        require(firstPolicies.size() == 1 && secondPolicies.size() == 1,
                "template did not create exactly one destination policy");
        require(!firstPolicies.get(0).id().equals(secondPolicies.get(0).id()),
                "template application live-linked or reused the source policy ID");

        ProductionPolicySystem.refreshCurrentSystem(world);
        Base networkCopy = NetBaseSync.fromState(NetBaseSync.toState(first));
        List<ProductionPolicySystem.PolicyView> replicated = ProductionPolicySystem.viewsForBase(null, networkCopy);
        require(replicated.size() == 1 && replicated.get(0).id().equals(firstPolicies.get(0).id()),
                "compact multiplayer station state lost policy view");
        List<ProductionPolicySystem.TemplateView> replicatedTemplates = ProductionPolicySystem.templateViews(null, networkCopy);
        require(replicatedTemplates.size() == 1 && replicatedTemplates.get(0).name().equals("Basic Fuel"),
                "compact multiplayer station state lost template view");

        Map<String,Object> saved = ProductionPlanner.capture(world);
        ProductionPolicySystem.clear(world);
        ProductionPlanner.restore(world, saved);
        require(ProductionPolicySystem.templateViews(world, first).stream()
                        .anyMatch(v -> v.name().equals("Basic Fuel")),
                "saved custom template did not survive runtime save/restore");
    }

    private static void validatePolicyPersistenceAndJobProvenance() {
        World world = world("persist");
        Base plant = base(world, PLAYER + ":M1", PLAYER, "manufacturing", 400, 400);
        fill(plant);
        String spec = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.REPEAT,
                ProductionJobKind.CRAFTABLE, "fuel", "", 0, 1, 50, 1, 3,
                Map.of(), Map.of());
        require(ProductionCommands.apply(world, PLAYER, "POLICY", plant.id,
                ProductionPolicySystem.COMMAND_CREATE, spec), "repeat policy creation failed");
        ProductionPolicySystem.update(world, 0.6);
        require(plant.productionQueue.size() == 1, "repeat policy did not queue initial job");
        String jobId = plant.productionQueue.get(0).id;
        String label = ProductionPolicySystem.jobLabel(world, plant, jobId);
        require(label.startsWith("AUTO PP"), "policy job provenance missing before persistence");

        Map<String,Object> savedPlanner = ProductionPlanner.capture(world);
        ProductionPolicySystem.clear(world);
        world.logisticsSystem.restore(world, Map.of());
        ProductionPlanner.restore(world, savedPlanner);
        List<ProductionPolicySystem.PolicyView> restored = ProductionPolicySystem.viewsForBase(world, plant);
        require(restored.size() == 1 && restored.get(0).type() == ProductionPolicySystem.PolicyType.REPEAT,
                "policy did not survive runtime save/restore");
        require(ProductionPolicySystem.jobLabel(world, plant, jobId).equals(label),
                "policy queue provenance did not survive runtime save/restore");
        require(ProductionPolicySystem.templateViews(world, plant).isEmpty(),
                "unexpected template appeared during policy restore");
    }

    private static void validatePlannerRootAndLogisticsSupplyNoDuplication() {
        World world = world("planner-supply");
        Base target = base(world, PLAYER + ":M1", PLAYER, "manufacturing", 250, 250);
        Base source = base(world, PLAYER + ":M2", PLAYER, "manufacturing", 750, 650);
        fill(source);
        CraftableItem fuel = CraftingRules.item("fuel");
        require(fuel != null, "Fuel recipe missing for planner supply test");
        for (Cost item : fuel.requiredResources) target.inventory.put(item.material(), item.amount() / 2.0);
        target.inventory.remove(Material.FUEL);
        require(world.craftItem(target.id, "fuel"), "logistics/planner-backed fuel request failed");
        require(target.productionQueue.size() == 1,
                "logistics/planner request did not create exactly one real root queue job");
        require(ProductionSystem.waitingForResources(target.productionQueue.get(0)),
                "logistics/planner root was expected to wait for resources");
        String spec = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK, ProductionJobKind.CRAFTABLE,
                "fuel", "", 50, 1, 80, 1, 0, Map.of(), Map.of());
        require(ProductionCommands.apply(world, PLAYER, "POLICY", target.id,
                ProductionPolicySystem.COMMAND_CREATE, spec), "policy creation over planner root failed");
        ProductionPolicySystem.update(world, 0.6);
        require(target.productionQueue.size() == 1,
                "policy double-count logic queued a duplicate final job beside a planner/logistics root");
    }

    private static void validateLogisticsRequestPersistence() {
        World world = world("logistics");
        Base target = base(world, PLAYER + ":Y1", PLAYER, "shipyard", 200, 200);
        Base source = base(world, PLAYER + ":Y2", PLAYER, "shipyard", 900, 700);
        fill(source);
        ShipType ship = Rules.findShip("prospector");
        ShipLoadoutDefinition loadout = WeaponRules.defaultLoadout("prospector");
        require(ship != null && loadout != null, "Prospector/default loadout missing");
        List<Cost> costs = WeaponRules.buildCost(ship, loadout);
        for (Cost item : costs) target.inventory.put(item.material(), item.amount() / 2.0);

        require(world.buildShip(target.id, loadout.id()), "logistics-backed ship request failed");
        require(target.productionQueue.size() == 1 && ProductionSystem.waitingForResources(target.productionQueue.get(0)),
                "logistics request did not create visible waiting production job");
        String jobId = target.productionQueue.get(0).id;
        Map<String,Object> saved = world.logisticsSystem.capture();
        require(!ServerSaveStore.list(saved.get("requests")).isEmpty(),
                "same-system logistics request was not captured");
        world.logisticsSystem.restore(world, Map.of());
        world.logisticsSystem.restore(world, saved);

        for (Unit unit : world.units.values()) {
            if (!LogisticsSystem.SHUTTLE_TYPE.equals(unit.shipTypeId)) continue;
            unit.x = target.x;
            unit.y = target.y;
        }
        world.logisticsSystem.update(world, 2.1);
        require(ProductionSystem.findJob(target, jobId) != null,
                "restored logistics request lost its production job");
        require(!ProductionSystem.waitingForResources(ProductionSystem.findJob(target, jobId)),
                "restored logistics request failed to fund its waiting job after cargo arrived");
    }

    private static void validateMalformedAndBoundedCommands() {
        World world = world("bounds");
        Base plant = base(world, PLAYER + ":M1", PLAYER, "manufacturing", 400, 400);
        require(!ProductionCommands.apply(world, PLAYER, "POLICY", plant.id,
                ProductionPolicySystem.COMMAND_CREATE, "garbage"),
                "malformed policy command was accepted");
        String invalid = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK,
                ProductionJobKind.CRAFTABLE, "fuel", "", 50,
                ProductionPolicySystem.MAX_BATCH_SIZE + 1, 50, 1, 0, Map.of(), Map.of());
        require(!ProductionCommands.apply(world, PLAYER, "POLICY", plant.id,
                ProductionPolicySystem.COMMAND_CREATE, invalid),
                "out-of-bounds policy batch size was accepted");
        String unknown = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK,
                ProductionJobKind.CRAFTABLE, "not_a_recipe", "", 50,
                1, 50, 1, 0, Map.of(), Map.of());
        require(!ProductionCommands.apply(world, PLAYER, "POLICY", plant.id,
                ProductionPolicySystem.COMMAND_CREATE, unknown),
                "unknown policy item ID was accepted");
        String nan = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK,
                ProductionJobKind.CRAFTABLE, "fuel", "", Double.NaN,
                1, 50, 1, 0, Map.of(), Map.of());
        require(!ProductionCommands.apply(world, PLAYER, "POLICY", plant.id,
                ProductionPolicySystem.COMMAND_CREATE, nan),
                "NaN policy target was accepted");
        String infinity = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK,
                ProductionJobKind.CRAFTABLE, "fuel", "", Double.POSITIVE_INFINITY,
                1, 50, 1, 0, Map.of(), Map.of());
        require(!ProductionCommands.apply(world, PLAYER, "POLICY", plant.id,
                ProductionPolicySystem.COMMAND_CREATE, infinity),
                "infinite policy target was accepted");
        require(ProductionPolicySystem.viewsForBase(world, plant).isEmpty(),
                "rejected policy commands mutated policy state");
    }

    private static void validateLargePolicyPerformanceAndBounds() {
        World world = world("large");
        List<Base> plants = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Base plant = base(world, PLAYER + ":M" + (i + 1), PLAYER, "manufacturing", 250 + i * 180, 350);
            fill(plant);
            plants.add(plant);
        }
        int created = 0;
        for (Base plant : plants) {
            for (int i = 0; i < ProductionPolicySystem.MAX_POLICIES_PER_STATION; i++) {
                String spec = ProductionPolicyWire.encodeSpec("",
                        ProductionPolicySystem.PolicyType.REPEAT, ProductionJobKind.CRAFTABLE,
                        "fuel", "", 0, 1, 50, 1, 0, Map.of(), Map.of());
                require(ProductionCommands.apply(world, PLAYER, "POLICY", plant.id,
                        ProductionPolicySystem.COMMAND_CREATE, spec),
                        "failed to create maximum bounded policy fixture at index " + created);
                created++;
            }
        }
        require(created == ProductionPolicySystem.MAX_POLICIES_PER_PLAYER,
                "large-policy fixture did not reach the player policy bound");
        String overflow = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.REPEAT, ProductionJobKind.CRAFTABLE,
                "fuel", "", 0, 1, 50, 1, 0, Map.of(), Map.of());
        require(!ProductionCommands.apply(world, PLAYER, "POLICY", plants.get(0).id,
                ProductionPolicySystem.COMMAND_CREATE, overflow),
                "policy limit accepted a 129th player policy");

        long started = System.nanoTime();
        ProductionPolicySystem.update(world, 0.6);
        require(queueCount(plants) == 16,
                "first large-policy evaluation did not respect the global 16-job enqueue bound");
        for (int i = 1; i < 8; i++) ProductionPolicySystem.update(world, 0.6);
        require(queueCount(plants) == ProductionPolicySystem.MAX_POLICIES_PER_PLAYER,
                "bounded evaluations did not eventually service all maximum policies");
        ProductionPolicySystem.update(world, 0.6);
        require(queueCount(plants) == ProductionPolicySystem.MAX_POLICIES_PER_PLAYER,
                "maximum-policy reevaluation duplicated already outstanding repeat jobs");
        long elapsed = System.nanoTime() - started;
        require(elapsed < 15_000_000_000L,
                "maximum-policy evaluation exceeded the generous 15 second performance ceiling");
    }

    private static World world(String suffix) {
        PlayerRegistry.reset("SOLO", "Issue 294 Validator", 0x50BEFF);
        World world = new World("Issue 294 Validator " + suffix, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.register(PLAYER, "Policy Tester", 0x50BEFF, true);
        return world;
    }

    private static Base base(World world, String id, String playerId, String typeId, double x, double y) {
        Base base = new Base(id, playerId, typeId, x, y);
        world.bases.put(id, base);
        return base;
    }

    private static void fill(Base base) {
        for (Material material : Material.values()) base.inventory.put(material, 100_000.0);
    }

    private static void setRecipeInputs(Base base, CraftableItem item) {
        base.inventory.clear();
        for (Cost input : item.requiredResources) base.inventory.put(input.material(), input.amount());
        base.inventory.remove(item.outputMaterial);
    }

    private static double cost(List<Cost> costs, Material material) {
        double total = 0;
        for (Cost cost : costs) if (cost.material() == material) total += cost.amount();
        return total;
    }

    private static ProductionPolicySystem.PolicyView findPolicy(List<ProductionPolicySystem.PolicyView> policies,
                                                                String itemId) {
        for (ProductionPolicySystem.PolicyView view : policies) if (itemId.equals(view.itemId())) return view;
        return null;
    }

    private static int queueCount(List<Base> bases) {
        int total = 0;
        for (Base base : bases) total += base.productionQueue.size();
        return total;
    }

    private static String firstOtherSystem(World world) {
        String active = world.activeSystemId();
        for (GalaxyMapSystem system : world.galaxyMapSnapshot().systems()) {
            if (system != null && !system.id().equals(active)) return system.id();
        }
        return "";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}