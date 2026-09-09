package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Deterministic, system-themed world backdrop.
 *
 * The generated scene is cached for the lifetime of the owning CelestialSystem;
 * draw() only replays simple primitives with cheap camera-relative parallax.
 * No simulation RNG or time-dependent state is consumed here.
 */
final class SpaceBackgroundRenderer {
    private static final double OVERSCAN = 3200.0;
    private static final double TAU = Math.PI * 2.0;
    private static final int TACTICAL_GRID_SPACING = 160;

    private final int width;
    private final int height;
    private final Theme theme;
    private final Profile profile;
    private final long seed;
    private final List<Star> distantStars;
    private final List<Star> mediumStars;
    private final List<Star> brightStars;
    private final List<DustPatch> dustPatches;
    private final List<Cloud> clouds;
    private final List<Debris> debris;

    SpaceBackgroundRenderer(StarSystemDefinition definition) {
        StarSystemDefinition resolved = definition == null ? StarSystems.defaultSystem() : definition;
        width = Math.max(1, resolved.width());
        height = Math.max(1, resolved.height());
        theme = Theme.from(resolved);
        profile = Profile.forTheme(theme);
        seed = stableSeed(resolved);

        Random random = new Random(seed);
        distantStars = generateStars(random, profile.distantStars, 1.2, 2.7, 0.025, 72, 142);
        mediumStars = generateStars(random, profile.mediumStars, 2.2, 4.8, 0.065, 125, 205);
        brightStars = generateStars(random, profile.brightStars, 4.0, 8.5, 0.11, 180, 245);
        dustPatches = generateDust(random, profile.dustPatches);
        clouds = generateClouds(random, profile.clouds);
        debris = generateDebris(random, profile.debris);
    }

    void draw(Graphics2D g2) {
        if (g2 == null) return;
        Graphics2D c = (Graphics2D) g2.create();
        c.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Rectangle clip = c.getClipBounds();
        Rectangle2D view = clip == null
                ? new Rectangle2D.Double(0, 0, width, height)
                : new Rectangle2D.Double(clip.x, clip.y, Math.max(1, clip.width), Math.max(1, clip.height));

        drawBase(c);
        drawDust(c, view);
        drawStars(c, view, distantStars, false);
        drawClouds(c, view);
        drawStars(c, view, mediumStars, false);
        drawStars(c, view, brightStars, true);
        drawDebris(c, view);
        if (Boolean.getBoolean("starchem.tacticalGrid")) drawTacticalGrid(c, view);
        c.dispose();
    }

    String themeNameForTest() { return theme.name(); }
    long seedForTest() { return seed; }

    private void drawBase(Graphics2D g2) {
        g2.setPaint(new GradientPaint(0f, 0f, profile.baseTop,
                (float) width, (float) height, profile.baseBottom));
        g2.fillRect(0, 0, width, height);

        // A second, restrained wash prevents the base from reading as a flat UI panel.
        g2.setColor(profile.ambientWash);
        double washW = width * 0.92;
        double washH = height * 0.62;
        g2.fill(new Ellipse2D.Double(width * 0.06, height * 0.14, washW, washH));
    }

    private void drawStars(Graphics2D g2, Rectangle2D view, List<Star> stars, boolean glow) {
        double vx = view.getX();
        double vy = view.getY();
        for (Star star : stars) {
            double x = star.x + vx * star.parallax;
            double y = star.y + vy * star.parallax;
            double margin = glow ? star.radius * 3.5 : star.radius * 1.5;
            if (!visible(view, x, y, margin)) continue;

            if (glow) {
                Color glowColor = alpha(star.color, Math.max(18, star.color.getAlpha() / 5));
                g2.setColor(glowColor);
                double gr = star.radius * 2.8;
                g2.fill(new Ellipse2D.Double(x - gr, y - gr, gr * 2, gr * 2));
            }
            g2.setColor(star.color);
            g2.fill(new Ellipse2D.Double(x - star.radius, y - star.radius,
                    star.radius * 2, star.radius * 2));

            if (glow && star.radius >= 6.2) {
                g2.setColor(alpha(star.color, Math.min(220, star.color.getAlpha())));
                g2.setStroke(new BasicStroke(1.0f));
                double ray = star.radius * 2.1;
                g2.draw(new Line2D.Double(x - ray, y, x + ray, y));
                g2.draw(new Line2D.Double(x, y - ray, x, y + ray));
            }
        }
    }

