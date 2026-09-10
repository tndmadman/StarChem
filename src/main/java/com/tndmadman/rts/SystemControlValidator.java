package com.tndmadman.rts;

import java.util.List;
import java.util.Map;
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
        validateRepairConsumer();
        validateSupplyTopologyAndRecompute();
        validateStrategicSummaryBoundariesAndPersistence();

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

    private static void validateRepairConsumer() {
        PlayerRegistry.reset("P1", "Blue", 0x3388FF);
        PlayerRegistry.register("P1", "Blue", 0x3388FF, true);
        PlayerRegistry.register("P2", "Red", 0xFF5544, false);
        World world = new World("Repair Territory", NO_NPCS, "ice_belt", false);
        PlayerRegistry.activate(world);
        SystemControlPoint point = new SystemControlPoint(StarSystems.get("ice_belt"));
        world.bases.put("P1:B1", new Base("P1:B1", "P1", Rules.DEFAULT_BASE, point.x, point.y));
        world.updateCurrentSystem(76.0);
        require("P1".equals(system(world.authoritativeGalaxyMapSnapshot(), world.activeSystemId()).controllerId()),
                "repair validation system was not captured");
        double controllerRate = NpcRecoverySystem.repairRateForTest(world, "P1");
        double outsiderRate = NpcRecoverySystem.repairRateForTest(world, "P2");
        require(controllerRate > outsiderRate * 1.05,
                "strategic repair bonus is not applied to authoritative station repair throughput");
    }

    private static void validateSupplyTopologyAndRecompute() {
        Map<String, StrategicSupplyState> connected = StrategicSupplyService.deriveStatesForTest(
                Set.of("A", "B", "C"), Set.of("A"), Set.of("C"),
                List.of(new GalaxyMapLink("A", "B"), new GalaxyMapLink("B", "C")));
        require(connected.get("A") == StrategicSupplyState.SUPPLIED
                        && connected.get("B") == StrategicSupplyState.SUPPLIED
                        && connected.get("C") == StrategicSupplyState.SUPPLIED,
                "connected controlled topology did not propagate supply");

        Map<String, StrategicSupplyState> blockaded = StrategicSupplyService.deriveStatesForTest(
                Set.of("A", "B", "C"), Set.of("A"), Set.of("C"),
                List.of(new GalaxyMapLink("A", "B")));
        require(blockaded.get("B") == StrategicSupplyState.SUPPLIED,
                "reachable territory became unsupplied after an unrelated link removal");
        require(blockaded.get("C") == StrategicSupplyState.STRAINED,
                "cut-off territory with local logistics infrastructure was not strained");

        Map<String, StrategicSupplyState> unsupported = StrategicSupplyService.deriveStatesForTest(
                Set.of("A", "B", "C"), Set.of("A"), Set.of(),
                List.of(new GalaxyMapLink("A", "B")));
        require(unsupported.get("C") == StrategicSupplyState.ISOLATED,
                "cut-off territory without local logistics infrastructure was not isolated");

        PlayerRegistry.reset("P1", "Blue", 0x3388FF);
        PlayerRegistry.register("P1", "Blue", 0x3388FF, true);
        World world = new World("Supply Cache", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        double x = world.width * 0.5;
        double y = world.height * 0.5;
        world.bases.put("P1:B1", new Base("P1:B1", "P1", Rules.DEFAULT_BASE, x, y));
        world.updateCurrentSystem(76.0);
        StrategicSupplyService.clear(world);
        require(SystemControlBonuses.supplyState(world, "P1") == StrategicSupplyState.SUPPLIED,
                "supply cache validation world was not supplied");
        int first = StrategicSupplyService.recomputeCountForTest(world);
        SystemControlBonuses.supplyState(world, "P1");
        require(StrategicSupplyService.recomputeCountForTest(world) == first,
                "cached supply unexpectedly rescanned the galaxy");

        Unit deployer = new Unit("P1", 77, "station_builder", 120, 120);
        deployer.basePackageType = "manufacturing";
        world.units.put(deployer.key(), deployer);
        require(world.placePackage(deployer),
                "strategic infrastructure package could not be placed during cache validation");
        SystemControlBonuses.supplyState(world, "P1");
        require(StrategicSupplyService.recomputeCountForTest(world) == first + 1,
                "strategic infrastructure placement did not invalidate cached supply");

        StrategicSupplyService.invalidate(world);
        SystemControlBonuses.supplyState(world, "P1");
        require(StrategicSupplyService.recomputeCountForTest(world) == first + 2,
                "explicit topology/control invalidation did not recompute supply");
    }

    private static void validateStrategicSummaryBoundariesAndPersistence() {
        PlayerRegistry.reset("P1", "Blue", 0x3388FF);
        PlayerRegistry.register("P1", "Blue", 0x3388FF, true);
        PlayerRegistry.register("P2", "Red", 0xFF5544, false);
        World world = new World("Strategic Summary", NO_NPCS, "ice_belt", false);
        PlayerRegistry.activate(world);
        SystemControlPoint point = new SystemControlPoint(StarSystems.get("ice_belt"));
        BaseType ownInfrastructure = Rules.findBase("manufacturing");
        BaseType enemyInfrastructure = Rules.findBase("shipyard");
        require(ownInfrastructure != null && enemyInfrastructure != null,
                "strategic infrastructure station definitions are missing");

        world.bases.put("P1:B1", new Base("P1:B1", "P1", "manufacturing", point.x, point.y));
        world.bases.put("P2:B9", new Base("P2:B9", "P2", "shipyard", world.width - 20, world.height - 20));
        world.updateCurrentSystem(76.0);
        String systemId = world.activeSystemId();
        require("P1".equals(system(world.authoritativeGalaxyMapSnapshot(), systemId).controllerId()),
                "strategic summary validation system was not captured");

        StrategicSummarySnapshot snapshot = StrategicSummaryService.captureFresh(world, "P1");
        StrategicSystemRow row = summarySystem(snapshot, systemId);
        require(row.controlled() && StrategicSupplyState.SUPPLIED.name().equals(row.supply()),
                "owner strategic summary did not expose controlled supply state");
        require(row.infrastructure().contains(ownInfrastructure.name),
                "owner strategic infrastructure was missing from strategic summary");
        require(!row.infrastructure().contains(enemyInfrastructure.name),
                "enemy strategic infrastructure leaked into owner strategic summary");
        for (StrategicStationRow station : snapshot.stations()) {
            require(station.baseId().startsWith("P1:"),
                    "foreign station leaked into owner strategic summary");
        }

        String token = StrategicSummaryWire.encodeToken(snapshot);
        StrategicSummarySnapshot decoded = StrategicSummaryWire.decodeToken(token);
        StrategicSystemRow decodedRow = summarySystem(decoded, systemId);
        require(decoded.ownerId().equals("P1")
                        && decodedRow.supply().equals(row.supply())
                        && decodedRow.infrastructure().equals(row.infrastructure()),
                "strategic supply/infrastructure state did not survive multiplayer wire encoding");
        for (StrategicStationRow station : decoded.stations()) {
            require(station.baseId().startsWith("P1:"),
                    "foreign station appeared after strategic summary wire decoding");
        }

        world.saveActiveSystem();
        Map<String,Object> save = world.captureServerSaveGalaxy();
        World restored = new World("Strategic Summary Restore", NO_NPCS, "ice_belt", false);
        PlayerRegistry.activate(restored);
        restored.restoreServerSaveGalaxy(save);
        StrategicSupplyService.clear(restored);
        StrategicSummaryService.clear(restored);
        StrategicSummarySnapshot restoredSnapshot = StrategicSummaryService.captureFresh(restored, "P1");
        StrategicSystemRow restoredRow = summarySystem(restoredSnapshot, systemId);
        require(restoredRow.controlled()
                        && StrategicSupplyState.SUPPLIED.name().equals(restoredRow.supply())
                        && restoredRow.infrastructure().contains(ownInfrastructure.name),
                "control/infrastructure/derived supply state did not survive save and reload");
        require(!restoredRow.infrastructure().contains(enemyInfrastructure.name),
                "enemy infrastructure leaked after save and reload");
    }

    private static GalaxyMapSystem system(GalaxyMapSnapshot snapshot, String id) {
        for (GalaxyMapSystem system : snapshot.systems()) if (id.equals(system.id())) return system;
        throw new IllegalStateException("Missing system " + id);
    }

    private static StrategicSystemRow summarySystem(StrategicSummarySnapshot snapshot, String id) {
        for (StrategicSystemRow row : snapshot.systems()) if (id.equals(row.systemId())) return row;
        throw new IllegalStateException("Missing strategic system " + id);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
