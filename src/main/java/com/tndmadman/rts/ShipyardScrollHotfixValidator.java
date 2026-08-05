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
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Guards scrolling, compact presentation, previews, hover-only costs, and bounded fit-store access. */
public final class ShipyardScrollHotfixValidator {
    private static final int VIEWPORT_WIDTH = 800;
    private static final int VIEWPORT_HEIGHT = 480;
    private static final Path BUILD_MENU = Path.of(
            "src/main/java/com/tndmadman/rts/BuildMenu.java");
    private static final String FIT_STORE_PROPERTY = "starchem.fitStore";

    private ShipyardScrollHotfixValidator() { }

    public static void main(String[] args) throws Exception {
        String originalStore = System.getProperty(FIT_STORE_PROPERTY);
        Path root = Files.createTempDirectory("starchem-menu-fit-cache-");
        try {
            System.setProperty(FIT_STORE_PROPERTY, root.resolve("fits.json").toString());
            ClientFitStore.resetCacheForTest();
            String source = Files.readString(BUILD_MENU, StandardCharsets.UTF_8);
            validateIntegration(source);
            SwingUtilities.invokeAndWait(ShipyardScrollHotfixValidator::validateRealStationMenus);
            require(ClientFitStore.loadCountForTest() <= 1,
                    "Opening production menus repeatedly reloaded the private fit library.");
            require(ClientFitStore.securitySetupCountForTest() <= 1,
                    "Opening production menus repeatedly reran private-file security setup.");
            System.out.println("Compact native production-menu previews, hover costs, scrolling, and fit caching validation passed.");
        } finally {
            ClientFitStore.resetCacheForTest();
            if (originalStore == null) System.clearProperty(FIT_STORE_PROPERTY);
            else System.setProperty(FIT_STORE_PROPERTY, originalStore);
            deleteTree(root);
        }
    }

    private static void validateIntegration(String source) {
        require(source.contains("new JScrollPane(content)"),
                "BuildMenu must retain its native JScrollPane.");
        require(source.contains("VERTICAL_SCROLLBAR_ALWAYS"),
                "Every production menu must retain a visible scrollbar.");
        require(source.contains("setToolTipText(entry.tooltip)"),
                "Required resources must be exposed through row tooltips.");
        require(source.contains("new ShipPreviewIcon(ship)"),
                "Ship rows need procedural ship previews.");
        require(source.contains("new StationPreviewIcon(station)"),
                "Station rows need station previews.");
        require(source.contains("new MaterialPreviewIcon(item.outputMaterial)"),
                "Manufacturing rows need material previews.");
        require(source.contains("new ResearchPreviewIcon(topic)"),
                "Research rows need research previews.");
        require(source.contains("ROW_H = 68"),
                "Normal production rows must stay compact.");
        require(!source.contains("drawScrollBar("),
                "The painted fake scrollbar must not return.");
        require(!source.contains("free || ResearchRules.shipUnlocked"),
                "Free-build mode must not reveal unresearched ships.");
        require(!source.contains("free || StationPackageResearchRules.unlocked"),
                "Free-build mode must not reveal unresearched station packages.");
        require(!source.contains("free || item.unlockedFor"),
                "Free-build mode must not reveal unresearched recipes.");
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

        validateHoverOnlyRequirementsAndIcons(
                menu, world, outpost, shipyard, laboratory, manufacturing);
        validateNativeWheelScrolling(menu, world, shipyard);
        validateOverflowingStationMenus(menu, world,
                List.of(outpost, shipyard, manufacturing));
    }

    private static void validateEveryStationOpens(
            BuildMenu menu, World world, List<Base> stations) {
        require(menu.normalRowHeightForTest() <= 68,
                "Production rows are not compact enough for the viewport.");
        for (Base station : stations) {
            menu.showForBase(world, station, 760, 440);
            require(!menu.entryTitlesForTest().isEmpty(),
                    station.type().name + " production menu is empty.");
            require(menu.scrollPaneForTest().getVerticalScrollBarPolicy()
                            == ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                    station.type().name + " does not always show its scrollbar.");
            for (String title : menu.entryTitlesForTest()) {
                require(menu.entryHasIconForTest(title),
                        station.type().name + " row has no preview icon: " + title);
            }
        }
    }

    private static void validateLockedItemsStayHiddenInFreeBuild(
            BuildMenu menu, World world,
            Base outpost, Base shipyard, Base manufacturing) {
        String lockedShip = firstLockedShip(world, shipyard);
        String lockedPackage = firstLockedPackage(world, outpost);
        CraftableItem lockedCraftable = firstLockedCraftable(world, manufacturing);

        require(lockedShip != null,
                "The shipyard config has no research-gated ship to test.");
        require(lockedPackage != null,
                "The outpost config has no research-gated package to test.");
        require(lockedCraftable != null,
                "The manufacturing config has no research-gated recipe to test.");

        menu.showForBase(world, shipyard, 760, 440);
        require(!menu.entryTitlesForTest().contains(
                        "Build " + Rules.ship(lockedShip).name),
                "Free-build mode exposed unresearched ship " + lockedShip + ".");

        menu.showForBase(world, outpost, 760, 440);
        require(!menu.entryTitlesForTest().contains(
                        "Load " + Rules.base(lockedPackage).name),
                "Free-build mode exposed unresearched station package " + lockedPackage + ".");

        menu.showForBase(world, manufacturing, 760, 440);
        require(!menu.entryTitlesForTest().contains(
                        "Manufacture " + lockedCraftable.name),
                "Free-build mode exposed unresearched recipe " + lockedCraftable.id + ".");
    }

