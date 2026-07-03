package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.util.EnumMap;

final class World {
    final int width = 2200;
    final int height = 1400;
    final String localPlayerName;
    final EnumMap<Material, Double> stockpile = new EnumMap<>(Material.class);
    String status = "Modular world skeleton loaded. Simulation files are being split out next.";

    World(String localPlayerName) {
        this.localPlayerName = Config.clean(localPlayerName);
    }

    void update(double dt) { }

    void draw(Graphics2D g2) {
        g2.setColor(new Color(9, 15, 24));
        g2.fillRect(0, 0, width, height);
        g2.setColor(new Color(22, 33, 48));
        for (int x = 0; x <= width; x += 80) g2.drawLine(x, 0, x, height);
        for (int y = 0; y <= height; y += 80) g2.drawLine(0, y, width, y);
        g2.setColor(new Color(80, 190, 255));
        g2.fillOval(210, 250, 30, 30);
        g2.setColor(Color.WHITE);
        g2.drawString("Modular StarChem build", 250, 270);
    }

    boolean buildShip(String baseId, String shipTypeId) {
        status = "Build menu skeleton clicked: " + Rules.ship(shipTypeId).name;
        return false;
    }

    boolean loadBasePackage(String baseId, String packageType) {
        status = "Package menu skeleton clicked: " + Rules.base(packageType).name;
        return false;
    }

    boolean placePackage(Unit unit) {
        status = "Placement skeleton clicked.";
        return false;
    }

    int selectedCount() { return 0; }
    Rectangle2D localBounds() { return new Rectangle2D.Double(220, 260, 1, 1); }
}
