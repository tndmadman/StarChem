package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
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
            Body body = new Body(definition.id(), bodyDef.id(), bodyDef.name(), parent, x, y, bodyDef.orbitRadius(), angle,
                    bodyDef.orbitSpeed(), bodyDef.radius(), bodyDef.color(),
                    VisualDefinitionCatalog.celestial(definition.id(), bodyDef.id()));
            bodies.add(body);
            byId.put(bodyDef.id(), body);
        }
        if (bodies.isEmpty()) {
            Body sun = new Body(definition.id(), "sun", "Sun", null, sunX, sunY, 0, 0, 0, 210,
                    new Color(255, 205, 80), VisualDefinitionCatalog.celestial(definition.id(), "sun"));
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
        SystemBackdropRenderer.draw(c, definition);
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
        final CelestialVisualDefinition visual;
        final long visualSeed;
        double x;
        double y;
        double angle;

        Body(String systemId, String id, String name, Body parent, double x, double y, double orbitRadius,
             double angle, double orbitSpeed, double radius, Color color, CelestialVisualDefinition visual) {
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
            this.visual = visual;
            this.visualSeed = ((long)systemId.hashCode() << 32) ^ id.hashCode() ^ Double.doubleToLongBits(radius);
        }

        void update(double dt) {
            angle += orbitSpeed * dt;
            x = parent.x + Math.cos(angle) * orbitRadius;
            y = parent.y + Math.sin(angle) * orbitRadius;
        }

        void draw(Graphics2D g2) {
            if (visual == null) {
                drawLegacy(g2);
                return;
            }
            if (visual.visualClass() == CelestialVisualClass.STAR
                    || visual.visualClass() == CelestialVisualClass.RED_STAR
                    || visual.visualClass() == CelestialVisualClass.PULSAR) {
                drawStar(g2);
            } else {
                drawPlanet(g2);
            }
            drawLabel(g2);
        }

        private void drawLegacy(Graphics2D g2) {
            int r = (int)Math.round(radius);
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), parent == null ? 80 : 35));
            g2.fillOval((int)(x - radius * 2.2), (int)(y - radius * 2.2), (int)(radius * 4.4), (int)(radius * 4.4));
            g2.setColor(color);
            g2.fillOval((int)(x - radius), (int)(y - radius), r * 2, r * 2);
            drawLabel(g2);
        }

        private void drawStar(Graphics2D g2) {
            Color secondary = new Color(visual.secondaryRgb());
            Color atmosphere = new Color(visual.atmosphereRgb());
            double corona = Math.max(.25, visual.corona());
            double glowRadius = radius * (1.55 + corona * .72);

            g2.setColor(alpha(atmosphere, 22));
            g2.fill(new Ellipse2D.Double(x - glowRadius, y - glowRadius, glowRadius * 2, glowRadius * 2));
            g2.setColor(alpha(color, 55));
            g2.fill(new Ellipse2D.Double(x - radius * 1.34, y - radius * 1.34, radius * 2.68, radius * 2.68));
            g2.setColor(color);
            g2.fill(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));

            Graphics2D surface = clippedSurface(g2);
            for (int i = 0; i < 9; i++) {
                double px = x + signedUnit(i, 17) * radius * .72;
                double py = y + signedUnit(i, 31) * radius * .72;
                double rr = radius * (.10 + unit(i, 47) * .18);
                surface.setColor(alpha(secondary, 45 + (int)(unit(i, 59) * 65)));
                surface.fill(new Ellipse2D.Double(px - rr, py - rr * .56, rr * 2, rr * 1.12));
            }
            surface.dispose();

            if (visual.visualClass() == CelestialVisualClass.PULSAR) {
                g2.setColor(alpha(atmosphere, 150));
                g2.setStroke(new BasicStroke((float)Math.max(1.4, radius * .045)));
                g2.drawLine((int)(x - radius * 2.8), (int)y, (int)(x + radius * 2.8), (int)y);
            }
        }

        private void drawPlanet(Graphics2D g2) {
            Color secondary = new Color(visual.secondaryRgb());
            Color atmosphere = new Color(visual.atmosphereRgb());

            if (visual.rings()) drawRings(g2, secondary);
            if (visual.atmosphereOpacity() > 0) {
                double ar = radius * 1.12;
                g2.setColor(alpha(atmosphere, (int)Math.round(255 * visual.atmosphereOpacity() * .45)));
                g2.fill(new Ellipse2D.Double(x - ar, y - ar, ar * 2, ar * 2));
            }

            g2.setColor(color);
            g2.fill(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
            Graphics2D surface = clippedSurface(g2);

            switch (visual.visualClass()) {
                case OCEAN -> drawContinents(surface, secondary);
                case DESERT -> drawDesert(surface, secondary);
                case ICE -> drawIce(surface, secondary);
                case VOLCANIC -> drawVolcanic(surface, secondary);
                case GAS_GIANT -> drawBands(surface, secondary, Math.max(5, visual.bands()));
                case MOON, ROCKY -> drawRocky(surface, secondary);
                default -> { }
            }

            if (visual.bands() > 0 && visual.visualClass() != CelestialVisualClass.GAS_GIANT) {
                drawBands(surface, secondary, visual.bands());
            }
            if (visual.craters() > 0) drawCraters(surface, secondary, visual.craters());
            if (visual.cloudLayers() > 0) drawClouds(surface, atmosphere, visual.cloudLayers());
            if (visual.iceCaps()) drawIceCaps(surface);
            if (visual.lava()) drawLava(surface);
            if (visual.nightLights() > 0) drawNightLights(surface, visual.nightLights());
            surface.dispose();

            g2.setColor(new Color(0, 0, 0, 54));
            g2.fill(new Ellipse2D.Double(x + radius * .12, y - radius, radius * .88, radius * 2));
            if (visual.atmosphereOpacity() > 0) {
                g2.setColor(alpha(atmosphere, (int)Math.round(255 * visual.atmosphereOpacity())));
                g2.setStroke(new BasicStroke((float)Math.max(1.0, radius * .035)));
                g2.draw(new Ellipse2D.Double(x - radius * 1.03, y - radius * 1.03, radius * 2.06, radius * 2.06));
            }
        }

        private Graphics2D clippedSurface(Graphics2D g2) {
            Graphics2D surface = (Graphics2D)g2.create();
            surface.clip(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
            return surface;
        }

        private void drawContinents(Graphics2D g, Color secondary) {
            for (int i = 0; i < 7; i++) {
                double px = x + signedUnit(i, 101) * radius * .64;
                double py = y + signedUnit(i, 103) * radius * .60;
                double rw = radius * (.18 + unit(i, 107) * .34);
                double rh = radius * (.10 + unit(i, 109) * .23);
                g.setColor(alpha(secondary, 190));
                g.fill(new Ellipse2D.Double(px - rw, py - rh, rw * 2, rh * 2));
            }
        }

        private void drawDesert(Graphics2D g, Color secondary) {
            for (int i = 0; i < 5; i++) {
                double py = y - radius * .62 + i * radius * .31;
                g.setColor(alpha(secondary, 50 + i * 8));
                g.setStroke(new BasicStroke((float)Math.max(1.1, radius * .055)));
                g.drawArc((int)(x - radius * .85), (int)(py - radius * .11), (int)(radius * 1.7),
                        (int)(radius * .22), (i & 1) == 0 ? 8 : 188, 150);
            }
        }

        private void drawIce(Graphics2D g, Color secondary) {
            g.setColor(alpha(secondary, 125));
            for (int i = 0; i < 9; i++) {
                double px = x + signedUnit(i, 127) * radius * .72;
                double py = y + signedUnit(i, 131) * radius * .72;
                double rr = radius * (.07 + unit(i, 137) * .15);
                g.fill(new Ellipse2D.Double(px - rr, py - rr * .7, rr * 2, rr * 1.4));
            }
        }

        private void drawVolcanic(Graphics2D g, Color secondary) {
            drawRocky(g, secondary);
            drawLava(g);
        }

        private void drawRocky(Graphics2D g, Color secondary) {
            for (int i = 0; i < 8; i++) {
                double px = x + signedUnit(i, 149) * radius * .72;
                double py = y + signedUnit(i, 151) * radius * .72;
                double rr = radius * (.08 + unit(i, 157) * .16);
                g.setColor(alpha(secondary, 70 + (int)(unit(i, 163) * 60)));
                g.fill(new Ellipse2D.Double(px - rr, py - rr, rr * 2, rr * 2));
            }
        }

        private void drawBands(Graphics2D g, Color secondary, int count) {
            for (int i = 0; i < count; i++) {
                double f = (i + 1.0) / (count + 1.0);
                double py = y - radius + f * radius * 2;
                double h = Math.max(2, radius * (.08 + .035 * (i % 3)));
                g.setColor(alpha(i % 2 == 0 ? secondary : color.brighter(), 62 + (i % 3) * 18));
                g.fillRect((int)(x - radius), (int)(py - h * .5), (int)(radius * 2), (int)h);
            }
        }

        private void drawCraters(Graphics2D g, Color secondary, int count) {
            Color dark = secondary.darker();
            for (int i = 0; i < count; i++) {
                double px = x + signedUnit(i, 173) * radius * .73;
                double py = y + signedUnit(i, 179) * radius * .73;
                double rr = radius * (.025 + unit(i, 181) * .09);
                g.setColor(alpha(dark, 105));
                g.fill(new Ellipse2D.Double(px - rr, py - rr, rr * 2, rr * 2));
                g.setColor(alpha(secondary.brighter(), 65));
                g.draw(new Ellipse2D.Double(px - rr * .82, py - rr * .82, rr * 1.64, rr * 1.64));
            }
        }

        private void drawClouds(Graphics2D g, Color atmosphere, int layers) {
            Color cloud = blend(Color.WHITE, atmosphere, .38);
            for (int layer = 0; layer < layers; layer++) {
                double yOffset = -radius * .58 + layer * (radius * 1.16 / Math.max(1, layers - 1));
                int alpha = Math.max(24, 80 - layer * 6);
                g.setColor(alpha(cloud, alpha));
                g.setStroke(new BasicStroke((float)Math.max(1.0, radius * .045)));
                for (int segment = 0; segment < 3; segment++) {
                    double shift = signedUnit(layer * 4 + segment, 191) * radius * .34;
                    g.drawArc((int)(x - radius * .72 + shift), (int)(y + yOffset - radius * .12),
                            (int)(radius * 1.05), (int)(radius * .24), 8, 164);
                }
            }
        }

        private void drawIceCaps(Graphics2D g) {
            g.setColor(new Color(232, 246, 252, 185));
            g.fillArc((int)(x - radius * .72), (int)(y - radius * 1.02), (int)(radius * 1.44),
                    (int)(radius * .68), 0, 180);
            g.fillArc((int)(x - radius * .72), (int)(y + radius * .34), (int)(radius * 1.44),
                    (int)(radius * .68), 180, 180);
        }

        private void drawLava(Graphics2D g) {
            Color lava = new Color(255, 102, 35, 215);
            g.setColor(lava);
            g.setStroke(new BasicStroke((float)Math.max(1.2, radius * .038), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < 5; i++) {
                Path2D crack = new Path2D.Double();
                double sx = x + signedUnit(i, 211) * radius * .58;
                double sy = y + signedUnit(i, 223) * radius * .58;
                crack.moveTo(sx, sy);
                for (int step = 1; step <= 4; step++) {
                    crack.lineTo(sx + signedUnit(i * 7 + step, 227) * radius * .16 * step,
                            sy + signedUnit(i * 7 + step, 229) * radius * .13 * step);
                }
                g.draw(crack);
            }
        }

        private void drawNightLights(Graphics2D g, int count) {
            Color light = new Color(255, 214, 116);
            for (int i = 0; i < count; i++) {
                double px = x + radius * (.05 + unit(i, 233) * .68);
                double py = y + signedUnit(i, 239) * radius * .72;
                double rr = Math.max(1.0, radius * (.012 + unit(i, 241) * .015));
                g.setColor(alpha(light, 150 + (int)(unit(i, 251) * 90)));
                g.fill(new Ellipse2D.Double(px - rr, py - rr, rr * 2, rr * 2));
            }
        }

        private void drawRings(Graphics2D g, Color secondary) {
            g.setColor(alpha(secondary, 105));
            g.setStroke(new BasicStroke((float)Math.max(1.1, radius * .035)));
            g.draw(new Ellipse2D.Double(x - radius * 1.65, y - radius * .48, radius * 3.30, radius * .96));
            g.setColor(alpha(secondary.brighter(), 58));
            g.draw(new Ellipse2D.Double(x - radius * 1.42, y - radius * .38, radius * 2.84, radius * .76));
        }

        private void drawLabel(Graphics2D g2) {
            g2.setColor(new Color(255, 255, 255, 150));
            g2.drawString(name, (int)(x + radius + 8), (int)(y - radius - 4));
        }

        private double unit(int index, int salt) {
            long z = visualSeed + 0x9E3779B97F4A7C15L * (index + 1L) + 0xBF58476D1CE4E5B9L * salt;
            z ^= z >>> 30;
            z *= 0xBF58476D1CE4E5B9L;
            z ^= z >>> 27;
            z *= 0x94D049BB133111EBL;
            z ^= z >>> 31;
            return (z >>> 11) * 0x1.0p-53;
        }

        private double signedUnit(int index, int salt) {
            return unit(index, salt) * 2.0 - 1.0;
        }
    }

    private static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static Color blend(Color a, Color b, double t) {
        double u = Math.max(0, Math.min(1, t));
        return new Color(
                (int)Math.round(a.getRed() * (1 - u) + b.getRed() * u),
                (int)Math.round(a.getGreen() * (1 - u) + b.getGreen() * u),
                (int)Math.round(a.getBlue() * (1 - u) + b.getBlue() * u));
    }
}
