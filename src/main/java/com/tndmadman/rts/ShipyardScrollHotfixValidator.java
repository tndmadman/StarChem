package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Legacy-named CI guard for scrolling across every custom in-game menu and
 * research-gated production-item filtering.
 */
public final class ShipyardScrollHotfixValidator {
    private static final Path BUILD_MENU = Path.of(
            "src/main/java/com/tndmadman/rts/BuildMenu.java");
    private static final Path HUD_WINDOW = Path.of(
            "src/main/java/com/tndmadman/rts/HudWindow.java");
    private static final Path DEV_MENU = Path.of(
            "src/main/java/com/tndmadman/rts/DevMenu.java");
    private static final Path AI_DEV_PANEL = Path.of(
            "src/main/java/com/tndmadman/rts/AiDevPanel.java");
    private static final Path GAME_PANEL = Path.of(
            "src/main/java/com/tndmadman/rts/GamePanel.java");

    private ShipyardScrollHotfixValidator() { }

    public static void main(String[] args) throws Exception {
        String buildMenuSource = Files.readString(BUILD_MENU, StandardCharsets.UTF_8);
        String hudWindowSource = Files.readString(HUD_WINDOW, StandardCharsets.UTF_8);
        String devMenuSource = Files.readString(DEV_MENU, StandardCharsets.UTF_8);
        String aiDevPanelSource = Files.readString(AI_DEV_PANEL, StandardCharsets.UTF_8);
        String gamePanelSource = Files.readString(GAME_PANEL, StandardCharsets.UTF_8);

        validateIntegration(buildMenuSource, hudWindowSource, devMenuSource,
                aiDevPanelSource, gamePanelSource);
        validateProductionMenuWheelBehavior();
        validateHudWindowWheelBehavior();

        System.out.println("All custom game-menu scrolling and unlock filtering validation passed.");
    }

    private static void validateIntegration(String buildMenu, String hudWindow,
                                            String devMenu, String aiDevPanel,
                                            String gamePanel) {
        require(buildMenu.contains(
                        "boolean scroll(int sx, int sy, int wheelRotation, int viewportWidth, int viewportHeight)"),
                "BuildMenu must expose viewport-aware wheel scrolling.");
        require(buildMenu.contains("capture the wheel anywhere"),
                "An overflowing repositioned production menu must capture wheel input globally.");
        require(buildMenu.contains("drawScrollBar(g2)"),
                "Overflowing production menus must draw a visible scrollbar.");
        require(buildMenu.contains("Mouse wheel  "),
                "Overflowing production menus must display a mouse-wheel hint.");
        require(buildMenu.contains("StationPackageResearchRules.unlocked"),
                "Locked station packages must be filtered from production menus.");
        require(buildMenu.contains("if (!unlocked) continue;"),
                "Locked ships must be filtered from production menus.");
        require(buildMenu.contains("if (!unlocked) return;"),
                "Locked crafting recipes must be filtered from production menus.");
        require(!buildMenu.contains("LOCKED:"),
                "Locked production items must not be rendered as disabled menu rows.");

        require(hudWindow.contains("boolean scroll(int sx, int sy, int wheelRotation"),
                "Shared HUD windows must consume wheel input.");
        require(hudWindow.contains("Graphics2D bodyGraphics"),
                "Shared HUD windows must clip and translate overflowing menu bodies.");
        require(hudWindow.contains("SCROLL ↕"),
                "Overflowing HUD menus must show a visual scroll clue.");
        require(devMenu.contains("window.bodyGraphics"),
                "The developer crafting menu must use the shared scrolling viewport.");
        require(devMenu.contains("boolean scroll(int sx, int sy"),
                "The developer crafting menu must expose wheel handling.");
        require(aiDevPanel.contains("window.bodyGraphics"),
                "The AI developer menu must use the shared scrolling viewport.");
        require(aiDevPanel.contains("boolean scroll(int sx, int sy"),
                "The AI developer menu must expose wheel handling.");

        require(gamePanel.contains("buildMenu.draw(g2, getWidth(), getHeight());"),
                "GamePanel must render BuildMenu using the full panel dimensions.");
        require(gamePanel.contains("devMenu.draw(g2, world, canEditDev(), getHeight());"),
                "GamePanel must render DevMenu using the current viewport height.");
        require(gamePanel.contains(
                        "aiDevPanel.draw(g2, world, devAuthorityNetwork, canEditDev(), getHeight());"),
                "GamePanel must render AiDevPanel using the current viewport height.");

        int wheelHandler = gamePanel.indexOf(
                "@Override public void mouseWheelMoved(MouseWheelEvent e)");
        int productionRoute = gamePanel.indexOf(
                "buildMenu.scroll(e.getX(), e.getY(), e.getWheelRotation(), getWidth(), getHeight())",
                wheelHandler);
        int aiDevRoute = gamePanel.indexOf(
                "aiDevPanel.scroll(e.getX(), e.getY(), e.getWheelRotation(), getHeight())",
                wheelHandler);
        int devRoute = gamePanel.indexOf(
                "devMenu.scroll(e.getX(), e.getY(), e.getWheelRotation(), getHeight())",
                wheelHandler);
        int cameraZoom = gamePanel.indexOf("camera.zoomAt(", wheelHandler);

        require(wheelHandler >= 0, "GamePanel mouse-wheel handler is missing.");
        require(productionRoute > wheelHandler,
                "GamePanel must route wheel input to production menus.");
        require(aiDevRoute > productionRoute,
                "GamePanel must route wheel input to the AI developer menu.");
        require(devRoute > aiDevRoute,
                "GamePanel must route wheel input to the developer crafting menu.");
        require(cameraZoom > devRoute,
                "Every custom menu must receive wheel input before camera zoom.");
    }

