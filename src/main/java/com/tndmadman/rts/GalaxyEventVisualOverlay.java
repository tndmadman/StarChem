package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Screen-space, FOW-safe visual treatment for discovered environmental events. */
final class GalaxyEventVisualOverlay {
    private GalaxyEventVisualOverlay() { }

    static void draw(Graphics2D source, World world, int width, int height) {
        if (source == null || world == null || width <= 0 || height <= 0) return;
        List<GalaxyEventView> views = visibleCurrentSystemEvents(world);
        if (views.isEmpty()) return;

        Graphics2D g = (Graphics2D) source.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double seconds = System.nanoTime() / 1_000_000_000.0;
        int bannerRow = 0;
        for (GalaxyEventView view : views) {
            GalaxyEventVisualStyle style = GalaxyEventVisualCatalog.visual(view.definitionId());
            if (!style.enabled() || style.effectArea() != EventVisualArea.SYSTEM) continue;
            drawTint(g, style, width, height, seconds, view.eventId());
            drawParticles(g, style, width, height, seconds, view.eventId());
            drawNoise(g, style, width, height, seconds, view.eventId());
            drawLightning(g, style, width, height, seconds, view.eventId());
            if (!style.bannerText().isBlank()) drawBanner(g, style, width, bannerRow++);
        }
        g.dispose();
    }

    private static List<GalaxyEventView> visibleCurrentSystemEvents(World world) {
        Map<String,GalaxyEventView> unique = new LinkedHashMap<>();
        for (GalaxyEventView view : GalaxyEventDirector.visibleViews(world)) {
            if (view != null && world.activeSystemId().equals(view.systemId())) unique.put(view.eventId(), view);
        }
        for (GalaxyEventView view : GalaxyEventExtensions.viewsFor(world, PlayerRegistry.localId())) {
            if (view != null && world.activeSystemId().equals(view.systemId())) unique.put(view.eventId(), view);
        }
        List<GalaxyEventView> out = new ArrayList<>(unique.values());
        out.sort(Comparator.comparing(GalaxyEventView::eventId));
        return out;
    }

    private static void drawTint(Graphics2D g, GalaxyEventVisualStyle style, int width, int height,
                                 double seconds, String eventId) {
        if (style.tintOpacity() <= 0) return;
        double pulse = pulse(style, seconds, eventId);
        int alpha = alpha(style.tintOpacity() * pulse);
        if (alpha <= 0) return;
        g.setColor(color(style.tintColorRgb(), alpha));
        g.fillRect(0, 0, width, height);
    }

    private static void drawParticles(Graphics2D g, GalaxyEventVisualStyle style, int width, int height,
                                      double seconds, String eventId) {
        int count = Math.min(256, Math.max(0, style.particleCount()));
        if (count == 0 || style.particleOpacity() <= 0) return;
        long seed = hash(eventId);
        double pulse = pulse(style, seconds, eventId);
        int alpha = alpha(style.particleOpacity() * Math.min(1.0, pulse));
        g.setColor(color(style.particleColorRgb(), alpha));
        for (int i = 0; i < count; i++) {
            long particleSeed = mix(seed + i * 0x9E3779B97F4A7C15L);
            double baseX = unit(particleSeed) * width;
            double baseY = unit(mix(particleSeed ^ 0xD1B54A32D192ED03L)) * height;
            double angle = unit(mix(particleSeed ^ 0x94D049BB133111EBL)) * Math.PI * 2.0;
            double velocityX = Math.cos(angle) * style.particleSpeed() + style.driftX();
            double velocityY = Math.sin(angle) * style.particleSpeed() + style.driftY();
            double x = wrap(baseX + seconds * velocityX, Math.max(1, width));
            double y = wrap(baseY + seconds * velocityY, Math.max(1, height));
            double sizeT = unit(mix(particleSeed ^ 0x632BE59BD9B4E019L));
            double size = style.particleSizeMin()
                    + (style.particleSizeMax() - style.particleSizeMin()) * sizeT;
            switch (style.particleType()) {
                case DUST -> g.fillOval((int)Math.round(x - size * 0.5), (int)Math.round(y - size * 0.5),
                        Math.max(1, (int)Math.round(size)), Math.max(1, (int)Math.round(size)));
                case SPARK -> {
                    int r = Math.max(1, (int)Math.round(size));
                    g.drawLine((int)x - r, (int)y, (int)x + r, (int)y);
                    g.drawLine((int)x, (int)y - r, (int)x, (int)y + r);
                }
                case STREAK -> {
                    double length = Math.max(3.0, size * 4.0);
                    double speed = Math.max(1.0, Math.hypot(velocityX, velocityY));
                    double dx = velocityX / speed * length;
                    double dy = velocityY / speed * length;
                    g.drawLine((int)Math.round(x - dx), (int)Math.round(y - dy),
                            (int)Math.round(x + dx), (int)Math.round(y + dy));
                }
            }
        }
    }

