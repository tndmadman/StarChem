package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

final class CelestialSystem {
    private final List<Body> bodies = new ArrayList<>();
    private final StarSystemDefinition definition;
    private final double sunX;
    private final double sunY;
    private double visualTime;

    CelestialSystem(int worldW, int worldH, Random random) {
        this(StarSystems.defaultSystem(), random);
    }

    CelestialSystem(StarSystemDefinition definition, Random random) {
        this(definition, random, 0, 0);
    }

    CelestialSystem(StarSystemDefinition definition, Random random, double offsetX, double offsetY) {
        this.definition = definition;
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
        if (Double.isFinite(dt)) visualTime += dt;
        for (Body body : bodies) body.update(dt);
    }

    void draw(Graphics2D g2) {
        Graphics2D c = (Graphics2D) g2.create();
        c.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        SpaceBackgroundRenderer.draw(c, definition, visualTime);
        c.setStroke(new BasicStroke(1f));
        for (Body body : bodies) if (body.parent != null) drawOrbit(c, body);
        for (Body body : bodies) body.draw(c, definition, visualTime);
        c.dispose();
    }

    double sunX() { return sunX; }
    double sunY() { return sunY; }

    private void drawOrbit(Graphics2D g2, Body body) {
        double cx = body.parent.x;
        double cy = body.parent.y;
        int d = (int)Math.round(body.orbitRadius * 2);
        g2.setColor(new Color(120, 155, 190, body.parent.parent == null ? 18 : 12));
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

        void draw(Graphics2D g2, StarSystemDefinition system, double time) {
            if (!RenderCulling.visible(g2, x, y, radius * 2.6 + 54)) return;
            CelestialVisualDefinition visual = VisualCatalog.celestial(visualId);
            BufferedImage surface = ArtAssetManager.image(visual.surfaceAssetPath());

            if (parent == null) drawStar(g2, surface, time);
            else drawPlanetaryBody(g2, surface, visual, system, time);

            double scale = SelectionRenderPolicy.scale(g2);
            if (scale >= 0.32) {
                g2.setColor(new Color(220, 230, 240, scale < 0.7 ? 95 : 135));
                g2.drawString(name, (int)Math.round(x + radius + 9), (int)Math.round(y - radius - 6));
            }
        }

        private void drawStar(Graphics2D g2, BufferedImage authored, double time) {
            int r = Math.max(1, (int)Math.round(radius));
            double pulse = 0.96 + Math.sin(time * 0.7 + visualId.hashCode()) * 0.025;
            g2.setColor(withAlpha(color, 12)); fillCircle(g2, radius * 2.45 * pulse);
            g2.setColor(withAlpha(color, 24)); fillCircle(g2, radius * 1.85 * pulse);
            g2.setColor(withAlpha(color, 52)); fillCircle(g2, radius * 1.35 * pulse);

            if (authored != null) {
                drawCentered(g2, authored, r * 2, r * 2);
                return;
            }

            g2.setColor(color.darker()); fillCircle(g2, radius);
            g2.setColor(color); fillOffsetCircle(g2, -radius * 0.08, -radius * 0.06, radius * 0.94);
            g2.setColor(withAlpha(Color.WHITE, 55));
            fillOffsetCircle(g2, -radius * 0.25, -radius * 0.24, radius * 0.34);

            int seed = visualId.hashCode();
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI * 2 / 8.0 + ((seed >>> (i * 2)) & 7) * 0.025;
                double inner = radius * 1.03;
                double outer = radius * (1.15 + ((seed >>> (i * 3)) & 7) * 0.018);
                g2.setColor(withAlpha(color.brighter(), 32 + (i % 3) * 8));
                g2.draw(new Line2D.Double(x + Math.cos(a) * inner, y + Math.sin(a) * inner,
                        x + Math.cos(a) * outer, y + Math.sin(a) * outer));
            }
        }

