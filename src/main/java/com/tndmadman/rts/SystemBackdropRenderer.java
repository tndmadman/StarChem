package com.tndmadman.rts;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/** Cached, deterministic deep-space presentation for authored system visual profiles. */
final class SystemBackdropRenderer {
    private static final ConcurrentHashMap<Key, Backdrop> CACHE = new ConcurrentHashMap<>();

    private SystemBackdropRenderer() { }

    static void draw(Graphics2D g2, StarSystemDefinition definition, SystemVisualProfile profile,
                     double originX, double originY) {
        if (g2 == null || definition == null || profile == null) return;
        Key key = new Key(definition.id(), definition.width(), definition.height(), profile.seed());
        Backdrop backdrop = CACHE.computeIfAbsent(key,
                ignored -> build(definition.width(), definition.height(), profile));
        backdrop.draw(g2, originX, originY, definition.width(), definition.height(), profile);
    }

    private static Backdrop build(int width, int height, SystemVisualProfile profile) {
        Random random = new Random(profile.seed());
        List<Star> stars = new ArrayList<>(Math.max(0, profile.starCount()));
        for (int i = 0; i < profile.starCount(); i++) {
            double depth = 0.25 + random.nextDouble() * 0.75;
            stars.add(new Star(random.nextDouble() * width, random.nextDouble() * height,
                    0.45 + random.nextDouble() * 1.7, depth, 0.40 + random.nextDouble() * 0.60));
        }

        List<Cloud> clouds = new ArrayList<>(Math.max(0, profile.nebulaClouds()));
        for (int i = 0; i < profile.nebulaClouds(); i++) {
            double x = random.nextDouble() * width;
            double y = random.nextDouble() * height;
            double radius = Math.min(width, height) * (0.08 + random.nextDouble() * 0.18);
            Color base = switch (i % 3) {
                case 0 -> profile.nebulaPrimary();
                case 1 -> profile.nebulaSecondary();
                default -> profile.nebulaHighlight();
            };
            int innerAlpha = clampAlpha(255 * profile.nebulaOpacity() * (0.18 + random.nextDouble() * 0.18));
            int middleAlpha = Math.max(0, innerAlpha / 2);
            Paint paint = new RadialGradientPaint(new Point2D.Double(x, y), (float)radius,
                    new float[]{0f, 0.48f, 1f},
                    new Color[]{withAlpha(base, innerAlpha), withAlpha(base, middleAlpha), withAlpha(base, 0)});
            clouds.add(new Cloud(x, y, radius, paint));
        }

        List<Dust> dust = new ArrayList<>(Math.max(0, profile.dustCount()));
        for (int i = 0; i < profile.dustCount(); i++) {
            dust.add(new Dust(random.nextDouble() * width, random.nextDouble() * height,
                    0.8 + random.nextDouble() * 2.4, 0.35 + random.nextDouble() * 0.65));
        }
        return new Backdrop(List.copyOf(stars), List.copyOf(clouds), List.copyOf(dust));
    }

    private static int clampAlpha(double value) {
        return (int)Math.max(0, Math.min(255, Math.round(value)));
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static double wrap(double value, double span) {
        if (span <= 0) return value;
        double result = value % span;
        return result < 0 ? result + span : result;
    }

    private record Key(String id, int width, int height, long seed) { }
    private record Star(double x, double y, double size, double depth, double brightness) { }
    private record Cloud(double x, double y, double radius, Paint paint) { }
    private record Dust(double x, double y, double size, double depth) { }

    private record Backdrop(List<Star> stars, List<Cloud> clouds, List<Dust> dust) {
        void draw(Graphics2D g2, double originX, double originY, int width, int height,
                  SystemVisualProfile profile) {
            Graphics2D s = (Graphics2D)g2.create();
            s.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Rectangle clip = s.getClipBounds();
            double viewCenterX = clip == null ? originX + width * 0.5 : clip.getCenterX();
            double viewCenterY = clip == null ? originY + height * 0.5 : clip.getCenterY();
            double cameraDx = viewCenterX - (originX + width * 0.5);
            double cameraDy = viewCenterY - (originY + height * 0.5);

            s.setColor(profile.background());
            s.fillRect((int)Math.floor(originX), (int)Math.floor(originY), width, height);

            Paint oldPaint = s.getPaint();
            for (Cloud cloud : clouds) {
                s.setPaint(cloud.paint());
                s.fill(new Ellipse2D.Double(originX + cloud.x() - cloud.radius(),
                        originY + cloud.y() - cloud.radius(), cloud.radius() * 2, cloud.radius() * 2));
            }
            s.setPaint(oldPaint);

            Composite oldComposite = s.getComposite();
            Stroke oldStroke = s.getStroke();
            double directionX = 0.92;
            double directionY = -0.39;
            for (Star star : stars) {
                double shift = profile.parallax() * star.depth();
                double x = originX + wrap(star.x() - cameraDx * shift, width);
                double y = originY + wrap(star.y() - cameraDy * shift, height);
                int alpha = clampAlpha(255 * star.brightness());
                s.setColor(withAlpha(profile.starColor(), alpha));
                if (star.depth() > 0.72 && star.size() > 1.2) {
                    double length = 2.0 + star.size() * 2.6;
                    s.setStroke(new BasicStroke((float)Math.max(0.6, star.size() * 0.55),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    s.drawLine((int)Math.round(x - directionX * length * 0.5),
                            (int)Math.round(y - directionY * length * 0.5),
                            (int)Math.round(x + directionX * length * 0.5),
                            (int)Math.round(y + directionY * length * 0.5));
                } else {
                    double size = Math.max(1.0, star.size());
                    s.fill(new Ellipse2D.Double(x - size * 0.5, y - size * 0.5, size, size));
                }
            }

            s.setStroke(oldStroke);
            for (Dust mote : dust) {
                double shift = profile.parallax() * 1.55 * mote.depth();
                double x = originX + wrap(mote.x() - cameraDx * shift, width);
                double y = originY + wrap(mote.y() - cameraDy * shift, height);
                int alpha = 18 + (int)Math.round(34 * mote.depth());
                Color dustColor = mote.depth() > 0.64 ? profile.nebulaHighlight() : profile.backgroundAccent();
                s.setColor(withAlpha(dustColor, alpha));
                double size = mote.size() * (0.7 + mote.depth() * 0.7);
                s.fill(new Ellipse2D.Double(x - size * 0.5, y - size * 0.5, size, size));
            }
            s.setComposite(oldComposite);
            s.dispose();
        }
    }
}
