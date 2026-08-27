package com.tndmadman.rts;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Set;

public final class MinimapHudValidator {
    private MinimapHudValidator() { }

    public static void main(String[] args) throws Exception {
        validateOrThrow();
        Issue295MultiplayerCommsValidator.validateOrThrow();
        System.out.println("StarChem tactical minimap and multiplayer communications validation passed.");
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

        validateRememberedWormholePresentation();
    }

    private static void validateRememberedWormholePresentation() {
        World world = new World("Remembered Wormhole Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset("SOLO", "Wormhole Observer", 0x50BEFF);
        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        world.wormholes.clear();

        double gateX = world.width * 0.56;
        double gateY = world.height * 0.52;
        Base radar = new Base("SOLO:RADAR", "SOLO", RadarTowerRules.TIER_ONE, gateX - 300, gateY);
        world.bases.put(radar.id, radar);
        world.wormholes.add(new WormholeGate("remembered-render-gate", world.activeSystemId(),
                "remembered-render-target", gateX, gateY, gateX + 200, gateY));

        FogOfWarView.clearCachedStateForTest(world);
        FogOfWarView.forceRefreshForTest(world);
        require(FogOfWarView.knownWormholes(world).stream()
                        .anyMatch(gate -> "remembered-render-gate".equals(gate.id())),
                "presentation fixture did not observe its wormhole");

        world.wormholes.clear();
        FogOfWarView.forceRefreshForTest(world);
        require(FogOfWarView.knownWormholes(world).stream()
                        .anyMatch(gate -> "remembered-render-gate".equals(gate.id())),
                "remembered wormhole vanished when the live gate snapshot was removed");

        int screenW = 1280;
        int screenH = 720;
        GameCamera camera = new GameCamera();
        camera.centerAt(gateX, gateY, world, screenW, screenH);
        camera.update(world, screenW, screenH, 0.016);

        MinimapHud hud = new MinimapHud();
        BufferedImage minimapImage = new BufferedImage(screenW, screenH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D minimapGraphics = minimapImage.createGraphics();
        hud.draw(minimapGraphics, world, camera, screenW, screenH);
        minimapGraphics.dispose();
        Point marker = hud.pointForWorld(world, gateX, gateY, screenW, screenH);
        require(cyanPixelCount(minimapImage, marker.x, marker.y, 7) >= 4,
                "remembered wormhole was not rendered on the tactical minimap");

        BufferedImage indicatorImage = new BufferedImage(screenW, screenH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D indicatorGraphics = indicatorImage.createGraphics();
        WormholeIndicator.draw(indicatorGraphics, world, camera, screenW, screenH);
        indicatorGraphics.dispose();
        Point2D screenPoint = camera.worldToScreen(gateX, gateY);
        require(nonTransparentPixelCount(indicatorImage, (int)Math.round(screenPoint.getX()),
                        (int)Math.round(screenPoint.getY()), 18) > 0,
                "remembered wormhole was not rendered by the tactical directional indicator");

        FogOfWarView.clearCachedStateForTest(world);
    }

    private static int cyanPixelCount(BufferedImage image, int centerX, int centerY, int radius) {
        int count = 0;
        int minX = Math.max(0, centerX - radius);
        int maxX = Math.min(image.getWidth() - 1, centerX + radius);
        int minY = Math.max(0, centerY - radius);
        int maxY = Math.min(image.getHeight() - 1, centerY + radius);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                int red = (argb >>> 16) & 0xFF;
                int green = (argb >>> 8) & 0xFF;
                int blue = argb & 0xFF;
                if (alpha >= 100 && green >= 180 && blue >= 210 && blue - red >= 50) count++;
            }
        }
        return count;
    }

    private static int nonTransparentPixelCount(BufferedImage image, int centerX, int centerY, int radius) {
        int count = 0;
        int minX = Math.max(0, centerX - radius);
        int maxX = Math.min(image.getWidth() - 1, centerX + radius);
        int minY = Math.max(0, centerY - radius);
        int maxY = Math.min(image.getHeight() - 1, centerY + radius);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) > 0) count++;
            }
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