    private void drawDust(Graphics2D g2, Rectangle2D view) {
        for (DustPatch patch : dustPatches) {
            double x = patch.x + view.getX() * patch.parallax;
            double y = patch.y + view.getY() * patch.parallax;
            double margin = Math.max(patch.width, patch.height) * 0.55;
            if (!visible(view, x, y, margin)) continue;

            g2.setColor(patch.outer);
            g2.fill(new Ellipse2D.Double(x - patch.width * 0.5, y - patch.height * 0.5,
                    patch.width, patch.height));
            g2.setColor(patch.inner);
            g2.fill(new Ellipse2D.Double(x - patch.width * 0.31, y - patch.height * 0.34,
                    patch.width * 0.62, patch.height * 0.68));
        }
    }

    private void drawClouds(Graphics2D g2, Rectangle2D view) {
        for (Cloud cloud : clouds) {
            double baseX = cloud.x + view.getX() * cloud.parallax;
            double baseY = cloud.y + view.getY() * cloud.parallax;
            if (!visible(view, baseX, baseY, cloud.radius)) continue;

            for (CloudLobe lobe : cloud.lobes) {
                double x = baseX + lobe.dx;
                double y = baseY + lobe.dy;
                g2.setColor(lobe.color);
                g2.fill(new Ellipse2D.Double(x - lobe.width * 0.5, y - lobe.height * 0.5,
                        lobe.width, lobe.height));
            }
        }
    }

    private void drawDebris(Graphics2D g2, Rectangle2D view) {
        if (debris.isEmpty()) return;
        g2.setStroke(new BasicStroke(4.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Debris piece : debris) {
            double x = piece.x + view.getX() * piece.parallax;
            double y = piece.y + view.getY() * piece.parallax;
            if (!visible(view, x, y, piece.length)) continue;

            double dx = Math.cos(piece.angle) * piece.length * 0.5;
            double dy = Math.sin(piece.angle) * piece.length * 0.5;
            g2.setColor(piece.color);
            g2.draw(new Line2D.Double(x - dx, y - dy, x + dx, y + dy));

            double branchAngle = piece.angle + 0.72;
            double bx = Math.cos(branchAngle) * piece.length * 0.24;
            double by = Math.sin(branchAngle) * piece.length * 0.24;
            g2.draw(new Line2D.Double(x - bx, y - by, x + bx, y + by));
        }
    }

    private void drawTacticalGrid(Graphics2D g2, Rectangle2D view) {
        int minX = Math.max(0, (int) Math.floor(view.getMinX() / TACTICAL_GRID_SPACING) * TACTICAL_GRID_SPACING);
        int maxX = Math.min(width, (int) Math.ceil(view.getMaxX() / TACTICAL_GRID_SPACING) * TACTICAL_GRID_SPACING);
        int minY = Math.max(0, (int) Math.floor(view.getMinY() / TACTICAL_GRID_SPACING) * TACTICAL_GRID_SPACING);
        int maxY = Math.min(height, (int) Math.ceil(view.getMaxY() / TACTICAL_GRID_SPACING) * TACTICAL_GRID_SPACING);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(new Color(72, 116, 148, 58));
        for (int x = minX; x <= maxX; x += TACTICAL_GRID_SPACING) g2.drawLine(x, minY, x, maxY);
        for (int y = minY; y <= maxY; y += TACTICAL_GRID_SPACING) g2.drawLine(minX, y, maxX, y);
    }

    private List<Star> generateStars(Random random, int count, double minRadius, double maxRadius,
                                     double parallax, int minAlpha, int maxAlpha) {
        List<Star> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double x = ranged(random, -OVERSCAN, width + OVERSCAN);
            double y = ranged(random, -OVERSCAN, height + OVERSCAN);
            double radius = ranged(random, minRadius, maxRadius);
            double tint = random.nextDouble();
            int alpha = (int) Math.round(ranged(random, minAlpha, maxAlpha));
            Color color = mix(profile.starCool, profile.starWarm, tint, alpha);
            double layerJitter = ranged(random, -0.008, 0.008);
            out.add(new Star(x, y, radius, Math.max(0, parallax + layerJitter), color));
        }
        return List.copyOf(out);
    }

