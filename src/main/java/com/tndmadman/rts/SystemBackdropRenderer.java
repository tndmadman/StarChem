package com.tndmadman.rts;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/** Cached deterministic environmental detail layered over the tactical map background. */
final class SystemBackdropRenderer {
    private static final Map<String, Backdrop> CACHE = new ConcurrentHashMap<>();

    private SystemBackdropRenderer() { }

    static void draw(Graphics2D source, StarSystemDefinition definition) {
        if (source == null || definition == null) return;
        SystemVisualDefinition visual = VisualDefinitionCatalog.system(definition.id());
        if (visual == null) return;
        String key = definition.id() + ':' + definition.width() + 'x' + definition.height() + ':' + visual.hashCode();
        Backdrop backdrop = CACHE.computeIfAbsent(key, ignored -> build(definition, visual));
        backdrop.draw(source, visual, definition.width(), definition.height());
    }

    static void clearCache() { CACHE.clear(); }

    private static Backdrop build(StarSystemDefinition definition, SystemVisualDefinition visual) {
        long seed = visual.seed() ^ ((long)definition.id().hashCode() << 32) ^ definition.width() ^ ((long)definition.height() << 17);
        Random random = new Random(seed);
        List<StarPoint> stars = new ArrayList<>(visual.starCount());
        for (int i = 0; i < visual.starCount(); i++) {
            stars.add(new StarPoint(
                    random.nextDouble() * definition.width(),
                    random.nextDouble() * definition.height(),
                    0.8 + random.nextDouble() * 2.2,
                    70 + random.nextInt(150)));
        }
        List<DustPoint> dust = new ArrayList<>(visual.dustCount());
        for (int i = 0; i < visual.dustCount(); i++) {
            dust.add(new DustPoint(
                    random.nextDouble() * definition.width(),
                    random.nextDouble() * definition.height(),
                    1.5 + random.nextDouble() * 5.5,
                    12 + random.nextInt(34)));
        }
        List<NebulaPatch> nebula = new ArrayList<>(visual.nebulaLayers());
        for (int i = 0; i < visual.nebulaLayers(); i++) {
            double w = definition.width() * (0.20 + random.nextDouble() * 0.34);
            double h = definition.height() * (0.13 + random.nextDouble() * 0.25);
            nebula.add(new NebulaPatch(
                    random.nextDouble() * definition.width(),
                    random.nextDouble() * definition.height(),
                    w, h,
                    random.nextDouble() * Math.PI,
                    9 + random.nextInt(18)));
        }
        double bandAngle = -0.75 + random.nextDouble() * 1.5;
        double bandOffset = (-0.18 + random.nextDouble() * .36) * definition.height();
        return new Backdrop(List.copyOf(stars), List.copyOf(dust), List.copyOf(nebula), bandAngle, bandOffset);
    }

    private record Backdrop(List<StarPoint> stars, List<DustPoint> dust, List<NebulaPatch> nebula,
                            double bandAngle, double bandOffset) {
        void draw(Graphics2D source, SystemVisualDefinition visual, int width, int height) {
            Graphics2D g = (Graphics2D)source.create();
            Shape clip = g.getClip();
            if (clip == null) g.setClip(0, 0, width, height);

            Color tint = new Color(visual.tintRgb());
            int tintAlpha = (int)Math.round(255 * visual.tintOpacity());
            if (tintAlpha > 0) {
                g.setColor(alpha(tint, tintAlpha));
                g.fillRect(0, 0, width, height);
            }

            Color nebulaColor = new Color(visual.nebulaColorRgb());
            if (visual.galacticBand() > 0) drawGalacticBand(g, width, height, nebulaColor, visual.galacticBand(), bandAngle, bandOffset);

            for (NebulaPatch patch : nebula) {
                Graphics2D n = (Graphics2D)g.create();
                n.translate(patch.x, patch.y);
                n.rotate(patch.angle);
                n.setColor(alpha(nebulaColor, patch.alpha));
                n.fill(new Ellipse2D.Double(-patch.width * .5, -patch.height * .5, patch.width, patch.height));
                n.setColor(alpha(nebulaColor.brighter(), Math.max(4, patch.alpha / 2)));
                n.fill(new Ellipse2D.Double(-patch.width * .32, -patch.height * .28, patch.width * .64, patch.height * .56));
                n.dispose();
            }

            Color starColor = new Color(visual.starColorRgb());
            for (StarPoint star : stars) {
                double r = star.radius;
                if (star.alpha > 150) {
                    g.setColor(alpha(starColor, Math.max(15, star.alpha / 6)));
                    g.fill(new Ellipse2D.Double(star.x - r * 2.4, star.y - r * 2.4, r * 4.8, r * 4.8));
                }
                g.setColor(alpha(starColor, star.alpha));
                g.fill(new Ellipse2D.Double(star.x - r * .5, star.y - r * .5, r, r));
            }

            Color dustColor = blend(nebulaColor, starColor, .35);
            for (DustPoint point : dust) {
                g.setColor(alpha(dustColor, point.alpha));
                g.fill(new Ellipse2D.Double(point.x - point.radius * .5, point.y - point.radius * .5,
                        point.radius, point.radius));
            }
            g.dispose();
        }
    }

    private static void drawGalacticBand(Graphics2D source, int width, int height, Color color, double intensity,
                                         double angle, double offset) {
        Graphics2D band = (Graphics2D)source.create();
        band.translate(width * .5, height * .5 + offset);
        band.rotate(angle);
        double diagonal = Math.hypot(width, height);
        double bandHeight = Math.max(280, height * (.10 + intensity * .18));
        int outerAlpha = (int)Math.round(18 + intensity * 30);
        int innerAlpha = (int)Math.round(10 + intensity * 22);
        band.setColor(alpha(color, outerAlpha));
        band.fill(new Rectangle2D.Double(-diagonal, -bandHeight * .5, diagonal * 2, bandHeight));
        band.setColor(alpha(color.brighter(), innerAlpha));
        band.fill(new Rectangle2D.Double(-diagonal, -bandHeight * .18, diagonal * 2, bandHeight * .36));
        band.dispose();
    }

    private record StarPoint(double x, double y, double radius, int alpha) { }
    private record DustPoint(double x, double y, double radius, int alpha) { }
    private record NebulaPatch(double x, double y, double width, double height, double angle, int alpha) { }

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
