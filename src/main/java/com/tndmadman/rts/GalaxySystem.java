package com.tndmadman.rts;

import java.awt.geom.Point2D;

final class GalaxySystem {
    final String id;
    final StarSystemDefinition definition;
    final double offsetX;
    final double offsetY;
    final CelestialSystem celestials;

    GalaxySystem(String id, StarSystemDefinition definition, double offsetX, double offsetY, CelestialSystem celestials) {
        this.id = id;
        this.definition = definition;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.celestials = celestials;
    }

    GalaxySystem(String id, StarSystemDefinition definition, double ignoredSystemTime, CelestialSystem celestials) {
        this(id, definition, 0, 0, celestials);
    }

    double width() { return definition.width(); }
    double height() { return definition.height(); }
    double maxX() { return offsetX + width(); }
    double maxY() { return offsetY + height(); }
    double centerX() { return offsetX + width() / 2.0; }
    double centerY() { return offsetY + height() / 2.0; }

    boolean contains(double x, double y) {
        return x >= offsetX && y >= offsetY && x <= maxX() && y <= maxY();
    }

    Point2D point(double localX, double localY) {
        return new Point2D.Double(offsetX + localX, offsetY + localY);
    }
}
