package com.tndmadman.rts;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class CelestialSystem {
    private final List<Body> bodies = new ArrayList<>();
    private final double sunX;
    private final double sunY;

    CelestialSystem(int worldW, int worldH, Random random) {
        sunX = worldW / 2.0;
        sunY = worldH / 2.0;
        bodies.add(new Body("Sun", null, sunX, sunY, 0, 0, 0, 210, new Color(255, 205, 80)));
        Body inner = planet(random, "Inner Planet", 1450, 46, 0.018, new Color(80, 145, 210));
        planet(random, "Rock Planet", 2600, 68, -0.012, new Color(160, 115, 75));
        Body giant = planet(random, "Gas Giant", 4100, 110, 0.007, new Color(205, 150, 95));
        planet(random, "Outer Ice Planet", 5750, 78, -0.0045, new Color(150, 205, 230));
        moon(random, inner, "Inner Moon", 210, 18, 0.06, new Color(180, 185, 190));
        moon(random, giant, "Giant Moon A", 260, 24, -0.045, new Color(190, 175, 145));
        moon(random, giant, "Giant Moon B", 390, 18, 0.035, new Color(145, 165, 190));
        update(0);
    }

    void update(double dt) {
        for (int i = 1; i < bodies.size(); i++) bodies.get(i).update(dt);
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

    private Body planet(Random random, String name, double orbit, double radius, double speed, Color color) {
        Body body = new Body(name, bodies.get(0), 0, 0, orbit, random.nextDouble() * Math.PI * 2, speed, radius, color);
        bodies.add(body);
        return body;
    }

    private void moon(Random random, Body parent, String name, double orbit, double radius, double speed, Color color) {
        bodies.add(new Body(name, parent, 0, 0, orbit, random.nextDouble() * Math.PI * 2, speed, radius, color));
    }

    private void drawOrbit(Graphics2D g2, Body body) {
        double cx = body.parent.x;
        double cy = body.parent.y;
        int d = (int)Math.round(body.orbitRadius * 2);
        g2.setColor(new Color(120, 155, 190, body.parent.parent == null ? 42 : 32));
        g2.drawOval((int)Math.round(cx - body.orbitRadius), (int)Math.round(cy - body.orbitRadius), d, d);
    }

    private static final class Body {
        final String name;
        final Body parent;
        final double orbitRadius;
        final double orbitSpeed;
        final double radius;
        final Color color;
        double x;
        double y;
        double angle;

        Body(String name, Body parent, double x, double y, double orbitRadius, double angle, double orbitSpeed, double radius, Color color) {
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