    @SuppressWarnings("unchecked")
    private static void validateProductionMenuWheelBehavior() throws Exception {
        BuildMenu menu = new BuildMenu();

        Field entriesField = BuildMenu.class.getDeclaredField("entries");
        entriesField.setAccessible(true);
        List<Object> entries = (List<Object>)entriesField.get(menu);

        Class<?> entryType = Class.forName("com.tndmadman.rts.BuildMenu$Entry");
        Constructor<?> entryConstructor = entryType.getDeclaredConstructors()[0];
        entryConstructor.setAccessible(true);
        for (int i = 0; i < 12; i++) {
            entries.add(entryConstructor.newInstance(
                    "Production test item " + i,
                    "test cost",
                    "",
                    null,
                    List.of(),
                    false,
                    false,
                    false,
                    (Runnable)() -> { }));
        }

        menu.visible = true;
        BufferedImage image = new BufferedImage(800, 480, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        menu.draw(graphics, image.getWidth(), image.getHeight());
        graphics.dispose();

        Field visibleRowsField = BuildMenu.class.getDeclaredField("visibleRows");
        visibleRowsField.setAccessible(true);
        Field scrollOffsetField = BuildMenu.class.getDeclaredField("scrollOffset");
        scrollOffsetField.setAccessible(true);

        int visibleRows = visibleRowsField.getInt(menu);
        require(visibleRows > 0 && visibleRows < entries.size(),
                "The production-menu validator must overflow its viewport.");
        require(scrollOffsetField.getInt(menu) == 0,
                "A newly opened production menu must start at the top.");

        boolean consumed = menu.scroll(20, 50, 1, image.getWidth(), image.getHeight());
        require(consumed, "Wheel input inside a production menu must be consumed.");
        require(scrollOffsetField.getInt(menu) > 0,
                "Wheel-down input must advance a production menu.");

        int offsetAfterInsideWheel = scrollOffsetField.getInt(menu);
        boolean outsideConsumed = menu.scroll(790, 470, 1, image.getWidth(), image.getHeight());
        require(outsideConsumed,
                "An overflowing open production menu must capture wheel input after repositioning.");
        require(scrollOffsetField.getInt(menu) > offsetAfterInsideWheel,
                "Wheel input outside a repositioned production menu must still advance its list.");
    }

    private static void validateHudWindowWheelBehavior() {
        HudWindow window = new HudWindow(10, 50, 240);
        int bodyHeight = 720;
        int screenHeight = 300;

        require(window.height(bodyHeight, screenHeight) <= screenHeight - window.y,
                "An overflowing HUD menu must be clipped to the visible screen.");
        require(window.scroll(30, 100, 1, bodyHeight, screenHeight),
                "Wheel input over an overflowing HUD menu must be consumed.");
        require(window.scrollOffsetForTest() > 0,
                "Wheel-down input must advance an overflowing HUD menu.");

        int offset = window.scrollOffsetForTest();
        require(window.contentY(100) > 100 - window.bodyY(),
                "Scrolled HUD-menu hit testing must map to translated content coordinates.");
        require(!window.scroll(500, 100, 1, bodyHeight, screenHeight),
                "Wheel input outside a HUD menu must fall through to the next target.");
        require(window.scrollOffsetForTest() == offset,
                "Outside wheel input must not move a HUD menu.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
