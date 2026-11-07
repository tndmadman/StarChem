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
        validateDisabledNpcOptionalTrack();
        validateHiddenNpcCannotAdvanceOptionalTrack();
    }

    private static void validateCoreAndAdvancedProgression() {
        World world = new World("Tutorial Tester", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        TutorialOverlay tutorial = new TutorialOverlay(world, true, false);
        expectEquals("core objective count", 17, tutorial.objectiveCountForTest());
        expectEquals("optional objective count", 6, tutorial.advancedObjectiveCountForTest());
        expectStep("initial objective", tutorial, "SELECT");

        Unit prospector = firstLocalUnit(world);
        Base outpost = firstLocalBase(world);
        prospector.selected = true;
        tutorial.updateForTest();
        expectStep("selection", tutorial, "HARVEST");
        ResourceNode node = firstHarvestableResource(world, prospector);
        world.autoHarvestSelected(node);
        tutorial.updateForTest();
        expectStep("harvest", tutorial, "COLLECT");
        prospector.inventory.put(node.material, 10.0);
        tutorial.updateForTest();
        expectStep("collect", tutorial, "DELIVER");
        outpost.inventory.put(node.material, outpost.inventory.getOrDefault(node.material, 0.0) + 5.0);
        tutorial.updateForTest();
        expectStep("delivery", tutorial, "QUEUE_BUILD");

        Unit second = new Unit(prospector.playerId, nextUnitId(world, prospector.playerId),
                Rules.STARTING_SHIP, outpost.x + 100, outpost.y);
        world.units.put(second.key(), second);
        tutorial.updateForTest();
        expectStep("second queued", tutorial, "BUILD_COMPLETE");
        tutorial.updateForTest();
        expectStep("second completed", tutorial, "QUEUE_DEPLOYER");

        Unit deployer = new Unit(prospector.playerId, nextUnitId(world, prospector.playerId),
                "station_builder", outpost.x + 130, outpost.y);
        world.units.put(deployer.key(), deployer);
        tutorial.updateForTest();
        expectStep("deployer queued", tutorial, "DEPLOYER_COMPLETE");
        tutorial.updateForTest();
        expectStep("deployer complete", tutorial, "LOAD_INDUSTRY_PACKAGE");
        deployer.basePackageType = "manufacturing";
        tutorial.updateForTest();
        expectStep("package loaded", tutorial, "PLACE_INDUSTRY_STATION");

        Base manufacturing = new Base("TUTORIAL-MFG", prospector.playerId, "manufacturing",
                outpost.x + 400, outpost.y);
        world.bases.put(manufacturing.id, manufacturing);
        tutorial.updateForTest();
        expectStep("manufacturing placed", tutorial, "QUEUE_INDUSTRY");
        CraftableItem wiring = CraftingRules.item("copper_wiring");
        ProductionJob job = new ProductionJob("TI1", ProductionJobKind.CRAFTABLE, wiring.id,
                wiring.timeSeconds, wiring.timeSeconds, false, "");
        manufacturing.productionQueue.add(job);
        tutorial.updateForTest();
        expectStep("craft queued", tutorial, "COMPLETE_INDUSTRY");
        manufacturing.productionQueue.remove(job);
        manufacturing.inventory.put(wiring.outputMaterial, wiring.outputAmount);
        tutorial.updateForTest();
        expectStep("craft completed", tutorial, "LAB_STATION");

        Base lab = new Base("TUTORIAL-LAB", prospector.playerId, "laboratory",
                outpost.x + 550, outpost.y);
        world.bases.put(lab.id, lab);
        tutorial.updateForTest();
        expectStep("Lab constructed", tutorial, "LAB_FUEL");
        lab.inventory.put(Material.FUEL, 50.0);
        tutorial.updateForTest();
        expectStep("Lab fueled", tutorial, "ADVANCED_RESEARCH");
        world.completedResearch.computeIfAbsent(prospector.playerId,
                ignored -> new java.util.LinkedHashSet<>()).add("advanced_industry");
        tutorial.updateForTest();
        expectStep("Advanced Industry complete", tutorial, "LOGISTICS_RESEARCH");
        world.completedResearch.get(prospector.playerId).add("logistics_engineering");
        world.completedResearch.get(prospector.playerId).add("autonomous_logistics");
        tutorial.updateForTest();
        expectStep("logistics complete", tutorial, "HAULER_COMPLETE");

        Unit hauler = new Unit(prospector.playerId, nextUnitId(world, prospector.playerId),
                "hauler", outpost.x + 650, outpost.y);
        world.units.put(hauler.key(), hauler);
        tutorial.updateForTest();
        expectStep("core completed", tutorial, "ADVANCED_READY");
        expectTrue("economic track completed", tutorial.coreCompletedForTest());
        expectFalse("core overlay deactivates", tutorial.active());

        tutorial.toggle();
        expectStep("optional starts", tutorial, "CATALOG");
        tutorial.observeCatalogForTest();
        tutorial.updateForTest();
        expectStep("catalog", tutorial, "CODEX");
        tutorial.observeCodexForTest();
        tutorial.updateForTest();
        expectStep("codex", tutorial, "MAP");
        tutorial.observeGalaxyMapForTest();
        tutorial.updateForTest();
        expectStep("map", tutorial, "WORMHOLE");
        tutorial.skipTrackForTest();
        expectStep("optional track finished", tutorial, "COMPLETE");
        expectTrue("optional marked complete", tutorial.advancedCompletedForTest());
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

    private static void validateDisabledNpcOptionalTrack() {
        World world = new World("Peaceful Tutorial", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        SkirmishRuntime.bind(world, SkirmishSettings.standard(
                Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID, Config.CORSAIRS_ID)));
        TutorialOverlay tutorial = new TutorialOverlay(world, true, false);
        tutorial.skipTrackForTest();
        tutorial.toggle();
        tutorial.skipSectionForTest(); // resource catalog and codex
        tutorial.skipSectionForTest(); // galaxy map and wormhole
        expectStep("optional NPC encounter reached", tutorial, "ENCOUNTER");
        tutorial.updateForTest();
        expectStep("peaceful optional track completes", tutorial, "COMPLETE");
        expectTrue("peaceful optional track completed", tutorial.advancedCompletedForTest());
    }

    private static void validateHiddenNpcCannotAdvanceOptionalTrack() {
        World world = new World("Hidden Tutorial NPC", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, true);
        TutorialOverlay tutorial = new TutorialOverlay(world, true, false);
        tutorial.skipTrackForTest();
        tutorial.toggle();
        tutorial.skipSectionForTest();
        tutorial.skipSectionForTest();
        expectStep("optional contact stage", tutorial, "ENCOUNTER");
        world.units.values().removeIf(unit -> NpcRules.isNpcFaction(unit.playerId));
        world.bases.values().removeIf(base -> NpcRules.isNpcFaction(base.playerId));
        NpcFaction npc = NpcRules.factions().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Expected an NPC definition"));
        Unit hidden = new Unit(npc.id(), 9231, Rules.STARTING_SHIP,
                Math.max(4000, world.width - 50), Math.max(4000, world.height - 50));
        world.units.put(hidden.key(), hidden);
        tutorial.updateForTest();
        expectStep("hidden NPC remains hidden", tutorial, "ENCOUNTER");
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
