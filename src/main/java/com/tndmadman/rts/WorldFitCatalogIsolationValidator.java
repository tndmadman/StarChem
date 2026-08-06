package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies that runtime fit definitions cannot leak between worlds in one JVM. */
public final class WorldFitCatalogIsolationValidator {
    private WorldFitCatalogIsolationValidator() { }

    public static void main(String[] args) {
        String playerA = "FIT_WORLD_A";
        String playerB = "FIT_WORLD_B";
        World worldA = world("Fit World A", playerA);
        ShipFitSpec specA = new ShipFitSpec("destroyer",
                List.of("light_railgun", "light_railgun", "light_missile"),
                List.of("afterburner"));
        ShipLoadoutDefinition fitA = WorldFitCatalog.registerRuntime(worldA, "World A Fit", specA);
        long revisionA = WorldFitCatalog.revision(worldA);

        require(!WeaponRules.SHIP_LOADOUTS.containsKey(fitA.id()),
                "runtime fit A leaked into the authored global registry");
        require(WeaponRules.findLoadout(worldA, fitA.id()) != null,
                "world A could not resolve its runtime fit");
        require(ShipModuleRules.moduleIds(WeaponRules.findLoadout(worldA, fitA.id())).equals(specA.moduleIds()),
                "world A runtime modules were not retained in the definition");

        World worldB = world("Fit World B", playerB);
        require(WeaponRules.findLoadout(worldB, fitA.id()) == null,
                "world B resolved world A's runtime fit");
        require(WeaponRules.findLoadout(fitA.id()) == null,
                "active world B inherited world A's runtime fit");
        require(WorldFitCatalog.revision(worldB) == 0,
                "reading a foreign runtime ID mutated world B's catalog revision");

        ShipFitSpec specB = new ShipFitSpec("destroyer",
                List.of("light_missile", "light_missile", "point_defense_laser"),
                List.of("micro_jump_drive"));
        ShipLoadoutDefinition fitB = WorldFitCatalog.registerRuntime(worldB, "World B Fit", specB);
        long revisionB = WorldFitCatalog.revision(worldB);
        require(!WeaponRules.SHIP_LOADOUTS.containsKey(fitB.id()),
                "runtime fit B leaked into the authored global registry");
        require(WeaponRules.findLoadout(worldA, fitB.id()) == null,
                "world A resolved world B's runtime fit");
        require(WorldFitCatalog.revision(worldA) == revisionA,
                "world B registration changed world A's catalog revision");

        PlayerRegistry.activate(worldA);
        require(WeaponRules.findLoadout(fitA.id()) != null && WeaponRules.findLoadout(fitB.id()) == null,
                "active-world lookup was not isolated to world A");
        Unit unitA = new Unit(playerA, 1, "destroyer", 500, 500);
        unitA.loadoutId = fitA.id();
        worldA.units.put(unitA.key(), unitA);
        require(WeaponRules.maxRange(worldA, unitA) > 0,
                "world A unit could not resolve runtime weapons");
        require(ShipModuleRules.has(worldA, unitA, ShipModuleKind.AFTERBURNER),
                "world A unit could not resolve runtime modules");

        PlayerRegistry.activate(worldB);
        Unit foreign = new Unit(playerB, 1, "destroyer", 500, 500);
        foreign.loadoutId = fitA.id();
        worldB.units.put(foreign.key(), foreign);
        require(WeaponRules.findLoadout(worldB, foreign.loadoutId) == null,
                "world B unit resolved a foreign runtime definition");
        require(!ShipModuleRules.has(worldB, foreign, ShipModuleKind.AFTERBURNER),
                "world B unit inherited foreign runtime modules");

        Map<String,Object> capturedA = WorldFitCatalog.capture(worldA);
        require(ServerSaveStore.longValue(capturedA, "version", 0) == 2,
                "runtime fit catalog schema version was not advanced for persisted names");
        List<Object> capturedDefinitions = ServerSaveStore.list(capturedA.get("definitions"));
        require(capturedDefinitions.size() == 1,
                "captured world A runtime definition count was unexpected");
        require("World A Fit".equals(ServerSaveStore.string(
                        ServerSaveStore.object(capturedDefinitions.get(0)), "displayName", "")),
                "captured runtime definition omitted its authoritative display name");

        World restoredA = world("Restored Fit World A", playerA + "_RESTORED");
        WorldFitCatalog.restore(restoredA, capturedA);
        ShipLoadoutDefinition restoredFitA = WeaponRules.findLoadout(restoredA, fitA.id());
        require(fitA.equals(restoredFitA),
                "restored world A changed its runtime fit name or derived definition");
        require(WeaponRules.findLoadout(restoredA, fitB.id()) == null,
                "restored world A imported world B's runtime fit");

        World clientA = world("Client Fit World A", playerA + "_CLIENT");
        WorldFitCatalog.applyNetworkView(clientA, WorldFitCatalog.networkView(worldA));
        ShipLoadoutDefinition clientFitA = WeaponRules.findLoadout(clientA, fitA.id());
        require(fitA.equals(clientFitA),
                "client catalog bootstrap changed the runtime fit name or derived definition");
        require(WeaponRules.findLoadout(clientA, fitB.id()) == null,
                "client catalog bootstrap imported a foreign runtime fit");
        require(WorldFitCatalog.revision(worldB) == revisionB,
                "capture, restore, or network bootstrap changed world B's revision");

        validateLegacyNameFallback(specA, fitA.id());
        validateForeignSaveReferenceRejected(fitA);
        System.out.println("StarChem world-scoped runtime fit catalog isolation validation passed.");
    }

