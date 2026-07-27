package com.tndmadman.rts;

import java.util.List;
import java.util.Set;

public final class TutorialOverlayValidator {
    private TutorialOverlayValidator() { }

    public static void main(String[] args) {
        validate();
        System.out.println("Core and advanced tutorial validation passed.");
    }

    static void validate() {
        validateCoreAndAdvancedProgression();
        validateFastCargoDelivery();
        validatePauseResume();
        validateMultiplayerIsolation();
    }

    private static void validateCoreAndAdvancedProgression() {
        World world = new World("Tutorial Tester", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        TutorialOverlay tutorial = new TutorialOverlay(world, true, false);
        expectEquals("core objective count", 10, tutorial.objectiveCountForTest());
        expectEquals("advanced objective count", 8, tutorial.advancedObjectiveCountForTest());
        expectStep("initial objective", tutorial, "SELECT");

        Unit prospector = firstLocalUnit(world);
        Base outpost = firstLocalBase(world);
        prospector.selected = true;
        tutorial.updateForTest();
        expectStep("selection", tutorial, "HARVEST");

        ResourceNode resource = firstHarvestableResource(world, prospector);
        world.autoHarvestSelected(resource);
        tutorial.updateForTest();
        expectStep("harvest", tutorial, "COLLECT");
        prospector.inventory.put(resource.material, 10.0);
        tutorial.updateForTest();
        expectStep("collect", tutorial, "DELIVER");
        outpost.inventory.put(resource.material, outpost.inventory.getOrDefault(resource.material, 0.0) + 5.0);
        tutorial.updateForTest();
        expectStep("deliver", tutorial, "QUEUE_BUILD");

        Unit secondProspector = new Unit(prospector.playerId, nextUnitId(world, prospector.playerId),
                Rules.STARTING_SHIP, outpost.x + 100, outpost.y);
        world.units.put(secondProspector.key(), secondProspector);
        tutorial.updateForTest();
        expectStep("build queue satisfied", tutorial, "BUILD_COMPLETE");
        tutorial.updateForTest();
        expectStep("build complete", tutorial, "MAP");
        tutorial.observeGalaxyMapForTest();
        tutorial.updateForTest();
        expectStep("map", tutorial, "WORMHOLE");

        String home = world.activeSystemId();
        String destination = firstOtherSystem(world, home);
        if (!world.viewGalaxySystem(destination)) throw new IllegalStateException("Could not view tutorial destination.");
        Unit traveler = new Unit(prospector.playerId, prospector.unitId, Rules.STARTING_SHIP, 120, 120);
        world.units.put(traveler.key(), traveler);
        tutorial.updateForTest();
        expectStep("wormhole", tutorial, "ENCOUNTER");

        NpcFaction faction = NpcRules.factions().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Tutorial validation requires an NPC faction."));
        Unit npc = new Unit(faction.id(), 9001, Rules.STARTING_SHIP, 220, 220);
        world.units.put(npc.key(), npc);
        tutorial.updateForTest();
        expectStep("encounter", tutorial, "RESPOND");
        if (!world.viewGalaxySystem(home)) throw new IllegalStateException("Could not return home.");
        tutorial.updateForTest();
        expectStep("core ready", tutorial, "ADVANCED_READY");
        expectTrue("core completed", tutorial.coreCompletedForTest());
        expectFalse("core completion hides overlay", tutorial.active());

        tutorial.toggle();
        expectStep("advanced begins", tutorial, "CATALOG");
        tutorial.observeCatalogForTest();
        tutorial.updateForTest();
        expectStep("catalog", tutorial, "CODEX");
        tutorial.observeCodexForTest();
        tutorial.updateForTest();
        expectStep("codex", tutorial, "QUEUE_DEPLOYER");

        ProductionJob deployerJob = new ProductionJob("TD1", ProductionJobKind.SHIP, "station_builder",
                14, 14, false, "");
        outpost.productionQueue.add(deployerJob);
        tutorial.updateForTest();
        expectStep("deployer queued", tutorial, "DEPLOYER_COMPLETE");
        outpost.productionQueue.remove(deployerJob);
        Unit deployer = new Unit(prospector.playerId, nextUnitId(world, prospector.playerId),
                "station_builder", outpost.x + 130, outpost.y);
        world.units.put(deployer.key(), deployer);
        tutorial.updateForTest();
        expectStep("deployer complete", tutorial, "LOAD_INDUSTRY_PACKAGE");
        deployer.basePackageType = "manufacturing";
        tutorial.updateForTest();
        expectStep("package loaded", tutorial, "PLACE_INDUSTRY_STATION");

        Base manufacturing = new Base("TUTORIAL-MFG", prospector.playerId, "manufacturing",
                outpost.x + 400, outpost.y);
        world.bases.put(manufacturing.id, manufacturing);
        tutorial.updateForTest();
        expectStep("station placed", tutorial, "QUEUE_INDUSTRY");
        CraftableItem item = firstCraftable("manufacturing");
        ProductionJob industryJob = new ProductionJob("TI1", ProductionJobKind.CRAFTABLE, item.id,
                item.timeSeconds, item.timeSeconds, false, "");
        manufacturing.productionQueue.add(industryJob);
        tutorial.updateForTest();
        expectStep("industry queued", tutorial, "COMPLETE_INDUSTRY");
        manufacturing.productionQueue.remove(industryJob);
        manufacturing.inventory.put(item.outputMaterial,
                manufacturing.inventory.getOrDefault(item.outputMaterial, 0.0) + item.outputAmount);
        tutorial.updateForTest();
        expectStep("advanced complete", tutorial, "COMPLETE");
        expectTrue("advanced completed", tutorial.advancedCompletedForTest());
    }

    private static void validateFastCargoDelivery() {
        World world = new World("Fast Delivery", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        TutorialOverlay tutorial = new TutorialOverlay(world, true, false);
        Unit prospector = firstLocalUnit(world);
        prospector.selected = true;
        tutorial.updateForTest();
        ResourceNode resource = firstHarvestableResource(world, prospector);
        world.autoHarvestSelected(resource);
        tutorial.updateForTest();
        Base outpost = firstLocalBase(world);
        outpost.inventory.put(resource.material, outpost.inventory.getOrDefault(resource.material, 0.0) + 3.0);
        tutorial.updateForTest();
        expectStep("fast collection", tutorial, "DELIVER");
        tutorial.updateForTest();
        expectStep("fast delivery", tutorial, "QUEUE_BUILD");
    }

    private static void validatePauseResume() {
        World world = new World("Tutorial Toggle", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        TutorialOverlay tutorial = new TutorialOverlay(world, true, false);
        expectTrue("solo tutorial active", tutorial.active());
        tutorial.toggle();
        expectFalse("tutorial paused", tutorial.active());
        tutorial.toggle();
        expectTrue("tutorial resumed", tutorial.active());
        expectStep("resumes current objective", tutorial, "SELECT");
    }

    private static void validateMultiplayerIsolation() {
        World world = new World("Tutorial Client", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        TutorialOverlay tutorial = new TutorialOverlay(world, false, false);
        expectFalse("joined tutorial inactive", tutorial.active());
        tutorial.toggle();
        expectFalse("joined tutorial remains inactive", tutorial.active());
    }

    private static Unit firstLocalUnit(World world) {
        for (Unit unit : world.units.values()) if (PlayerRegistry.isLocal(unit.playerId)) return unit;
        throw new IllegalStateException("Tutorial world has no local unit.");
    }

    private static Base firstLocalBase(World world) {
        for (Base base : world.bases.values()) if (PlayerRegistry.isLocal(base.playerId)) return base;
        throw new IllegalStateException("Tutorial world has no local base.");
    }

    private static ResourceNode firstHarvestableResource(World world, Unit unit) {
        for (ResourceNode node : world.resources) if (node.active && unit.type().harvestKinds.contains(node.kind)) return node;
        throw new IllegalStateException("Tutorial world has no harvestable resource.");
    }

    private static CraftableItem firstCraftable(String stationTypeId) {
        List<CraftableItem> items = CraftingRules.forStation(stationTypeId);
        if (items.isEmpty()) throw new IllegalStateException("No craftable items for " + stationTypeId + ".");
        return items.get(0);
    }

    private static int nextUnitId(World world, String playerId) {
        int next = 1;
        for (Unit unit : world.units.values()) if (playerId.equals(unit.playerId)) next = Math.max(next, unit.unitId + 1);
        return next;
    }

    private static String firstOtherSystem(World world, String homeSystemId) {
        for (GalaxyMapSystem system : world.galaxyMapSnapshot().systems()) {
            if (system != null && system.id() != null && !system.id().isBlank() && !system.id().equals(homeSystemId)) return system.id();
        }
        throw new IllegalStateException("Tutorial galaxy has no destination system.");
    }

    private static void expectStep(String name, TutorialOverlay tutorial, String expected) {
        String actual = tutorial.stepNameForTest();
        if (!expected.equals(actual)) throw new IllegalStateException(name + " expected " + expected + " but was " + actual + ".");
    }
    private static void expectTrue(String name, boolean actual) { if (!actual) throw new IllegalStateException("Expected true: " + name); }
    private static void expectFalse(String name, boolean actual) { if (actual) throw new IllegalStateException("Expected false: " + name); }
    private static void expectEquals(String name, Object expected, Object actual) { if (!expected.equals(actual)) throw new IllegalStateException(name + " expected " + expected + " but was " + actual + "."); }
}
