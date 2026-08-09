package com.tndmadman.rts;

import java.util.EnumMap;
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
        validateTemplatesAndStatusReplication();
        validatePolicyPersistenceAndJobProvenance();
        validateLogisticsRequestPersistence();
        validateMalformedAndBoundedCommands();
    }

    private static void validateMaintainStockAndNoDuplicateJobs() {
        World world = world("stock");
        Base plant = base(world, PLAYER + ":M1", PLAYER, "manufacturing", 400, 400);
        fill(plant);
        String spec = ProductionPolicySystem.encodeSpec("",
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

        String spec = ProductionPolicySystem.encodeSpec("",
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
        CraftableItem fuel = CraftingRules.item("fuel");
        require(fuel != null, "Fuel recipe missing");
        double hydrogenCost = cost(fuel.requiredResources, Material.HYDROGEN);
        plant.inventory.put(Material.HYDROGEN, hydrogenCost + 20);
        EnumMap<Material,Double> stationReserve = new EnumMap<>(Material.class);
        stationReserve.put(Material.HYDROGEN, 25.0);
        String reserveSpec = ProductionPolicySystem.encodeSpec("",
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
        String spec = ProductionPolicySystem.encodeSpec("",
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

    private static void validateTemplatesAndStatusReplication() {
        World world = world("template");
        Base first = base(world, PLAYER + ":M1", PLAYER, "manufacturing", 300, 300);
        Base second = base(world, PLAYER + ":M2", PLAYER, "manufacturing", 700, 300);
        fill(first);
        fill(second);
        String spec = ProductionPolicySystem.encodeSpec("",
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
    }

    private static void validatePolicyPersistenceAndJobProvenance() {
        World world = world("persist");
        Base plant = base(world, PLAYER + ":M1", PLAYER, "manufacturing", 400, 400);
        fill(plant);
        String spec = ProductionPolicySystem.encodeSpec("",
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
        require(ProductionPolicySystem.viewsForBase(world, plant).isEmpty(),
                "policy runtime did not clear for persistence test");
        ProductionPlanner.restore(world, savedPlanner);
        List<ProductionPolicySystem.PolicyView> restored = ProductionPolicySystem.viewsForBase(world, plant);
        require(restored.size() == 1 && restored.get(0).type() == ProductionPolicySystem.PolicyType.REPEAT,
                "policy did not survive runtime save/restore");
        require(ProductionPolicySystem.jobLabel(world, plant, jobId).equals(label),
                "policy queue provenance did not survive runtime save/restore");
        require(ProductionPolicySystem.templateViews(world, plant).isEmpty(),
                "unexpected template appeared during policy restore");
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
        String invalid = ProductionPolicySystem.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK,
                ProductionJobKind.CRAFTABLE, "fuel", "", 50,
                ProductionPolicySystem.MAX_BATCH_SIZE + 1, 50, 1, 0, Map.of(), Map.of());
        require(!ProductionCommands.apply(world, PLAYER, "POLICY", plant.id,
                ProductionPolicySystem.COMMAND_CREATE, invalid),
                "out-of-bounds policy batch size was accepted");
        String unknown = ProductionPolicySystem.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK,
                ProductionJobKind.CRAFTABLE, "not_a_recipe", "", 50,
                1, 50, 1, 0, Map.of(), Map.of());
        require(!ProductionCommands.apply(world, PLAYER, "POLICY", plant.id,
                ProductionPolicySystem.COMMAND_CREATE, unknown),
                "unknown policy item ID was accepted");
        require(ProductionPolicySystem.viewsForBase(world, plant).isEmpty(),
                "rejected policy commands mutated policy state");
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

    private static double cost(List<Cost> costs, Material material) {
        double total = 0;
        for (Cost cost : costs) if (cost.material() == material) total += cost.amount();
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