    private static void validateLegacyNameFallback(ShipFitSpec spec, String runtimeId) {
        Map<String,Object> definition = new LinkedHashMap<>();
        definition.put("id", runtimeId);
        definition.put("spec", spec.toMap());
        Map<String,Object> legacy = new LinkedHashMap<>();
        legacy.put("revision", 7);
        legacy.put("definitions", List.of(definition));
        legacy.put("published", List.of());

        World legacyWorld = world("Legacy Runtime Name", "FIT_WORLD_LEGACY");
        WorldFitCatalog.applyNetworkView(legacyWorld, legacy);
        ShipLoadoutDefinition restored = WeaponRules.findLoadout(legacyWorld, runtimeId);
        require(restored != null, "legacy name-less runtime definition was not restored");
        require("Custom Fit".equals(restored.displayName()),
                "legacy name-less runtime definition did not receive the compatibility fallback name");
        require(spec.equals(WorldFitCatalog.runtimeSpec(legacyWorld, runtimeId)),
                "legacy name migration changed the runtime fit component specification");
        require(WorldFitCatalog.revision(legacyWorld) == 7,
                "legacy catalog restore changed its authoritative revision");
    }

    private static void validateForeignSaveReferenceRejected(ShipLoadoutDefinition foreignFit) {
        Map<String,Object> unit = new LinkedHashMap<>();
        unit.put("playerId", "FOREIGN_SAVE");
        unit.put("unitId", 1);
        unit.put("shipTypeId", foreignFit.hullId());
        unit.put("loadoutId", foreignFit.id());
        Map<String,Object> system = new LinkedHashMap<>();
        system.put("systemId", StarSystems.DEFAULT_SYSTEM_ID);
        system.put("units", new ArrayList<>(List.of(unit)));
        system.put("bases", new ArrayList<>());
        Map<String,Object> galaxy = new LinkedHashMap<>();
        galaxy.put("systems", new ArrayList<>(List.of(system)));
        Map<String,Object> runtime = new LinkedHashMap<>();
        runtime.put("shipFits", Map.of("definitions", List.of(), "published", List.of()));

        boolean rejected = false;
        try {
            SavedFitReferenceValidator.validate(ServerSaveStore.SAVE_FORMAT_VERSION, galaxy, runtime);
        } catch (IllegalArgumentException ex) {
            rejected = ex.getMessage() != null && ex.getMessage().contains("missing loadout");
        }
        require(rejected,
                "strict save validation accepted a runtime fit leaked from another world");
    }

    private static World world(String name, String player) {
        World world = new World(name, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset(player, name, 0x55CCFF);
        return world;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
