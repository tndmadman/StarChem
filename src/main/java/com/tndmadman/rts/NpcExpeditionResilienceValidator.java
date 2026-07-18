package com.tndmadman.rts;

import java.util.Set;

public final class NpcExpeditionResilienceValidator {
    private NpcExpeditionResilienceValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem NPC expedition resilience validation passed.");
    }

    static void validateOrThrow() {
        validateTerritorialPolicy();
        validateConsumedDeployerReplacementAtFullSupport();
        validateTransientSurvivorDelaysInsteadOfInvalidates();
    }

    private static void validateTerritorialPolicy() {
        NpcFaction faction = corsairs();
        require(faction.homeInfrastructureTarget() == 3,
                "Corsair home did not reserve exactly three infrastructure stations");
        require(faction.maxStations() == 6,
                "Corsair territorial station cap is not configured for repeated expansion");
        require(faction.maxControlledSystems() == 4,
                "Corsair territorial policy still stops after the first frontier system");
        require(!NpcStrategicState.EXPAND.buildsStations(),
                "local station construction still competes with expedition footholds");
        require(!NpcStrategicState.FORTIFY.buildsStations(),
                "fortification can still consume frontier station slots locally");
    }

    private static void validateConsumedDeployerReplacementAtFullSupport() {
        Fixture fixture = fixture("Expedition Deployer Replacement");
        World world = fixture.world;
        NpcFaction faction = fixture.faction;

        NpcFactionCapacitySnapshot before = NpcFactionCapacitySystem.snapshot(world, faction);
        require(before.support() == faction.maxSupportUnits(),
                "fixture did not begin with the permanent support cap full");
        require(before.shipTypeCount("station_builder") == 0,
                "fixture unexpectedly retained the consumed deployer");
        require(before.livingStations() == faction.homeInfrastructureTarget(),
                "fixture did not begin with complete home infrastructure");

        NpcExpeditionSnapshot expedition = NpcExpeditionSnapshot.NONE;
        for (int i = 0; i < 90; i++) {
            world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
            fixture.director.update(world, 1.0);
            expedition = NpcExpeditionSystem.snapshot(world, faction);
            if (expedition.active()
                    && expedition.state() == NpcExpeditionState.ASSEMBLING) break;
        }

        require(expedition.active()
                        && expedition.state() == NpcExpeditionState.ASSEMBLING,
                "Corsairs did not replace a consumed deployer and reserve another expedition");
        require(!expedition.builderKey().isBlank(),
                "replacement expedition reserved no deployer");
        Unit builder = world.units.get(expedition.builderKey());
        require(builder != null && builder.type().baseBuilder
                        && faction.baseType().equals(builder.basePackageType),
                "replacement deployer was not created and loaded for the frontier foothold");

        NpcFactionCapacitySnapshot after = NpcFactionCapacitySystem.snapshot(world, faction);
        require(after.support() == faction.maxSupportUnits(),
                "disposable deployer incorrectly increased permanent support usage");
        require(after.shipTypeCount("station_builder") == 1,
                "replacement deployer was missing from authoritative ship counts");
        require(after.stationCommitments() == before.livingStations() + 1,
                "reserved expedition did not claim exactly one frontier station slot");
        require(after.stationCommitments() < faction.maxStations(),
                "one replacement expedition incorrectly exhausted the territorial station cap");
    }

    private static void validateTransientSurvivorDelaysInsteadOfInvalidates() {
        Fixture fixture = fixture("Expedition Survivor Clearance");
        World world = fixture.world;
        NpcFaction faction = fixture.faction;
        advanceStrategyToExpand(world, faction);

        NpcExpeditionSystem.update(world, faction, NpcStrategicState.EXPAND, 1.0);
        NpcExpeditionSnapshot planning = NpcExpeditionSystem.snapshot(world, faction);
        require(planning.active() && planning.state() == NpcExpeditionState.PLANNING,
                "fixture did not create a pre-reservation expedition plan");

        String target = planning.targetSystemId();
        world.activateSystem(target);
        Unit survivor = new Unit(faction.id(), 97_901, "prospector",
                world.width * 0.5, world.height * 0.5);
        world.units.put(survivor.key(), survivor);
        world.saveActiveSystem();
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);

        require(!NpcExpeditionReadinessSystem.allowProgress(
                        world, faction, NpcStrategicState.EXPAND, 1.0),
                "transient friendly survivor did not pause prelaunch target validation");
        require(NpcExpeditionSystem.snapshot(world, faction).state()
                        == NpcExpeditionState.PLANNING,
                "paused survivor clearance destroyed or advanced the expedition plan");
        require(NpcExpeditionReadinessSystem.status(world, faction)
                        .contains("surviving ship"),
                "readiness diagnostics did not explain the survivor clearance wait");

        world.activateSystem(target);
        world.units.remove(survivor.key());
        world.saveActiveSystem();
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);

        require(NpcExpeditionReadinessSystem.allowProgress(
                        world, faction, NpcStrategicState.EXPAND, 1.0),
                "cleared target remained blocked by stale survivor state");
        NpcExpeditionSystem.update(world, faction, NpcStrategicState.EXPAND, 1.0);
        require(NpcExpeditionSystem.snapshot(world, faction).state()
                        == NpcExpeditionState.RESERVING,
                "cleared target did not resume reservation");
        require(NpcExpeditionReadinessSystem.allowProgress(
                        world, faction, NpcStrategicState.EXPAND, 1.0),
                "ready replacement roster did not pass reservation readiness");
        NpcExpeditionSystem.update(world, faction, NpcStrategicState.EXPAND, 1.0);
        require(NpcExpeditionSystem.snapshot(world, faction).state()
                        == NpcExpeditionState.ASSEMBLING,
                "survivor-delayed expedition did not reserve after the target cleared");
    }

    private static void advanceStrategyToExpand(World world, NpcFaction faction) {
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        NpcStrategicDirector.clear(world);
        NpcStrategicDirector.onSpawned(world, faction);
        for (int i = 0; i < 20; i++) {
            NpcStrategicState state = NpcStrategicDirector.update(world, faction, 3.0);
            if (state == NpcStrategicState.EXPAND) return;
        }
        throw new IllegalStateException("strategically ready Corsairs never entered EXPAND");
    }

    private static Fixture fixture(String name) {
        PlayerRegistry.reset("WAIT", name, 0x50BEFF);
        World world = new World(name,
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID),
                StarSystems.CORSAIR_SYSTEM_ID,
                false);
        world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        NpcStationConstructionSystem.clear(world);
        NpcExpeditionSystem.clear(world);
        NpcExpeditionReadinessSystem.clear(world);
        NpcStrategicDirector.clear(world);

        NpcFaction faction = corsairs();
        double x = world.width * 0.5;
        double y = world.height * 0.5;
        addBase(world, faction, 1, "outpost", x, y);
        addBase(world, faction, 2, "laboratory", x - 620, y + 180);
        addBase(world, faction, 3, "manufacturing", x + 620, y + 180);
        for (Base base : world.bases.values()) {
            for (Material material : Material.values()) {
                base.inventory.put(material, 100_000.0);
            }
        }

        addUnit(world, faction, 97_001, "prospector", x - 180, y);
        addUnit(world, faction, 97_002, "prospector", x - 230, y + 80);
        addUnit(world, faction, 97_003, "prospector", x - 280, y + 140);
        addUnit(world, faction, 97_011, "frigate", x, y + 130);
        addUnit(world, faction, 97_012, "frigate", x, y - 130);
        addUnit(world, faction, 97_013, "destroyer", x + 170, y + 100);
        addUnit(world, faction, 97_014, "destroyer", x - 170, y - 100);
        addUnit(world, faction, 97_015, "frigate", x + 235, y - 145);
        addUnit(world, faction, 97_016, "frigate", x - 235, y + 145);
        addUnit(world, faction, 97_021, "hauler", x + 280, y + 220);
        addUnit(world, faction, 97_022, "freighter", x + 340, y + 220);
        addUnit(world, faction, 97_023, "salvager", x + 400, y + 220);
        addUnit(world, faction, 97_031, "deep_miner", x - 340, y + 220);
        for (String topic : faction.researchTopicIds()) {
            world.completeResearch(faction.id(), topic);
        }
        world.saveActiveSystem();

        GalaxyMapSystem home = mapSystem(world.authoritativeGalaxyMapSnapshot(),
                StarSystems.CORSAIR_SYSTEM_ID);
        require(home != null && faction.id().equals(home.controllerId()),
                "resilience fixture did not begin under Corsair control");
        return new Fixture(world, faction, new NpcGalaxyDirector());
    }

    private static Base addBase(World world, NpcFaction faction, int number,
                                String type, double x, double y) {
        String id = faction.id() + ":RESILIENT_B" + number;
        Base base = new Base(id, faction.id(), type, x, y);
        world.bases.put(id, base);
        return base;
    }

    private static Unit addUnit(World world, NpcFaction faction, int id,
                                String type, double x, double y) {
        Unit unit = new Unit(faction.id(), id, type, x, y);
        world.units.put(unit.key(), unit);
        return unit;
    }

    private static GalaxyMapSystem mapSystem(GalaxyMapSnapshot map, String id) {
        if (map == null || map.systems() == null) return null;
        for (GalaxyMapSystem system : map.systems()) {
            if (system != null && id.equals(system.id())) return system;
        }
        return null;
    }

    private static NpcFaction corsairs() {
        for (NpcFaction faction : NpcRules.factions()) {
            if (Config.CORSAIRS_ID.equals(faction.id())) return faction;
        }
        throw new IllegalStateException("Corsair faction is not configured");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Fixture(World world, NpcFaction faction,
                           NpcGalaxyDirector director) { }
}