    private static void drawNoise(Graphics2D g, GalaxyEventVisualStyle style, int width, int height,
                                  double seconds, String eventId) {
        int count = Math.min(512, Math.max(0, style.noiseSamples()));
        if (count == 0 || style.noiseOpacity() <= 0) return;
        long frame = (long)Math.floor(seconds * 12.0);
        long seed = mix(hash(eventId) ^ frame);
        g.setColor(new Color(255, 255, 255, alpha(style.noiseOpacity())));
        for (int i = 0; i < count; i++) {
            long value = mix(seed + i * 0x9E3779B97F4A7C15L);
            int x = (int)Math.floor(unit(value) * width);
            int y = (int)Math.floor(unit(mix(value)) * height);
            g.fillRect(x, y, 1, 1);
        }
    }

    private static void drawLightning(Graphics2D g, GalaxyEventVisualStyle style, int width, int height,
                                      double seconds, String eventId) {
        if (style.lightningChancePerSecond() <= 0) return;
        long second = (long)Math.floor(seconds);
        long seed = mix(hash(eventId) ^ second);
        if (unit(seed) >= style.lightningChancePerSecond()) return;
        int segments = 7;
        double x = unit(mix(seed ^ 0xA0761D6478BD642FL)) * width;
        double y = -8;
        Path2D path = new Path2D.Double();
        path.moveTo(x, y);
        for (int i = 1; i <= segments; i++) {
            y = height * i / (double)segments;
            x += (unit(mix(seed + i * 0xE7037ED1A0B428DBL)) - 0.5) * width * 0.16;
            path.lineTo(x, y);
        }
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(color(style.lightningColorRgb(), 170));
        g.draw(path);
    }

    private static void drawBanner(Graphics2D g, GalaxyEventVisualStyle style, int width, int row) {
        String text = style.bannerText();
        Font old = g.getFont();
        g.setFont(old.deriveFont(Font.BOLD, 12f));
        int textWidth = g.getFontMetrics().stringWidth(text);
        int boxWidth = textWidth + 28;
        int x = Math.max(12, (width - boxWidth) / 2);
        int y = 154 + row * 30;
        g.setColor(new Color(0, 0, 0, 178));
        g.fillRoundRect(x, y, boxWidth, 24, 10, 10);
        g.setColor(color(style.bannerColorRgb(), 220));
        g.drawRoundRect(x, y, boxWidth, 24, 10, 10);
        g.setColor(color(style.bannerColorRgb(), 245));
        g.drawString(text, x + 14, y + 16);
        g.setFont(old);
    }

    private static double pulse(GalaxyEventVisualStyle style, double seconds, String eventId) {
        if (style.pulseSpeed() <= 0 || style.pulseIntensity() <= 0) return 1.0;
        double phase = unit(hash(eventId)) * Math.PI * 2.0;
        double wave = Math.sin(seconds * style.pulseSpeed() * Math.PI * 2.0 + phase);
        return Math.max(0, 1.0 + wave * style.pulseIntensity());
    }

    private static Color color(int rgb, int alpha) {
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, Math.max(0, Math.min(255, alpha)));
    }

    private static int alpha(double value) {
        return Math.max(0, Math.min(255, (int)Math.round(value * 255.0)));
    }

    private static double wrap(double value, double extent) {
        double out = value % extent;
        return out < 0 ? out + extent : out;
    }

    private static long hash(String value) {
        long hash = 0xcbf29ce484222325L;
        String safe = value == null ? "" : value;
        for (int i = 0; i < safe.length(); i++) {
            hash ^= safe.charAt(i);
            hash *= 0x100000001b3L;
        }
        return mix(hash);
    }

    private static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        value ^= value >>> 31;
        return value;
    }

    private static double unit(long value) {
        return (value >>> 11) * 0x1.0p-53;
    }
}
