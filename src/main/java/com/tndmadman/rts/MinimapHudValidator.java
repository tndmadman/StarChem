package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Set;

public final class MinimapHudValidator {
    private MinimapHudValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem tactical minimap validation passed.");
    }

    static void validateOrThrow() {
        PlayerRegistry.reset("SOLO", "Minimap Validator", 0x50BEFF);
        World world = new World("Minimap Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        Base base = new Base("SOLO:B1", "SOLO", "outpost", world.width * 0.25, world.height * 0.35);
        Unit unit = new Unit("SOLO", 1, "prospector", world.width * 0.30, world.height * 0.40);
        world.bases.put(base.id, base);
        world.units.put(unit.key(), unit);

        int screenW = 1280;
        int screenH = 720;
        GameCamera camera = new GameCamera();
        camera.update(world, screenW, screenH, 0.016);
        MinimapHud hud = new MinimapHud();
        BufferedImage image = new BufferedImage(screenW, screenH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        hud.draw(graphics, world, camera, screenW, screenH);

        Rectangle bounds = hud.bounds(world, screenW, screenH);
        require(bounds.width >= 190 && bounds.height >= 100, "minimap did not scale to a usable size");
        require(bounds.x + bounds.width <= screenW && bounds.y + bounds.height <= screenH,
                "minimap exceeded the window bounds");
        require(bounds.y >= 150, "minimap overlaps the main status HUD");

        double targetX = world.width * 0.72;
        double targetY = world.height * 0.64;
        Point targetPoint = hud.pointForWorld(world, targetX, targetY, screenW, screenH);
        require(hud.click(world, camera, targetPoint.x, targetPoint.y, screenW, screenH),
                "click inside minimap was not consumed");
        Rectangle2D view = camera.visibleWorldRect(screenW, screenH);
        require(view.contains(targetX, targetY), "minimap click did not pan the camera to the target");
        require(!hud.click(world, camera, 2, 2, screenW, screenH),
                "click outside minimap was incorrectly consumed");

        Unit hiddenEnemy = new Unit("ENEMY", 1, "prospector", world.width * 0.80, world.height * 0.22);
        world.units.put(hiddenEnemy.key(), hiddenEnemy);
        hud.draw(graphics, world, camera, screenW, screenH);
        require(hud.pingCount() == 0, "enemy outside sensor coverage created a minimap ping");

        Unit visibleEnemy = new Unit("ENEMY", 2, "prospector", unit.x + 120, unit.y);
        world.units.put(visibleEnemy.key(), visibleEnemy);
        hud.draw(graphics, world, camera, screenW, screenH);
        require(hud.pingCount() > 0, "new sensor-visible enemy contact did not create a minimap ping");

        world.units.remove(unit.key());
        hud.draw(graphics, world, camera, screenW, screenH);
        require(hud.pingCount() > 0, "lost friendly contact did not retain a minimap ping");
        graphics.dispose();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
