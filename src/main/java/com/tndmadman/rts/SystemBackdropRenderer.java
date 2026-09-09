package com.tndmadman.rts;

import java.awt.*;
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
            stars.add(new StarPoint(random.nextDouble() * definition.width(), random.nextDouble() * definition.height(),
                    0.8 + random.nextDouble() * 2.2, 70 + random.nextInt(150)));
        }
        List<DustPoint> dust = new ArrayList<>(visual.dustCount());
        for (int i = 0; i < visual.dustCount(); i++) {
            dust.add(new DustPoint(random.nextDouble() * definition.width(), random.nextDouble() * definition.height(),
                    1.5 + random.nextDouble() * 5.5, 12 + random.nextInt(34)));
        }
        List<NebulaPatch> nebula = new ArrayList<>(visual.nebulaLayers());
        for (int i = 0; i < visual.nebulaLayers(); i++) {
            double w = definition.width() * (0.20 + random.nextDouble() * 0.34);
            double h = definition.height() * (0.13 + random.nextDouble() * 0.25);
            nebula.add(new NebulaPatch(random.nextDouble() * definition.width(), random.nextDouble() * definition.height(),
                    w, h, random.nextDouble() * Math.PI, 9 + random.nextInt(18)));
        }
        List<VfxPoint> vfx = new ArrayList<>(visual.vfxCount());
        for (int i = 0; i < visual.vfxCount(); i++) {
            vfx.add(new VfxPoint(random.nextDouble() * definition.width(), random.nextDouble() * definition.height(),
                    3 + random.nextDouble() * 10, random.nextDouble() * Math.PI * 2, 28 + random.nextInt(70)));
        }
        double bandAngle = -0.75 + random.nextDouble() * 1.5;
        double bandOffset = (-0.18 + random.nextDouble() * .36) * definition.height();
        return new Backdrop(List.copyOf(stars), List.copyOf(dust), List.copyOf(nebula), List.copyOf(vfx), bandAngle, bandOffset);
    }

    private record Backdrop(List<StarPoint> stars, List<DustPoint> dust, List<NebulaPatch> nebula,
                            List<VfxPoint> vfx, double bandAngle, double bandOffset) {
        void draw(Graphics2D source, SystemVisualDefinition visual, int width, int height) {
            Graphics2D g = (Graphics2D)source.create();
            Rectangle clipBounds = g.getClipBounds();
            Rectangle2D visible = clipBounds == null
                    ? new Rectangle2D.Double(0, 0, width, height)
                    : clipBounds.createIntersection(new Rectangle2D.Double(0, 0, width, height));
            if (visible.isEmpty()) { g.dispose(); return; }

            Color tint = new Color(visual.tintRgb());
            int tintAlpha = (int)Math.round(255 * visual.tintOpacity());
            if (tintAlpha > 0) { g.setColor(alpha(tint, tintAlpha)); g.fill(visible); }

            Color nebulaColor = new Color(visual.nebulaColorRgb());
            if (visual.galacticBand() > 0) drawGalacticBand(g, width, height, nebulaColor, visual.galacticBand(), bandAngle, bandOffset);

            for (NebulaPatch patch : nebula) {
                double bound = Math.hypot(patch.width, patch.height) * .5;
                if (!visible.intersects(patch.x - bound, patch.y - bound, bound * 2, bound * 2)) continue;
                Graphics2D n = (Graphics2D)g.create();
                n.translate(patch.x, patch.y); n.rotate(patch.angle);
                n.setColor(alpha(nebulaColor, patch.alpha));
                n.fill(new Ellipse2D.Double(-patch.width*.5, -patch.height*.5, patch.width, patch.height));
                n.setColor(alpha(nebulaColor.brighter(), Math.max(4, patch.alpha/2)));
                n.fill(new Ellipse2D.Double(-patch.width*.32, -patch.height*.28, patch.width*.64, patch.height*.56));
                n.dispose();
            }

            Color starColor = new Color(visual.starColorRgb());
            for (StarPoint star : stars) {
                double r = star.radius, bound = r * 2.4;
                if (!visible.intersects(star.x-bound, star.y-bound, bound*2, bound*2)) continue;
                if (star.alpha > 150) { g.setColor(alpha(starColor, Math.max(15, star.alpha/6))); g.fill(new Ellipse2D.Double(star.x-r*2.4, star.y-r*2.4, r*4.8, r*4.8)); }
                g.setColor(alpha(starColor, star.alpha)); g.fill(new Ellipse2D.Double(star.x-r*.5, star.y-r*.5, r, r));
            }

            Color dustColor = blend(nebulaColor, starColor, .35);
            for (DustPoint point : dust) {
                double r = point.radius;
                if (!visible.intersects(point.x-r, point.y-r, r*2, r*2)) continue;
                g.setColor(alpha(dustColor, point.alpha)); g.fill(new Ellipse2D.Double(point.x-r*.5, point.y-r*.5, r, r));
            }
            drawVfx(g, visual, vfx, visible, starColor, nebulaColor);
            g.dispose();
        }
    }

    private static void drawVfx(Graphics2D g, SystemVisualDefinition visual, List<VfxPoint> points,
                                Rectangle2D visible, Color starColor, Color nebulaColor) {
        if (visual.vfx() == SystemVfx.NONE) return;
        Color effect = switch (visual.vfx()) {
            case ION_STREAKS -> blend(starColor, new Color(100, 255, 235), .55);
            case EMBERS -> blend(nebulaColor, new Color(255, 128, 46), .70);
            case RADIATION -> blend(starColor, new Color(130, 220, 255), .60);
            case NONE -> starColor;
        };
        for (VfxPoint point : points) {
            double bound = point.scale * 3;
            if (!visible.intersects(point.x-bound, point.y-bound, bound*2, bound*2)) continue;
            Graphics2D e = (Graphics2D)g.create();
            e.translate(point.x, point.y); e.rotate(point.angle);
            switch (visual.vfx()) {
                case ION_STREAKS -> {
                    e.setColor(alpha(effect, point.alpha)); e.setStroke(new BasicStroke(1.1f));
                    e.drawLine((int)(-point.scale*2.5), 0, (int)(point.scale*2.5), 0);
                }
                case EMBERS -> {
                    e.setColor(alpha(effect, Math.max(10, point.alpha/3))); e.fill(new Ellipse2D.Double(-point.scale, -point.scale, point.scale*2, point.scale*2));
                    e.setColor(alpha(effect.brighter(), point.alpha)); e.fill(new Ellipse2D.Double(-1.4, -1.4, 2.8, 2.8));
                }
                case RADIATION -> {
                    e.setColor(alpha(effect, point.alpha)); e.setStroke(new BasicStroke(1f));
                    e.drawLine((int)(-point.scale*1.7), 0, (int)(point.scale*1.7), 0);
                    e.drawLine(0, (int)(-point.scale*.7), 0, (int)(point.scale*.7));
                }
                case NONE -> { }
            }
            e.dispose();
        }
    }

    private static void drawGalacticBand(Graphics2D source, int width, int height, Color color, double intensity,
                                         double angle, double offset) {
        Graphics2D band = (Graphics2D)source.create();
        band.translate(width*.5, height*.5+offset); band.rotate(angle);
        double diagonal = Math.hypot(width, height);
        double bandHeight = Math.max(280, height*(.10+intensity*.18));
        band.setColor(alpha(color, (int)Math.round(18+intensity*30)));
        band.fill(new Rectangle2D.Double(-diagonal, -bandHeight*.5, diagonal*2, bandHeight));
        band.setColor(alpha(color.brighter(), (int)Math.round(10+intensity*22)));
        band.fill(new Rectangle2D.Double(-diagonal, -bandHeight*.18, diagonal*2, bandHeight*.36));
        band.dispose();
    }

    private record StarPoint(double x, double y, double radius, int alpha) { }
    private record DustPoint(double x, double y, double radius, int alpha) { }
    private record NebulaPatch(double x, double y, double width, double height, double angle, int alpha) { }
    private record VfxPoint(double x, double y, double scale, double angle, int alpha) { }

    private static Color alpha(Color color, int alpha) { return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha))); }
    private static Color blend(Color a, Color b, double t) { double u=Math.max(0,Math.min(1,t)); return new Color((int)Math.round(a.getRed()*(1-u)+b.getRed()*u),(int)Math.round(a.getGreen()*(1-u)+b.getGreen()*u),(int)Math.round(a.getBlue()*(1-u)+b.getBlue()*u)); }
}
