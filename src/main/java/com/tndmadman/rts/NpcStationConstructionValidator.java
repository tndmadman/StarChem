package com.tndmadman.rts;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public final class NpcStationConstructionValidator {
    private static final double EPSILON = 0.001;

    private NpcStationConstructionValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem NPC station construction validation passed.");
    }

    static void validateOrThrow() {
        validateScoredTravelAndTimedCompletion();
        validateCancellationAndLossRules();
        validateObstructionReplanning();
        validateNpcSystemUsesPipeline();
    }

    private static void validateScoredTravelAndTimedCompletion() {
        Fixture fixture = fixture("NPC Station Travel");
        Base source = fixture.source;
        Unit builder = fixture.builder;

        for (int i = 0; i < 5; i++) {
            fixture.world.resources.add(new ResourceNode(90_000 + i, "East Resource " + i,
                    NodeKind.SILICATE_ROCK, Material.IRON,
                    source.x + 950 + i * 55, source.y - 180 + i * 90,
                    5000, 10, 42));
        }
        Unit threat = new Unit("PHASE7_ENEMY", 1, "frigate", source.x - 1050, source.y);
        fixture.world.units.put(threat.key(), threat);

        BaseType station = Rules.base("manufacturing");
        EnumMap<Material, Double> before = copy(source.inventory);
        require(NpcStationConstructionSystem.start(fixture.world, fixture.faction, source, builder,
                        station.id, NpcBudgetCategory.STATION_RECOVERY),
                "construction plan could not be started");
        NpcStationConstructionSnapshot plan = NpcStationConstructionSystem.snapshot(
                fixture.world, fixture.faction);
        require(plan.active() && plan.phase() == NpcConstructionPhase.TRAVELLING,
                "new station plan did not enter TRAVELLING state");
        require(plan.targetX() > source.x + 100,
                "manufacturing site scoring did not favor the safe resource-rich side");
        require(factionBaseCount(fixture.world, fixture.faction.id()) == 1,
                "station appeared immediately when the plan was created");
        require(station.id.equals(builder.basePackageType),
                "deployer did not visibly carry the committed station package");
        require(!fixture.world.placePackage(builder),
                "manual or recovery placement bypassed an active timed construction plan");
        require(NpcStationConstructionSystem.snapshot(fixture.world, fixture.faction).active()
                        && fixture.world.units.containsKey(builder.key())
                        && factionBaseCount(fixture.world, fixture.faction.id()) == 1,
                "blocked direct placement damaged the active construction plan");
        assertSpentExactly(before, source.inventory, station.buildCost,
                "initial construction reservation");

        EnumMap<Material, Double> afterFirstStart = copy(source.inventory);
        require(NpcStationConstructionSystem.start(fixture.world, fixture.faction, source, builder,
                        station.id, NpcBudgetCategory.STATION_RECOVERY),
                "duplicate start did not recognize the active construction plan");
        require(afterFirstStart.equals(source.inventory),
                "duplicate construction request charged the package twice");
        require(NpcStationConstructionSystem.snapshot(fixture.world, fixture.faction).builderKey()
                        .equals(builder.key()),
                "duplicate construction request replaced the assigned deployer");

        String targetSystem = "red_dwarf";
        fixture.world.saveActiveSystem();
        fixture.world.activateSystem(targetSystem);
        fixture.world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        NpcStationConstructionSnapshot restored = NpcStationConstructionSystem.snapshot(
                fixture.world, fixture.faction);
        require(restored.active()
                        && Math.abs(restored.targetX() - plan.targetX()) < EPSILON
                        && Math.abs(restored.targetY() - plan.targetY()) < EPSILON,
                "construction plan did not survive a system switch");

        int travelGuard = 0;
        while (NpcStationConstructionSystem.snapshot(fixture.world, fixture.faction).phase()
                == NpcConstructionPhase.TRAVELLING && travelGuard++ < 120) {
            NpcStationConstructionSystem.update(fixture.world, fixture.faction, 1.0);
            builder.updatePosition(1.0, fixture.world.width, fixture.world.height);
        }
        NpcStationConstructionSnapshot constructing = NpcStationConstructionSystem.snapshot(
                fixture.world, fixture.faction);
        require(constructing.active() && constructing.phase() == NpcConstructionPhase.CONSTRUCTING,
                "deployer did not physically reach the construction site");
        require(factionBaseCount(fixture.world, fixture.faction.id()) == 1,
                "completed station appeared as soon as the deployer arrived");
        require(Calc.distance(builder.x, builder.y, constructing.targetX(), constructing.targetY()) <= 1.0,
                "construction-site deployer was not anchored at the selected site");

        double almostComplete = Math.max(0, constructing.duration() - 0.25);
        NpcStationConstructionSystem.update(fixture.world, fixture.faction, almostComplete);
        require(factionBaseCount(fixture.world, fixture.faction.id()) == 1,
                "station completed before its configured construction duration");
        NpcStationConstructionSystem.update(fixture.world, fixture.faction, 0.30);
        require(!NpcStationConstructionSystem.snapshot(fixture.world, fixture.faction).active(),
                "construction plan remained active after completion");
        require(!fixture.world.units.containsKey(builder.key()),
                "single-use deployer survived completed station construction");
        Base completed = baseOfType(fixture.world, fixture.faction.id(), station.id);
        require(completed != null,
                "timed construction did not create the requested station type");
        require(Calc.distance(completed.x, completed.y,
                        constructing.targetX(), constructing.targetY()) < EPSILON,
                "completed station was not created at the scored construction site");
    }

    private static void validateCancellationAndLossRules() {
        Fixture refundable = fixture("NPC Station Refund");
        BaseType station = Rules.base("shipyard");
        EnumMap<Material, Double> original = copy(refundable.source.inventory);
        require(NpcStationConstructionSystem.start(refundable.world, refundable.faction,
                        refundable.source, refundable.builder, station.id,
                        NpcBudgetCategory.STATION_RECOVERY),
                "refundable construction plan could not start");
        require(NpcStationConstructionSystem.cancel(refundable.world, refundable.faction,
                        "validator cancellation before ground work"),
                "travelling construction plan could not be cancelled");
        require(original.equals(refundable.source.inventory),
                "travelling cancellation did not refund the package exactly once");
        require(refundable.builder.basePackageType.isBlank(),
                "cancelled deployer retained its station package");
        require(!NpcStationConstructionSystem.snapshot(refundable.world, refundable.faction).active(),
                "cancelled construction plan remained active");

        Fixture committed = fixture("NPC Station Committed Loss");
        EnumMap<Material, Double> before = copy(committed.source.inventory);
        require(NpcStationConstructionSystem.start(committed.world, committed.faction,
                        committed.source, committed.builder, station.id,
                        NpcBudgetCategory.STATION_RECOVERY),
                "committed construction plan could not start");
        NpcStationConstructionSnapshot plan = NpcStationConstructionSystem.snapshot(
                committed.world, committed.faction);
        committed.builder.x = plan.targetX();
        committed.builder.y = plan.targetY();
        NpcStationConstructionSystem.update(committed.world, committed.faction, 0.0);
        require(NpcStationConstructionSystem.snapshot(committed.world, committed.faction).phase()
                        == NpcConstructionPhase.CONSTRUCTING,
                "loss fixture did not begin ground construction");
        committed.builder.hp = 0;
        NpcStationConstructionSystem.update(committed.world, committed.faction, 1.0);
        require(!NpcStationConstructionSystem.snapshot(committed.world, committed.faction).active(),
                "deployer-loss plan did not fail deterministically");
        require(factionBaseCount(committed.world, committed.faction.id()) == 1,
                "destroyed deployer still produced a station");
        assertSpentExactly(before, committed.source.inventory, station.buildCost,
                "deployer loss after construction start");
    }

    private static void validateObstructionReplanning() {
        Fixture fixture = fixture("NPC Station Replan");
        BaseType station = Rules.base("shipyard");
        require(NpcStationConstructionSystem.start(fixture.world, fixture.faction,
                        fixture.source, fixture.builder, station.id,
                        NpcBudgetCategory.STATION_RECOVERY),
                "replanning fixture could not start");
        NpcStationConstructionSnapshot first = NpcStationConstructionSystem.snapshot(
                fixture.world, fixture.faction);
        EnumMap<Material, Double> afterReservation = copy(fixture.source.inventory);

        Base obstruction = new Base(fixture.faction.id() + ":B90", fixture.faction.id(),
                "outpost", first.targetX(), first.targetY());
        fixture.world.bases.put(obstruction.id, obstruction);
        NpcStationConstructionSystem.update(fixture.world, fixture.faction, 1.0);
        NpcStationConstructionSnapshot replanned = NpcStationConstructionSystem.snapshot(
                fixture.world, fixture.faction);
        require(replanned.active() && replanned.replans() == 1,
                "site obstruction did not produce exactly one replan");
        require(Calc.distance(first.targetX(), first.targetY(),
                        replanned.targetX(), replanned.targetY()) > 50.0,
                "site obstruction did not change the construction target");
        require(afterReservation.equals(fixture.source.inventory),
                "site replanning charged the station package again");
        require(factionBaseCount(fixture.world, fixture.faction.id()) == 2,
                "replanning created an extra completed station");
    }

    private static void validateNpcSystemUsesPipeline() {
        Fixture fixture = fixture("NPC Station Integration");
        addUnit(fixture.world, fixture.faction, 81_101, "prospector",
                fixture.source.x - 130, fixture.source.y + 80);
        addUnit(fixture.world, fixture.faction, 81_102, "prospector",
                fixture.source.x + 130, fixture.source.y + 80);
        addUnit(fixture.world, fixture.faction, 81_103, "prospector",
                fixture.source.x, fixture.source.y - 150);
        fixture.world.saveActiveSystem();

        NpcStrategicState strategic = NpcStrategicDirector.update(
                fixture.world, fixture.faction, 1.0);
        require(strategic == NpcStrategicState.ESTABLISH,
                "integration fixture did not enter ESTABLISH strategy");

        NpcSystem system = new NpcSystem(Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID));
        system.update(fixture.world, 17.0);
        NpcStationConstructionSnapshot plan = NpcStationConstructionSystem.snapshot(
                fixture.world, fixture.faction);
        require(plan.active(),
                "NpcSystem station order did not create a persistent construction plan");
        require(factionBaseCount(fixture.world, fixture.faction.id()) == 1,
                "NpcSystem still created a completed station instantly");
        require(fixture.world.units.containsKey(plan.builderKey()),
                "NpcSystem consumed the deployer before construction completed");
    }

    private static Fixture fixture(String name) {
        PlayerRegistry.reset("WAIT", name, 0x50BEFF);
        World world = new World(name,
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.CORSAIR_SYSTEM_ID,
                false);
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        world.resources.clear();
        world.wormholes.clear();
        world.units.clear();
        world.bases.clear();
        NpcStationConstructionSystem.clear(world);

        NpcFaction faction = corsairs();
        Base source = new Base(faction.id() + ":B1", faction.id(), "outpost",
                world.width * 0.5, world.height * 0.5);
        for (Material material : Material.values()) source.inventory.put(material, 100_000.0);
        world.bases.put(source.id, source);
        Unit builder = addUnit(world, faction, 81_001, "station_builder",
                source.x + 70, source.y);
        return new Fixture(world, faction, source, builder);
    }

    private static NpcFaction corsairs() {
        for (NpcFaction faction : NpcRules.factions()) {
            if (Config.CORSAIRS_ID.equals(faction.id())) return faction;
        }
        throw new IllegalStateException("Corsair faction is not configured");
    }

    private static Unit addUnit(World world, NpcFaction faction, int id,
                                String type, double x, double y) {
        Unit unit = new Unit(faction.id(), id, type, x, y);
        world.units.put(unit.key(), unit);
        return unit;
    }

    private static int factionBaseCount(World world, String factionId) {
        int count = 0;
        for (Base base : world.bases.values()) {
            if (factionId.equals(base.playerId) && base.hp > 0) count++;
        }
        return count;
    }

    private static Base baseOfType(World world, String factionId, String typeId) {
        for (Base base : world.bases.values()) {
            if (factionId.equals(base.playerId) && base.hp > 0 && typeId.equals(base.typeId)) return base;
        }
        return null;
    }

    private static EnumMap<Material, Double> copy(Map<Material, Double> source) {
        EnumMap<Material, Double> copy = new EnumMap<>(Material.class);
        copy.putAll(source);
        return copy;
    }

    private static void assertSpentExactly(Map<Material, Double> before,
                                           Map<Material, Double> after,
                                           Iterable<Cost> cost,
                                           String context) {
        EnumMap<Material, Double> expected = new EnumMap<>(Material.class);
        for (Cost entry : cost) expected.merge(entry.material(), entry.amount(), Double::sum);
        for (Material material : Material.values()) {
            double spent = before.getOrDefault(material, 0.0)
                    - after.getOrDefault(material, 0.0);
            double required = expected.getOrDefault(material, 0.0);
            require(Math.abs(spent - required) < EPSILON,
                    context + " changed " + material + " by " + spent
                            + " instead of " + required);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Fixture(World world, NpcFaction faction, Base source, Unit builder) { }
}
