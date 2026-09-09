package com.tndmadman.rts;

import java.util.Set;

public final class SystemControlValidator {
    private static final Set<String> NO_NPCS = Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID, Config.CORSAIRS_ID);

    private SystemControlValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem system control validation passed.");
    }

    static void validateOrThrow() {
        validateStrategicDefinitions();

        PlayerRegistry.reset("P1", "Blue", 0x3388FF);
        PlayerRegistry.register("P1", "Blue", 0x3388FF, true);
        PlayerRegistry.register("P2", "Red", 0xFF5544, false);
        World world = new World("Control Validator", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        double x = world.width * 0.5;
        double y = world.height * 0.5;
        world.bases.put("P1:B1", new Base("P1:B1", "P1", Rules.DEFAULT_BASE, x, y));
        world.updateCurrentSystem(76.0);
        GalaxyMapSystem controlled = system(world.authoritativeGalaxyMapSnapshot(), world.activeSystemId());
        require(controlled.controlStatus() == SystemControlStatus.CONTROLLED && "P1".equals(controlled.controllerId()),
                "uncontested player influence did not capture the system");
        require(controlled.controlColorRgb() == 0x3388FF, "map control color does not match controlling player");
        require(SystemControlBonuses.miningYield(world, "P1") > 1.0
                        && SystemControlBonuses.shieldRegen(world, "P1") > 1.0,
                "system controller received no configured territorial bonuses");
        require(SystemControlBonuses.miningYield(world, "P2") == 1.0,
                "non-controller received territorial bonuses");
        require(SystemControlBonuses.supplyState(world, "P1") == StrategicSupplyState.SUPPLIED,
                "controlled bootstrap territory was not supplied");
        int recomputes = StrategicSupplyService.recomputeCountForTest(world);
        SystemControlBonuses.supplyState(world, "P1");
        SystemControlBonuses.supplyState(world, "P1");
        require(StrategicSupplyService.recomputeCountForTest(world) == recomputes,
                "supply state rescanned the galaxy inside the cache interval");

        world.bases.put("P2:B1", new Base("P2:B1", "P2", Rules.DEFAULT_BASE, x + 80, y + 60));
        world.updateCurrentSystem(1.0);
        GalaxyMapSystem contested = system(world.authoritativeGalaxyMapSnapshot(), world.activeSystemId());
        require(contested.controlStatus() == SystemControlStatus.CONTESTED,
                "hostile presence in the command zone did not contest control");

        validateSensorConsumer();
        validateLogisticsConsumer();

        PlayerRegistry.reset("SOLO", "Home", 0x50BEFF);
        World home = new World("Home", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, true);
        home.bases.put("P2:B2", new Base("P2:B2", "P2", Rules.DEFAULT_BASE, home.width * 0.5, home.height * 0.5));
        home.updateCurrentSystem(90.0);
        GalaxyMapSystem protectedHome = system(home.authoritativeGalaxyMapSnapshot(), home.activeSystemId());
        require(protectedHome.controlStatus() == SystemControlStatus.PROTECTED,
                "protected player home became capturable");

        GalaxyMapSystem corsairs = system(home.authoritativeGalaxyMapSnapshot(), StarSystems.CORSAIR_SYSTEM_ID);
        require(corsairs != null && Config.CORSAIRS_ID.equals(corsairs.controllerId()),
                "Corsair Den did not begin under Corsair control");
    }

    private static void validateStrategicDefinitions() {
        require(StarSystems.get("binary_forge").strategic().productionThroughput() >= 1.20,
                "Binary Forge lacks an industrial ownership identity");
        require(StarSystems.get("ice_belt").strategic().logisticsThroughput() >= 1.20,
                "Ice Belt lacks a logistics ownership identity");
        require(StarSystems.get("nebula_expanse").strategic().sensorRange() >= 1.30,
                "Nebula Expanse lacks a sensor ownership identity");
        require(StarSystems.get("ancient_graveyard").strategic().researchThroughput() >= 1.20,
                "Ancient Graveyard lacks a research ownership identity");
        SystemControlPoint nebula = new SystemControlPoint(StarSystems.get("nebula_expanse"));
        require(Math.abs(nebula.x - StarSystems.get("nebula_expanse").width() * 0.5) > 1,
                "template-specific control geometry was ignored");
    }

    private static void validateSensorConsumer() {
        PlayerRegistry.reset("P1", "Blue", 0x3388FF);
        PlayerRegistry.register("P1", "Blue", 0x3388FF, true);
        PlayerRegistry.register("P2", "Red", 0xFF5544, false);
        World world = new World("Sensor Territory", NO_NPCS, "nebula_expanse", false);
        PlayerRegistry.activate(world);
        SystemControlPoint point = new SystemControlPoint(StarSystems.get("nebula_expanse"));
        world.bases.put("P1:B1", new Base("P1:B1", "P1", Rules.DEFAULT_BASE, point.x, point.y));
        world.updateCurrentSystem(76.0);
        require("P1".equals(system(world.authoritativeGalaxyMapSnapshot(), world.activeSystemId()).controllerId()),
                "sensor validation system was not captured");
        Unit controller = new Unit("P1", 1, Rules.STARTING_SHIP, 100, 100);
        Unit outsider = new Unit("P2", 1, Rules.STARTING_SHIP, 100, 100);
        double controllerRange = IntelWarfareSystem.ordinaryUnitRange(world, controller);
        double outsiderRange = IntelWarfareSystem.ordinaryUnitRange(world, outsider);
        require(controllerRange > outsiderRange * 1.20,
                "strategic sensor bonus is not applied to authoritative sensor range");
    }

    private static void validateLogisticsConsumer() {
        PlayerRegistry.reset("P1", "Blue", 0x3388FF);
        PlayerRegistry.register("P1", "Blue", 0x3388FF, true);
        PlayerRegistry.register("P2", "Red", 0xFF5544, false);
        World world = new World("Logistics Territory", NO_NPCS, "ice_belt", false);
        PlayerRegistry.activate(world);
        SystemControlPoint point = new SystemControlPoint(StarSystems.get("ice_belt"));
        world.bases.put("P1:B1", new Base("P1:B1", "P1", Rules.DEFAULT_BASE, point.x, point.y));
        world.updateCurrentSystem(76.0);
        require("P1".equals(system(world.authoritativeGalaxyMapSnapshot(), world.activeSystemId()).controllerId()),
                "logistics validation system was not captured");

        Unit controller = new Unit("P1", 1, LogisticsSystem.SHUTTLE_TYPE, 100, 100);
        controller.logisticsRequestId = "VALIDATOR";
        controller.moveTo(2000, 100);
        Unit outsider = new Unit("P2", 1, LogisticsSystem.SHUTTLE_TYPE, 100, 100);
        outsider.logisticsRequestId = "VALIDATOR";
        outsider.moveTo(2000, 100);
        controller.updatePosition(1.0, world.width, world.height);
        outsider.updatePosition(1.0, world.width, world.height);
        require(controller.x - 100 > (outsider.x - 100) * 1.15,
                "strategic logistics bonus is not applied to logistics movement throughput");
    }

    private static GalaxyMapSystem system(GalaxyMapSnapshot snapshot, String id) {
        for (GalaxyMapSystem system : snapshot.systems()) if (id.equals(system.id())) return system;
        throw new IllegalStateException("Missing system " + id);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