    private List<DustPatch> generateDust(Random random, int count) {
        List<DustPatch> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double x = ranged(random, -OVERSCAN * 0.55, width + OVERSCAN * 0.55);
            double y = ranged(random, -OVERSCAN * 0.55, height + OVERSCAN * 0.55);
            double w = ranged(random, 1100, 3600);
            double h = w * ranged(random, 0.34, 0.86);
            int outerAlpha = (int) Math.round(ranged(random, profile.dustAlphaMin, profile.dustAlphaMax));
            int innerAlpha = Math.max(2, outerAlpha + 4);
            Color tint = random.nextBoolean() ? profile.dustA : profile.dustB;
            out.add(new DustPatch(x, y, w, h, ranged(random, 0.035, 0.075),
                    alpha(tint, outerAlpha), alpha(tint, innerAlpha)));
        }
        return List.copyOf(out);
    }

    private List<Cloud> generateClouds(Random random, int count) {
        List<Cloud> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double x = ranged(random, -OVERSCAN * 0.35, width + OVERSCAN * 0.35);
            double y = ranged(random, -OVERSCAN * 0.35, height + OVERSCAN * 0.35);
            double radius = ranged(random, 1200, 3200);
            int lobeCount = 5 + random.nextInt(4);
            List<CloudLobe> lobes = new ArrayList<>(lobeCount);
            Color base = random.nextBoolean() ? profile.cloudA : profile.cloudB;
            for (int j = 0; j < lobeCount; j++) {
                double angle = random.nextDouble() * TAU;
                double distance = ranged(random, 0, radius * 0.34);
                double lobeW = radius * ranged(random, 0.68, 1.32);
                double lobeH = lobeW * ranged(random, 0.42, 0.88);
                int alpha = (int) Math.round(ranged(random, profile.cloudAlphaMin, profile.cloudAlphaMax));
                lobes.add(new CloudLobe(Math.cos(angle) * distance, Math.sin(angle) * distance,
                        lobeW, lobeH, alpha(base, alpha)));
            }
            out.add(new Cloud(x, y, radius * 1.25, ranged(random, 0.08, 0.135), List.copyOf(lobes)));
        }
        return List.copyOf(out);
    }

    private List<Debris> generateDebris(Random random, int count) {
        if (count <= 0) return List.of();
        List<Debris> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double x = ranged(random, -OVERSCAN * 0.2, width + OVERSCAN * 0.2);
            double y = ranged(random, -OVERSCAN * 0.2, height + OVERSCAN * 0.2);
            double length = ranged(random, 36, 150);
            int alpha = (int) Math.round(ranged(random, 26, 58));
            out.add(new Debris(x, y, length, random.nextDouble() * TAU,
                    ranged(random, 0.11, 0.16), alpha(profile.debrisColor, alpha)));
        }
        return List.copyOf(out);
    }

    private boolean visible(Rectangle2D view, double x, double y, double margin) {
        return x + margin >= view.getMinX() && x - margin <= view.getMaxX()
                && y + margin >= view.getMinY() && y - margin <= view.getMaxY();
    }

    private static double ranged(Random random, double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    private static Color alpha(Color color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), a);
    }

    private static Color mix(Color a, Color b, double t, int alpha) {
        double clamped = Math.max(0, Math.min(1, t));
        int r = (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * clamped);
        int g = (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * clamped);
        int bl = (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * clamped);
        return new Color(r, g, bl, Math.max(0, Math.min(255, alpha)));
    }

    private static long stableSeed(StarSystemDefinition definition) {
        long hash = 0xcbf29ce484222325L;
        hash = fnv(hash, definition.id());
        hash = fnv(hash, definition.role());
        hash ^= definition.width();
        hash *= 0x100000001b3L;
        hash ^= definition.height();
        hash *= 0x100000001b3L;
        for (String tag : definition.tags().stream().map(value -> value.toLowerCase(Locale.ROOT)).sorted().toList()) {
            hash = fnv(hash, tag);
        }
        return hash;
    }

    private static long fnv(long hash, String value) {
        String text = value == null ? "" : value;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private enum Theme {
        STANDARD,
        FRONTIER,
        NEBULA,
        ICE,
        INDUSTRIAL,
        WARZONE;

        static Theme from(StarSystemDefinition definition) {
            String role = definition.role() == null ? "" : definition.role().toLowerCase(Locale.ROOT);
            String id = definition.id() == null ? "" : definition.id().toLowerCase(Locale.ROOT);
            if (definition.hasTag("gas_rich") || definition.hasTag("sensor_interference")
                    || role.contains("gas") || id.contains("nebula")) return NEBULA;
            if (definition.hasTag("ice_rich") || role.contains("ice") || id.contains("ice")) return ICE;
            if (definition.hasTag("hazardous") || role.contains("danger") || id.contains("warzone")) return WARZONE;
            if (definition.hasTag("industrial") || definition.hasTag("metal_rich")
                    || role.contains("industrial") || id.contains("forge")) return INDUSTRIAL;
            if (definition.hasTag("frontier") || role.contains("frontier") || id.contains("frontier")) return FRONTIER;
            return STANDARD;
        }
    }

    private record Profile(
            Color baseTop,
            Color baseBottom,
            Color ambientWash,
            Color starCool,
            Color starWarm,
            Color dustA,
            Color dustB,
            Color cloudA,
            Color cloudB,
            Color debrisColor,
            int distantStars,
            int mediumStars,
            int brightStars,
            int dustPatches,
            int clouds,
            int debris,
            int dustAlphaMin,
            int dustAlphaMax,
            int cloudAlphaMin,
            int cloudAlphaMax
    ) {
        static Profile forTheme(Theme theme) {
            return switch (theme) {
                case NEBULA -> new Profile(
                        rgb(7, 6, 18), rgb(12, 9, 29), rgba(57, 35, 88, 14),
                        rgb(170, 194, 255), rgb(244, 188, 255),
                        rgb(83, 53, 122), rgb(38, 98, 132),
                        rgb(102, 55, 154), rgb(42, 125, 154), rgb(72, 68, 86),
                        1200, 330, 58, 28, 14, 0, 5, 13, 7, 18);
                case ICE -> new Profile(
                        rgb(3, 9, 17), rgb(6, 18, 31), rgba(57, 110, 145, 12),
                        rgb(190, 230, 255), rgb(228, 248, 255),
                        rgb(74, 121, 151), rgb(115, 159, 181),
                        rgb(72, 122, 151), rgb(123, 167, 190), rgb(83, 108, 122),
                        1350, 360, 74, 24, 4, 4, 4, 10, 4, 9);
                case INDUSTRIAL -> new Profile(
                        rgb(8, 9, 12), rgb(19, 14, 12), rgba(111, 71, 38, 12),
                        rgb(176, 199, 218), rgb(255, 202, 143),
                        rgb(103, 72, 43), rgb(84, 71, 61),
                        rgb(92, 59, 36), rgb(67, 72, 76), rgb(119, 104, 88),
                        1120, 350, 60, 30, 6, 28, 5, 12, 4, 10);
                case WARZONE -> new Profile(
                        rgb(9, 7, 9), rgb(20, 10, 10), rgba(122, 43, 29, 13),
                        rgb(184, 191, 205), rgb(255, 174, 117),
                        rgb(108, 45, 35), rgb(75, 57, 54),
                        rgb(91, 32, 28), rgb(74, 55, 49), rgb(118, 91, 82),
                        1040, 315, 56, 30, 8, 38, 5, 13, 5, 12);
                case FRONTIER -> new Profile(
                        rgb(4, 9, 14), rgb(7, 17, 22), rgba(36, 91, 92, 10),
                        rgb(167, 205, 216), rgb(225, 215, 184),
                        rgb(48, 84, 82), rgb(68, 74, 70),
                        rgb(42, 78, 79), rgb(65, 72, 71), rgb(91, 93, 88),
                        980, 270, 42, 22, 3, 8, 4, 10, 3, 8);
                case STANDARD -> new Profile(
                        rgb(4, 8, 15), rgb(7, 15, 25), rgba(35, 70, 104, 10),
                        rgb(173, 207, 242), rgb(244, 224, 183),
                        rgb(44, 66, 87), rgb(45, 75, 91),
                        rgb(46, 69, 91), rgb(42, 65, 82), rgb(82, 91, 101),
                        1120, 300, 50, 20, 2, 0, 4, 9, 3, 7);
            };
        }

        private static Color rgb(int r, int g, int b) { return new Color(r, g, b); }
        private static Color rgba(int r, int g, int b, int a) { return new Color(r, g, b, a); }
    }

    private record Star(double x, double y, double radius, double parallax, Color color) { }
    private record DustPatch(double x, double y, double width, double height, double parallax,
                             Color outer, Color inner) { }
    private record Cloud(double x, double y, double radius, double parallax, List<CloudLobe> lobes) { }
    private record CloudLobe(double dx, double dy, double width, double height, Color color) { }
    private record Debris(double x, double y, double length, double angle, double parallax, Color color) { }
}
