package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

final class CelestialSystem {
    private final List<Body> bodies = new ArrayList<>();
    private final double sunX;
    private final double sunY;
    private final double originX;
    private final double originY;
    private final StarSystemDefinition definition;
    private final SystemVisualProfile visualProfile;

    CelestialSystem(int worldW, int worldH, Random random) {
        this(StarSystems.defaultSystem(), random);
    }

    CelestialSystem(StarSystemDefinition definition, Random random) {
        this(definition, random, 0, 0);
    }

    CelestialSystem(StarSystemDefinition definition, Random random, double offsetX, double offsetY) {
        this.definition = definition;
        this.originX = offsetX;
        this.originY = offsetY;
        this.visualProfile = SystemVisualProfiles.forSystem(definition).orElse(null);
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
        c.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        if (visualProfile != null) {
            SystemBackdropRenderer.draw(c, definition, visualProfile, originX, originY);
        }
        c.setStroke(new BasicStroke(1f));
        for (Body body : bodies) if (body.parent != null) drawOrbit(c, body);
        for (Body body : bodies) drawBody(c, body);
        c.dispose();
    }

    double sunX() { return sunX; }
    double sunY() { return sunY; }

    private void drawOrbit(Graphics2D g2, Body body) {
        double cx = body.parent.x;
        double cy = body.parent.y;
        int d = (int)Math.round(body.orbitRadius * 2);
        int alpha = visualProfile == null ? (body.parent.parent == null ? 42 : 32) : (body.parent.parent == null ? 30 : 22);
        Color orbitColor = visualProfile == null ? new Color(120, 155, 190, alpha)
                : withAlpha(visualProfile.starColor(), alpha);
        g2.setColor(orbitColor);
        g2.drawOval((int)Math.round(cx - body.orbitRadius), (int)Math.round(cy - body.orbitRadius), d, d);
    }

    private void drawBody(Graphics2D g2, Body body) {
        SystemVisualProfile.BodyVisual visual = visualProfile == null ? null : visualProfile.body(body.id);
        if (visual == null) {
            drawLegacyBody(g2, body);
            return;
        }
        String style = visual.style() == null ? "planet" : visual.style().toLowerCase();
        if ("star".equals(style)) drawStar(g2, body, visual);
        else drawPlanet(g2, body, visual, style);
        drawLabel(g2, body, style);
    }

    private void drawLegacyBody(Graphics2D g2, Body body) {
        int r = (int)Math.round(body.radius);
        g2.setColor(new Color(body.color.getRed(), body.color.getGreen(), body.color.getBlue(), body.parent == null ? 80 : 35));
        g2.fillOval((int)(body.x - body.radius * 2.2), (int)(body.y - body.radius * 2.2),
                (int)(body.radius * 4.4), (int)(body.radius * 4.4));
        g2.setColor(body.color);
        g2.fillOval((int)(body.x - body.radius), (int)(body.y - body.radius), r * 2, r * 2);
        g2.setColor(new Color(255,255,255,150));
        g2.drawString(body.name, (int)(body.x + body.radius + 8), (int)(body.y - body.radius - 4));
    }

