package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Issue293LogisticsRouteValidator {
    private Issue293LogisticsRouteValidator() { }

    public static void main(String[] args) {
        validateMultiHopDeliveryAndReturn();
        validateLaggingEscortHopAndRestore();
        validateCompetingRoutesAndReserve();
        validateProductionContention();
        validateTransportDestructionRecovery();
        validatePathRemovalRecovery();
        validateSaveRestoreReconciliation();
        validatePausedSaveRestore();
        validateStatusResyncFallback();
        validateManualOverrideAndLifecycleCommands();
        validateAuthorizationAndBounds();
        System.out.println("Issue 293 logistics route validation passed.");
    }

    private static void validateMultiHopDeliveryAndReturn() {
        Fixture fixture = fixture("Issue 293 multi-hop", 1_000, 100);
        Unit escort = armedEscort(fixture.world, 7002, fixture.source);
        String spec = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem, fixture.destination.id,
                List.of(Material.IRON), 100, 300, 200, 75,
                List.of(fixture.transport.key()), List.of(escort.key()), false);
        require(ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, spec),
                "multi-hop route creation was rejected");
        require(LogisticsRouteSystem.routeCount(fixture.world) == 1,
                "route was not registered");
        require(LogisticsRouteSystem.ownsTransport(fixture.world, fixture.transport)
                        && LogisticsRouteSystem.ownsEscort(fixture.world, escort.key()),
                "transport or escort assignment was not retained");

        LogisticsRouteSystem.update(fixture.world, 0.25);
        require(close(fixture.transport.inventory.getOrDefault(Material.IRON, 0.0), 200),
                "first shipment did not load the configured batch");
        require(fixture.source.inventory.getOrDefault(Material.IRON, 0.0) >= 100 - 0.001,
                "source reserve was violated during loading");

        moveConvoy(fixture.world, fixture.transport.key(), List.of(escort.key()), fixture.destinationSystem);
        fixture.world.activateSystem(fixture.destinationSystem);
        Unit deliveredTransport = fixture.world.units.get(fixture.transport.key());
        Base destination = fixture.world.bases.get(fixture.destination.id);
        require(deliveredTransport != null && destination != null, "convoy disappeared at destination");
        deliveredTransport.x = destination.x;
        deliveredTransport.y = destination.y;
        deliveredTransport.targetX = destination.x;
        deliveredTransport.targetY = destination.y;
        LogisticsRouteSystem.update(fixture.world, 100.0);
        require(destination.inventory.getOrDefault(Material.IRON, 0.0) >= 199.9,
                "physical cargo was not unloaded at the destination");
        require(deliveredTransport.cargoUsed() <= 0.05,
                "transport retained cargo after destination unload");

        moveConvoy(fixture.world, fixture.transport.key(), List.of(escort.key()), fixture.sourceSystem);
        require(fixture.sourceSystem.equals(fixture.world.ownerUnitLocations("SOLO").get(fixture.transport.key())),
                "empty convoy did not return to its source system");
        fixture.world.activateSystem(fixture.sourceSystem);
        LogisticsRouteSystem.update(fixture.world, 0.25);
        Unit returned = fixture.world.units.get(fixture.transport.key());
        require(returned != null && returned.inventory.getOrDefault(Material.IRON, 0.0) <= 100.1,
                "second shipment ignored destination in-flight/stock accounting");
    }

    private static void validateLaggingEscortHopAndRestore() {
        Fixture fixture = fixture("Issue 293 lagging escort", 900, 100);
        Unit escort = armedEscort(fixture.world, 7020, fixture.source);
        String spec = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem, fixture.destination.id,
                List.of(Material.IRON), 100, 300, 180, 70,
                List.of(fixture.transport.key()), List.of(escort.key()), false);
        require(ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, spec),
                "lagging-escort route creation was rejected");
        LogisticsRouteSystem.update(fixture.world, 0.25);

        List<String> path = LogisticsRouteSystem.pathForTest(
                fixture.world, fixture.sourceSystem, fixture.destinationSystem);
        require(path.size() >= 2, "lagging-escort route has no first hop");
        String firstHop = path.get(1);
        Unit transport = fixture.world.units.get(fixture.transport.key());
        require(transport != null, "lagging-escort transport missing before first hop");

        transport.x = transport.targetX;
        transport.y = transport.targetY;
        transport.wormholeCooldown = 0;
        LogisticsRouteSystem.update(fixture.world, 0.01);
        require(escort.logisticsRequestId.contains(":ESCORT:" + firstHop),
                "escort did not persist the convoy wormhole hop before lead transfer");
        require(fixture.world.transferTouchingShips("SOLO"),
                "lead transport failed to cross first wormhole");
        require(firstHop.equals(fixture.world.ownerUnitLocations("SOLO").get(transport.key())),
                "lead transport did not reach the first hop");
        require(fixture.sourceSystem.equals(fixture.world.ownerUnitLocations("SOLO").get(escort.key())),
                "escort unexpectedly crossed with the lead in lag test");

        Map<String,Object> galaxy = fixture.world.captureServerSaveGalaxy();
        Map<String,Object> runtime = fixture.world.captureServerSaveRuntime();
        World restored = new World("Issue 293 lagging escort restore", Set.of(), fixture.world.systemId(), false);
        PlayerRegistry.activate(restored);
        restored.restoreServerSaveGalaxy(galaxy);
        restored.restoreServerSaveRuntime(runtime);
        require(LogisticsRouteSystem.ownsEscort(restored, escort.key()),
                "escort assignment did not survive hop restore");

        restored.activateSystem(fixture.sourceSystem);
        Unit restoredEscort = restored.units.get(escort.key());
        require(restoredEscort != null, "lagging escort missing after restore");
        require(restoredEscort.logisticsRequestId.contains(":ESCORT:" + firstHop),
                "escort hop intent was not persisted with the unit");
        LogisticsRouteSystem.update(restored, 0.25);
        require(Calc.distance(restoredEscort.x, restoredEscort.y,
                        restoredEscort.targetX, restoredEscort.targetY) > 0.1,
                "lagging escort stopped instead of continuing to the convoy hop");
        restoredEscort.x = restoredEscort.targetX;
        restoredEscort.y = restoredEscort.targetY;
        restoredEscort.wormholeCooldown = 0;
        require(restored.transferTouchingShips("SOLO"),
                "lagging escort failed to follow the persisted wormhole hop");
        require(firstHop.equals(restored.ownerUnitLocations("SOLO").get(escort.key())),
                "lagging escort did not reunite with the lead system");
    }

    private static void validateCompetingRoutesAndReserve() {
        Fixture fixture = fixture("Issue 293 competing routes", 1_000, 850);
        Unit second = new Unit("SOLO", 7011, "hauler", fixture.source.x + 12, fixture.source.y + 8);
        fixture.world.units.put(second.key(), second);
        fixture.world.saveActiveSystem();

        String first = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem, fixture.destination.id,
                List.of(Material.IRON), 850, 500, 400, 90,
                List.of(fixture.transport.key()), List.of(), false);
        String secondSpec = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem, fixture.destination.id,
                List.of(Material.IRON), 850, 500, 400, 80,
                List.of(second.key()), List.of(), false);
        require(ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, first), "first competing route was rejected");
        require(ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, secondSpec), "second competing route was rejected");

        LogisticsRouteSystem.update(fixture.world, 0.25);
        double committed = fixture.transport.inventory.getOrDefault(Material.IRON, 0.0)
                + second.inventory.getOrDefault(Material.IRON, 0.0);
        require(committed <= 150.1,
                "competing routes spent below the configured source reserve");
        require(fixture.source.inventory.getOrDefault(Material.IRON, 0.0) >= 849.9,
                "source reserve was overspent by competing routes");
        require(close(committed + fixture.source.inventory.getOrDefault(Material.IRON, 0.0), 1_000),
                "route reservation duplicated or destroyed source inventory");
    }

    private static void validateProductionContention() {
        Fixture fixture = fixture("Issue 293 production contention", 600, 100);
        ShipType ship = Rules.ship("hauler");
        ShipLoadoutDefinition loadout = WeaponRules.defaultLoadout(ship.id);
        List<Cost> buildCost = WeaponRules.buildCost(ship, loadout);
        require(!buildCost.isEmpty(), "hauler build cost is empty in production-contention test");
        Material contested = buildCost.get(0).material();
        double productionSpend = 0;
        for (Cost cost : buildCost) {
            fixture.source.inventory.put(cost.material(),
                    Math.max(fixture.source.inventory.getOrDefault(cost.material(), 0.0), cost.amount() + 500));
            if (cost.material() == contested) productionSpend += cost.amount();
        }
        double starting = fixture.source.inventory.getOrDefault(contested, 0.0);
        require(ProductionSystem.enqueueShip(fixture.world, fixture.source, ship, loadout, false),
                "production reservation was rejected in contention test");
        require(close(fixture.source.inventory.getOrDefault(contested, 0.0), starting - productionSpend),
                "production did not reserve the expected contested inventory");

        String spec = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem, fixture.destination.id,
                List.of(contested), 100, 300, 300, 50,
                List.of(fixture.transport.key()), List.of(), false);
        require(ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, spec),
                "route creation failed after production reservation");
        LogisticsRouteSystem.update(fixture.world, 0.25);
        double carried = fixture.transport.inventory.getOrDefault(contested, 0.0);
        double remaining = fixture.source.inventory.getOrDefault(contested, 0.0);
        require(remaining >= 99.9, "route consumed the configured reserve after production claimed inventory");
        require(close(remaining + carried + productionSpend, starting),
                "production and route reservations double-spent or recreated contested inventory");
    }

    private static void validateTransportDestructionRecovery() {
        Fixture fixture = fixture("Issue 293 destroyed transport", 700, 100);
        String spec = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem, fixture.destination.id,
                List.of(Material.IRON), 100, 300, 180, 50,
                List.of(fixture.transport.key()), List.of(), false);
        require(ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, spec),
                "destroyed-transport route creation was rejected");
        LogisticsRouteSystem.update(fixture.world, 0.25);
        double sourceAfterLoad = fixture.source.inventory.getOrDefault(Material.IRON, 0.0);
        require(fixture.transport.cargoUsed() > 0.05, "destroyed-transport test did not load cargo");
        fixture.transport.hp = 0;
        fixture.world.updateCurrentSystem(0.25);
        require(close(fixture.source.inventory.getOrDefault(Material.IRON, 0.0), sourceAfterLoad),
                "destroyed transport recreated its cargo in the source hangar");
        require(onlyRoute(fixture.world, fixture.source).transportCount() == 0,
                "destroyed explicitly assigned transport remained attached to the route");
    }

    private static void validatePathRemovalRecovery() {
        Fixture fixture = fixture("Issue 293 path removal", 800, 100);
        String spec = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem, fixture.destination.id,
                List.of(Material.IRON), 100, 300, 180, 50,
                List.of(fixture.transport.key()), List.of(), false);
        require(ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, spec),
                "path-removal route creation was rejected");
        LogisticsRouteSystem.update(fixture.world, 0.25);
        double cargo = fixture.transport.cargoUsed();
        require(cargo > 0.05, "path-removal test did not load cargo");

        fixture.world.activateSystem(fixture.sourceSystem);
        fixture.world.wormholes.clear();
        LogisticsRouteSystem.update(fixture.world, 0.25);
        require(onlyRoute(fixture.world, fixture.source).phase() == LogisticsRouteSystem.RoutePhase.BLOCKED,
                "route did not block after its required wormhole gate was removed");
        require(close(fixture.transport.cargoUsed(), cargo),
                "path removal recreated or deleted physical in-transit cargo");
    }

    private static void validateSaveRestoreReconciliation() {
        Fixture fixture = fixture("Issue 293 persistence", 800, 100);
        String spec = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem, fixture.destination.id,
                List.of(Material.IRON), 100, 350, 180, 60,
                List.of(fixture.transport.key()), List.of(), false);
        require(ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, spec), "persistence route was rejected");
        LogisticsRouteSystem.update(fixture.world, 0.25);
        double carriedBefore = fixture.transport.inventory.getOrDefault(Material.IRON, 0.0);
        double sourceBefore = fixture.source.inventory.getOrDefault(Material.IRON, 0.0);
        require(carriedBefore > 0.05, "persistence test did not create an in-progress shipment");

        Map<String,Object> galaxy = fixture.world.captureServerSaveGalaxy();
        Map<String,Object> runtime = fixture.world.captureServerSaveRuntime();
        World restored = new World("Issue 293 restored", Set.of(), fixture.world.systemId(), false);
        PlayerRegistry.activate(restored);
        restored.restoreServerSaveGalaxy(galaxy);
        restored.restoreServerSaveRuntime(runtime);
        require(LogisticsRouteSystem.routeCount(restored) == 1,
                "route definition did not survive runtime restore");
        String location = restored.ownerUnitLocations("SOLO").get(fixture.transport.key());
        require(location != null, "assigned transport did not survive galaxy restore");
        restored.activateSystem(location);
        Unit restoredTransport = restored.units.get(fixture.transport.key());
        Base restoredSource = restored.bases.get(fixture.source.id);
        require(restoredTransport != null && restoredSource != null,
                "restored shipment assets are missing");
        require(close(restoredTransport.inventory.getOrDefault(Material.IRON, 0.0), carriedBefore),
                "restore did not trust authoritative physical ship cargo");
        require(close(restoredSource.inventory.getOrDefault(Material.IRON, 0.0), sourceBefore),
                "restore recreated cargo in the source inventory");
        LogisticsRouteSystem.update(restored, 0.25);
        require(close(restoredSource.inventory.getOrDefault(Material.IRON, 0.0), sourceBefore),
                "restore reconciliation double-dispatched an in-progress shipment");
    }

    private static void validatePausedSaveRestore() {
        Fixture fixture = fixture("Issue 293 paused restore", 600, 100);
        String spec = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem, fixture.destination.id,
                List.of(Material.IRON), 100, 300, 150, 50,
                List.of(fixture.transport.key()), List.of(), false);
        require(ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, spec), "paused-restore route was rejected");
        String routeId = onlyRoute(fixture.world, fixture.source).id();
        require(ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_PAUSE, routeId), "paused-restore pause was rejected");

        Map<String,Object> galaxy = fixture.world.captureServerSaveGalaxy();
        Map<String,Object> runtime = fixture.world.captureServerSaveRuntime();
        World restored = new World("Issue 293 paused restored", Set.of(), fixture.world.systemId(), false);
        PlayerRegistry.activate(restored);
        restored.restoreServerSaveGalaxy(galaxy);
        restored.restoreServerSaveRuntime(runtime);
        restored.activateSystem(fixture.sourceSystem);
        Base restoredSource = restored.bases.get(fixture.source.id);
        require(restoredSource != null, "paused-restore source is missing");
        require(onlyRoute(restored, restoredSource).phase() == LogisticsRouteSystem.RoutePhase.PAUSED,
                "paused route resumed unexpectedly after save/restore");
    }

    private static void validateStatusResyncFallback() {
        Fixture fixture = fixture("Issue 293 status resync", 600, 100);
        String spec = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem, fixture.destination.id,
                List.of(Material.IRON), 100, 300, 150, 55,
                List.of(fixture.transport.key()), List.of(), false);
        require(ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, spec), "status-resync route was rejected");
        LogisticsRouteSystem.update(fixture.world, 0.25);
        String status = fixture.source.logisticsStatus;
        List<LogisticsRouteSystem.RouteView> parsed = LogisticsRouteSystem.viewsFromStatus(status);
        require(parsed.size() == 1 && parsed.get(0).destinationSystemId().equals(fixture.destinationSystem),
                "authoritative station status did not contain a reconnect-safe route summary");
        LogisticsRouteSystem.clear(fixture.world);
        List<LogisticsRouteSystem.RouteView> fallback = LogisticsRouteSystem.viewsForSource(fixture.world, fixture.source);
        require(fallback.size() == 1 && fallback.get(0).id().equals(parsed.get(0).id()),
                "client-style status fallback could not reconstruct route state after runtime loss");
    }

    private static void validateManualOverrideAndLifecycleCommands() {
        Fixture fixture = fixture("Issue 293 lifecycle", 700, 100);
        String spec = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem, fixture.destination.id,
                List.of(Material.IRON), 100, 300, 150, 50,
                List.of(fixture.transport.key()), List.of(), false);
        require(ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, spec), "lifecycle route was rejected");
        String routeId = onlyRoute(fixture.world, fixture.source).id();

        require(ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_PAUSE, routeId), "pause was rejected");
        require(onlyRoute(fixture.world, fixture.source).phase() == LogisticsRouteSystem.RoutePhase.PAUSED,
                "pause did not update route state");
        require(ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_RESUME, routeId), "resume was rejected");

        LogisticsRouteSystem.update(fixture.world, 0.25);
        double cargo = fixture.transport.cargoUsed();
        require(cargo > 0.05, "manual override test did not load cargo");
        String unsafeEdit = LogisticsRouteSystem.encodeSpec(routeId, fixture.destinationSystem, fixture.destination.id,
                List.of(Material.COPPER), 100, 300, 150, 50,
                List.of(), List.of(), true);
        require(!ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_UPDATE, unsafeEdit),
                "route changed material rules while physical cargo was already in flight");
        require(close(fixture.transport.cargoUsed(), cargo),
                "rejected in-flight edit changed physical cargo");

        require(AUnitMove.apply(fixture.world, new MoveCommand("SOLO", fixture.transport.unitId,
                        fixture.transport.x + 60, fixture.transport.y + 20)),
                "manual move command was rejected");
        require(!LogisticsRouteSystem.ownsTransport(fixture.world, fixture.transport),
                "manual player command did not release route automation");
        require(close(fixture.transport.cargoUsed(), cargo),
                "manual override recreated or deleted physical cargo");

        require(ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_DELETE, routeId), "delete was rejected");
        require(LogisticsRouteSystem.routeCount(fixture.world) == 0,
                "deleted route remained registered");
    }

    private static void validateAuthorizationAndBounds() {
        Fixture fixture = fixture("Issue 293 authority", 500, 50);
        String valid = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem, fixture.destination.id,
                List.of(Material.IRON), 50, 200, 100, 50,
                List.of(fixture.transport.key()), List.of(), false);
        require(!ProductionCommands.apply(fixture.world, "OTHER", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, valid),
                "non-owner created a route from another player's station");
        require(!ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, "v1~bad"),
                "malformed route command was accepted");

        Base localDestination = new Base("SOLO:LOCAL_DEST", "SOLO", Rules.DEFAULT_BASE,
                fixture.source.x + 400, fixture.source.y + 200);
        fixture.world.bases.put(localDestination.id, localDestination);
        String localRoute = LogisticsRouteSystem.encodeSpec("", fixture.sourceSystem, localDestination.id,
                List.of(Material.IRON), 50, 200, 100, 50,
                List.of(fixture.transport.key()), List.of(), false);
        require(!ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, localRoute),
                "same-system route was accepted by the inter-system route controller");

        String keepOnCreate = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem, fixture.destination.id,
                List.of(Material.IRON), 50, 200, 100, 50,
                List.of(), List.of(), true);
        require(!ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, keepOnCreate),
                "KEEP assignment sentinel was accepted on route creation");

        List<Material> oversizedMaterials = new ArrayList<>();
        for (Material material : Material.values()) oversizedMaterials.add(material);
        while (oversizedMaterials.size() <= LogisticsRouteSystem.MAX_MATERIALS) {
            oversizedMaterials.add(Material.IRON);
        }
        String oversized = "v1~~" + fixture.destinationSystem + '~' + fixture.destination.id + '~'
                + oversizedMaterials.stream().map(Material::name).reduce((a, b) -> a + "," + b).orElse("")
                + "~0~200~100~50~" + fixture.transport.key() + "~NONE";
        require(!ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, oversized),
                "oversized material list was accepted");

        String impossibleSystem = LogisticsRouteSystem.encodeSpec("", "missing_system", fixture.destination.id,
                List.of(Material.IRON), 0, 200, 100, 50,
                List.of(fixture.transport.key()), List.of(), false);
        require(!ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, impossibleSystem),
                "route to a nonexistent system was accepted");
    }

    private static Fixture fixture(String name, double stock, double reserve) {
        PlayerRegistry.reset("SOLO", name, 0x50BEFF);
        World world = new World(name, Set.of(), StarSystems.DEFAULT_SYSTEM_ID);
        PlayerRegistry.activate(world);
        String sourceSystem = world.activeSystemId();
        Base source = world.bases.values().stream()
                .filter(base -> "SOLO".equals(base.playerId))
                .findFirst().orElseThrow(() -> new IllegalStateException("source base missing"));
        source.inventory.put(Material.IRON, stock);
        Unit transport = new Unit("SOLO", 7001, "hauler", source.x + 8, source.y + 8);
        world.units.put(transport.key(), transport);
        world.saveActiveSystem();

        String destinationSystem = distantSystem(world, sourceSystem);
        require(!destinationSystem.isBlank(), "test galaxy has no reachable remote system");
        List<String> path = LogisticsRouteSystem.pathForTest(world, sourceSystem, destinationSystem);
        require(path.size() >= 2, "test destination is not reachable through wormholes");
        String previous = world.activeSystemId();
        world.activateSystem(destinationSystem);
        Base destination = new Base("SOLO:ROUTE_DEST", "SOLO", Rules.DEFAULT_BASE,
                world.width * 0.48, world.height * 0.52);
        world.bases.put(destination.id, destination);
        world.saveActiveSystem();
        world.activateSystem(previous);
        require(source.inventory.getOrDefault(Material.IRON, 0.0) >= reserve,
                "fixture stock is below requested reserve");
        return new Fixture(world, sourceSystem, source, destinationSystem, destination, transport);
    }

    private static String distantSystem(World world, String sourceSystem) {
        String best = "";
        int bestHops = 0;
        for (GalaxyMapSystem system : world.galaxyMapSnapshot().systems()) {
            if (system == null || system.id().equals(sourceSystem)) continue;
            List<String> path = LogisticsRouteSystem.pathForTest(world, sourceSystem, system.id());
            if (path.size() > bestHops) {
                bestHops = path.size();
                best = system.id();
            }
        }
        return best;
    }

    private static Unit armedEscort(World world, int unitId, Base source) {
        Unit escort = new Unit("SOLO", unitId, "destroyer", source.x + 20, source.y + 20);
        escort.loadoutId = "destroyer_rail_escort";
        world.units.put(escort.key(), escort);
        require(WeaponRules.armed(world, escort), "escort fixture is not armed");
        world.saveActiveSystem();
        return escort;
    }

    private static void moveConvoy(World world, String transportKey, List<String> escortKeys, String targetSystem) {
        for (int guard = 0; guard < 64; guard++) {
            String current = world.ownerUnitLocations("SOLO").get(transportKey);
            require(current != null, "transport vanished during wormhole transit");
            if (targetSystem.equals(current)) return;
            world.activateSystem(current);
            LogisticsRouteSystem.update(world, 0.25);
            Unit transport = world.units.get(transportKey);
            require(transport != null, "transport missing from its reported system");
            transport.wormholeCooldown = 0;
            transport.x = transport.targetX;
            transport.y = transport.targetY;
            LogisticsRouteSystem.update(world, 0.01);
            for (String escortKey : escortKeys) {
                Unit escort = world.units.get(escortKey);
                if (escort == null) continue;
                escort.wormholeCooldown = 0;
                escort.x = escort.targetX;
                escort.y = escort.targetY;
            }
            require(world.transferTouchingShips("SOLO"),
                    "convoy failed to cross its assigned wormhole");
        }
        throw new IllegalStateException("convoy did not reach " + targetSystem);
    }

    private static LogisticsRouteSystem.RouteView onlyRoute(World world, Base source) {
        List<LogisticsRouteSystem.RouteView> routes = LogisticsRouteSystem.viewsForSource(world, source);
        require(routes.size() == 1, "expected exactly one route but found " + routes.size());
        return routes.get(0);
    }

    private static boolean close(double a, double b) { return Math.abs(a - b) <= 0.15; }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Fixture(World world, String sourceSystem, Base source,
                           String destinationSystem, Base destination, Unit transport) { }
}
