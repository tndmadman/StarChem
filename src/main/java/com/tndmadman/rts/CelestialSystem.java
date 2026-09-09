package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
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
            String visualId = definition.id() + "-" + bodyDef.id();
            Body body = new Body(bodyDef.id(), visualId, bodyDef.name(), parent, x, y,
                    bodyDef.orbitRadius(), angle, bodyDef.orbitSpeed(), bodyDef.radius(), bodyDef.color());
            bodies.add(body);
            byId.put(bodyDef.id(), body);
        }
        if (bodies.isEmpty()) {
            String visualId = definition.id() + "-sun";
            Body sun = new Body("sun", visualId, "Sun", null, sunX, sunY, 0, 0, 0,
                    210, new Color(255, 205, 80));
            bodies.add(sun);
        }
    }

    void update(double dt) {
        for (Body body : bodies) body.update(dt);
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
        g2.setColor(new Color(120, 155, 190, body.parent.parent == null ? 28 : 20));
        g2.drawOval((int)Math.round(cx - body.orbitRadius), (int)Math.round(cy - body.orbitRadius), d, d);
    }

    private static final class Body {
        final String id;
        final String visualId;
        final String name;
        final Body parent;
        final double orbitRadius;
        final double orbitSpeed;
        final double radius;
        final Color color;
        double x;
        double y;
        double angle;
        double visualPhase;

        Body(String id, String visualId, String name, Body parent, double x, double y,
             double orbitRadius, double angle, double orbitSpeed, double radius, Color color) {
            this.id = id;
            this.visualId = visualId;
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
            if (parent != null) {
                angle += orbitSpeed * dt;
                x = parent.x + Math.cos(angle) * orbitRadius;
                y = parent.y + Math.sin(angle) * orbitRadius;
            }
            if (Double.isFinite(dt)) visualPhase += dt * (parent == null ? 0.012 : 0.025);
        }

        void draw(Graphics2D g2) {
            if (!RenderCulling.visible(g2, x, y, radius * 2.4 + 48)) return;
            CelestialVisualDefinition visual = VisualCatalog.celestial(visualId);
            BufferedImage surface = ArtAssetManager.image(visual.surfaceAssetPath());

            if (parent == null) {
                drawStar(g2, surface);
            } else {
                drawPlanetaryBody(g2, surface, visual);
            }

            g2.setColor(new Color(225, 235, 244, 145));
            g2.drawString(name, (int)Math.round(x + radius + 10), (int)Math.round(y - radius - 6));
        }

        private void drawStar(Graphics2D g2, BufferedImage authored) {
            int r = Math.max(1, (int)Math.round(radius));
            g2.setColor(withAlpha(color, 16));
            fillCircle(g2, radius * 2.25);
            g2.setColor(withAlpha(color, 30));
            fillCircle(g2, radius * 1.70);
            g2.setColor(withAlpha(color, 58));
            fillCircle(g2, radius * 1.30);

            if (authored != null) {
                drawCentered(g2, authored, r * 2, r * 2);
                return;
            }

            g2.setColor(color.darker());
            fillCircle(g2, radius);
            g2.setColor(color);
            fillOffsetCircle(g2, -radius * 0.10, -radius * 0.08, radius * 0.92);
            g2.setColor(withAlpha(Color.WHITE, 58));
            fillOffsetCircle(g2, -radius * 0.24, -radius * 0.22, radius * 0.35);
        }

        private void drawPlanetaryBody(Graphics2D g2, BufferedImage authored,
                                       CelestialVisualDefinition visual) {
            int diameter = Math.max(2, (int)Math.round(radius * 2));
            g2.setColor(withAlpha(color, 24));
            fillCircle(g2, radius * 1.16);

            if (authored != null) {
                drawCentered(g2, authored, diameter, diameter);
                BufferedImage detail = ArtAssetManager.image(visual.detailAssetPath());
                if (detail != null) drawCentered(g2, detail, diameter, diameter);
                drawCloudLayer(g2, visual, diameter);
                return;
            }

            Graphics2D body = (Graphics2D) g2.create();
            body.clip(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
            body.setColor(color.darker().darker());
            body.fillOval((int)Math.round(x - radius), (int)Math.round(y - radius), diameter, diameter);

            body.setColor(color);
            body.fillOval((int)Math.round(x - radius * 1.12), (int)Math.round(y - radius * 1.08),
                    (int)Math.round(radius * 1.82), (int)Math.round(radius * 1.88));

            int hash = visualId.hashCode();
            Color detail = withAlpha(color.brighter(), 42);
            body.setColor(detail);
            for (int i = 0; i < 3; i++) {
                double bandY = y - radius * 0.55 + radius * 0.48 * i
                        + (((hash >>> (i * 5)) & 7) - 3) * radius * 0.015;
                int bandH = Math.max(1, (int)Math.round(radius * (0.08 + ((hash >>> (i * 3)) & 3) * 0.018)));
                body.fillRoundRect((int)Math.round(x - radius * 0.82), (int)Math.round(bandY),
                        (int)Math.round(radius * 1.38), bandH, bandH, bandH);
            }
            body.setColor(withAlpha(Color.WHITE, 32));
            body.fillOval((int)Math.round(x - radius * 0.56), (int)Math.round(y - radius * 0.55),
                    (int)Math.round(radius * 0.42), (int)Math.round(radius * 0.26));
            body.dispose();
        }

        private void drawCloudLayer(Graphics2D g2, CelestialVisualDefinition visual, int diameter) {
            BufferedImage clouds = ArtAssetManager.image(visual.cloudAssetPath());
            if (clouds == null) return;
            Graphics2D layer = (Graphics2D) g2.create();
            layer.rotate(visualPhase, x, y);
            drawCentered(layer, clouds, diameter, diameter);
            layer.dispose();
        }

        private void fillCircle(Graphics2D g2, double drawRadius) {
            fillOffsetCircle(g2, 0, 0, drawRadius);
        }

        private void fillOffsetCircle(Graphics2D g2, double dx, double dy, double drawRadius) {
            int d = Math.max(1, (int)Math.round(drawRadius * 2));
            g2.fillOval((int)Math.round(x + dx - drawRadius), (int)Math.round(y + dy - drawRadius), d, d);
        }

        private void drawCentered(Graphics2D g2, BufferedImage image, int width, int height) {
            g2.drawImage(image, (int)Math.round(x - width / 2.0), (int)Math.round(y - height / 2.0),
                    width, height, null);
        }

        private static Color withAlpha(Color source, int alpha) {
            return new Color(source.getRed(), source.getGreen(), source.getBlue(),
                    Math.max(0, Math.min(255, alpha)));
        }
    }
}