package com.tndmadman.rts;

import java.util.Set;

public final class NpcStationDeployerRecoveryValidator {
    private NpcStationDeployerRecoveryValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem NPC station deployer recovery validation passed.");
    }

    static void validateOrThrow() {
        validateLoadedOrphanIsAdopted();
        validateAtCapDeployerIsParked();
        validateDevStationUsesTimedPipeline();
    }

    private static void validateLoadedOrphanIsAdopted() {
        Fixture fixture = fixture("Loaded Deployer Recovery");
        Unit builder = fixture.builder;
        builder.basePackageType = "shipyard";
        builder.issueMove(builder.x + 900, builder.y + 300);

        require(!NpcStationConstructionSystem.hasActivePlan(fixture.world, fixture.faction),
                "fixture unexpectedly began with a construction plan");
        NpcStationDeployerRecoverySystem.update(
                fixture.world, fixture.faction, NpcStrategicState.FORTIFY);

        NpcStationConstructionSnapshot adopted = NpcStationConstructionSystem.snapshot(
                fixture.world, fixture.faction);
        require(adopted.active(),
                "loaded deployer whose runtime plan was lost was not adopted");
        require(builder.key().equals(adopted.builderKey()),
                "orphan recovery assigned a different deployer");
        require("shipyard".equals(adopted.packageType()),
                "orphan recovery changed the carried package");
        require(baseCount(fixture.world, fixture.faction.id()) == 1,
                "orphan recovery created a completed station immediately");

        int guard = 0;
        while (NpcStationConstructionSystem.hasActivePlan(fixture.world, fixture.faction)
                && guard++ < 240) {
            NpcStationDeployerRecoverySystem.update(
                    fixture.world, fixture.faction, NpcStrategicState.FORTIFY);
            NpcStationConstructionSystem.update(fixture.world, fixture.faction, 1.0);
            Unit live = fixture.world.units.get(builder.key());
            if (live != null) live.updatePosition(1.0, fixture.world.width, fixture.world.height);
        }
        require(!NpcStationConstructionSystem.hasActivePlan(fixture.world, fixture.faction),
                "adopted deployer did not finish its timed plan");
        require(baseCount(fixture.world, fixture.faction.id()) == 2,
                "adopted deployer did not create exactly one station");
        require(!fixture.world.units.containsKey(builder.key()),
                "completed single-use deployer was not consumed");
    }

    private static void validateAtCapDeployerIsParked() {
        Fixture fixture = fixture("Capped Deployer Parking");
        for (int i = 1; i < fixture.faction.maxStations(); i++) {
            double angle = i * Math.PI * 2.0 / fixture.faction.maxStations();
            String id = fixture.faction.id() + ":CAP_B" + i;
            fixture.world.bases.put(id, new Base(id, fixture.faction.id(), "shipyard",
                    fixture.source.x + Math.cos(angle) * 1500,
                    fixture.source.y + Math.sin(angle) * 1500));
        }
        Unit builder = fixture.builder;
        builder.basePackageType = "shipyard";
        builder.issueMove(builder.x + 1000, builder.y + 700);
        double parkedX = builder.x;
        double parkedY = builder.y;

        NpcStationDeployerRecoverySystem.update(
                fixture.world, fixture.faction, NpcStrategicState.FORTIFY);
        require(!NpcStationConstructionSystem.hasActivePlan(fixture.world, fixture.faction),
                "deployer started construction above the station cap");
        require("shipyard".equals(builder.basePackageType),
                "parked capped deployer lost its paid package");
        require(Math.abs(builder.targetX - parkedX) < 0.001
                        && Math.abs(builder.targetY - parkedY) < 0.001,
                "capped deployer continued wandering instead of parking");
        require(builder.task == UnitTask.MOVE,
                "parking did not suppress generic idle orbit for this tick");
    }

    private static void validateDevStationUsesTimedPipeline() {
        Fixture fixture = fixture("Dev Timed Station Deployment");
        int before = baseCount(fixture.world, fixture.faction.id());
        AiDevCommands.forceStation(fixture.world);
        fixture.world.activateSystem(StarSystems.CORSAIR_SYSTEM_ID);

        require(baseCount(fixture.world, fixture.faction.id()) == before,
                "dev force-station action still inserted a completed station instantly");
        NpcStationConstructionSnapshot plan = NpcStationConstructionSystem.snapshot(
                fixture.world, fixture.faction);
        require(plan.active(),
                "dev force-station action did not start the timed construction pipeline");
        Unit builder = fixture.world.units.get(plan.builderKey());
        require(builder != null && !builder.basePackageType.isBlank(),
                "dev timed deployment did not visibly load its deployer");
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
        world.bases.put(source.id, source);
        Unit builder = new Unit(faction.id(), 91_001, "station_builder",
                source.x + 90, source.y);
        world.units.put(builder.key(), builder);
        world.saveActiveSystem();
        return new Fixture(world, faction, source, builder);
    }

    private static NpcFaction corsairs() {
        for (NpcFaction faction : NpcRules.factions()) {
            if (Config.CORSAIRS_ID.equals(faction.id())) return faction;
        }
        throw new IllegalStateException("Corsair faction is not configured");
    }

    private static int baseCount(World world, String factionId) {
        int count = 0;
        for (Base base : world.bases.values()) {
            if (factionId.equals(base.playerId) && base.hp > 0) count++;
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Fixture(World world, NpcFaction faction, Base source, Unit builder) { }
}
