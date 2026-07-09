package com.tndmadman.rts;

import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

final class CelestialSystem {
    private final List<Body> bodies = new ArrayList<>();
    private final double sunX;
    private final double sunY;

    CelestialSystem(int worldW, int worldH, Random random) {
        this(StarSystems.defaultSystem(), random);
    }

    CelestialSystem(StarSystemDefinition definition, Random random) {
        this(definition, random, 0, 0);
    }

    CelestialSystem(StarSystemDefinition definition, Random random, double offsetX, double offsetY) {
        sunX = offsetX + definition.width() / 2.0;
        sunY = offsetY + definition.height() / 2.0;
        buildBodies(definition, random);
        update(0);
    }

    private void buildBodies(StarSystemDefinition definition, Random random) {
        Map<String, Body> byId = new LinkedHashMap<>();
        for (CelestialBodyDefinition bodyDef : definition.bodies()) {
            Body parent = bodyDef.parentId() == null ? null : byId.get(bodyDef.parentId());
            double x = parent == null ? sunX : 0;
            double y = parent == null ? sunY : 0;
            double angle = bodyDef.orbitRadius() <= 0 ? 0 : random.nextDouble() * Math.PI * 2;
            Body body = new Body(bodyDef.id(), bodyDef.name(), parent, x, y, bodyDef.orbitRadius(), angle,
                    bodyDef.orbitSpeed(), bodyDef.radius(), bodyDef.color());
            bodies.add(body);
            byId.put(bodyDef.id(), body);
        }
        if (bodies.isEmpty()) {
            Body sun = new Body("sun", "Sun", null, sunX, sunY, 0, 0, 0, 210, new Color(255, 205, 80));
            bodies.add(sun);
        }
    }

    void update(double dt) {
        for (Body body : bodies) if (body.parent != null) body.update(dt);
    }

    void draw(Graphics2D g2) {
        Graphics2D c = (Graphics2D) g2.create();
        c.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        c.setStroke(new BasicStroke(1f));
        for (Body body : bodies) if (body.parent != null) drawOrbit(c, body);
        for (Body body : bodies) body.draw(c);
        c.dispose();
    }

    double sunX() { return sunX; }
    double sunY() { return sunY; }

    private void drawOrbit(Graphics2D g2, Body body) {
        double cx = body.parent.x;
        double cy = body.parent.y;
        int d = (int)Math.round(body.orbitRadius * 2);
        g2.setColor(new Color(120, 155, 190, body.parent.parent == null ? 42 : 32));
        g2.drawOval((int)Math.round(cx - body.orbitRadius), (int)Math.round(cy - body.orbitRadius), d, d);
    }

    private static final class Body {
        final String id;
        final String name;
        final Body parent;
        final double orbitRadius;
        final double orbitSpeed;
        final double radius;
        final Color color;
        double x;
        double y;
        double angle;

        Body(String id, String name, Body parent, double x, double y, double orbitRadius, double angle, double orbitSpeed, double radius, Color color) {
            this.id = id;
            this.name = name;
            this.parent = parent;
            this.x = x;
            this.y = y;
            this.orbitRadius = orbitRadius;
            this.angle = angle;
            this.orbitSpeed = orbitSpeed;
            this.radius = radius;
            this.color = color;
        }

        void update(double dt) {
            angle += orbitSpeed * dt;
            x = parent.x + Math.cos(angle) * orbitRadius;
            y = parent.y + Math.sin(angle) * orbitRadius;
        }

        void draw(Graphics2D g2) {
            int r = (int)Math.round(radius);
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), parent == null ? 80 : 35));
            g2.fillOval((int)(x - radius * 2.2), (int)(y - radius * 2.2), (int)(radius * 4.4), (int)(radius * 4.4));
            g2.setColor(color);
            g2.fillOval((int)(x - radius), (int)(y - radius), r * 2, r * 2);
            g2.setColor(new Color(255,255,255,150));
            g2.drawString(name, (int)(x + radius + 8), (int)(y - radius - 4));
        }
    }
}