    private static void validateHoverOnlyRequirementsAndIcons(
            BuildMenu menu, World world, Base outpost, Base shipyard,
            Base laboratory, Base manufacturing) {
        ShipType ship = firstBuildableShip(shipyard);
        require(ship != null, "No researched ship exists for presentation testing.");
        menu.showForBase(world, shipyard, 760, 440);
        assertHoverOnlyCost(menu, "Build " + ship.name,
                Rules.formatCost(ship.buildCost), "ship");

        BaseType station = firstBuildablePackage(outpost);
        require(station != null, "No researched station package exists for presentation testing.");
        menu.showForBase(world, outpost, 760, 440);
        assertHoverOnlyCost(menu, "Load " + station.name,
                Rules.formatCost(station.buildCost), "station package");

        List<ResearchTopic> topics = ResearchRules.forStation(laboratory.typeId);
        require(!topics.isEmpty(), "The laboratory has no research topics.");
        ResearchTopic topic = topics.get(0);
        menu.showForBase(world, laboratory, 760, 440);
        assertHoverOnlyCost(menu, "Research " + topic.name,
                Rules.formatCost(topic.requiredResources), "research");

        CraftingCategory category = firstCategoryWithRecipe(manufacturing);
        require(category != null, "Manufacturing has no recipe category to test.");
        CraftableItem item = CraftingRules.forStationAndCategory(
                manufacturing.typeId, category).stream()
                .filter(candidate -> candidate.unlockedFor(world, manufacturing.playerId))
                .findFirst().orElse(null);
        require(item != null, "Manufacturing category has no unlocked recipe.");
        menu.showCraftingCategoryForTest(world, manufacturing, category);
        assertHoverOnlyCost(menu, "Manufacture " + item.name,
                Rules.formatCost(item.requiredResources), "manufacturing recipe");

        for (String detail : menu.entryDetailsForTest()) {
            require(!detail.toLowerCase().contains("required resources"),
                    "Normal menu text exposes required resources: " + detail);
        }
    }

    private static void assertHoverOnlyCost(
            BuildMenu menu, String title, String formattedCost, String kind) {
        require(menu.entryTitlesForTest().contains(title),
                "Missing " + kind + " row: " + title);
        require(menu.entryHasIconForTest(title),
                "Missing preview icon for " + kind + ": " + title);
        String detail = menu.entryDetailForTest(title);
        String tooltip = menu.entryTooltipForTest(title);
        if (formattedCost != null && !formattedCost.isBlank()) {
            require(!detail.contains(formattedCost),
                    "Normal " + kind + " row still shows resource cost: " + detail);
            require(tooltip.contains(formattedCost),
                    "Hover tooltip omits " + kind + " resource cost.");
        }
        require(tooltip.contains("Required resources"),
                "Hover tooltip does not label required resources for " + kind + ".");
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
                        menu.entryTitlesForTest().get(menu.entryTitlesForTest().size() - 1)),
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
                    station.type().name + " native scrollbar did not move.");
        }
    }

    private static void draw(BuildMenu menu) {
        BufferedImage image = new BufferedImage(
                VIEWPORT_WIDTH, VIEWPORT_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        menu.draw(graphics, VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        graphics.dispose();
    }

    private static ShipType firstBuildableShip(Base shipyard) {
        for (String shipId : shipyard.type().buildableShips) {
            ShipType ship = Rules.ship(shipId);
            if (ship != null) return ship;
        }
        return null;
    }

    private static BaseType firstBuildablePackage(Base outpost) {
        for (String stationId : outpost.type().basePackages) {
            BaseType station = Rules.base(stationId);
            if (station != null) return station;
        }
        return null;
    }

    private static CraftingCategory firstCategoryWithRecipe(Base manufacturing) {
        for (CraftingCategory category : CraftingRules.categoriesForStation(manufacturing.typeId)) {
            if (!CraftingRules.forStationAndCategory(manufacturing.typeId, category).isEmpty()) {
                return category;
            }
        }
        return null;
    }

    private static String firstLockedShip(World world, Base shipyard) {
        for (String shipId : shipyard.type().buildableShips) {
            if (!ResearchRules.shipUnlocked(world, shipyard.playerId, shipId)) return shipId;
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

    private static CraftableItem firstLockedCraftable(World world, Base manufacturing) {
        for (CraftableItem item : CraftingRules.forStation(manufacturing.typeId)) {
            if (!item.unlockedFor(world, manufacturing.playerId)) return item;
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

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(
                    "Production menu validation failed: " + message);
        }
    }
}