        private void drawPlanetaryBody(Graphics2D g2, BufferedImage authored,
                                       CelestialVisualDefinition visual, StarSystemDefinition system, double time) {
            int diameter = Math.max(2, (int)Math.round(radius * 2));
            BodyStyle style = BodyStyle.of(this, system);
            if (style.rings) drawRings(g2, style, time);

            g2.setColor(withAlpha(style.atmosphere, style.hasAtmosphere ? 25 : 11));
            fillCircle(g2, radius * (style.hasAtmosphere ? 1.13 : 1.06));

            if (authored != null) {
                drawCentered(g2, authored, diameter, diameter);
                BufferedImage detail = ArtAssetManager.image(visual.detailAssetPath());
                if (detail != null) drawCentered(g2, detail, diameter, diameter);
                drawCloudLayer(g2, visual, diameter);
                drawLighting(g2);
                drawAtmosphereRim(g2, style);
                return;
            }

            Graphics2D body = (Graphics2D) g2.create();
            body.clip(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
            body.setColor(style.base.darker().darker());
            body.fillOval((int)Math.round(x - radius), (int)Math.round(y - radius), diameter, diameter);
            body.setColor(style.base);
            body.fillOval((int)Math.round(x - radius * 0.98), (int)Math.round(y - radius * 0.98),
                    (int)Math.round(radius * 1.96), (int)Math.round(radius * 1.96));

            switch (style.kind) {
                case GAS -> drawGasSurface(body, style, time);
                case ICE -> drawIceSurface(body, style);
                case LAVA -> drawLavaSurface(body, style);
                case TERRESTRIAL -> drawTerrestrialSurface(body, style, time);
                case DESERT -> drawDesertSurface(body, style);
                case ARTIFICIAL -> drawArtificialSurface(body, style);
                case ROCKY -> drawRockySurface(body, style);
            }
            body.dispose();

            drawLighting(g2);
            drawAtmosphereRim(g2, style);
        }

        private void drawGasSurface(Graphics2D g, BodyStyle style, double time) {
            int seed = visualId.hashCode();
            for (int i = 0; i < 8; i++) {
                double yy = y - radius * 0.78 + radius * 0.22 * i;
                double wobble = Math.sin(time * 0.08 + i * 1.7 + seed * 0.001) * radius * 0.025;
                int h = Math.max(2, (int)Math.round(radius * (0.07 + (i % 3) * 0.018)));
                Color band = i % 2 == 0 ? style.detail : style.base.brighter();
                g.setColor(withAlpha(band, 65 + (i % 3) * 15));
                g.fillRoundRect((int)Math.round(x - radius * 1.05 + wobble), (int)Math.round(yy),
                        (int)Math.round(radius * 2.1), h, h, h);
            }
            double stormX = x + radius * 0.35;
            double stormY = y + radius * 0.18;
            g.setColor(withAlpha(style.storm, 150));
            g.fill(new Ellipse2D.Double(stormX - radius * 0.18, stormY - radius * 0.10,
                    radius * 0.36, radius * 0.20));
            g.setColor(withAlpha(Color.WHITE, 30));
            g.draw(new Ellipse2D.Double(stormX - radius * 0.14, stormY - radius * 0.07,
                    radius * 0.28, radius * 0.14));
        }

        private void drawIceSurface(Graphics2D g, BodyStyle style) {
            int seed = visualId.hashCode();
            g.setColor(withAlpha(style.detail, 120));
            for (int i = 0; i < 9; i++) {
                double a = ((seed >>> (i % 16)) & 31) / 31.0 * Math.PI * 2;
                double len = radius * (0.25 + (i % 4) * 0.12);
                double sx = x + Math.cos(a) * radius * 0.18;
                double sy = y + Math.sin(a) * radius * 0.18;
                g.draw(new Line2D.Double(sx, sy, sx + Math.cos(a + 0.7) * len, sy + Math.sin(a + 0.7) * len));
            }
            g.setColor(withAlpha(Color.WHITE, 55));
            g.fill(new Ellipse2D.Double(x - radius * 0.52, y - radius * 0.65, radius * 0.52, radius * 0.25));
        }

        private void drawLavaSurface(Graphics2D g, BodyStyle style) {
            int seed = visualId.hashCode();
            g.setStroke(new BasicStroke((float)Math.max(1.0, radius * 0.025), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < 11; i++) {
                double a = (i * 2.399 + seed * 0.00013) % (Math.PI * 2);
                double sx = x + Math.cos(a) * radius * 0.18;
                double sy = y + Math.sin(a) * radius * 0.18;
                double ex = x + Math.cos(a + 0.5) * radius * (0.55 + (i % 3) * 0.12);
                double ey = y + Math.sin(a + 0.5) * radius * (0.55 + (i % 3) * 0.12);
                g.setColor(withAlpha(style.detail, 165 - (i % 4) * 18));
                g.draw(new Line2D.Double(sx, sy, ex, ey));
            }
        }

        private void drawTerrestrialSurface(Graphics2D g, BodyStyle style, double time) {
            int seed = visualId.hashCode();
            g.setColor(withAlpha(style.detail, 125));
            for (int i = 0; i < 7; i++) {
                double a = i * 2.1 + seed * 0.0007;
                double cx = x + Math.cos(a) * radius * (0.18 + (i % 3) * 0.19);
                double cy = y + Math.sin(a * 1.3) * radius * 0.48;
                double w = radius * (0.25 + (i % 2) * 0.18);
                double h = radius * (0.16 + (i % 3) * 0.07);
                g.fill(new Ellipse2D.Double(cx - w * 0.5, cy - h * 0.5, w, h));
            }
            g.setColor(new Color(240, 250, 255, 45));
            for (int i = 0; i < 4; i++) {
                double yy = y - radius * 0.52 + i * radius * 0.31;
                double shift = Math.sin(time * 0.04 + i) * radius * 0.08;
                g.fillRoundRect((int)(x - radius * 0.7 + shift), (int)yy,
                        (int)(radius * 1.25), Math.max(2, (int)(radius * 0.055)), 8, 8);
            }
        }

        private void drawDesertSurface(Graphics2D g, BodyStyle style) {
            g.setColor(withAlpha(style.detail, 72));
            for (int i = 0; i < 8; i++) {
                double yy = y - radius * 0.75 + i * radius * 0.21;
                double offset = Math.sin(i * 1.8 + visualId.hashCode()) * radius * 0.16;
                g.drawArc((int)(x - radius * 0.85 + offset), (int)yy,
                        (int)(radius * 1.45), (int)(radius * 0.28), 8, 164);
            }
        }

        private void drawArtificialSurface(Graphics2D g, BodyStyle style) {
            g.setColor(withAlpha(style.detail, 125));
            for (int ring = 1; ring <= 4; ring++) {
                double rr = radius * ring / 5.0;
                g.draw(new Ellipse2D.Double(x - rr, y - rr * 0.45, rr * 2, rr * 0.9));
            }
            g.setColor(new Color(110, 220, 255, 150));
            for (int i = 0; i < 12; i++) {
                double a = i * Math.PI * 2 / 12.0 + visualId.hashCode() * 0.0001;
                double rr = radius * (0.32 + (i % 3) * 0.16);
                g.fill(new Ellipse2D.Double(x + Math.cos(a) * rr - 1.6, y + Math.sin(a) * rr - 1.6, 3.2, 3.2));
            }
        }

        private void drawRockySurface(Graphics2D g, BodyStyle style) {
            int seed = visualId.hashCode();
            for (int i = 0; i < 12; i++) {
                double a = i * 2.37 + seed * 0.00031;
                double rr = radius * (0.12 + ((seed >>> (i % 15)) & 7) * 0.075);
                double cx = x + Math.cos(a) * rr;
                double cy = y + Math.sin(a * 1.17) * rr;
                double cr = radius * (0.035 + (i % 4) * 0.018);
                g.setColor(withAlpha(style.base.darker().darker(), 110));
                g.fill(new Ellipse2D.Double(cx - cr, cy - cr, cr * 2, cr * 2));
                g.setColor(withAlpha(style.detail, 45));
                g.draw(new Ellipse2D.Double(cx - cr * 0.8, cy - cr * 0.8, cr * 1.6, cr * 1.6));
            }
        }

        private void drawLighting(Graphics2D g2) {
            Body star = rootStar();
            double lx = star.x - x;
            double ly = star.y - y;
            double length = Math.max(1.0, Math.hypot(lx, ly));
            lx /= length; ly /= length;
            Graphics2D shade = (Graphics2D) g2.create();
            shade.clip(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
            double lightX = x + lx * radius;
            double lightY = y + ly * radius;
            double darkX = x - lx * radius;
            double darkY = y - ly * radius;
            shade.setPaint(new GradientPaint((float)lightX, (float)lightY, new Color(0, 0, 0, 0),
                    (float)darkX, (float)darkY, new Color(0, 0, 0, 205)));
            shade.fill(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
            shade.dispose();
        }

        private void drawAtmosphereRim(Graphics2D g2, BodyStyle style) {
            if (!style.hasAtmosphere) return;
            Stroke previous = g2.getStroke();
            g2.setStroke(new BasicStroke((float)Math.max(1.2, radius * 0.022)));
            g2.setColor(withAlpha(style.atmosphere, 110));
            g2.draw(new Ellipse2D.Double(x - radius * 1.015, y - radius * 1.015,
                    radius * 2.03, radius * 2.03));
            g2.setStroke(previous);
        }

        private void drawRings(Graphics2D g2, BodyStyle style, double time) {
            Graphics2D rings = (Graphics2D) g2.create();
            rings.rotate(-0.18 + Math.sin(visualId.hashCode()) * 0.08, x, y);
            rings.setColor(withAlpha(style.detail.brighter(), 70));
            rings.setStroke(new BasicStroke((float)Math.max(2.0, radius * 0.035)));
            rings.draw(new Ellipse2D.Double(x - radius * 1.55, y - radius * 0.42,
                    radius * 3.1, radius * 0.84));
            rings.setColor(withAlpha(style.atmosphere, 40));
            rings.setStroke(new BasicStroke((float)Math.max(1.0, radius * 0.016)));
            rings.draw(new Ellipse2D.Double(x - radius * 1.75, y - radius * 0.50,
                    radius * 3.5, radius));
            rings.dispose();
        }

        private Body rootStar() {
            Body root = this;
            while (root.parent != null) root = root.parent;
            return root;
        }

        private void drawCloudLayer(Graphics2D g2, CelestialVisualDefinition visual, int diameter) {
            BufferedImage clouds = ArtAssetManager.image(visual.cloudAssetPath());
            if (clouds == null) return;
            Graphics2D layer = (Graphics2D) g2.create();
            layer.rotate(visualPhase, x, y);
            drawCentered(layer, clouds, diameter, diameter);
            layer.dispose();
        }

        private void fillCircle(Graphics2D g2, double drawRadius) { fillOffsetCircle(g2, 0, 0, drawRadius); }

        private void fillOffsetCircle(Graphics2D g2, double dx, double dy, double drawRadius) {
            int d = Math.max(1, (int)Math.round(drawRadius * 2));
            g2.fillOval((int)Math.round(x + dx - drawRadius), (int)Math.round(y + dy - drawRadius), d, d);
        }

        private void drawCentered(Graphics2D g2, BufferedImage image, int width, int height) {
            g2.drawImage(image, (int)Math.round(x - width / 2.0), (int)Math.round(y - height / 2.0), width, height, null);
        }

        private static Color withAlpha(Color source, int alpha) {
            return new Color(source.getRed(), source.getGreen(), source.getBlue(), Math.max(0, Math.min(255, alpha)));
        }
    }

    private enum SurfaceKind { GAS, ICE, LAVA, TERRESTRIAL, DESERT, ARTIFICIAL, ROCKY }

    private record BodyStyle(SurfaceKind kind, Color base, Color detail, Color atmosphere, Color storm,
                             boolean hasAtmosphere, boolean rings) {
        static BodyStyle of(Body body, StarSystemDefinition system) {
            String key = (body.visualId + " " + body.name + " " + system.id() + " " + system.role()).toLowerCase();
            Color base = body.color;
            if (key.contains("giant") || key.contains("crown") || key.contains("gas"))
                return new BodyStyle(SurfaceKind.GAS, base, mix(base, new Color(220, 205, 180), 0.42),
                        base.brighter(), mix(base, new Color(205, 125, 92), 0.62), true, key.contains("crown") || (body.visualId.hashCode() & 3) == 0);
            if (key.contains("ice") || key.contains("frozen") || key.contains("cryo"))
                return new BodyStyle(SurfaceKind.ICE, mix(base, new Color(120, 165, 190), 0.48), new Color(190, 225, 240),
                        new Color(145, 210, 245), Color.WHITE, true, false);
            if (key.contains("lava") || key.contains("volcan") || key.contains("crucible") || key.contains("inferno"))
                return new BodyStyle(SurfaceKind.LAVA, mix(base, new Color(70, 45, 38), 0.52), new Color(255, 116, 38),
                        new Color(230, 93, 30), new Color(255, 205, 70), false, false);
            if (key.contains("earth") || key.contains("terra") || key.contains("garden") || key.contains("ocean"))
                return new BodyStyle(SurfaceKind.TERRESTRIAL, mix(base, new Color(45, 95, 125), 0.45), new Color(72, 125, 70),
                        new Color(90, 175, 230), Color.WHITE, true, false);
            if (key.contains("desert") || key.contains("dune") || key.contains("arid"))
                return new BodyStyle(SurfaceKind.DESERT, mix(base, new Color(164, 110, 62), 0.48), new Color(220, 172, 104),
                        new Color(210, 155, 95), Color.WHITE, true, false);
            if (key.contains("artificial") || key.contains("forge") || key.contains("industrial") || key.contains("station"))
                return new BodyStyle(SurfaceKind.ARTIFICIAL, mix(base, new Color(72, 82, 92), 0.58), new Color(140, 158, 174),
                        new Color(80, 175, 220), Color.WHITE, false, false);

            int variant = Math.floorMod(body.visualId.hashCode(), 5);
            if (variant == 0) return new BodyStyle(SurfaceKind.DESERT, mix(base, new Color(154, 108, 70), 0.35),
                    new Color(205, 157, 105), new Color(190, 145, 105), Color.WHITE, true, false);
            if (variant == 1) return new BodyStyle(SurfaceKind.ICE, mix(base, new Color(105, 145, 170), 0.32),
                    new Color(190, 220, 235), new Color(135, 195, 225), Color.WHITE, true, false);
            if (variant == 2) return new BodyStyle(SurfaceKind.TERRESTRIAL, mix(base, new Color(55, 92, 105), 0.28),
                    new Color(78, 115, 74), new Color(85, 165, 215), Color.WHITE, true, false);
            return new BodyStyle(SurfaceKind.ROCKY, mix(base, new Color(105, 98, 90), 0.35),
                    new Color(150, 142, 132), base.brighter(), Color.WHITE, false, false);
        }

        private static Color mix(Color a, Color b, double t) {
            double u = 1.0 - t;
            return new Color((int)Math.round(a.getRed() * u + b.getRed() * t),
                    (int)Math.round(a.getGreen() * u + b.getGreen() * t),
                    (int)Math.round(a.getBlue() * u + b.getBlue() * t));
        }
    }
}
