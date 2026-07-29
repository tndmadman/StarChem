package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Guards the shipyard production-menu scrolling hotfix against stale-branch merges.
 */
public final class ShipyardScrollHotfixValidator {
    private static final Path BUILD_MENU = Path.of(
            "src/main/java/com/tndmadman/rts/BuildMenu.java");
    private static final Path GAME_PANEL = Path.of(
            "src/main/java/com/tndmadman/rts/GamePanel.java");

    private ShipyardScrollHotfixValidator() { }

    public static void main(String[] args) throws Exception {
        String buildMenu = Files.readString(BUILD_MENU, StandardCharsets.UTF_8);
        String gamePanel = Files.readString(GAME_PANEL, StandardCharsets.UTF_8);

        require(buildMenu.contains(
                        "boolean scroll(int sx, int sy, int wheelRotation, int viewportWidth, int viewportHeight)"),
                "BuildMenu must expose viewport-aware wheel scrolling.");
        require(buildMenu.contains("Rectangle viewport = viewport(viewportWidth, viewportHeight);"),
                "BuildMenu must calculate scrolling from stable viewport dimensions.");
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

        System.out.println("Shipyard scroll hotfix validation passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
