package com.tndmadman.rts;

import java.awt.*;
import java.util.EnumMap;

final class Base {
    final String id;
    final String playerId;
    final String typeId;
    final double x, y;

    Base(String id, String playerId, String typeId, double x, double y) {
        this.id = id; this.playerId = playerId; this.typeId = typeId; this.x = x; this.y = y;
    }

    BaseType type() { return Rules.base(typeId); }

    boolean contains(double wx, double wy) {
        return Calc.distance(wx, wy, x, y) <= (typeId.equals("shipyard") ? 82 : 64);
    }

    void draw(Graphics2D g2, Color ignoredColor, EnumMap<Material, Double> stockpile, boolean ignoredLocal) {
        Color playerColor = PlayerRegistry.color(playerId);
        boolean local = PlayerRegistry.isLocal(playerId);
        Graphics2D s = (Graphics2D) g2.create();
        s.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        BaseType def = type();
        double radius = typeId.equals("shipyard") ? 82 : 64;
        s.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), local ? 42 : 22));
        s.fillOval((int)(x - def.unloadRange), (int)(y - def.unloadRange), (int)(def.unloadRange * 2), (int)(def.unloadRange * 2));
        s.setColor(new Color(playerColor.getRed(), playerColor.getGreen(), playerColor.getBlue(), local ? 120 : 72));
        s.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{10f,8f}, 0));
        s.drawOval((int)(x - def.unloadRange), (int)(y - def.unloadRange), (int)(def.unloadRange * 2), (int)(def.unloadRange * 2));
        Polygon hull = new Polygon();
        for (int i = 0; i < 6; i++) {
            double a = Math.PI / 6 + i * Math.PI * 2 / 6.0;
            hull.addPoint((int)Math.round(x + Math.cos(a) * radius), (int)Math.round(y + Math.sin(a) * radius));
        }
        s.setColor(new Color(20,29,42)); s.fillPolygon(hull);
        s.setColor(playerColor); s.setStroke(new BasicStroke(3f)); s.drawPolygon(hull);
        s.setColor(new Color(125,205,255,90)); s.fillOval((int)(x - 26), (int)(y - 26), 52, 52);
        s.setFont(s.getFont().deriveFont(Font.BOLD, 12f));
        String label = def.name + " - " + PlayerRegistry.name(playerId);
        int tw = s.getFontMetrics().stringWidth(label);
        s.setColor(new Color(0,0,0,160));
        s.fillRoundRect((int)(x - tw / 2.0 - 6), (int)(y - radius - 32), tw + 12, 18, 8, 8);
        s.setColor(playerColor); s.drawString(label, (int)(x - tw / 2.0), (int)(y - radius - 18));
        s.dispose();
    }
}
