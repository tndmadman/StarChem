package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;

final class WormholeIndicator {
    private static final int MARGIN = 28;

    private WormholeIndicator() { }

    static void draw(Graphics2D g2, World world, GameCamera camera, int screenW, int screenH) {
        if (screenW <= 0 || screenH <= 0) return;
        // GamePanel invokes this screen-space pass after world/FOW rendering and before
        // HUDs. Draw discovered event weather here so the effect cannot tint menus,
        // the minimap, or the galaxy map and never bypasses event fog-of-war rules.
        GalaxyEventVisualOverlay.draw(g2, world, screenW, screenH);
        for (FogOfWarView.KnownWormhole gate : FogOfWarView.knownWormholes(world)) {
            drawOne(g2, gate, camera, screenW, screenH);
        }
    }

    private static void drawOne(Graphics2D g2, FogOfWarView.KnownWormhole gate,
                                GameCamera camera, int screenW, int screenH) {
        Point2D sp = camera.worldToScreen(gate.x(), gate.y());
        double rawX = sp.getX();
        double rawY = sp.getY();
        boolean visible = rawX >= MARGIN && rawX <= screenW - MARGIN && rawY >= MARGIN && rawY <= screenH - MARGIN;
        double x = Calc.clamp(rawX, MARGIN, screenW - MARGIN);
        double y = Calc.clamp(rawY, MARGIN, screenH - MARGIN);
        double cx = screenW / 2.0;
        double cy = screenH / 2.0;
        double angle = Math.atan2(rawY - cy, rawX - cx);
        Graphics2D a = (Graphics2D) g2.create();
        a.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        a.translate(x, y);
        a.rotate(angle);
        a.setColor(new Color(0, 0, 0, visible ? 95 : 165));
        a.fillOval(-13, -13, 26, 26);
        a.setColor(visible ? new Color(100, 235, 255, 190) : new Color(130, 220, 255, 235));
        int[] xs = {10, -6, -6};
        int[] ys = {0, -7, 7};
        a.fillPolygon(xs, ys, 3);
        a.setTransform(new AffineTransform());
        String text = gate.toSystemId();
        FontMetrics fm = g2.getFontMetrics();
        int tw = fm.stringWidth(text);
        int tx = (int)Calc.clamp(x - tw / 2.0, 4, Math.max(4, screenW - tw - 4));
        int ty = (int)Calc.clamp(y + 24, 18, Math.max(18, screenH - 6));
        g2.setColor(new Color(0, 0, 0, 120));
        g2.fillRoundRect(tx - 4, ty - 13, tw + 8, 16, 7, 7);
        g2.setColor(new Color(220, 245, 255, 220));
        g2.drawString(text, tx, ty);
        a.dispose();
    }
}
