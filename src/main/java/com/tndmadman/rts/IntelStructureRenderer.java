package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

final class IntelStructureRenderer {
    private IntelStructureRenderer() { }

    static double radius(String typeId) {
        if (IntelWarfareSystem.isJammer(typeId)) return 68;
        if (IntelWarfareSystem.isDecoy(typeId)) return 72;
        if (IntelWarfareSystem.CONTACT_STATION.equals(typeId)) return 48;
        return 0;
    }

    static boolean drawCore(Graphics2D source, Base base, Color playerColor) {
        if (source == null || base == null) return false;
        if (IntelWarfareSystem.isJammer(base.typeId)) {
            drawJammer(source, base, playerColor);
            return true;
        }
        if (IntelWarfareSystem.isDecoy(base.typeId)) {
            drawDecoy(source, base, playerColor);
            return true;
        }
        if (IntelWarfareSystem.CONTACT_STATION.equals(base.typeId)) {
            drawContact(source, base);
            return true;
        }
        return false;
    }

    static void drawStatus(Graphics2D source, Base base, double radius) {
        if (source == null || base == null || !PlayerRegistry.isLocal(base.playerId)) return;
        String text = "";
        Color color = new Color(125, 225, 255);
        if (IntelWarfareSystem.isRadar(base.typeId)) {
            IntelWarfareSystem.RadarMode mode = IntelWarfareSystem.radarMode(PlayerRegistry.activeWorld(), base);
            text = "RADAR " + mode.name() + " | Ctrl-click to cycle";
        } else if (IntelWarfareSystem.isJammer(base.typeId)) {
            text = "ECM ACTIVE";
            color = new Color(215, 120, 255);
        } else if (IntelWarfareSystem.isDecoy(base.typeId)) {
            text = "FALSE SIGNATURE ACTIVE";
            color = new Color(255, 185, 105);
        }
        if (text.isBlank()) return;
        int width = source.getFontMetrics().stringWidth(text) + 12;
        int x = (int)Math.round(base.x - width / 2.0);
        int y = (int)Math.round(base.y + radius + 34);
        source.setColor(new Color(0, 0, 0, 170));
        source.fillRoundRect(x, y, width, 18, 8, 8);
        source.setColor(color);
        source.drawString(text, x + 6, y + 13);
    }

    private static void drawJammer(Graphics2D source, Base base, Color playerColor) {
        Graphics2D g = (Graphics2D)source.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double time = System.nanoTime() / 1_000_000_000.0;
        double spin = time * 1.15;
        double pulse = time % 1.0;

        for (int i = 0; i < 4; i++) {
            double phase = (pulse + i * 0.25) % 1.0;
            double radius = 28 + phase * 52;
            int alpha = (int)Math.round(100 * (1 - phase));
            g.setColor(new Color(205, 80, 255, Math.max(0, alpha)));
            g.setStroke(new BasicStroke((float)(2.4 - phase * 1.4), BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND, 0, new float[]{8f, 6f}, (float)(phase * 10)));
            g.draw(new Ellipse2D.Double(base.x - radius, base.y - radius * 0.62,
                    radius * 2, radius * 1.24));
        }

        g.translate(base.x, base.y);
        g.rotate(spin);
        for (int i = 0; i < 3; i++) {
            double angle = i * Math.PI * 2 / 3.0;
            Graphics2D arm = (Graphics2D)g.create();
            arm.rotate(angle);
            arm.setColor(new Color(80, 34, 110, 230));
            arm.fillRoundRect(5, -5, 40, 10, 8, 8);
            arm.setColor(new Color(225, 145, 255, 230));
            arm.setStroke(new BasicStroke(2f));
            arm.drawLine(10, 0, 47, 0);
            arm.fill(new Ellipse2D.Double(42, -7, 14, 14));
            arm.dispose();
        }
        g.rotate(-spin * 1.8);
        g.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), 225));
        g.fill(new Ellipse2D.Double(-22, -22, 44, 44));
        g.setColor(new Color(18, 8, 28, 235));
        g.fill(new Ellipse2D.Double(-16, -16, 32, 32));
        g.setColor(new Color(245, 190, 255, 230));
        g.fill(new Ellipse2D.Double(-6, -6, 12, 12));
        g.setColor(new Color(210, 95, 255, 95));
        g.fill(new Arc2D.Double(-37, -37, 74, 74, -35, 70, Arc2D.PIE));
        g.dispose();
    }

    private static void drawDecoy(Graphics2D source, Base base, Color playerColor) {
        Graphics2D g = (Graphics2D)source.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double time = System.nanoTime() / 1_000_000_000.0;
        double flicker = 0.72 + Math.sin(time * 7.5) * 0.18 + Math.sin(time * 19.0) * 0.08;
        int alpha = Math.max(45, Math.min(220, (int)Math.round(175 * flicker)));

        for (int layer = 0; layer < 3; layer++) {
            double radius = 30 + layer * 15 + Math.sin(time * (1.8 + layer * 0.3)) * 4;
            Polygon polygon = new Polygon();
            for (int i = 0; i < 8; i++) {
                double angle = time * (layer % 2 == 0 ? 0.24 : -0.18) + i * Math.PI / 4.0;
                polygon.addPoint((int)Math.round(base.x + Math.cos(angle) * radius),
                        (int)Math.round(base.y + Math.sin(angle) * radius * 0.68));
            }
            g.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), alpha / (layer + 1)));
            g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    0, new float[]{7f, 5f}, layer * 3));
            g.drawPolygon(polygon);
        }

        Path2D silhouette = new Path2D.Double();
        silhouette.moveTo(base.x, base.y - 40);
        silhouette.lineTo(base.x + 32, base.y - 12);
        silhouette.lineTo(base.x + 48, base.y + 18);
        silhouette.lineTo(base.x + 12, base.y + 34);
        silhouette.lineTo(base.x - 38, base.y + 24);
        silhouette.lineTo(base.x - 46, base.y - 14);
        silhouette.closePath();
        g.setColor(new Color(255, 170, 80, Math.max(25, alpha / 4)));
        g.fill(silhouette);
        g.setColor(new Color(255, 215, 155, alpha));
        g.setStroke(new BasicStroke(2.2f));
        g.draw(silhouette);

        for (int i = 0; i < 6; i++) {
            double angle = time * 0.7 + i * Math.PI / 3.0;
            double orbit = 52 + Math.sin(time * 2 + i) * 6;
            double x = base.x + Math.cos(angle) * orbit;
            double y = base.y + Math.sin(angle) * orbit * 0.55;
            g.setColor(new Color(255, 195, 110, alpha));
            g.fill(new Ellipse2D.Double(x - 3, y - 3, 6, 6));
        }
        g.dispose();
    }

    private static void drawContact(Graphics2D source, Base base) {
        Graphics2D g = (Graphics2D)source.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double pulse = (System.nanoTime() / 1_000_000_000.0) % 1.0;
        double radius = 24 + pulse * 18;
        g.setColor(new Color(175, 190, 205, (int)(150 * (1 - pulse))));
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                0, new float[]{5f, 5f}, 0));
        g.draw(new Ellipse2D.Double(base.x - radius, base.y - radius, radius * 2, radius * 2));
        g.setColor(new Color(150, 165, 180, 190));
        g.fill(new Ellipse2D.Double(base.x - 18, base.y - 18, 36, 36));
        g.setColor(Color.WHITE);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 24f));
        String mark = "?";
        int width = g.getFontMetrics().stringWidth(mark);
        g.drawString(mark, (float)(base.x - width / 2.0), (float)(base.y + 8));
        g.dispose();
    }
}
