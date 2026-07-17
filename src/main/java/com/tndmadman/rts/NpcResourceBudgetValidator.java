package com.tndmadman.rts;

import java.util.List;
import java.util.Set;

public final class NpcResourceBudgetValidator {
    private static final double EPSILON = 0.001;

    private NpcResourceBudgetValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem NPC resource budget validation passed.");
    }

    static void validateOrThrow() {
        validateEmergencyFuelProtection();
        validateWorkerRecoveryProtection();
        validateStationRecoveryProtection();
        validateResearchProtectionAndRelease();
        validateRecursiveFleetInputs();
        validateExpansionWaitsForRecovery();
    }

    private static void validateEmergencyFuelProtection() {
        Fixture fixture = fixture("Emergency Fuel Budget");
        ensureWorkers(fixture);
        clearMaterials(fixture);
        double emergency = Math.max(10.0, fixture.faction.fuelReserve() * 0.20);
        fixture.home.inventory.put(Material.FUEL, emergency);

        NpcBudgetPlan plan = NpcResourceBudget.plan(
                fixture.world, fixture.faction, NpcStrategicState.STABILIZE_ECONOMY);
        require(plan.fullyFunded(NpcBudgetCategory.EMERGENCY_FUEL),
                "emergency fuel reserve was not funded");
        List<Cost> oneFuel = List.of(new Cost(Material.FUEL, 1.0));
        require(!NpcResourceBudget.canAfford(fixture.world, fixture.faction,
                        NpcBudgetCategory.GENERAL, oneFuel, plan),
                "general production could consume the emergency fuel reserve");
        require(NpcResourceBudget.canAfford(fixture.world, fixture.faction,
                        NpcBudgetCategory.EMERGENCY_FUEL, oneFuel, plan),
                "emergency fuel work could not consume its own reserve");
    }

    private static void validateWorkerRecoveryProtection() {
        Fixture fixture = fixture("Worker Recovery Budget");
        ensureWorkers(fixture);
        removeOneWorker(fixture);
        clearMaterials(fixture);
        List<Cost> workerCost = Rules.ship(firstWorkerType(fixture.faction)).buildCost;
        addCosts(fixture.home, workerCost, 1.0);

        NpcBudgetPlan plan = NpcResourceBudget.plan(
                fixture.world, fixture.faction, NpcStrategicState.STABILIZE_ECONOMY);
        require(plan.fullyFunded(NpcBudgetCategory.WORKER_RECOVERY),
                "worker recovery reserve was not fully funded");
        require(!NpcResourceBudget.canAfford(fixture.world, fixture.faction,
                        NpcBudgetCategory.GENERAL, workerCost, plan),
                "general production could consume worker replacement materials");
        require(NpcResourceBudget.canAfford(fixture.world, fixture.faction,
                        NpcBudgetCategory.WORKER_RECOVERY, workerCost, plan),
                "worker recovery could not consume its own reservation");
    }

    private static void validateStationRecoveryProtection() {
        Fixture fixture = fixture("Station Recovery Budget");
        ensureWorkers(fixture);
        removeBuilders(fixture);
        clearMaterials(fixture);

        NpcBudgetPlan initial = NpcResourceBudget.plan(
                fixture.world, fixture.faction, NpcStrategicState.ESTABLISH);
        fundDesired(fixture.home, initial, NpcBudgetCategory.STATION_RECOVERY);
        NpcBudgetPlan plan = NpcResourceBudget.plan(
                fixture.world, fixture.faction, NpcStrategicState.ESTABLISH);

        require(plan.fullyFunded(NpcBudgetCategory.STATION_RECOVERY),
                "station recovery reserve was not fully funded");
        Material protectedMaterial = blockedMaterial(plan,
                NpcBudgetCategory.STATION_RECOVERY, NpcBudgetCategory.FLEET);
        List<Cost> probe = List.of(new Cost(protectedMaterial,
                probeAmount(plan.reserved(NpcBudgetCategory.STATION_RECOVERY, protectedMaterial))));
        require(!NpcResourceBudget.canAfford(fixture.world, fixture.faction,
                        NpcBudgetCategory.FLEET, probe, plan),
                "fleet construction could consume station recovery materials");
        require(NpcResourceBudget.canAfford(fixture.world, fixture.faction,
                        NpcBudgetCategory.STATION_RECOVERY, probe, plan),
                "station recovery could not consume its own reservation");
    }

    private static void validateResearchProtectionAndRelease() {
        Fixture fixture = fixture("Research Budget");
        ensureWorkers(fixture);
        ensureAllStations(fixture);
        clearMaterials(fixture);
        powerFuelConsumers(fixture);

        NpcBudgetPlan initial = NpcResourceBudget.plan(
                fixture.world, fixture.faction, NpcStrategicState.RESEARCH);
        fundDesired(fixture.home, initial, NpcBudgetCategory.RESEARCH);
        NpcBudgetPlan plan = NpcResourceBudget.plan(
                fixture.world, fixture.faction, NpcStrategicState.RESEARCH);

        require(plan.fullyFunded(NpcBudgetCategory.RESEARCH),
                "research reserve was not fully funded");
        require(plan.reservedTotal(NpcBudgetCategory.RESEARCH) > EPSILON,
                "missing research created no reservation");
        Material protectedMaterial = blockedMaterial(plan,
                NpcBudgetCategory.RESEARCH, NpcBudgetCategory.GENERAL);
        List<Cost> probe = List.of(new Cost(protectedMaterial,
                probeAmount(plan.reserved(NpcBudgetCategory.RESEARCH, protectedMaterial))));
        require(!NpcResourceBudget.canAfford(fixture.world, fixture.faction,
                        NpcBudgetCategory.GENERAL, probe, plan),
                "general production could consume reserved research materials");
        require(NpcResourceBudget.canAfford(fixture.world, fixture.faction,
                        NpcBudgetCategory.RESEARCH, probe, plan),
                "research could not consume its own reservation");

        completeResearch(fixture);
        NpcBudgetPlan released = NpcResourceBudget.plan(
                fixture.world, fixture.faction, NpcStrategicState.RESEARCH);
        require(desiredTotal(released, NpcBudgetCategory.RESEARCH) <= EPSILON,
                "completed research left stale material reservations");
    }

    private static void validateRecursiveFleetInputs() {
        Fixture fixture = fixture("Fleet Input Budget");
        ensureWorkers(fixture);
        ensureAllStations(fixture);
        completeResearch(fixture);
        removeCombatToOne(fixture);
        clearMaterials(fixture);
        fixture.home.inventory.put(Material.FUEL,
                Math.max(20.0, fixture.faction.fuelReserve()));

        String fleetType = fixture.faction.fleetUnitTypes().get(0);
        List<Cost> directFleetCost = Rules.ship(fleetType).buildCost;
        NpcBudgetPlan emptyFleet = NpcResourceBudget.plan(
                fixture.world, fixture.faction, NpcStrategicState.BUILD_FLEET);
        Material rawInput = null;
        for (Material material : Material.values()) {
            if (!material.raw || directCostContains(directFleetCost, material)) continue;
            if (emptyFleet.desired(NpcBudgetCategory.FLEET, material) <= EPSILON) continue;
            rawInput = material;
            break;
        }
        require(rawInput != null,
                "fleet reservation did not expand processed hull components into raw inputs");
        fixture.home.inventory.put(rawInput,
                emptyFleet.desired(NpcBudgetCategory.FLEET, rawInput));

        NpcBudgetPlan plan = NpcResourceBudget.plan(
                fixture.world, fixture.faction, NpcStrategicState.BUILD_FLEET);
        require(plan.reserved(NpcBudgetCategory.FLEET, rawInput) > EPSILON,
                "recursive fleet input was not protected after becoming available");
        List<Cost> probe = List.of(new Cost(rawInput,
                probeAmount(plan.reserved(NpcBudgetCategory.FLEET, rawInput))));
        require(!NpcResourceBudget.canAfford(fixture.world, fixture.faction,
                        NpcBudgetCategory.GENERAL, probe, plan),
                "general production could consume a recursive fleet input");
        require(NpcResourceBudget.canAfford(fixture.world, fixture.faction,
                        NpcBudgetCategory.FLEET, probe, plan),
                "fleet production could not consume its reserved recursive input");
    }

    private static void validateExpansionWaitsForRecovery() {
        Fixture fixture = fixture("Expansion Budget");
        ensureWorkers(fixture);
        ensureAllStations(fixture);
        ensureCombat(fixture);
        ensureSupportAndIndustry(fixture);
        ensureBuilder(fixture);
        completeResearch(fixture);
        clearMaterials(fixture);
        for (Material material : Material.values()) {
            if (material.raw || material == Material.FUEL) fixture.home.inventory.put(material, 1000.0);
        }

        NpcBudgetPlan ready = NpcResourceBudget.plan(
                fixture.world, fixture.faction, NpcStrategicState.EXPAND);
        require(NpcResourceBudget.canLaunchExpansion(fixture.world, fixture.faction, ready),
                "fully supplied mature faction could not launch an expedition");

        removeOneWorker(fixture);
        removeMaterial(fixture, Material.IRON);
        removeMaterial(fixture, Material.COPPER);
        NpcBudgetPlan blocked = NpcResourceBudget.plan(
                fixture.world, fixture.faction, NpcStrategicState.EXPAND);
        require(!blocked.fullyFunded(NpcBudgetCategory.WORKER_RECOVERY),
                "missing worker was unexpectedly fully funded without iron or copper");
        require(!NpcResourceBudget.canLaunchExpansion(fixture.world, fixture.faction, blocked),
                "expansion launched while worker recovery was underfunded");

        addCosts(fixture.home,
                Rules.ship(firstWorkerType(fixture.faction)).buildCost,
                3.0);
        NpcBudgetPlan restored = NpcResourceBudget.plan(
                fixture.world, fixture.faction, NpcStrategicState.EXPAND);
        require(restored.fullyFunded(NpcBudgetCategory.WORKER_RECOVERY),
                "worker recovery remained underfunded after restoring its build materials");
        require(NpcResourceBudget.canLaunchExpansion(fixture.world, fixture.faction, restored),
                "funded worker recovery did not release the expansion gate");
    }

    private static void fundDesired(Base base, NpcBudgetPlan plan, NpcBudgetCategory through) {
        for (NpcBudgetCategory category : NpcBudgetCategory.values()) {
            if (category.ordinal() > through.ordinal()) break;
            for (Material material : Material.values()) {
                double amount = plan.desired(category, material);
                if (amount > EPSILON) HangarStore.add(base.inventory, material, amount);
            }
        }
    }

    private static void powerFuelConsumers(Fixture fixture) {
        for (Base base : fixture.world.bases.values()) {
            if (!fixture.faction.id().equals(base.playerId) || base.hp <= 0) continue;
            StationFuelRequirement requirement = StationFuelRules.requirement(base.typeId);
            if (requirement != null) HangarStore.add(base.inventory, requirement.material(), 5.0);
        }
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

    private static void clearMaterials(Fixture fixture) {
        for (Base base : fixture.world.bases.values()) {
            if (fixture.faction.id().equals(base.playerId)) base.inventory.clear();
        }
    }

    private static void removeMaterial(Fixture fixture, Material material) {
        for (Base base : fixture.world.bases.values()) {
            if (fixture.faction.id().equals(base.playerId)) base.inventory.remove(material);
        }
    }

    private static void removeOneWorker(Fixture fixture) {
        for (Unit unit : List.copyOf(fixture.world.units.values())) {
            if (!fixture.faction.id().equals(unit.playerId)
                    || unit.type().harvestKinds.isEmpty()
                    || !fixture.faction.workerTypeSet().contains(unit.shipTypeId)) continue;
            fixture.world.units.remove(unit.key());
            return;
        }
        throw new IllegalStateException("No Corsair worker was available to remove");
    }

    private static void ensureWorkers(Fixture fixture) {
        while (workerCount(fixture) < fixture.faction.maxWorkers()) {
            addUnit(fixture, firstWorkerType(fixture.faction));
        }
    }

    private static int workerCount(Fixture fixture) {
        int count = 0;
        for (Unit unit : fixture.world.units.values()) {
            if (fixture.faction.id().equals(unit.playerId) && unit.hp > 0
                    && !unit.type().harvestKinds.isEmpty()
                    && fixture.faction.workerTypeSet().contains(unit.shipTypeId)) count++;
        }
        return count;
    }

    private static String firstWorkerType(NpcFaction faction) {
        for (String type : faction.workerUnitTypes()) if (Rules.SHIPS.containsKey(type)) return type;
        throw new IllegalStateException("Corsair worker type is not configured");
    }

    private static void ensureAllStations(Fixture fixture) {
        for (String type : fixture.faction.stationPackageTypes()) {
            if (!hasBaseType(fixture, type)) addBase(fixture, type);
        }
    }

    private static boolean hasBaseType(Fixture fixture, String type) {
        for (Base base : fixture.world.bases.values()) {
            if (fixture.faction.id().equals(base.playerId) && base.hp > 0 && type.equals(base.typeId)) return true;
        }
        return false;
    }

    private static void addBase(Fixture fixture, String type) {
        int n = fixture.world.bases.size() + 100;
        String id = fixture.faction.id() + ":BUDGET_B" + n;
        fixture.world.bases.put(id, new Base(id, fixture.faction.id(), type,
                fixture.home.x + Math.cos(n) * 520,
                fixture.home.y + Math.sin(n) * 520));
    }

    private static void ensureCombat(Fixture fixture) {
        while (combatCount(fixture) < fixture.faction.targetFleetSize()) addUnit(fixture, "frigate");
    }

    private static void removeCombatToOne(Fixture fixture) {
        for (Unit unit : List.copyOf(fixture.world.units.values())) {
            if (combatCount(fixture) <= 1) return;
            if (fixture.faction.id().equals(unit.playerId) && WeaponRules.armed(unit.type())) {
                fixture.world.units.remove(unit.key());
            }
        }
    }

    private static int combatCount(Fixture fixture) {
        int count = 0;
        for (Unit unit : fixture.world.units.values()) {
            if (fixture.faction.id().equals(unit.playerId) && unit.hp > 0 && WeaponRules.armed(unit.type())) count++;
        }
        return count;
    }

    private static void ensureSupportAndIndustry(Fixture fixture) {
        if (!hasUnitType(fixture, "hauler")) addUnit(fixture, "hauler");
        if (!hasUnitType(fixture, "salvager")) addUnit(fixture, "salvager");
        if (!hasUnitType(fixture, "deep_miner")) addUnit(fixture, "deep_miner");
    }

    private static void ensureBuilder(Fixture fixture) {
        if (!hasUnitType(fixture, "station_builder")) addUnit(fixture, "station_builder");
    }

    private static void removeBuilders(Fixture fixture) {
        fixture.world.units.values().removeIf(unit -> fixture.faction.id().equals(unit.playerId)
                && unit.type().baseBuilder);
    }

    private static boolean hasUnitType(Fixture fixture, String type) {
        for (Unit unit : fixture.world.units.values()) {
            if (fixture.faction.id().equals(unit.playerId) && unit.hp > 0 && type.equals(unit.shipTypeId)) return true;
        }
        return false;
    }

    private static void addUnit(Fixture fixture, String type) {
        int id = 20_000;
        while (fixture.world.units.containsKey(Unit.key(fixture.faction.id(), id))) id++;
        Unit unit = new Unit(fixture.faction.id(), id, type,
                fixture.home.x + id % 13,
                fixture.home.y + id % 17);
        fixture.world.units.put(unit.key(), unit);
    }

    private static void completeResearch(Fixture fixture) {
        for (String topicId : fixture.faction.researchTopicIds()) {
            fixture.world.completeResearch(fixture.faction.id(), topicId);
        }
    }

    private static void addCosts(Base base, List<Cost> cost, double multiplier) {
        for (Cost entry : cost) {
            HangarStore.add(base.inventory, entry.material(), entry.amount() * multiplier);
        }
    }

    private static boolean directCostContains(List<Cost> cost, Material material) {
        for (Cost entry : cost) if (entry.material() == material && entry.amount() > EPSILON) return true;
        return false;
    }

    private static Material blockedMaterial(NpcBudgetPlan plan,
                                              NpcBudgetCategory protectedCategory,
                                              NpcBudgetCategory spendingCategory) {
        for (Material material : Material.values()) {
            double reserved = plan.reserved(protectedCategory, material);
            if (reserved <= EPSILON) continue;
            double probe = probeAmount(reserved);
            double available = plan.total(material) - plan.protectedBefore(spendingCategory, material);
            if (available + EPSILON < probe) return material;
        }
        throw new IllegalStateException("No exclusively protected material was found for " + protectedCategory);
    }

    private static double probeAmount(double reserved) {
        return Math.max(0.01, Math.min(1.0, reserved * 0.5));
    }

    private static double desiredTotal(NpcBudgetPlan plan, NpcBudgetCategory category) {
        double total = 0.0;
        for (Material material : Material.values()) total += plan.desired(category, material);
        return total;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Fixture(World world, NpcFaction faction, Base home) { }
}
