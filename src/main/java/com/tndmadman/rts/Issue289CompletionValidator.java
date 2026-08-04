package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** End-to-end closure checks for issue #289's remaining authority, persistence, UI, and restart gaps. */
public final class Issue289CompletionValidator {
    private Issue289CompletionValidator() { }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        validateWeaponRangeIndicator();
        validateRejectedFitRequestsDoNotMutate();
        validateCurrentSaveReferencesAreStrict();
        validateDedicatedRestartReconnectAndWormhole();
        System.out.println("StarChem issue #289 completion validation passed.");
    }

    private static void validateWeaponRangeIndicator() {
        String playerId = "ISSUE289_RANGE";
        PlayerRegistry.reset(playerId, "Range Validator", 0x50BEFF);
        World world = new World("Range Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);

        Unit destroyer = new Unit(playerId, 1, "destroyer", 900, 900);
        destroyer.selected = true;
        destroyer.loadoutId = "destroyer_rail_escort";
        world.units.put(destroyer.key(), destroyer);
        double railExpected = WeaponRules.maxRange(destroyer) * SystemModifierRules.weaponRange(world);
        require(close(UnitRenderer.displayedWeaponRange(world, destroyer), railExpected),
                "weapon range indicator ignored the selected rail fit");

        destroyer.loadoutId = "destroyer_missile_screen";
        double missileExpected = WeaponRules.maxRange(destroyer) * SystemModifierRules.weaponRange(world);
        require(close(UnitRenderer.displayedWeaponRange(world, destroyer), missileExpected),
                "weapon range indicator ignored the selected missile fit");
        require(Math.abs(missileExpected - railExpected) > 0.001,
                "range indicator fixture did not exercise different fitted ranges");

        Unit prospector = new Unit(playerId, 2, "prospector", 1100, 900);
        prospector.selected = true;
        world.units.put(prospector.key(), prospector);
        require(UnitRenderer.displayedWeaponRange(world, prospector) == 0,
                "unarmed fit displayed a weapon range");

        BufferedImage image = new BufferedImage(2400, 2000, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            UnitRenderer.draw(graphics, destroyer, PlayerRegistry.color(playerId), true);
            UnitRenderer.draw(graphics, prospector, PlayerRegistry.color(playerId), true);
        } finally {
            graphics.dispose();
        }
    }

    private static void validateRejectedFitRequestsDoNotMutate() {
        String actor = "ISSUE289_AUTH";
        String other = "ISSUE289_OTHER";
        PlayerRegistry.reset(actor, "Authority Validator", 0x50BEFF);
        World world = new World("Fit Authority Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        world.completeResearch(actor, "combat_doctrine");
        world.completeResearch(actor, "advanced_industry");
        world.completeResearch(actor, "battlefleet_engineering");

        Base actorYard = new Base(actor + ":B1", actor, "shipyard", 1000, 1000);
        Base otherYard = new Base(other + ":B1", other, "shipyard", 1500, 1000);
        world.bases.put(actorYard.id, actorYard);
        world.bases.put(otherYard.id, otherYard);
        Unit actorShip = new Unit(actor, 1, "destroyer", 1040, 1000);
        Unit otherShip = new Unit(other, 1, "destroyer", 1540, 1000);
        world.units.put(actorShip.key(), actorShip);
        world.units.put(otherShip.key(), otherShip);

        rejectWithoutMutation(world, actor, actorYard, actorShip, "unknown weapon",
                payload("Unknown Weapon", new ShipFitSpec("destroyer", List.of("not_a_weapon"), List.of()),
                        actorYard.id, actorShip.key()));
        rejectWithoutMutation(world, actor, actorYard, actorShip, "too many hardpoints",
                payload("Too Many", new ShipFitSpec("destroyer",
                                List.of("light_railgun", "light_railgun", "light_railgun", "light_railgun"), List.of()),
                        actorYard.id, actorShip.key()));
        rejectWithoutMutation(world, actor, actorYard, actorShip, "incompatible capital weapon",
                payload("Capital Injection", new ShipFitSpec("destroyer", List.of("capital_lance"), List.of()),
                        actorYard.id, actorShip.key()));
        rejectWithoutMutation(world, actor, actorYard, actorShip, "unknown utility module",
                payload("Unknown Module", new ShipFitSpec("destroyer", List.of("light_railgun"),
                                List.of("not_a_module")), actorYard.id, actorShip.key()));

        int utilitySlots = ShipModuleRules.moduleSlotCount("destroyer");
        List<String> excessiveModules = new ArrayList<>();
        for (int i = 0; i <= utilitySlots; i++) excessiveModules.add("afterburner");
        rejectWithoutMutation(world, actor, actorYard, actorShip, "too many utility modules",
                payload("Too Many Modules", new ShipFitSpec("destroyer", List.of("light_railgun"), excessiveModules),
                        actorYard.id, actorShip.key()));

        ShipFitSpec valid = new ShipFitSpec("destroyer",
                List.of("light_railgun", "light_railgun", "light_railgun"), List.of());
        rejectWithoutMutation(world, actor, actorYard, actorShip, "foreign ship",
                payload("Foreign Ship", valid, actorYard.id, otherShip.key()));
        rejectWithoutMutation(world, actor, actorYard, actorShip, "foreign station",
                payload("Foreign Station", valid, otherYard.id, actorShip.key()));

        // A valid but unaffordable request must not leave a runtime definition behind.
        actorYard.inventory.clear();
        rejectWithoutMutation(world, actor, actorYard, actorShip, "unaffordable fit",
                payload("Unaffordable", valid, actorYard.id, actorShip.key()));
        require(!catalogContains(world, valid.runtimeId()),
                "rejected unaffordable refit polluted the authoritative runtime catalog");

        // Client-supplied economics and identity are ignored; server-derived cost still rejects.
        Map<String,Object> injectedSpec = new LinkedHashMap<>(valid.toMap());
        injectedSpec.put("refitCost", Map.of());
        injectedSpec.put("refitTimeSeconds", 0);
        Map<String,Object> injected = new LinkedHashMap<>();
        injected.put("name", "Injected Economics");
        injected.put("spec", injectedSpec);
        injected.put("baseId", actorYard.id);
        injected.put("unitKey", actorShip.key());
        injected.put("ownerPlayerId", other);
        rejectWithoutMutation(world, actor, actorYard, actorShip, "client-supplied economics",
                injected);
    }

    private static void rejectWithoutMutation(World world, String actor, Base yard, Unit ship,
                                              String label, Map<String,Object> payload) {
        long revision = WorldFitCatalog.revision(world);
        int queueSize = yard.productionQueue.size();
        String loadoutId = ship.loadoutId;
        EnumMap<Material,Double> inventory = new EnumMap<>(yard.inventory);
        double x = ship.x, y = ship.y, targetX = ship.targetX, targetY = ship.targetY;
        UnitTask task = ship.task;
        String attackTarget = ship.attackTarget;

        FitCommand.Result result = FitCommand.applyLocal(world, actor, "REFIT", payload);
        require(!result.success(), label + " request was accepted");
        require(WorldFitCatalog.revision(world) == revision,
                label + " rejection changed the runtime catalog revision");
        require(yard.productionQueue.size() == queueSize,
                label + " rejection changed the production queue");
        require(yard.inventory.equals(inventory), label + " rejection changed station inventory");
        require(loadoutId.equals(ship.loadoutId), label + " rejection changed the installed fit");
        require(close(ship.x, x) && close(ship.y, y) && close(ship.targetX, targetX) && close(ship.targetY, targetY),
                label + " rejection changed ship movement state");
        require(ship.task == task && attackTarget.equals(ship.attackTarget),
                label + " rejection changed ship activity state");
    }

    private static void validateCurrentSaveReferencesAreStrict() {
        ShipFitSpec spec = new ShipFitSpec("prospector", List.of(), List.of("afterburner"));
        String runtimeId = spec.runtimeId();
        Map<String,Object> definition = new LinkedHashMap<>();
        definition.put("id", runtimeId);
        definition.put("spec", spec.toMap());
        Map<String,Object> shipFits = new LinkedHashMap<>();
        shipFits.put("definitions", new ArrayList<>(List.of(definition)));
        shipFits.put("published", new ArrayList<>());
        Map<String,Object> runtime = new LinkedHashMap<>();
        runtime.put("shipFits", shipFits);

        Map<String,Object> unit = savedUnit("SAVE_PLAYER", 1, "prospector", runtimeId);
        Map<String,Object> system = new LinkedHashMap<>();
        system.put("systemId", StarSystems.DEFAULT_SYSTEM_ID);
        system.put("units", new ArrayList<>(List.of(unit)));
        system.put("bases", new ArrayList<>());
        Map<String,Object> galaxy = new LinkedHashMap<>();
        galaxy.put("systems", new ArrayList<>(List.of(system)));

        ServerSaveMigration.migrate(4, new LinkedHashMap<>(), new LinkedHashMap<>(), galaxy, runtime);

        unit.put("loadoutId", "custom_missing_definition");
        expectReject(() -> ServerSaveMigration.migrate(4, new LinkedHashMap<>(), new LinkedHashMap<>(),
                galaxy, runtime), "missing loadout");
        unit.put("loadoutId", runtimeId);

        ServerSaveStore.list(shipFits.get("definitions")).clear();
        expectReject(() -> ServerSaveMigration.migrate(4, new LinkedHashMap<>(), new LinkedHashMap<>(),
                galaxy, runtime), "missing runtime definition");

        // Legacy saves intentionally omit loadout IDs and receive hull defaults during migration.
        Map<String,Object> legacyUnit = savedUnit("LEGACY_PLAYER", 1, "prospector", "");
        legacyUnit.remove("loadoutId");
        Map<String,Object> legacySystem = new LinkedHashMap<>();
        legacySystem.put("systemId", StarSystems.DEFAULT_SYSTEM_ID);
        legacySystem.put("units", new ArrayList<>(List.of(legacyUnit)));
        legacySystem.put("bases", new ArrayList<>());
        Map<String,Object> legacyGalaxy = new LinkedHashMap<>();
        legacyGalaxy.put("systems", new ArrayList<>(List.of(legacySystem)));
        ServerSaveMigration.migrate(3, new LinkedHashMap<>(), new LinkedHashMap<>(),
                legacyGalaxy, new LinkedHashMap<>());
    }

    private static void validateDedicatedRestartReconnectAndWormhole() throws Exception {
        Path root = Files.createTempDirectory("starchem-issue289-lifecycle-");
        Path saveDir = root.resolve("saves");
        Path sessionStore = root.resolve("client-session.properties");
        String clientName = "Issue 289 Lifecycle Client";
        String playerId;
        int unitId;
        String runtimeId;
        String destinationSystem;

        try {
            try (TcpIntegrationHarness first = TcpIntegrationHarness.dedicated(saveDir, sessionStore)) {
                TcpIntegrationHarness.TestClient client = first.addClient(clientName);
                first.awaitJoined(client);
                playerId = client.playerId();
                String homeSystem = first.serverWorld.playerHomeSystemId(playerId);
                first.serverWorld.activateSystem(homeSystem);

                Unit ship = first.firstUnit(first.serverWorld, playerId);
                require(ship != null, "dedicated lifecycle player has no ship");
                unitId = ship.unitId;
                ship.shipTypeId = "prospector";
                ship.loadoutId = WeaponRules.defaultLoadoutId("prospector");
                ship.hp = ship.type().maxHp;
                ship.shield = ship.type().maxShield;

                Base yard = firstOwnedRefitBase(first.serverWorld, playerId);
                require(yard != null, "dedicated lifecycle player has no refit-capable station");
                for (Material material : Material.values()) yard.inventory.put(material, 10_000.0);
                ship.x = Calc.clamp(yard.x + yard.type().refitRange + 900, 0, first.serverWorld.width);
                ship.y = yard.y;
                ship.targetX = ship.x + 300;
                ship.targetY = ship.y;
                ship.task = UnitTask.ATTACK;
                ship.attackTarget = "B:enemy-lifecycle";
                first.serverWorld.saveActiveSystem();
                first.runTicks(80);

                ShipFitSpec spec = new ShipFitSpec("prospector", List.of(), List.of("afterburner"));
                for (String topic : PlayerFitRules.requiredResearch(spec)) {
                    first.serverWorld.completeResearch(playerId, topic);
                }
                runtimeId = spec.runtimeId();
                require(FitNetworkBridge.submit(client.network(), client.world(), "REFIT", "Lifecycle Afterburner",
                                spec, yard.id, ship.key(), null),
                        "client could not submit the dedicated lifecycle refit");

                first.await(() -> {
                    Unit authoritative = first.unit(first.serverWorld, playerId, unitId);
                    return authoritative != null && ProductionSystem.refitReserved(first.serverWorld, authoritative.key())
                            && authoritative.attackTarget.isBlank()
                            && Calc.distance(authoritative.targetX, authoritative.targetY, yard.x, yard.y)
                            < Calc.distance(ship.x, ship.y, yard.x, yard.y);
                }, 8_000, "remote combat-active refit was not authoritatively recalled");

                first.await(() -> {
                    Unit authoritative = first.unit(first.serverWorld, playerId, unitId);
                    return authoritative != null && runtimeId.equals(authoritative.loadoutId)
                            && !ProductionSystem.refitReserved(first.serverWorld, authoritative.key());
                }, 20_000, "dedicated lifecycle refit did not complete");
                first.await(() -> {
                    Unit replica = first.unit(client.world(), playerId, unitId);
                    return replica != null && runtimeId.equals(replica.loadoutId);
                }, 10_000, "client did not receive the fitted runtime ID");

                Unit fitted = first.unit(first.serverWorld, playerId, unitId);
                require(fitted != null && ShipModuleRules.has(fitted, ShipModuleKind.AFTERBURNER),
                        "dedicated fitted ship lost its afterburner mechanics");
                require(catalogContains(first.serverWorld, runtimeId) && catalogContains(client.world(), runtimeId),
                        "runtime fit catalog did not synchronize before the fitted snapshot");

                String sourceSystem = systemContaining(first.serverWorld, playerId, unitId);
                require(!sourceSystem.isBlank(), "could not locate fitted ship before wormhole travel");
                first.serverWorld.activateSystem(sourceSystem);
                WormholeGate gate = first.serverWorld.wormholes.isEmpty() ? null : first.serverWorld.wormholes.get(0);
                require(gate != null, "dedicated lifecycle system has no wormhole");
                destinationSystem = gate.toSystemId;
                fitted = first.serverWorld.units.get(Unit.key(playerId, unitId));
                fitted.x = gate.x;
                fitted.y = gate.y;
                fitted.targetX = fitted.x;
                fitted.targetY = fitted.y;
                fitted.task = UnitTask.IDLE;
                fitted.wormholeCooldown = 0;
                require(first.serverWorld.transferTouchingShips(playerId),
                        "fitted ship did not transfer through the wormhole");
                Unit transferred = first.unit(first.serverWorld, playerId, unitId);
                require(transferred != null && runtimeId.equals(transferred.loadoutId)
                                && ShipModuleRules.has(transferred, ShipModuleKind.AFTERBURNER),
                        "wormhole travel lost the fitted loadout or module behavior");
            }

            try (TcpIntegrationHarness restarted = TcpIntegrationHarness.dedicated(saveDir, sessionStore)) {
                TcpIntegrationHarness.TestClient client = restarted.addClient(clientName);
                restarted.awaitJoined(client);
                require(playerId.equals(client.playerId()),
                        "dedicated restart did not resume the retained player identity");
                Unit restored = restarted.unit(restarted.serverWorld, playerId, unitId);
                require(restored != null && runtimeId.equals(restored.loadoutId),
                        "dedicated restart lost the custom fitted ship");
                require(ShipModuleRules.has(restored, ShipModuleKind.AFTERBURNER),
                        "dedicated restart lost the custom module definition");
                require(catalogContains(restarted.serverWorld, runtimeId),
                        "dedicated restart did not restore the runtime fit catalog");

                client.network().viewSystem(playerId, destinationSystem);
                restarted.await(() -> destinationSystem.equals(client.world().activeSystemId())
                                && restarted.unit(client.world(), playerId, unitId) != null,
                        12_000, "reconnected client could not view the fitted ship's destination system");
                Unit replica = restarted.unit(client.world(), playerId, unitId);
                require(replica != null && runtimeId.equals(replica.loadoutId),
                        "reconnected client rejected or lost the restored custom loadout snapshot");
                require(catalogContains(client.world(), runtimeId),
                        "reconnected client did not install the fit catalog before the ship snapshot");
            }
        } finally {
            deleteTree(root);
        }
    }

    private static Base firstOwnedRefitBase(World world, String playerId) {
        for (Base base : world.bases.values()) {
            if (playerId.equals(base.playerId) && base.hp > 0 && base.type().canRefitShips) return base;
        }
        return null;
    }

    private static String systemContaining(World world, String playerId, int unitId) {
        String previous = world.activeSystemId();
        try {
            GalaxyMapSnapshot snapshot = world.galaxyMapSnapshot();
            if (snapshot == null || snapshot.systems() == null) return "";
            for (GalaxyMapSystem system : snapshot.systems()) {
                if (system == null || system.id() == null || system.id().isBlank()) continue;
                world.activateSystem(system.id());
                if (world.units.containsKey(Unit.key(playerId, unitId))) return system.id();
            }
            return "";
        } finally {
            world.activateSystem(previous);
        }
    }

    private static Map<String,Object> payload(String name, ShipFitSpec spec, String baseId, String unitKey) {
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("spec", spec.toMap());
        payload.put("baseId", baseId);
        payload.put("unitKey", unitKey);
        return payload;
    }

    private static Map<String,Object> savedUnit(String playerId, int unitId, String hullId, String loadoutId) {
        Map<String,Object> unit = new LinkedHashMap<>();
        unit.put("playerId", playerId);
        unit.put("unitId", unitId);
        unit.put("shipTypeId", hullId);
        unit.put("loadoutId", loadoutId);
        return unit;
    }

    private static boolean catalogContains(World world, String runtimeId) {
        Map<String,Object> view = WorldFitCatalog.networkView(world);
        for (Object item : ServerSaveStore.list(view.get("definitions"))) {
            if (runtimeId.equals(ServerSaveStore.string(ServerSaveStore.object(item), "id", ""))) return true;
        }
        return false;
    }

    private static void expectReject(Runnable action, String label) {
        try {
            action.run();
            throw new IllegalStateException(label + " was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected strict current-save rejection.
        }
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) < 0.000001;
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
