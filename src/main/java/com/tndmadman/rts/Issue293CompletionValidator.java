package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Final acceptance coverage for issue #293 beyond the original implementation validator. */
final class Issue293CompletionValidator {
    private Issue293CompletionValidator() { }

    static void run() {
        validateSnapshotReconnectResync();
        validateEndpointDestructionAndResumeGuard();
        validateAuthoritativeTopologyLossAndRecovery();
        validateRemovedDestinationSystemPauses();
        validateOperationalPhaseSaveRestore();
        validateNaturalConvoyAndSchedulerWake();
        validateSavedStateBoundsAndAssignmentConflicts();
        validateAdditionalAuthorizationAndBounds();
    }

    private static void validateSnapshotReconnectResync() {
        Fixture fixture = fixture("Issue 293 network resync", 600, 100);
        createRoute(fixture, List.of(fixture.transport.key()), List.of(), 100, 300, 150, 55);
        LogisticsRouteSystem.update(fixture.world, 0.25);
        LogisticsRouteSystem.RouteView serverView = onlyRoute(fixture.world, fixture.source);

        Snapshot firstWire = SnapshotReader.read(SnapshotWriter.write(WorldNetAccess.snapshot(fixture.world, 1)));
        PlayerRegistry.reset("SOLO", "Issue 293 reconnect client", 0x50BEFF);
        World client = new World("Issue 293 reconnect client", disabledNpcFactions(),
                fixture.world.systemId(), false);
        PlayerRegistry.activate(client);
        WorldNetAccess.applyFullView(client, firstWire);
        LogisticsRouteSystem.clear(client);
        Base clientSource = client.bases.get(fixture.source.id);
        require(clientSource != null, "reconnect snapshot omitted the route source station");
        LogisticsRouteSystem.RouteView clientView = onlyRoute(client, clientSource);
        require(clientView.id().equals(serverView.id())
                        && clientView.phase() == serverView.phase()
                        && clientView.condition() == serverView.condition()
                        && clientView.destinationSystemId().equals(fixture.destinationSystem),
                "client could not reconstruct the authoritative route from the real snapshot wire");

        PlayerRegistry.activate(fixture.world);
        require(ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_PAUSE, serverView.id()),
                "server pause failed during reconnect validation");
        Snapshot pausedWire = SnapshotReader.read(SnapshotWriter.write(WorldNetAccess.snapshot(fixture.world, 2)));
        PlayerRegistry.activate(client);
        WorldNetAccess.applyFullView(client, pausedWire);
        LogisticsRouteSystem.clear(client);
        clientSource = client.bases.get(fixture.source.id);
        require(clientSource != null && onlyRoute(client, clientSource).phase() == LogisticsRouteSystem.RoutePhase.PAUSED,
                "client reconnect/resync did not receive the updated paused route state");
    }

    private static void validateEndpointDestructionAndResumeGuard() {
        Fixture sourceFixture = fixture("Issue 293 source destroyed", 500, 100);
        createRoute(sourceFixture, List.of(sourceFixture.transport.key()), List.of(), 100, 250, 150, 50);
        sourceFixture.source.hp = 0;
        LogisticsRouteSystem.update(sourceFixture.world, 0.25);
        LogisticsRouteSystem.RouteView sourceView = onlyRoute(sourceFixture.world, sourceFixture.source);
        require(sourceView.phase() == LogisticsRouteSystem.RoutePhase.PAUSED
                        && sourceView.condition() == LogisticsRouteSystem.RouteCondition.SOURCE_UNAVAILABLE,
                "destroyed source station did not structurally pause the route");
        require(sourceFixture.transport.cargoUsed() <= 0.05,
                "route loaded cargo after its source station was destroyed");

        Fixture destinationFixture = fixture("Issue 293 destination destroyed", 700, 100);
        createRoute(destinationFixture, List.of(destinationFixture.transport.key()), List.of(), 100, 300, 180, 50);
        LogisticsRouteSystem.update(destinationFixture.world, 0.25);
        double cargoBefore = destinationFixture.transport.cargoUsed();
        double sourceAfterLoad = destinationFixture.source.inventory.getOrDefault(Material.IRON, 0.0);
        require(cargoBefore > 0.05, "destination-destruction fixture did not load cargo");

        destinationFixture.world.activateSystem(destinationFixture.destinationSystem);
        Base destination = destinationFixture.world.bases.get(destinationFixture.destination.id);
        require(destination != null, "destination station disappeared before destruction test");
        destination.hp = 0;
        LogisticsRouteSystem.update(destinationFixture.world, 0.25);
        destinationFixture.world.activateSystem(destinationFixture.sourceSystem);
        Base source = destinationFixture.world.bases.get(destinationFixture.source.id);
        Unit transport = destinationFixture.world.units.get(destinationFixture.transport.key());
        LogisticsRouteSystem.RouteView destinationView = onlyRoute(destinationFixture.world, source);
        require(destinationView.phase() == LogisticsRouteSystem.RoutePhase.PAUSED
                        && destinationView.condition() == LogisticsRouteSystem.RouteCondition.DESTINATION_UNAVAILABLE,
                "destroyed destination station did not structurally pause the route");
        require(transport != null && close(transport.cargoUsed(), cargoBefore),
                "destroyed destination changed physical cargo still carried by the transport");
        require(close(source.inventory.getOrDefault(Material.IRON, 0.0), sourceAfterLoad),
                "destroyed destination refunded in-transit cargo to the source");
        require(!ProductionCommands.apply(destinationFixture.world, "SOLO", "CONTROL", source.id,
                        LogisticsRouteSystem.COMMAND_RESUME, destinationView.id()),
                "route resumed even though its destination station remained destroyed");
    }

    private static void validateAuthoritativeTopologyLossAndRecovery() {
        Fixture fixture = fixture("Issue 293 authoritative path loss", 650, 100);
        createRoute(fixture, List.of(fixture.transport.key()), List.of(), 100, 300, 180, 50);
        LogisticsRouteSystem.update(fixture.world, 0.25);
        double cargo = fixture.transport.cargoUsed();
        require(cargo > 0.05, "authoritative path-loss fixture did not load cargo");

        Map<String,Object> originalGalaxy = fixture.world.captureServerSaveGalaxy();
        Map<String,Object> brokenGalaxy = fixture.world.captureServerSaveGalaxy();
        Map<String,Object> runtime = fixture.world.captureServerSaveRuntime();
        isolateSystem(brokenGalaxy, fixture.sourceSystem);

        World restored = restore("Issue 293 isolated topology", fixture, brokenGalaxy, runtime);
        restored.activateSystem(fixture.sourceSystem);
        LogisticsRouteSystem.update(restored, 0.25);
        Base source = restored.bases.get(fixture.source.id);
        Unit transport = restored.units.get(fixture.transport.key());
        LogisticsRouteSystem.RouteView blocked = onlyRoute(restored, source);
        require(blocked.phase() == LogisticsRouteSystem.RoutePhase.BLOCKED
                        && blocked.condition() == LogisticsRouteSystem.RouteCondition.NO_PATH,
                "authoritatively removed wormhole links did not block the route");
        require(transport != null && close(transport.cargoUsed(), cargo),
                "authoritative path loss changed in-transit physical cargo");

        restored.restoreServerSaveGalaxy(originalGalaxy);
        restored.restoreServerSaveRuntime(runtime);
        restored.activateSystem(fixture.sourceSystem);
        LogisticsRouteSystem.update(restored, 0.25);
        source = restored.bases.get(fixture.source.id);
        LogisticsRouteSystem.RouteView recovered = onlyRoute(restored, source);
        require(recovered.phase() != LogisticsRouteSystem.RoutePhase.BLOCKED
                        && recovered.condition() == LogisticsRouteSystem.RouteCondition.NONE,
                "route did not recover after authoritative wormhole topology returned");
    }

    private static void validateRemovedDestinationSystemPauses() {
        Fixture fixture = fixture("Issue 293 removed destination", 650, 100);
        createRoute(fixture, List.of(fixture.transport.key()), List.of(), 100, 300, 180, 50);
        LogisticsRouteSystem.update(fixture.world, 0.25);
        double cargo = fixture.transport.cargoUsed();
        Map<String,Object> galaxy = fixture.world.captureServerSaveGalaxy();
        Map<String,Object> runtime = fixture.world.captureServerSaveRuntime();
        removeSystem(galaxy, fixture.destinationSystem);

        World restored = restore("Issue 293 removed system restore", fixture, galaxy, runtime);
        restored.activateSystem(fixture.sourceSystem);
        LogisticsRouteSystem.update(restored, 0.25);
        Base source = restored.bases.get(fixture.source.id);
        Unit transport = restored.units.get(fixture.transport.key());
        LogisticsRouteSystem.RouteView view = onlyRoute(restored, source);
        require(view.phase() == LogisticsRouteSystem.RoutePhase.PAUSED
                        && view.condition() == LogisticsRouteSystem.RouteCondition.DESTINATION_SYSTEM_MISSING,
                "removed destination system did not structurally pause the route");
        require(transport != null && close(transport.cargoUsed(), cargo),
                "removed destination system changed physical in-transit cargo");
    }

    private static void validateOperationalPhaseSaveRestore() {
        Fixture waiting = fixture("Issue 293 waiting restore", 500, 100);
        waiting.world.activateSystem(waiting.destinationSystem);
        Base waitingDestination = waiting.world.bases.get(waiting.destination.id);
        waitingDestination.inventory.put(Material.IRON, 300.0);
        waiting.world.saveActiveSystem();
        waiting.world.activateSystem(waiting.sourceSystem);
        createRoute(waiting, List.of(waiting.transport.key()), List.of(), 100, 300, 150, 50);
        LogisticsRouteSystem.update(waiting.world, 0.25);
        requireRestoredPhase(waiting, LogisticsRouteSystem.RoutePhase.WAITING, waiting.sourceSystem);

        Fixture loading = fixture("Issue 293 loading restore", 500, 100);
        loading.transport.x = loading.source.x + 2_500;
        loading.transport.y = loading.source.y + 1_400;
        loading.world.saveActiveSystem();
        createRoute(loading, List.of(loading.transport.key()), List.of(), 100, 300, 150, 50);
        LogisticsRouteSystem.update(loading.world, 0.25);
        require(onlyRoute(loading.world, loading.source).phase() == LogisticsRouteSystem.RoutePhase.LOADING,
                "loading fixture did not enter LOADING");
        requireRestoredPhase(loading, LogisticsRouteSystem.RoutePhase.LOADING, loading.sourceSystem);

        Fixture outbound = fixture("Issue 293 outbound restore", 500, 100);
        createRoute(outbound, List.of(outbound.transport.key()), List.of(), 100, 300, 150, 50);
        LogisticsRouteSystem.update(outbound.world, 0.25);
        require(onlyRoute(outbound.world, outbound.source).phase() == LogisticsRouteSystem.RoutePhase.OUTBOUND,
                "outbound fixture did not enter OUTBOUND");
        requireRestoredPhase(outbound, LogisticsRouteSystem.RoutePhase.OUTBOUND, outbound.sourceSystem);

        Fixture unloading = fixture("Issue 293 unloading restore", 500, 100);
        createRoute(unloading, List.of(unloading.transport.key()), List.of(), 100, 300, 150, 50);
        LogisticsRouteSystem.update(unloading.world, 0.25);
        moveConvoy(unloading.world, unloading.transport.key(), unloading.destinationSystem);
        unloading.world.activateSystem(unloading.destinationSystem);
        LogisticsRouteSystem.update(unloading.world, 0.25);
        require(routeViewFromSource(unloading).phase() == LogisticsRouteSystem.RoutePhase.UNLOADING,
                "unloading fixture did not enter UNLOADING");
        requireRestoredPhase(unloading, LogisticsRouteSystem.RoutePhase.UNLOADING, unloading.destinationSystem);

        Fixture returning = fixture("Issue 293 returning restore", 500, 100);
        createRoute(returning, List.of(returning.transport.key()), List.of(), 100, 300, 150, 50);
        LogisticsRouteSystem.update(returning.world, 0.25);
        moveConvoy(returning.world, returning.transport.key(), returning.destinationSystem);
        returning.world.activateSystem(returning.destinationSystem);
        Unit returnedTransport = returning.world.units.get(returning.transport.key());
        Base returnedDestination = returning.world.bases.get(returning.destination.id);
        returnedTransport.x = returnedDestination.x;
        returnedTransport.y = returnedDestination.y;
        returnedTransport.targetX = returnedDestination.x;
        returnedTransport.targetY = returnedDestination.y;
        LogisticsRouteSystem.update(returning.world, 100.0);
        require(routeViewFromSource(returning).phase() == LogisticsRouteSystem.RoutePhase.RETURNING,
                "returning fixture did not enter RETURNING");
        requireRestoredPhase(returning, LogisticsRouteSystem.RoutePhase.RETURNING, returning.destinationSystem);
    }

    private static void validateNaturalConvoyAndSchedulerWake() {
        Fixture fixture = fixture("Issue 293 natural convoy", 300, 100);
        Unit escort = armedEscort(fixture.world, 7300, fixture.source, "SOLO");
        createRoute(fixture, List.of(fixture.transport.key()), List.of(escort.key()), 100, 200, 200, 80);

        AuthoritativeSystemScheduler scheduler = new AuthoritativeSystemScheduler();
        Set<String> transportSystems = new LinkedHashSet<>();
        Set<String> escortSystems = new LinkedHashSet<>();
        boolean delivered = false;
        boolean returned = false;
        boolean sawIntermediateHot = false;
        for (int step = 0; step < 20_000; step++) {
            scheduler.update(fixture.world, 0.25, fixture.world::authoritativeGalaxyMapSnapshot);
            Map<String,String> locations = fixture.world.ownerUnitLocations("SOLO");
            String transportSystem = locations.get(fixture.transport.key());
            String escortSystem = locations.get(escort.key());
            if (transportSystem != null) transportSystems.add(transportSystem);
            if (escortSystem != null) escortSystems.add(escortSystem);

            if (transportSystem != null && !transportSystem.equals(fixture.sourceSystem)
                    && !transportSystem.equals(fixture.destinationSystem)) {
                String previous = fixture.world.activeSystemId();
                fixture.world.activateSystem(transportSystem);
                sawIntermediateHot |= SystemSimulationScheduler.tier(fixture.world)
                        == SystemSimulationScheduler.SimulationTier.HOT;
                fixture.world.activateSystem(previous);
            }

            if (step % 20 == 0) {
                String previous = fixture.world.activeSystemId();
                fixture.world.activateSystem(fixture.destinationSystem);
                Base destination = fixture.world.bases.get(fixture.destination.id);
                delivered |= destination != null
                        && destination.inventory.getOrDefault(Material.IRON, 0.0) >= 199.9;
                fixture.world.activateSystem(previous);
            }
            if (delivered && fixture.sourceSystem.equals(transportSystem)) {
                String previous = fixture.world.activeSystemId();
                fixture.world.activateSystem(fixture.sourceSystem);
                Unit transport = fixture.world.units.get(fixture.transport.key());
                returned = transport != null && transport.cargoUsed() <= 0.05;
                fixture.world.activateSystem(previous);
                if (returned) break;
            }
        }
        require(delivered, "natural convoy never delivered its shipment without teleport assistance");
        require(returned, "natural convoy did not return to its source after delivery");
        require(transportSystems.size() >= 2, "natural transport never crossed a wormhole");
        require(escortSystems.size() >= 2, "natural escort never crossed a wormhole with the convoy");
        require(sawIntermediateHot || LogisticsRouteSystem.pathForTest(
                        fixture.world, fixture.sourceSystem, fixture.destinationSystem).size() == 2,
                "active convoy did not wake an intermediate system to HOT simulation");
    }

    private static void validateSavedStateBoundsAndAssignmentConflicts() {
        Fixture fixture = fixture("Issue 293 saved bounds", 500, 100);
        createRoute(fixture, List.of(fixture.transport.key()), List.of(), 100, 300, 150, 50);
        Map<String,Object> runtime = fixture.world.captureServerSaveRuntime();
        Map<String,Object> logistics = map(runtime.get("logisticsRoutes"));
        Map<String,Object> template = map(list(logistics.get("routes")).get(0));

        List<Object> tooMany = new ArrayList<>();
        for (int i = 0; i < LogisticsRouteSystem.MAX_ROUTES_PER_PLAYER + 1; i++) {
            Map<String,Object> row = copyMap(template);
            row.put("id", "LR_CAP_" + i);
            row.put("autoPool", true);
            row.put("transportKeys", new ArrayList<>());
            row.put("escortKeys", new ArrayList<>());
            tooMany.add(row);
        }
        LogisticsRouteSystem.clear(fixture.world);
        LogisticsRouteSystem.restore(fixture.world, Map.of("nextRouteId", 500L, "routes", tooMany));
        require(LogisticsRouteSystem.routeCount(fixture.world) == LogisticsRouteSystem.MAX_ROUTES_PER_PLAYER,
                "restore exceeded the per-player route cap");

        Map<String,Object> oversized = copyMap(template);
        List<Object> oversizedTransports = new ArrayList<>();
        for (int i = 0; i <= LogisticsRouteSystem.MAX_TRANSPORTS; i++) oversizedTransports.add("SOLO:" + (8000 + i));
        oversized.put("transportKeys", oversizedTransports);
        LogisticsRouteSystem.clear(fixture.world);
        LogisticsRouteSystem.restore(fixture.world, Map.of("routes", List.of(oversized)));
        require(LogisticsRouteSystem.routeCount(fixture.world) == 0,
                "restore silently truncated an oversized saved transport list instead of rejecting it");

        Map<String,Object> high = copyMap(template);
        high.put("id", "LR_HIGH");
        high.put("priority", 90);
        high.put("transportKeys", List.of(fixture.transport.key()));
        Map<String,Object> low = copyMap(template);
        low.put("id", "LR_LOW");
        low.put("priority", 10);
        low.put("transportKeys", List.of(fixture.transport.key()));
        LogisticsRouteSystem.restore(fixture.world, Map.of("routes", List.of(low, high)));
        fixture.world.activateSystem(fixture.sourceSystem);
        List<LogisticsRouteSystem.RouteView> views = LogisticsRouteSystem.viewsForSource(fixture.world, fixture.source);
        LogisticsRouteSystem.RouteView highView = find(views, "LR_HIGH");
        LogisticsRouteSystem.RouteView lowView = find(views, "LR_LOW");
        require(highView != null && highView.transportCount() == 1,
                "higher-priority route did not win a restored ship-assignment conflict");
        require(lowView != null && lowView.transportCount() == 0
                        && lowView.condition() == LogisticsRouteSystem.RouteCondition.ASSIGNMENT_CONFLICT,
                "lower-priority restored route did not deterministically release the conflicting ship");
    }

    private static void validateAdditionalAuthorizationAndBounds() {
        Fixture fixture = fixture("Issue 293 extra authority", 500, 50);
        PlayerRegistry.register("OTHER", "Other player", 0xFF5F55, false);

        fixture.world.activateSystem(fixture.destinationSystem);
        Base foreignDestination = new Base("OTHER:ROUTE_DEST", "OTHER", Rules.DEFAULT_BASE,
                fixture.world.width * 0.42, fixture.world.height * 0.46);
        fixture.world.bases.put(foreignDestination.id, foreignDestination);
        fixture.world.saveActiveSystem();
        fixture.world.activateSystem(fixture.sourceSystem);

        String foreignDestinationSpec = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem,
                foreignDestination.id, List.of(Material.IRON), 50, 200, 100, 50,
                List.of(fixture.transport.key()), List.of(), false);
        require(!ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, foreignDestinationSpec),
                "route accepted another player's destination station");

        Unit foreignTransport = new Unit("OTHER", 8100, "hauler", fixture.source.x + 30, fixture.source.y + 30);
        fixture.world.units.put(foreignTransport.key(), foreignTransport);
        Unit foreignEscort = armedEscort(fixture.world, 8101, fixture.source, "OTHER");
        String foreignTransportSpec = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem,
                fixture.destination.id, List.of(Material.IRON), 50, 200, 100, 50,
                List.of(foreignTransport.key()), List.of(), false);
        require(!ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, foreignTransportSpec),
                "route accepted another player's transport");
        String foreignEscortSpec = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem,
                fixture.destination.id, List.of(Material.IRON), 50, 200, 100, 50,
                List.of(fixture.transport.key()), List.of(foreignEscort.key()), false);
        require(!ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, foreignEscortSpec),
                "route accepted another player's escort");

        String unknownTransport = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem,
                fixture.destination.id, List.of(Material.IRON), 50, 200, 100, 50,
                List.of("SOLO:999999"), List.of(), false);
        require(!ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, unknownTransport),
                "route accepted an unknown transport ID");
        String unknownEscort = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem,
                fixture.destination.id, List.of(Material.IRON), 50, 200, 100, 50,
                List.of(fixture.transport.key()), List.of("SOLO:999998"), false);
        require(!ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, unknownEscort),
                "route accepted an unknown escort ID");
        String sameShip = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem,
                fixture.destination.id, List.of(Material.IRON), 50, 200, 100, 50,
                List.of(fixture.transport.key()), List.of(fixture.transport.key()), false);
        require(!ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, sameShip),
                "route accepted the same ship as both transport and escort");

        List<String> tooManyTransports = new ArrayList<>();
        for (int i = 0; i <= LogisticsRouteSystem.MAX_TRANSPORTS; i++) tooManyTransports.add("SOLO:" + (8200 + i));
        String transportOverflow = "v1~~" + fixture.destinationSystem + '~' + fixture.destination.id
                + "~IRON~50~200~100~50~" + String.join(",", tooManyTransports) + "~NONE";
        require(!ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, transportOverflow),
                "route accepted more than the maximum transport count");

        List<String> tooManyEscorts = new ArrayList<>();
        for (int i = 0; i <= LogisticsRouteSystem.MAX_ESCORTS; i++) tooManyEscorts.add("SOLO:" + (8300 + i));
        String escortOverflow = "v1~~" + fixture.destinationSystem + '~' + fixture.destination.id
                + "~IRON~50~200~100~50~AUTO~" + String.join(",", tooManyEscorts);
        require(!ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, escortOverflow),
                "route accepted more than the maximum escort count");

        String oversizedCommand = "v1~~" + fixture.destinationSystem + '~' + fixture.destination.id
                + "~IRON~50~200~100~50~AUTO~NONE" + "X".repeat(LogisticsRouteSystem.MAX_COMMAND_CHARS);
        require(!ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, oversizedCommand),
                "route accepted an oversized command payload");

        Fixture routeCap = fixture("Issue 293 route cap", 500, 50);
        String autoSpec = LogisticsRouteSystem.encodeSpec("", routeCap.destinationSystem, routeCap.destination.id,
                List.of(Material.IRON), 50, 200, 100, 50, List.of(), List.of(), false);
        for (int i = 0; i < LogisticsRouteSystem.MAX_ROUTES_PER_PLAYER; i++) {
            require(ProductionCommands.apply(routeCap.world, "SOLO", "CONTROL", routeCap.source.id,
                            LogisticsRouteSystem.COMMAND_CREATE, autoSpec),
                    "valid route was rejected before reaching the per-player route cap at index " + i);
        }
        require(!ProductionCommands.apply(routeCap.world, "SOLO", "CONTROL", routeCap.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, autoSpec),
                "route count exceeded the per-player maximum");
    }

    private static void requireRestoredPhase(Fixture fixture, LogisticsRouteSystem.RoutePhase expected,
                                             String relevantSystem) {
        Map<String,Object> galaxy = fixture.world.captureServerSaveGalaxy();
        Map<String,Object> runtime = fixture.world.captureServerSaveRuntime();
        World restored = restore("Issue 293 phase restore " + expected, fixture, galaxy, runtime);
        restored.activateSystem(fixture.destinationSystem);
        LogisticsRouteSystem.update(restored, 0.01);
        restored.activateSystem(relevantSystem);
        LogisticsRouteSystem.update(restored, 0.01);
        restored.activateSystem(fixture.sourceSystem);
        Base source = restored.bases.get(fixture.source.id);
        require(source != null, expected + " restore lost the source station");
        LogisticsRouteSystem.RouteView view = onlyRoute(restored, source);
        require(view.phase() == expected,
                "route restored from " + expected + " as " + view.phase());
    }

    private static LogisticsRouteSystem.RouteView routeViewFromSource(Fixture fixture) {
        String previous = fixture.world.activeSystemId();
        fixture.world.activateSystem(fixture.sourceSystem);
        Base source = fixture.world.bases.get(fixture.source.id);
        LogisticsRouteSystem.RouteView view = onlyRoute(fixture.world, source);
        fixture.world.activateSystem(previous);
        return view;
    }

    private static World restore(String name, Fixture fixture, Map<String,Object> galaxy,
                                 Map<String,Object> runtime) {
        PlayerRegistry.reset("SOLO", name, 0x50BEFF);
        World restored = new World(name, disabledNpcFactions(), fixture.world.systemId(), false);
        PlayerRegistry.activate(restored);
        restored.restoreServerSaveGalaxy(galaxy);
        restored.restoreServerSaveRuntime(runtime);
        return restored;
    }

    private static String createRoute(Fixture fixture, List<String> transports, List<String> escorts,
                                      double reserve, double target, double batch, int priority) {
        fixture.world.activateSystem(fixture.sourceSystem);
        String spec = LogisticsRouteSystem.encodeSpec("", fixture.destinationSystem, fixture.destination.id,
                List.of(Material.IRON), reserve, target, batch, priority, transports, escorts, false);
        require(ProductionCommands.apply(fixture.world, "SOLO", "CONTROL", fixture.source.id,
                        LogisticsRouteSystem.COMMAND_CREATE, spec),
                "route creation was rejected in " + fixture.world.localPlayerName);
        return onlyRoute(fixture.world, fixture.source).id();
    }

    private static Fixture fixture(String name, double stock, double reserve) {
        PlayerRegistry.reset("SOLO", name, 0x50BEFF);
        World world = new World(name, disabledNpcFactions(), StarSystems.DEFAULT_SYSTEM_ID);
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

    private static Unit armedEscort(World world, int unitId, Base source, String playerId) {
        Unit escort = new Unit(playerId, unitId, "destroyer", source.x + 20, source.y + 20);
        escort.loadoutId = "destroyer_rail_escort";
        world.units.put(escort.key(), escort);
        require(WeaponRules.armed(world, escort), "escort fixture is not armed");
        world.saveActiveSystem();
        return escort;
    }

    private static void moveConvoy(World world, String transportKey, String targetSystem) {
        for (int guard = 0; guard < 64; guard++) {
            String current = world.ownerUnitLocations("SOLO").get(transportKey);
            require(current != null, "transport vanished during phase setup");
            if (targetSystem.equals(current)) return;
            world.activateSystem(current);
            LogisticsRouteSystem.update(world, 0.25);
            Unit transport = world.units.get(transportKey);
            require(transport != null, "transport missing from reported system during phase setup");
            transport.wormholeCooldown = 0;
            transport.x = transport.targetX;
            transport.y = transport.targetY;
            LogisticsRouteSystem.update(world, 0.01);
            require(world.transferTouchingShips("SOLO"), "transport failed to cross wormhole during phase setup");
        }
        throw new IllegalStateException("transport did not reach " + targetSystem + " during phase setup");
    }

    private static void isolateSystem(Map<String,Object> galaxy, String systemId) {
        for (Object item : list(galaxy.get("systems"))) {
            Map<String,Object> system = map(item);
            List<Object> gates = list(system.get("wormholes"));
            if (systemId.equals(String.valueOf(system.get("systemId")))) gates.clear();
            else gates.removeIf(gate -> systemId.equals(String.valueOf(map(gate).get("toSystemId"))));
        }
    }

    private static void removeSystem(Map<String,Object> galaxy, String systemId) {
        List<Object> systems = list(galaxy.get("systems"));
        systems.removeIf(item -> systemId.equals(String.valueOf(map(item).get("systemId"))));
        for (Object item : systems) {
            list(map(item).get("wormholes")).removeIf(
                    gate -> systemId.equals(String.valueOf(map(gate).get("toSystemId"))));
        }
    }

    private static LogisticsRouteSystem.RouteView find(List<LogisticsRouteSystem.RouteView> views, String id) {
        for (LogisticsRouteSystem.RouteView view : views) if (id.equals(view.id())) return view;
        return null;
    }

    private static LogisticsRouteSystem.RouteView onlyRoute(World world, Base source) {
        List<LogisticsRouteSystem.RouteView> routes = LogisticsRouteSystem.viewsForSource(world, source);
        require(routes.size() == 1, "expected exactly one route but found " + routes.size());
        return routes.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value) {
        return (Map<String,Object>)value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>)value;
    }

    private static Map<String,Object> copyMap(Map<String,Object> source) {
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<String,Object> entry : source.entrySet()) out.put(entry.getKey(), deepCopy(entry.getValue()));
        return out;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?,?> source) {
            Map<String,Object> out = new LinkedHashMap<>();
            for (Map.Entry<?,?> entry : source.entrySet()) out.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
            return out;
        }
        if (value instanceof List<?> source) {
            List<Object> out = new ArrayList<>();
            for (Object item : source) out.add(deepCopy(item));
            return out;
        }
        return value;
    }

    private static Set<String> disabledNpcFactions() {
        Set<String> out = new LinkedHashSet<>();
        for (NpcFaction faction : NpcRules.factions()) out.add(faction.id());
        return Set.copyOf(out);
    }

    private static boolean close(double a, double b) { return Math.abs(a - b) <= 0.15; }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record Fixture(World world, String sourceSystem, Base source,
                           String destinationSystem, Base destination, Unit transport) { }
}
