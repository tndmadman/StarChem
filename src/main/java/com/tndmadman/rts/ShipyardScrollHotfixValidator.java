package com.tndmadman.rts;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Guards the native Swing production popup shared by every station type.
 *
 * The historical class name is retained because CI already invokes it.
 */
public final class ShipyardScrollHotfixValidator {
    private static final int VIEWPORT_WIDTH = 800;
    private static final int VIEWPORT_HEIGHT = 480;
    private static final Path BUILD_MENU = Path.of(
            "src/main/java/com/tndmadman/rts/BuildMenu.java");

    private ShipyardScrollHotfixValidator() { }

    public static void main(String[] args) throws Exception {
        String buildMenuSource =
                Files.readString(BUILD_MENU, StandardCharsets.UTF_8);

        validateIntegration(buildMenuSource);
        SwingUtilities.invokeAndWait(ShipyardScrollHotfixValidator::validateRealStationMenus);

        System.out.println(
                "Native station production-menu scrolling and research filtering validation passed.");
    }

    private static void validateIntegration(String buildMenu) {
        require(buildMenu.contains("new JScrollPane(content)"),
                "BuildMenu must use a real Swing JScrollPane.");
        require(buildMenu.contains("VERTICAL_SCROLLBAR_ALWAYS"),
                "Every production menu must show a visible vertical scrollbar.");
        require(buildMenu.contains("setWheelScrollingEnabled(true)"),
                "Native wheel scrolling must be enabled.");
        require(buildMenu.contains("popup.show(invoker, x, y)"),
                "The production menu must be displayed as a Swing popup.");
        require(buildMenu.contains("MOUSE WHEEL / DRAG SCROLLBAR"),
                "Every production menu needs an obvious scrolling clue.");
        require(!buildMenu.contains("drawScrollBar("),
                "The legacy painted fake scrollbar must not return.");
        require(!buildMenu.contains("free || ResearchRules.shipUnlocked"),
                "Free-build mode must not reveal unresearched ships.");
        require(!buildMenu.contains("free || StationPackageResearchRules.unlocked"),
                "Free-build mode must not reveal unresearched station packages.");
        require(!buildMenu.contains("free || item.unlockedFor"),
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
        validateNativeWheelScrolling(menu, world, shipyard);
        validateOverflowingStationMenus(menu, world,
                List.of(outpost, shipyard, manufacturing));
    }

    private static void validateEveryStationOpens(
            BuildMenu menu, World world, List<Base> stations) {
        for (Base station : stations) {
            menu.showForBase(world, station, 760, 440);
            require(!menu.entryTitlesForTest().isEmpty(),
                    station.type().name + " production menu is empty.");
            require(menu.scrollPaneForTest().getVerticalScrollBarPolicy()
                            == ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                    station.type().name + " does not always show its scrollbar.");
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

    private static void validateNativeWheelScrolling(
            BuildMenu menu, World world, Base shipyard) {
        fill(shipyard);
        for (int i = 0; i < 5; i++) {
            require(world.buildShip(shipyard.id, "prospector"),
                    "Could not create real production queue rows.");
        }

        menu.showForBase(world, shipyard, 760, 440);
        draw(menu);
        require(menu.overflowForTest(),
                "The researched shipyard menu must overflow the test viewport.");

        JScrollPane pane = menu.scrollPaneForTest();
        JScrollBar bar = pane.getVerticalScrollBar();
        int before = bar.getValue();

        MouseWheelEvent event = new MouseWheelEvent(
                pane,
                MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(),
                0,
                20,
                20,
                0,
                false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                3,
                1);
        for (MouseWheelListener listener : pane.getMouseWheelListeners()) {
            listener.mouseWheelMoved(event);
        }

        require(bar.getValue() > before,
                "A real JScrollPane wheel listener did not move the production menu.");

        bar.setValue(bar.getMaximum() - bar.getVisibleAmount());
        require(menu.visibleEntryTitlesForTest().contains(
                        menu.entryTitlesForTest().get(
                                menu.entryTitlesForTest().size() - 1)),
                "The native scrollbar cannot expose the final production entry.");

        Rectangle bounds = menu.menuBoundsForTest();
        require(!menu.scroll(
                        Math.max(0, bounds.x - 2), bounds.y + 20, 1.0,
                        VIEWPORT_WIDTH, VIEWPORT_HEIGHT),
                "Wheel input outside the production popup must fall through.");
    }

    private static void validateOverflowingStationMenus(
            BuildMenu menu, World world, List<Base> stations) {
        for (Base station : stations) {
            menu.showForBase(world, station, 760, 440);
            draw(menu);
            if (!menu.overflowForTest()) continue;

            JScrollBar bar = menu.scrollPaneForTest().getVerticalScrollBar();
            int before = bar.getValue();
            bar.setValue(Math.min(
                    bar.getMaximum() - bar.getVisibleAmount(),
                    before + Math.max(1, bar.getUnitIncrement(1))));
            require(bar.getValue() > before,
                    station.type().name
                            + " native scrollbar did not move.");
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
