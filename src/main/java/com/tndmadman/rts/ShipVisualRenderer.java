package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/** Draws authored ship art when present and a deterministic neutral-hull fallback otherwise. */
final class ShipVisualRenderer {
    private static final Color FALLBACK_HULL = new Color(0x66727A);

    private ShipVisualRenderer() { }

    static void draw(Graphics2D g2, ShipType type, Color playerColor) {
        if (g2 == null || type == null) return;
        ShipVisualDefinition definition = VisualCatalog.ship(type);
        BufferedImage asset = ArtAssetManager.image(definition.assetPath());
        if (asset != null) {
            drawAuthored(g2, asset, definition);
            return;
        }
        drawFallback(g2, type, playerColor, definition);
    }

    static String visualId(ShipType type) {
        return VisualCatalog.ship(type).id();
    }

    private static void drawAuthored(Graphics2D g2, BufferedImage image,
                                     ShipVisualDefinition definition) {
        double scale = definition.assetScale();
        int width = Math.max(1, (int)Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int)Math.round(image.getHeight() * scale));
        g2.drawImage(image, -width / 2, -height / 2, width, height, null);
    }

    private static void drawFallback(Graphics2D g2, ShipType type, Color playerColor,
                                     ShipVisualDefinition definition) {
        Color hullColor = definition.neutralProceduralFallback() ? FALLBACK_HULL : playerColor;
        if (hullColor == null) hullColor = FALLBACK_HULL;
        ShipShape.draw(g2, type, hullColor);

        if (playerColor == null || definition.accentAlpha() <= 0) return;
        Path2D outline = ShipShape.create(type);
        Graphics2D accent = (Graphics2D) g2.create();
        accent.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        accent.setStroke(new BasicStroke((float)Math.max(1.0, type.size.scale * 0.55),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int alpha = (int)Math.round(definition.accentAlpha() * 255.0);
        accent.setColor(new Color(playerColor.getRed(), playerColor.getGreen(),
                playerColor.getBlue(), alpha));
        accent.draw(outline);
        accent.dispose();
    }
}
