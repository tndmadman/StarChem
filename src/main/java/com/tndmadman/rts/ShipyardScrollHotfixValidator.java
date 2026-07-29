package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Guards real station-production menu scrolling and research filtering.
 *
 * The historical class name is retained because CI already invokes it.
 */
public final class ShipyardScrollHotfixValidator {
    private static final int VIEWPORT_WIDTH = 800;
    private static final int VIEWPORT_HEIGHT = 480;
    private static final Path BUILD_MENU = Path.of(
            "src/main/java/com/tndmadman/rts/BuildMenu.java");
    private static final Path GAME_PANEL = Path.of(
            "src/main/java/com/tndmadman/rts/GamePanel.java");

    private ShipyardScrollHotfixValidator() { }

    public static void main(String[] args) throws Exception {
        String buildMenuSource =
                Files.readString(BUILD_MENU, StandardCharsets.UTF_8);
        String gamePanelSource =
                Files.readString(GAME_PANEL, StandardCharsets.UTF_8);

        validateIntegration(buildMenuSource, gamePanelSource);
        validateRealStationMenus();

        System.out.println(
                "Station production-menu scrolling and research filtering validation passed.");
    }

    private static void validateIntegration(String buildMenu, String gamePanel) {
        require(buildMenu.contains(
                        "double preciseWheelRotation"),
                "BuildMenu must accept precise fractional wheel movement.");
        require(buildMenu.contains(
                        "PRECISE_SCROLL_THRESHOLD"),
                "BuildMenu must accumulate small high-resolution wheel events.");
        require(buildMenu.contains(
                        "if (!menuBounds().contains(sx, sy))"),
                "Production menus must only capture wheel input under the pointer.");
        require(buildMenu.contains(
                        "calculateBottomOffset"),
                "Mixed-height production rows need a stable bottom offset.");
        require(buildMenu.contains(
                        "SCROLL  ↕"),
                "Overflowing production menus must show a visual scroll clue.");
        require(gamePanel.contains(
                        "e.getPreciseWheelRotation()"),
                "GamePanel must pass precise wheel data to BuildMenu.");

        require(!buildMenu.contains(
                        "free || ResearchRules.shipUnlocked"),
                "Free-build mode must not reveal unresearched ships.");
        require(!buildMenu.contains(
                        "free || StationPackageResearchRules.unlocked"),
                "Free-build mode must not reveal unresearched station packages.");
        require(!buildMenu.contains(
                        "free || item.unlockedFor"),
                "Free-build mode must not reveal unresearched crafting recipes.");
    }

