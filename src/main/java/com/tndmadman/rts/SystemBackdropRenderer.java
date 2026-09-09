package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/** Cached, deterministic deep-space presentation for authored system visual profiles. */
final class SystemBackdropRenderer {
    private static final int NEBULA_TEXTURE_MAX_DIMENSION = 1024;
    private static final ConcurrentHashMap<Key, Backdrop> CACHE = new ConcurrentHashMap<>();

    private SystemBackdropRenderer() { }

    static void draw(Graphics2D g2, StarSystemDefinition definition, SystemVisualProfile profile,
                     double originX, double originY) {
        if (g2 == null || definition == null || profile == null) return;
        Key key = new Key(definition.id(), definition.width(), definition.height(), profile.seed(), originX, originY);
        Backdrop backdrop = CACHE.computeIfAbsent(key,
                ignored -> build(definition.width(), definition.height(), profile));
        backdrop.draw(g2, originX, originY, definition.width(), definition.height(), profile);
    }

    private static Backdrop build(int width, int height, SystemVisualProfile profile) {
        Random random = new Random(profile.seed());
        List<Star> stars = new ArrayList<>(Math.max(0, profile.starCount()));
        for (int i = 0; i < profile.starCount(); i++) {
            double depth = 0.25 + random.nextDouble() * 0.75;
            double size = 0.45 + random.nextDouble() * 1.7;
            double brightness = 0.40 + random.nextDouble() * 0.60;
            boolean streak = depth > 0.72 && size > 1.2;
            double length = streak ? 2.0 + size * 2.6 : 0;
            Stroke stroke = streak
                    ? new BasicStroke((float)Math.max(0.6, size * 0.55), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    : null;
            Color color = withAlpha(profile.starColor(), clampAlpha(255 * brightness));
            stars.add(new Star(random.nextDouble() * width, random.nextDouble() * height,
                    size, depth, color, stroke, length));
        }

        BufferedImage nebulaTexture = buildNebulaTexture(width, height, profile, random);

        List<Dust> dust = new ArrayList<>(Math.max(0, profile.dustCount()));
        for (int i = 0; i < profile.dustCount(); i++) {
            double depth = 0.35 + random.nextDouble() * 0.65;
            Color base = depth > 0.64 ? profile.nebulaHighlight() : profile.backgroundAccent();
            Color color = withAlpha(base, 18 + (int)Math.round(34 * depth));
            dust.add(new Dust(random.nextDouble() * width, random.nextDouble() * height,
                    0.8 + random.nextDouble() * 2.4, depth, color));
        }
        return new Backdrop(List.copyOf(stars), nebulaTexture, List.copyOf(dust));
    }

    /**
     * Nebula clouds are static presentation data. Rasterize their expensive radial gradients once
     * into a bounded diffuse texture instead of repainting multi-thousand-unit gradients every frame.
     */
    private static BufferedImage buildNebulaTexture(int width, int height, SystemVisualProfile profile, Random random) {
        if (profile.nebulaClouds() <= 0 || width <= 0 || height <= 0) return null;
        double scale = Math.min(1.0, NEBULA_TEXTURE_MAX_DIMENSION / (double)Math.max(width, height));
        int textureWidth = Math.max(1, (int)Math.round(width * scale));
        int textureHeight = Math.max(1, (int)Math.round(height * scale));
        double textureScaleX = textureWidth / (double)width;
        double textureScaleY = textureHeight / (double)height;
        double radiusScale = Math.min(textureScaleX, textureScaleY);

        BufferedImage texture = new BufferedImage(textureWidth, textureHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D t = texture.createGraphics();
        t.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        t.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        for (int i = 0; i < profile.nebulaClouds(); i++) {
            double worldX = random.nextDouble() * width;
            double worldY = random.nextDouble() * height;
            double worldRadius = Math.min(width, height) * (0.08 + random.nextDouble() * 0.18);
            Color base = switch (i % 3) {
                case 0 -> profile.nebulaPrimary();
                case 1 -> profile.nebulaSecondary();
                default -> profile.nebulaHighlight();
            };
            int innerAlpha = clampAlpha(255 * profile.nebulaOpacity() * (0.18 + random.nextDouble() * 0.18));
            int middleAlpha = Math.max(0, innerAlpha / 2);
            double x = worldX * textureScaleX;
            double y = worldY * textureScaleY;
            double radius = Math.max(1.0, worldRadius * radiusScale);
            t.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), (float)radius,
                    new float[]{0f, 0.48f, 1f},
                    new Color[]{withAlpha(base, innerAlpha), withAlpha(base, middleAlpha), withAlpha(base, 0)}));
            t.fill(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
        }
        t.dispose();
        return texture;
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

    private record Key(String id, int width, int height, long seed, double originX, double originY) { }
    private record Star(double x, double y, double size, double depth, Color color, Stroke stroke, double length) { }
    private record Dust(double x, double y, double size, double depth, Color color) { }

    private record Backdrop(List<Star> stars, BufferedImage nebulaTexture, List<Dust> dust) {
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
            if (nebulaTexture != null) {
                Object oldInterpolation = s.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
                s.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                s.drawImage(nebulaTexture,
                        (int)Math.floor(originX), (int)Math.floor(originY), width, height, null);
                if (oldInterpolation != null) s.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
                else s.getRenderingHints().remove(RenderingHints.KEY_INTERPOLATION);
            }

            Stroke oldStroke = s.getStroke();
            double directionX = 0.92;
            double directionY = -0.39;
            for (Star star : stars) {
                double shift = profile.parallax() * star.depth();
                double x = originX + wrap(star.x() - cameraDx * shift, width);
                double y = originY + wrap(star.y() - cameraDy * shift, height);
                s.setColor(star.color());
                if (star.stroke() != null) {
                    s.setStroke(star.stroke());
                    s.drawLine((int)Math.round(x - directionX * star.length() * 0.5),
                            (int)Math.round(y - directionY * star.length() * 0.5),
                            (int)Math.round(x + directionX * star.length() * 0.5),
                            (int)Math.round(y + directionY * star.length() * 0.5));
                } else {
                    double size = Math.max(1.0, star.size());
                    s.fillOval((int)Math.round(x - size * 0.5), (int)Math.round(y - size * 0.5),
                            Math.max(1, (int)Math.ceil(size)), Math.max(1, (int)Math.ceil(size)));
                }
            }

            s.setStroke(oldStroke);
            for (Dust mote : dust) {
                double shift = profile.parallax() * 1.55 * mote.depth();
                double x = originX + wrap(mote.x() - cameraDx * shift, width);
                double y = originY + wrap(mote.y() - cameraDy * shift, height);
                s.setColor(mote.color());
                double size = mote.size() * (0.7 + mote.depth() * 0.7);
                s.fillOval((int)Math.round(x - size * 0.5), (int)Math.round(y - size * 0.5),
                        Math.max(1, (int)Math.ceil(size)), Math.max(1, (int)Math.ceil(size)));
            }
            s.dispose();
        }
    }
}
