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
 * Guards the shipyard production-menu scrolling and unlock filtering behavior.
 */
public final class ShipyardScrollHotfixValidator {
    private static final Path BUILD_MENU = Path.of(
            "src/main/java/com/tndmadman/rts/BuildMenu.java");
    private static final Path GAME_PANEL = Path.of(
            "src/main/java/com/tndmadman/rts/GamePanel.java");

    private ShipyardScrollHotfixValidator() { }

    public static void main(String[] args) throws Exception {
        String buildMenuSource = Files.readString(BUILD_MENU, StandardCharsets.UTF_8);
        String gamePanelSource = Files.readString(GAME_PANEL, StandardCharsets.UTF_8);

        validateIntegration(buildMenuSource, gamePanelSource);
        validateWheelBehavior();

        System.out.println("Shipyard scroll and unlock filtering validation passed.");
    }

    private static void validateIntegration(String buildMenu, String gamePanel) {
        require(buildMenu.contains(
                        "boolean scroll(int sx, int sy, int wheelRotation, int viewportWidth, int viewportHeight)"),
                "BuildMenu must expose viewport-aware wheel scrolling.");
        require(buildMenu.contains("capture the wheel anywhere"),
                "An overflowing repositioned menu must capture wheel input globally.");
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
        require(gamePanel.contains("buildMenu.draw(g2, getWidth(), getHeight());"),
                "GamePanel must render BuildMenu using the full panel dimensions.");

        int wheelHandler = gamePanel.indexOf(
                "@Override public void mouseWheelMoved(MouseWheelEvent e)");
        int menuRoute = gamePanel.indexOf(
                "buildMenu.scroll(e.getX(), e.getY(), e.getWheelRotation(), getWidth(), getHeight())",
                wheelHandler);
        int cameraZoom = gamePanel.indexOf("camera.zoomAt(", wheelHandler);

        require(wheelHandler >= 0, "GamePanel mouse-wheel handler is missing.");
        require(menuRoute > wheelHandler,
                "GamePanel must route wheel input to BuildMenu.");
        require(cameraZoom > menuRoute,
                "BuildMenu must receive wheel input before camera zoom.");
    }

    @SuppressWarnings("unchecked")
    private static void validateWheelBehavior() throws Exception {
        BuildMenu menu = new BuildMenu();

        Field entriesField = BuildMenu.class.getDeclaredField("entries");
        entriesField.setAccessible(true);
        List<Object> entries = (List<Object>)entriesField.get(menu);

        Class<?> entryType = Class.forName("com.tndmadman.rts.BuildMenu$Entry");
        Constructor<?> entryConstructor = entryType.getDeclaredConstructors()[0];
        entryConstructor.setAccessible(true);
        for (int i = 0; i < 12; i++) {
            entries.add(entryConstructor.newInstance(
                    "Build test hull " + i,
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
                "The validator menu must overflow its viewport.");
        require(scrollOffsetField.getInt(menu) == 0,
                "A newly opened production menu must start at the top.");

        boolean consumed = menu.scroll(20, 50, 1, image.getWidth(), image.getHeight());
        require(consumed, "Wheel input inside the production menu must be consumed.");
        require(scrollOffsetField.getInt(menu) > 0,
                "Wheel-down input must advance the production menu.");

        int offsetAfterInsideWheel = scrollOffsetField.getInt(menu);
        boolean outsideConsumed = menu.scroll(790, 470, 1, image.getWidth(), image.getHeight());
        require(outsideConsumed,
                "An overflowing open production menu must capture wheel input even after it is repositioned.");
        require(scrollOffsetField.getInt(menu) > offsetAfterInsideWheel,
                "Wheel input outside the repositioned menu must still advance its list.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
