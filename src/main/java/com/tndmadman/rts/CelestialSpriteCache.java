package com.tndmadman.rts;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.geom.Ellipse2D;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

final class CelestialSpriteCache {
    static final int IMAGE_SIZE = 256;
    private static final int MAX_ENTRIES = 192;
    private static final Map<Key, Sprite> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, Sprite> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private CelestialSpriteCache() { }

    static Sprite sprite(CelestialVisualDefinition visual, long detailSeed) {
        if (visual == null) return null;
        Key key = new Key(visual, detailSeed);
        synchronized (CACHE) {
            Sprite found = CACHE.get(key);
            if (found != null) return found;
            Sprite generated = generate(visual, detailSeed);
            CACHE.put(key, generated);
            return generated;
        }
    }

    static int cacheSize() { synchronized (CACHE) { return CACHE.size(); } }
    static int maxEntries() { return MAX_ENTRIES; }

    static long pixelHash(CelestialVisualDefinition visual, long detailSeed) {
        Sprite sprite = sprite(visual, detailSeed);
        long hash = 0xcbf29ce484222325L;
        for (int y = 0; y < IMAGE_SIZE; y += 7) {
            for (int x = 0; x < IMAGE_SIZE; x += 7) {
                hash ^= sprite.surface.getRGB(x, y);
                hash *= 0x100000001b3L;
                hash ^= sprite.emissive.getRGB(x, y);
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }

    private static Sprite generate(CelestialVisualDefinition visual, long seed) {
        BufferedImage surface = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
        BufferedImage emissive = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(seed ^ ((long)visual.id().hashCode() << 32) ^ visual.visualClass().ordinal());
        double phaseA = random.nextDouble() * Math.PI * 2;
        double phaseB = random.nextDouble() * Math.PI * 2;
        double phaseC = random.nextDouble() * Math.PI * 2;
        int center = IMAGE_SIZE / 2;
        double radius = IMAGE_SIZE * 0.465;

        for (int py = 0; py < IMAGE_SIZE; py++) {
            double ny = (py + 0.5 - center) / radius;
            for (int px = 0; px < IMAGE_SIZE; px++) {
                double nx = (px + 0.5 - center) / radius;
                double d2 = nx * nx + ny * ny;
                if (d2 > 1.03) continue;
                double edge = Math.max(0, Math.min(1, (1.03 - d2) / 0.055));
                int alpha = (int)Math.round(255 * edge);
                if (alpha <= 0) continue;
                double detail = detailNoise(nx, ny, seed, phaseA, phaseB, phaseC);
                Color color = surfaceColor(visual, nx, ny, detail, phaseA, phaseB);
                if (visual.hasClouds()) {
                    double cloud = cloudNoise(nx, ny, seed ^ 0x71A5C0DEL, phaseB);
                    double threshold = 1.0 - visual.cloudCoverage();
                    if (cloud > threshold) {
                        double amount = Math.min(0.62, (cloud - threshold) / Math.max(0.08, visual.cloudCoverage()) * 0.48 + 0.12);
                        color = mix(color, new Color(235, 240, 235), amount);
                    }
                }
                surface.setRGB(px, py, argb(alpha, color));

                if (visual.emissive()) {
                    Color glow = emissiveColor(visual, nx, ny, detail, seed);
                    if (glow != null) emissive.setRGB(px, py, argb((int)Math.round(225 * edge), glow));
                }
            }
        }

        decorate(surface, emissive, visual, random);
        return new Sprite(surface, emissive);
    }

    private static Color surfaceColor(CelestialVisualDefinition visual, double nx, double ny, double detail,
                                      double phaseA, double phaseB) {
        return switch (visual.visualClass()) {
            case STAR -> {
                double granule = 0.50 + 0.32 * detail + 0.13 * Math.sin(nx * 33 + phaseA) * Math.sin(ny * 28 + phaseB);
                yield mix(visual.secondary(), visual.primary(), clamp01(granule + 0.28));
            }
            case GAS_GIANT, ICE_GIANT -> {
                double bands = 0.5 + 0.34 * Math.sin(ny * 27 + phaseA + detail * 1.8)
                        + 0.12 * Math.sin(ny * 61 + phaseB);
                yield bands > 0.55 ? mix(visual.primary(), visual.accent(), 0.24 + 0.28 * detail)
                        : mix(visual.secondary(), visual.primary(), 0.28 + 0.30 * detail);
            }
            case TERRESTRIAL -> {
                double continent = detail + 0.18 * Math.sin(nx * 5 + phaseA) - 0.12 * Math.cos(ny * 7 + phaseB);
                if (continent > 0.54) yield mix(visual.secondary(), visual.accent(), clamp01((continent - 0.5) * 1.5));
                yield mix(visual.primary(), visual.secondary(), 0.08 + 0.12 * detail);
            }
            case DESERT -> {
                double dune = 0.5 + 0.28 * Math.sin(ny * 36 + nx * 5 + phaseA) + 0.22 * detail;
                yield mix(visual.secondary(), visual.primary(), clamp01(dune));
            }
            case ICE -> {
                double fracture = Math.abs(Math.sin((nx * 19 + ny * 13 + detail * 4) + phaseA));
                yield fracture > 0.92 ? visual.accent() : mix(visual.secondary(), visual.primary(), 0.46 + 0.42 * detail);
            }
            case LAVA -> {
                double cracks = Math.abs(Math.sin(nx * 23 + detail * 8 + phaseA) * Math.cos(ny * 19 - detail * 6 + phaseB));
                yield cracks > 0.87 ? mix(visual.primary(), visual.accent(), 0.58) : mix(visual.secondary(), visual.primary(), 0.30 + 0.35 * detail);
            }
            case TOXIC -> mix(visual.secondary(), visual.primary(), 0.28 + 0.56 * detail);
            case INDUSTRIAL -> {
                int panelX = Math.floorMod((int)Math.floor((nx + 1) * 14), 2);
                int panelY = Math.floorMod((int)Math.floor((ny + 1) * 12), 2);
                double panel = panelX == panelY ? 0.60 : 0.32;
                yield mix(visual.secondary(), visual.primary(), panel + detail * 0.14);
            }
            case DEAD, ROCKY -> mix(visual.secondary(), visual.primary(), 0.30 + 0.56 * detail);
        };
    }

    private static Color emissiveColor(CelestialVisualDefinition visual, double nx, double ny, double detail, long seed) {
        if (visual.visualClass() == CelestialVisualClass.LAVA) {
            double cracks = Math.abs(Math.sin(nx * 23 + detail * 8 + seed * 0.000001)
                    * Math.cos(ny * 19 - detail * 6));
            return cracks > 0.90 ? visual.accent() : null;
        }
        if (visual.visualClass() == CelestialVisualClass.INDUSTRIAL) {
            long cell = hash(seed, (int)Math.floor((nx + 1) * 32), (int)Math.floor((ny + 1) * 32));
            return (cell & 0x3F) == 0 ? visual.accent() : null;
        }
        if (visual.visualClass() == CelestialVisualClass.STAR) return mix(visual.primary(), Color.WHITE, 0.20 + detail * 0.32);
        return null;
    }

    private static void decorate(BufferedImage surface, BufferedImage emissive, CelestialVisualDefinition visual, Random random) {
        Graphics2D g = surface.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double r = IMAGE_SIZE * 0.465;
        double c = IMAGE_SIZE / 2.0;
        g.clip(new Ellipse2D.Double(c - r, c - r, r * 2, r * 2));
        if (visual.visualClass() == CelestialVisualClass.ROCKY || visual.visualClass() == CelestialVisualClass.DEAD
                || visual.visualClass() == CelestialVisualClass.ICE || visual.visualClass() == CelestialVisualClass.DESERT) {
            int craters = visual.visualClass() == CelestialVisualClass.DEAD ? 24 : 12;
            for (int i = 0; i < craters; i++) {
                double a = random.nextDouble() * Math.PI * 2;
                double distance = Math.sqrt(random.nextDouble()) * r * 0.72;
                double size = r * (0.035 + random.nextDouble() * (visual.visualClass() == CelestialVisualClass.DEAD ? 0.12 : 0.07));
                double x = c + Math.cos(a) * distance;
                double y = c + Math.sin(a) * distance;
                g.setColor(withAlpha(visual.secondary(), 90));
                g.fillOval((int)(x - size), (int)(y - size), (int)(size * 2), (int)(size * 1.45));
                g.setColor(withAlpha(visual.accent(), 65));
                g.drawOval((int)(x - size), (int)(y - size), (int)(size * 2), (int)(size * 1.45));
            }
        }
        if (visual.visualClass() == CelestialVisualClass.GAS_GIANT || visual.visualClass() == CelestialVisualClass.ICE_GIANT) {
            int stormW = (int)Math.round(r * (0.34 + random.nextDouble() * 0.18));
            int stormH = Math.max(4, (int)Math.round(stormW * 0.42));
            int stormX = (int)Math.round(c + (random.nextDouble() - 0.5) * r * 0.55 - stormW / 2.0);
            int stormY = (int)Math.round(c + (random.nextDouble() - 0.5) * r * 0.50 - stormH / 2.0);
            g.setColor(withAlpha(visual.accent(), 105));
            g.fillOval(stormX, stormY, stormW, stormH);
            g.setColor(withAlpha(visual.secondary(), 100));
            g.setStroke(new BasicStroke(2f));
            g.drawOval(stormX + 3, stormY + 2, Math.max(2, stormW - 6), Math.max(2, stormH - 4));
        }
        if (visual.visualClass() == CelestialVisualClass.STAR) {
            g.setColor(withAlpha(Color.WHITE, 42));
            for (int i = 0; i < 18; i++) {
                int size = 2 + random.nextInt(6);
                int x = (int)Math.round(c + (random.nextDouble() - 0.5) * r * 1.45);
                int y = (int)Math.round(c + (random.nextDouble() - 0.5) * r * 1.45);
                g.fillOval(x - size, y - size, size * 2, size * 2);
            }
        }
        g.dispose();

        if (visual.visualClass() == CelestialVisualClass.INDUSTRIAL) {
            Graphics2D e = emissive.createGraphics();
            e.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            e.setColor(withAlpha(visual.accent(), 210));
            for (int i = 0; i < 30; i++) {
                double a = random.nextDouble() * Math.PI * 2;
                double distance = Math.sqrt(random.nextDouble()) * r * 0.76;
                int x = (int)Math.round(c + Math.cos(a) * distance);
                int y = (int)Math.round(c + Math.sin(a) * distance);
                int size = 1 + random.nextInt(2);
                e.fillRect(x, y, size, size);
            }
            e.dispose();
        }
    }

    private static double detailNoise(double x, double y, long seed, double a, double b, double c) {
        double broad = 0.5 + 0.25 * Math.sin(x * 5.7 + a) * Math.cos(y * 6.3 + b);
        double medium = 0.5 + 0.25 * Math.sin((x + y) * 14.2 + c) + 0.13 * Math.cos((x - y) * 21.7 + a);
        int gx = (int)Math.floor((x + 1.2) * 96);
        int gy = (int)Math.floor((y + 1.2) * 96);
        double grain = ((hash(seed, gx, gy) >>> 11) & 0xFFFF) / 65535.0;
        return clamp01(broad * 0.42 + medium * 0.36 + grain * 0.22);
    }

    private static double cloudNoise(double x, double y, long seed, double phase) {
        double bands = 0.5 + 0.25 * Math.sin(y * 17 + x * 3.5 + phase);
        int gx = (int)Math.floor((x + 1.3) * 54);
        int gy = (int)Math.floor((y + 1.3) * 54);
        double grain = ((hash(seed, gx, gy) >>> 9) & 0xFFFF) / 65535.0;
        return clamp01(bands * 0.64 + grain * 0.36);
    }

    private static long hash(long seed, int x, int y) {
        long z = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL);
        z ^= z >>> 30;
        z *= 0xBF58476D1CE4E5B9L;
        z ^= z >>> 27;
        z *= 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static int argb(int alpha, Color color) {
        return (Math.max(0, Math.min(255, alpha)) << 24)
                | (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
    }

    private static Color mix(Color a, Color b, double amount) {
        double t = clamp01(amount);
        return new Color(
                (int)Math.round(a.getRed() * (1 - t) + b.getRed() * t),
                (int)Math.round(a.getGreen() * (1 - t) + b.getGreen() * t),
                (int)Math.round(a.getBlue() * (1 - t) + b.getBlue() * t));
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static double clamp01(double value) { return Math.max(0, Math.min(1, value)); }

    record Sprite(BufferedImage surface, BufferedImage emissive) { }

    private record Key(CelestialVisualDefinition visual, long detailSeed) { }
}