    private static void validateRealStationMenus() {
        PlayerRegistry.reset("SOLO", "Menu Validator", 0x50BEFF);
        String playerId = PlayerRegistry.localId();
        World world = new World(
                "Menu Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        world.setDevFreeBuild(playerId, true);

        Base outpost = base(world, playerId, "outpost", 100, 100);
        Base shipyard = base(world, playerId, "shipyard", 300, 100);
        Base laboratory = base(world, playerId, "laboratory", 500, 100);
        Base manufacturing = base(world, playerId, "manufacturing", 700, 100);

        BuildMenu menu = new BuildMenu();
        validateEveryStationOpens(menu, world,
                List.of(outpost, shipyard, laboratory, manufacturing));
        validateLockedItemsStayHiddenInFreeBuild(
                menu, world, outpost, shipyard, manufacturing);

        for (ResearchTopic topic : ResearchRules.all()) {
            world.completeResearch(playerId, topic.id);
        }

        validateResearchUnlocksAppear(
                menu, world, outpost, shipyard, manufacturing);
        validatePreciseScrollingAndStableBottom(menu, world, shipyard);
        validateOverflowingStationMenus(menu, world,
                List.of(outpost, shipyard, manufacturing));
    }

    private static void validateEveryStationOpens(
            BuildMenu menu, World world, List<Base> stations) {
        for (Base station : stations) {
            menu.showForBase(world, station, 760, 440);
            require(!menu.entryTitlesForTest().isEmpty(),
                    station.type().name + " production menu is empty.");
        }
    }

    private static void validateLockedItemsStayHiddenInFreeBuild(
            BuildMenu menu, World world,
            Base outpost, Base shipyard, Base manufacturing) {
        String lockedShip = firstLockedShip(world, shipyard);
        String lockedPackage = firstLockedPackage(world, outpost);
        CraftableItem lockedCraftable =
                firstLockedCraftable(world, manufacturing);

        require(lockedShip != null,
                "The real shipyard config has no research-gated ship to test.");
        require(lockedPackage != null,
                "The real outpost config has no research-gated package to test.");
        require(lockedCraftable != null,
                "The real manufacturing config has no research-gated recipe to test.");

        menu.showForBase(world, shipyard, 760, 440);
        require(!menu.entryTitlesForTest().contains(
                        "Build " + Rules.ship(lockedShip).name),
                "Free-build mode exposed unresearched ship " + lockedShip + ".");

        menu.showForBase(world, outpost, 760, 440);
        require(!menu.entryTitlesForTest().contains(
                        "Load " + Rules.base(lockedPackage).name),
                "Free-build mode exposed unresearched station package "
                        + lockedPackage + ".");

        menu.showForBase(world, manufacturing, 760, 440);
        require(!menu.entryTitlesForTest().contains(
                        "Manufacture " + lockedCraftable.name),
                "Free-build mode exposed unresearched recipe "
                        + lockedCraftable.id + ".");
    }

    private static void validateResearchUnlocksAppear(
            BuildMenu menu, World world,
            Base outpost, Base shipyard, Base manufacturing) {
        String researchedShip = firstResearchGatedShip(shipyard);
        String researchedPackage = firstResearchGatedPackage(outpost);
        CraftableItem researchedCraftable =
                firstResearchGatedCraftable(manufacturing);

        require(researchedShip != null
                        && researchedPackage != null
                        && researchedCraftable != null,
                "The real configs need gated production content.");

        menu.showForBase(world, shipyard, 760, 440);
        require(menu.entryTitlesForTest().contains(
                        "Build " + Rules.ship(researchedShip).name),
                "Researched ship did not appear in the shipyard.");

        menu.showForBase(world, outpost, 760, 440);
        require(menu.entryTitlesForTest().contains(
                        "Load " + Rules.base(researchedPackage).name),
                "Researched station package did not appear in the outpost.");

        menu.showForBase(world, manufacturing, 760, 440);
        require(menu.entryTitlesForTest().stream()
                        .anyMatch(title -> title.startsWith("Manufacturing | ")
                                || title.equals(
                                "Manufacture " + researchedCraftable.name)),
                "Researched crafting content did not appear in manufacturing.");
    }

    private static void validatePreciseScrollingAndStableBottom(
            BuildMenu menu, World world, Base shipyard) {
        fill(shipyard);
        for (int i = 0; i < 4; i++) {
            require(world.buildShip(shipyard.id, "prospector"),
                    "Could not create mixed-height real queue rows.");
        }

        menu.showForBase(world, shipyard, 760, 440);
        draw(menu);

        require(menu.overflowForTest(),
                "The researched shipyard menu must overflow the test viewport.");

        Rectangle bounds = menu.menuBoundsForTest();
        int insideX = bounds.x + 24;
        int insideY = bounds.y + 54;
        int outsideX = bounds.x > 2
                ? bounds.x - 2
                : bounds.x + bounds.width + 2;

        require(!menu.scroll(
                        outsideX, insideY, 1.0,
                        VIEWPORT_WIDTH, VIEWPORT_HEIGHT),
                "Wheel input outside the production menu must fall through.");
        require(menu.scrollOffsetForTest() == 0,
                "Outside wheel input moved the production menu.");

        require(menu.scroll(
                        insideX, insideY, 0.10,
                        VIEWPORT_WIDTH, VIEWPORT_HEIGHT),
                "A fractional wheel event over the production menu was not consumed.");
        require(menu.scrollOffsetForTest() == 0,
                "One sub-threshold wheel event should accumulate, not jump.");

        require(menu.scroll(
                        insideX, insideY, 0.10,
                        VIEWPORT_WIDTH, VIEWPORT_HEIGHT),
                "The accumulated fractional wheel event was not consumed.");
        require(menu.scrollOffsetForTest() > 0,
                "Fractional wheel input did not advance the production menu.");

        for (int i = 0; i < 200; i++) {
            menu.scroll(insideX, insideY, 1.0,
                    VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        }

        require(menu.scrollOffsetForTest() == menu.maxScrollOffsetForTest(),
                "Mixed-height production rows could not reach the true bottom.");

        List<String> allTitles = menu.entryTitlesForTest();
        List<String> visibleTitles = menu.visibleEntryTitlesForTest();
        require(!allTitles.isEmpty()
                        && visibleTitles.contains(allTitles.get(allTitles.size() - 1)),
                "The final production entry is not visible at the bottom.");
    }

    private static void validateOverflowingStationMenus(
            BuildMenu menu, World world, List<Base> stations) {
        for (Base station : stations) {
            menu.showForBase(world, station, 760, 440);
            draw(menu);
            if (!menu.overflowForTest()) continue;

            Rectangle bounds = menu.menuBoundsForTest();
            int x = bounds.x + 24;
            int y = bounds.y + 54;
            int before = menu.scrollOffsetForTest();
            menu.scroll(x, y, 1.0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
            require(menu.scrollOffsetForTest() > before,
                    station.type().name
                            + " did not respond to wheel input.");
        }
    }

    private static void draw(BuildMenu menu) {
        BufferedImage image = new BufferedImage(
                VIEWPORT_WIDTH, VIEWPORT_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        menu.draw(graphics, VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        graphics.dispose();
    }

    private static String firstLockedShip(World world, Base shipyard) {
        for (String shipId : shipyard.type().buildableShips) {
            if (!ResearchRules.shipUnlocked(
                    world, shipyard.playerId, shipId)) return shipId;
        }
        return null;
    }

    private static String firstResearchGatedShip(Base shipyard) {
        for (String shipId : shipyard.type().buildableShips) {
            if (ResearchRules.firstTopicUnlockingShip(shipId) != null) return shipId;
        }
        return null;
    }

    private static String firstLockedPackage(World world, Base outpost) {
        for (String packageId : outpost.type().basePackages) {
            if (!StationPackageResearchRules.unlocked(
                    world, outpost.playerId, packageId)) return packageId;
        }
        return null;
    }

    private static String firstResearchGatedPackage(Base outpost) {
        for (String packageId : outpost.type().basePackages) {
            if (!StationPackageResearchRules.requiredResearchId(
                    packageId).isBlank()) return packageId;
        }
        return null;
    }

    private static CraftableItem firstLockedCraftable(
            World world, Base manufacturing) {
        for (CraftableItem item :
                CraftingRules.forStation(manufacturing.typeId)) {
            if (!item.unlockedFor(world, manufacturing.playerId)) return item;
        }
        return null;
    }

    private static CraftableItem firstResearchGatedCraftable(
            Base manufacturing) {
        World emptyResearchWorld = new World(
                "Craft Gate Probe", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        for (CraftableItem item :
                CraftingRules.forStation(manufacturing.typeId)) {
            if (!item.unlockedFor(
                    emptyResearchWorld, manufacturing.playerId)) return item;
        }
        return null;
    }

    private static Base base(
            World world, String playerId, String typeId, double x, double y) {
        String id = playerId + ":MENU:" + typeId;
        Base base = new Base(id, playerId, typeId, x, y);
        world.bases.put(id, base);
        return base;
    }

    private static void fill(Base base) {
        for (Material material : Material.values()) {
            base.inventory.put(material, 100_000.0);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(
                    "Production menu validation failed: " + message);
        }
    }
}
