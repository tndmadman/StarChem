package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CustomFitConstructionValidator {
    private CustomFitConstructionValidator() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("starchem-custom-build-");
        System.setProperty("starchem.fitStore", root.resolve("fits.json").toString());
        try {
            validateLocalConstructionAndMenu();
            validateFundedStationSelection();
            validateQuotePresentation();
            validateFallbackOutpost();
            validateAuthenticatedTcpConstruction();
            System.out.println("StarChem custom-fit construction and station-selection validation passed.");
        } finally {
            System.clearProperty("starchem.fitStore");
            deleteTree(root);
        }
    }

    private static void validateLocalConstructionAndMenu() {
        String player = "CUSTOM_BUILD";
        World world = new World("Custom Builder", Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset(player, "Custom Builder", 0x50BEFF);
        Base base = new Base(player + ":B1", player, "outpost", 900, 900);
        world.bases.put(base.id, base);
        fund(base);

        ShipFitSpec privateSpec = new ShipFitSpec("prospector", List.of(),
                List.of("afterburner"));
        ShipFitSpec serverSpec = new ShipFitSpec("prospector", List.of(),
                List.of("micro_jump_drive"));
        grant(world, player, privateSpec);
        grant(world, player, serverSpec);
        String commander = PlayerRegistry.baseName(player);
        ClientFitStore.save(commander, "", "Private Burner", privateSpec);
        WorldFitCatalog.publish(world, player, commander,
                "Published Jumper", serverSpec);

        BuildMenu menu = new BuildMenu();
        menu.showForBase(world, null, base, 120, 120);
        List<String> titles = menu.entryTitlesForTest();
        require(titles.stream().anyMatch(value -> value.contains("MY FIT: Private Burner")),
                "station menu did not expose a private custom fit");
        require(titles.stream().anyMatch(value -> value.contains("SERVER FIT")
                        && value.contains("Published Jumper")),
                "station menu did not expose a server-published custom fit");

        long revision = WorldFitCatalog.revision(world);
        int queueBefore = base.productionQueue.size();
        EnumMap<Material,Double> inventoryBefore = new EnumMap<>(base.inventory);
        ShipFitSpec unaffordable = new ShipFitSpec("prospector", List.of(),
                List.of("jump_scrambler"));
        grant(world, player, unaffordable);
        base.inventory.clear();
        FitCommand.Result rejected = FitCommand.applyLocal(world, player, "BUILD",
                payload("Rejected Build", unaffordable, base.id));
        require(!rejected.success(), "unaffordable custom build was accepted");
        require(base.productionQueue.size() == queueBefore,
                "rejected custom build changed the production queue");
        require(WorldFitCatalog.revision(world) == revision
                        && !catalogContains(world, unaffordable.runtimeId()),
                "rejected custom build polluted the runtime catalog");
        base.inventory.putAll(inventoryBefore);

        FitCommand.Result accepted = FitCommand.applyLocal(world, player, "BUILD",
                payload("Private Burner", privateSpec, base.id));
        require(accepted.success(), "valid private custom build was rejected: " + accepted.message());
        require(catalogContains(world, privateSpec.runtimeId()),
                "successful custom build did not register its runtime fit");
        ProductionJob job = base.productionQueue.get(base.productionQueue.size() - 1);
        require(job.kind == ProductionJobKind.SHIP
                        && privateSpec.runtimeId().equals(job.loadoutId),
                "custom construction job did not retain the selected fit");
        job.remaining = 0;
        ProductionSystem.update(world, 0.1);
        require(world.units.values().stream().anyMatch(unit ->
                        privateSpec.runtimeId().equals(unit.loadoutId)),
                "completed custom construction did not install the exact fit");

        FitCommand.Result publishedBuild = FitCommand.applyLocal(world, player, "BUILD",
                payload("Published Jumper", serverSpec, base.id));
        require(publishedBuild.success(),
                "published custom build was rejected: " + publishedBuild.message());
        require(base.productionQueue.stream().anyMatch(value ->
                        serverSpec.runtimeId().equals(value.loadoutId)),
                "published custom fit was not placed in production");
    }

    private static void validateFundedStationSelection() {
        String player = "STATION_SELECT";
        World world = new World("Station Select", Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset(player, "Station Select", 0x50BEFF);
        ShipFitSpec spec = new ShipFitSpec("prospector", List.of(),
                List.of("afterburner"));
        grant(world, player, spec);
        ShipLoadoutDefinition fit = PlayerFitRules.definition("Funded Far Fit", spec);

        Base near = new Base(player + ":B1", player, "outpost", 500, 500);
        Base far = new Base(player + ":B2", player, "shipyard", 3500, 500);
        world.bases.put(near.id, near);
        world.bases.put(far.id, far);
        fund(far);
        Unit unit = new Unit(player, 1, "prospector", 540, 500);
        world.units.put(unit.key(), unit);

        ShipFittingWindow.FittingOption option = ShipFittingWindow.evaluate(world, unit, fit);
        require(option.ready() && option.base() == far,
                "fitting UI rejected or misrouted a farther funded station");
    }

    private static void validateQuotePresentation() {
        String player = "QUOTE_UI";
        World world = new World("Quote UI", Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset(player, "Quote UI", 0x50BEFF);
        Unit unit = new Unit(player, 1, "prospector", 500, 500);
        world.units.put(unit.key(), unit);

        ShipFitSpec afterburnerSpec = new ShipFitSpec("prospector", List.of(),
                List.of("afterburner"));
        grant(world, player, afterburnerSpec);
        ShipLoadoutDefinition afterburner = PlayerFitRules.definition(
                "Quote Burner", afterburnerSpec);
        String add = ShipFittingWindow.refitCostSummary(unit, afterburner);
        require(add.startsWith("Add ") && add.contains("Nothing removed"),
                "UI did not present the exact added-material conversion quote: " + add);

        unit.loadoutId = afterburner.id();
        ShipLoadoutDefinition defaultFit = WeaponRules.defaultLoadout("prospector");
        String remove = ShipFittingWindow.refitCostSummary(unit, defaultFit);
        require(remove.contains("No added materials")
                        && remove.contains("Scrap 1× Afterburner"),
                "UI did not present the explicit removed-component scrap policy: " + remove);
    }

    private static void validateFallbackOutpost() {
        require(BaseType.fallbackCanRefit("outpost"),
                "fallback Outpost is not refit-capable");
        require(BaseType.fallbackCanRefit("shipyard"),
                "fallback Shipyard is not refit-capable");
        require(!BaseType.fallbackCanRefit("research_station"),
                "fallback non-refit station was incorrectly enabled");
        require(BaseType.fallbackRefitRange("outpost", 118) == 420,
                "fallback Outpost refit range is incorrect");
        require(BaseType.fallbackRefitRange("shipyard", 150) == 520,
                "fallback Shipyard refit range is incorrect");
    }

    private static void validateAuthenticatedTcpConstruction() throws Exception {
        try (TcpIntegrationHarness harness = TcpIntegrationHarness.host()) {
            TcpIntegrationHarness.TestClient client = harness.addClient("TCP Custom Builder");
            harness.awaitJoined(client);
            String player = client.playerId();
            String home = harness.serverWorld.playerHomeSystemId(player);
            harness.serverWorld.activateSystem(home);
            Base base = harness.serverWorld.bases.values().stream()
                    .filter(value -> player.equals(value.playerId)
                            && value.type().buildableShips.contains("prospector"))
                    .findFirst().orElseThrow();
            fund(base);
            ShipFitSpec spec = new ShipFitSpec("prospector", List.of(),
                    List.of("afterburner"));
            grant(harness.serverWorld, player, spec);

            require(FitNetworkBridge.submit(client.network(), client.world(), "BUILD",
                            "TCP Burner", spec, base.id, null, null),
                    "client could not submit authenticated custom construction");
            harness.await(() -> base.productionQueue.stream().anyMatch(job ->
                            job.kind == ProductionJobKind.SHIP
                                    && spec.runtimeId().equals(job.loadoutId)),
                    10_000, "server did not authorize the custom construction job");
            ProductionJob job = base.productionQueue.stream().filter(value ->
                            spec.runtimeId().equals(value.loadoutId))
                    .findFirst().orElseThrow();
            job.remaining = 0;
            harness.await(() -> harness.serverWorld.units.values().stream().anyMatch(unit ->
                            player.equals(unit.playerId)
                                    && spec.runtimeId().equals(unit.loadoutId)),
                    10_000, "authenticated custom construction did not complete");
            harness.await(() -> catalogContains(client.world(), spec.runtimeId()),
                    10_000, "client did not receive the custom build fit catalog");
        }
    }

    private static Map<String,Object> payload(String name, ShipFitSpec spec, String baseId) {
        return Map.of("name", name, "spec", spec.toMap(), "baseId", baseId);
    }

    private static void grant(World world, String player, ShipFitSpec spec) {
        for (String topic : PlayerFitRules.requiredResearch(spec)) {
            world.completeResearch(player, topic);
        }
    }

    private static void fund(Base base) {
        for (Material material : Material.values()) base.inventory.put(material, 100_000.0);
    }

    private static boolean catalogContains(World world, String id) {
        for (Object item : ServerSaveStore.list(
                WorldFitCatalog.networkView(world).get("definitions"))) {
            if (id.equals(ServerSaveStore.string(
                    ServerSaveStore.object(item), "id", ""))) return true;
        }
        return false;
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