    private void drawStar(Graphics2D g2, Body body, SystemVisualProfile.BodyVisual visual) {
        double radius = body.radius;
        Paint oldPaint = g2.getPaint();
        Stroke oldStroke = g2.getStroke();

        RadialGradientPaint glow = new RadialGradientPaint(
                new Point2D.Double(body.x, body.y), (float)(radius * 2.75),
                new float[]{0f, 0.34f, 0.70f, 1f},
                new Color[]{withAlpha(visual.atmosphere(), 112), withAlpha(body.color, 65),
                        withAlpha(visual.atmosphere(), 20), withAlpha(body.color, 0)});
        g2.setPaint(glow);
        g2.fill(new Ellipse2D.Double(body.x - radius * 2.75, body.y - radius * 2.75,
                radius * 5.5, radius * 5.5));

        RadialGradientPaint core = new RadialGradientPaint(
                new Point2D.Double(body.x - radius * 0.28, body.y - radius * 0.30), (float)(radius * 1.38),
                new float[]{0f, 0.45f, 0.82f, 1f},
                new Color[]{Color.WHITE, visual.accent(), body.color, darken(body.color, 0.72)});
        g2.setPaint(core);
        g2.fill(new Ellipse2D.Double(body.x - radius, body.y - radius, radius * 2, radius * 2));

        g2.setPaint(oldPaint);
        int rays = 18;
        int hash = Math.abs(body.id.hashCode());
        for (int i = 0; i < rays; i++) {
            double angle = i * Math.PI * 2 / rays + (hash % 31) * 0.01;
            double inner = radius * (1.08 + ((hash + i * 17) % 12) / 100.0);
            double outer = radius * (1.26 + ((hash + i * 29) % 30) / 100.0);
            int alpha = 42 + ((hash + i * 13) % 38);
            g2.setColor(withAlpha(visual.atmosphere(), alpha));
            g2.setStroke(new BasicStroke((float)Math.max(1.0, radius * 0.008), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine((int)Math.round(body.x + Math.cos(angle) * inner),
                    (int)Math.round(body.y + Math.sin(angle) * inner),
                    (int)Math.round(body.x + Math.cos(angle) * outer),
                    (int)Math.round(body.y + Math.sin(angle) * outer));
        }
        g2.setStroke(oldStroke);
    }

    private void drawPlanet(Graphics2D g2, Body body, SystemVisualProfile.BodyVisual visual, String style) {
        double radius = body.radius;
        Paint oldPaint = g2.getPaint();

        RadialGradientPaint atmosphere = new RadialGradientPaint(
                new Point2D.Double(body.x, body.y), (float)(radius * 1.24),
                new float[]{0.72f, 0.90f, 1f},
                new Color[]{withAlpha(visual.atmosphere(), 0), withAlpha(visual.atmosphere(), 62),
                        withAlpha(visual.atmosphere(), 0)});
        g2.setPaint(atmosphere);
        g2.fill(new Ellipse2D.Double(body.x - radius * 1.24, body.y - radius * 1.24,
                radius * 2.48, radius * 2.48));

        RadialGradientPaint sphere = new RadialGradientPaint(
                new Point2D.Double(body.x - radius * 0.35, body.y - radius * 0.30), (float)(radius * 1.42),
                new float[]{0f, 0.48f, 0.78f, 1f},
                new Color[]{lighten(visual.accent(), 1.18), body.color, darken(body.color, 0.68), darken(body.color, 0.36)});
        g2.setPaint(sphere);
        Ellipse2D.Double disc = new Ellipse2D.Double(body.x - radius, body.y - radius, radius * 2, radius * 2);
        g2.fill(disc);
        g2.setPaint(oldPaint);

        Shape oldClip = g2.getClip();
        g2.clip(disc);
        if ("gas_giant".equals(style)) {
            drawGasBands(g2, body, visual);
        } else if ("ice".equals(style)) {
            drawIceDetails(g2, body, visual);
        } else {
            drawTerrestrialDetails(g2, body, visual);
        }
        g2.setClip(oldClip);

        g2.setColor(withAlpha(visual.atmosphere(), 125));
        g2.setStroke(new BasicStroke((float)Math.max(1.0, radius * 0.025)));
        g2.draw(disc);
    }

    private void drawGasBands(Graphics2D g2, Body body, SystemVisualProfile.BodyVisual visual) {
        double radius = body.radius;
        for (int i = -4; i <= 4; i++) {
            double y = body.y + i * radius * 0.20;
            double bandHeight = radius * (0.085 + Math.floorMod(body.id.hashCode() + i * 19, 5) * 0.012);
            Color band = (i & 1) == 0 ? visual.accent() : visual.bands();
            int alpha = 46 + Math.floorMod(body.id.hashCode() + i * 23, 34);
            g2.setColor(withAlpha(band, alpha));
            g2.fill(new Rectangle2D.Double(body.x - radius * 1.05, y - bandHeight * 0.5, radius * 2.1, bandHeight));
        }
        g2.setColor(withAlpha(visual.storm(), 150));
        g2.fill(new Ellipse2D.Double(body.x + radius * 0.22, body.y + radius * 0.22,
                radius * 0.42, radius * 0.19));
        g2.setColor(withAlpha(lighten(visual.storm(), 1.18), 105));
        g2.draw(new Ellipse2D.Double(body.x + radius * 0.24, body.y + radius * 0.235,
                radius * 0.36, radius * 0.15));
    }

    private void drawTerrestrialDetails(Graphics2D g2, Body body, SystemVisualProfile.BodyVisual visual) {
        double radius = body.radius;
        int hash = Math.abs(body.id.hashCode());
        g2.setColor(withAlpha(visual.bands(), 105));
        for (int i = 0; i < 6; i++) {
            double angle = (hash * 0.013 + i * 1.71) % (Math.PI * 2);
            double distance = radius * (0.18 + ((hash + i * 31) % 45) / 100.0);
            double size = radius * (0.14 + ((hash + i * 11) % 18) / 100.0);
            double x = body.x + Math.cos(angle) * distance;
            double y = body.y + Math.sin(angle) * distance;
            g2.fill(new Ellipse2D.Double(x - size * 0.5, y - size * 0.35, size, size * 0.7));
        }
    }

    private void drawIceDetails(Graphics2D g2, Body body, SystemVisualProfile.BodyVisual visual) {
        double radius = body.radius;
        g2.setColor(withAlpha(visual.accent(), 105));
        g2.setStroke(new BasicStroke((float)Math.max(1.0, radius * 0.035), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = -2; i <= 2; i++) {
            double y = body.y + i * radius * 0.22;
            g2.drawLine((int)Math.round(body.x - radius * 0.72), (int)Math.round(y - radius * 0.10),
                    (int)Math.round(body.x + radius * 0.58), (int)Math.round(y + radius * 0.08));
        }
    }

    private void drawLabel(Graphics2D g2, Body body, String style) {
        double sx = Math.hypot(g2.getTransform().getScaleX(), g2.getTransform().getShearX());
        if (sx < 0.38 && !"star".equals(style)) return;
        g2.setColor(new Color(232, 240, 255, 178));
        g2.drawString(body.name, (int)Math.round(body.x + body.radius + 10),
                (int)Math.round(body.y - body.radius - 6));
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static Color darken(Color color, double factor) {
        return new Color(clamp(color.getRed() * factor), clamp(color.getGreen() * factor), clamp(color.getBlue() * factor));
    }

    private static Color lighten(Color color, double factor) {
        return new Color(clamp(color.getRed() * factor), clamp(color.getGreen() * factor), clamp(color.getBlue() * factor));
    }

    private static int clamp(double value) {
        return (int)Math.max(0, Math.min(255, Math.round(value)));
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

        Body(String id, String name, Body parent, double x, double y, double orbitRadius, double angle,
             double orbitSpeed, double radius, Color color) {
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
    }
}
